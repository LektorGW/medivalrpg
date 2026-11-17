// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.world;

import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import pl.medivalrpg.kraje.api.KrajeApi;

/** Conservative policy: only explicit natural materials may be replaced; every tile entity is protected. */
public final class WorksProtection {
    private static final Set<String> NATURAL = Set.of("STONE", "DIRT", "GRASS_BLOCK", "COARSE_DIRT", "ROOTED_DIRT",
        "PODZOL", "MYCELIUM", "SAND", "RED_SAND", "GRAVEL", "DEEPSLATE", "TUFF", "GRANITE", "DIORITE", "ANDESITE",
        "CLAY", "MUD", "SNOW", "SNOW_BLOCK", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "WATER", "SEAGRASS", "TALL_SEAGRASS");
    private static final Set<String> SOFT = Set.of("WATER", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "SNOW", "SEAGRASS", "TALL_SEAGRASS");
    private final KrajeApi countries;
    private java.util.function.BiPredicate<org.bukkit.Location, UUID> validator;
    public WorksProtection(KrajeApi countries) { this.countries = countries; }
    public void setClaimValidator(java.util.function.BiPredicate<org.bukkit.Location, UUID> validator) {
        this.validator = java.util.Objects.requireNonNull(validator);
    }
    public String territory(World world, int x, int y, int z, UUID country) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return "Poza wysokością świata";
        if (!world.getWorldBorder().isInside(new org.bukkit.Location(world, x + .5, y, z + .5))) return "Poza granicą świata";
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return "Chunk niezaładowany";
        try {
            if (validator != null) {
                if (!validator.test(new org.bukkit.Location(world, x, y, z), country)) return "Brak zgody na budowę";
            } else {
                if (countries == null) return "Brak walidatora terenu";
                var owner = countries.getCountryAt(world.getName(), x >> 4, z >> 4);
                if (owner.isPresent() && !owner.get().id().equals(country)) return "Teren innego kraju";
            }
        } catch (RuntimeException ex) { return "Nie można sprawdzić zgody na budowę"; }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                if (WorldGuardProtection.protectedAt(world, x, y, z)) return "Region WorldGuard";
            } catch (LinkageError | RuntimeException ex) { return "Nie można sprawdzić WorldGuard"; }
        }
        return null;
    }
    public static boolean natural(Block block) {
        return !(block.getState() instanceof TileState) && (block.getType().isAir() || NATURAL.contains(block.getType().name()) || plant(block) || block.getType().name().endsWith("_ORE"));
    }
    /** Underwater kelp keeps growing after a survey; like sugar cane it is a plant, never a structure. */
    private static boolean plant(Block block) {
        String name=block.getType().name();
        return Tag.FLOWERS.isTagged(block.getType()) || Set.of("GLOW_LICHEN","LEAF_LITTER","BUSH","FIREFLY_BUSH","DEAD_BUSH","SUGAR_CANE","VINE","BROWN_MUSHROOM","RED_MUSHROOM","KELP","KELP_PLANT").contains(name);
    }
    public static boolean soft(Block block) { return block.getType().isAir() || SOFT.contains(block.getType().name()) || plant(block); }
}
