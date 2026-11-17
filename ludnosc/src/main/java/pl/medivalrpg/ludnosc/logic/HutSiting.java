// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.List;
import java.util.Set;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.planner.SiteFinder;
import pl.medivalrpg.ludnosc.planner.TerrainView;

/**
 * Citizens build a hut themselves (pure, unit tested): when to order one and where. The place comes from the normal
 * {@link SiteFinder} (own land, no water, no trees or player blocks, gap to every building/site/field, never on a
 * road) with planned streets of a town redesign / the design plan excluded as well, so a hut never blocks a street.
 */
public final class HutSiting {

    private HutSiting() {
    }

    /** Why citizens do (not) order a hut now. */
    public enum Decision {
        ORDER("zlecają chatę"),
        DISABLED("samodzielne budowanie wyłączone"),
        NO_NEED("nikt nie jest bezdomny"),
        LIMIT("limit trwających chat obywateli"),
        NO_MONEY("za mało oszczędności"),
        QUEUE_FULL("kolejka budów kraju jest pełna");

        private final String title;

        Decision(final String title) {
            this.title = title;
        }

        public String title() {
            return this.title;
        }
    }

    /**
     * @param homeless   citizens without a bed (including the beds still missing when every hut in progress is done)
     * @param inProgress huts ordered by the citizens that are not finished yet
     * @param limit      max huts in progress at once (0 = none)
     * @param savings    money in the citizens' account
     * @param cost       the hut price paid from the savings
     * @param queueFree  the country's construction queue accepts an order
     */
    public static Decision decide(final boolean enabled, final int homeless, final int inProgress, final int limit,
                                  final long savings, final long cost, final boolean queueFree) {
        if (!enabled || limit <= 0) {
            return Decision.DISABLED;
        }
        if (homeless <= 0) {
            return Decision.NO_NEED;
        }
        if (inProgress >= limit) {
            return Decision.LIMIT;
        }
        if (savings < cost) {
            return Decision.NO_MONEY;
        }
        if (!queueFree) {
            return Decision.QUEUE_FULL;
        }
        return Decision.ORDER;
    }

    /** Terrain without the given street cells (planned, not built yet). */
    public static TerrainView withoutStreets(final TerrainView base, final Set<Long> streets) {
        if (streets == null || streets.isEmpty()) {
            return base;
        }
        return new TerrainView() {
            @Override
            public int height(final int x, final int z) {
                return base.height(x, z);
            }

            @Override
            public boolean liquid(final int x, final int z) {
                return base.liquid(x, z);
            }

            @Override
            public boolean obstacle(final int x, final int z) {
                return base.obstacle(x, z);
            }

            @Override
            public boolean own(final int x, final int z) {
                return base.own(x, z) && !streets.contains(pl.medivalrpg.ludnosc.planner.RoadPlanner.key(x, z));
            }

            @Override
            public boolean stone(final int x, final int z) {
                return base.stone(x, z);
            }

            @Override
            public boolean tree(final int x, final int z) {
                return base.tree(x, z);
            }
        };
    }

    public static TerrainView inPlan(final TerrainView base, final List<int[]> quarters) {
        return new TerrainView() {
            public int height(int x,int z) { return base.height(x,z); }
            public boolean liquid(int x,int z) { return base.liquid(x,z); }
            public boolean obstacle(int x,int z) { return base.obstacle(x,z); }
            public boolean tree(int x,int z) { return base.tree(x,z); }
            public boolean stone(int x,int z) { return base.stone(x,z); }
            public boolean own(int x,int z) {
                return base.own(x,z) && quarters.stream().anyMatch(q -> q.length>=4 && x>=q[0] && z>=q[1] && x<=q[2] && z<=q[3]);
            }
        };
    }

    public static TerrainView courteous(final TerrainView base, final Set<Long> roads, final int gap) {
        final java.util.Map<Long, Boolean> safe = new java.util.HashMap<>();
        return new TerrainView() {
            public int height(int x, int z) { return base.height(x,z); }
            public boolean liquid(int x, int z) { return base.liquid(x,z); }
            public boolean obstacle(int x, int z) { return base.obstacle(x,z); }
            public boolean tree(int x, int z) { return base.tree(x,z); }
            public boolean stone(int x, int z) { return base.stone(x,z); }
            public boolean own(int x, int z) {
                if (!base.own(x,z)) return false;
                return safe.computeIfAbsent(pl.medivalrpg.ludnosc.planner.RoadPlanner.key(x,z), key -> {
                    for (int dx=-gap; dx<=gap; dx++) for (int dz=-gap; dz<=gap; dz++)
                        if (base.obstacle(x+dx,z+dz) || roads.contains(pl.medivalrpg.ludnosc.planner.RoadPlanner.key(x+dx,z+dz))) return false;
                    return true;
                });
            }
        };
    }

    /**
     * Best place for a hut of {@code size} (width, depth) around the town centre.
     *
     * @param taken   footprints of buildings, fields, sites and queued orders
     * @param roads   built roads (never under a hut)
     * @param streets planned streets (redesign / design plan), excluded from building land
     */
    public static SiteFinder.Search find(final TerrainView view, final int[] size, final int centreX, final int centreZ,
                                         final String zone, final List<BlockBox> taken, final Set<Long> roads,
                                         final Set<Long> streets, final SiteFinder.Settings settings, final int groundLayer) {
        return SiteFinder.strictSearch(withoutStreets(view, streets), size, centreX, centreZ, zone, taken, roads, settings, groundLayer);
    }
}
