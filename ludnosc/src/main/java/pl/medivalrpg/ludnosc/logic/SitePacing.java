// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/**
 * Pace of a construction site (pure, unit tested): how the block credit grows, when a site catches up after being
 * unloaded, and where a started site resumes after its template changed.
 */
public final class SitePacing {

    /** Phase indices of {@code ConstructionService.Phase}. */
    public static final int TERRAIN = 0;
    public static final int FOUNDATION = 1;
    public static final int CELLS = 2;
    public static final int ROAD = 4;
    public static final int FINISH = 5;

    /** Periods of credit a loaded site may hold (pacing only, no burst after a walk). */
    public static final int LOADED_PERIODS = 3;
    /** Credit above this many periods means offline progress to catch up (placed without walking). */
    public static final int CATCH_UP_PERIODS = 6;

    private SitePacing() {
    }

    /**
     * Credit after one work period.
     *
     * @param loaded   the site's chunks are loaded (builders walk and place)
     * @param offline  offline progress enabled (unloaded sites earn credit to catch up later)
     * @param waiting  the site waits for material (no credit while nothing can be built)
     */
    public static double accrue(final double credit, final double perPeriod, final boolean day, final boolean loaded,
                                final boolean offline, final boolean waiting, final int remaining) {
        if (!day || waiting) {
            return credit;
        }
        if (loaded) {
            return Math.min(credit + perPeriod, Math.max(credit, perPeriod * LOADED_PERIODS));
        }
        return offline ? Math.min(credit + perPeriod, Math.max(0, remaining) + perPeriod) : credit;
    }

    /** The builders place blocks without walking: only while working off offline credit. */
    public static boolean catchUp(final double credit, final double perPeriod) {
        return credit > perPeriod * CATCH_UP_PERIODS;
    }

    /**
     * Phase a site resumes in after its template (or the order semantics) changed: repairs/upgrades re-check their
     * cells, roads their road, a building that already left the earthworks is re-checked from its foundation (placed
     * blocks are skipped), never sent back into the earthworks that would dig at the standing walls.
     */
    public static int phaseAfterTemplateChange(final String kind, final int phase) {
        return switch (kind == null ? "build" : kind) {
            case "repair", "upgrade" -> CELLS;
            case "road" -> ROAD;
            default -> phase > TERRAIN && phase < FINISH ? FOUNDATION : TERRAIN;
        };
    }
}
