// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;

/**
 * Armia's view of the optional Ludnosc plugin. {@link #NONE} when Ludnosc is missing, so no class of
 * {@code pl.medivalrpg.ludnosc.api} is ever touched without the plugin.
 */
public interface Population {

    /** A conscripted citizen: villager profession key and where it stood. */
    record Conscripted(String profession, Location location) {
    }

    boolean present();

    int population(UUID country);

    int housing(UUID country);

    /** Settlement level 1..6, zero when an older/missing provider cannot report it. */
    default int settlementLevel(final UUID country) {
        return 0;
    }

    long dailyTax(UUID country);

    /** Main thread. */
    Optional<Conscripted> conscript(UUID country, Location near);

    Collection<String> buildingTypes();

    /** Main thread. Result name of {@code LudnoscApi.BuildOrderResult} ("OK" on success). */
    String orderBuilding(UUID country, String type, Location origin, int rotation);

    /**
     * Main thread. Gives a dismissed soldier back to the population ({@code LudnoscApi.returnCitizen}). False when
     * Ludnosc refused, is missing or is an older version without the method — the caller then uses its fallback.
     */
    boolean returnCitizen(UUID country, Location location, String profession);

    /**
     * Does the country have a finished building of this type ({@code LudnoscApi.hasBuilding})? Empty when unknown
     * (Ludnosc missing or too old) — the caller then uses its own check.
     */
    Optional<Boolean> hasBuilding(UUID country, String type);

    /**
     * Main thread. Lets Ludnosc's settlement planner choose what and where to build within {@code budget}
     * ({@code LudnoscApi.developSettlement}). Result name of {@code BuildOrderResult}; {@code "UNKNOWN_BUILDING"}
     * when Ludnosc is missing or too old (no planner, also on {@link LinkageError}) — the caller then plans itself.
     */
    default String developSettlement(final UUID country, final long budget) {
        return "UNKNOWN_BUILDING";
    }

    /** A spot at (or in front of) a finished building of this type ({@code LudnoscApi.info}); empty when unknown. */
    default Optional<Location> buildingSpot(final UUID country, final String type) {
        return Optional.empty();
    }

    /** A building type and whether the country may build it now ({@code LudnoscApi.buildingRequirements}). */
    record Requirement(String type, boolean available, int built, long cost) {
    }

    /** A defensive building of a country (background sieges): kind {@code WALL}, {@code TOWER} or {@code CASTLE}. */
    record Fort(String world, int x, int z, String kind) {
    }

    /**
     * Walls, gates, towers, castles and palaces of a country ({@code LudnoscApi.info} — existing API, read only);
     * empty when Ludnosc is missing or too old. Main thread.
     */
    default List<Fort> fortifications(final UUID country) {
        return List.of();
    }

    /** A finished building as Ludnosc reports it: {@code function} is the Polish function text with the state appended. */
    record BuildingView(String type, String function, int beds, String world, int x, int y, int z) {
    }

    /**
     * Finished buildings of a country ({@code LudnoscApi.info} — existing API, a snapshot Ludnosc refreshes itself, no
     * world access); empty when Ludnosc is missing or too old.
     */
    default List<BuildingView> buildings(final UUID country) {
        return List.of();
    }

    /** Building types with availability; empty when Ludnosc is missing / too old. */
    default List<Requirement> requirements(final UUID country) {
        return List.of();
    }

    /**
     * Main thread. Orders a specific building type, the planner chooses the spot ({@code LudnoscApi.orderBuildingAuto}).
     * {@code "UNKNOWN_BUILDING"} when unsupported.
     */
    default String orderBuildingAuto(final UUID country, final String type) {
        return "UNKNOWN_BUILDING";
    }

    /** Main thread. Adds settlers ({@code LudnoscApi.adjustSettlers}); how many were added, 0 when unsupported. */
    default int adjustSettlers(final UUID country, final int delta) {
        return 0;
    }

    /** Main thread. Adds granary food ({@code LudnoscApi.adjustFood}); false when unsupported. */
    default boolean adjustFood(final UUID country, final long delta) {
        return false;
    }

    /**
     * A village found by Ludnosc ({@code LudnoscApi.findVillage}); {@code handle} is Ludnosc's own object, passed back to
     * {@link #adoptVillage}.
     */
    record Village(String world, int bellX, int bellY, int bellZ, java.util.Set<Long> chunkKeys, int beds, int villagers,
                   int jobSites, Object handle) {
    }

    /** Finds the village near a location (any thread completes it); empty when none or Ludnosc is too old. */
    default java.util.concurrent.CompletableFuture<Optional<Village>> findVillage(final Location near, final int radius) {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }

    /** Main thread. Adopts the village into the country; villagers adopted, negative = failure / unsupported. */
    default int adoptVillage(final UUID country, final Village village) {
        return -1;
    }

    Population NONE = new Population() {
        @Override
        public boolean present() {
            return false;
        }

        @Override
        public int population(final UUID country) {
            return 0;
        }

        @Override
        public int housing(final UUID country) {
            return 0;
        }

        @Override
        public long dailyTax(final UUID country) {
            return 0;
        }

        @Override
        public Optional<Conscripted> conscript(final UUID country, final Location near) {
            return Optional.empty();
        }

        @Override
        public Collection<String> buildingTypes() {
            return List.of();
        }

        @Override
        public String orderBuilding(final UUID country, final String type, final Location origin, final int rotation) {
            return "UNKNOWN_BUILDING";
        }

        @Override
        public boolean returnCitizen(final UUID country, final Location location, final String profession) {
            return false;
        }

        @Override
        public Optional<Boolean> hasBuilding(final UUID country, final String type) {
            return Optional.empty();
        }
    };
}
