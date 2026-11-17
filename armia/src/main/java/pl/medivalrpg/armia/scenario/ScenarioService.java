// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.scenario;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.Structure;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.StructureSearchResult;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.logic.Scenario;
import pl.medivalrpg.armia.logic.ScenarioPlacement;
import pl.medivalrpg.armia.service.Population;
import pl.medivalrpg.armia.service.SafeSpot;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.model.Relation;

/**
 * Start scenarios ({@code /nk scenariusz}): YAML files in {@code plugins/Armia/scenarios/} (bundled ones are
 * extracted on start). Loading places one country every two seconds: nearest village (structure search), else a dry,
 * flat spot at the target, never near protected areas (spawn) or other capitals; chunks are loaded asynchronously.
 * Existing names are skipped, so loading again only adds what is missing. Main thread.
 */
public final class ScenarioService {

    /** Scenarios shipped in the jar. */
    public static final List<String> BUNDLED = List.of("europa1350");
    private static final List<Structure> VILLAGES = List.of(Structure.VILLAGE_PLAINS, Structure.VILLAGE_DESERT, Structure.VILLAGE_SAVANNA,
        Structure.VILLAGE_SNOWY, Structure.VILLAGE_TAIGA);
    private static final List<String> PARAMS = List.of("agresja", "ekspansja", "dyplomacja", "bogactwo", "max-chunki", "max-zolnierze");

    private final Plugin plugin;
    private final Services services;
    private final ArmiaApi api;
    private final pl.medivalrpg.armia.ai.NpcAiService ai;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;
    private final Path dir;
    private Run running;

    public ScenarioService(final Plugin plugin, final Services services, final ArmiaApi api, final pl.medivalrpg.armia.ai.NpcAiService ai,
                           final Supplier<ArmiaConfig> config,
                           final Supplier<Messages> messages) {
        this.plugin = plugin;
        this.services = services;
        this.api = api;
        this.ai = ai;
        this.config = config;
        this.messages = messages;
        this.dir = plugin.getDataFolder().toPath().resolve("scenarios");
    }

    private Messages m() {
        return this.messages.get();
    }

    /** Copies the bundled scenarios to {@code plugins/Armia/scenarios/} (never overwrites an edited file). */
    public void extractDefaults() {
        for (final String id : BUNDLED) {
            final Path file = this.dir.resolve(id + ".yml");
            if (Files.exists(file) && this.outdated(id, file)) {
                try {
                    Files.copy(file, this.dir.resolve(id + ".yml.old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    this.plugin.saveResource("scenarios/" + id + ".yml", true);
                    this.plugin.getLogger().warning("Scenariusz " + id + ": starsza wersja pliku (bez honoru) — zastąpiono wersją z jara, kopia: "
                        + id + ".yml.old");
                } catch (final IOException | IllegalArgumentException e) {
                    this.plugin.getLogger().log(Level.WARNING, "Nie udało się zaktualizować scenariusza " + id, e);
                }
                continue;
            }
            if (!Files.exists(file)) {
                try {
                    this.plugin.saveResource("scenarios/" + id + ".yml", false);
                } catch (final IllegalArgumentException e) {
                    this.plugin.getLogger().warning("Brak scenariusza " + id + " w jarze");
                }
            }
        }
    }

    /** The file on disk is older than the bundled one: lower {@code wersja}, or no {@code honor} while the jar's has one. */
    private boolean outdated(final String id, final Path file) {
        try (var in = this.plugin.getResource("scenarios/" + id + ".yml")) {
            if (in == null) {
                return false;
            }
            final YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            final YamlConfiguration disk = YamlConfiguration.loadConfiguration(file.toFile());
            return ScenarioParser.outdated(bundled, disk);
        } catch (final IOException e) {
            return false;
        }
    }

    /** Ids of the scenario files. */
    public List<String> ids() {
        final List<String> out = new ArrayList<>();
        final File[] files = this.dir.toFile().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (final File f : files) {
                out.add(f.getName().substring(0, f.getName().length() - 4));
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** Parses a scenario; empty when there is no such file. Structural errors throw IllegalArgumentException. */
    public Optional<Scenario> load(final String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            return Optional.empty();
        }
        final File f = this.dir.resolve(id + ".yml").toFile();
        if (!f.isFile()) {
            return Optional.empty();
        }
        return Optional.of(ScenarioParser.parse(id, YamlConfiguration.loadConfiguration(f)));
    }

    /** Rule problems (unique names / colours, ranges, unit types, Kraje name rules). */
    public List<String> validate(final Scenario s) {
        final org.bukkit.plugin.Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
        final int min = kraje == null ? 3 : kraje.getConfig().getInt("country.name-min-length", 3);
        final int max = kraje == null ? 24 : kraje.getConfig().getInt("country.name-max-length", 24);
        final List<String> errors = new ArrayList<>(s.validate(this.config.get().units.keySet(), min, max));
        if (this.world(s) == null) {
            errors.add("Nie ma świata " + (s.world().isBlank() ? "(pierwszy świat)" : s.world()) + ".");
        }
        return errors;
    }

    public World world(final Scenario s) {
        if (s.world() == null || s.world().isBlank()) {
            return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }
        return Bukkit.getWorld(s.world());
    }

    public boolean busy() {
        return this.running != null;
    }

    // ------------------------------------------------------------------ zones

    /** Protected areas of Kraje ({@code plugins/Kraje/protected-areas.json}) in this world, grown by their buffer + {@code extra}. */
    public List<ScenarioPlacement.Zone> zones(final World w, final int extra) {
        final List<ScenarioPlacement.Zone> out = new ArrayList<>();
        final org.bukkit.plugin.Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
        if (kraje != null) {
            final Path file = kraje.getDataFolder().toPath().resolve("protected-areas.json");
            if (Files.exists(file)) {
                try {
                    final JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (root.has("areas")) {
                        for (final JsonElement e : root.getAsJsonArray("areas")) {
                            final JsonObject a = e.getAsJsonObject();
                            if (!w.getName().equals(a.getAsJsonPrimitive("world").getAsString())) {
                                continue;
                            }
                            final int buffer = a.has("bufferChunks") ? a.get("bufferChunks").getAsInt() * 16 : 0;
                            out.add(new ScenarioPlacement.Zone(Math.floorDiv(a.get("minX").getAsInt(), 16) * 16, Math.floorDiv(a.get("minZ").getAsInt(), 16) * 16,
                                Math.floorDiv(a.get("maxX").getAsInt(), 16) * 16 + 15, Math.floorDiv(a.get("maxZ").getAsInt(), 16) * 16 + 15).grow(buffer + extra));
                        }
                    }
                } catch (final IOException | RuntimeException e) {
                    this.plugin.getLogger().log(Level.WARNING, "Nie można odczytać obszarów chronionych Kraje", e);
                }
            }
        }
        if (out.isEmpty()) {
            final Location spawn = w.getSpawnLocation();
            out.add(new ScenarioPlacement.Zone(spawn.getBlockX(), spawn.getBlockZ(), spawn.getBlockX(), spawn.getBlockZ()).grow(extra));
        }
        return out;
    }

    /** Capitals of the existing countries in this world (chunk centres). */
    private List<ScenarioPlacement.Point> capitals(final World w) {
        final List<ScenarioPlacement.Point> out = new ArrayList<>();
        for (final KrajInfo k : this.services.kraje().getCountries()) {
            if (w.getName().equals(k.capitalWorld())) {
                out.add(new ScenarioPlacement.Point((k.capitalX() << 4) + 8, (k.capitalZ() << 4) + 8));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ show

    public void show(final CommandSender s, final Scenario sc) {
        this.m().send(s, "nk-scenario-show-header", Placeholder.unparsed("name", sc.name()), Placeholder.unparsed("id", sc.id()),
            Placeholder.unparsed("count", Integer.toString(sc.countries().size())), Placeholder.unparsed("description", sc.description()));
        for (final Scenario.CountrySpec c : sc.countries()) {
            final boolean exists = this.services.kraje().getCountryByName(c.name()).isPresent();
            this.m().send(s, "nk-scenario-show-line",
                Placeholder.styling("color", net.kyori.adventure.text.format.TextColor.color(c.rgb())),
                Placeholder.unparsed("name", c.name()), Placeholder.unparsed("ruler", c.ruler()),
                Placeholder.unparsed("x", Integer.toString(c.x())), Placeholder.unparsed("z", Integer.toString(c.z())),
                Placeholder.unparsed("traits", c.aggression() + "/" + c.expansion() + "/" + c.diplomacy() + "/" + c.wealth()),
                Placeholder.unparsed("treasury", this.services.money(c.treasury())),
                Placeholder.unparsed("army", Integer.toString(c.army().values().stream().mapToInt(Integer::intValue).sum())),
                Placeholder.unparsed("status", exists ? "istnieje — zostanie pominięte" : "do utworzenia"));
        }
        for (final String w : sc.similarColors(60)) {
            this.m().send(s, "nk-scenario-warning", Placeholder.unparsed("text", "podobne kolory: " + w));
        }
        final List<String> errors = this.validate(sc);
        if (!errors.isEmpty()) {
            this.m().send(s, "nk-scenario-invalid", Placeholder.unparsed("errors", String.join("; ", errors)));
        }
    }

    // ------------------------------------------------------------------ load

    /** Run state of one loading. */
    private final class Run {
        final Scenario scenario;
        final World world;
        final UUID senderId;
        final CommandSender console;
        final Deque<Scenario.CountrySpec> queue;
        final List<ScenarioPlacement.Zone> zones;
        final List<ScenarioPlacement.Point> others;
        final int total;
        int created;
        int skipped;
        int failed;

        Run(final Scenario scenario, final World world, final CommandSender sender) {
            this.scenario = scenario;
            this.world = world;
            this.senderId = sender instanceof Player p ? p.getUniqueId() : null;
            this.console = sender instanceof Player ? null : sender;
            this.queue = new ArrayDeque<>(scenario.countries());
            this.zones = ScenarioService.this.zones(world, scenario.spawnDistance());
            this.others = ScenarioService.this.capitals(world);
            this.total = scenario.countries().size();
        }

        void tell(final String key, final TagResolver... tags) {
            final CommandSender to = this.senderId == null ? this.console : Bukkit.getPlayer(this.senderId);
            if (to != null) {
                ScenarioService.this.m().send(to, key, tags);
            }
            if (!(to instanceof org.bukkit.command.ConsoleCommandSender)) {
                ScenarioService.this.plugin.getLogger().info("[scenariusz " + this.scenario.id() + "] "
                    + ScenarioService.this.m().plain(key, tags));
            }
        }

        int done() {
            return this.total - this.queue.size();
        }
    }

    /** Starts loading (validated by the caller). False when another scenario is being loaded. */
    public boolean start(final CommandSender sender, final Scenario sc) {
        if (this.running != null) {
            return false;
        }
        final World w = this.world(sc);
        if (w == null) {
            return false;
        }
        this.running = new Run(sc, w, sender);
        this.running.tell("nk-scenario-start", Placeholder.unparsed("name", sc.name()), Placeholder.unparsed("count", Integer.toString(sc.countries().size())),
            Placeholder.unparsed("world", w.getName()));
        Bukkit.getScheduler().runTaskLater(this.plugin, this::next, 10L);
        return true;
    }

    private void next() {
        final Run run = this.running;
        if (run == null) {
            return;
        }
        if (run.queue.isEmpty()) {
            this.finish(run);
            return;
        }
        final Scenario.CountrySpec spec = run.queue.poll();
        final TagResolver[] who = {Placeholder.unparsed("n", Integer.toString(run.done())), Placeholder.unparsed("total", Integer.toString(run.total)),
            Placeholder.unparsed("name", spec.name())};
        try {
            final Optional<KrajInfo> existing = this.services.kraje().getCountryByName(spec.name());
            if (existing.isPresent()) {
                run.skipped++;
                run.tell("nk-scenario-skip", who);
                // An older file had no honor: take it from the scenario unless an admin set one.
                if (spec.honor() != null && existing.get().npc()) {
                    final pl.medivalrpg.armia.model.NpcState st = this.ai.ensure(existing.get().id());
                    if (st.honor == null) {
                        st.honor = spec.honor();
                        this.ai.log(existing.get().id(), pl.medivalrpg.armia.api.NpcLogCategory.OTHER, "Scenariusz " + run.scenario.id() + ": honor = " + spec.honor());
                        final TagResolver[] tags = java.util.Arrays.copyOf(who, who.length + 1);
                        tags[who.length] = Placeholder.unparsed("honor", Integer.toString(spec.honor()));
                        run.tell("nk-scenario-honor", tags);
                    }
                }
                Bukkit.getScheduler().runTaskLater(this.plugin, this::next, 1L);
                return;
            }
            final ScenarioPlacement.Point target = new ScenarioPlacement.Point(spec.x(), spec.z());
            final ScenarioPlacement.Point village = this.findVillage(run.world, target, run.scenario.villageRadius());
            final Optional<ScenarioPlacement.Choice> choice = ScenarioPlacement.choose(target, village, run.scenario.villageRadius(),
                run.zones, run.others, run.scenario.minDistance(), 1600, 64);
            if (choice.isEmpty()) {
                this.fail(run, who, "brak miejsca z wymaganymi odstępami w promieniu 1600 bloków");
                return;
            }
            final ScenarioPlacement.Point p = choice.get().point();
            final List<CompletableFuture<?>> chunks = new ArrayList<>();
            final int cx = p.x() >> 4;
            final int cz = p.z() >> 4;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    chunks.add(run.world.getChunkAtAsync(cx + dx, cz + dz, true));
                }
            }
            CompletableFuture.allOf(chunks.toArray(new CompletableFuture<?>[0])).whenComplete((v, e) ->
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    if (e != null) {
                        this.fail(run, who, "nie udało się wczytać chunków (" + e.getClass().getSimpleName() + ")");
                    } else {
                        this.place(run, spec, choice.get(), who);
                    }
                }));
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Scenariusz: błąd przy " + spec.name(), e);
            this.fail(run, who, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void fail(final Run run, final TagResolver[] who, final String reason) {
        run.failed++;
        final TagResolver[] tags = java.util.Arrays.copyOf(who, who.length + 1);
        tags[who.length] = Placeholder.unparsed("reason", reason);
        run.tell("nk-scenario-failed", tags);
        Bukkit.getScheduler().runTaskLater(this.plugin, this::next, 20L);
    }

    /** Nearest village of any kind within {@code radius} blocks, or null. */
    private ScenarioPlacement.Point findVillage(final World w, final ScenarioPlacement.Point target, final int radius) {
        if (radius <= 0) {
            return null;
        }
        final Location at = new Location(w, target.x(), 64, target.z());
        final int chunks = Math.max(1, (int) Math.ceil(radius / 16.0));
        ScenarioPlacement.Point best = null;
        for (final Structure s : VILLAGES) {
            try {
                final StructureSearchResult r = w.locateNearestStructure(at, s, chunks, false);
                if (r == null || r.getLocation() == null) {
                    continue;
                }
                final ScenarioPlacement.Point p = new ScenarioPlacement.Point(r.getLocation().getBlockX(), r.getLocation().getBlockZ());
                if (p.distance(target) <= radius && (best == null || p.distance(target) < best.distance(target))) {
                    best = p;
                }
            } catch (final RuntimeException e) {
                // structure not in this world's generator — try the next kind
            }
        }
        return best;
    }

    /** A bell within two chunks of the village start, or null. */
    private ScenarioPlacement.Point findBell(final World w, final ScenarioPlacement.Point p) {
        final int cx = p.x() >> 4;
        final int cz = p.z() >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                final ChunkSnapshot snap = w.getChunkAt(cx + dx, cz + dz).getChunkSnapshot(true, false, false);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        final int top = snap.getHighestBlockYAt(x, z);
                        for (int y = top; y >= Math.max(w.getMinHeight(), top - 8); y--) {
                            if (snap.getBlockType(x, y, z) == Material.BELL) {
                                return new ScenarioPlacement.Point(((cx + dx) << 4) + x, ((cz + dz) << 4) + z);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private Integer height(final World w, final int x, final int z) {
        final Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return SafeSpot.standable(top) ? top.getY() : null;
    }

    private void place(final Run run, final Scenario.CountrySpec spec, final ScenarioPlacement.Choice choice, final TagResolver[] who) {
        final World w = run.world;
        final ScenarioPlacement.Point p = choice.point();
        Location loc = null;
        String where;
        if (choice.source() == ScenarioPlacement.Source.VILLAGE) {
            final ScenarioPlacement.Point bell = this.findBell(w, p);
            if (bell != null) {
                loc = SafeSpot.near(w, bell.x() + 2, bell.z(), 6, 90f);
                where = "przy dzwonie wioski";
            } else {
                loc = SafeSpot.near(w, p.x(), p.z(), 16, 0f);
                where = "w wiosce";
            }
        } else {
            final ScenarioPlacement.Point flat = ScenarioPlacement.flattest(p, 32, 4, (dx, dz) -> this.height(w, p.x() + dx, p.z() + dz));
            final ScenarioPlacement.Point c = flat == null ? p : flat;
            loc = SafeSpot.near(w, c.x(), c.z(), flat == null ? 32 : 4, 0f);
            where = choice.source() == ScenarioPlacement.Source.TARGET ? "w pozycji docelowej" : "przesunięte (odstępy)";
        }
        if (loc == null) {
            // the target (or the village) lies in water: search land on a spiral, farther and farther
            this.searchLand(run, spec, who, choice.point());
            return;
        }
        this.create(run, spec, loc, where, who);
    }

    // ------------------------------------------------------------------ land search (target in water)

    /** State of one asynchronous spiral search. */
    private final class Search {
        final Run run;
        final Scenario.CountrySpec spec;
        final TagResolver[] who;
        final ScenarioPlacement.Point target;
        final java.util.Iterator<ScenarioPlacement.Point> candidates;
        int tried;
        int rejectedByDistance;
        int lastReported;

        Search(final Run run, final Scenario.CountrySpec spec, final TagResolver[] who, final ScenarioPlacement.Point target,
               final List<ScenarioPlacement.Point> candidates) {
            this.run = run;
            this.spec = spec;
            this.who = who;
            this.target = target;
            this.candidates = candidates.iterator();
        }

        TagResolver[] tags(final String key1, final String v1, final String key2, final String v2) {
            final TagResolver[] t = java.util.Arrays.copyOf(this.who, this.who.length + 2);
            t[this.who.length] = Placeholder.unparsed(key1, v1);
            t[this.who.length + 1] = Placeholder.unparsed(key2, v2);
            return t;
        }
    }

    private void searchLand(final Run run, final Scenario.CountrySpec spec, final TagResolver[] who, final ScenarioPlacement.Point from) {
        final ArmiaConfig cfg = this.config.get();
        final Location centre = run.world.getWorldBorder().getCenter();
        final List<ScenarioPlacement.Point> candidates = ScenarioPlacement.spiralCandidates(from,
            new ScenarioPlacement.Point(centre.getBlockX(), centre.getBlockZ()), cfg.scenarioSearchStep, cfg.scenarioMaxSearchRadius);
        final Search s = new Search(run, spec, who, from, candidates);
        run.tell("nk-scenario-searching", s.tags("radius", Integer.toString(cfg.scenarioMaxSearchRadius), "step", Integer.toString(cfg.scenarioSearchStep)));
        this.searchStep(s);
    }

    /** Next allowed candidate: load its chunk (async, with a timeout), probe the surface, then the flat spot around it. */
    private void searchStep(final Search s) {
        final ArmiaConfig cfg = this.config.get();
        ScenarioPlacement.Point p = null;
        while (s.candidates.hasNext()) {
            final ScenarioPlacement.Point c = s.candidates.next();
            if (ScenarioPlacement.allowed(c, s.run.zones, s.run.others, s.run.scenario.minDistance())) {
                p = c;
                break;
            }
            s.rejectedByDistance++;
        }
        if (p == null) {
            this.fail(s.run, s.who, "brak lądu w promieniu " + cfg.scenarioMaxSearchRadius + " bl. od celu (sprawdzono " + s.tried + " miejsc"
                + (s.rejectedByDistance > 0 ? ", " + s.rejectedByDistance + " odrzuconych przez odstępy od spawnu i innych państw" : "") + ")");
            return;
        }
        s.tried++;
        final int distance = (int) Math.round(p.distance(s.target));
        if (distance - s.lastReported >= 4 * cfg.scenarioSearchStep) {
            s.lastReported = distance;
            s.run.tell("nk-scenario-search-progress", s.tags("distance", Integer.toString(distance), "tried", Integer.toString(s.tried)));
        }
        final ScenarioPlacement.Point probe = p;
        final World w = s.run.world;
        w.getChunkAtAsync(probe.x() >> 4, probe.z() >> 4, true).orTimeout(cfg.scenarioStepTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (this.running != s.run) {
                    return;
                }
                if (error != null || this.height(w, probe.x(), probe.z()) == null) {
                    this.searchStep(s); // water, lava or timeout: next candidate
                    return;
                }
                this.loadAround(w, probe, 2, cfg.scenarioStepTimeoutSeconds).whenComplete((v, e2) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                    if (this.running != s.run) {
                        return;
                    }
                    final ScenarioPlacement.Point flat = e2 != null ? null
                        : ScenarioPlacement.flattest(probe, 24, 4, (dx, dz) -> this.height(w, probe.x() + dx, probe.z() + dz));
                    final Location loc = flat == null ? null : SafeSpot.near(w, flat.x(), flat.z(), 4, 0f);
                    if (loc == null) {
                        this.searchStep(s);
                        return;
                    }
                    final int moved = (int) Math.round(new ScenarioPlacement.Point(loc.getBlockX(), loc.getBlockZ()).distance(s.target));
                    if (s.rejectedByDistance > 0) {
                        this.plugin.getLogger().info("[scenariusz] " + s.spec.name() + ": odstępy od spawnu/innych państw wykluczyły "
                            + s.rejectedByDistance + " miejsc — stolica " + moved + " bl. od celu");
                    }
                    this.create(s.run, s.spec, loc, "ląd " + moved + " bl. od celu (cel w wodzie)", s.who);
                }));
            }));
    }

    /** Loads the chunks within {@code radius} chunks of a point asynchronously. */
    private CompletableFuture<Void> loadAround(final World w, final ScenarioPlacement.Point p, final int radius, final int timeoutSeconds) {
        final List<CompletableFuture<?>> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(w.getChunkAtAsync((p.x() >> 4) + dx, (p.z() >> 4) + dz, true));
            }
        }
        return CompletableFuture.allOf(chunks.toArray(new CompletableFuture<?>[0])).orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void create(final Run run, final Scenario.CountrySpec spec, final Location loc, final String where, final TagResolver[] who) {
        final KrajeResult r = this.api.createNpcCountry(spec.name(), spec.rgb(), loc, spec.ruler());
        if (!r.ok() || r.countryId() == null) {
            this.fail(run, who, r.message().isBlank() ? r.status().name() : r.message());
            return;
        }
        final UUID id = r.countryId();
        // A peaceful start: the war cooldown (ai.war-cooldown-minutes) runs from now.
        this.ai.ensure(id).lastWarDeclaredAt = System.currentTimeMillis();
        final List<String> notes = new ArrayList<>();
        if (!spec.skin().isEmpty()) {
            this.api.placeRuler(id, spec.ruler(), spec.skin());
        }
        final int[] values = {spec.aggression(), spec.expansion(), spec.diplomacy(), spec.wealth(), spec.maxChunks(), spec.maxSoldiers()};
        for (int i = 0; i < PARAMS.size(); i++) {
            this.api.setNpcSetting(id, PARAMS.get(i), Integer.toString(values[i]));
        }
        if (spec.honor() != null) {
            this.api.setNpcSetting(id, "honor", Integer.toString(spec.honor()));
        }
        if (spec.treasury() > 0) {
            this.services.skarbiec().deposit(this.services.treasury(id), spec.treasury(), "Scenariusz " + run.scenario.id());
        }
        int soldiers = 0;
        for (final Map.Entry<String, Integer> e : spec.army().entrySet()) {
            if (e.getValue() > 0) {
                final KrajeResult a = this.api.addSoldiers(id, e.getKey(), e.getValue());
                if (a.ok()) {
                    soldiers += e.getValue();
                } else {
                    notes.add("wojsko " + e.getKey() + ": " + a.message());
                }
            }
        }
        final Population pop = this.services.population();
        if (spec.settlers() > 0) {
            final int added = pop.adjustSettlers(id, spec.settlers());
            if (added <= 0) {
                notes.add("osadnicy: Ludnosc nie obsługuje albo odmówiła");
            }
        }
        if (spec.food() > 0 && !pop.adjustFood(id, spec.food())) {
            notes.add("jedzenie: Ludnosc nie obsługuje albo odmówiła");
        }
        run.others.add(new ScenarioPlacement.Point(loc.getBlockX(), loc.getBlockZ()));
        run.created++;
        final TagResolver[] tags = java.util.Arrays.copyOf(who, who.length + 6);
        tags[who.length] = Placeholder.unparsed("x", Integer.toString(loc.getBlockX()));
        tags[who.length + 1] = Placeholder.unparsed("y", Integer.toString(loc.getBlockY()));
        tags[who.length + 2] = Placeholder.unparsed("z", Integer.toString(loc.getBlockZ()));
        tags[who.length + 3] = Placeholder.unparsed("where", where);
        tags[who.length + 4] = Placeholder.unparsed("soldiers", Integer.toString(soldiers));
        tags[who.length + 5] = Placeholder.unparsed("notes", notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
        run.tell("nk-scenario-progress", tags);
        Bukkit.getScheduler().runTaskLater(this.plugin, this::next, 40L);
    }

    private void finish(final Run run) {
        this.running = null;
        int alliances = 0;
        int attitudes = 0;
        for (final String[] pair : run.scenario.alliances()) {
            final Optional<KrajInfo> a = this.services.kraje().getCountryByName(pair[0]);
            final Optional<KrajInfo> b = this.services.kraje().getCountryByName(pair[1]);
            if (a.isPresent() && b.isPresent() && this.services.kraje().getRelation(a.get().id(), b.get().id()) != Relation.ALLIANCE
                && this.services.kraje().setRelation(a.get().id(), b.get().id(), Relation.ALLIANCE).ok()) {
                alliances++;
            }
        }
        final Set<String> missing = new HashSet<>();
        for (final Scenario.Attitude at : run.scenario.attitudes()) {
            final Optional<KrajInfo> from = this.services.kraje().getCountryByName(at.from());
            final Optional<KrajInfo> to = this.services.kraje().getCountryByName(at.to());
            if (from.isEmpty() || to.isEmpty()) {
                missing.add(from.isEmpty() ? at.from() : at.to());
                continue;
            }
            if (!from.get().npc()) {
                continue;
            }
            if (at.opinion() != null) {
                this.api.setOpinion(from.get().id(), to.get().id(), at.opinion());
            }
            if (at.stance() != null) {
                this.api.setStance(from.get().id(), to.get().id(), at.stance().name());
            }
            attitudes++;
        }
        run.tell("nk-scenario-done", Placeholder.unparsed("name", run.scenario.name()),
            Placeholder.unparsed("created", Integer.toString(run.created)), Placeholder.unparsed("skipped", Integer.toString(run.skipped)),
            Placeholder.unparsed("failed", Integer.toString(run.failed)), Placeholder.unparsed("alliances", Integer.toString(alliances)),
            Placeholder.unparsed("attitudes", Integer.toString(attitudes)),
            Placeholder.unparsed("missing", missing.isEmpty() ? "" : " Brak państw: " + String.join(", ", missing) + "."));
    }
}
