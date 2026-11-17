// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.village;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.StructureSearchResult;
import pl.medivalrpg.armia.ai.NpcAiService;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.armia.api.NpcVillageResult;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.logic.ChunkPlanner;
import pl.medivalrpg.armia.logic.ScenarioPlacement.Point;
import pl.medivalrpg.armia.logic.ScenarioPlacement.Zone;
import pl.medivalrpg.armia.logic.VillageRules;
import pl.medivalrpg.armia.scenario.ScenarioService;
import pl.medivalrpg.armia.service.Population;
import pl.medivalrpg.armia.service.SafeSpot;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeResult;

/**
 * Villages become NPC countries: {@code /nk wioska} (preview + {@code potwierdz}), the web panel
 * ({@code admin.npc.fromVillage}) and the automat — a one-time scan of the pre-generated world (one grid point every
 * few seconds) plus villages in newly generated chunks, each village rolled once ({@code plugins/Armia/villages.json}).
 * Detection and adoption are Ludnosc's ({@code findVillage}, {@code adoptVillage}). Main thread.
 */
public final class VillageService implements Listener {

    private static final List<String> PARAMS = List.of("agresja", "ekspansja", "dyplomacja", "bogactwo", "honor", "max-chunki", "max-zolnierze");

    /** Persisted automat state. */
    static final class State {
        String world;
        int scanIndex;
        boolean scanDone;
        Set<Long> seen = new LinkedHashSet<>();
        Set<UUID> autoCountries = new LinkedHashSet<>();
    }

    /** A preview waiting for {@code /nk wioska potwierdz}. */
    private record Pending(Population.Village village, String name, int rgb, String ruler, long at) {
    }

    private final Plugin plugin;
    private final Services services;
    private final ArmiaApi api;
    private final NpcAiService ai;
    private final ScenarioService scenarios;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Deque<Point> candidates = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private State state = new State();
    private List<Structure> villages;
    private BukkitTask task;
    private boolean busy;

    public VillageService(final Plugin plugin, final Services services, final ArmiaApi api, final NpcAiService ai, final ScenarioService scenarios,
                          final Supplier<ArmiaConfig> config, final Supplier<Messages> messages) {
        this.plugin = plugin;
        this.services = services;
        this.api = api;
        this.ai = ai;
        this.scenarios = scenarios;
        this.config = config;
        this.messages = messages;
        this.file = plugin.getDataFolder().toPath().resolve("villages.json");
    }

    private Messages m() {
        return this.messages.get();
    }

    private List<Structure> villageStructures() {
        if (this.villages == null) {
            this.villages = List.of(Structure.VILLAGE_PLAINS, Structure.VILLAGE_DESERT, Structure.VILLAGE_SAVANNA, Structure.VILLAGE_SNOWY,
                Structure.VILLAGE_TAIGA);
        }
        return this.villages;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        this.load();
        final int seconds = this.config.get().villageScanIntervalSeconds;
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::step, 20L * 60, 20L * seconds);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
        }
        this.saveNow();
    }

    private void load() {
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            final State s = this.gson.fromJson(Files.readString(this.file, StandardCharsets.UTF_8), State.class);
            if (s != null) {
                if (s.seen == null) {
                    s.seen = new LinkedHashSet<>();
                }
                if (s.autoCountries == null) {
                    s.autoCountries = new LinkedHashSet<>();
                }
                this.state = s;
            }
        } catch (final IOException | RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie można wczytać villages.json — skan zacznie się od nowa", e);
        }
    }

    private void save() {
        final String json = this.gson.toJson(this.state);
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> this.write(json));
    }

    private void saveNow() {
        this.write(this.gson.toJson(this.state));
    }

    private synchronized void write(final String json) {
        try {
            Files.createDirectories(this.file.getParent());
            final Path tmp = this.file.resolveSibling("villages.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie można zapisać villages.json", e);
        }
    }

    // ------------------------------------------------------------------ creation (command, panel, automat)

    private Set<String> takenNames() {
        final Set<String> out = new HashSet<>();
        for (final KrajInfo k : this.services.kraje().getCountries()) {
            out.add(k.name());
        }
        return out;
    }

    private Set<Integer> usedColors() {
        final Set<Integer> out = new HashSet<>();
        for (final KrajInfo k : this.services.kraje().getCountries()) {
            out.add(k.color().value());
        }
        return out;
    }

    private String randomName(final Random rnd) {
        return VillageRules.pickName(this.config.get().villageNames, this.takenNames(), rnd);
    }

    private String randomRuler(final Random rnd) {
        final List<String> pool = this.config.get().villageRulers;
        return pool.get(rnd.nextInt(pool.size()));
    }

    /** Creates the country from a found village. */
    public NpcVillageResult create(final Population.Village v, final String name, final int rgb, final String ruler, final String source) {
        final World w = Bukkit.getWorld(v.world());
        if (w == null) {
            return NpcVillageResult.fail("Nie ma świata " + v.world() + ".");
        }
        if (this.services.kraje().getCountryByName(name).isPresent()) {
            return NpcVillageResult.fail("Kraj " + name + " już istnieje.");
        }
        final Location loc = Optional.ofNullable(SafeSpot.near(w, v.bellX() + 2, v.bellZ(), 6, 90f))
            .orElse(new Location(w, v.bellX() + 0.5, v.bellY() + 1.0, v.bellZ() + 0.5));
        final KrajeResult r = this.api.createNpcCountry(name, rgb, loc, ruler);
        if (!r.ok() || r.countryId() == null) {
            return NpcVillageResult.fail(r.message().isBlank() ? r.status().name() : r.message());
        }
        final UUID id = r.countryId();
        this.ai.ensure(id).lastWarDeclaredAt = System.currentTimeMillis(); // a peaceful start
        int claimed = 1;
        for (final long k : VillageRules.claimOrder(ChunkPlanner.key(loc.getBlockX() >> 4, loc.getBlockZ() >> 4), v.chunkKeys())) {
            if (this.services.kraje().claimChunk(id, w.getName(), ChunkPlanner.cx(k), ChunkPlanner.cz(k), ClaimCause.ADMIN).ok()) {
                claimed++;
            }
        }
        final int adopted = this.services.population().adoptVillage(id, v);
        final int[] traits = VillageRules.randomPersonality(ThreadLocalRandom.current());
        for (int i = 0; i < PARAMS.size(); i++) {
            this.api.setNpcSetting(id, PARAMS.get(i), Integer.toString(traits[i]));
        }
        final String text = "Wioska przy " + v.bellX() + ", " + v.bellZ() + " (" + source + "): " + claimed + " chunków, " + v.beds() + " łóżek, "
            + (adopted >= 0 ? adopted + " wieśniaków przyjętych" : "Ludnosc nie przejęła wieśniaków");
        this.ai.log(id, pl.medivalrpg.armia.api.NpcLogCategory.OTHER, "Powstanie z wioski — " + text);
        this.plugin.getLogger().info("Kraj NPC " + name + " z wioski: " + text);
        return new NpcVillageResult(true, id, name, name + ": " + text + ".", claimed, v.beds(), v.villagers(), adopted);
    }

    /** API / panel: find the village near (x, z) and create the country. Completes on the main thread. */
    public CompletableFuture<NpcVillageResult> fromVillage(final String worldName, final int x, final int z, final String name, final int rgb,
                                                           final String ruler) {
        final World w = worldName == null || worldName.isBlank() ? Bukkit.getWorlds().get(0) : Bukkit.getWorld(worldName);
        if (w == null) {
            return CompletableFuture.completedFuture(NpcVillageResult.fail("Nie ma świata " + worldName + "."));
        }
        final CompletableFuture<NpcVillageResult> out = new CompletableFuture<>();
        final Location at = new Location(w, x + 0.5, w.getMinHeight() + 64, z + 0.5);
        w.getChunkAtAsync(x >> 4, z >> 4, true).thenCompose(c -> this.services.population().findVillage(at, this.config.get().villageFindRadius))
            .whenComplete((v, e) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (e != null || v == null || v.isEmpty()) {
                    out.complete(NpcVillageResult.fail("Nie znaleziono wioski w promieniu " + this.config.get().villageFindRadius + " bl. od " + x + ", " + z
                        + (this.services.population().present() ? "." : " (brak pluginu Ludnosc).")));
                    return;
                }
                final Random rnd = ThreadLocalRandom.current();
                out.complete(this.create(v.get(), name == null || name.isBlank() ? this.randomName(rnd) : name,
                    rgb < 0 ? VillageRules.randomColor(this.usedColors(), rnd) : rgb, ruler == null || ruler.isBlank() ? this.randomRuler(rnd) : ruler, "panel"));
            }));
        return out;
    }

    // ------------------------------------------------------------------ command

    private static String key(final CommandSender s) {
        return s instanceof Player p ? p.getUniqueId().toString() : "console";
    }

    /** {@code /nk wioska [nazwa] [kolor] [władca]}: looks for the village and shows what would be taken. */
    public void preview(final CommandSender s, final Location at, final String name, final Integer rgb, final String ruler) {
        this.m().send(s, "nk-village-searching", Placeholder.unparsed("radius", Integer.toString(this.config.get().villageFindRadius)));
        this.services.population().findVillage(at, this.config.get().villageFindRadius).whenComplete((v, e) ->
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (e != null || v == null || v.isEmpty()) {
                    this.m().send(s, "nk-village-none", Placeholder.unparsed("radius", Integer.toString(this.config.get().villageFindRadius)));
                    return;
                }
                final Random rnd = ThreadLocalRandom.current();
                final Pending p = new Pending(v.get(), name == null || name.isBlank() ? this.randomName(rnd) : name,
                    rgb == null ? VillageRules.randomColor(this.usedColors(), rnd) : rgb, ruler == null || ruler.isBlank() ? this.randomRuler(rnd) : ruler,
                    System.currentTimeMillis());
                this.pending.put(key(s), p);
                final Population.Village vi = p.village();
                this.m().send(s, "nk-village-preview", Placeholder.unparsed("x", Integer.toString(vi.bellX())), Placeholder.unparsed("z", Integer.toString(vi.bellZ())),
                    Placeholder.unparsed("chunks", Integer.toString(vi.chunkKeys().size())), Placeholder.unparsed("beds", Integer.toString(vi.beds())),
                    Placeholder.unparsed("villagers", Integer.toString(vi.villagers())), Placeholder.unparsed("jobs", Integer.toString(vi.jobSites())),
                    Placeholder.component("name", Component.text(p.name(), net.kyori.adventure.text.format.TextColor.color(p.rgb()))),
                    Placeholder.unparsed("ruler", p.ruler()), Messages.command("/nk wioska potwierdz"));
            }));
    }

    /** {@code /nk wioska potwierdz}. */
    public void confirm(final CommandSender s) {
        final Pending p = this.pending.remove(key(s));
        if (p == null || System.currentTimeMillis() - p.at() > 5 * 60_000L) {
            this.m().send(s, "nk-village-no-pending");
            return;
        }
        final NpcVillageResult r = this.create(p.village(), p.name(), p.rgb(), p.ruler(), "komenda");
        this.m().send(s, r.ok() ? "nk-village-created" : "nk-village-failed", Placeholder.unparsed("text", r.message()));
        if (r.ok()) {
            this.announce(p.village(), r.name(), p.ruler());
        }
    }

    private void announce(final Population.Village v, final String name, final String ruler) {
        final World w = Bukkit.getWorld(v.world());
        final Location c = w == null ? new Location(null, 0, 0, 0) : w.getWorldBorder().getCenter();
        final String dir = VillageRules.direction(new Point(v.bellX(), v.bellZ()), new Point(c.getBlockX(), c.getBlockZ()));
        final String direction = Character.toUpperCase(dir.charAt(0)) + dir.substring(1);
        if (!this.m().raw("village-founded-broadcast").isEmpty()) {
            Bukkit.broadcast(this.m().get("village-founded-broadcast", Placeholder.unparsed("direction", direction),
                Placeholder.unparsed("x", Integer.toString(v.bellX())), Placeholder.unparsed("z", Integer.toString(v.bellZ())),
                Placeholder.unparsed("country", name.replace('_', ' ')), Placeholder.unparsed("title", VillageRules.rulerTitle(name)),
                Placeholder.unparsed("ruler", ruler)));
        }
    }

    // ------------------------------------------------------------------ automat

    private World autoWorld() {
        final World first = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (this.state.world == null && first != null) {
            this.state.world = first.getName();
        }
        return this.state.world == null ? null : Bukkit.getWorld(this.state.world);
    }

    /** New chunks: villages starting there become candidates (rate-limited by the step timer). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(final ChunkLoadEvent event) {
        if (!event.isNewChunk() || !this.config.get().villageRules.enabled() || this.candidates.size() >= 64) {
            return;
        }
        final World w = this.autoWorld();
        if (w == null || event.getWorld() != w) {
            return;
        }
        final Set<Structure> kinds = new HashSet<>(this.villageStructures());
        for (final GeneratedStructure g : event.getChunk().getStructures()) {
            if (kinds.contains(g.getStructure())) {
                final BoundingBox b = g.getBoundingBox();
                this.offer(new Point((int) Math.floor(b.getCenterX()), (int) Math.floor(b.getCenterZ())));
            }
        }
    }

    private void offer(final Point p) {
        final long k = VillageRules.key(p.x(), p.z());
        if (!this.state.seen.contains(k) && this.queued.add(k)) {
            this.candidates.add(p);
        }
    }

    /** One step of the automat: a candidate village, or the next point of the scan. */
    private void step() {
        final ArmiaConfig cfg = this.config.get();
        if (!cfg.villageRules.enabled() || this.busy) {
            return;
        }
        final World w = this.autoWorld();
        if (w == null) {
            return;
        }
        try {
            if (!this.candidates.isEmpty()) {
                final Point p = this.candidates.poll();
                this.queued.remove(VillageRules.key(p.x(), p.z()));
                this.consider(w, p);
                return;
            }
            if (!this.state.scanDone) {
                this.scanNext(w);
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Automat wiosek: błąd", e);
        }
    }

    private void scanNext(final World w) {
        final ArmiaConfig cfg = this.config.get();
        final Location centre = w.getWorldBorder().getCenter();
        final List<Point> grid = VillageRules.scanGrid(new Point(centre.getBlockX(), centre.getBlockZ()), cfg.villageRules.scanRadius(), cfg.villageRules.scanStep());
        if (this.state.scanIndex >= grid.size()) {
            this.state.scanDone = true;
            this.plugin.getLogger().info("Automat wiosek: skan świata zakończony (" + grid.size() + " punktów, wiosek: " + this.state.seen.size() + ")");
            this.save();
            return;
        }
        final Point p = grid.get(this.state.scanIndex++);
        final Location at = new Location(w, p.x(), 64, p.z());
        final int radiusChunks = Math.max(1, cfg.villageRules.scanStep() / 32 + 1);
        for (final Structure s : this.villageStructures()) {
            try {
                final StructureSearchResult r = w.locateNearestStructure(at, s, radiusChunks, false);
                if (r != null && r.getLocation() != null) {
                    this.offer(new Point(r.getLocation().getBlockX(), r.getLocation().getBlockZ()));
                }
            } catch (final RuntimeException e) {
                // structure not in this world's generator
            }
        }
        if (this.state.scanIndex % 10 == 0 || this.state.scanIndex == grid.size()) {
            this.plugin.getLogger().info("Automat wiosek: skan " + this.state.scanIndex + "/" + grid.size() + ", kandydaci w kolejce: " + this.candidates.size());
        }
        this.save();
    }

    private int npcCountries() {
        int n = 0;
        for (final KrajInfo k : this.services.kraje().getCountries()) {
            if (k.npc()) {
                n++;
            }
        }
        return n;
    }

    private List<Point> countryChunks(final World w) {
        final List<Point> out = new ArrayList<>();
        for (final KrajInfo k : this.services.kraje().getCountries()) {
            for (final long key : this.services.kraje().getClaims(k.id(), w.getName())) {
                out.add(new Point((ChunkPlanner.cx(key) << 4) + 8, (ChunkPlanner.cz(key) << 4) + 8));
            }
        }
        return out;
    }

    /** Applies the automat rules to one village; a village is rolled only once. */
    void consider(final World w, final Point p) {
        final ArmiaConfig cfg = this.config.get();
        final long k = VillageRules.key(p.x(), p.z());
        this.state.autoCountries.removeIf(id -> this.services.kraje().getCountry(id).isEmpty());
        final List<Zone> zones = this.scenarios.zones(w, cfg.villageRules.minSpawnDistance());
        final VillageRules.Decision d = VillageRules.decide(cfg.villageRules, p, this.state.seen.contains(k), zones, this.countryChunks(w),
            this.npcCountries(), this.state.autoCountries.size(), ThreadLocalRandom.current().nextDouble());
        if (d == VillageRules.Decision.SEEN || d == VillageRules.Decision.DISABLED || d == VillageRules.Decision.LIMIT) {
            return; // limit: not rolled, may be used when a country falls
        }
        this.state.seen.add(k);
        this.save();
        if (d != VillageRules.Decision.CREATE) {
            return;
        }
        this.busy = true;
        final Location at = new Location(w, p.x() + 0.5, 64, p.z() + 0.5);
        w.getChunkAtAsync(p.x() >> 4, p.z() >> 4, true).thenCompose(c -> this.services.population().findVillage(at, cfg.villageFindRadius))
            .whenComplete((v, e) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                this.busy = false;
                if (e != null || v == null || v.isEmpty()) {
                    return;
                }
                final Random rnd = ThreadLocalRandom.current();
                final String ruler = this.randomRuler(rnd);
                final NpcVillageResult r = this.create(v.get(), this.randomName(rnd), VillageRules.randomColor(this.usedColors(), rnd), ruler, "automat");
                if (r.ok()) {
                    this.state.autoCountries.add(r.countryId());
                    this.save();
                    this.announce(v.get(), r.name(), ruler);
                } else {
                    this.plugin.getLogger().info("Automat wiosek: wioska przy " + p.x() + ", " + p.z() + " — " + r.message());
                }
            }));
    }
}
