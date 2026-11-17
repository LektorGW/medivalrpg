// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.economy;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Polish names of goods, their kind (for the page colours), the building material groups of Ludnosc construction sites
 * ({@code drewno}, {@code kamien} — and later ones such as glass, bricks or wool) with the workplaces that make them, and the
 * advice shown when something is missing. Unknown materials and groups get a readable fallback. Pure.
 */
public final class Goods {

    private Goods() {
    }

    /** A building material group: name, the workplace functions (Ludnosc display names) that produce it, what to build. */
    public record Group(String key, String label, String genitive, List<String> producers, String build, List<String> prefixes) {
    }

    /** Known groups. Producer names are matched against {@code workplaces[].function} (lower case, contains). */
    public static final List<Group> GROUPS = List.of(
        new Group("drewno", "drewno", "drewna", List.of("tartak", "drwal", "las"), "tartak",
            List.of("_log", "_wood", "_stem", "_hyphae", "_planks")),
        new Group("kamien", "kamień", "kamienia", List.of("kamieniołom", "kopalnia"), "kamieniołom",
            List.of("cobblestone", "cobbled_deepslate", "minecraft:stone", "andesite", "diorite", "granite", "deepslate", "tuff",
                "stone_bricks", "smooth_stone")),
        new Group("szklo", "szkło", "szkła", List.of("huta szkła", "huta"), "hutę szkła", List.of("glass")),
        new Group("cegla", "cegła", "cegieł", List.of("cegielnia"), "cegielnię", List.of("brick")),
        new Group("welna", "wełna", "wełny", List.of("owczarnia", "owczarz"), "owczarnię", List.of("_wool", "carpet")),
        new Group("glina", "glina", "gliny", List.of("glinianka", "cegielnia"), "gliniankę", List.of("clay")),
        new Group("piasek", "piasek", "piasku", List.of("piaskownia", "kamieniołom"), "piaskownię", List.of("sand")),
        new Group("zelazo", "żelazo", "żelaza", List.of("kopalnia", "huta"), "kopalnię", List.of("iron")),
        new Group("jedzenie", "jedzenie", "jedzenia", List.of("pole", "przystań", "piekarnia", "owczarnia"), "pole albo przystań", List.of()));

    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("wood", "drewno"), Map.entry("drewno", "drewno"),
        Map.entry("stone", "kamien"), Map.entry("kamien", "kamien"), Map.entry("kamień", "kamien"),
        Map.entry("glass", "szklo"), Map.entry("szklo", "szklo"), Map.entry("szkło", "szklo"),
        Map.entry("brick", "cegla"), Map.entry("bricks", "cegla"), Map.entry("cegla", "cegla"), Map.entry("cegła", "cegla"),
        Map.entry("wool", "welna"), Map.entry("welna", "welna"), Map.entry("wełna", "welna"),
        Map.entry("clay", "glina"), Map.entry("glina", "glina"), Map.entry("sand", "piasek"), Map.entry("piasek", "piasek"),
        Map.entry("iron", "zelazo"), Map.entry("zelazo", "zelazo"), Map.entry("żelazo", "zelazo"),
        Map.entry("food", "jedzenie"), Map.entry("jedzenie", "jedzenie"));

    /** Group by key or alias (case-insensitive), null when unknown. */
    public static Group group(final String key) {
        if (key == null) {
            return null;
        }
        final String k = ALIASES.getOrDefault(key.toLowerCase(Locale.ROOT).trim(), key.toLowerCase(Locale.ROOT).trim());
        for (final Group g : GROUPS) {
            if (g.key().equals(k)) {
                return g;
            }
        }
        return null;
    }

    /** Display name of a group key ({@code kamien} → „kamień”); unknown keys are shown as they are. */
    public static String groupLabel(final String key) {
        final Group g = group(key);
        return g != null ? g.label() : key == null ? "?" : key.replace('_', ' ');
    }

    /**
     * Construction units one item of {@code material} supplies to {@code group} — as Ludnosc counts them: a log is 4 units
     * (4 planks), a plank / cobblestone / stone is 1; other groups count 1 per matching item. 0 = does not supply.
     */
    public static long units(final String group, final String material) {
        final Group g = group(group);
        if (g == null || material == null) {
            return 0;
        }
        final String m = material.toLowerCase(Locale.ROOT);
        if (g.key().equals("drewno")) {
            if (m.endsWith("_log") || m.endsWith("_wood") || m.endsWith("_stem") || m.endsWith("_hyphae")) {
                return 4;
            }
            return m.endsWith("_planks") ? 1 : 0;
        }
        if (g.key().equals("kamien")) {
            final String path = m.startsWith("minecraft:") ? m.substring(10) : m;
            if (path.endsWith("_slab") || path.endsWith("_stairs") || path.endsWith("_wall") || path.endsWith("_button")
                || path.endsWith("_pressure_plate") || path.contains("ore")) {
                return 0;
            }
            return switch (path) {
                case "cobblestone", "cobbled_deepslate", "stone", "andesite", "diorite", "granite", "deepslate", "tuff",
                     "stone_bricks", "smooth_stone", "mossy_cobblestone", "polished_andesite", "polished_diorite", "polished_granite",
                     "polished_deepslate", "deepslate_bricks", "deepslate_tiles", "polished_tuff", "tuff_bricks" -> 1;
                default -> 0;
            };
        }
        if (g.key().equals("jedzenie")) {
            return kind(material).equals("food") ? 1 : 0;
        }
        for (final String p : g.prefixes()) {
            if (m.contains(p)) {
                return 1;
            }
        }
        return 0;
    }

    /** Whether a workplace function (Ludnosc display name, e.g. „kamieniołom”) produces the group. */
    public static boolean produces(final Group g, final String function) {
        if (g == null || function == null) {
            return false;
        }
        final String f = function.toLowerCase(Locale.ROOT);
        return g.producers().stream().anyMatch(f::contains);
    }

    // ------------------------------------------------------------------ goods

    private static final Map<String, String> NAMES = Map.ofEntries(
        Map.entry("oak_log", "Kłoda dębowa"), Map.entry("spruce_log", "Kłoda świerkowa"), Map.entry("birch_log", "Kłoda brzozowa"),
        Map.entry("jungle_log", "Kłoda tropikalna"), Map.entry("acacia_log", "Kłoda akacjowa"), Map.entry("dark_oak_log", "Kłoda ciemnego dębu"),
        Map.entry("mangrove_log", "Kłoda namorzynowa"), Map.entry("cherry_log", "Kłoda wiśniowa"), Map.entry("pale_oak_log", "Kłoda bladego dębu"),
        Map.entry("oak_planks", "Deski dębowe"), Map.entry("spruce_planks", "Deski świerkowe"), Map.entry("birch_planks", "Deski brzozowe"),
        Map.entry("stick", "Patyk"), Map.entry("oak_sapling", "Sadzonka dębu"), Map.entry("spruce_sapling", "Sadzonka świerku"),
        Map.entry("birch_sapling", "Sadzonka brzozy"), Map.entry("apple", "Jabłko"), Map.entry("oak_leaves", "Liście dębu"),
        Map.entry("cobblestone", "Bruk"), Map.entry("cobbled_deepslate", "Bruk łupkowy"), Map.entry("stone", "Kamień"),
        Map.entry("andesite", "Andezyt"), Map.entry("diorite", "Dioryt"), Map.entry("granite", "Granit"), Map.entry("tuff", "Tuf"),
        Map.entry("deepslate", "Łupek głębinowy"), Map.entry("gravel", "Żwir"), Map.entry("flint", "Krzemień"), Map.entry("dirt", "Ziemia"),
        Map.entry("sand", "Piasek"), Map.entry("clay_ball", "Glina"), Map.entry("clay", "Blok gliny"), Map.entry("brick", "Cegła"),
        Map.entry("bricks", "Cegły (blok)"), Map.entry("glass", "Szkło"), Map.entry("glass_pane", "Szyba"), Map.entry("white_wool", "Biała wełna"),
        Map.entry("string", "Nić"), Map.entry("mutton", "Surowa baranina"), Map.entry("leather", "Skóra"),
        Map.entry("raw_iron", "Surowe żelazo"), Map.entry("raw_copper", "Surowa miedź"), Map.entry("raw_gold", "Surowe złoto"),
        Map.entry("iron_ingot", "Sztabka żelaza"), Map.entry("coal", "Węgiel"), Map.entry("charcoal", "Węgiel drzewny"),
        Map.entry("diamond", "Diament"), Map.entry("emerald", "Szmaragd"), Map.entry("lapis_lazuli", "Lazuryt"), Map.entry("redstone", "Czerwony pył"),
        Map.entry("torch", "Pochodnia"), Map.entry("soul_torch", "Pochodnia dusz"),
        Map.entry("wheat", "Pszenica"), Map.entry("wheat_seeds", "Nasiona pszenicy"), Map.entry("carrot", "Marchew"), Map.entry("potato", "Ziemniak"),
        Map.entry("beetroot", "Burak"), Map.entry("beetroot_seeds", "Nasiona buraka"), Map.entry("bread", "Chleb"), Map.entry("cod", "Dorsz"),
        Map.entry("salmon", "Łosoś"), Map.entry("cooked_cod", "Pieczony dorsz"), Map.entry("cooked_salmon", "Pieczony łosoś"),
        Map.entry("baked_potato", "Pieczony ziemniak"), Map.entry("poisonous_potato", "Trujący ziemniak"), Map.entry("melon_slice", "Plaster arbuza"),
        Map.entry("pumpkin", "Dynia"), Map.entry("sugar_cane", "Trzcina cukrowa"), Map.entry("egg", "Jajko"), Map.entry("hay_block", "Snopek siana"),
        Map.entry("cooked_beef", "Pieczona wołowina"), Map.entry("cooked_mutton", "Pieczona baranina"), Map.entry("cooked_porkchop", "Pieczony schab"));

    /** Polish name of an item key ({@code minecraft:oak_log} or {@code OAK_LOG}); fallback: the key as plain words. */
    public static String label(final String key) {
        if (key == null || key.isBlank()) {
            return "?";
        }
        final String path = path(key);
        final String n = NAMES.get(path);
        if (n != null) {
            return n;
        }
        if (path.endsWith("_wool")) {
            return "Wełna (" + path.substring(0, path.length() - 5).replace('_', ' ') + ")";
        }
        if (path.endsWith("_log")) {
            return "Kłoda (" + path.substring(0, path.length() - 4).replace('_', ' ') + ")";
        }
        if (path.endsWith("_planks")) {
            return "Deski (" + path.substring(0, path.length() - 7).replace('_', ' ') + ")";
        }
        final String s = path.replace('_', ' ');
        return s.isEmpty() ? key : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** {@code minecraft:oak_log} / {@code OAK_LOG} → {@code oak_log}. */
    public static String path(final String key) {
        if (key == null) {
            return "";
        }
        final String k = key.toLowerCase(Locale.ROOT).trim();
        final int c = k.indexOf(':');
        return c >= 0 ? k.substring(c + 1) : k;
    }

    /** Kind for colours and grouping: wood, stone, ore, food, fish, tools, textile, ceramics, other. */
    public static String kind(final String key) {
        final String p = path(key);
        if (p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_planks") || p.endsWith("_stem") || p.endsWith("_hyphae")
            || p.endsWith("_sapling") || p.equals("stick") || p.endsWith("_leaves")) {
            return "wood";
        }
        if (p.equals("cod") || p.equals("salmon") || p.startsWith("cooked_cod") || p.startsWith("cooked_salmon") || p.contains("fish")) {
            return "fish";
        }
        if (p.equals("wheat") || p.equals("carrot") || p.equals("potato") || p.equals("beetroot") || p.equals("bread") || p.equals("apple")
            || p.startsWith("cooked_") || p.startsWith("baked_") || p.equals("melon_slice") || p.equals("pumpkin") || p.equals("egg")
            || p.equals("mutton") || p.equals("beef") || p.equals("porkchop") || p.equals("chicken") || p.equals("hay_block")) {
            return "food";
        }
        if (p.startsWith("raw_") || p.equals("coal") || p.equals("charcoal") || p.equals("diamond") || p.equals("emerald") || p.equals("redstone")
            || p.equals("lapis_lazuli") || p.endsWith("_ingot") || p.endsWith("_nugget")) {
            return "ore";
        }
        if (p.endsWith("_wool") || p.endsWith("_carpet") || p.equals("string") || p.equals("leather")) {
            return "textile";
        }
        if (p.contains("glass") || p.contains("brick") || p.startsWith("clay") || p.contains("terracotta")) {
            return "ceramics";
        }
        if (p.equals("torch") || p.equals("soul_torch")) {
            return "tools";
        }
        if (units("kamien", p) > 0 || p.equals("gravel") || p.equals("sand") || p.equals("dirt") || p.equals("flint")) {
            return "stone";
        }
        return "other";
    }
}
