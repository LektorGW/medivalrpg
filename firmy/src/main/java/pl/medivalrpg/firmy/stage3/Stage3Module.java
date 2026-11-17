// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage3;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.firmy.FirmyPlugin;
import pl.medivalrpg.firmy.bridge.BukkitNotifier;
import pl.medivalrpg.firmy.command.FirmaCommand;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.ludnosc.api.LudnoscApi;

/**
 * Stage 3 in the game: {@code /firma warsztat|robotnik}, workers as Mannequins (not villagers: Ludnosc must never adopt
 * them back as citizens), production every 5 s on real chests, citizens from Ludnosc ({@code LudnoscApi.conscript} /
 * {@code returnCitizen}, or the optional unemployed-only {@code hireWorker} of a newer Ludnosc). Loaded only from a
 * stage-3 jar.
 */
public final class Stage3Module implements Listener {

    private static final long TICK_MS = 5_000L;

    private final FirmyPlugin plugin;
    private final FirmService service;
    private final Stage3Data data;
    private final Path file;
    private final KrajeApi kraje;
    private final Manufactures m;
    private final NamespacedKey workerKey;
    private final NamespacedKey shopKey;
    /** A worker body was spawned (its entity id changed) since the last save. */
    private boolean bodiesChanged;

    public Stage3Module(final FirmyPlugin plugin) throws IOException {
        this.plugin = plugin;
        this.service = plugin.service();
        this.file = plugin.getDataFolder().toPath().resolve("manufaktury.json");
        this.data = plugin.store().read(this.file, Stage3Data.class, Stage3Data::new);
        if (this.data.workshops == null) {
            this.data.workshops = new LinkedHashMap<>();
        }
        this.kraje = Bukkit.getServicesManager().load(KrajeApi.class);
        this.workerKey = new NamespacedKey(plugin, "robotnik");
        this.shopKey = new NamespacedKey(plugin, "warsztat");
        this.m = new Manufactures(this.service, this.data, new Ludnosc(), Manufactures.Settings.from(plugin.getConfig()), this::save);
        this.service.addExtension(this.m);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // save only what changed: an idle stage 3 must not rewrite manufaktury.json (fsync + .bak) every 5 s
            if (this.m.work(TICK_MS, this::loaded, this::box) | this.bodiesChanged) {
                this.bodiesChanged = false;
                this.save();
            }
        }, 100L, TICK_MS / 50L);
        Bukkit.getScheduler().runTaskLater(plugin, this::ensureBodies, 60L);
        plugin.onDisable(() -> plugin.store().writeNow(this.file, this.data));
        plugin.command().extend(this::commands,
            new String[] {"/firma warsztat", "warsztaty: manufaktura (Renesans) albo 1 warsztat cechu (przepisy średniowieczne)"},
            new String[] {"/firma warsztat zaloz <przepis>", "patrząc na stanowisko na ziemi siedziby"},
            new String[] {"/firma warsztat wejscie|wyjscie <warsztat>", "patrząc na skrzynię obok stanowiska"},
            new String[] {"/firma robotnik zatrudnij|zwolnij <warsztat>", "obywatel kraju siedziby za pensję dla obywateli"});
        plugin.panel().register("firmy.workshops", (actor, a) -> {
            final Firm f = this.service.ctx.firm(a.get("id")).orElseThrow(() -> new IllegalArgumentException("Nie ma takiej firmy."));
            final List<Map<String, Object>> out = new ArrayList<>();
            for (final Stage3Data.Workshop w : this.m.of(f)) {
                final Recipe r = this.m.settings().recipes.get(w.recipe);
                out.add(Map.of("id", w.id, "recipe", r == null ? w.recipe : r.name(), "workers", w.workers.size(), "status", w.status,
                    "produced", w.produced, "x", w.x, "y", w.y, "z", w.z));
            }
            return Map.of("workshops", out, "workers", this.m.workers(f), "limit", this.m.workerLimit(f));
        });
        plugin.getLogger().info("Firmy etap 3: warsztatów " + this.data.workshops.size());
    }

    public Manufactures manufactures() {
        return this.m;
    }

    private void save() {
        this.plugin.store().writeAsync(this.file, this.data);
    }

    // ------------------------------------------------------------------ world access

    private boolean loaded(final Stage3Data.Workshop w) {
        final World world = Bukkit.getWorld(w.world);
        return world != null && world.isChunkLoaded(w.x >> 4, w.z >> 4) && this.chunkOf(w.input) && this.chunkOf(w.output);
    }

    private boolean chunkOf(final String key) {
        final String[] k = key.split(":");
        final World world = Bukkit.getWorld(k[0]);
        return world != null && world.isChunkLoaded(Integer.parseInt(k[1]) >> 4, Integer.parseInt(k[3]) >> 4);
    }

    /** A chest as a production box (whole double chest), or null when it is gone. */
    private Production.Box box(final String key) {
        final String[] k = key.split(":");
        final World world = Bukkit.getWorld(k[0]);
        if (world == null) {
            return null;
        }
        final Block b = world.getBlockAt(Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3]));
        if (!(b.getState(false) instanceof Container c)) {
            return null;
        }
        final Inventory inv = c.getInventory();
        return new Production.Box() {
            @Override
            public int count(final String item) {
                final Material mat = Material.matchMaterial(item);
                int n = 0;
                for (final ItemStack it : inv.getStorageContents()) {
                    if (it != null && it.getType() == mat && plain(it)) {
                        n += it.getAmount();
                    }
                }
                return n;
            }

            @Override
            public boolean remove(final String item, final int amount) {
                if (this.count(item) < amount) {
                    return false;
                }
                final Material mat = Material.matchMaterial(item);
                int left = amount;
                final ItemStack[] contents = inv.getStorageContents();
                for (int i = 0; i < contents.length && left > 0; i++) {
                    final ItemStack it = contents[i];
                    if (it == null || it.getType() != mat || !plain(it)) {
                        continue;
                    }
                    final int take = Math.min(left, it.getAmount());
                    it.setAmount(it.getAmount() - take);
                    inv.setItem(i, it.getAmount() <= 0 ? null : it);
                    left -= take;
                }
                return true;
            }

            @Override
            public boolean fits(final String item, final int amount) {
                final Material mat = Material.matchMaterial(item);
                if (mat == null) {
                    return false;
                }
                int room = 0;
                for (final ItemStack it : inv.getStorageContents()) {
                    if (it == null || it.isEmpty()) {
                        room += mat.getMaxStackSize();
                    } else if (it.getType() == mat && plain(it)) {
                        room += mat.getMaxStackSize() - it.getAmount();
                    }
                    if (room >= amount) {
                        return true;
                    }
                }
                return room >= amount;
            }

            @Override
            public void add(final String item, final int amount) {
                final Map<Integer, ItemStack> rest = inv.addItem(new ItemStack(Material.matchMaterial(item), amount));
                if (!rest.isEmpty()) {
                    // cannot happen after fits(); never destroy: drop next to the chest
                    rest.values().forEach(x -> b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 1, 0.5), x));
                }
            }
        };
    }

    private static boolean plain(final ItemStack it) {
        return !it.hasItemMeta() || !it.getItemMeta().hasDisplayName() && !it.getItemMeta().hasEnchants();
    }

    // ------------------------------------------------------------------ workers (Ludnosc + Mannequins)

    private final class Ludnosc implements Manufactures.Labor {
        private String why = "";

        @Override
        public Optional<String> hire(final UUID country, final Stage3Data.Workshop w, final Stage3Data.Worker worker) {
            final LudnoscApi api = Bukkit.getServicesManager().load(LudnoscApi.class);
            final Plugin ludnosc = Bukkit.getPluginManager().getPlugin("Ludnosc");
            if (api == null || ludnosc == null || !ludnosc.isEnabled()) {
                this.why = "Robotników daje Ludność — plugin jest niedostępny.";
                return Optional.empty();
            }
            final Location near = new Location(Bukkit.getWorld(w.world), w.x + 0.5, w.y + 1, w.z + 0.5);
            String profession = null;
            // A newer Ludnosc may offer an unemployed-only hire (see firmy/README: proposed Ludnosc patch).
            try {
                final Method hire = ludnosc.getClass().getMethod("hireWorker", UUID.class, Location.class);
                final Object r = hire.invoke(ludnosc, country, near);
                if (r instanceof Optional<?> o) {
                    profession = o.map(String::valueOf).orElse(null);
                    if (profession == null) {
                        this.why = "W kraju siedziby nie ma teraz bezrobotnego dorosłego obywatela.";
                        return Optional.empty();
                    }
                }
            } catch (final NoSuchMethodException e) {
                try {
                    final Optional<LudnoscApi.Conscript> c = api.conscript(country, near);
                    if (c.isEmpty()) {
                        this.why = "W kraju siedziby nie ma teraz dorosłego obywatela do zatrudnienia (musi być w załadowanym terenie).";
                        return Optional.empty();
                    }
                    profession = c.get().profession();
                } catch (final RuntimeException x) {
                    this.why = "Ludność odmówiła zatrudnienia: " + x.getMessage();
                    return Optional.empty();
                }
            } catch (final ReflectiveOperationException | RuntimeException e) {
                this.why = "Ludność odmówiła zatrudnienia.";
                return Optional.empty();
            }
            worker.name = NAMES.get(Math.floorMod(worker.id.hashCode(), NAMES.size()));
            Stage3Module.this.spawn(w, worker);
            return Optional.of(profession);
        }

        @Override
        public void dismiss(final UUID country, final Stage3Data.Workshop w, final Stage3Data.Worker worker) {
            Stage3Module.this.despawn(worker);
            final LudnoscApi api = Bukkit.getServicesManager().load(LudnoscApi.class);
            final World world = Bukkit.getWorld(w.world);
            if (api != null && world != null) {
                final boolean back = api.returnCitizen(country, new Location(world, w.x + 0.5, w.y + 1, w.z + 0.5),
                    worker.profession == null ? "minecraft:none" : worker.profession);
                if (!back) {
                    Stage3Module.this.plugin.getLogger().warning("Ludność nie przyjęła z powrotem robotnika " + worker.name + " (kraj " + country + ")");
                }
            }
        }

        @Override
        public String why() {
            return this.why;
        }

        @Override
        public String citizensAccount(final UUID country) {
            return "ludnosc:obywatele_" + country;
        }
    }

    private static final List<String> NAMES = List.of("Bartosz", "Jadwiga", "Wojciech", "Małgorzata", "Szymon", "Agnieszka", "Mikołaj",
        "Katarzyna", "Stanisław", "Zofia", "Jakub", "Dorota");

    private void spawn(final Stage3Data.Workshop w, final Stage3Data.Worker worker) {
        final World world = Bukkit.getWorld(w.world);
        if (world == null || !world.isChunkLoaded(w.x >> 4, w.z >> 4)) {
            return;
        }
        final int index = w.workers.indexOf(worker) < 0 ? w.workers.size() : w.workers.indexOf(worker);
        final Location at = new Location(world, w.x + 0.5 + (index == 0 ? 1 : -1), w.y, w.z + 0.5);
        final Mannequin body = world.spawn(at, Mannequin.class, e -> {
            e.setImmovable(true);
            e.setInvulnerable(true);
            e.setPersistent(true);
            e.setSilent(true);
            e.setRemoveWhenFarAway(false);
            e.customName(BukkitNotifier.parse("<gold>" + worker.name + " <gray>(robotnik)"));
            e.setCustomNameVisible(false);
            e.setDescription(BukkitNotifier.parse("<gray>Robotnik manufaktury"));
            e.getEquipment().setItem(EquipmentSlot.CHEST, new ItemStack(Material.LEATHER_CHESTPLATE));
            e.getPersistentDataContainer().set(this.workerKey, PersistentDataType.STRING, worker.id);
            e.getPersistentDataContainer().set(this.shopKey, PersistentDataType.STRING, w.id);
        });
        worker.entity = body.getUniqueId().toString();
        this.bodiesChanged = true;
    }

    private void despawn(final Stage3Data.Worker worker) {
        if (worker.entity == null) {
            return;
        }
        final Entity e = Bukkit.getEntity(UUID.fromString(worker.entity));
        if (e != null) {
            e.remove();
        }
        worker.entity = null;
    }

    private Optional<Stage3Data.Worker> worker(final String id) {
        for (final Stage3Data.Workshop w : this.data.workshops.values()) {
            for (final Stage3Data.Worker x : w.workers) {
                if (x.id.equals(id)) {
                    return Optional.of(x);
                }
            }
        }
        return Optional.empty();
    }

    /** Orphan bodies (dismissed or unknown workers) are removed; missing bodies of loaded workshops are spawned. */
    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        for (final Entity e : event.getChunk().getEntities()) {
            final String id = e.getPersistentDataContainer().get(this.workerKey, PersistentDataType.STRING);
            if (id != null && this.worker(id).map(x -> !e.getUniqueId().toString().equals(x.entity)).orElse(true)) {
                e.remove();
            }
        }
        for (final Stage3Data.Workshop w : this.data.workshops.values()) {
            if (w.world.equals(event.getWorld().getName()) && w.x >> 4 == event.getChunk().getX() && w.z >> 4 == event.getChunk().getZ()) {
                Bukkit.getScheduler().runTask(this.plugin, () -> this.ensure(w));
            }
        }
    }

    private void ensureBodies() {
        for (final Stage3Data.Workshop w : this.data.workshops.values()) {
            this.ensure(w);
        }
    }

    private void ensure(final Stage3Data.Workshop w) {
        for (final Stage3Data.Worker x : w.workers) {
            if (x.entity == null || Bukkit.getEntity(UUID.fromString(x.entity)) == null) {
                this.spawn(w, x);
            }
        }
    }

    // ------------------------------------------------------------------ commands

    private SuggestionProvider<CommandSourceStack> recipes() {
        return (ctx, b) -> {
            for (final Recipe r : this.m.settings().recipes.values()) {
                if (r.id().startsWith(b.getRemaining().toLowerCase(Locale.ROOT))) {
                    b.suggest(r.id(), new com.mojang.brigadier.LiteralMessage(r.name() + " (" + r.station().toLowerCase(Locale.ROOT) + ")"));
                }
            }
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> workshops() {
        return (ctx, b) -> {
            if (ctx.getSource().getSender() instanceof Player p) {
                this.plugin.command().current(p).ifPresent(f -> this.m.of(f).forEach(w -> b.suggest(w.id,
                    new com.mojang.brigadier.LiteralMessage(w.recipe))));
            }
            return b.buildFuture();
        };
    }

    private UUID landAt(final Block b) {
        return this.kraje.getCountryAt(b.getLocation()).map(KrajInfo::id).orElse(null);
    }

    private void commands(final LiteralArgumentBuilder<CommandSourceStack> root) {
        final FirmaCommand cmd = this.plugin.command();
        root.then(Commands.literal("warsztat")
            .executes(ctx -> cmd.withFirm(ctx, this::list))
            .then(Commands.literal("zaloz").then(Commands.argument("przepis", StringArgumentType.word()).suggests(this.recipes())
                .executes(ctx -> cmd.withFirm(ctx, (p, f) -> {
                    final Block b = p.getTargetBlockExact(6);
                    if (b == null) {
                        FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Spójrz na stanowisko (stół, piec, wędzarnię, krosno).");
                        return;
                    }
                    FirmaCommand.result(p, this.m.create(f, p.getUniqueId(), StringArgumentType.getString(ctx, "przepis"), b.getWorld().getName(),
                        b.getX(), b.getY(), b.getZ(), b.getType().name(), this.landAt(b)));
                }))))
            .then(this.chestNode("wejscie", true))
            .then(this.chestNode("wyjscie", false))
            .then(Commands.literal("usun").then(Commands.argument("warsztat", StringArgumentType.word()).suggests(this.workshops())
                .executes(ctx -> cmd.withFirm(ctx, (p, f) -> FirmaCommand.result(p, this.m.remove(f, p.getUniqueId(),
                    StringArgumentType.getString(ctx, "warsztat"))))))));
        root.then(Commands.literal("robotnik")
            .executes(ctx -> cmd.withFirm(ctx, this::list))
            .then(Commands.literal("zatrudnij").then(Commands.argument("warsztat", StringArgumentType.word()).suggests(this.workshops())
                .executes(ctx -> cmd.withFirm(ctx, (p, f) -> FirmaCommand.result(p, this.m.hire(f, p.getUniqueId(),
                    StringArgumentType.getString(ctx, "warsztat")))))))
            .then(Commands.literal("zwolnij").then(Commands.argument("warsztat", StringArgumentType.word()).suggests(this.workshops())
                .executes(ctx -> cmd.withFirm(ctx, (p, f) -> FirmaCommand.result(p, this.m.dismiss(f, p.getUniqueId(),
                    StringArgumentType.getString(ctx, "warsztat")))))))
            // before a rollback to a jar without stage 3: every worker goes back to its country (citizens are not lost)
            .then(Commands.literal("zwolnij-wszystkich").requires(s -> s.getSender().hasPermission(FirmaCommand.PERM_ADMIN))
                .executes(ctx -> {
                    final int n = this.m.dismissEverybody("przygotowanie do wycofania etapu 3");
                    FirmaCommand.send(ctx.getSource().getSender(), "<gold>[Firmy]</gold> Robotnicy wrócili do swoich krajów: " + n + ".");
                    return 1;
                })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> chestNode(final String literal, final boolean input) {
        return Commands.literal(literal).then(Commands.argument("warsztat", StringArgumentType.word()).suggests(this.workshops())
            .executes(ctx -> this.plugin.command().withFirm(ctx, (p, f) -> {
                final Block b = p.getTargetBlockExact(6);
                if (b == null || !(b.getState(false) instanceof Container)) {
                    FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Spójrz na skrzynię albo beczkę obok stanowiska.");
                    return;
                }
                FirmaCommand.result(p, this.m.chest(f, p.getUniqueId(), StringArgumentType.getString(ctx, "warsztat"), input, b.getWorld().getName(),
                    b.getX(), b.getY(), b.getZ(), this.landAt(b)));
            })));
    }

    private void list(final Player p, final Firm f) {
        FirmaCommand.send(p, "<gold>━━ Warsztaty „" + FirmaCommand.escape(f.name) + "” ━━ <gray>robotnicy " + this.m.workers(f) + "/" + this.m.workerLimit(f)
            + ", pensja " + this.service.ctx.fmt(this.m.settings().wage) + " dziennie (dla obywateli)");
        for (final Stage3Data.Workshop w : this.m.of(f)) {
            final Recipe r = this.m.settings().recipes.get(w.recipe);
            FirmaCommand.send(p, " <yellow>" + w.id + "</yellow> <white>" + (r == null ? w.recipe : r.name()) + "</white> <gray>— " + w.status
                + ", robotnicy " + w.workers.size() + ", wyprodukowano " + w.produced + ", " + w.x + " " + w.y + " " + w.z + "</gray>");
        }
        if (f.type == pl.medivalrpg.firmy.model.FirmType.CECH && this.m.guild(f)) {
            FirmaCommand.send(p, "<gray>Cech: " + this.m.maxWorkshops(f) + " warsztat z przepisami średniowiecznymi, robotnicy NPC jak w manufakturze."
                + " Pełna lista przepisów i więcej warsztatów: manufaktura (Renesans).");
        } else if (f.type != pl.medivalrpg.firmy.model.FirmType.MANUFAKTURA) {
            FirmaCommand.send(p, "<gray>Warsztaty z robotnikami NPC ma manufaktura (Renesans) i cech (1 warsztat, przepisy średniowieczne).");
        }
        final List<String> recipes = this.m.recipesFor(f);
        FirmaCommand.send(p, "<dark_gray>Przepisy: " + (recipes.isEmpty() ? "—" : String.join(", ", recipes))
            + (recipes.isEmpty() ? "" : " · nowy: patrząc na stanowisko /firma warsztat zaloz <przepis>"));
    }
}
