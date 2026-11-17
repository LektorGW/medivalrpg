// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.protection.ProtectionService;

/**
 * Country standards in the game:
 * <ul>
 *   <li><b>A</b> — a vanilla banner whose base colour and up to six pattern layers approximate the approved flag
 *   ({@link BannerFit}); placeable, usable on a shield and by Armia (bridge {@code KrajePlugin#flagBanner});</li>
 *   <li><b>B</b> — the item "Sztandar kraju" with the exact approved flag (resource-pack model {@code kraje:sztandar/…},
 *   {@link StandardAssets}); held in hand and placed as a decoration: an item display plus an interaction hitbox
 *   (neither blocks walking), saved with the chunk, Shift + right click takes it back.</li>
 * </ul>
 * Price: materials from the player's inventory plus a fee from the country treasury (withdraw = a move to
 * {@code world:payments} inside the closed pool), per-country daily limit. Main thread only.
 */
public final class StandardService implements Listener {

    /** Bone-free default tint for a placed standard of a country that no longer exists. */
    private static final int ORPHAN_TINT = 0x888888;

    private final KrajePlugin plugin;
    private final FlagService flags;
    private final NamespacedKey keyBanner;
    private final NamespacedKey keyItem;
    private final NamespacedKey keyPlaced;
    private final NamespacedKey keyPartner;
    private final NamespacedKey keyPlacer;
    private final Path logFile;
    private StandardRules.Settings settings = StandardRules.Settings.DEFAULT;
    private StandardRules.Log log = new StandardRules.Log();
    private final Object saveLock = new Object();

    public StandardService(final KrajePlugin plugin, final FlagService flags) {
        this.plugin = plugin;
        this.flags = flags;
        this.keyBanner = new NamespacedKey(plugin, "sztandar-kraju");
        this.keyItem = new NamespacedKey(plugin, "sztandar-przedmiot");
        this.keyPlaced = new NamespacedKey(plugin, "sztandar-postawiony");
        this.keyPartner = new NamespacedKey(plugin, "sztandar-para");
        this.keyPlacer = new NamespacedKey(plugin, "sztandar-stawial");
        this.logFile = plugin.getDataFolder().toPath().resolve("flags").resolve("standards.json");
    }

    public void enable() {
        this.settings = StandardRules.Settings.from(this.plugin.getConfig().getConfigurationSection("flags.standards"));
        try {
            this.log = Files.isRegularFile(this.logFile)
                ? StandardRules.Log.fromJson(Files.readString(this.logFile, StandardCharsets.UTF_8)) : new StandardRules.Log();
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Sztandary: nie można odczytać " + this.logFile, e);
        }
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void disable() {
        this.writeLog(this.log.toJson());
    }

    public StandardRules.Settings settings() {
        return this.settings;
    }

    // ------------------------------------------------------------------ issuing

    private String today() {
        return StandardRules.Log.day(System.currentTimeMillis(), this.settings.zone());
    }

    /** Remaining standards of this kind for the country today. */
    public int remaining(final Country c, final StandardRules.Kind kind) {
        return Math.max(0, this.settings.cost(kind).dailyLimit() - this.log.used(this.today(), c.id(), kind));
    }

    private Map<String, Integer> inventoryCounts(final Player p) {
        final Map<String, Integer> have = new LinkedHashMap<>();
        for (final ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && !it.getType().isAir() && !this.isStandard(it) && !it.hasItemMeta()) {
                have.merge(it.getType().name(), it.getAmount(), Integer::sum);
            }
        }
        return have;
    }

    private String refusal(final Player p, final Country c, final StandardRules.Kind kind) {
        final Member m = c.member(p.getUniqueId());
        final StandardRules.Cost cost = this.settings.cost(kind);
        final var treasury = this.plugin.treasury();
        return StandardRules.refusal(kind, cost, this.settings.enabled() && this.flags.enabled(), m == null ? null : m.rank(), c.npc(),
            this.flags.settings().uploadRank(), this.log.used(this.today(), c.id(), kind),
            StandardRules.missing(cost.materials(), this.inventoryCounts(p)), treasury.active(), treasury.balance(c), treasury::format);
    }

    /** Takes the price: treasury fee first (closed pool), then the materials. Re-checked right before. */
    private void pay(final Player p, final Country c, final StandardRules.Kind kind) {
        final String why = this.refusal(p, c, kind);
        if (why != null) {
            throw new IllegalArgumentException(why);
        }
        final StandardRules.Cost cost = this.settings.cost(kind);
        try {
            this.plugin.treasury().charge(c, cost.treasuryCost(), kind == StandardRules.Kind.BANNER ? "sztandar" : "sztandar kraju (przedmiot)",
                p.getName());
        } catch (final RuntimeException e) {
            throw new IllegalArgumentException("Nie udało się pobrać opłaty ze skarbca kraju.");
        }
        final ItemStack[] contents = p.getInventory().getStorageContents();
        for (final Map.Entry<String, Integer> need : cost.materials().entrySet()) {
            int left = need.getValue();
            for (int i = 0; i < contents.length && left > 0; i++) {
                final ItemStack it = contents[i];
                if (it == null || it.getType().isAir() || it.hasItemMeta() || this.isStandard(it)
                    || !StandardRules.matches(need.getKey(), it.getType().name())) {
                    continue;
                }
                final int take = Math.min(left, it.getAmount());
                it.setAmount(it.getAmount() - take);
                contents[i] = it.getAmount() <= 0 ? null : it;
                left -= take;
            }
        }
        p.getInventory().setStorageContents(contents);
        this.log.add(this.today(), c.id(), kind);
        this.saveLog();
        this.plugin.getLogger().info("Sztandary: " + p.getName() + " odebrał " + (kind == StandardRules.Kind.BANNER ? "sztandar" : "przedmiot „Sztandar kraju”")
            + " kraju " + c.name() + " (opłata " + this.plugin.treasury().format(cost.treasuryCost()) + " ze skarbca, materiały: "
            + StandardRules.describe(cost.materials()) + ").");
    }

    private static void give(final Player p, final ItemStack item) {
        for (final ItemStack rest : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    /**
     * {@code /kraj flaga sztandar} (A). The pattern is computed off the main thread the first time; everything is checked
     * again when it is ready. {@code free} = moderator/admin issue for any country (no price, no limit).
     */
    public void issueBanner(final Player p, final Country c, final boolean free) {
        if (!free) {
            final String why = this.refusal(p, c, StandardRules.Kind.BANNER);
            if (why != null) {
                throw new IllegalArgumentException(why);
            }
        }
        final BannerFit.Design ready = this.flags.designNow(c);
        if (ready == null) {
            this.flags.line(p, "Obliczam wzór sztandaru z flagi kraju…");
        }
        final UUID countryId = c.id();
        final UUID playerId = p.getUniqueId();
        this.flags.design(c).whenComplete((design, error) -> {
            final Player now = Bukkit.getPlayer(playerId);
            final Country country = this.plugin.manager().get(countryId);
            if (now == null || country == null) {
                return;
            }
            if (error != null || design == null) {
                this.flags.error(now, "Nie udało się obliczyć wzoru sztandaru.");
                return;
            }
            try {
                if (!free) {
                    this.pay(now, country, StandardRules.Kind.BANNER);
                }
                give(now, this.bannerItem(country, design));
                this.flags.ok(now, "Otrzymujesz sztandar kraju " + country.name() + " (" + design.describe() + "). Postaw go, przybij do tarczy "
                    + "w stole rzemieślniczym albo powieś na murach." + (free ? "" : " Dziś zostało: " + this.remaining(country, StandardRules.Kind.BANNER) + "."));
            } catch (final IllegalArgumentException e) {
                this.flags.error(now, e.getMessage());
            }
        });
    }

    /** {@code /kraj flaga przedmiot} (B). */
    public void issueItem(final Player p, final Country c, final boolean free) {
        if (this.flags.renderer() == BannerRenderer.NONE) {
            throw new IllegalArgumentException("Na serwerze nie ma BetterModel — przedmiot z obrazkiem flagi jest niedostępny.");
        }
        if (!free) {
            this.pay(p, c, StandardRules.Kind.ITEM);
        }
        give(p, this.standardItem(c));
        final boolean own = this.ownModel(c) != null;
        this.flags.ok(p, "Otrzymujesz przedmiot „Sztandar kraju” " + c.name() + (own ? " z flagą kraju" : " (flaga domyślna w kolorze kraju"
            + (this.flags.book().current(c.id()) != null ? " — własna pojawi się po przebudowie paczki zasobów)" : ")"))
            + ". PPM na bloku stawia go, Shift + PPM na postawionym zabiera." + (free ? "" : " Dziś zostało: " + this.remaining(c, StandardRules.Kind.ITEM) + "."));
    }

    // ------------------------------------------------------------------ A: vanilla banner

    public ItemStack bannerItem(final Country c, final BannerFit.Design d) {
        final ItemStack item = new ItemStack(Material.valueOf(d.base().name() + "_BANNER"));
        final ItemMeta raw = item.getItemMeta();
        if (raw instanceof BannerMeta meta) {
            for (final BannerFit.Layer l : d.layers()) {
                final PatternType type = io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.BANNER_PATTERN).get(NamespacedKey.minecraft(l.pattern()));
                if (type != null) {
                    meta.addPattern(new Pattern(DyeColor.valueOf(l.dye().name()), type));
                }
            }
            meta.customName(Component.text("Sztandar kraju " + c.name(), TextColor.color(c.color() & 0xFFFFFF))
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Przybliżony wzór flagi kraju (barwniki)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(this.keyBanner, PersistentDataType.STRING, c.id().toString());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A banner of the country if its pattern is ready (Armia bridge), otherwise null (the pattern is being computed). */
    public ItemStack bannerItemNow(final Country c) {
        final BannerFit.Design d = this.flags.designNow(c);
        return d == null ? null : this.bannerItem(c, d);
    }

    // ------------------------------------------------------------------ B: item with the exact flag

    boolean isStandard(final ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(this.keyItem, PersistentDataType.STRING);
    }

    private static UUID uuid(final String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private UUID standardCountry(final ItemStack it) {
        return it == null || !it.hasItemMeta() ? null
            : uuid(it.getItemMeta().getPersistentDataContainer().get(this.keyItem, PersistentDataType.STRING));
    }

    /** The 12-hex id of the country's own standard model when it is in the pack and live, else null. */
    String ownModel(final Country c) {
        final FlagService.Look look = this.flags.look(c);
        final String own = this.flags.settings().modelOf(c.id());
        return look != null && look.tint() < 0 && look.model().equals(own) ? own.substring(this.flags.settings().modelPrefix().length()) : null;
    }

    public ItemStack standardItem(final Country c) {
        final ItemStack item = new ItemStack(Material.WHITE_BANNER);
        final ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Sztandar kraju " + c.name(), NamedTextColor.GOLD));
        meta.lore(List.of(
            Component.text("Flaga kraju " + c.name(), TextColor.color(c.color() & 0xFFFFFF)).decoration(TextDecoration.ITALIC, false),
            Component.text("PPM na bloku — postaw", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Shift + PPM na postawionym — zabierz", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(this.keyItem, PersistentDataType.STRING, c.id().toString());
        item.setItemMeta(meta);
        this.applyLook(item, c.id());
        return item;
    }

    /** Points the item at the right model (own flag or the tinted default); true when it changed. */
    boolean applyLook(final ItemStack item, final UUID countryId) {
        final Country c = countryId == null ? null : this.plugin.manager().get(countryId);
        final String own = c == null ? null : this.ownModel(c);
        final Key model = Key.key(StandardAssets.itemModel(own != null ? own : StandardAssets.DEFAULT_ID));
        final Integer tint = own != null ? null : c == null ? ORPHAN_TINT : c.color() & 0xFFFFFF;
        final Key was = item.getData(DataComponentTypes.ITEM_MODEL);
        final CustomModelData cmd = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        final Integer wasTint = cmd == null || cmd.colors().isEmpty() ? null : cmd.colors().getFirst().asRGB();
        if (model.equals(was) && java.util.Objects.equals(tint, wasTint)) {
            return false;
        }
        item.setData(DataComponentTypes.ITEM_MODEL, model);
        if (tint == null) {
            item.unsetData(DataComponentTypes.CUSTOM_MODEL_DATA);
        } else {
            item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addColor(Color.fromRGB(tint)).build());
        }
        return true;
    }

    /** After a pack rebuild / flag change: items of online players and loaded placed standards follow the current look. */
    public void refreshAll() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            this.refreshInventory(p);
        }
        for (final World w : Bukkit.getWorlds()) {
            for (final ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                if (d.getPersistentDataContainer().has(this.keyPlaced, PersistentDataType.STRING)) {
                    this.refreshDisplay(d);
                }
            }
        }
    }

    private void refreshInventory(final Player p) {
        final ItemStack[] contents = p.getInventory().getContents();
        boolean changed = false;
        for (final ItemStack it : contents) {
            if (this.isStandard(it)) {
                changed |= this.applyLook(it, this.standardCountry(it));
            }
        }
        if (changed) {
            p.getInventory().setContents(contents);
        }
    }

    private void refreshDisplay(final ItemDisplay d) {
        final ItemStack it = d.getItemStack();
        if (this.isStandard(it) && this.applyLook(it, this.standardCountry(it))) {
            d.setItemStack(it);
        }
    }

    // ------------------------------------------------------------------ placing and taking back

    private int placedIn(final Chunk chunk) {
        int n = 0;
        for (final Entity e : chunk.getEntities()) {
            if (e instanceof ItemDisplay && e.getPersistentDataContainer().has(this.keyPlaced, PersistentDataType.STRING)) {
                n++;
            }
        }
        return n;
    }

    /** Placing goes through the real BlockPlaceEvent, so Kraje, WorldGuard and spawn protection decide first. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        final ItemStack hand = event.getItemInHand();
        if (!this.isStandard(hand)) {
            return;
        }
        event.setCancelled(true);
        final Player p = event.getPlayer();
        final Block block = event.getBlockPlaced();
        if (!block.getChunk().isEntitiesLoaded()) {
            // entities of this chunk are still loading: a display spawned now could be lost — nothing is taken
            this.flags.error(p, "Teren jeszcze się wczytuje — spróbuj postawić sztandar za chwilę.");
            return;
        }
        if (this.placedIn(block.getChunk()) >= this.settings.maxPerChunk()) {
            this.flags.error(p, "W tym chunku stoi już " + this.settings.maxPerChunk() + " sztandarów — więcej się nie zmieści.");
            return;
        }
        final ItemStack one = hand.clone();
        one.setAmount(1);
        this.place(block.getLocation().add(0.5, 0, 0.5), p.getLocation().getYaw(), this.standardCountry(one), p.getUniqueId(), one);
        if (p.getGameMode() != GameMode.CREATIVE) {
            final EquipmentSlot slot = event.getHand();
            final ItemStack inHand = p.getInventory().getItem(slot);
            if (this.isStandard(inHand)) {
                inHand.setAmount(inHand.getAmount() - 1);
                p.getInventory().setItem(slot, inHand.getAmount() <= 0 ? null : inHand);
            }
        }
    }

    /** Spawns a placed standard (display + hitbox) standing on {@code base} (bottom centre of a block). */
    public ItemDisplay place(final Location base, final float yaw, final UUID country, final UUID placer, final ItemStack item) {
        final World w = base.getWorld();
        final float facing = Math.round(yaw / 15f) * 15f;
        this.applyLook(item, country);
        final ItemDisplay display = w.spawn(base.clone().add(0, 1.5, 0), ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setRotation(facing, 0);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f()));
            d.setPersistent(true);
            final PersistentDataContainer pdc = d.getPersistentDataContainer();
            pdc.set(this.keyPlaced, PersistentDataType.STRING, country == null ? "" : country.toString());
            if (placer != null) {
                pdc.set(this.keyPlacer, PersistentDataType.STRING, placer.toString());
            }
        });
        final Interaction hitbox = w.spawn(base.clone(), Interaction.class, i -> {
            i.setInteractionWidth(0.5f);
            i.setInteractionHeight(3.0f);
            i.setResponsive(true);
            i.setPersistent(true);
            final PersistentDataContainer pdc = i.getPersistentDataContainer();
            pdc.set(this.keyPlaced, PersistentDataType.STRING, country == null ? "" : country.toString());
            pdc.set(this.keyPartner, PersistentDataType.STRING, display.getUniqueId().toString());
        });
        display.getPersistentDataContainer().set(this.keyPartner, PersistentDataType.STRING, hitbox.getUniqueId().toString());
        return display;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClickStandard(final PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction hitbox)
            || !hitbox.getPersistentDataContainer().has(this.keyPlaced, PersistentDataType.STRING)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player p = event.getPlayer();
        final Entity partner = Bukkit.getEntity(uuidOr(hitbox.getPersistentDataContainer().get(this.keyPartner, PersistentDataType.STRING)));
        final UUID countryId = uuid(hitbox.getPersistentDataContainer().get(this.keyPlaced, PersistentDataType.STRING));
        final Country c = countryId == null ? null : this.plugin.manager().get(countryId);
        if (!p.isSneaking()) {
            this.flags.line(p, "Sztandar kraju " + (c == null ? "(kraj nie istnieje)" : c.name()) + ". Shift + PPM — zabierz.");
            return;
        }
        if (!this.mayTake(p, hitbox, partner, countryId)) {
            this.flags.error(p, "Ten sztandar może zabrać tylko ten, kto go postawił, obywatel jego kraju albo gospodarz tej ziemi.");
            return;
        }
        ItemStack item = partner instanceof ItemDisplay d ? d.getItemStack() : null;
        if (partner != null) {
            partner.remove();
        }
        hitbox.remove();
        if (this.isStandard(item)) {
            this.applyLook(item, countryId);
            give(p, item);
        }
        this.flags.ok(p, "Zabrano sztandar.");
    }

    private static UUID uuidOr(final String raw) {
        final UUID u = uuid(raw);
        return u == null ? new UUID(0, 0) : u;
    }

    private boolean mayTake(final Player p, final Interaction hitbox, final Entity display, final UUID country) {
        if (p.hasPermission("kraje.admin")) {
            return true;
        }
        if (display != null && p.getUniqueId().toString().equals(display.getPersistentDataContainer().get(this.keyPlacer, PersistentDataType.STRING))) {
            return true;
        }
        if (country != null && country.equals(this.plugin.manager().countryOf(p.getUniqueId()) == null ? null
            : this.plugin.manager().countryOf(p.getUniqueId()).id())) {
            return true;
        }
        final UUID owner = this.plugin.manager().ownerAt(hitbox.getLocation());
        return owner != null && this.plugin.protection().check(p, hitbox.getLocation(), ProtectionService.Flag.BLOCK_BREAK);
    }

    // ------------------------------------------------------------------ housekeeping

    /** Loaded chunk: placed standards follow the current look; a half (display without hitbox or the reverse) is removed. */
    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        final Set<UUID> here = new HashSet<>();
        final List<Entity> ours = new ArrayList<>();
        for (final Entity e : event.getEntities()) {
            here.add(e.getUniqueId());
            if (e.getPersistentDataContainer().has(this.keyPlaced, PersistentDataType.STRING)) {
                ours.add(e);
            }
        }
        for (final Entity e : ours) {
            final UUID partner = uuid(e.getPersistentDataContainer().get(this.keyPartner, PersistentDataType.STRING));
            final boolean whole = partner != null && (here.contains(partner) || Bukkit.getEntity(partner) != null);
            if (!whole) {
                if (e instanceof ItemDisplay d && this.isStandard(d.getItemStack())) {
                    e.getWorld().dropItemNaturally(e.getLocation().subtract(0, 1.5, 0), d.getItemStack());
                }
                e.remove();
                continue;
            }
            if (e instanceof ItemDisplay d) {
                this.refreshDisplay(d);
            }
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline()) {
                this.refreshInventory(p);
            }
        }, 5L);
    }

    /** The B item is a banner underneath: no shields, copies or loom patterns from it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(final PrepareItemCraftEvent event) {
        for (final ItemStack it : event.getInventory().getMatrix()) {
            if (this.isStandard(it)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(final CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter) {
            for (final ItemStack it : crafter.getInventory().getContents()) {
                if (this.isStandard(it)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLoom(final io.papermc.paper.event.player.PlayerLoomPatternSelectEvent event) {
        if (this.isStandard(event.getLoomInventory().getItem(0))) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ persistence

    private void saveLog() {
        final String json = this.log.toJson();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> this.writeLog(json));
    }

    private void writeLog(final String json) {
        synchronized (this.saveLock) {
            try {
                Files.createDirectories(this.logFile.getParent());
                final Path tmp = this.logFile.resolveSibling("standards.json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                FlagStore.move(tmp, this.logFile);
            } catch (final IOException e) {
                this.plugin.getLogger().log(Level.WARNING, "Sztandary: nie można zapisać " + this.logFile, e);
            }
        }
    }

    /** Counts for diagnostics ({@code /kraj flaga admin status}). */
    public Map<String, Integer> placedLoaded() {
        final Map<String, Integer> out = new HashMap<>();
        for (final World w : Bukkit.getWorlds()) {
            for (final ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                if (d.getPersistentDataContainer().has(this.keyPlaced, PersistentDataType.STRING)) {
                    out.merge(w.getName(), 1, Integer::sum);
                }
            }
        }
        return out;
    }

    boolean isPlaced(final Entity e) {
        return e.getPersistentDataContainer().has(this.keyPlaced, PersistentDataType.STRING);
    }
}
