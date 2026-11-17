// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.HashSet;
import java.util.Set;

/**
 * Finds the head of a gallery that already exists in the world (pure, unit tested). Used when a mine building has no
 * gallery record — e.g. it was built by another country and the land changed owner — so the new owner's miners carry
 * on from the real head instead of re-digging (or blocking on) the old corridor.
 *
 * <p>Only the gallery's own marks are followed, never a natural cave: a step down needs the cobblestone stairs that
 * {@link MineDigger} lays under every staircase step (plus the three cleared cells), a level step needs the rail laid
 * on every level step and free headroom (torches count as free). Directions are tried in the digger's order: ahead,
 * right, left. Returns {@code null} when a needed block is not readable (unloaded chunk) — the caller retries later.
 */
public final class MineTrace {

    private static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * Result.
     *
     * @param head    walking cell of the last gallery step {x, y, z} (the shaft exit when nothing was dug)
     * @param dir     direction of the last step (the start direction when nothing was dug)
     * @param length  gallery steps found
     * @param lowestY lowest walking cell on the way
     */
    public record Trace(int[] head, int dir, int length, int lowestY) {
    }

    private MineTrace() {
    }

    static boolean free(final String id) {
        return MineDigger.open(id) || id.endsWith("torch") || id.endsWith("lantern");
    }

    static boolean rail(final String id) {
        return id.endsWith(":rail") || id.endsWith("_rail");
    }

    static boolean stairs(final String id) {
        return id.endsWith("_stairs");
    }

    /**
     * Follows the gallery from the shaft exit (x, y, z) in direction {@code dir} for at most {@code maxSteps} steps.
     * The world returns {@code null} for an unreadable position.
     */
    public static Trace trace(final MineDigger.Blocks world, final int x, final int y, final int z, final int dir,
                              final int maxSteps) {
        int cx = x;
        int cy = y;
        int cz = z;
        int d = Math.floorMod(dir, 4);
        int length = 0;
        int lowest = y;
        final Set<Long> seen = new HashSet<>();
        seen.add(MineDigger.key(cx, cy, cz));
        while (length < maxSteps) {
            int chosen = -1;
            boolean down = false;
            for (final int t : new int[] {d, Math.floorMod(d + 1, 4), Math.floorMod(d - 1, 4)}) {
                final int nx = cx + DIRS[t][0];
                final int nz = cz + DIRS[t][1];
                final String low = world.at(nx, cy - 1, nz);
                final String mid = world.at(nx, cy, nz);
                final String high = world.at(nx, cy + 1, nz);
                final String floor = world.at(nx, cy - 2, nz);
                if (low == null || mid == null || high == null || floor == null) {
                    return null;
                }
                if (free(low) && free(mid) && free(high) && stairs(floor) && !seen.contains(MineDigger.key(nx, cy - 1, nz))) {
                    chosen = t;
                    down = true;
                    break;
                }
                if (rail(mid) && free(high) && !free(low) && !seen.contains(MineDigger.key(nx, cy, nz))) {
                    chosen = t;
                    break;
                }
            }
            if (chosen < 0) {
                break;
            }
            d = chosen;
            cx += DIRS[d][0];
            cz += DIRS[d][1];
            if (down) {
                cy--;
            }
            seen.add(MineDigger.key(cx, cy, cz));
            lowest = Math.min(lowest, cy);
            length++;
        }
        return new Trace(new int[] {cx, cy, cz}, d, length, lowest);
    }
}
