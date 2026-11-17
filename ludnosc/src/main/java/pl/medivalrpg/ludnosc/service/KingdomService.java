// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.LudnoscApi.BuildOrderResult;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.logic.BridgeRoute;
import pl.medivalrpg.ludnosc.planner.KingdomPlanner;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.planner.TerrainView;

/**
 * The kingdom plan above the towns: large and rich countries found more settlements ({@code kingdom.*}) on their
 * own land far from the capital (flat, dry, compact), each with its own centre (a well first) and its own planning
 * turns; a high road (trakt) from every settlement to the capital's street network, built in sections, lit and paved
 * in the style of the epoch. {@code /ludnosc plan krolestwo} draws the kingdom as an ASCII map. Main thread.
 */
public final class KingdomService {

    private static final int SECTION = 48;
    private static final long ROAD_COST_PER_BLOCK = 2;

    private final LudnoscPlugin plugin;

    public KingdomService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    /** Settlement centres of a country: the capital first, then the others. */
    public List<BlockPos> settlements(final UUID country) {
        final List<BlockPos> out = new ArrayList<>();
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null) {
            return out;
        }
        out.add(this.plugin.settlement().center(k));
        final CountryState st = this.data().stateIfPresent(country);
        if (st != null && st.settlements != null) {
            out.addAll(st.settlements);
        }
        return out;
    }

    /** Founds a new settlement when the country is big and rich enough. True when one was founded. */
    public boolean maybeFound(final UUID country, final long budget) {
        final var cfg = this.plugin.settings();
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null || this.plugin.settlement().levelIndex(country) < cfg.kingdomMinLevel
            || this.plugin.settlement().treasury(country) < cfg.kingdomMinTreasury || budget < 1000) {
            return false;
        }
        final CountryState st = this.data().state(country);
        final List<BlockPos> existing = this.settlements(country);
        if (existing.size() - 1 >= cfg.kingdomMaxSettlements) {
            return false;
        }
        final BlockPos capital = existing.get(0);
        final Set<Long> claims = this.plugin.kraje().getClaims(country, capital.world());
        if (claims.size() < cfg.kingdomMinChunks) {
            return false;
        }
        // scan the whole territory (async snapshots) and choose
        for (final long c : claims) {
            this.plugin.terrain().request(new pl.medivalrpg.ludnosc.model.ChunkRef(capital.world(), (int) (c >> 32), (int) c));
        }
        final TerrainView view = this.plugin.terrain().view(capital.world(), country);
        final List<int[]> centres = new ArrayList<>();
        for (final BlockPos p : existing) {
            centres.add(new int[] {p.x(), p.z()});
        }
        final long site = KingdomPlanner.site(claims, centres, cfg.kingdomMinDistance, view);
        if (site == Long.MIN_VALUE) {
            return false;
        }
        final int x = (int) (site >> 32) * 16 + 8;
        final int z = (int) site * 16 + 8;
        final int y = view.height(x, z);
        final World w = Bukkit.getWorld(capital.world());
        if (w == null || y == TerrainView.UNKNOWN) {
            return false;
        }
        final BlockPos centre = new BlockPos(capital.world(), x, y, z);
        if (st.settlements == null) {
            st.settlements = new ArrayList<>();
        }
        // the high road to the capital's streets
        final List<BlockBox> taken = new ArrayList<>();
        for (final Building b : this.data().buildingsOf(country)) {
            taken.add(b.box());
        }
        final List<int[]> path = BridgeRoute.path(view, x, z, st.roads(), capital.x(), capital.z(), taken, 40_000);
        if (path.isEmpty() || st.traktPending != null && !st.traktPending.isEmpty()) {
            return false; // never found an isolated settlement or overwrite an unfinished trunk road
        }
        st.settlements.add(centre);
        st.traktPending = new ArrayList<>();
        for (final int[] c : path) {
            st.traktPending.add(RoadPlanner.key(c[0], c[1]));
        }
        this.data().markDirty();
        // the well of the new settlement (its square)
        this.plugin.construction().orderDetailed(country, "studnia", w, x, y, z, 0, false, "AI", null);
        this.plugin.notifyCountry(country, "kingdom-founded", Placeholder.unparsed("pos", x + " " + y + " " + z),
            Placeholder.unparsed("n", Integer.toString(st.settlements.size() + 1)));
        this.plugin.getLogger().info(k.name() + ": nowa osada w " + x + " " + y + " " + z + ", trakt " + path.size() + " bl.");
        return true;
    }

    /**
     * War: a palisade (Middle Ages: wooden, then in the style of the epoch) on the most threatened stretch of the border,
     * facing the nearest enemy capital; at most {@code kingdom.border-segments} stretches per country. True when ordered.
     */
    public boolean fortify(final UUID country, final long budget) {
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null || k.enemies().isEmpty()) {
            return false;
        }
        final CountryState st = this.data().state(country);
        if (st.fortified == null) {
            st.fortified = new HashSet<>();
        }
        if (st.fortified.size() >= this.plugin.settings().kingdomBorderSegments) {
            return false;
        }
        for (final var s : this.data().sitesOf(country)) {
            if (ConstructionService.WALL_TYPE.equals(s.type)) {
                return false;
            }
        }
        final BlockPos capital = this.plugin.settlement().center(k);
        KrajInfo enemy = null;
        double nearest = Double.MAX_VALUE;
        for (final UUID e : k.enemies()) {
            final KrajInfo ek = this.plugin.kraje().getCountry(e).orElse(null);
            if (ek == null || !ek.capitalWorld().equals(capital.world())) {
                continue;
            }
            final double d = Math.hypot(ek.capitalX() * 16 - capital.x(), ek.capitalZ() * 16 - capital.z());
            if (d < nearest) {
                nearest = d;
                enemy = ek;
            }
        }
        if (enemy == null) {
            return false;
        }
        final List<int[]> edge = pl.medivalrpg.ludnosc.planner.WallPlanner.threatenedEdge(
            this.plugin.kraje().getClaims(country, capital.world()), enemy.capitalX() * 16 + 8, enemy.capitalZ() * 16 + 8, st.fortified);
        if (edge == null) {
            return false;
        }
        final long cost = edge.size() * this.plugin.settings().wallsCostPerCell / 2;
        if (cost > budget) {
            return false;
        }
        final List<BlockPos> cells = new ArrayList<>();
        for (final int[] c : edge) {
            cells.add(new BlockPos(capital.world(), c[0], capital.y(), c[1]));
        }
        if (this.plugin.construction().orderWall(country, capital.world(), cells, cost).result() == BuildOrderResult.OK) {
            st.fortified.add(pl.medivalrpg.ludnosc.planner.WallPlanner.edgeChunk(edge));
            this.data().markDirty();
            this.plugin.getLogger().info(k.name() + ": umocnienia granicy od strony " + enemy.name());
            return true;
        }
        return false;
    }

    /** Next section of a high road. True when ordered. */
    public boolean step(final UUID country, final long budget) {
        final CountryState st = this.data().stateIfPresent(country);
        if (st == null || st.traktPending == null || st.traktPending.isEmpty()) {
            return false;
        }
        final List<BlockPos> existing = this.settlements(country);
        if (existing.isEmpty()) {
            return false;
        }
        final BlockPos capital = existing.get(0);
        final List<BlockPos> cells = new ArrayList<>();
        final TerrainView view = this.plugin.terrain().view(capital.world(), country);
        // Preserve the bank cell until the entire crossing has been admitted as one durable construction order.
        if (st.traktPending.size() > 2 && !view.liquid(RoadPlanner.keyX(st.traktPending.getFirst()), RoadPlanner.keyZ(st.traktPending.getFirst()))
            && view.liquid(RoadPlanner.keyX(st.traktPending.get(1)), RoadPlanner.keyZ(st.traktPending.get(1)))) {
            int end = 2;
            while (end < st.traktPending.size() && view.liquid(RoadPlanner.keyX(st.traktPending.get(end)), RoadPlanner.keyZ(st.traktPending.get(end)))) {
                end++;
            }
            if (end >= st.traktPending.size() || end > BridgeRoute.MAX_SPAN + 1) {
                return false;
            }
            for (final long key : st.traktPending.subList(0, end + 1)) {
                final int x = RoadPlanner.keyX(key);
                final int z = RoadPlanner.keyZ(key);
                final int h = view.height(x, z);
                if (h == TerrainView.UNKNOWN) {
                    return false;
                }
                cells.add(new BlockPos(capital.world(), x, h, z));
            }
            final long bridgeCost = cells.size() * 12L;
            if (bridgeCost <= budget && this.plugin.construction().orderBridge(country, capital.world(), cells, bridgeCost).result() == BuildOrderResult.OK) {
                st.traktPending.subList(0, end).clear(); // retain the far bank as the next ordinary-road start
                this.data().markDirty();
                return true;
            }
            return false;
        }
        int length = Math.min(SECTION, st.traktPending.size());
        for (int i = 0; i < length; i++) {
            final long key = st.traktPending.get(i);
            if (view.liquid(RoadPlanner.keyX(key), RoadPlanner.keyZ(key))) {
                length = Math.max(0, i - 1);
                break;
            }
        }
        if (length == 0) {
            return false;
        }
        final List<Long> section = new ArrayList<>(st.traktPending.subList(0, length));
        for (final long k : section) {
            final int h = view.height(RoadPlanner.keyX(k), RoadPlanner.keyZ(k));
            if (h == TerrainView.UNKNOWN) {
                return false;
            }
            cells.add(new BlockPos(capital.world(), RoadPlanner.keyX(k), h, RoadPlanner.keyZ(k)));
        }
        final long cost = cells.size() * ROAD_COST_PER_BLOCK;
        if (cost > budget) {
            return false;
        }
        if (this.plugin.construction().orderRoad(country, capital.world(), cells, cost, "AI").result() == BuildOrderResult.OK) {
            st.traktPending.subList(0, section.size()).clear();
            this.data().markDirty();
            return true;
        }
        return false;
    }

    /** The settlement whose turn it is in the planner (round robin), or null for the capital. */
    public BlockPos planningTurn(final UUID country) {
        final CountryState st = this.data().state(country);
        if (st.settlements == null || st.settlements.isEmpty()) {
            return null;
        }
        final int turn = Math.floorMod(st.settlementTurn++, st.settlements.size() + 1);
        return turn == 0 ? null : st.settlements.get(turn - 1);
    }

    /** ASCII map of the kingdom (chunks). */
    public String[] map(final UUID country) {
        final List<BlockPos> settlements = this.settlements(country);
        if (settlements.isEmpty()) {
            return new String[0];
        }
        final BlockPos capital = settlements.get(0);
        final Set<Long> claims = this.plugin.kraje().getClaims(country, capital.world());
        if (claims.isEmpty()) {
            return new String[0];
        }
        int minCx = Integer.MAX_VALUE;
        int minCz = Integer.MAX_VALUE;
        int maxCx = Integer.MIN_VALUE;
        int maxCz = Integer.MIN_VALUE;
        for (final long c : claims) {
            minCx = Math.min(minCx, (int) (c >> 32));
            maxCx = Math.max(maxCx, (int) (c >> 32));
            minCz = Math.min(minCz, (int) c);
            maxCz = Math.max(maxCz, (int) c);
        }
        final Set<Long> roadChunks = new HashSet<>();
        final CountryState st = this.data().state(country);
        for (final long r : st.roads()) {
            roadChunks.add(KingdomPlanner.key(RoadPlanner.keyX(r) >> 4, RoadPlanner.keyZ(r) >> 4));
        }
        final TerrainView view = this.plugin.terrain().view(capital.world(), country);
        final long capitalChunk = KingdomPlanner.key(capital.x() >> 4, capital.z() >> 4);
        final Set<Long> towns = new HashSet<>();
        for (final BlockPos p : settlements.subList(1, settlements.size())) {
            towns.add(KingdomPlanner.key(p.x() >> 4, p.z() >> 4));
        }
        return KingdomPlanner.map(minCx, minCz, maxCx, maxCz, claims, (cx, cz) -> {
            final long key = KingdomPlanner.key(cx, cz);
            if (!claims.contains(key)) {
                return ' ';
            }
            if (key == capitalChunk) {
                return 'S';
            }
            if (towns.contains(key)) {
                return 'o';
            }
            if (roadChunks.contains(key)) {
                return '#';
            }
            final int x = cx * 16 + 8;
            final int z = cz * 16 + 8;
            if (view.liquid(x, z)) {
                return '~';
            }
            if (view.stone(x, z)) {
                return '^';
            }
            if (view.tree(x, z)) {
                return 'T';
            }
            return '.';
        });
    }
}
