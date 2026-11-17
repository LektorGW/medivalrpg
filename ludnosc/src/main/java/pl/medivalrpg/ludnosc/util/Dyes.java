// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Nearest Minecraft dye colour for a country colour (pure, unit tested) — for wool, carpets and banners. */
public final class Dyes {

    /** Dye name → RGB (vanilla dye colours). */
    public static final Map<String, Integer> RGB = new LinkedHashMap<>();

    static {
        RGB.put("white", 0xF9FFFE);
        RGB.put("orange", 0xF9801D);
        RGB.put("magenta", 0xC74EBD);
        RGB.put("light_blue", 0x3AB3DA);
        RGB.put("yellow", 0xFED83D);
        RGB.put("lime", 0x80C71F);
        RGB.put("pink", 0xF38BAA);
        RGB.put("gray", 0x474F52);
        RGB.put("light_gray", 0x9D9D97);
        RGB.put("cyan", 0x169C9C);
        RGB.put("purple", 0x8932B8);
        RGB.put("blue", 0x3C44AA);
        RGB.put("brown", 0x835432);
        RGB.put("green", 0x5E7C16);
        RGB.put("red", 0xB02E26);
        RGB.put("black", 0x1D1D21);
    }

    private Dyes() {
    }

    /** Closest dye by weighted RGB distance ("redmean"). */
    public static String nearest(final int rgb) {
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;
        String best = "red";
        double bestD = Double.MAX_VALUE;
        for (final Map.Entry<String, Integer> e : RGB.entrySet()) {
            final int r2 = (e.getValue() >> 16) & 0xFF;
            final int g2 = (e.getValue() >> 8) & 0xFF;
            final int b2 = e.getValue() & 0xFF;
            final double rm = (r + r2) / 2.0;
            final double d = (2 + rm / 256) * (r - r2) * (r - r2) + 4 * (g - g2) * (g - g2) + (2 + (255 - rm) / 256) * (b - b2) * (b - b2);
            if (d < bestD) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }
}
