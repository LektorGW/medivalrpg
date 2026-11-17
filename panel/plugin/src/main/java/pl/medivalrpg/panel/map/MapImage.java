// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.map;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import javax.imageio.ImageIO;
import pl.medivalrpg.panel.plan.ChunkGeometry;

/**
 * Top-down map image like vanilla maps: the colour of the highest visible block, shaded by height against the block
 * to the north (brighter when higher, darker when lower), water drawn half-transparent over the bottom (deeper =
 * more water colour). Works on reduced chunk columns, so it runs off the main thread. Pure (AWT/ImageIO only).
 */
public final class MapImage {

    /** Vanilla map shade multipliers (out of 255): lower, level, higher than the block to the north. */
    static final int SHADE_LOW = 180;
    static final int SHADE_FLAT = 220;
    static final int SHADE_HIGH = 255;
    /** Water depth (blocks) at which the bottom is no longer visible. */
    static final int MAX_WATER_DEPTH = 16;
    static final byte WATER = 1;

    private MapImage() {
    }

    /** Block access for {@link #reduce}; coordinates are chunk-local x, z (0..15) and world y. */
    public interface ColumnSource {
        /** Highest non-air block y of the column (scan starts here), or below {@code minY} when the column is empty. */
        int top(int x, int z);

        /** Map colour {@code 0xRRGGBB} of the block, or -1 when it is transparent on maps (air, glass, …). */
        int color(int x, int y, int z);

        /** Whether the block is water (or a water plant / bubble column). */
        boolean water(int x, int y, int z);
    }

    /** One chunk reduced to 16×16 columns: opaque colour (alpha 0 = nothing), surface height and flags. */
    public static final class Column {
        final int[] argb = new int[256];
        final short[] height = new short[256];
        final byte[] flags = new byte[256];

        public int argb(final int x, final int z) {
            return this.argb[(z << 4) | x];
        }
    }

    /** Reduces a chunk: per column the first visible block from the top; water blended with what lies under it. */
    public static Column reduce(final ColumnSource src, final int minY, final int maxY) {
        final Column c = new Column();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int i = (z << 4) | x;
                int y = Math.min(maxY - 1, src.top(x, z) + 1);
                int color = -1;
                while (y >= minY) {
                    color = src.color(x, y, z);
                    if (color >= 0) {
                        break;
                    }
                    y--;
                }
                if (color < 0) {
                    c.argb[i] = 0;
                    c.height[i] = (short) minY;
                    continue;
                }
                c.height[i] = (short) y;
                if (src.water(x, y, z)) {
                    final int waterColor = color;
                    int depth = 0;
                    int bottom = -1;
                    int yy = y - 1;
                    while (yy >= minY && depth < MAX_WATER_DEPTH) {
                        depth++;
                        if (!src.water(x, yy, z)) {
                            final int b = src.color(x, yy, z);
                            if (b >= 0) {
                                bottom = b;
                                break;
                            }
                        }
                        yy--;
                    }
                    c.flags[i] = WATER;
                    c.argb[i] = 0xFF000000 | waterOver(bottom, waterColor, depth);
                } else {
                    c.argb[i] = 0xFF000000 | color;
                }
            }
        }
        return c;
    }

    /** Water colour over the bottom: shallow water shows the bottom, from {@link #MAX_WATER_DEPTH} only water. */
    static int waterOver(final int bottom, final int water, final int depth) {
        if (bottom < 0) {
            return darken(water, 0.85);
        }
        final double a = Math.min(0.92, 0.45 + depth * (0.47 / MAX_WATER_DEPTH));
        final int r = (int) Math.round(((bottom >> 16) & 255) * (1 - a) + ((water >> 16) & 255) * a);
        final int g = (int) Math.round(((bottom >> 8) & 255) * (1 - a) + ((water >> 8) & 255) * a);
        final int b = (int) Math.round((bottom & 255) * (1 - a) + (water & 255) * a);
        return darken((r << 16) | (g << 8) | b, 1.0 - Math.min(0.25, depth * 0.015));
    }

    static int darken(final int rgb, final double f) {
        final int r = (int) Math.round(((rgb >> 16) & 255) * f);
        final int g = (int) Math.round(((rgb >> 8) & 255) * f);
        final int b = (int) Math.round((rgb & 255) * f);
        return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    /**
     * Full-resolution image (1 px = 1 block) of the rectangle starting at block ({@code originX}, {@code originZ});
     * chunks missing from {@code columns} stay transparent.
     */
    public static int[] compose(final Map<Long, Column> columns, final int originX, final int originZ, final int width, final int height) {
        final int[] out = new int[width * height];
        for (int pz = 0; pz < height; pz++) {
            final int z = originZ + pz;
            for (int px = 0; px < width; px++) {
                final int x = originX + px;
                final Column c = columns.get(ChunkGeometry.key(x >> 4, z >> 4));
                if (c == null) {
                    continue;
                }
                final int i = ((z & 15) << 4) | (x & 15);
                final int argb = c.argb[i];
                if ((argb >>> 24) == 0) {
                    continue;
                }
                if (c.flags[i] == WATER) {
                    out[pz * width + px] = argb;
                    continue;
                }
                final int h = c.height[i];
                final Column n = (z & 15) == 0 ? columns.get(ChunkGeometry.key(x >> 4, (z - 1) >> 4)) : c;
                int shade = SHADE_FLAT;
                if (n != null) {
                    final int j = (((z - 1) & 15) << 4) | (x & 15);
                    if ((n.argb[j] >>> 24) != 0) {
                        final int hn = n.height[j];
                        shade = h > hn ? SHADE_HIGH : h < hn ? SHADE_LOW : SHADE_FLAT;
                    }
                }
                out[pz * width + px] = 0xFF000000 | darken(argb & 0xFFFFFF, shade / 255.0);
            }
        }
        return out;
    }

    /** A bounded image of sparse land. Work depends on loaded columns, never on the empty world between islands. */
    public record Raster(int[] pixels, int width, int height, int scale) { }

    public static Raster composeBounded(final Map<Long, Column> columns, final int originX, final int originZ,
                                        final int width, final int height, final int maxSize, final int minimumScale) {
        if (width < 1 || height < 1 || maxSize < 1 || minimumScale < 1) throw new IllegalArgumentException("Invalid map bounds");
        int scale = minimumScale;
        while (ceil(width, scale) > maxSize || ceil(height, scale) > maxSize) scale = Math.multiplyExact(scale, 2);
        final int w = ceil(width, scale);
        final int h = ceil(height, scale);
        final int size = Math.multiplyExact(w, h);
        final long[] red = new long[size], green = new long[size], blue = new long[size];
        final int[] count = new int[size];
        for (final var entry : columns.entrySet()) {
            final long bx = (long) ChunkGeometry.cx(entry.getKey()) * 16;
            final long bz = (long) ChunkGeometry.cz(entry.getKey()) * 16;
            final Column c = entry.getValue();
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                final long dx = bx + x - originX, dz = bz + z - originZ;
                if (dx < 0 || dz < 0 || dx >= width || dz >= height) continue;
                final int i = (z << 4) | x;
                final int argb = c.argb[i];
                if ((argb >>> 24) == 0) continue;
                int color = argb & 0xFFFFFF;
                if (c.flags[i] != WATER) {
                    final Column north = z == 0 ? columns.get(ChunkGeometry.key(ChunkGeometry.cx(entry.getKey()), ChunkGeometry.cz(entry.getKey()) - 1)) : c;
                    int shade = SHADE_FLAT;
                    if (north != null) {
                        final int j = (((z + 15) & 15) << 4) | x;
                        if ((north.argb[j] >>> 24) != 0) shade = c.height[i] > north.height[j] ? SHADE_HIGH : c.height[i] < north.height[j] ? SHADE_LOW : SHADE_FLAT;
                    }
                    color = darken(color, shade / 255.0);
                }
                final int p = (int) (dz / scale) * w + (int) (dx / scale);
                red[p] += (color >> 16) & 255; green[p] += (color >> 8) & 255; blue[p] += color & 255; count[p]++;
            }
        }
        final int[] out = new int[size];
        for (int i = 0; i < size; i++) if (count[i] != 0) {
            // Preserve even a small distant island instead of erasing it with a majority-transparent threshold.
            out[i] = 0xFF000000 | ((int) (red[i] / count[i]) << 16) | ((int) (green[i] / count[i]) << 8) | (int) (blue[i] / count[i]);
        }
        return new Raster(out, w, h, scale);
    }

    private static int ceil(final int value, final int divisor) {
        return (int) (((long) value + divisor - 1) / divisor);
    }

    /** Downscales by an integer factor: average of the opaque pixels, transparent when fewer than half are opaque. */
    public static int[] downscale(final int[] src, final int width, final int height, final int factor) {
        if (factor <= 1) {
            return src;
        }
        final int w = width / factor;
        final int h = height / factor;
        final int[] out = new int[w * h];
        final int half = (factor * factor + 1) / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        final int p = src[(y * factor + dy) * width + x * factor + dx];
                        if ((p >>> 24) != 0) {
                            n++;
                            r += (p >> 16) & 255;
                            g += (p >> 8) & 255;
                            b += p & 255;
                        }
                    }
                }
                if (n >= half) {
                    out[y * w + x] = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
                }
            }
        }
        return out;
    }

    /** PNG bytes of an ARGB image. */
    public static byte[] png(final int[] argb, final int width, final int height) {
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, width, height, argb, 0, width);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, width * height));
        try {
            if (!ImageIO.write(img, "png", out)) {
                throw new IOException("no PNG writer");
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
