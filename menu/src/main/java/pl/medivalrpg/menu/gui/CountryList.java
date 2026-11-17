// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.gui;

import java.text.Collator;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.Color;
import org.bukkit.DyeColor;

/**
 * Content of a {@code type: countries} menu: one banner per country read from {@code KrajeApi.getCountries()}.
 * Pure (no server access): sorting, the banner colour nearest to the country colour and Polish plurals.
 */
public final class CountryList {

    /** Command run as the player when a country is clicked; the country name is appended. */
    public static final String TELEPORT_COMMAND = "kraj admin tp ";

    /**
     * What Menu shows about one country.
     *
     * @param name    country name (no formatting)
     * @param rgb     country colour {@code 0xRRGGBB}
     * @param npc     run by the NPC AI
     * @param ruler   ruler name (NPC ruler's display name for NPC countries)
     * @param chunks  claimed chunks, -1 when unknown
     * @param members player members, -1 when unknown
     */
    public record Entry(String name, int rgb, boolean npc, String ruler, int chunks, int members) {
    }

    private static final Collator POLISH = Collator.getInstance(Locale.forLanguageTag("pl-PL"));

    static {
        POLISH.setStrength(Collator.SECONDARY);
    }

    /** NPC countries first, then by name (Polish alphabetical order, case ignored). */
    public static final Comparator<Entry> ORDER = Comparator.comparing((Entry e) -> !e.npc())
        .thenComparing(Entry::name, (a, b) -> {
            synchronized (POLISH) {
                return POLISH.compare(a, b);
            }
        })
        .thenComparing(Entry::name);

    private CountryList() {
    }

    public static List<Entry> sorted(final Collection<Entry> countries) {
        return countries.stream().sorted(ORDER).toList();
    }

    /**
     * Dye colour closest to {@code rgb} ("redmean" weighted RGB distance, close to how the eye compares colours).
     * Banners only exist in the 16 dye colours.
     */
    public static DyeColor nearestDye(final int rgb) {
        DyeColor best = DyeColor.WHITE;
        double bestDistance = Double.MAX_VALUE;
        for (final DyeColor dye : DyeColor.values()) {
            final double d = distance(rgb, dye.getColor());
            if (d < bestDistance) {
                bestDistance = d;
                best = dye;
            }
        }
        return best;
    }

    static double distance(final int rgb, final Color color) {
        final int r1 = (rgb >> 16) & 0xFF;
        final int g1 = (rgb >> 8) & 0xFF;
        final int b1 = rgb & 0xFF;
        final double rMean = (r1 + color.getRed()) / 2.0;
        final int dr = r1 - color.getRed();
        final int dg = g1 - color.getGreen();
        final int db = b1 - color.getBlue();
        return (2 + rMean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rMean) / 256) * db * db;
    }

    /** {@code RED} → {@code RED_BANNER}. */
    public static String bannerMaterial(final DyeColor dye) {
        return dye.name() + "_BANNER";
    }

    /** Colour of the NPC mark on the banner: white on dark banners, black on light ones. */
    public static DyeColor markColor(final DyeColor base) {
        final Color c = base.getColor();
        final double luminance = 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
        return luminance < 110 ? DyeColor.WHITE : DyeColor.BLACK;
    }

    /** "1 chunk", "3 chunki", "5 chunków", "22 chunki", "12 chunków"; {@code "?"} when unknown. */
    public static String chunks(final int n) {
        if (n < 0) {
            return "?";
        }
        return n + " " + plural(n, "chunk", "chunki", "chunków");
    }

    /** Polish plural: 1 → one, 2–4 (not 12–14) → few, otherwise many. */
    public static String plural(final int n, final String one, final String few, final String many) {
        final int abs = Math.abs(n);
        if (abs == 1) {
            return one;
        }
        final int lastTwo = abs % 100;
        final int last = abs % 10;
        return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? few : many;
    }
}
