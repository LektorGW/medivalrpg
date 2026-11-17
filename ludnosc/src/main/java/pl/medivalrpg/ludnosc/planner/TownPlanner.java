// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/**
 * The town plan (pure, unit tested): what to build next ({@link BuildingChooser}), where ({@link SiteFinder}) and the
 * street that connects its door to the network ({@link RoadPlanner}). The first building is the well on the town
 * square; then the main axes leave the square in four directions and plots line up along the streets.
 */
public final class TownPlanner {

    /** Template metadata and footprint size (width x, depth z at rotation 0) per id. */
    public record Catalog(Map<String, BuildingTemplate.Meta> metas, Map<String, int[]> sizes) {
    }

    /** A planned building: template, why, where, the street to its door, and its 2D footprint box (y = floor). */
    public record Proposal(String type, BuildingChooser.Reason reason, SiteFinder.Spot spot, List<int[]> road, BlockBox box) {
    }

    /**
     * Outcome.
     *
     * @param reason why nothing (when {@code proposal == null})
     * @param noSpot something is needed but no suitable spot was found (yet: terrain may still be scanned)
     */
    public record Result(Proposal proposal, BuildingChooser.Reason reason, boolean noSpot, String failure, String type) {

        public Result(final Proposal proposal, final BuildingChooser.Reason reason, final boolean noSpot) {
            this(proposal, reason, noSpot, null, proposal == null ? null : proposal.type());
        }

        public Result(final Proposal proposal, final BuildingChooser.Reason reason, final boolean noSpot, final String failure) {
            this(proposal, reason, noSpot, failure, proposal == null ? null : proposal.type());
        }
    }

    private TownPlanner() {
    }

    public static Result plan(final BuildingChooser.Input in, final Catalog catalog, final TerrainView view, final String world,
                              final int centreX, final int centreZ, final Set<Long> roads, final List<BlockBox> taken,
                              final SiteFinder.Settings settings, final int roadMaxNodes) {
        return plan(in, catalog, view, world, centreX, centreZ, roads, taken, settings, roadMaxNodes, m -> view);
    }

    /** {@code viewFor}: the terrain the chosen type may use (zones of a design plan restrict where it may stand). */
    public static Result plan(final BuildingChooser.Input in, final Catalog catalog, final TerrainView view, final String world,
                              final int centreX, final int centreZ, final Set<Long> roads, final List<BlockBox> taken,
                              final SiteFinder.Settings settings, final int roadMaxNodes,
                              final java.util.function.Function<BuildingTemplate.Meta, TerrainView> viewFor) {
        final BuildingChooser.Choice choice = BuildingChooser.choose(in);
        if (!choice.found()) {
            return new Result(null, choice.reason(), false);
        }
        final BuildingTemplate.Meta meta = catalog.metas().get(choice.type());
        final int[] size = catalog.sizes().get(choice.type());
        if (meta == null || size == null) {
            return new Result(null, BuildingChooser.Reason.NOTHING_NEEDED, false);
        }
        final SiteFinder.Search search = SiteFinder.search(viewFor.apply(meta), size, centreX, centreZ, meta.zone(), taken, roads, settings,
            meta.groundLayer(), "highrise".equals(meta.category()));
        final SiteFinder.Spot spot = search.spot();
        if (spot == null) {
            return new Result(null, choice.reason(), true, meta.displayName().replaceAll("<[^>]*>", "") + ": "
                + search.stats().reason(size[0], size[1]), choice.type());
        }
        final BlockBox box = footprint(world, size, spot);
        final List<BlockBox> obstacles = new ArrayList<>(taken);
        obstacles.add(box);
        List<int[]> road = RoadPlanner.path(view, spot.doorX(), spot.doorZ(), roads, centreX, centreZ, obstacles, roadMaxNodes);
        if (road.isEmpty() && Math.abs(spot.doorX() - centreX) + Math.abs(spot.doorZ() - centreZ) > 3) {
            road = List.of(new int[] {spot.doorX(), spot.doorZ()});
        }
        return new Result(new Proposal(choice.type(), choice.reason(), spot, road, box), choice.reason(), false);
    }

    /** 2D footprint of a rotated template at a spot (y range = floor level only). */
    public static BlockBox footprint(final String world, final int[] size, final SiteFinder.Spot spot) {
        final int w = spot.rotation() % 2 == 0 ? size[0] : size[1];
        final int d = spot.rotation() % 2 == 0 ? size[1] : size[0];
        final int minX = spot.x() - w / 2;
        final int minZ = spot.z() - d / 2;
        return new BlockBox(world, minX, spot.y(), minZ, minX + w - 1, spot.y(), minZ + d - 1);
    }

    /**
     * Main streets from the square: in each of the four directions a road towards a point {@code length} blocks away
     * (shortened where the land ends or the terrain is impassable).
     */
    public static List<List<int[]>> mainAxes(final TerrainView view, final int centreX, final int centreZ, final int length,
                                             final List<BlockBox> obstacles, final int roadMaxNodes) {
        final List<List<int[]>> out = new ArrayList<>();
        for (final int[] dir : new int[][] {{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
            for (int len = length; len >= 6; len -= 4) {
                final int tx = centreX + dir[0] * len;
                final int tz = centreZ + dir[1] * len;
                final List<int[]> path = new ArrayList<>(RoadPlanner.path(view, tx, tz, Set.of(), centreX, centreZ, obstacles,
                    roadMaxNodes));
                if (!path.isEmpty()) {
                    Collections.reverse(path);
                    out.add(path);
                    break;
                }
            }
        }
        return out;
    }
}
