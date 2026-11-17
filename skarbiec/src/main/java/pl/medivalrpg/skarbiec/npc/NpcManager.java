// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.config.Messages;
import pl.medivalrpg.skarbiec.gui.BankGui;
import pl.medivalrpg.skarbiec.gui.Gui;
import pl.medivalrpg.skarbiec.gui.MerchantGui;

/**
 * Banker and merchant NPCs. Each NPC is made of two entities tagged with {@code skarbiec:npc = <registry id>}:
 * <ul>
 *   <li>the body: a {@link Mannequin} (no AI), immovable, invulnerable and persistent;</li>
 *   <li>the hitbox: an invisible, responsive {@link Interaction} (0.8 × 2 blocks) on the same spot. It is
 *       <b>not</b> persistent and is created again whenever the chunk loads. The client always sends clicks on
 *       an Interaction, which a Mannequin does not reliably get.</li>
 * </ul>
 * Right click (main hand) or left click on either entity opens the window, at most once per few ticks.
 * The registry ({@code npcs.json}) is the source of truth. On start and whenever chunk entities load, tagged
 * entities that are not in the registry, and duplicates, are removed. Missing bodies and hitboxes are spawned
 * again.
 */
public final class NpcManager implements Listener {

    private static final String ROLE_HITBOX = "hitbox";
    private static final int OPEN_COOLDOWN_TICKS = 5;

    private final SkarbiecPlugin plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey roleKey;
    private final Path file;
    private final Map<UUID, NpcRecord> records = new LinkedHashMap<>();
    private final Map<UUID, Integer> lastOpen = new HashMap<>();
    private final Map<String, ResolvableProfile> nickProfiles = new HashMap<>();
    private BukkitTask lookTask;
    private BukkitTask checkTask;

    public NpcManager(final SkarbiecPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "npc");
        this.typeKey = new NamespacedKey(plugin, "npc_type");
        this.roleKey = new NamespacedKey(plugin, "npc_role");
        this.file = plugin.getDataFolder().toPath().resolve("npcs.json");
    }

    public static boolean validSkin(final String skin) {
        return NpcSkins.isNick(skin);
    }

    // ------------------------------------------------------------------ persistence

    public void load() throws IOException {
        final JsonElement root = this.plugin.files().read(this.file);
        if (root == null) {
            return;
        }
        boolean migrated = false;
        for (final JsonElement e : root.getAsJsonArray()) {
            final NpcRecord r = NpcRecord.fromJson(e.getAsJsonObject());
            // NPCs without a nick skin get a stable random default skin (also existing ones from older versions).
            final UUID look = NpcSkins.appearance(r.skin, r.appearance, UUID::randomUUID);
            if (look != r.appearance) {
                r.appearance = look;
                migrated = true;
            }
            this.records.put(r.id, r);
        }
        if (migrated) {
            this.save();
        }
        this.plugin.getLogger().info("NPC Skarbca: " + this.records.size());
    }

    private void save() {
        final JsonArray arr = new JsonArray();
        for (final NpcRecord r : this.records.values()) {
            arr.add(r.toJson());
        }
        this.plugin.files().writeAsync(this.file, arr);
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        for (final World w : Bukkit.getWorlds()) {
            for (final Entity e : w.getEntities()) {
                if (e instanceof Mannequin || e instanceof Interaction) {
                    this.adopt(e);
                }
            }
        }
        this.respawnMissing(null);
        this.checkTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> this.respawnMissing(null), 100L, 100L);
        this.lookTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::lookAtPlayers, 10L, 10L);
    }

    public void stop() {
        if (this.lookTask != null) {
            this.lookTask.cancel();
        }
        if (this.checkTask != null) {
            this.checkTask.cancel();
        }
        // Hitboxes are not persistent anyway; remove them now so a reload never leaves extra ones behind.
        for (final NpcRecord r : this.records.values()) {
            final Entity h = this.hitbox(r);
            if (h != null) {
                h.remove();
            }
            r.hitbox = null;
        }
    }

    // ------------------------------------------------------------------ management

    public Collection<NpcRecord> all() {
        return List.copyOf(this.records.values());
    }

    public NpcRecord create(final NpcType type, final String name, final String skin, final Location at) {
        return this.create(type, name, skin, at, null);
    }

    /** Creates an NPC; {@code merchantType} only for merchants ({@code null} = default type). */
    public NpcRecord create(final NpcType type, final String name, final String skin, final Location at, final String merchantType) {
        final NpcRecord r = new NpcRecord(UUID.randomUUID(), type, name, skin == null ? "" : skin, at, null);
        r.merchantType = type == NpcType.MERCHANT ? merchantType : null;
        r.appearance = NpcSkins.appearance(r.skin, null, UUID::randomUUID);
        this.records.put(r.id, r);
        this.spawn(r);
        this.save();
        return r;
    }

    public void remove(final NpcRecord r) {
        this.records.remove(r.id);
        final Entity e = this.entity(r);
        if (e != null) {
            e.remove();
        }
        final Entity h = this.hitbox(r);
        if (h != null) {
            h.remove();
        }
        this.save(); // a body in an unloaded chunk is removed as an orphan when the chunk loads
    }

    /**
     * Changes the skin: a Mojang nick, or {@link NpcSkins#RANDOM} for a NEW random default skin.
     *
     * @return false when the nick is invalid
     */
    public boolean setSkin(final NpcRecord r, final String skin) {
        if (NpcSkins.isRandomKeyword(skin)) {
            r.skin = "";
            r.appearance = UUID.randomUUID();
        } else if (NpcSkins.isNick(skin)) {
            r.skin = skin;
        } else {
            return false;
        }
        r.appearance = NpcSkins.appearance(r.skin, r.appearance, UUID::randomUUID);
        if (this.entity(r) instanceof Mannequin m) {
            this.applySkin(m, r);
        }
        this.save();
        return true;
    }

    /** Changes the merchant type of a merchant NPC (look and offer); false for a banker. */
    public boolean setMerchantType(final NpcRecord r, final String merchantType) {
        if (r.type != NpcType.MERCHANT) {
            return false;
        }
        r.merchantType = merchantType;
        if (this.entity(r) instanceof Mannequin m) {
            this.dress(m, r);
        }
        this.save();
        return true;
    }

    /** Places, moves or removes ({@code null}) the stall of a merchant NPC; false for a banker. */
    public boolean setStall(final NpcRecord r, final NpcRecord.Stall stall) {
        if (r.type != NpcType.MERCHANT) {
            return false;
        }
        r.stall = stall;
        this.save();
        return true;
    }

    /** Merchant type of a merchant NPC (stored, else the default type). */
    public pl.medivalrpg.skarbiec.merchant.MerchantType merchantType(final NpcRecord r) {
        return this.plugin.cfg().merchantType(r.merchantType);
    }

    /** Nearest NPC within {@code radius} of the player (any type when {@code type} is null). */
    public NpcRecord nearest(final Player player, final double radius, final NpcType type) {
        final Location at = player.getLocation();
        return this.records.values().stream()
            .filter(r -> type == null || r.type == type)
            .filter(r -> r.world.equals(at.getWorld().getName()))
            .filter(r -> {
                final Entity e = this.entity(r);
                final Location l = e != null ? e.getLocation() : r.location();
                return l != null && l.distanceSquared(at) <= radius * radius;
            })
            .min(Comparator.comparingDouble(r -> {
                final Location l = r.location();
                return l == null ? Double.MAX_VALUE : l.distanceSquared(at);
            }))
            .orElse(null);
    }

    public NpcRecord byIdPrefix(final String prefix) {
        final List<NpcRecord> hits = new ArrayList<>();
        for (final NpcRecord r : this.records.values()) {
            if (r.id.toString().startsWith(prefix.toLowerCase(Locale.ROOT))) {
                hits.add(r);
            }
        }
        return hits.size() == 1 ? hits.getFirst() : null;
    }

    /** Re-applies name, description and skin to every loaded NPC (after reload). */
    public void refreshAll() {
        this.nickProfiles.clear();
        for (final NpcRecord r : this.records.values()) {
            if (this.entity(r) instanceof Mannequin m) {
                this.dress(m, r);
            }
        }
    }

    private Entity entity(final NpcRecord r) {
        return valid(r.entity);
    }

    private Entity hitbox(final NpcRecord r) {
        return valid(r.hitbox);
    }

    private static Entity valid(final UUID id) {
        if (id == null) {
            return null;
        }
        final Entity e = Bukkit.getEntity(id);
        return e != null && e.isValid() ? e : null;
    }

    // ------------------------------------------------------------------ spawning

    private void spawn(final NpcRecord r) {
        final Location at = r.location();
        if (at == null) {
            return;
        }
        final Mannequin m = at.getWorld().spawn(at, Mannequin.class, e -> {
            final PersistentDataContainer pdc = e.getPersistentDataContainer();
            pdc.set(this.idKey, PersistentDataType.STRING, r.id.toString());
            pdc.set(this.typeKey, PersistentDataType.STRING, r.type.id());
            e.setImmovable(true);
            e.setInvulnerable(true);
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            e.setCollidable(false);
            e.setSilent(true);
            this.dress(e, r);
        });
        r.entity = m.getUniqueId();
        this.save();
        this.spawnHitbox(r);
        this.animate(m, r);
    }

    /** Bodies whose work animation Interakcje already accepted (it is stored on the entity, so once per body). */
    private final java.util.Set<UUID> animated = new java.util.HashSet<>();

    /**
     * Work animation through Interakcje (optional, reflection): the merchant invites, the banker rests. Retried by
     * {@link #respawnMissing} until Interakcje accept it (they may load later or be missing — then nothing happens).
     */
    private void animate(final Entity body, final NpcRecord r) {
        if (body == null || this.animated.contains(body.getUniqueId())) {
            return;
        }
        if (this.animated.size() > 4096) {
            this.animated.clear();
        }
        if (WorkAnim.play(body, r.type == NpcType.MERCHANT ? "zaproszenie" : "odpoczynek", true)) {
            this.animated.add(body.getUniqueId());
        }
    }

    private void spawnHitbox(final NpcRecord r) {
        final Location at = r.location();
        if (at == null) {
            return;
        }
        final Interaction box = at.getWorld().spawn(at, Interaction.class, e -> {
            final PersistentDataContainer pdc = e.getPersistentDataContainer();
            pdc.set(this.idKey, PersistentDataType.STRING, r.id.toString());
            pdc.set(this.typeKey, PersistentDataType.STRING, r.type.id());
            pdc.set(this.roleKey, PersistentDataType.STRING, ROLE_HITBOX);
            e.setInteractionWidth(0.8f);
            e.setInteractionHeight(2.0f);
            e.setResponsive(true);
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setSilent(true);
        });
        r.hitbox = box.getUniqueId();
    }

    private void dress(final Mannequin m, final NpcRecord r) {
        final Messages msg = this.plugin.messages();
        m.customName(Messages.mini().deserialize(r.name));
        m.setCustomNameVisible(true);
        if (r.type == NpcType.BANKER) {
            m.setDescription(msg.get("npc.description-banker"));
            m.getEquipment().setItemInMainHand(ItemStack.of(Material.GOLD_INGOT));
        } else {
            final pl.medivalrpg.skarbiec.merchant.MerchantType mt = this.merchantType(r);
            m.setDescription(msg.get("npc.description-merchant-type", Placeholder.parsed("type", mt.name())));
            m.getEquipment().setItemInMainHand(ItemStack.of(mt.icon()));
        }
        this.applySkin(m, r);
    }

    /**
     * Nick skin: a name-only (dynamic) profile at once, which clients resolve themselves, then the full textured
     * profile once the server has fetched it (cached per nick). Without a nick: a complete profile with the
     * record's random appearance UUID, a placeholder name that cannot be a real account, and no textures. The
     * client then shows the default skin chosen by that UUID.
     */
    private void applySkin(final Mannequin m, final NpcRecord r) {
        if (!NpcSkins.isNick(r.skin)) {
            m.setProfile(defaultSkinProfile(r.appearance == null ? r.id : r.appearance));
            return;
        }
        final String nick = r.skin.toLowerCase(Locale.ROOT);
        final ResolvableProfile cached = this.nickProfiles.get(nick);
        if (cached != null) {
            m.setProfile(cached);
            return;
        }
        final ResolvableProfile byName = ResolvableProfile.resolvableProfile().name(r.skin).build();
        m.setProfile(byName);
        final UUID entityId = m.getUniqueId();
        try {
            byName.resolve().whenComplete((profile, error) -> {
                if (error != null || profile == null || !profile.hasTextures()) {
                    return;
                }
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    final ResolvableProfile full = ResolvableProfile.resolvableProfile(profile);
                    this.nickProfiles.put(nick, full);
                    if (Bukkit.getEntity(entityId) instanceof Mannequin live && live.isValid()
                        && r.skin.equalsIgnoreCase(nick)) {
                        live.setProfile(full);
                    }
                });
            });
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.FINE, "Nie udało się pobrać skina " + r.skin, e);
        }
    }

    private static ResolvableProfile defaultSkinProfile(final UUID appearance) {
        try {
            return ResolvableProfile.resolvableProfile(Bukkit.createProfileExact(appearance, NpcSkins.placeholderName(appearance)));
        } catch (final RuntimeException e) {
            // Fallback: UUID only (the builder allows it). The client may look the UUID up, fail, and still use
            // the default skin for that UUID.
            return ResolvableProfile.resolvableProfile().uuid(appearance).build();
        }
    }

    // ------------------------------------------------------------------ orphan cleanup / respawn

    private boolean isHitbox(final Entity e) {
        return ROLE_HITBOX.equals(e.getPersistentDataContainer().get(this.roleKey, PersistentDataType.STRING));
    }

    /** Attaches or removes one tagged entity (body or hitbox). */
    private void adopt(final Entity e) {
        final String idText = e.getPersistentDataContainer().get(this.idKey, PersistentDataType.STRING);
        if (idText == null) {
            return;
        }
        NpcRecord r = null;
        try {
            r = this.records.get(UUID.fromString(idText));
        } catch (final IllegalArgumentException ignored) {
            // malformed id → orphan
        }
        final boolean hitbox = this.isHitbox(e);
        if (r == null) {
            this.plugin.getLogger().info("Usuwam osieroconego NPC Skarbca " + idText + (hitbox ? " (hitbox)" : "")
                + " w " + e.getLocation().toVector());
            e.remove();
            return;
        }
        final UUID known = hitbox ? r.hitbox : r.entity;
        if (!e.getUniqueId().equals(known)) {
            final Entity current = hitbox ? this.hitbox(r) : this.entity(r);
            if (current != null && current != e) {
                this.plugin.getLogger().info("Usuwam zdublowanego NPC Skarbca " + idText + (hitbox ? " (hitbox)" : ""));
                e.remove();
                return;
            }
            if (hitbox) {
                r.hitbox = e.getUniqueId();
            } else {
                r.entity = e.getUniqueId();
                this.save();
            }
        }
        if (e instanceof Mannequin m) {
            this.dress(m, r); // e.g. older NPCs without a skin get their random default look
        }
    }

    /** Spawns the bodies and hitboxes that are missing while their chunk, with its entities, is loaded. */
    private void respawnMissing(final Chunk only) {
        for (final NpcRecord r : List.copyOf(this.records.values())) {
            final World w = Bukkit.getWorld(r.world);
            if (w == null || (only != null && (only.getWorld() != w || only.getX() != r.chunkX() || only.getZ() != r.chunkZ()))) {
                continue;
            }
            if (!w.isChunkLoaded(r.chunkX(), r.chunkZ()) || !w.getChunkAt(r.chunkX(), r.chunkZ()).isEntitiesLoaded()) {
                continue;
            }
            final Entity existing = this.entity(r);
            if (existing == null) {
                this.spawn(r); // also spawns a hitbox and sets the work animation
            } else {
                if (this.hitbox(r) == null) {
                    this.spawnHitbox(r);
                }
                this.animate(existing, r);
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        for (final Entity e : event.getEntities()) {
            if (e instanceof Mannequin || e instanceof Interaction) {
                this.adopt(e);
            }
        }
        final Chunk chunk = event.getChunk();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (chunk.isLoaded()) {
                this.respawnMissing(chunk);
            }
        });
    }

    // ------------------------------------------------------------------ interaction

    private NpcRecord recordOf(final Entity e) {
        final String idText = e.getPersistentDataContainer().get(this.idKey, PersistentDataType.STRING);
        if (idText == null) {
            return null;
        }
        try {
            return this.records.get(UUID.fromString(idText));
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean isNpc(final Entity e) {
        return e.getPersistentDataContainer().has(this.idKey, PersistentDataType.STRING);
    }

    /**
     * Right click on the body or the hitbox. {@link PlayerInteractAtEntityEvent} shares this handler list, so
     * both kinds arrive here. Both are cancelled and both may open the window: deduplication is in {@link #open}.
     * LOWEST and not ignoring cancelled events: protection plugins cannot hide the click from us.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEntityEvent event) {
        final Entity clicked = event.getRightClicked();
        if (!this.isNpc(clicked)) {
            return;
        }
        this.debug(event, clicked, "hand=" + event.getHand() + ", anulowane przed Skarbcem=" + event.isCancelled());
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        this.open(event.getPlayer(), clicked, event instanceof PlayerInteractAtEntityEvent ? "PPM(at)" : "PPM");
    }

    /** Left click (Paper fires it before any attack logic, also for Interaction entities). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreAttack(final PrePlayerAttackEntityEvent event) {
        final Entity attacked = event.getAttacked();
        if (!this.isNpc(attacked)) {
            return;
        }
        this.debug(event, attacked, "anulowane przed Skarbcem=" + event.isCancelled());
        event.setCancelled(true);
        this.open(event.getPlayer(), attacked, "LPM");
    }

    /** Damage of any kind is cancelled; a player's hit (left click fallback) opens the window. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(final EntityDamageEvent event) {
        final Entity e = event.getEntity();
        if (!this.isNpc(e)) {
            return;
        }
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player player) {
            this.debug(event, e, "cios gracza " + player.getName());
            this.open(player, e, "LPM(cios)");
        }
    }

    /** Opens the NPC window, at most once per {@value #OPEN_COOLDOWN_TICKS} ticks and never over another window. */
    private void open(final Player player, final Entity clicked, final String how) {
        final NpcRecord r = this.recordOf(clicked);
        if (r == null) {
            return;
        }
        final int now = Bukkit.getCurrentTick();
        final Integer last = this.lastOpen.get(player.getUniqueId());
        if (last != null && now - last < OPEN_COOLDOWN_TICKS) {
            return;
        }
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Gui) {
            return;
        }
        this.lastOpen.put(player.getUniqueId(), now);
        if (this.plugin.cfg().debugNpcClicks) {
            this.plugin.getLogger().info("[debug-npc-clicks] " + player.getName() + " otwiera " + r.type.id() + " (" + how + ")");
        }
        if (r.type == NpcType.BANKER) {
            new BankGui(this.plugin, player).open(player);
        } else {
            final Component title = this.plugin.messages().get("gui.merchant.title",
                Placeholder.component("name", Messages.mini().deserialize(r.name)));
            new MerchantGui(this.plugin, player, title, this.merchantType(r).id()).open(player);
        }
    }

    // ------------------------------------------------------------------ diagnostics

    private void debug(final Event event, final Entity e, final String details) {
        if (!this.plugin.cfg().debugNpcClicks) {
            return;
        }
        this.plugin.getLogger().info("[debug-npc-clicks] " + event.getEventName() + " na " + e.getType()
            + (this.isHitbox(e) ? " (hitbox)" : "") + " " + e.getUniqueId().toString().substring(0, 8) + ": " + details);
    }

    /** Final state after all plugins, plus who else listens (the likely canceller) — only with debug on. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractMonitor(final PlayerInteractEntityEvent event) {
        this.debugFinal(event, event.getRightClicked(), PlayerInteractEntityEvent.getHandlerList());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreAttackMonitor(final PrePlayerAttackEntityEvent event) {
        this.debugFinal(event, event.getAttacked(), PrePlayerAttackEntityEvent.getHandlerList());
    }

    private void debugFinal(final Event event, final Entity e, final HandlerList handlers) {
        if (!this.plugin.cfg().debugNpcClicks || !this.isNpc(e)) {
            return;
        }
        final String others = java.util.Arrays.stream(handlers.getRegisteredListeners())
            .filter(l -> l.getPlugin() != this.plugin)
            .map(NpcManager::describe)
            .collect(Collectors.joining(", "));
        this.plugin.getLogger().info("[debug-npc-clicks] " + event.getEventName() + " końcowo anulowane="
            + ((Cancellable) event).isCancelled() + "; inne pluginy nasłuchujące: " + (others.isEmpty() ? "brak" : others));
    }

    private static String describe(final RegisteredListener l) {
        return l.getPlugin().getName() + "@" + l.getPriority() + (l.isIgnoringCancelled() ? "(ignoreCancelled)" : "");
    }

    // ------------------------------------------------------------------ cosmetics

    private void lookAtPlayers() {
        if (!this.plugin.cfg().npcLookAtPlayers) {
            return;
        }
        final double radius = this.plugin.cfg().npcLookRadius;
        for (final NpcRecord r : this.records.values()) {
            final Entity e = this.entity(r);
            if (e == null) {
                continue;
            }
            final Location eye = e.getLocation().add(0, 1.62, 0);
            Player nearest = null;
            double best = radius * radius;
            for (final Player p : e.getWorld().getPlayers()) {
                final double d = p.getEyeLocation().distanceSquared(eye);
                if (d <= best) {
                    best = d;
                    nearest = p;
                }
            }
            if (nearest == null) {
                if (Math.abs(e.getLocation().getYaw() - r.yaw) > 0.5f) {
                    e.setRotation(r.yaw, r.pitch);
                }
                continue;
            }
            final Vector dir = nearest.getEyeLocation().toVector().subtract(eye.toVector());
            final Location look = eye.clone().setDirection(dir);
            e.setRotation(look.getYaw(), look.getPitch());
        }
    }
}
