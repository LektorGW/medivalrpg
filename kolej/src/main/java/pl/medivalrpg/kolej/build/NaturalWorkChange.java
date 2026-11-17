// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.Set;

/** Physics caused by removing a plant's support or covering grass during an accepted project. */
final class NaturalWorkChange {
    private static final Set<String> FRAGILE = Set.of(
        "short_grass", "tall_grass", "fern", "large_fern", "sunflower", "lilac", "rose_bush", "peony", "pitcher_plant",
        "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip",
        "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "torchflower", "pink_petals",
        "wildflowers", "glow_lichen", "leaf_litter", "bush", "firefly_bush", "dead_bush", "sugar_cane", "vine",
        "brown_mushroom", "red_mushroom", "seagrass", "tall_seagrass", "snow", "sand", "red_sand", "gravel");
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    private static final Set<String> KELP = Set.of("minecraft:kelp", "minecraft:kelp_plant");
    private NaturalWorkChange() { }
    static boolean accepts(String before, String current) {
        if (before == null || current == null) return false;
        String type = before.split("\\[", 2)[0], now = current.split("\\[", 2)[0];
        return (type.startsWith("minecraft:") && FRAGILE.contains(type.substring(10)) && AIR.contains(current))
            || (Set.of("minecraft:grass_block", "minecraft:dirt").contains(type)
                && Set.of("minecraft:grass_block", "minecraft:dirt").contains(now))
            || (type.equals("minecraft:water") && (now.equals("minecraft:water") || AIR.contains(current) || KELP.contains(now)))
            // Kelp keeps growing underwater (top age, new top above): same plant family, or water after it broke.
            || (KELP.contains(type) && (KELP.contains(now) || now.equals("minecraft:water") || AIR.contains(current)));
    }
    static boolean matches(String before, String target, String current) {
        return current != null && (current.equals(before) || current.equals(target) || accepts(before,current) || NaturalDrift.same(before,current));
    }
}
