// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Steps in front of an exterior door down to the terrain (pure, unit tested). The building stands on a foundation,
 * so its threshold can be above the ground; stairs (one block lower per block outwards, facing the door) with
 * supports underneath make the entrance walkable. Terrain higher than the threshold is dug away instead.
 *
 * <p>Distances count outwards from the first cell in front of the door that is not part of the building's floor.
 * With the stair at distance {@code d} at height {@code doorY - d}, every rise is at most half a block.</p>
 */
public final class Entrance {

    public enum Kind {
        /** Stair block facing the door. */
        STAIR,
        /** Solid block under a stair (foundation material). */
        SUPPORT,
        /** Cleared to air (terrain above the threshold). */
        CLEAR
    }

    /** One block to place: {@code dist} cells outwards from the door, at height {@code y}. */
    public record Step(int dist, int y, Kind kind) {
    }

    private Entrance() {
    }

    /**
     * @param doorY      y of the lower door half (the feet level inside)
     * @param groundTop  distance → y of the top solid terrain block there
     * @param maxSteps   stop after this many stairs (very steep drops)
     */
    public static List<Step> plan(final int doorY, final IntUnaryOperator groundTop, final int maxSteps) {
        final List<Step> out = new ArrayList<>();
        final int g1 = groundTop.applyAsInt(1);
        if (g1 >= doorY - 1) {
            // ground at or above the threshold: dig a passage two blocks high (and a bit further if needed)
            for (int d = 1; d <= 2; d++) {
                final int g = groundTop.applyAsInt(d);
                for (int y = doorY; y <= Math.min(g, doorY + 1); y++) {
                    out.add(new Step(d, y, Kind.CLEAR));
                }
            }
            return out;
        }
        for (int d = 1; d <= maxSteps; d++) {
            final int stairY = doorY - d;
            final int g = groundTop.applyAsInt(d);
            if (g >= stairY) {
                break; // reached the terrain: one half-step up onto the previous stair
            }
            for (int y = g + 1; y < stairY; y++) {
                out.add(new Step(d, y, Kind.SUPPORT));
            }
            out.add(new Step(d, stairY, Kind.STAIR));
            // headroom above the stair
            for (int y = stairY + 1; y <= stairY + 2; y++) {
                if (groundTop.applyAsInt(d) >= y) {
                    out.add(new Step(d, y, Kind.CLEAR));
                }
            }
        }
        return out;
    }

    /** Number of stairs in a plan. */
    public static int stairs(final List<Step> plan) {
        int n = 0;
        for (final Step s : plan) {
            if (s.kind() == Kind.STAIR) {
                n++;
            }
        }
        return n;
    }

    /** Outward distance of the bottom (last) stair, 0 when there are no stairs — where the road should start. */
    public static int bottomDistance(final List<Step> plan) {
        int d = 0;
        for (final Step s : plan) {
            if (s.kind() == Kind.STAIR) {
                d = Math.max(d, s.dist());
            }
        }
        return d;
    }
}
