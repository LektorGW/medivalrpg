// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Vanilla banner approximation of a flag image (pure Java, no Bukkit, deterministic).
 *
 * <p>A vanilla banner is a base colour plus up to {@value #MAX_LAYERS} pattern layers, each one of the 16 dye colours.
 * The cloth front face is {@value #W}×{@value #H} texels (client jar: {@code BannerFlagModel} — {@code addBox(-10, 0, -2,
 * 20, 40, 1)} on a 64×64 texture; the pattern textures {@code textures/entity/banner/*.png} hold it at x 1..20, y 1..40).
 * The masks in {@code flag/banner-masks.txt} are the alpha channels of that region (only the shapes, see
 * {@code kraje/tools/extract_banner_masks.py}); only the patterns that the loom makes without a pattern item are used.
 *
 * <p>The 64×40 flag is squeezed to 20×40 (area average, so horizontal and vertical stripes stay where they were), then
 * a beam search over (pattern, dye) layers minimises the summed CIELAB distance (ΔE76) to the target; the best few
 * designs are refined layer by layer with the exact renderer (sRGB alpha blending like the game). A layer is only kept
 * when it lowers the error by more than a small penalty, so simple flags get few layers.
 */
public final class BannerFit {

    public static final int W = 20;
    public static final int H = 40;
    public static final int MAX_LAYERS = 6;
    private static final int N = W * H;
    private static final int BEAM = 12;

    /** The 16 dye colours with their banner colour (client {@code DyeColor.textureDiffuseColor}, verified in the jar). */
    public enum Dye {
        WHITE(0xF9FFFE, "biały"), ORANGE(0xF9801D, "pomarańczowy"), MAGENTA(0xC74EBD, "karmazynowy"),
        LIGHT_BLUE(0x3AB3DA, "jasnoniebieski"), YELLOW(0xFED83D, "żółty"), LIME(0x80C71F, "jasnozielony"),
        PINK(0xF38BAA, "różowy"), GRAY(0x474F52, "szary"), LIGHT_GRAY(0x9D9D97, "jasnoszary"), CYAN(0x169C9C, "turkusowy"),
        PURPLE(0x8932B8, "fioletowy"), BLUE(0x3C44AA, "niebieski"), BROWN(0x835432, "brązowy"), GREEN(0x5E7C16, "zielony"),
        RED(0xB02E26, "czerwony"), BLACK(0x1D1D21, "czarny");

        private final int rgb;
        private final String polish;

        Dye(final int rgb, final String polish) {
            this.rgb = rgb;
            this.polish = polish;
        }

        public int rgb() {
            return this.rgb;
        }

        public String polish() {
            return this.polish;
        }
    }

    /** One pattern layer: {@code pattern} is the vanilla id (e.g. {@code half_horizontal_bottom}). */
    public record Layer(String pattern, Dye dye) {
    }

    /**
     * @param base        base colour of the banner (the banner item material)
     * @param layers      pattern layers, bottom first (at most {@link #MAX_LAYERS})
     * @param meanDeltaE  coverage-weighted mean ΔE76 between the rendered banner and the squeezed flag
     * @param baselineDeltaE the same for the best single-colour banner (for comparison)
     * @param paletteDeltaE  floor of the palette: every texel in its nearest dye colour (no banner can do better)
     */
    public record Design(Dye base, List<Layer> layers, double meanDeltaE, double baselineDeltaE, double paletteDeltaE) {
        public Design {
            layers = List.copyOf(layers);
        }

        /** Short Polish description, e.g. "tło: biały; połowa dolna: czerwony". */
        public String describe() {
            final StringBuilder b = new StringBuilder("tło: ").append(this.base.polish());
            for (final Layer l : this.layers) {
                b.append("; ").append(polishPattern(l.pattern())).append(": ").append(l.dye().polish());
            }
            return b.toString();
        }
    }

    // ------------------------------------------------------------------ masks

    private static final Map<String, float[]> MASKS;
    private static final float SHADE;
    /** Patterns usable as layers (everything in the mask file except {@code base}), in file order. */
    public static final List<String> PATTERNS;

    static {
        final Map<String, float[]> masks = new LinkedHashMap<>();
        float shade = 1f;
        try (InputStream in = BannerFit.class.getResourceAsStream("/flag/banner-masks.txt")) {
            if (in == null) {
                throw new IllegalStateException("flag/banner-masks.txt missing from the jar");
            }
            final BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                final String[] parts = line.trim().split(" ", 2);
                if (parts[0].equals("shade")) {
                    shade = Float.parseFloat(parts[1]);
                    continue;
                }
                final byte[] alpha = Base64.getDecoder().decode(parts[1]);
                if (alpha.length != N) {
                    throw new IllegalStateException("bad mask " + parts[0]);
                }
                final float[] m = new float[N];
                for (int i = 0; i < N; i++) {
                    m[i] = (alpha[i] & 0xFF) / 255f;
                }
                masks.put(parts[0], m);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        MASKS = Collections.unmodifiableMap(masks);
        SHADE = shade;
        final List<String> patterns = new ArrayList<>(masks.keySet());
        patterns.remove("base");
        PATTERNS = List.copyOf(patterns);
    }

    private BannerFit() {
    }

    private static final Map<String, String> POLISH_PATTERNS = Map.ofEntries(
        Map.entry("stripe_bottom", "pas dolny"), Map.entry("stripe_top", "pas górny"), Map.entry("stripe_left", "pas lewy"),
        Map.entry("stripe_right", "pas prawy"), Map.entry("stripe_center", "słup"), Map.entry("stripe_middle", "pas środkowy"),
        Map.entry("stripe_downright", "skos"), Map.entry("stripe_downleft", "skos lewy"), Map.entry("small_stripes", "prążki"),
        Map.entry("cross", "krzyż skośny"), Map.entry("straight_cross", "krzyż"), Map.entry("triangle_bottom", "trójkąt dolny"),
        Map.entry("triangle_top", "trójkąt górny"), Map.entry("triangles_bottom", "ząbki dolne"), Map.entry("triangles_top", "ząbki górne"),
        Map.entry("diagonal_left", "skos górny lewy"), Map.entry("diagonal_up_right", "skos dolny prawy"),
        Map.entry("diagonal_up_left", "skos dolny lewy"), Map.entry("diagonal_right", "skos górny prawy"), Map.entry("circle", "koło"),
        Map.entry("rhombus", "romb"), Map.entry("half_vertical", "połowa lewa"), Map.entry("half_horizontal", "połowa górna"),
        Map.entry("half_vertical_right", "połowa prawa"), Map.entry("half_horizontal_bottom", "połowa dolna"),
        Map.entry("border", "obramowanie"), Map.entry("gradient", "gradient"), Map.entry("gradient_up", "gradient od dołu"),
        Map.entry("square_bottom_left", "narożnik lewy dolny"), Map.entry("square_bottom_right", "narożnik prawy dolny"),
        Map.entry("square_top_left", "narożnik lewy górny"), Map.entry("square_top_right", "narożnik prawy górny"));

    /** Polish name of a vanilla pattern id (the id itself when unknown). */
    public static String polishPattern(final String pattern) {
        return POLISH_PATTERNS.getOrDefault(pattern, pattern);
    }

    static float[] mask(final String pattern) {
        final float[] m = MASKS.get(pattern);
        if (m == null) {
            throw new IllegalArgumentException("unknown banner pattern " + pattern);
        }
        return m;
    }

    // ------------------------------------------------------------------ colour

    /** sRGB 0..255 → CIELAB (D65), written into {@code out[o..o+2]}. */
    static void lab(final double r, final double g, final double b, final float[] out, final int o) {
        final double lr = lin(r / 255.0);
        final double lg = lin(g / 255.0);
        final double lb = lin(b / 255.0);
        final double x = (0.4124564 * lr + 0.3575761 * lg + 0.1804375 * lb) / 0.95047;
        final double y = 0.2126729 * lr + 0.7151522 * lg + 0.0721750 * lb;
        final double z = (0.0193339 * lr + 0.1191920 * lg + 0.9503041 * lb) / 1.08883;
        final double fx = f(x);
        final double fy = f(y);
        final double fz = f(z);
        out[o] = (float) (116 * fy - 16);
        out[o + 1] = (float) (500 * (fx - fy));
        out[o + 2] = (float) (200 * (fy - fz));
    }

    private static double lin(final double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(final double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    /** Colour of a dye on the cloth (texture shade × dye), sRGB. */
    static double[] cloth(final Dye d) {
        return new double[] {((d.rgb >> 16) & 0xFF) * SHADE, ((d.rgb >> 8) & 0xFF) * SHADE, (d.rgb & 0xFF) * SHADE};
    }

    private static final float[][] DYE_LAB = new float[Dye.values().length][3];

    static {
        for (final Dye d : Dye.values()) {
            final double[] c = cloth(d);
            lab(c[0], c[1], c[2], DYE_LAB[d.ordinal()], 0);
        }
    }

    // ------------------------------------------------------------------ target

    /** The flag squeezed to the banner face: CIELAB per texel and coverage weight (0..1, holes weigh 0). */
    record Target(float[] lab, float[] weight, double totalWeight) {
    }

    /** {@code flag} is {@link FlagImage#WIDTH}×{@link FlagImage#HEIGHT} ARGB; any w×h works (area average to 20×40). */
    static Target target(final int[] flag, final int fw, final int fh) {
        final float[] lab = new float[N * 3];
        final float[] weight = new float[N];
        double total = 0;
        final double sx = (double) fw / W;
        final double sy = (double) fh / H;
        for (int by = 0; by < H; by++) {
            final double y0 = by * sy;
            final double y1 = y0 + sy;
            for (int bx = 0; bx < W; bx++) {
                final double x0 = bx * sx;
                final double x1 = x0 + sx;
                double r = 0;
                double g = 0;
                double b = 0;
                double a = 0;
                double area = 0;
                for (int y = (int) Math.floor(y0); y < Math.min(fh, (int) Math.ceil(y1)); y++) {
                    final double wy = Math.min(y1, y + 1) - Math.max(y0, y);
                    for (int x = (int) Math.floor(x0); x < Math.min(fw, (int) Math.ceil(x1)); x++) {
                        final double wx = Math.min(x1, x + 1) - Math.max(x0, x);
                        final double w = wx * wy;
                        if (w <= 0) {
                            continue;
                        }
                        final int p = flag[y * fw + x];
                        final double pa = (p >>> 24) / 255.0;
                        r += ((p >> 16) & 0xFF) * pa * w;
                        g += ((p >> 8) & 0xFF) * pa * w;
                        b += (p & 0xFF) * pa * w;
                        a += pa * w;
                        area += w;
                    }
                }
                final int i = by * W + bx;
                if (a <= 0 || area <= 0) {
                    lab(255, 255, 255, lab, i * 3);
                    weight[i] = 0;
                    continue;
                }
                lab(r / a, g / a, b / a, lab, i * 3);
                weight[i] = (float) (a / area);
                total += weight[i];
            }
        }
        return new Target(lab, weight, total);
    }

    // ------------------------------------------------------------------ search

    private record State(Dye base, List<Layer> layers, float[] lab, double err) {
    }

    private record Candidate(int state, String pattern, Dye dye, double err) {
    }

    /** Fits a banner to a flag image ({@code fw}×{@code fh} ARGB). */
    public static Design fit(final int[] flag, final int fw, final int fh) {
        final Target t = target(flag, fw, fh);
        // 1. every base colour
        final List<State> beam = new ArrayList<>();
        for (final Dye d : Dye.values()) {
            final float[] lab = new float[N * 3];
            for (int i = 0; i < N; i++) {
                System.arraycopy(DYE_LAB[d.ordinal()], 0, lab, i * 3, 3);
            }
            beam.add(new State(d, List.of(), lab, error(lab, t)));
        }
        beam.sort((a, b) -> Double.compare(a.err, b.err));
        final double baseline = beam.getFirst().err;
        final double penalty = Math.max(1e-6, baseline * 0.004);
        State best = beam.getFirst();
        List<State> current = new ArrayList<>(beam.subList(0, Math.min(BEAM, beam.size())));
        final List<State> finalists = new ArrayList<>(current.subList(0, Math.min(3, current.size())));
        // 2. beam search over layers (approximate blending in Lab during the search)
        for (int depth = 1; depth <= MAX_LAYERS && !current.isEmpty(); depth++) {
            final PriorityQueue<Candidate> top = new PriorityQueue<>((a, b) -> Double.compare(b.err, a.err));
            for (int s = 0; s < current.size(); s++) {
                final State st = current.get(s);
                final Layer last = st.layers.isEmpty() ? null : st.layers.getLast();
                for (final String p : PATTERNS) {
                    final float[] m = MASKS.get(p);
                    for (final Dye d : Dye.values()) {
                        if (last != null && last.pattern.equals(p) && last.dye == d) {
                            continue;
                        }
                        final double delta = deltaError(st.lab, t, m, DYE_LAB[d.ordinal()]);
                        if (delta >= -penalty) {
                            continue;
                        }
                        final double err = st.err + delta;
                        if (top.size() < BEAM) {
                            top.add(new Candidate(s, p, d, err));
                        } else if (err < top.peek().err) {
                            top.poll();
                            top.add(new Candidate(s, p, d, err));
                        }
                    }
                }
            }
            final List<Candidate> chosen = new ArrayList<>(top);
            chosen.sort((a, b) -> Double.compare(a.err, b.err));
            final List<State> next = new ArrayList<>();
            for (final Candidate c : chosen) {
                final State st = current.get(c.state);
                final float[] lab = st.lab.clone();
                apply(lab, MASKS.get(c.pattern), DYE_LAB[c.dye.ordinal()]);
                final List<Layer> layers = new ArrayList<>(st.layers);
                layers.add(new Layer(c.pattern, c.dye));
                next.add(new State(st.base, layers, lab, c.err));
            }
            current = next;
            for (final State st : current) {
                if (st.err + penalty * st.layers.size() < best.err + penalty * best.layers.size()) {
                    best = st;
                }
            }
            if (!current.isEmpty()) {
                finalists.add(current.getFirst());
            }
        }
        finalists.add(best);
        // 3. exact refinement of the finalists, keep the best (error + layer penalty)
        Design out = null;
        double outScore = Double.MAX_VALUE;
        final double exactPenalty = Math.max(1e-6, exactError(new Design(beam.getFirst().base, List.of(), 0, 0, 0), t) * 0.004);
        for (final State st : finalists) {
            final Design refined = refine(st.base, st.layers, t, exactPenalty);
            final double score = exactError(refined, t) + exactPenalty * refined.layers().size();
            if (score < outScore - 1e-9) {
                out = refined;
                outScore = score;
            }
        }
        return new Design(out.base(), out.layers(), meanDeltaE(out, t), meanDeltaE(new Design(beam.getFirst().base, List.of(), 0, 0, 0), t),
            paletteDeltaE(t));
    }

    /** Fits the 64×40 stored flag. */
    public static Design fit(final int[] flag64x40) {
        return fit(flag64x40, FlagImage.WIDTH, FlagImage.HEIGHT);
    }

    private static double error(final float[] lab, final Target t) {
        double e = 0;
        for (int i = 0; i < N; i++) {
            final float w = t.weight[i];
            if (w == 0) {
                continue;
            }
            final int o = i * 3;
            final double dl = lab[o] - t.lab[o];
            final double da = lab[o + 1] - t.lab[o + 1];
            final double db = lab[o + 2] - t.lab[o + 2];
            e += w * Math.sqrt(dl * dl + da * da + db * db);
        }
        return e;
    }

    private static double deltaError(final float[] lab, final Target t, final float[] mask, final float[] dye) {
        double delta = 0;
        for (int i = 0; i < N; i++) {
            final float a = mask[i];
            final float w = t.weight[i];
            if (a == 0 || w == 0) {
                continue;
            }
            final int o = i * 3;
            final float l0 = lab[o];
            final float a0 = lab[o + 1];
            final float b0 = lab[o + 2];
            final float l1 = l0 + (dye[0] - l0) * a;
            final float a1 = a0 + (dye[1] - a0) * a;
            final float b1 = b0 + (dye[2] - b0) * a;
            final float tl = t.lab[o];
            final float ta = t.lab[o + 1];
            final float tb = t.lab[o + 2];
            final double before = Math.sqrt((l0 - tl) * (l0 - tl) + (a0 - ta) * (a0 - ta) + (b0 - tb) * (b0 - tb));
            final double after = Math.sqrt((l1 - tl) * (l1 - tl) + (a1 - ta) * (a1 - ta) + (b1 - tb) * (b1 - tb));
            delta += w * (after - before);
        }
        return delta;
    }

    private static void apply(final float[] lab, final float[] mask, final float[] dye) {
        for (int i = 0; i < N; i++) {
            final float a = mask[i];
            if (a == 0) {
                continue;
            }
            final int o = i * 3;
            lab[o] += (dye[0] - lab[o]) * a;
            lab[o + 1] += (dye[1] - lab[o + 1]) * a;
            lab[o + 2] += (dye[2] - lab[o + 2]) * a;
        }
    }

    /** Coordinate descent with the exact renderer: change the base, replace or drop one layer while it helps. */
    private static Design refine(final Dye base0, final List<Layer> layers0, final Target t, final double penalty) {
        Dye base = base0;
        List<Layer> layers = new ArrayList<>(layers0);
        double score = exactError(new Design(base, layers, 0, 0, 0), t) + penalty * layers.size();
        for (int pass = 0; pass < 2; pass++) {
            boolean improved = false;
            for (final Dye d : Dye.values()) {
                final double s = exactError(new Design(d, layers, 0, 0, 0), t) + penalty * layers.size();
                if (s < score - 1e-9) {
                    score = s;
                    base = d;
                    improved = true;
                }
            }
            for (int i = 0; i < layers.size(); i++) {
                final List<Layer> without = new ArrayList<>(layers);
                without.remove(i);
                final double drop = exactError(new Design(base, without, 0, 0, 0), t) + penalty * without.size();
                if (drop < score - 1e-9) {
                    score = drop;
                    layers = without;
                    improved = true;
                    i--;
                    continue;
                }
                for (final String p : PATTERNS) {
                    for (final Dye d : Dye.values()) {
                        final Layer cand = new Layer(p, d);
                        if (cand.equals(layers.get(i))) {
                            continue;
                        }
                        final List<Layer> trial = new ArrayList<>(layers);
                        trial.set(i, cand);
                        final double s = exactError(new Design(base, trial, 0, 0, 0), t) + penalty * trial.size();
                        if (s < score - 1e-9) {
                            score = s;
                            layers = trial;
                            improved = true;
                        }
                    }
                }
            }
            if (!improved) {
                break;
            }
        }
        return new Design(base, layers, 0, 0, 0);
    }

    // ------------------------------------------------------------------ exact renderer

    /** Renders the banner face like the game: sRGB alpha blending of shaded dye colours. Returns 20×40 ARGB. */
    public static int[] render(final Design d) {
        final double[] rgb = renderRgb(d);
        final int[] out = new int[N];
        for (int i = 0; i < N; i++) {
            out[i] = 0xFF000000 | clamp(rgb[i * 3]) << 16 | clamp(rgb[i * 3 + 1]) << 8 | clamp(rgb[i * 3 + 2]);
        }
        return out;
    }

    private static int clamp(final double v) {
        return (int) Math.clamp(Math.round(v), 0, 255);
    }

    private static double[] renderRgb(final Design d) {
        final double[] rgb = new double[N * 3];
        final double[] base = cloth(d.base());
        for (int i = 0; i < N; i++) {
            System.arraycopy(base, 0, rgb, i * 3, 3);
        }
        for (final Layer l : d.layers()) {
            final float[] m = mask(l.pattern());
            final double[] c = cloth(l.dye());
            for (int i = 0; i < N; i++) {
                final float a = m[i];
                if (a == 0) {
                    continue;
                }
                final int o = i * 3;
                rgb[o] += (c[0] - rgb[o]) * a;
                rgb[o + 1] += (c[1] - rgb[o + 1]) * a;
                rgb[o + 2] += (c[2] - rgb[o + 2]) * a;
            }
        }
        return rgb;
    }

    /** CIELAB of an 8-bit sRGB colour (cached: a banner has only a few dozen distinct colours). */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, float[]> LAB_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static float[] lab8(final int rgb) {
        if (LAB_CACHE.size() > 200_000) {
            LAB_CACHE.clear();
        }
        return LAB_CACHE.computeIfAbsent(rgb, c -> {
            final float[] out = new float[3];
            lab((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, out, 0);
            return out;
        });
    }

    private static double exactError(final Design d, final Target t) {
        final double[] rgb = renderRgb(d);
        final float[] lab = new float[N * 3];
        for (int i = 0; i < N; i++) {
            if (t.weight[i] != 0) {
                final float[] c = lab8(clamp(rgb[i * 3]) << 16 | clamp(rgb[i * 3 + 1]) << 8 | clamp(rgb[i * 3 + 2]));
                System.arraycopy(c, 0, lab, i * 3, 3);
            }
        }
        return error(lab, t);
    }

    private static double meanDeltaE(final Design d, final Target t) {
        final double[] rgb = renderRgb(d);
        final float[] lab = new float[3];
        double sum = 0;
        for (int i = 0; i < N; i++) {
            final float w = t.weight[i];
            if (w == 0) {
                continue;
            }
            System.arraycopy(lab8(clamp(rgb[i * 3]) << 16 | clamp(rgb[i * 3 + 1]) << 8 | clamp(rgb[i * 3 + 2])), 0, lab, 0, 3);
            final int o = i * 3;
            final double dl = lab[0] - t.lab[o];
            final double da = lab[1] - t.lab[o + 1];
            final double db = lab[2] - t.lab[o + 2];
            sum += w * Math.sqrt(dl * dl + da * da + db * db);
        }
        return t.totalWeight <= 0 ? 0 : sum / t.totalWeight;
    }

    /** Mean ΔE76 when every texel takes its nearest dye colour — the lower bound for any banner. */
    private static double paletteDeltaE(final Target t) {
        double sum = 0;
        for (int i = 0; i < N; i++) {
            final float w = t.weight[i];
            if (w == 0) {
                continue;
            }
            double best = Double.MAX_VALUE;
            for (final float[] d : DYE_LAB) {
                final int o = i * 3;
                final double dl = d[0] - t.lab[o];
                final double da = d[1] - t.lab[o + 1];
                final double db = d[2] - t.lab[o + 2];
                best = Math.min(best, dl * dl + da * da + db * db);
            }
            sum += w * Math.sqrt(best);
        }
        return t.totalWeight <= 0 ? 0 : sum / t.totalWeight;
    }

    /** Mean ΔE76 of a design against a flag (tests, panel). */
    public static double meanDeltaE(final Design d, final int[] flag, final int fw, final int fh) {
        return meanDeltaE(d, target(flag, fw, fh));
    }

    /** Preview PNG of the banner face, each texel {@code scale}×{@code scale} (nearest neighbour). */
    public static byte[] previewPng(final Design d, final int scale) {
        final int[] face = render(d);
        final int s = Math.clamp(scale, 1, 16);
        final int[] big = new int[W * s * H * s];
        for (int y = 0; y < H * s; y++) {
            for (int x = 0; x < W * s; x++) {
                big[y * W * s + x] = face[(y / s) * W + x / s];
            }
        }
        return FlagImage.encodePng(big, W * s, H * s);
    }

    /** Stable textual key of a design ({@code base|pattern:dye|…}), e.g. for logs and tests. */
    public static String key(final Design d) {
        final StringBuilder b = new StringBuilder(d.base().name().toLowerCase(Locale.ROOT));
        for (final Layer l : d.layers()) {
            b.append('|').append(l.pattern()).append(':').append(l.dye().name().toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }
}
