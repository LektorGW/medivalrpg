// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * NPC population of countries, provided by the Ludnosc plugin (ServicesManager). Citizens are real villager
 * entities tagged with their country; they pay taxes into the country treasury and can be conscripted.
 *
 * <p>Read methods are thread-safe snapshots. Methods marked "main thread" touch entities or the world.</p>
 */
public interface LudnoscApi {

    /** Number of living NPC citizens of a country (loaded or not). */
    int population(UUID countryId);

    /** Housing capacity (beds in finished/recognised houses on the country's land). */
    int housing(UUID countryId);

    /** Entity UUIDs of the country's citizens. */
    Collection<UUID> citizens(UUID countryId);

    /** Country of a citizen entity. */
    Optional<UUID> countryOf(Entity entity);

    /** Expected daily tax income from NPC citizens, in copper. */
    long dailyTaxIncome(UUID countryId);

    /**
     * Main thread. Removes one adult citizen of the country — the closest loaded one to {@code near}
     * (any loaded one if {@code near} is null) — so it can become a soldier. Empty if nobody can be conscripted.
     */
    Optional<Conscript> conscript(UUID countryId, Location near);

    /**
     * Main thread. Returns a former citizen (disbanded or deserted soldier) to the country's population:
     * spawns an adult citizen villager at {@code location} (or a safe spot on the country's land nearby).
     *
     * @param profession villager profession key, e.g. from {@link Conscript#profession()}
     * @return false if the country is unknown or the citizen could not be placed
     */
    default boolean returnCitizen(final UUID countryId, final Location location, final String profession) {
        return false;
    }

    /** True when the country has at least one FINISHED building of the given type (e.g. {@code "koszary"}). */
    default boolean hasBuilding(final UUID countryId, final String buildingType) {
        return false;
    }

    /** Ids of building schematics available to {@link #orderBuilding}. */
    Collection<String> buildingTypes();

    /**
     * Main thread. Orders builders to construct {@code buildingType} with its origin at {@code origin}
     * (rotation in degrees: 0/90/180/270), paid from the country treasury. The area must lie on the country's land.
     */
    BuildOrderResult orderBuilding(UUID countryId, String buildingType, Location origin, int rotation);

    /**
     * Main thread. Lets the settlement planner decide WHAT to build next and WHERE (needs, wealth tier, town layout,
     * terrain), spending at most {@code budget} copper from the treasury. Used by the NPC-country AI instead of
     * choosing buildings and spots itself. Returns {@link BuildOrderResult#OK} when an order was placed or queued,
     * {@link BuildOrderResult#INSUFFICIENT_FUNDS} when nothing useful fits the budget,
     * {@link BuildOrderResult#AREA_OBSTRUCTED} when no suitable spot was found, {@link BuildOrderResult#TOO_MANY_ORDERS}
     * when the queue is full. Default (older Ludnosc): {@link BuildOrderResult#UNKNOWN_BUILDING}.
     */
    default BuildOrderResult developSettlement(final UUID countryId, final long budget) {
        return BuildOrderResult.UNKNOWN_BUILDING;
    }

    /**
     * Population, housing, granary, buildings and construction sites of a country (for the web panel).
     * Empty when unsupported. Main thread recommended (consistent snapshot).
     */
    default Optional<LudnoscInfo> info(final UUID countryId) {
        return Optional.empty();
    }

    /** Main thread. Settlement centres and simplified, bounded built/planned road geometry for squaremap. */
    default Optional<KingdomMapInfo> kingdomMap(final UUID countryId) {
        return Optional.empty();
    }

    // ---- web panel v2 (NPC-country management); main thread for mutations ------------------------------

    /** Adds (or with a negative delta removes) granary food, clamped to 0..capacity. False if unsupported/unknown. */
    default boolean adjustFood(final UUID countryId, final long delta) {
        return false;
    }

    /** Adds or removes settlers (adults); returns how many were actually added (negative = removed). */
    default int adjustSettlers(final UUID countryId, final int delta) {
        return 0;
    }

    /** Build queue of a country, in order (index 0 starts next). */
    default java.util.List<QueuedOrderInfo> queue(final UUID countryId) {
        return java.util.List.of();
    }

    /** Moves a queue entry by {@code delta} positions (-1 = up, +1 = down). */
    default boolean moveQueue(final UUID countryId, final int index, final int delta) {
        return false;
    }

    /** Cancels a queued (not yet started) order with a full refund. */
    default boolean cancelQueued(final UUID countryId, final int index) {
        return false;
    }

    /** Every building type with cost and whether the country may build it now (and what is missing). */
    default java.util.List<BuildingRequirement> buildingRequirements(final UUID countryId) {
        return java.util.List.of();
    }

    /** Orders a SPECIFIC building type; the settlement planner chooses the spot. */
    default BuildOrderResult orderBuildingAuto(final UUID countryId, final String buildingType) {
        return BuildOrderResult.UNKNOWN_BUILDING;
    }

    /** Settlement tier (1 = Osada … 5 = Stolica) and its Polish name; empty if unsupported. */
    default Optional<SettlementTier> settlementTier(final UUID countryId) {
        return Optional.empty();
    }

    // ---- design plan drawn in the web panel (format: PANEL.md "Edytor planu państwa NPC") -------------

    /**
     * Main thread. Stores the design plan (JSON, version 1) that the settlement planner must follow for this country.
     * Returns validation warnings (empty list = OK), or {@code null} when unsupported (older Ludnosc).
     */
    default java.util.List<String> setDesignPlan(final UUID countryId, final String planJson) {
        return null;
    }

    /** The stored design plan JSON, if any. */
    default Optional<String> designPlan(final UUID countryId) {
        return Optional.empty();
    }

    /** Main thread. Removes the design plan (the automatic planner takes over again). */
    default boolean clearDesignPlan(final UUID countryId) {
        return false;
    }

    /** JSON summary of what the planner will do with the current plan (orders, demolitions, cost); empty if unsupported. */
    default Optional<String> designPlanPreview(final UUID countryId) {
        return Optional.empty();
    }

    /** Main thread. JSON preview of a bounded rectangle; null means unsupported. targetY null chooses the median. */
    default String previewTerrainLevel(final UUID countryId, final String world, final int minX, final int minZ,
                                       final int maxX, final int maxZ, final Integer targetY) {
        return null;
    }

    /** Main thread. Revalidates terrain/ownership and starts paid gradual earthworks. free is an admin-only decision. */
    default BuildOrderResult orderTerrainLevel(final UUID countryId, final String world, final int minX, final int minZ,
                                               final int maxX, final int maxZ, final int targetY, final boolean free) {
        return BuildOrderResult.UNKNOWN_BUILDING;
    }

    /**
     * Main thread. Persistently requests inspection/reconstruction of template buildings touched by an extinguished
     * fire. Returns number of buildings accepted (0 = none), -1 if unsupported/invalid. Inspections are budgeted;
     * repair costs and material requirements apply, insufficient funds are retried. Does not reconstruct arbitrary
     * player buildings for which no template exists.
     */
    default int requestFireRecovery(final UUID countryId, final String world, final int minX, final int minZ,
                                    final int maxX, final int maxZ) {
        return -1;
    }

    // ---- villages → NPC states ----------------------------------------------------------------------

    /**
     * Main thread (loads/scans chunks around {@code near}, may take a few ticks — the future completes on the main
     * thread). Finds a vanilla village near {@code near} within {@code radius} blocks: bell (meeting point) as the
     * centre, beds, job sites, fields and villagers. Empty when none found or unsupported.
     */
    default java.util.concurrent.CompletableFuture<Optional<VillageInfo>> findVillage(final Location near, final int radius) {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Main thread. Makes an existing village part of {@code countryId} (which must already own the village chunks):
     * registers its houses with beds as housing ("dom wiejski"), fields as food sources, adopts every villager
     * (keeping professions) and seeds the town plan from the existing layout (paths as roads).
     * Returns how many villagers were adopted (negative = failure).
     */
    default int adoptVillage(final UUID countryId, final VillageInfo village) {
        return -1;
    }

    /**
     * Admin / panel: computes the town redesign (plan v2) of any country (also NPC) and orders all of it at once
     * (demolitions at the front of the queue, streets in sections; what does not fit the queue follows gradually).
     * {@code free}: the country's treasury does not pay. Returns the number of orders placed (negative = failure).
     * Main thread.
     */
    default int forceRedesign(final UUID countryId, final boolean free) {
        return -1;
    }

    /** Admin / panel: rebuilds the country's street network in the style of its epoch now. Main thread. */
    default boolean forceRoads(final UUID countryId) {
        return false;
    }

    /** Admin / panel: modernises every outdated building of the country now. Returns the orders placed. Main thread. */
    default int forceModernize(final UUID countryId) {
        return 0;
    }

    /**
     * Raw materials in the country's store: {@code drewno} (lumberjacks), {@code kamien} (quarries), {@code rudy}
     * (mines), {@code ryby} (harbours) → amount. Empty when none / unsupported. Thread-safe read.
     */
    default java.util.Map<String, Long> resources(final UUID countryId) {
        return java.util.Map.of();
    }

    /**
     * Why the last planning or build order of a country found no place or was refused (Polish, e.g. "za mało ziemi
     * kraju: budynek 13×9 potrzebuje ok. 2 wolnych chunków", "teren za stromy", "kolizja z innym budynkiem"), or a
     * warning of a forced order. Empty when the last attempt succeeded or nothing is known. Thread-safe read.
     */
    default Optional<String> lastPlanFailure(final UUID countryId) {
        return Optional.empty();
    }

    /**
     * The current server-wide season (wiosna / lato / jesien / zima). Empty when seasons are disabled or the provider
     * does not support them. Thread-safe read.
     */
    default Optional<SeasonInfo> season() {
        return Optional.empty();
    }

    /** True while the country suffers a poor harvest ("nieurodzaj": lower food production). Thread-safe read. */
    default boolean poorHarvest(final UUID countryId) {
        return false;
    }

    /**
     * Current multiplier of the country's food production from seasons and poor harvests (1.0 = normal, e.g. 0.25 in
     * winter, 1.4 in autumn). Thread-safe read.
     */
    default double foodProductionMultiplier(final UUID countryId) {
        return 1.0;
    }

    /**
     * A detected village.
     *
     * @param world      world name
     * @param bellX      centre (bell or meeting point) x
     * @param bellY      centre y
     * @param bellZ      centre z
     * @param chunkKeys  chunks covered by the village buildings/fields, key = {@code ((long) cx << 32) | (cz & 0xFFFFFFFFL)}
     * @param beds       number of beds found
     * @param villagers  number of villagers found
     * @param jobSites   number of job-site blocks found
     */
    record VillageInfo(String world, int bellX, int bellY, int bellZ, java.util.Set<Long> chunkKeys,
                       int beds, int villagers, int jobSites) {
    }

    /**
     * A queued build order.
     *
     * @param index     position in the queue
     * @param type      building type id
     * @param world     world name
     * @param x         origin block x
     * @param y         origin block y
     * @param z         origin block z
     * @param rotation  0/90/180/270
     * @param cost      reserved cost in copper (refunded on cancel)
     * @param orderedBy player name, or "AI"/"admin"
     * @param orderedAt epoch millis
     */
    record QueuedOrderInfo(int index, String type, String world, int x, int y, int z, int rotation, long cost,
                           String orderedBy, long orderedAt) {
    }

    /**
     * Availability of a building type for a country.
     *
     * @param type        building type id
     * @param displayName Polish name (plain text)
     * @param cost        cost in copper
     * @param available   true when every requirement is met now
     * @param missing     human readable unmet requirements (Polish), e.g. "poziom: Miasto", "ludność ≥ 40"
     * @param built       how many the country already has (finished)
     * @param max         max per country, -1 = unlimited
     */
    record BuildingRequirement(String type, String displayName, long cost, boolean available,
                               java.util.List<String> missing, int built, int max) {
    }

    /** Settlement tier: {@code level} 1..5, {@code name} e.g. "Miasteczko". */
    record SettlementTier(int level, String name) {
    }

    /**
     * A conscripted citizen.
     *
     * @param countryId  country the citizen belonged to
     * @param profession villager profession key, e.g. {@code "minecraft:farmer"} ({@code "minecraft:none"} if none)
     * @param location   where the citizen was removed (spawn the soldier here)
     */
    record Conscript(UUID countryId, String profession, Location location) {
    }

    /** Outcome of {@link #orderBuilding}. */
    enum BuildOrderResult {
        OK,
        UNKNOWN_BUILDING,
        NOT_ON_OWN_LAND,
        AREA_OBSTRUCTED,
        INSUFFICIENT_FUNDS,
        TOO_MANY_ORDERS
    }
}
