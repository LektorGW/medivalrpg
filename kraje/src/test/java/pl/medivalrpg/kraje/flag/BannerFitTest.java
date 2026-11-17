// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import javax.imageio.ImageIO;
import org.bukkit.DyeColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The vanilla banner approximation: measurable error (mean CIELAB ΔE76) on sample flags, structure of the result,
 * determinism. Writes previews (flag next to the rendered banner) to {@code build/.../banner-previews/}.
 */
class BannerFitTest {

    static final int W = FlagImage.WIDTH;
    static final int H = FlagImage.HEIGHT;

    static int[] flag(final IntBinaryOperator px) {
        final int[] out = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                out[y * W + x] = px.applyAsInt(x, y);
            }
        }
        return out;
    }

    static final int WHITE = 0xFFFFFFFF;
    static final int POL_RED = 0xFFDC143C;

    /** Sample flags (64×40 like the stored upload). */
    static final Map<String, int[]> SAMPLES = new LinkedHashMap<>();

    @BeforeAll
    static void samples() {
        SAMPLES.put("polska", flag((x, y) -> y < H / 2 ? WHITE : POL_RED));
        SAMPLES.put("krzyz", flag((x, y) -> Math.abs(x - 31.5) <= 4 || Math.abs(y - 19.5) <= 4 ? WHITE : 0xFFC8102E));
        SAMPLES.put("krzyz-skandynawski", flag((x, y) -> Math.abs(x - 22.5) <= 3 || Math.abs(y - 19.5) <= 3 ? 0xFFFECC00 : 0xFF006AA7));
        SAMPLES.put("niemcy", flag((x, y) -> y < 13 ? 0xFF000000 : y < 27 ? 0xFFDD0000 : 0xFFFFCE00));
        SAMPLES.put("francja", flag((x, y) -> x < 21 ? 0xFF002395 : x < 43 ? WHITE : 0xFFED2939));
        // "herb": red field, white shield with a golden crown above, dark border
        SAMPLES.put("herb", flag((x, y) -> {
            if (x < 3 || y < 3 || x >= W - 3 || y >= H - 3) {
                return 0xFF3A0A0A;
            }
            final double dx = (x - 31.5) / 10.0;
            final double dy = (y - 22.0) / 12.0;
            final boolean shield = y >= 12 && y <= 34 && Math.abs(dx) <= 1.0 && (y < 26 || dx * dx + (y - 26) * (y - 26) / 64.0 <= 1.0);
            if (shield) {
                return WHITE;
            }
            if (y >= 6 && y < 11 && Math.abs(x - 31.5) <= 7 && (y >= 8 || (x % 4) != 0)) {
                return 0xFFE0B33C;
            }
            return 0xFFB71C1C;
        }));
        SAMPLES.put("domyslna-niebieska", FlagImage.defaultFlag(0x1E5AA8));
    }

    static final Map<String, BannerFit.Design> FITS = new LinkedHashMap<>();

    static BannerFit.Design fit(final String name) {
        return FITS.computeIfAbsent(name, n -> BannerFit.fit(SAMPLES.get(n)));
    }

    @Test
    void dyeTableMatchesBukkit() {
        for (final BannerFit.Dye d : BannerFit.Dye.values()) {
            assertEquals(DyeColor.valueOf(d.name()).getColor().asRGB(), d.rgb(), d.name());
        }
        assertEquals(16, BannerFit.Dye.values().length);
    }

    @Test
    void masksAreTheLoomPatterns() {
        assertEquals(32, BannerFit.PATTERNS.size());
        assertTrue(BannerFit.PATTERNS.contains("half_horizontal_bottom"));
        assertTrue(BannerFit.PATTERNS.contains("straight_cross"));
        // never patterns that need a pattern item (creeper, skull, mojang, flower, globe, piglin, flow, guster, bricks...)
        for (final String forbidden : List.of("creeper", "skull", "mojang", "flower", "globe", "piglin", "flow", "guster", "bricks", "curly_border")) {
            assertTrue(!BannerFit.PATTERNS.contains(forbidden), forbidden);
        }
        // geometry sanity: half_horizontal covers exactly the top half, stripe_left the left columns
        final float[] top = BannerFit.mask("half_horizontal");
        assertEquals(1f, top[5 * BannerFit.W + 10], 0.01f);
        assertEquals(0f, top[35 * BannerFit.W + 10], 0.01f);
        final float[] left = BannerFit.mask("stripe_left");
        assertTrue(left[20 * BannerFit.W] > 0.5f);
        assertEquals(0f, left[20 * BannerFit.W + 19], 0.01f);
    }

    @Test
    void polandIsWhiteOverRed() {
        final BannerFit.Design d = fit("polska");
        System.out.println("polska: " + BannerFit.key(d) + " ΔE=" + d.meanDeltaE() + " (1 kolor: " + d.baselineDeltaE() + ", paleta: " + d.paletteDeltaE() + ")");
        assertTrue(d.layers().size() <= 2, BannerFit.key(d));
        final int[] face = BannerFit.render(d);
        assertTrue(isNear(face[5 * BannerFit.W + 10], BannerFit.Dye.WHITE), "top must be white");
        assertTrue(isNear(face[35 * BannerFit.W + 10], BannerFit.Dye.RED), "bottom must be red");
        assertTrue(d.meanDeltaE() <= d.paletteDeltaE() + 1.0, "ΔE " + d.meanDeltaE() + " vs palette " + d.paletteDeltaE());
        assertTrue(d.meanDeltaE() < d.baselineDeltaE() * 0.4, "must beat a single colour clearly");
    }

    @Test
    void crossKeepsTheCross() {
        final BannerFit.Design d = fit("krzyz");
        System.out.println("krzyz: " + BannerFit.key(d) + " ΔE=" + d.meanDeltaE() + " (1 kolor: " + d.baselineDeltaE() + ", paleta: " + d.paletteDeltaE() + ")");
        final int[] face = BannerFit.render(d);
        assertTrue(isNear(face[20 * BannerFit.W + 10], BannerFit.Dye.WHITE), "centre of the cross must be white");
        assertTrue(isNear(face[3 * BannerFit.W + 2], BannerFit.Dye.RED), "corner must be red");
        assertTrue(d.meanDeltaE() <= d.paletteDeltaE() + 8.0, "ΔE " + d.meanDeltaE() + " vs palette " + d.paletteDeltaE());
        assertTrue(d.meanDeltaE() < d.baselineDeltaE() * 0.6);
    }

    @Test
    void tricoloursAndScandinavianCross() {
        for (final String name : List.of("niemcy", "francja", "krzyz-skandynawski")) {
            final BannerFit.Design d = fit(name);
            System.out.println(name + ": " + BannerFit.key(d) + " ΔE=" + d.meanDeltaE() + " (1 kolor: " + d.baselineDeltaE() + ", paleta: " + d.paletteDeltaE() + ")");
            assertTrue(d.meanDeltaE() < d.baselineDeltaE() * 0.6, name + " ΔE " + d.meanDeltaE() + " vs " + d.baselineDeltaE());
            assertTrue(d.meanDeltaE() <= d.paletteDeltaE() + 5.0, name + " ΔE " + d.meanDeltaE() + " vs palette " + d.paletteDeltaE());
        }
        final int[] de = BannerFit.render(fit("niemcy"));
        assertTrue(isNear(de[3 * BannerFit.W + 10], BannerFit.Dye.BLACK), "German top black");
        assertTrue(isNear(de[37 * BannerFit.W + 10], BannerFit.Dye.YELLOW), "German bottom yellow");
        final int[] fr = BannerFit.render(fit("francja"));
        assertTrue(isNear(fr[20 * BannerFit.W + 1], BannerFit.Dye.BLUE), "French left blue");
        assertTrue(isNear(fr[20 * BannerFit.W + 18], BannerFit.Dye.RED), "French right red");
    }

    @Test
    void coatOfArmsBeatsSingleColour() {
        final BannerFit.Design d = fit("herb");
        System.out.println("herb: " + BannerFit.key(d) + " ΔE=" + d.meanDeltaE() + " (1 kolor: " + d.baselineDeltaE() + ", paleta: " + d.paletteDeltaE() + ")");
        assertTrue(d.meanDeltaE() < d.baselineDeltaE() * 0.75, "ΔE " + d.meanDeltaE() + " vs " + d.baselineDeltaE());
        final int[] face = BannerFit.render(d);
        assertTrue(isNear(face[22 * BannerFit.W + 10], BannerFit.Dye.WHITE) || isNear(face[22 * BannerFit.W + 10], BannerFit.Dye.LIGHT_GRAY),
            "shield centre must be light");
    }

    @Test
    void defaultFlagUsesTheCountryHue() {
        final BannerFit.Design d = fit("domyslna-niebieska");
        System.out.println("domyslna: " + BannerFit.key(d) + " ΔE=" + d.meanDeltaE() + " (1 kolor: " + d.baselineDeltaE() + ", paleta: " + d.paletteDeltaE() + ")");
        assertTrue(d.base() == BannerFit.Dye.BLUE || d.base() == BannerFit.Dye.LIGHT_BLUE || d.base() == BannerFit.Dye.CYAN, d.base().name());
    }

    @Test
    void limitsAndDeterminism() {
        for (final String name : SAMPLES.keySet()) {
            final BannerFit.Design d = fit(name);
            assertTrue(d.layers().size() <= BannerFit.MAX_LAYERS);
            assertTrue(d.meanDeltaE() <= d.baselineDeltaE() + 1e-9, name);
        }
        assertEquals(BannerFit.key(fit("herb")), BannerFit.key(BannerFit.fit(SAMPLES.get("herb"))));
    }

    @Test
    void holesDoNotCount() {
        // swallowtail: a transparent notch on the fly side must not drag the fit
        final int[] notched = flag((x, y) -> x > 50 && Math.abs(y - 19.5) < (x - 50) * 1.4 ? 0 : POL_RED);
        final BannerFit.Design d = BannerFit.fit(notched);
        assertEquals(BannerFit.Dye.RED, d.base());
        assertTrue(d.layers().isEmpty(), BannerFit.key(d));
    }

    /** Previews for the user: flag (×3) and the rendered banner (×4) side by side, one sheet. */
    @Test
    void writePreviews() throws Exception {
        final Path dir = Path.of(System.getProperty("bannerPreviewDir", "build/banner-previews"));
        Files.createDirectories(dir);
        final int cellW = 64 * 3 + 20 + 20 * 4 + 40;
        final int cellH = 40 * 4 + 100;
        final List<String> names = new ArrayList<>(SAMPLES.keySet());
        final BufferedImage sheet = new BufferedImage(cellW * 2, cellH * ((names.size() + 1) / 2), BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2B2B2B));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            final BannerFit.Design d = fit(name);
            final BufferedImage flag = ImageIO.read(new ByteArrayInputStream(FlagImage.encodePng(SAMPLES.get(name), W, H)));
            final BufferedImage banner = ImageIO.read(new ByteArrayInputStream(BannerFit.previewPng(d, 4)));
            Files.write(dir.resolve(name + "-sztandar.png"), BannerFit.previewPng(d, 6));
            final int ox = (i % 2) * cellW + 10;
            final int oy = (i / 2) * cellH + 10;
            g.drawImage(flag, ox, oy + 20, 64 * 3, 40 * 3, null);
            g.drawImage(banner, ox + 64 * 3 + 20, oy, null);
            g.setColor(Color.WHITE);
            g.drawString(name + "  ΔE " + String.format(java.util.Locale.ROOT, "%.1f", d.meanDeltaE())
                + " (1 kolor " + String.format(java.util.Locale.ROOT, "%.1f", d.baselineDeltaE())
                + ", paleta " + String.format(java.util.Locale.ROOT, "%.1f", d.paletteDeltaE()) + ")", ox, oy + 40 * 4 + 16);
            g.setColor(new Color(0xBBBBBB));
            int line = 0;
            StringBuilder row = new StringBuilder();
            for (final String part : d.describe().split("; ")) {
                if (row.length() + part.length() > 46 && !row.isEmpty()) {
                    g.drawString(row.toString(), ox, oy + 40 * 4 + 32 + 14 * line++);
                    row = new StringBuilder();
                }
                row.append(row.isEmpty() ? "" : "; ").append(part);
            }
            g.drawString(row.toString(), ox, oy + 40 * 4 + 32 + 14 * line);
        }
        g.dispose();
        ImageIO.write(sheet, "png", dir.resolve("podglad-sztandarow.png").toFile());
        assertTrue(Files.size(dir.resolve("podglad-sztandarow.png")) > 1000);
    }

    static boolean isNear(final int argb, final BannerFit.Dye dye) {
        final double[] c = BannerFit.cloth(dye);
        final int r = (argb >> 16) & 0xFF;
        final int g = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        return Math.abs(r - c[0]) < 12 && Math.abs(g - c[1]) < 12 && Math.abs(b - c[2]) < 12;
    }
}
