// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Flag image pipeline (pure Java, no Bukkit): validation of an uploaded PNG/JPEG, centre crop to the flag aspect,
 * area-average scaling to {@link #WIDTH}×{@link #HEIGHT}, binary alpha, median-cut colour quantisation and a fresh PNG
 * without any metadata (only IHDR/IDAT/IEND). Every failure is an {@link IllegalArgumentException} with a Polish
 * message for the uploader.
 */
public final class FlagImage {

    /** Texture size of the flag cloth in game (aspect 1.6, like a 2 × 1.25 block banner). */
    public static final int WIDTH = 64;
    public static final int HEIGHT = 40;

    /**
     * @param maxBytes        upload size limit (raw bytes)
     * @param maxSourcePixels decoded size limit (width × height), checked from the header before decoding
     * @param maxSourceSide   longest side limit
     * @param minWidth        smallest accepted source width
     * @param minHeight       smallest accepted source height
     * @param maxColors       palette size after quantisation (2..256)
     */
    public record Limits(int maxBytes, int maxSourcePixels, int maxSourceSide, int minWidth, int minHeight, int maxColors) {
        public static final Limits DEFAULT = new Limits(160 * 1024, 4096 * 1024, 4096, 16, 10, 32);

        public Limits {
            maxBytes = Math.max(1024, maxBytes);
            maxSourcePixels = Math.max(WIDTH * HEIGHT, maxSourcePixels);
            maxSourceSide = Math.max(WIDTH, maxSourceSide);
            minWidth = Math.max(1, minWidth);
            minHeight = Math.max(1, minHeight);
            maxColors = Math.clamp(maxColors, 2, 256);
        }
    }

    /**
     * Normalised flag.
     *
     * @param argb         {@link #WIDTH}×{@link #HEIGHT} pixels, alpha is 0 or 255
     * @param png          the encoded texture (metadata free)
     * @param sha256       hex SHA-256 of {@code png}
     * @param format       {@code PNG} or {@code JPEG}
     * @param sourceWidth  width of the upload
     * @param sourceHeight height of the upload
     * @param sourceBytes  size of the upload
     * @param colors       distinct opaque colours after quantisation
     */
    public record Result(int[] argb, byte[] png, String sha256, String format, int sourceWidth, int sourceHeight,
                         int sourceBytes, int colors) {

        /** Stable short id of the image (first 16 hex chars of the SHA-256). */
        public String imageId() {
            return this.sha256.substring(0, 16);
        }
    }

    private FlagImage() {
    }

    /** A rule violation with a message for the uploader (as opposed to a decoder failure). */
    private static final class Rejected extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        Rejected(final String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ upload

    /** Recognises the container by its magic bytes (the browser's file name or MIME type is never trusted). */
    static String sniff(final byte[] data) {
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
            && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return "PNG";
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "JPEG";
        }
        return null;
    }

    /** Validates and normalises an upload. */
    public static Result normalize(final byte[] data, final Limits limits) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Plik jest pusty.");
        }
        if (data.length > limits.maxBytes()) {
            throw new IllegalArgumentException("Plik jest za duży (" + kb(data.length) + "), limit to " + kb(limits.maxBytes()) + ".");
        }
        final String format = sniff(data);
        if (format == null) {
            throw new IllegalArgumentException("Dozwolone są tylko obrazki PNG i JPG.");
        }
        final BufferedImage image = decode(data, format, limits);
        final int w = image.getWidth();
        final int h = image.getHeight();
        final int[] source = image.getRGB(0, 0, w, h, null, 0, w);
        final int[] scaled = scaleCover(source, w, h, WIDTH, HEIGHT);
        binaryAlpha(scaled);
        int opaque = 0;
        for (final int p : scaled) {
            if (p >>> 24 != 0) {
                opaque++;
            }
        }
        if (opaque * 2 < scaled.length) {
            throw new IllegalArgumentException("Flaga musi być w większości nieprzezroczysta (jest " + (opaque * 100 / scaled.length) + "%).");
        }
        final int[] quantized = quantize(scaled, limits.maxColors());
        final byte[] png = encodePng(quantized, WIDTH, HEIGHT);
        return new Result(quantized, png, sha256(png), format, w, h, data.length, countColors(quantized));
    }

    private static String kb(final int bytes) {
        return (bytes + 1023) / 1024 + " KB";
    }

    private static BufferedImage decode(final byte[] data, final String format, final Limits limits) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (in == null) {
                throw new Rejected("Nie udało się odczytać obrazka.");
            }
            final Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.toLowerCase(java.util.Locale.ROOT));
            if (!readers.hasNext()) {
                throw new Rejected("Serwer nie obsługuje formatu " + format + ".");
            }
            final ImageReader reader = readers.next();
            try {
                // ignoreMetadata: EXIF, ICC, text chunks are never even parsed into memory.
                reader.setInput(in, true, true);
                final int w = reader.getWidth(0);
                final int h = reader.getHeight(0);
                if (w < limits.minWidth() || h < limits.minHeight()) {
                    throw new Rejected("Obrazek jest za mały (" + w + "×" + h + "), minimum to "
                        + limits.minWidth() + "×" + limits.minHeight() + ".");
                }
                if (w > limits.maxSourceSide() || h > limits.maxSourceSide() || (long) w * h > limits.maxSourcePixels()) {
                    throw new Rejected("Obrazek ma za dużą rozdzielczość (" + w + "×" + h + ").");
                }
                final BufferedImage image = reader.read(0);
                if (image == null || image.getWidth() != w || image.getHeight() != h) {
                    throw new Rejected("Nie udało się odczytać obrazka.");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (final Rejected e) {
            throw e;
        } catch (final IOException | RuntimeException | OutOfMemoryError e) {
            throw new IllegalArgumentException("Nie udało się odczytać obrazka (uszkodzony plik?).");
        }
    }

    // ------------------------------------------------------------------ scaling

    /**
     * Centre crop to the target aspect ratio, then an exact area-average (box filter with fractional coverage) in
     * premultiplied alpha, so transparent pixels never bleed their (meaningless) colour into the edges.
     */
    static int[] scaleCover(final int[] src, final int sw, final int sh, final int tw, final int th) {
        double cropW = sw;
        double cropH = sh;
        if ((double) sw / sh > (double) tw / th) {
            cropW = (double) sh * tw / th;
        } else {
            cropH = (double) sw * th / tw;
        }
        final double x0 = (sw - cropW) / 2.0;
        final double y0 = (sh - cropH) / 2.0;
        final double sx = cropW / tw;
        final double sy = cropH / th;
        final int[] out = new int[tw * th];
        for (int ty = 0; ty < th; ty++) {
            final double fy0 = y0 + ty * sy;
            final double fy1 = fy0 + sy;
            for (int tx = 0; tx < tw; tx++) {
                final double fx0 = x0 + tx * sx;
                final double fx1 = fx0 + sx;
                double a = 0;
                double r = 0;
                double g = 0;
                double b = 0;
                double weight = 0;
                for (int y = (int) Math.floor(fy0); y < Math.min(sh, (int) Math.ceil(fy1)); y++) {
                    final double wy = Math.min(fy1, y + 1) - Math.max(fy0, y);
                    if (wy <= 0) {
                        continue;
                    }
                    for (int x = (int) Math.floor(fx0); x < Math.min(sw, (int) Math.ceil(fx1)); x++) {
                        final double wx = Math.min(fx1, x + 1) - Math.max(fx0, x);
                        if (wx <= 0) {
                            continue;
                        }
                        final double wgt = wx * wy;
                        final int p = src[y * sw + x];
                        final double pa = (p >>> 24) / 255.0;
                        a += pa * wgt;
                        r += ((p >> 16) & 0xFF) * pa * wgt;
                        g += ((p >> 8) & 0xFF) * pa * wgt;
                        b += (p & 0xFF) * pa * wgt;
                        weight += wgt;
                    }
                }
                if (weight <= 0 || a <= 0) {
                    out[ty * tw + tx] = 0;
                    continue;
                }
                final int oa = clamp8(a / weight * 255.0);
                out[ty * tw + tx] = oa << 24 | clamp8(r / a) << 16 | clamp8(g / a) << 8 | clamp8(b / a);
            }
        }
        return out;
    }

    private static int clamp8(final double v) {
        return (int) Math.clamp(Math.round(v), 0, 255);
    }

    /** Minecraft item models render cutout textures: alpha below one half becomes a hole, the rest is fully opaque. */
    static void binaryAlpha(final int[] argb) {
        for (int i = 0; i < argb.length; i++) {
            argb[i] = (argb[i] >>> 24) >= 128 ? argb[i] | 0xFF000000 : 0;
        }
    }

    // ------------------------------------------------------------------ quantisation

    static int countColors(final int[] argb) {
        return (int) Arrays.stream(argb).filter(p -> p >>> 24 != 0).map(p -> p & 0xFFFFFF).distinct().count();
    }

    /**
     * Deterministic median cut on the opaque pixels: boxes are split at the median of their widest channel (weighted by
     * pixel count) until there are {@code maxColors} boxes; every pixel is then replaced by the nearest box average.
     */
    static int[] quantize(final int[] argb, final int maxColors) {
        final Map<Integer, Integer> histogram = new HashMap<>();
        for (final int p : argb) {
            if (p >>> 24 != 0) {
                histogram.merge(p & 0xFFFFFF, 1, Integer::sum);
            }
        }
        if (histogram.size() <= maxColors) {
            return argb.clone();
        }
        final List<int[]> colors = new ArrayList<>();
        histogram.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(e -> colors.add(new int[] {(e.getKey() >> 16) & 0xFF, (e.getKey() >> 8) & 0xFF, e.getKey() & 0xFF, e.getValue()}));
        final List<List<int[]>> boxes = new ArrayList<>();
        boxes.add(colors);
        while (boxes.size() < maxColors) {
            int best = -1;
            int bestRange = 0;
            int bestChannel = 0;
            for (int i = 0; i < boxes.size(); i++) {
                final List<int[]> box = boxes.get(i);
                if (box.size() < 2) {
                    continue;
                }
                for (int c = 0; c < 3; c++) {
                    int min = 255;
                    int max = 0;
                    for (final int[] col : box) {
                        min = Math.min(min, col[c]);
                        max = Math.max(max, col[c]);
                    }
                    if (max - min > bestRange) {
                        bestRange = max - min;
                        best = i;
                        bestChannel = c;
                    }
                }
            }
            if (best < 0) {
                break;
            }
            final List<int[]> box = boxes.remove(best);
            final int channel = bestChannel;
            box.sort(Comparator.<int[]>comparingInt(col -> col[channel]).thenComparingInt(col -> col[0] << 16 | col[1] << 8 | col[2]));
            long total = 0;
            for (final int[] col : box) {
                total += col[3];
            }
            long acc = 0;
            int cut = 1;
            for (int i = 0; i < box.size() - 1; i++) {
                acc += box.get(i)[3];
                cut = i + 1;
                if (acc * 2 >= total) {
                    break;
                }
            }
            boxes.add(new ArrayList<>(box.subList(0, cut)));
            boxes.add(new ArrayList<>(box.subList(cut, box.size())));
        }
        final int[] palette = new int[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            long r = 0;
            long g = 0;
            long b = 0;
            long n = 0;
            for (final int[] col : boxes.get(i)) {
                r += (long) col[0] * col[3];
                g += (long) col[1] * col[3];
                b += (long) col[2] * col[3];
                n += col[3];
            }
            palette[i] = (int) Math.round((double) r / n) << 16 | (int) Math.round((double) g / n) << 8 | (int) Math.round((double) b / n);
        }
        final Map<Integer, Integer> mapping = new HashMap<>();
        final int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            final int p = argb[i];
            if (p >>> 24 == 0) {
                out[i] = 0;
                continue;
            }
            out[i] = 0xFF000000 | mapping.computeIfAbsent(p & 0xFFFFFF, rgb -> nearest(palette, rgb));
        }
        return out;
    }

    private static int nearest(final int[] palette, final int rgb) {
        int best = palette[0];
        long bestDist = Long.MAX_VALUE;
        for (final int c : palette) {
            final int dr = ((c >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
            final int dg = ((c >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
            final int db = (c & 0xFF) - (rgb & 0xFF);
            final long d = 2L * dr * dr + 4L * dg * dg + 3L * db * db;
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ default flag

    /**
     * Greyscale default cloth: white field, darker border and a centred cross. BetterModel multiplies it by the
     * country colour ({@code tint}), so one shared model serves every country without a flag of its own.
     */
    public static int[] defaultTexture() {
        final int[] out = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int grey = 255;
                final boolean border = x < 3 || y < 3 || x >= WIDTH - 3 || y >= HEIGHT - 3;
                final boolean cross = Math.abs(x - 22) <= 3 || Math.abs(y - HEIGHT / 2 + 0.5) <= 3;
                if (border) {
                    grey = 150;
                } else if (cross) {
                    grey = 120;
                } else if ((x / 4 + y / 4) % 2 == 0) {
                    grey = 242; // faint damask
                }
                out[y * WIDTH + x] = 0xFF000000 | grey << 16 | grey << 8 | grey;
            }
        }
        return out;
    }

    /** {@link #defaultTexture()} multiplied by {@code rgb} — what players see (panel preview and map icon). */
    public static int[] defaultFlag(final int rgb) {
        return tint(defaultTexture(), rgb);
    }

    static int[] tint(final int[] argb, final int rgb) {
        final int[] out = new int[argb.length];
        final int tr = (rgb >> 16) & 0xFF;
        final int tg = (rgb >> 8) & 0xFF;
        final int tb = rgb & 0xFF;
        for (int i = 0; i < argb.length; i++) {
            final int p = argb[i];
            out[i] = p & 0xFF000000 | ((p >> 16) & 0xFF) * tr / 255 << 16 | ((p >> 8) & 0xFF) * tg / 255 << 8 | (p & 0xFF) * tb / 255;
        }
        return out;
    }

    // ------------------------------------------------------------------ codec

    /** Encodes ARGB pixels into a PNG built from scratch (no metadata, no colour profile, no text). */
    public static byte[] encodePng(final int[] argb, final int w, final int h) {
        final BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, argb, 0, w);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        try {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("No PNG writer");
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** Reads a stored texture back ({@link #WIDTH}×{@link #HEIGHT} PNG written by this class). */
    public static int[] decodeStored(final byte[] png) {
        try {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null || image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
                throw new IllegalArgumentException("Zapisana flaga ma złe wymiary.");
            }
            return image.getRGB(0, 0, WIDTH, HEIGHT, null, 0, WIDTH);
        } catch (final IOException e) {
            throw new IllegalArgumentException("Nie można odczytać zapisanej flagi.", e);
        }
    }

    /** Chunk types of a PNG in file order (tests and the moderation audit). */
    static List<String> pngChunks(final byte[] png) {
        final List<String> chunks = new ArrayList<>();
        int pos = 8;
        while (pos + 8 <= png.length) {
            final int len = (png[pos] & 0xFF) << 24 | (png[pos + 1] & 0xFF) << 16 | (png[pos + 2] & 0xFF) << 8 | png[pos + 3] & 0xFF;
            chunks.add(new String(png, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
            pos += 12 + len;
        }
        return chunks;
    }

    public static String sha256(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String dataUrl(final byte[] png) {
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
    }
}
