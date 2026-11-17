// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.planner.MineDigger;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Mines: after a mine ({@code kopalnia}) is built its miners dig a gallery from the bottom of the shaft — a staircase
 * down to {@code mines.depth} below the shaft, then a level corridor with rails, timber frames and torches
 * ({@link MineDigger}). The gallery grows one step every {@link #PERIOD} ticks during work hours while extension
 * steps are paid for. Actual drops and lighting costs go through an all-or-nothing physical storage commit; no virtual daily yield.
 * The head chunk gets a plugin chunk ticket while digging during work hours (one per digging mine, at most
 * {@code max-per-country} mines per country; not counted in {@code construction.max-loaded-chunks-total}).
 * Main thread only.
 */
public final class MineService {

    /** Ticks between two gallery steps of a mine. */
    public static final long PERIOD = 40L;

    /** Indices of a mine record in {@link CountryState#mines()}. */
    static final int X = 0;
    static final int Y = 1;
    static final int Z = 2;
    static final int DIR = 3;
    static final int LENGTH = 4;
    static final int REMAINING = 5;
    static final int BOTTOM = 6;
    static final int WORLD_HASH = 7;
    /** Since mine levels: level, floors, ore mined, branch steps left, main direction, headframe built, floor start. */
    static final int LEVEL = 8;
    static final int FLOORS = 9;
    static final int ORES = 10;
    static final int BRANCH = 11;
    static final int MAIN_DIR = 12;
    static final int HEADFRAME = 13;
    static final int FLOOR_START = 14;
    /** Since miners: digging progress of the miners in thousandths of a step; blocked until (epoch minute). */
    static final int PROGRESS = 15;
    static final int BLOCKED = 16;
    static final int SIZE = 17;
    private static final int LEVELS_SIZE = 15;

    /** Records written before mine levels are extended (level 1, one floor); older ones get the new fields as 0. */
    static int[] normalise(final int[] m) {
        if (m.length >= SIZE) {
            return m;
        }
        final int[] out = java.util.Arrays.copyOf(m, SIZE);
        if (m.length < LEVELS_SIZE) {
            out[LEVEL] = Math.max(1, out[LEVEL]);
            out[FLOORS] = Math.max(1, out[FLOORS]);
            out[MAIN_DIR] = out[DIR];
        }
        return out;
    }

    /** Shaft doorway of the raw (unrotated) mine template: walking cell and gallery direction (east). */
    static final int[] SHAFT_EXIT = {6, 1, 4};
    static final int SHAFT_DIR = 1;

    private final LudnoscPlugin plugin;
    private final Set<String> tickets = new HashSet<>();
    /** Visible miners at the gallery head (Mannequins, only with a player nearby). */
    private final Map<UUID, MineCrew> crews = new java.util.HashMap<>();
    /** Mines whose loaded store refused work (full, not bound): no chunk tickets until this time (epoch ms). */
    private final Map<UUID, Long> idleUntil = new java.util.HashMap<>();
    /** Runs of {@link #tick()}; orphaned mine buildings are looked for every {@link #ADOPT_EVERY} runs. */
    private long runs;
    static final int ADOPT_EVERY = 30;
    static final int ADOPT_MAX_CHUNKS = 48;
    /** Orphaned mines whose gallery is being traced: chunks held loaded for the trace. */
    private final Map<UUID, Set<String>> adoptChunks = new java.util.HashMap<>();
    static final int OBSTACLE_MINUTES = 10;
    /** Last obstacle logged per mine (one log line per obstacle). */
    private final Map<UUID, String> obstacles = new java.util.HashMap<>();
    static final long IDLE_RETRY_MILLIS = 5 * 60_000L;

    /** Runtime crew of one mine: bodies and the last head cells (the bodies stand in the gallery behind the head). */
    private static final class MineCrew {
        final BuilderCrew crew = new BuilderCrew();
        final java.util.ArrayDeque<org.bukkit.Location> trail = new java.util.ArrayDeque<>();
    }

    public MineService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    /** Registers a finished mine and starts its first (free) gallery section. */
    public void register(final Building b, final BuildingTemplate raw, final int rotation, final int minX, final int minY,
                         final int minZ) {
        if (b.countryId() == null || b.function() != BuildingFunction.MINE) {
            return;
        }
        this.data().state(b.countryId()).mines().put(b.id().toString(),
            start(raw.width(), raw.depth(), rotation, minX, minY, minZ, this.plugin.settings().mineExtension,
                this.plugin.settings().mineDepth));
        this.data().markDirty();
    }

    /** New gallery record at the shaft exit of a mine template (width x depth) placed at (minX, minY, minZ). */
    static int[] start(final int width, final int depth, final int rotation, final int minX, final int minY, final int minZ,
                       final int extension, final int mineDepth) {
        final int[] p = BuildingTemplate.rotatePoint(SHAFT_EXIT[0], SHAFT_EXIT[2], width, depth, rotation);
        final int y = minY + SHAFT_EXIT[1];
        return normalise(new int[] {minX + p[0], y, minZ + p[1], Math.floorMod(SHAFT_DIR + rotation, 4), 0, extension,
            y - mineDepth, 0});
    }

    /**
     * Continues a fresh record ({@link #start}) from a gallery found in the world: head, direction, length, floors and
     * level follow the trace; the free first section counts as used as far as the old gallery reaches.
     */
    static int[] resume(final int[] rec, final pl.medivalrpg.ludnosc.planner.MineTrace.Trace t, final int extension,
                        final int era) {
        final int[] out = normalise(rec.clone());
        if (t == null || t.length() <= 0) {
            return out;
        }
        out[X] = t.head()[0];
        out[Y] = t.head()[1];
        out[Z] = t.head()[2];
        out[DIR] = t.dir();
        out[MAIN_DIR] = t.dir();
        out[LENGTH] = t.length();
        out[REMAINING] = Math.max(0, Math.min(out[REMAINING], extension - t.length()));
        if (t.lowestY() < out[BOTTOM]) {
            final int deeper = (out[BOTTOM] - t.lowestY() + pl.medivalrpg.ludnosc.planner.MinePlanner.FLOOR_DEPTH - 1)
                / pl.medivalrpg.ludnosc.planner.MinePlanner.FLOOR_DEPTH;
            out[BOTTOM] -= deeper * pl.medivalrpg.ludnosc.planner.MinePlanner.FLOOR_DEPTH;
            out[FLOORS] = 1 + deeper;
            out[FLOOR_START] = t.length();
        }
        out[LEVEL] = pl.medivalrpg.ludnosc.planner.MinePlanner.level(out[LENGTH], out[FLOORS], Math.min(2, era));
        return out;
    }

    /**
     * A mine building changes owner (land claimed, country disbanded): its gallery record goes with it instead of being
     * dropped with the old owner's state. Ownerless mines lose the record; {@link #adoptOrphans} restores it from the
     * world when a country takes the land.
     */
    public void ownerChanged(final Building b, final UUID newOwner) {
        if (b == null || b.function() != BuildingFunction.MINE || java.util.Objects.equals(b.countryId(), newOwner)) {
            return;
        }
        final CountryState old = b.countryId() == null ? null : this.data().stateIfPresent(b.countryId());
        if (!moveRecord(old == null ? null : old.mines, newOwner == null ? null : this.data().state(newOwner).mines(),
            b.id().toString())) {
            return;
        }
        this.data().markDirty();
    }

    /** Moves the record {@code id} from one country's mines to another's (never over an existing one). */
    static boolean moveRecord(final Map<String, int[]> from, final Map<String, int[]> to, final String id) {
        final int[] rec = from == null ? null : from.remove(id);
        if (rec == null) {
            return false;
        }
        if (to != null) {
            to.putIfAbsent(id, normalise(rec));
        }
        return true;
    }

    /**
     * Mine buildings of a country without a gallery record (taken over from another country before records followed
     * the owner): the gallery is traced from the shaft exit in the loaded world and the record continues from its real
     * head. The chunks the trace needs get plugin tickets (asynchronous loads, at most {@link #ADOPT_MAX_CHUNKS} per
     * mine) and the trace is repeated every run until it completes; the existing corridor is never dug again.
     */
    void adoptOrphans() {
        final Set<UUID> orphans = new HashSet<>();
        for (final Building b : List.copyOf(this.data().allBuildings())) {
            if (b.function() != BuildingFunction.MINE || b.countryId() == null
                || this.plugin.royalRebuild() != null && this.plugin.royalRebuild().blocks(b.countryId())) {
                continue;
            }
            final CountryState st = this.data().stateIfPresent(b.countryId());
            if (st != null && st.mines != null && st.mines.containsKey(b.id().toString())) {
                continue;
            }
            final BuildingTemplate raw = this.plugin.templates().get(b.type());
            final World w = Bukkit.getWorld(b.box().world());
            if (raw == null || w == null) {
                continue;
            }
            final int[] rec = start(raw.width(), raw.depth(), b.rotation(), b.box().minX(), b.box().minY(), b.box().minZ(),
                this.plugin.settings().mineExtension, this.plugin.settings().mineDepth);
            final String[] missing = new String[1];
            final var trace = pl.medivalrpg.ludnosc.planner.MineTrace.trace((x, y, z) -> {
                if (w.isChunkLoaded(x >> 4, z >> 4)) {
                    return w.getBlockAt(x, y, z).getType().getKey().toString();
                }
                missing[0] = w.getName() + ":" + (x >> 4) + ":" + (z >> 4);
                return null;
            }, rec[X], rec[Y], rec[Z], rec[DIR], this.plugin.settings().mineMaxLength);
            if (trace == null) { // part of the gallery is not loaded: load it (asynchronously) and look again
                orphans.add(b.id());
                final Set<String> chunks = this.adoptChunks.computeIfAbsent(b.id(), k -> new java.util.LinkedHashSet<>());
                if (missing[0] != null && chunks.size() < ADOPT_MAX_CHUNKS) {
                    chunks.add(missing[0]);
                }
                continue;
            }
            final int[] resumed = resume(rec, trace, this.plugin.settings().mineExtension, this.plugin.postep().era(b.countryId()));
            this.data().state(b.countryId()).mines().put(b.id().toString(), resumed);
            this.data().markDirty();
            this.plugin.getLogger().info("Kopalnia " + b.id() + " (" + b.box().centerX() + " " + b.box().minY() + " "
                + b.box().centerZ() + "): przywrócono zapis chodnika — długość " + resumed[LENGTH] + ", przodek "
                + resumed[X] + " " + resumed[Y] + " " + resumed[Z] + ".");
        }
        this.adoptChunks.keySet().retainAll(orphans);
    }

    /** Mines of a country whose building still exists: building → record. */
    public List<Map.Entry<Building, int[]>> mines(final UUID country) {
        final CountryState st = this.data().stateIfPresent(country);
        final List<Map.Entry<Building, int[]>> out = new ArrayList<>();
        if (st == null) {
            return out;
        }
        st.mines().entrySet().removeIf(e -> {
            final Building b = this.building(e.getKey());
            return b == null || !country.equals(b.countryId());
        });
        st.mines().replaceAll((k, v) -> normalise(v));
        for (final var e : st.mines().entrySet()) {
            out.add(Map.entry(this.building(e.getKey()), e.getValue()));
        }
        return out;
    }

    private Building building(final String id) {
        try {
            return this.data().building(UUID.fromString(id));
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /** Daily income of a country's mines: gallery blocks × income per block × level / epoch factor. */
    public long income(final UUID country) {
        return 0; // Physical ore is not converted into an additional daily treasury credit.
    }

    /** Ore found by the miners of a country per day (grows with the mine level). */
    public long dailyOre(final UUID country) {
        long n = 0;
        for (final var e : this.mines(country)) {
            n += pl.medivalrpg.ludnosc.planner.MinePlanner.dailyOre(e.getValue()[LEVEL]);
        }
        return n;
    }

    /** Total gallery length of the country's mines. */
    public long galleryBlocks(final UUID country) {
        long n = 0;
        for (final var e : this.mines(country)) {
            n += e.getValue()[LENGTH];
        }
        return n;
    }

    /** Level 1..5 of a mine building (1 when unknown). */
    public int levelOf(final UUID country, final UUID building) {
        final CountryState st = this.data().stateIfPresent(country);
        final int[] m = st == null || st.mines == null ? null : st.mines.get(building.toString());
        return m == null ? 1 : Math.max(1, normalise(m)[LEVEL]);
    }

    /** Miners working in a mine (citizens assigned by the job board). */
    public int miners(final UUID country, final UUID building) {
        return this.plugin.work().workersAt(country, building);
    }

    /** Tool factor of a country's miners (smithy, epoch). */
    public double tools(final UUID country) {
        final boolean smithy = this.data().buildingsOf(country).stream()
            .anyMatch(b -> b.function() == BuildingFunction.SMITHY);
        return this.plugin.settings().work.mineYield.toolFactor(smithy, this.plugin.postep().era(country));
    }

    /** Expected daily output of one mine (miners x level x tools x mood). */
    public pl.medivalrpg.ludnosc.logic.MineYield.Output dailyYield(final UUID country, final Building b, final int[] m) {
        return this.plugin.settings().work.mineYield.daily(this.miners(country, b.id()), Math.max(1, m[LEVEL]), this.tools(country),
            this.plugin.zycie().moodMultiplier(country));
    }

    /** Expected daily output of all mines of a country. */
    public pl.medivalrpg.ludnosc.logic.MineYield.Output dailyYield(final UUID country) {
        pl.medivalrpg.ludnosc.logic.MineYield.Output sum = pl.medivalrpg.ludnosc.logic.MineYield.NONE;
        for (final var e : this.mines(country)) {
            sum = sum.plus(this.dailyYield(country, e.getKey(), e.getValue()));
        }
        return sum;
    }

    /** The mine digs by itself now: miners, auto digging on, not at the maximum length, not blocked. */
    private boolean autoDigging(final UUID country, final Building b, final int[] m) {
        return this.plugin.settings().work.mineAutoDig && m[LENGTH] < this.plugin.settings().mineMaxLength
            && m[BLOCKED] <= nowMinute() && this.miners(country, b.id()) > 0;
    }

    /** Auto digging is blocked (water, lava or the border all around) until this epoch minute; 0 = not blocked. */
    public static int blockedUntil(final int[] m) {
        return m.length > BLOCKED ? m[BLOCKED] : 0;
    }

    static int nowMinute() {
        return (int) (System.currentTimeMillis() / 60_000L);
    }

    /** Result of an extension order. */
    public enum Extension { ORDERED, NO_MINE, BUSY, MAX, FUNDS }

    /**
     * Pays for one more gallery section ({@code mines.extension-blocks} steps) of the mine (prefix of its id, or
     * {@code null} = the shortest idle mine).
     */
    public Extension extend(final UUID country, final String idPrefix, final boolean pay) {
        int[] best = null;
        boolean any = false;
        boolean busy = false;
        for (final var e : this.mines(country)) {
            if (idPrefix != null && !e.getKey().id().toString().startsWith(idPrefix.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            any = true;
            final int[] m = e.getValue();
            if (m[REMAINING] > 0) {
                busy = true;
                continue;
            }
            if (m[LENGTH] >= this.plugin.settings().mineMaxLength) {
                continue;
            }
            if (best == null || m[LENGTH] < best[LENGTH]) {
                best = m;
            }
        }
        if (!any) {
            return Extension.NO_MINE;
        }
        if (best == null) {
            return busy ? Extension.BUSY : Extension.MAX;
        }
        final long cost = this.plugin.settings().mineExtensionCost;
        if (pay && cost > 0) {
            final SkarbiecApi eco = this.plugin.skarbiec();
            if (eco == null || !eco.withdraw(SkarbiecApi.countryAccount(country), cost, "Ludnosc: rozbudowa kopalni").ok()) {
                return Extension.FUNDS;
            }
        }
        best[REMAINING] = Math.min(this.plugin.settings().mineExtension, this.plugin.settings().mineMaxLength - best[LENGTH]);
        this.data().markDirty();
        return Extension.ORDERED;
    }

    /** A mine of the country can be extended (idle, below the maximum length). */
    public boolean extendable(final UUID country) {
        for (final var e : this.mines(country)) {
            final int[] m = e.getValue();
            if (m[REMAINING] == 0 && m[LENGTH] < this.plugin.settings().mineMaxLength) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every {@link #PERIOD} ticks during work hours: paid sections are dug one step per run; besides that the miners
     * drive the gallery forward by themselves ({@code mines.steps-per-miner-hour} per miner). The head chunk of a
     * digging mine gets a chunk ticket (paid sections always, auto digging at most {@code mines.max-auto-tickets}).
     */
    public void tick() {
        if (this.runs++ % ADOPT_EVERY == 0 || !this.adoptChunks.isEmpty()) {
            this.adoptOrphans();
        }
        final Set<String> wanted = new HashSet<>();
        if (this.plugin.settings().keepLoaded) {
            for (final Set<String> chunks : this.adoptChunks.values()) {
                for (final String c : chunks) {
                    final String[] p = c.split(":");
                    final World w = Bukkit.getWorld(p[0]);
                    if (w != null) {
                        this.want(wanted, w, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    }
                }
            }
        }
        final Set<UUID> crewed = new HashSet<>();
        final long now = System.currentTimeMillis();
        int autoTickets = 0;
        for (final Map.Entry<UUID, CountryState> ce : this.data().states().entrySet()) {
            if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(ce.getKey()))continue;
            final UUID country = ce.getKey();
            if (ce.getValue().mines == null || ce.getValue().mines.isEmpty()) {
                continue;
            }
            for (final var e : this.mines(country)) {
                final Building b = e.getKey();
                final int[] m = e.getValue();
                final boolean paid = m[REMAINING] > 0;
                final boolean auto = !paid && this.autoDigging(country, b, m);
                if (!paid && !auto) {
                    continue;
                }
                final World w = Bukkit.getWorld(b.box().world());
                if (w == null || !this.plugin.settings().workTime(w.getTime())) {
                    continue;
                }
                // The chunk tickets come before the store check: the store (and the head) of a mine nobody stands near
                // is unloaded, so without a ticket the mine would only ever work while a player is around.
                final pl.medivalrpg.ludnosc.model.BlockPos store = this.plugin.production().storeAnchor(b);
                final boolean storeHere = store.world().equals(w.getName());
                final boolean loaded = this.loadedAround(w, m[X], m[Z])
                    && (!storeHere || w.isChunkLoaded(store.x() >> 4, store.z() >> 4));
                final boolean busy = this.plugin.production().busy(b);
                final boolean ready = !busy && this.plugin.production().ready(b);
                if (wantsTickets(ready, busy, loaded, this.idleUntil.getOrDefault(b.id(), 0L), now)) {
                    this.idleUntil.remove(b.id());
                    if (this.plugin.settings().keepLoaded
                        && (paid || autoTickets < this.plugin.settings().work.mineMaxAutoTickets)) {
                        if (!paid) {
                            autoTickets++;
                        }
                        this.want(wanted, w, m[X] >> 4, m[Z] >> 4);
                        if (storeHere) {
                            this.want(wanted, w, store.x() >> 4, store.z() >> 4);
                        }
                    }
                } else if (loaded && !ready && !busy) {
                    // the store is loaded and says no (full, not bound, audit): no tickets for a while
                    this.idleUntil.put(b.id(), now + IDLE_RETRY_MILLIS);
                }
                if (!ready) {
                    continue;
                }
                if (auto) {
                    final double steps = this.plugin.settings().work.mineYield.steps(this.miners(country, b.id()), PERIOD * 50L);
                    m[PROGRESS] = (int) Math.min(3000, m[PROGRESS] + Math.max(0, Math.round(steps * 1000)));
                    this.data().markDirty();
                }
                if (!this.loadedAround(w, m[X], m[Z])) {
                    continue;
                }
                if (this.crew(country, b, m, w)) {
                    crewed.add(b.id());
                }
                if (paid) {
                    this.step(country, b, m, w, true);
                } else if (m[PROGRESS] >= 1000) {
                    this.step(country, b, m, w, false);
                }
            }
        }
        for (final var it = this.tickets.iterator(); it.hasNext();) {
            final String t = it.next();
            if (!wanted.contains(t)) {
                it.remove();
                this.release(t);
            }
        }
        this.idleUntil.values().removeIf(t -> t <= now);
        for (final var it = this.crews.entrySet().iterator(); it.hasNext();) {
            final var e = it.next();
            if (!crewed.contains(e.getKey())) {
                e.getValue().crew.remove();
                it.remove();
            }
        }
    }

    /**
     * Visible miners at the gallery head when a player is within 40 blocks of it: half as many bodies as miners (at
     * least one, at most {@code mines.crew-max}) with the pickaxe of the country's tools. True when a crew stands there.
     */
    private boolean crew(final UUID country, final Building b, final int[] m, final World w) {
        final int max = this.plugin.settings().work.mineCrewMax;
        final org.bukkit.Location head = new org.bukkit.Location(w, m[X] + 0.5, m[Y], m[Z] + 0.5);
        boolean near = false;
        for (final org.bukkit.entity.Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(head) <= 40 * 40) {
                near = true;
                break;
            }
        }
        if (!near || max <= 0) {
            return false;
        }
        final MineCrew mc = this.crews.computeIfAbsent(b.id(), k -> new MineCrew());
        final int miners = this.miners(country, b.id());
        final int wanted = Math.max(1, Math.min(max, (int) Math.ceil(miners / 2.0)));
        if (mc.trail.isEmpty() || !mc.trail.peekFirst().equals(head)) {
            mc.trail.addFirst(head);
            while (mc.trail.size() > wanted + 1) {
                mc.trail.removeLast();
            }
        }
        mc.crew.prune();
        if (mc.crew.bodies.size() != wanted) {
            if (mc.crew.bodies.size() > wanted) {
                mc.crew.remove();
            }
            mc.crew.ensure(this.plugin, b.id(), head, wanted, "Górnik");
            final boolean smithy = this.data().buildingsOf(country).stream().anyMatch(x -> x.function() == BuildingFunction.SMITHY);
            final org.bukkit.Material pick = org.bukkit.Material.IRON_PICKAXE;
            for (final BuilderCrew.Body body : mc.crew.bodies) {
                if (body.entity() instanceof org.bukkit.entity.LivingEntity le && le.getEquipment() != null && pick != null) {
                    le.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(pick));
                }
            }
        }
        final int[] d = {0, -1, 1, 0, 0, 1, -1, 0};
        final int dir = Math.floorMod(m[DIR], 4);
        final org.bukkit.Location face = head.clone().add(d[dir * 2], 1, d[dir * 2 + 1]);
        int i = 0;
        for (final org.bukkit.Location cell : mc.trail) {
            if (i >= mc.crew.bodies.size()) {
                break;
            }
            final BuilderCrew.Body body = mc.crew.bodies.get(i++);
            body.target = cell.clone();
            body.look = face;
        }
        return true;
    }

    /** Builders' walking also moves the miners (every 2 ticks). */
    public void moveCrews(final double step) {
        for (final MineCrew mc : this.crews.values()) {
            if (!mc.crew.bodies.isEmpty()) {
                mc.crew.move(step);
            }
        }
    }

    /**
     * A digging mine holds chunk tickets on its head and store when the store accepts work (or a commit is running),
     * or when one of them is unloaded and no idle pause is running. A loaded store that refuses (full, not bound,
     * audit) gets no tickets: the caller starts an idle pause so an unattended full chest does not keep chunks loaded.
     */
    static boolean wantsTickets(final boolean ready, final boolean busy, final boolean loaded, final long idleUntil,
                                final long now) {
        if (ready || busy) {
            return true;
        }
        return !loaded && idleUntil <= now;
    }

    /** Keeps a plugin chunk ticket on (cx, cz) this run; a new ticket loads the chunk asynchronously. */
    private void want(final Set<String> wanted, final World w, final int cx, final int cz) {
        final String ticket = w.getName() + ":" + cx + ":" + cz;
        wanted.add(ticket);
        if (this.tickets.add(ticket)) {
            if (w.isChunkLoaded(cx, cz)) {
                w.addPluginChunkTicket(cx, cz, this.plugin);
            } else { // never a synchronous chunk load on the main thread
                w.getChunkAtAsync(cx, cz).thenAccept(chunk -> {
                    if (this.plugin.isEnabled() && this.tickets.contains(ticket)) {
                        chunk.addPluginChunkTicket(this.plugin);
                    }
                });
            }
        }
    }

    private boolean loadedAround(final World w, final int x, final int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!w.isChunkLoaded((x >> 4) + dx, (z >> 4) + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void step(final UUID country, final Building b, final int[] m, final World w, final boolean paid) {
        if (!this.plugin.production().ready(b)) return;
        final int[] next=m.clone();
        if(next[BRANCH]==0 && next[Y]<=next[BOTTOM]
            && next[LENGTH]-next[FLOOR_START]>=pl.medivalrpg.ludnosc.planner.MinePlanner.floorLength(next[FLOORS])
            && next[BOTTOM]-pl.medivalrpg.ludnosc.planner.MinePlanner.FLOOR_DEPTH>w.getMinHeight()+6) {
            next[BOTTOM]-=pl.medivalrpg.ludnosc.planner.MinePlanner.FLOOR_DEPTH; next[FLOORS]++; next[FLOOR_START]=next[LENGTH];
        }
        final MineDigger.Blocks blocks=(x,y,z) -> {
            if (!w.isChunkLoaded(x >> 4,z >> 4) || y <= w.getMinHeight()+1 || !this.own(country,w,x,z)) return "minecraft:lava";
            final Block block=w.getBlockAt(x,y,z);
            if (block.getState() instanceof org.bukkit.block.Container || this.plugin.design().untouchable(country,x,z)) return "minecraft:lava";
            return block.getType().getKey().toString();
        };
        if(next[BRANCH]>0) { if(--next[BRANCH]==0) next[DIR]=next[MAIN_DIR]; }
        else if(next[Y]<=next[BOTTOM]) {
            int vein=pl.medivalrpg.ludnosc.planner.MinePlanner.oreDirection(blocks,next[X],next[Y],next[Z],next[DIR],4);
            if(vein>=0) { next[MAIN_DIR]=next[DIR]; next[DIR]=vein; next[BRANCH]=5; }
        }
        final MineDigger.Result result=MineDigger.dig(blocks,next[X],next[Y],next[Z],next[DIR],1,next[BOTTOM],0,4,next[LENGTH]);
        if(result.dug()==0) { m[BLOCKED]=nowMinute()+60; this.data().markDirty(); return; }
        final java.util.Map<pl.medivalrpg.ludnosc.model.BlockPos,String> replacements=new java.util.LinkedHashMap<>();
        for(var op:result.ops()) {
            if(!w.isChunkLoaded(op.x() >> 4,op.z() >> 4) || !this.own(country,w,op.x(),op.z())) return;
            Block at=w.getBlockAt(op.x(),op.y(),op.z());
            if(at.getState() instanceof org.bukkit.block.Container || at.isLiquid()) return;
            if(!at.getType().isAir() && !org.bukkit.Tag.BASE_STONE_OVERWORLD.isTagged(at.getType())
                && !org.bukkit.Tag.DIRT.isTagged(at.getType()) && !pl.medivalrpg.ludnosc.logic.BuildFixes.soil(at.getType().name())
                && !at.getType().name().endsWith("_ORE")
                && at.getType()!=org.bukkit.Material.GRAVEL && !at.getBlockData().getAsString().equals(op.state())
                && !this.mayDig(b, w, at)) {
                this.obstacle(b, m, paid, at);
                return;
            }
            replacements.put(GranaryService.pos(at),op.state());
            if(op.state().equals("minecraft:air")) {
                Block above=at.getRelative(0,1,0);
                if(above.getType().hasGravity()) replacements.put(GranaryService.pos(above),"minecraft:cobblestone");
            }
        }
        final int hx=result.head()[0], hy=result.head()[1], hz=result.head()[2];
        for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=2;dy++) for(int dz=-1;dz<=1;dz++) {
            Block at=w.getBlockAt(hx+dx,hy+dy,hz+dz);
            if(at.getType().name().endsWith("_ORE") && this.own(country,w,at.getX(),at.getZ()))
                replacements.putIfAbsent(GranaryService.pos(at),"minecraft:cobblestone");
        }
        final java.util.List<org.bukkit.inventory.ItemStack> costs=new java.util.ArrayList<>();
        final java.util.List<ProductionStorageService.Change> supports=new java.util.ArrayList<>();
        final boolean lighting=this.plugin.getConfig().getBoolean("mines.lighting.enabled",true);
        if(lighting) {
            var configuredItem=org.bukkit.Material.matchMaterial(this.plugin.getConfig().getString("mines.lighting.item","TORCH"));
            final org.bukkit.Material lightItem=configuredItem==org.bukkit.Material.SOUL_TORCH ? org.bukkit.Material.SOUL_TORCH : org.bukkit.Material.TORCH;
            boolean lit=false;
            for(int dx=-1;dx<=1;dx++) for(int dy=0;dy<=1;dy++) for(int dz=-1;dz<=1;dz++) {
                var material=w.getBlockAt(hx+dx,hy+dy,hz+dz).getType();
                if(material==org.bukkit.Material.TORCH || material==org.bukkit.Material.WALL_TORCH
                    || material==org.bukkit.Material.SOUL_TORCH || material==org.bukkit.Material.SOUL_WALL_TORCH) lit=true;
            }
            if(pl.medivalrpg.ludnosc.logic.MineLighting.due(m[LENGTH],m[LENGTH]+result.dug(),this.plugin.getConfig().getInt("mines.lighting.spacing",7),lit)) {
                // A standing torch on the stable gallery floor, just behind the new head.
                final int tx=m[X], ty=m[Y], tz=m[Z];
                final Block target=w.getBlockAt(tx,ty,tz), floor=w.getBlockAt(tx,ty-1,tz);
                if(pl.medivalrpg.ludnosc.logic.MineLighting.canPlace(target.getType().isAir(),floor.getType().isOccluding(),
                    floor.getState() instanceof org.bukkit.block.Container,floor.getType().name().endsWith("_ORE"))
                    && !replacements.containsKey(GranaryService.pos(floor))) {
                    replacements.put(GranaryService.pos(target),lightItem.getKey().toString());
                    supports.add(ProductionStorageService.Change.of(floor,floor.getBlockData().getAsString()));
                    if(this.plugin.getConfig().getBoolean("mines.lighting.consume-from-store",true)) {
                        costs.add(new org.bukkit.inventory.ItemStack(lightItem));
                    }
                } else {
                    // Wall mount: only real air, stable existing wall, never replace rails, ore or structures.
                    boolean placed=false;
                    int[][] dirs={{0,-1},{1,0},{0,1},{-1,0}}; String[] faces={"north","east","south","west"};
                    Block cell=w.getBlockAt(tx,ty+1,tz);
                    if(cell.getType().isAir()) for(int i=0;i<4;i++) {
                        Block support=cell.getRelative(dirs[i][0],0,dirs[i][1]);
                        if(!pl.medivalrpg.ludnosc.logic.MineLighting.canPlace(cell.getType().isAir(),support.getType().isOccluding(),
                            support.getState() instanceof org.bukkit.block.Container,support.getType().name().endsWith("_ORE"))
                            || replacements.containsKey(GranaryService.pos(support))) continue;
                        String light=pl.medivalrpg.ludnosc.logic.MineLighting.wallState(faces[i]);
                        if(lightItem==org.bukkit.Material.SOUL_TORCH) light=light.replace("wall_torch","soul_wall_torch");
                        replacements.put(GranaryService.pos(cell),light);
                        supports.add(ProductionStorageService.Change.of(support,support.getBlockData().getAsString()));
                        if(this.plugin.getConfig().getBoolean("mines.lighting.consume-from-store",true)) costs.add(new org.bukkit.inventory.ItemStack(lightItem));
                        placed=true; break;
                    }
                    if(!placed) { this.plugin.production().blocked(b,"Brak bezpiecznego miejsca na oświetlenie — praca wstrzymana"); return; }
                }
            }
        }
        final java.util.List<ProductionStorageService.Change> changes=new java.util.ArrayList<>();
        final java.util.List<org.bukkit.inventory.ItemStack> drops=new java.util.ArrayList<>();
        final var tool=new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_PICKAXE);
        int oreBlocks=0;
        for(var e:replacements.entrySet()) {
            var p=e.getKey(); Block at=w.getBlockAt(p.x(),p.y(),p.z());
            if(at.getBlockData().getAsString().equals(e.getValue())) continue;
            if(at.getState() instanceof org.bukkit.block.Container || at.isLiquid()) return;
            if(!at.getType().isAir()) drops.addAll(this.plugin.production().drops(at,tool,false));
            if(at.getType().name().endsWith("_ORE")) oreBlocks++;
            changes.add(ProductionStorageService.Change.of(at,e.getValue()));
        }
        changes.addAll(supports);
        next[X]=hx; next[Y]=hy; next[Z]=hz; next[DIR]=result.dir(); next[LENGTH]+=result.dug(); next[ORES]+=oreBlocks;
        if(paid) next[REMAINING]=Math.max(0,next[REMAINING]-result.dug());
        else next[PROGRESS]=Math.max(0,next[PROGRESS]-1000);
        next[LEVEL]=pl.medivalrpg.ludnosc.planner.MinePlanner.level(next[LENGTH],next[FLOORS],Math.min(2,this.plugin.postep().era(country)));
        this.plugin.production().produce(b,changes,drops,costs,() -> { System.arraycopy(next,0,m,0,m.length); this.data().markDirty(); });
    }

    /**
     * The next gallery step meets a block the miners may not dig (not natural stone, dirt, gravel or ore — e.g. sand,
     * clay, a player's block): the step is refused as before, but visibly — status and one log line — and auto
     * digging pauses {@link #OBSTACLE_MINUTES} minutes instead of retrying every run.
     */
    private void obstacle(final Building b, final int[] m, final boolean paid, final Block at) {
        final String where = at.getX() + " " + at.getY() + " " + at.getZ();
        final String what = at.getType().getKey().getKey();
        this.plugin.production().blocked(b, "Przeszkoda w chodniku kopalni: " + what + " (" + where
            + ") — usuń ją, praca wstrzymana");
        if (!paid) {
            m[BLOCKED] = nowMinute() + OBSTACLE_MINUTES;
            this.data().markDirty();
        }
        if (!(what + "@" + where).equals(this.obstacles.put(b.id(), what + "@" + where))) {
            this.plugin.getLogger().info("Kopalnia " + b.id() + ": przeszkoda w chodniku " + what + " przy " + where
                + " — praca wstrzymana do usunięcia bloku.");
        }
    }

    /**
     * Blocks of the plugin's own galleries (cobblestone floor/walls/ceiling, posts, planks, rails, torches) and cellar
     * seals deep underground, and natural sandstone, are dug like rock: a branch or a new floor crossing an older
     * gallery used to stop the mine for good ("przeszkoda w chodniku cobblestone").
     */
    private boolean mayDig(final Building mine, final World w, final Block at) {
        final Building other = this.data().buildingAt(w.getName(), at.getX(), at.getY(), at.getZ());
        return pl.medivalrpg.ludnosc.logic.BuildFixes.mineMayDig(at.getType().name(), at.getY(),
            w.getHighestBlockYAt(at.getX(), at.getZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES),
            other != null && !other.id().equals(mine.id()));
    }

    private boolean own(final UUID country, final World w, final int x, final int z) {
        return this.plugin.kraje().getCountryAt(w.getName(), x >> 4, z >> 4).map(KrajInfo::id).map(country::equals).orElse(false);
    }

    /** Industrial epoch: a steel headframe with a winding wheel over the mine (once, into air only). */
    private void headframe(final Building b, final int[] m, final World w, final int era) {
        if (era < 3 || m[HEADFRAME] != 0 || !w.isChunkLoaded(b.box().centerX() >> 4, b.box().centerZ() >> 4)) {
            return;
        }
        final int top = b.box().maxY() + 1;
        final int x1 = b.box().minX() + 1;
        final int z1 = b.box().minZ() + 1;
        final int x2 = b.box().maxX() - 1;
        final int z2 = b.box().maxZ() - 1;
        for (final int[] c : new int[][] {{x1, z1}, {x2, z1}, {x1, z2}, {x2, z2}}) {
            for (int y = top; y < top + 8; y++) {
                this.placeAir(w, c[0], y, c[1], "minecraft:iron_bars");
            }
        }
        final int cx = (x1 + x2) / 2;
        final int cz = (z1 + z2) / 2;
        for (int x = x1; x <= x2; x++) {
            this.placeAir(w, x, top + 8, z1, "minecraft:polished_deepslate");
            this.placeAir(w, x, top + 8, z2, "minecraft:polished_deepslate");
        }
        for (int z = z1; z <= z2; z++) {
            this.placeAir(w, cx, top + 8, z, "minecraft:polished_deepslate");
        }
        this.placeAir(w, cx, top + 9, cz, "minecraft:iron_block");
        this.placeAir(w, cx, top + 10, cz, "minecraft:lantern[hanging=false]");
        for (int y = top; y < top + 8; y++) {
            this.placeAir(w, cx, y, cz, "minecraft:iron_chain[axis=y]");
        }
        m[HEADFRAME] = 1;
        this.data().markDirty();
    }

    private void placeAir(final World w, final int x, final int y, final int z, final String state) {
        final Block blk = w.getBlockAt(x, y, z);
        if (blk.getType().isAir()) {
            blk.setBlockData(Bukkit.createBlockData(state), false);
        }
    }

    private void release(final String ticket) {
        final String[] p = ticket.split(":");
        final World w = Bukkit.getWorld(p[0]);
        if (w != null) {
            w.removePluginChunkTicket(Integer.parseInt(p[1]), Integer.parseInt(p[2]), this.plugin);
        }
    }

    /** Around the head of every gallery being dug: liquids may not flow in. */
    public List<pl.medivalrpg.ludnosc.model.BlockBox> guardBoxes() {
        final List<pl.medivalrpg.ludnosc.model.BlockBox> out = new ArrayList<>();
        for (final Map.Entry<UUID, CountryState> ce : this.data().states().entrySet()) {
            if (ce.getValue().mines == null) {
                continue;
            }
            for (final Map.Entry<String, int[]> e : ce.getValue().mines.entrySet()) {
                final int[] m = e.getValue();
                final Building b = this.building(e.getKey());
                if (b != null && (m[REMAINING] > 0 || m.length >= SIZE && this.autoDigging(ce.getKey(), b, m))) {
                    out.add(new pl.medivalrpg.ludnosc.model.BlockBox(b.box().world(), m[X] - 4, m[Y] - 3, m[Z] - 4, m[X] + 4, m[Y] + 4,
                        m[Z] + 4));
                }
            }
        }
        return out;
    }

    public int ticketCount() {
        return this.tickets.size();
    }

    public void shutdown() {
        for (final MineCrew mc : this.crews.values()) {
            mc.crew.remove();
        }
        this.crews.clear();
        for (final String t : this.tickets) {
            this.release(t);
        }
        this.tickets.clear();
    }
}
