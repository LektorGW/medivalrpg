// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Eras and progress of every country (player and NPC), provided by the Postep plugin (ServicesManager).
 * Each country advances on its own (Civilization-like): research points come from population, buildings,
 * treasury investment and time; an era also needs a minimum time spent in the previous one.
 *
 * <p>Eras (ids 1..4): 1 = Średniowiecze, 2 = Renesans, 3 = Epoka przemysłowa, 4 = Nowoczesność (2026).
 * Consumers (Ludnosc: buildings/palettes, Armia: units, Kraje/map: titles) read the era and react to
 * {@link pl.medivalrpg.postep.api.event.EraAdvanceEvent}. Read methods are thread-safe.</p>
 */
public interface PostepApi {

    int MEDIEVAL = 1;
    int RENAISSANCE = 2;
    int INDUSTRIAL = 3;
    int MODERN = 4;

    /** Current era of a country (1..4); 1 for unknown countries. */
    int era(UUID countryId);

    /** Static description of an era. */
    EraInfo eraInfo(int era);

    /** All eras in order. */
    List<EraInfo> eras();

    /** Progress toward the next era, 0..1 (1 = ready / already max era). */
    double progress(UUID countryId);

    /** Accumulated research points of a country. */
    long researchPoints(UUID countryId);

    /** Ids of discoveries (techs) the country has unlocked, e.g. {@code "druk"}, {@code "maszyna_parowa"}. */
    Set<String> discoveries(UUID countryId);

    default boolean hasDiscovery(final UUID countryId, final String discoveryId) {
        return this.discoveries(countryId).contains(discoveryId);
    }

    /** Human readable Polish list of what is still missing for the next era; empty when ready or at max era. */
    List<String> missingForNextEra(UUID countryId);

    /** Epoch millis when the country entered its current era. */
    Optional<Long> eraSince(UUID countryId);

    // ---- extensions (since Postep 1.0; default = unsupported / neutral) -----------------------------
    //
    // Reads are thread-safe snapshots (cached by Postep, refreshed on the main thread). Mutations must be called on the
    // main thread and do not check who calls them (the caller - a command, Armia's AI or the web panel - does).

    /** Short era icon (one unicode symbol, e.g. {@code "⚜"} for the Renaissance); empty when unknown. */
    default String eraIcon(final int era) {
        return "";
    }

    /**
     * Ready-to-use MiniMessage badge of the country's era, e.g. {@code "<#C9A227>⚜ Renesans</#C9A227>"} (for the tab
     * list, {@code /kraj info}, chat). Empty when unsupported.
     */
    default String eraBadge(final UUID countryId) {
        return "";
    }

    /** Expected research points per day (population, buildings, diplomacy, discoveries; without investments). */
    default double dailyResearch(final UUID countryId) {
        return 0.0;
    }

    /** Research points earned in total (including spent ones). */
    default long totalResearchPoints(final UUID countryId) {
        return this.researchPoints(countryId);
    }

    /** Discovery the country is currently researching (unlocked automatically once the points suffice). */
    default Optional<String> researchTarget(final UUID countryId) {
        return Optional.empty();
    }

    /** The whole discovery tree in config order (all eras). */
    default List<DiscoveryInfo> discoveryTree() {
        return List.of();
    }

    default Optional<DiscoveryInfo> discovery(final String discoveryId) {
        return this.discoveryTree().stream().filter(d -> d.id().equals(discoveryId)).findFirst();
    }

    /**
     * Sum of a numeric effect over the country's discoveries, in percent (e.g. {@code effect(id, "podatki") = 15.0}
     * means +15 % taxes). Known keys: {@code podatki, jedzenie, sila-armii, tempo-budowy, badania, ludnosc}; other
     * plugins may read any key defined in Postep's {@code drzewko.yml}. 0 when none.
     */
    default double effect(final UUID countryId, final String effectKey) {
        return 0.0;
    }

    /** Research points that {@link #invest} would give now for {@code amount} copper (diminishing returns per day). */
    default double investPreview(final UUID countryId, final long amount) {
        return 0.0;
    }

    /**
     * Main thread. Converts {@code amount} copper from the country treasury into research points (diminishing returns:
     * the more invested since the last daily tick, the less each coin gives). {@code byPlayer} null = AI / API.
     */
    default InvestResult invest(final UUID countryId, final long amount, final UUID byPlayer) {
        return new InvestResult(false, 0L, 0.0, "nieobsługiwane");
    }

    /**
     * Share of the daily income an NPC country invests automatically, in percent; -1 = automatic (Postep derives it
     * from the personality: bogactwo, dyplomacja, war). Armia may set a fixed share or 0 to invest itself via
     * {@link #invest}.
     */
    default int npcInvestShare(final UUID countryId) {
        return -1;
    }

    /** Main thread. {@code percent} 0..100, or -1 = automatic. False for unknown countries / unsupported. */
    default boolean setNpcInvestShare(final UUID countryId, final int percent) {
        return false;
    }

    /** Copper invested since the last daily tick (diminishing returns are counted from it). */
    default long investedToday(final UUID countryId) {
        return 0L;
    }

    /**
     * Main thread. Same as {@code /postep odkryj <id>}: unlocks the discovery at once when every requirement is owned
     * and the points suffice, otherwise makes it the research target (missing requirements are researched first and
     * everything unlocks automatically once the points suffice). Does not check who calls it ({@code byPlayer} is only
     * informational, null = AI / API).
     */
    default DiscoverResult discover(final UUID countryId, final String discoveryId, final UUID byPlayer) {
        return new DiscoverResult(false, DiscoverResult.UNSUPPORTED, discoveryId, 0.0, "nieobsługiwane");
    }

    /** Main thread, admin: sets the era (1..4); required discoveries of earlier eras are granted. False when invalid. */
    default boolean setEra(final UUID countryId, final int era) {
        return false;
    }

    /** Main thread, admin: changes the spendable research points by {@code delta} (never below 0). */
    default boolean addResearchPoints(final UUID countryId, final double delta) {
        return false;
    }

    /** Main thread, admin: grants a discovery and its missing prerequisites for free; returns the granted ids. */
    default List<String> grantDiscovery(final UUID countryId, final String discoveryId) {
        return List.of();
    }

    /**
     * Result of {@link #discover}.
     *
     * @param ok            true for {@link #UNLOCKED} and {@link #TARGET_SET}
     * @param status        one of the constants below
     * @param discovery     the discovery id
     * @param missingPoints points still missing for the target (and its path), 0 when unlocked
     * @param message       Polish description (always set)
     */
    record DiscoverResult(boolean ok, String status, String discovery, double missingPoints, String message) {

        public static final String UNLOCKED = "UNLOCKED";
        public static final String TARGET_SET = "TARGET_SET";
        public static final String ALREADY_OWNED = "ALREADY_OWNED";
        public static final String LATER_ERA = "LATER_ERA";
        public static final String UNKNOWN = "UNKNOWN";
        public static final String NO_COUNTRY = "NO_COUNTRY";
        public static final String UNSUPPORTED = "UNSUPPORTED";
    }

    /**
     * A discovery (tech) of the tree.
     *
     * @param id             id, e.g. {@code "druk"}
     * @param name           Polish name
     * @param era            era it belongs to (1..4)
     * @param cost           research points
     * @param requires       ids of required discoveries (same or earlier era)
     * @param requiredForEra true when needed to enter the next era
     * @param category       {@code gospodarka, nauka, wojsko, budownictwo, dyplomacja}
     * @param description    Polish description
     * @param effectText     Polish description of the effect (also for other plugins)
     * @param effects        numeric effects in percent, see {@link #effect}
     */
    record DiscoveryInfo(String id, String name, int era, long cost, List<String> requires, boolean requiredForEra,
                         String category, String description, String effectText, java.util.Map<String, Double> effects) {
    }

    /**
     * Result of {@link #invest}.
     *
     * @param ok      true when the money was taken
     * @param paid    copper taken from the treasury
     * @param points  research points gained
     * @param message Polish explanation (empty on success)
     */
    record InvestResult(boolean ok, long paid, double points, String message) {
    }

    /**
     * Static era description.
     *
     * @param id          1..4
     * @param name        Polish name, e.g. "Renesans"
     * @param yearsLabel  e.g. "XV–XVII w.", "2026"
     * @param color       hex colour used in UI, e.g. "#C9A227"
     * @param description short Polish description
     */
    record EraInfo(int id, String name, String yearsLabel, String color, String description) {
    }
}
