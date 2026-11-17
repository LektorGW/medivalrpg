// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Bed;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.logic.PlayerBuildingRules;
import pl.medivalrpg.ludnosc.logic.PlayerBuildingRules.Def;
import pl.medivalrpg.ludnosc.logic.PlayerBuildingRules.Shortage;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.model.OwnBuilding;
import pl.medivalrpg.ludnosc.planner.BuildingChooser;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/**
 * Buildings built by the players and registered with a type ({@code /ludnosc budynek}). Validation of the outline
 * (own land, no overlap, size, key blocks, limits and requirements of the template of the same kind), registration as
 * an ordinary registered {@link Building} and the upkeep of its function: a change of blocks inside (break, place,
 * fire, explosion, bucket) or the slow sweep re-counts the key blocks; without them the function is
 * {@link BuildingFunction#OTHER} until the players put them back. Never scans unloaded chunks. Main thread.
 */
public final class PlayerBuildingService {

    /** Result of a registration: the building, or the problems (Polish, each one line). */
    public record Outcome(Building building, List<String> problems) {
    }

    /** Key blocks found inside an outline. */
    public record Scan(Map<String, Integer> counts, List<BlockPos> containers, List<BlockPos> halfBeds, int wholeBeds) {
    }

    private static final Set<Material> ANVILS = Set.of(Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL);

    private final LudnoscPlugin plugin;
    private volatile Map<String, Def> types = Map.of();
    private volatile boolean enabled = true;
    private volatile PlayerBuildingRules.Limits limits = new PlayerBuildingRules.Limits(3, 3, 32768);
    private volatile int maxTotal = 40;
    private volatile boolean requirements = true;
    private volatile long delayTicks = 40;
    private final Set<UUID> pending = new LinkedHashSet<>();
    private boolean scheduled;
    private int sweepCursor;
    private org.bukkit.scheduler.BukkitTask sweepTask;

    public PlayerBuildingService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
        this.reload();
        plugin.data().setAlias(this::activeKind);
    }

    /** Reads {@code player-buildings.*} (defaults from the jar's config.yml) and restarts the sweep. */
    public void reload() {
        final var c = this.plugin.getConfig();
        this.enabled = c.getBoolean("player-buildings.enabled", true);
        this.limits = new PlayerBuildingRules.Limits(Math.max(1, c.getInt("player-buildings.min-side", 3)),
            Math.max(1, c.getInt("player-buildings.min-height", 3)),
            Math.max(27, c.getLong("player-buildings.max-volume", 32768)));
        this.maxTotal = Math.max(0, c.getInt("player-buildings.max-per-country", 40));
        this.requirements = c.getBoolean("player-buildings.requirements", true);
        this.delayTicks = Math.max(1, c.getLong("player-buildings.recheck-delay-ticks", 40));
        final List<String> warnings = new ArrayList<>();
        final Map<String, Map<String, Object>> section = new LinkedHashMap<>();
        final ConfigurationSection s = c.getConfigurationSection("player-buildings.types");
        if (s != null) {
            for (final String key : s.getKeys(false)) {
                final ConfigurationSection t = s.getConfigurationSection(key);
                if (t != null) {
                    section.put(key, plain(t));
                }
            }
        }
        final Map<String, Def> parsed = PlayerBuildingRules.configure(section, warnings);
        for (final Def d : List.copyOf(parsed.values())) {
            for (final String m : d.materials()) {
                if (!PlayerBuildingRules.TOKENS.contains(m) && Material.matchMaterial(m) == null) {
                    warnings.add("player-buildings.types." + d.key() + ": nieznany blok " + m);
                }
            }
            final BuildingTemplate t = this.plugin.templates() == null ? null : this.plugin.templates().get(d.template());
            if (t == null) {
                warnings.add("player-buildings.types." + d.key() + ": brak szablonu " + d.template() + " — typ pominięty");
                parsed.remove(d.key());
            }
        }
        warnings.forEach(w -> this.plugin.getLogger().warning(w));
        this.types = Map.copyOf(parsed);
        if (this.sweepTask != null) {
            this.sweepTask.cancel();
            this.sweepTask = null;
        }
        final long sweep = Math.max(0, c.getLong("player-buildings.sweep-seconds", 30)) * 20L;
        if (sweep > 0 && this.plugin.isEnabled()) {
            this.sweepTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::sweep, sweep, sweep);
        }
    }

    private static Map<String, Object> plain(final ConfigurationSection s) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final String k : s.getKeys(false)) {
            final Object v = s.get(k);
            if (v instanceof ConfigurationSection sub) {
                out.put(k, plain(sub));
            } else if (v instanceof List<?> list) {
                final List<Object> items = new ArrayList<>();
                for (final Object o : list) {
                    items.add(o instanceof ConfigurationSection cs ? plain(cs) : o);
                }
                out.put(k, items);
            } else {
                out.put(k, v);
            }
        }
        return out;
    }

    public boolean enabled() {
        return this.enabled;
    }

    /** Registrable types in catalogue order. */
    public Collection<Def> types() {
        final List<Def> out = new ArrayList<>();
        for (final Def d : PlayerBuildingRules.DEFAULTS) {
            if (this.types.containsKey(d.key())) {
                out.add(this.types.get(d.key()));
            }
        }
        for (final Def d : this.types.values()) {
            if (!out.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    public Def type(final String key) {
        return key == null ? null : this.types.get(key.toLowerCase(java.util.Locale.ROOT));
    }

    public PlayerBuildingRules.Limits limits() {
        return this.limits;
    }

    // ------------------------------------------------------------------ registered buildings

    public static boolean isPlayer(final Building b) {
        return b != null && PlayerBuildingRules.isPlayerType(b.type());
    }

    public Def def(final Building b) {
        return isPlayer(b) ? this.types.get(PlayerBuildingRules.keyOf(b.type())) : null;
    }

    /** Template id of the kind (limits count it whether it works or not); other buildings: their own type. */
    public String kind(final Building b) {
        final Def d = this.def(b);
        return d != null ? d.template() : b.type();
    }

    /** Template id of a working registered building (Armia barracks, bonuses, trades), null otherwise. */
    public String activeKind(final Building b) {
        final Def d = this.def(b);
        return d != null && b.function() == d.function() ? d.template() : null;
    }

    public boolean working(final Building b) {
        return this.activeKind(b) != null;
    }

    public OwnBuilding meta(final Building b) {
        final String id = b.id().toString();
        if (b.countryId() != null) {
            final CountryState st = this.plugin.data().stateIfPresent(b.countryId());
            if (st != null && st.ownBuildings != null && st.ownBuildings.containsKey(id)) {
                return st.ownBuildings.get(id);
            }
        }
        for (final CountryState st : this.plugin.data().states().values()) {
            if (st.ownBuildings != null && st.ownBuildings.containsKey(id)) {
                return st.ownBuildings.get(id);
            }
        }
        return null;
    }

    private void putMeta(final Building b, final OwnBuilding meta) {
        final String id = b.id().toString();
        final CountryState target = b.countryId() == null ? null : this.plugin.data().state(b.countryId());
        for (final CountryState st : this.plugin.data().states().values()) {
            if (st != target && st.ownBuildings != null) {
                st.ownBuildings.remove(id);
            }
        }
        if (target != null) {
            target.ownBuildings().put(id, meta);
        }
        this.plugin.data().markDirty();
    }

    /** Called when any building leaves the registry. */
    public void forget(final Building b) {
        if (!isPlayer(b)) {
            return;
        }
        final String id = b.id().toString();
        for (final CountryState st : this.plugin.data().states().values()) {
            if (st.ownBuildings != null) {
                st.ownBuildings.remove(id);
            }
        }
        this.pending.remove(b.id());
        this.plugin.data().markDirty();
    }

    /** Player-facing name of the type ({@code Kuźnia (zgłoszona)}), for type ids. */
    public String typeName(final String type) {
        final String key = PlayerBuildingRules.keyOf(type);
        final Def d = key == null ? null : this.types.get(key);
        return (d == null ? key : d.name()) + " (zgłoszony)";
    }

    /** MiniMessage label of a building: its escaped name and type, or the type. */
    public String label(final Building b) {
        final OwnBuilding m = this.meta(b);
        final String type = this.typeName(b.type());
        if (m != null && m.name() != null && !m.name().isBlank()) {
            return MiniMessage.miniMessage().escapeTags(m.name()) + " (" + type + ")";
        }
        return type;
    }

    public String plainLabel(final Building b) {
        final OwnBuilding m = this.meta(b);
        final String type = this.typeName(b.type());
        return m != null && m.name() != null && !m.name().isBlank() ? m.name() + " (" + type + ")" : type;
    }

    public List<Building> of(final UUID country) {
        final List<Building> out = new ArrayList<>();
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (isPlayer(b)) {
                out.add(b);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ scanning

    private static boolean loaded(final World w, final BlockBox box) {
        for (final ChunkRef c : box.chunks()) {
            if (!w.isChunkLoaded(c.cx(), c.cz())) {
                return false;
            }
        }
        return true;
    }

    /** Counts the key blocks of the outline (chunks must be loaded). */
    public static Scan scan(final World w, final BlockBox box, final Set<String> materials) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final List<BlockPos> containers = new ArrayList<>();
        final List<BlockPos> halfBeds = new ArrayList<>();
        int whole = 0;
        final String world = w.getName();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    final Block b = w.getBlockAt(x, y, z);
                    final Material type = b.getType();
                    if (type.isAir()) {
                        continue;
                    }
                    if (Tag.BEDS.isTagged(type)) {
                        final BlockData data = b.getBlockData();
                        if (data instanceof Bed bed) {
                            final boolean head = bed.getPart() == Bed.Part.HEAD;
                            final int[] other = head ? PlayerBuildingRules.bedFoot(x, y, z, bed.getFacing().name())
                                : new int[] {x + bed.getFacing().getModX(), y, z + bed.getFacing().getModZ()};
                            final boolean inside = box.contains(world, other[0], other[1], other[2]);
                            if (head && inside) {
                                whole++;
                            } else if (!inside) {
                                halfBeds.add(new BlockPos(world, x, y, z));
                            }
                        }
                        continue;
                    }
                    if (type == Material.WATER) {
                        if (b.getBlockData() instanceof Levelled l && l.getLevel() == 0) {
                            counts.merge(PlayerBuildingRules.WATER, 1, Integer::sum);
                        }
                        continue;
                    }
                    if (type == Material.CHEST || type == Material.BARREL) {
                        counts.merge(PlayerBuildingRules.CONTAINER, 1, Integer::sum);
                        containers.add(new BlockPos(world, x, y, z));
                    }
                    if (ANVILS.contains(type)) {
                        counts.merge(PlayerBuildingRules.ANVIL, 1, Integer::sum);
                    }
                    if (Tag.CANDLES.isTagged(type)) {
                        counts.merge(PlayerBuildingRules.CANDLE, 1, Integer::sum);
                    }
                    if (materials.contains(type.name())) {
                        counts.merge(type.name(), 1, Integer::sum);
                    }
                }
            }
        }
        counts.put(PlayerBuildingRules.BED, whole);
        return new Scan(counts, containers, halfBeds, whole);
    }

    // ------------------------------------------------------------------ registration

    /** Owner of every chunk of the box, or the problem (first chunk that is not the country's). */
    private String landProblem(final UUID country, final BlockBox box) {
        for (final ChunkRef c : box.chunks()) {
            final KrajInfo owner = this.plugin.kraje().getCountryAt(c.world(), c.cx(), c.cz()).orElse(null);
            if (owner == null || !owner.id().equals(country)) {
                final int x0 = Math.max(box.minX(), c.cx() << 4);
                final int z0 = Math.max(box.minZ(), c.cz() << 4);
                final int x1 = Math.min(box.maxX(), (c.cx() << 4) + 15);
                final int z1 = Math.min(box.maxZ(), (c.cz() << 4) + 15);
                return "obrys wychodzi poza ziemię kraju: bloki x " + x0 + "…" + x1 + ", z " + z0 + "…" + z1 + " (chunk " + c.cx() + ", "
                    + c.cz() + ") " + (owner == null ? "to dzika ziemia" : "należy do kraju " + owner.name());
            }
        }
        return null;
    }

    /** Every problem of a registration (Polish), empty = it can be registered. Chunks must be loaded for the scan. */
    public List<String> check(final UUID country, final World w, final BlockBox box, final Def d, final Scan scan) {
        final List<String> out = new ArrayList<>();
        final String size = PlayerBuildingRules.sizeProblem(box.sizeX(), box.sizeY(), box.sizeZ(), box.volume(), this.limits,
            d.minVolume());
        if (size != null) {
            out.add(size);
        }
        if (box.minY() <= w.getMinHeight() || box.maxY() >= w.getMaxHeight()) {
            out.add("obrys wychodzi poza wysokość świata");
        }
        final String land = this.landProblem(country, box);
        if (land != null) {
            out.add(land);
        }
        final String conflict = this.plugin.construction().conflict(box);
        if (conflict != null) {
            out.add("obrys nachodzi na " + conflict);
        }
        out.addAll(this.requirementProblems(country, d));
        if (scan != null) {
            final List<Shortage> missing = PlayerBuildingRules.shortages(d, scan.counts());
            if (!missing.isEmpty()) {
                out.add("w obrysie " + box + " brakuje: " + PlayerBuildingRules.describe(missing));
            }
            if (d.materials().contains(PlayerBuildingRules.BED) && !scan.halfBeds().isEmpty()) {
                final List<String> at = new ArrayList<>();
                for (final BlockPos p : scan.halfBeds().subList(0, Math.min(3, scan.halfBeds().size()))) {
                    at.add(p.toString());
                }
                out.add("łóżka wystające poza obrys nie liczą się: " + String.join("; ", at)
                    + (scan.halfBeds().size() > 3 ? " i " + (scan.halfBeds().size() - 3) + " więcej" : "") + " — powiększ zaznaczenie");
            }
        }
        return out;
    }

    /** Limits and requirements of the template of the kind (epoch, discovery, level, population, treasury, limits). */
    public List<String> requirementProblems(final UUID country, final Def d) {
        final List<String> out = new ArrayList<>();
        final BuildingTemplate t = this.plugin.templates().get(d.template());
        if (t == null) {
            out.add("typ " + d.key() + " nie ma szablonu " + d.template());
            return out;
        }
        final BuildingTemplate.Meta m = t.meta();
        final BuildingChooser.Input in = this.plugin.settlement().input(country, Long.MAX_VALUE);
        int registeredOfType = 0;
        int registeredTotal = 0;
        for (final Building b : this.of(country)) {
            registeredTotal++;
            if (d.key().equals(PlayerBuildingRules.keyOf(b.type()))) {
                registeredOfType++;
            }
        }
        final String limit = PlayerBuildingRules.limitProblem(d, m.maxPerCountry(), in.count(m.id()), registeredOfType,
            this.maxTotal, registeredTotal);
        if (limit != null) {
            out.add(limit);
        }
        for (final String s : this.plugin.settlement().requirementsMissing(m, in, false)) {
            if (s.startsWith("epoka") || s.startsWith("odkrycie")) {
                out.add("wymaga " + s);
            } else if (this.requirements) {
                out.add("wymaga (jak zamawiany budynek): " + s);
            }
        }
        return out;
    }

    /** Registers the outline as a building of type {@code d} of {@code country}; nothing changes on a problem. */
    public Outcome register(final UUID country, final String actor, final UUID actorId, final BlockBox box, final Def d,
                            final String name) {
        final World w = Bukkit.getWorld(box.world());
        if (w == null) {
            return new Outcome(null, List.of("nieznany świat " + box.world()));
        }
        final List<String> problems = new ArrayList<>();
        final String nameProblem = PlayerBuildingRules.nameProblem(name);
        if (nameProblem != null) {
            problems.add(nameProblem);
        }
        if (!loaded(w, box)) {
            problems.add("część obrysu jest w niewczytanych chunkach — podejdź bliżej budynku");
            return new Outcome(null, problems);
        }
        final Scan scan = box.volume() <= this.limits.maxVolume() ? scan(w, box, d.materials()) : null;
        problems.addAll(this.check(country, w, box, d, scan));
        if (!problems.isEmpty()) {
            return new Outcome(null, problems);
        }
        final BlockPos spawn = d.function().housing() ? BuildingService.findSpawn(w, box) : null;
        final Building b = this.plugin.buildings().add(country, PlayerBuildingRules.typeId(d.key()), d.function(), box,
            scan.wholeBeds(), spawn, 0, true);
        this.plugin.data().state(country).origins().put(b.id().toString(), actor == null ? "gracz" : actor);
        this.putMeta(b, new OwnBuilding(d.key(), name == null || name.isBlank() ? null : name.trim(), actor, actorId,
            b.createdAt(), null, System.currentTimeMillis()));
        if (d.function() == BuildingFunction.GRANARY) {
            this.plugin.granary().addAll(country, scan.containers());
        }
        this.plugin.getLogger().info("Zgłoszono budynek " + d.key() + " (" + b.id().toString().substring(0, 8) + ") kraju "
            + this.plugin.countryName(country) + " przez " + actor + " w " + box);
        return new Outcome(b, List.of());
    }

    /** Takes a registered player building out of the registry (blocks stay). */
    public void unregister(final Building b) {
        this.forget(b);
        this.plugin.buildings().remove(b);
        if (b.countryId() != null) {
            this.plugin.data().state(b.countryId()).origins().remove(b.id().toString());
        }
        this.plugin.getLogger().info("Wyrejestrowano zgłoszony budynek " + b.type() + " (" + b.id().toString().substring(0, 8) + ")");
    }

    // ------------------------------------------------------------------ upkeep

    /** A block changed: recheck the registered building containing it a moment later (debounced). */
    public void changed(final Block block) {
        final Building b = this.plugin.data().buildingAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (isPlayer(b)) {
            this.schedule(b.id());
        }
    }

    public void schedule(final UUID building) {
        this.pending.add(building);
        if (!this.scheduled && this.plugin.isEnabled()) {
            this.scheduled = true;
            Bukkit.getScheduler().runTaskLater(this.plugin, this::flush, this.delayTicks);
        }
    }

    private void flush() {
        this.scheduled = false;
        final List<UUID> ids = List.copyOf(this.pending);
        this.pending.clear();
        for (final UUID id : ids) {
            final Building b = this.plugin.data().building(id);
            if (b != null) {
                this.recheck(b, true);
            }
        }
    }

    /** One loaded registered building per sweep (catches block changes no event reported). */
    private void sweep() {
        final List<Building> all = new ArrayList<>();
        for (final Building b : this.plugin.data().allBuildings()) {
            if (isPlayer(b)) {
                all.add(b);
            }
        }
        if (all.isEmpty()) {
            return;
        }
        all.sort(java.util.Comparator.comparing(b -> b.id().toString()));
        for (int i = 0; i < all.size(); i++) {
            final Building b = all.get(Math.floorMod(this.sweepCursor++, all.size()));
            final World w = Bukkit.getWorld(b.box().world());
            if (w != null && loaded(w, b.box())) {
                this.recheck(b, true);
                return;
            }
        }
    }

    /**
     * Re-counts the key blocks: function on when every need is met, {@link BuildingFunction#OTHER} otherwise; beds,
     * spawn and granary containers follow the blocks. Returns the scan, or null when the building cannot be checked
     * now (unknown type, unloaded chunks).
     */
    public Scan recheck(final Building current, final boolean notify) {
        final Def d = this.def(current);
        final World w = Bukkit.getWorld(current.box().world());
        if (d == null || w == null || !loaded(w, current.box())) {
            return null;
        }
        final Scan scan = scan(w, current.box(), d.materials());
        final List<Shortage> missing = PlayerBuildingRules.shortages(d, scan.counts());
        final boolean active = missing.isEmpty();
        final boolean wasActive = current.function() == d.function();
        Building b = current;
        if (b.beds() != scan.wholeBeds()) {
            b = b.withBeds(scan.wholeBeds());
        }
        if (d.function().housing()) {
            final BlockPos spawn = scan.wholeBeds() > 0 ? BuildingService.findSpawn(w, b.box()) : null;
            if (!java.util.Objects.equals(spawn, b.spawn()) && (spawn != null || scan.wholeBeds() == 0)) {
                b = b.withSpawn(spawn);
            }
        }
        final BuildingFunction function = active ? d.function() : BuildingFunction.OTHER;
        if (b.function() != function) {
            b = new Building(b.id(), b.countryId(), b.type(), function, b.box(), b.beds(), b.spawn(), b.rotation(), b.createdAt(),
                b.registered(), b.dye(), b.level());
        }
        if (b != current) {
            this.plugin.data().putBuilding(b);
        }
        if (d.function() == BuildingFunction.GRANARY && b.countryId() != null) {
            this.syncContainers(b, active ? scan.containers() : List.of());
        }
        final String problem = active ? null : PlayerBuildingRules.describe(missing);
        final OwnBuilding meta = this.meta(b);
        final OwnBuilding base = meta != null ? meta
            : new OwnBuilding(d.key(), null, null, null, b.createdAt(), null, 0);
        this.putMeta(b, base.withProblem(problem, System.currentTimeMillis()));
        if (notify && b.countryId() != null && active != wasActive) {
            final String pos = b.box().centerX() + " " + b.box().minY() + " " + b.box().centerZ();
            if (active) {
                this.plugin.notifyCountry(b.countryId(), "budynek.restored", Placeholder.parsed("name", this.label(b)),
                    Placeholder.unparsed("pos", pos));
            } else {
                this.plugin.notifyCountry(b.countryId(), "budynek.broken", Placeholder.parsed("name", this.label(b)),
                    Placeholder.unparsed("pos", pos), Placeholder.unparsed("missing", problem));
            }
            this.plugin.getLogger().info("Zgłoszony budynek " + b.type() + " (" + b.id().toString().substring(0, 8) + ") "
                + (active ? "znów działa" : "nieczynny: brak " + problem));
        }
        return scan;
    }

    /** Granary points of the building = exactly the given containers. */
    private void syncContainers(final Building b, final List<BlockPos> containers) {
        final GranaryService g = this.plugin.granary();
        final CountryState st = this.plugin.data().stateIfPresent(b.countryId());
        if (st != null) {
            for (final BlockPos p : List.copyOf(st.chests())) {
                if (b.box().contains(p.world(), p.x(), p.y(), p.z()) && !containers.contains(p)) {
                    g.removePos(p);
                }
            }
        }
        final List<BlockPos> add = new ArrayList<>();
        for (final BlockPos p : containers) {
            if (!b.countryId().equals(g.countryOf(p))) {
                add.add(p);
            }
        }
        if (!add.isEmpty()) {
            g.addAll(b.countryId(), add);
        }
    }
}
