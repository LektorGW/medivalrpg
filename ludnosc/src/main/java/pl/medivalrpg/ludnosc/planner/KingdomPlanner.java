// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.List;
import java.util.Set;

/**
 * The kingdom above the towns (pure, unit tested): where a new settlement goes (own land far enough from the other
 * settlements, flat and dry, not too far from the capital) and the ASCII map of the kingdom for
 * {@code /ludnosc plan krolestwo}.
 */
public final class KingdomPlanner {

    private KingdomPlanner() {
    }

    public static long key(final int cx, final int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * The chunk for a new settlement, or {@link Long#MIN_VALUE}.
     *
     * @param claims       the country's chunks
     * @param settlements  existing settlement centres {x, z} (the capital first)
     * @param minDistance  blocks to the nearest settlement at least
     */
    public static long site(final Set<Long> claims, final List<int[]> settlements, final int minDistance, final TerrainView view) {
        long best = Long.MIN_VALUE;
        double bestScore = Double.MAX_VALUE;
        for (final long c : claims) {
            final int cx = (int) (c >> 32);
            final int cz = (int) c;
            // the chunk and its four neighbours must be the country's (room for a village)
            int ownAround = 0;
            for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (claims.contains(key(cx + d[0], cz + d[1]))) {
                    ownAround++;
                }
            }
            if (ownAround < 4) {
                continue;
            }
            final int x = cx * 16 + 8;
            final int z = cz * 16 + 8;
            double nearest = Double.MAX_VALUE;
            for (final int[] s : settlements) {
                nearest = Math.min(nearest, Math.hypot(x - s[0], z - s[1]));
            }
            if (nearest < minDistance) {
                continue;
            }
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            int water = 0;
            int known = 0;
            for (int dx = -12; dx <= 12; dx += 4) {
                for (int dz = -12; dz <= 12; dz += 4) {
                    final int h = view.height(x + dx, z + dz);
                    if (h == TerrainView.UNKNOWN) {
                        continue;
                    }
                    known++;
                    if (view.liquid(x + dx, z + dz)) {
                        water++;
                    }
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                }
            }
            if (known < 10 || water > 6) {
                continue;
            }
            // flat, dry, and not farther than needed from the others (compact kingdom)
            final double score = (max - min) * 3.0 + water * 4.0 + (nearest - minDistance) * 0.05;
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** One map cell. */
    @FunctionalInterface
    public interface Cell {
        /** Symbol of a chunk: S capital, o settlement, # road, T forest, ^ rock, ~ water, . land, space = foreign. */
        char at(int cx, int cz);
    }

    /** ASCII map rows (north at the top) of the chunks {@code minCx..maxCx} × {@code minCz..maxCz}, at most 48×24. */
    public static String[] map(final int minCx, final int minCz, final int maxCx, final int maxCz, final Cell cell) {
        final int w = Math.min(48, maxCx - minCx + 1);
        final int h = Math.min(24, maxCz - minCz + 1);
        final int sx = Math.max(1, (int) Math.ceil((maxCx - minCx + 1) / (double) w));
        final int sz = Math.max(1, (int) Math.ceil((maxCz - minCz + 1) / (double) h));
        final String[] rows = new String[h];
        for (int r = 0; r < h; r++) {
            final StringBuilder sb = new StringBuilder();
            for (int c = 0; c < w; c++) {
                char best = ' ';
                for (int dx = 0; dx < sx; dx++) {
                    for (int dz = 0; dz < sz; dz++) {
                        final char ch = cell.at(minCx + c * sx + dx, minCz + r * sz + dz);
                        if (priority(ch) > priority(best)) {
                            best = ch;
                        }
                    }
                }
                sb.append(best);
            }
            rows[r] = sb.toString();
        }
        return rows;
    }

    /** Sparse equivalent of the map above: evaluates only real country chunks, including distant islands. */
    public static String[] map(final int minCx, final int minCz, final int maxCx, final int maxCz,
                               final Set<Long> claims, final Cell cell) {
        final long spanX = (long) maxCx - minCx + 1, spanZ = (long) maxCz - minCz + 1;
        if (spanX < 1 || spanZ < 1) return new String[0];
        final int w = (int) Math.min(48, spanX), h = (int) Math.min(24, spanZ);
        final long sx = (spanX + w - 1) / w, sz = (spanZ + h - 1) / h;
        final char[][] pixels = new char[h][w];
        for (final char[] row : pixels) java.util.Arrays.fill(row, ' ');
        for (final long key : claims) {
            final int x = (int) (key >> 32), z = (int) key;
            if (x < minCx || x > maxCx || z < minCz || z > maxCz) continue;
            final int col = (int) (((long) x - minCx) / sx), row = (int) (((long) z - minCz) / sz);
            final char symbol = cell.at(x, z);
            if (priority(symbol) > priority(pixels[row][col])) pixels[row][col] = symbol;
        }
        final String[] rows = new String[h];
        for (int i = 0; i < h; i++) rows[i] = new String(pixels[i]);
        return rows;
    }

    private static int priority(final char c) {
        return switch (c) {
            case 'S' -> 9;
            case 'o' -> 8;
            case 'w' -> 7;
            case '#' -> 6;
            case '~' -> 5;
            case '^' -> 4;
            case 'T' -> 3;
            case '.' -> 2;
            default -> 0;
        };
    }
}
