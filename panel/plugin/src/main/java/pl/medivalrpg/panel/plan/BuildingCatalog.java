// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.plan;

import java.util.Locale;
import java.util.Map;

/**
 * Footprints (width along X × depth along Z at rotation 0, in blocks) and categories of Ludnosc's building templates.
 * {@code LudnoscApi.BuildingRequirement} carries no size, so the panel uses this table, measured from
 * {@code ludnosc/src/main/resources/budynki/*.yml} (layer 0) on 2026-10-26. Unknown types get {@link #DEFAULT}.
 * Pure, no Bukkit.
 */
public final class BuildingCatalog {

    /** One template: footprint and Ludnosc category (housing, economy, civic, …). */
    public record Entry(int width, int depth, String category) {
    }

    public static final Entry DEFAULT = new Entry(9, 9, "other");

    private static final Map<String, Entry> TYPES = Map.ofEntries(
        e("akademia", 17, 15, "military"), e("arena", 23, 19, "civic"), e("bank", 13, 12, "economy"),
        e("biblioteka", 11, 9, "civic"), e("blok", 15, 11, "housing"), e("brama", 13, 7, "defense"),
        e("browar", 11, 9, "economy"), e("chata", 9, 8, "housing"), e("cmentarz", 11, 11, "religious"),
        e("dom", 11, 9, "housing"), e("drukarnia", 11, 9, "economy"), e("dworzec", 23, 15, "infrastructure"),
        e("dworzec_nowoczesny", 25, 15, "infrastructure"), e("elektrownia", 19, 14, "economy"), e("fabryka", 19, 14, "economy"),
        e("galeria", 23, 17, "economy"), e("kamienica", 11, 9, "housing"), e("kamienica_czynszowa", 11, 11, "housing"),
        e("kamienica_renesansowa", 11, 10, "housing"), e("kamieniolom", 15, 15, "economy"), e("karczma", 13, 9, "civic"),
        e("katedra", 15, 28, "religious"), e("kopalnia", 9, 9, "economy"), e("kosciol", 9, 20, "religious"),
        e("koszary", 13, 9, "military"), e("koszary2", 15, 11, "military"), e("kuznia", 9, 9, "economy"),
        e("laboratorium", 15, 11, "civic"), e("latarnia", 7, 7, "economy"), e("laznia", 11, 9, "civic"),
        e("lotnisko", 41, 17, "infrastructure"), e("magazyn", 11, 11, "food"), e("mlyn", 9, 10, "economy"),
        e("most", 7, 19, "infrastructure"), e("most_stalowy", 7, 25, "infrastructure"), e("mur", 13, 6, "defense"),
        e("obserwatorium", 9, 9, "civic"), e("ogrody", 19, 17, "representative"), e("palac", 30, 29, "representative"),
        e("palac_renesansowy", 25, 23, "representative"), e("park", 17, 17, "civic"), e("parking", 15, 15, "infrastructure"),
        e("parlament", 25, 17, "representative"), e("piekarnia", 9, 8, "economy"), e("pole", 9, 9, "farm"),
        e("pomnik", 7, 7, "representative"), e("port", 15, 17, "economy"), e("przystan", 9, 11, "economy"),
        e("ratusz", 15, 13, "civic"), e("ratusz_renesansowy", 15, 12, "civic"), e("rynek", 17, 17, "civic"),
        e("skarbiec", 9, 9, "representative"), e("spichlerz", 10, 11, "food"), e("stadion", 31, 23, "civic"),
        e("stajnia", 13, 9, "economy"), e("studnia", 5, 5, "infrastructure"), e("szkola", 15, 10, "civic"),
        e("szkola_nowoczesna", 19, 11, "civic"), e("szpital", 17, 11, "civic"), e("szpital_nowoczesny", 17, 13, "civic"),
        e("targ", 13, 13, "civic"), e("tartak", 11, 9, "economy"), e("uniwersytet", 19, 13, "civic"),
        e("wieza", 7, 7, "defense"), e("wiezowiec", 13, 13, "highrise"), e("wiezowiec_biurowy", 13, 13, "highrise"),
        e("zamek", 23, 24, "representative"), e("zielarnia", 9, 11, "civic"));

    private BuildingCatalog() {
    }

    private static Map.Entry<String, Entry> e(final String type, final int w, final int d, final String category) {
        return Map.entry(type, new Entry(w, d, category));
    }

    /** Entry of a type (case-insensitive), {@link #DEFAULT} when unknown. */
    public static Entry of(final String type) {
        if (type == null) {
            return DEFAULT;
        }
        return TYPES.getOrDefault(type.toLowerCase(Locale.ROOT), DEFAULT);
    }

    /** Whether the size of {@code type} comes from the table (false = default 9×9). */
    public static boolean known(final String type) {
        return type != null && TYPES.containsKey(type.toLowerCase(Locale.ROOT));
    }

    /**
     * Axis-aligned footprint {minX, minZ, maxX, maxZ} (inclusive blocks) of a building centred on ({@code x}, {@code z}):
     * width along X and depth along Z at rotation 0/180, swapped at 90/270.
     */
    public static int[] footprint(final String type, final int x, final int z, final int rotation) {
        final Entry s = of(type);
        final boolean swap = rotation == 90 || rotation == 270;
        final int w = swap ? s.depth() : s.width();
        final int d = swap ? s.width() : s.depth();
        final int minX = x - w / 2;
        final int minZ = z - d / 2;
        return new int[] {minX, minZ, minX + w - 1, minZ + d - 1};
    }
}
