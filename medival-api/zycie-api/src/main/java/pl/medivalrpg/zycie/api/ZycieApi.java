// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily life and mood of NPC citizens, provided by the Zycie plugin (ServicesManager). Read methods are thread-safe.
 * Ludnosc (taxes, growth, departures) and other plugins may read {@link #moodMultiplier(UUID)}; without Zycie it is 1.0.
 */
public interface ZycieApi {

    /** Mood of a country's citizens, 0..100 (50 = neutral). */
    int mood(UUID countryId);

    /** Contribution of each factor to the mood, e.g. {@code "jedzenie" → +12}, {@code "podatki" → -8}. */
    Map<String, Integer> moodFactors(UUID countryId);

    /** Current Polish concerns of the citizens (for UI), most important first. */
    List<String> concerns(UUID countryId);

    /** Multiplier for taxes/growth/work derived from mood: 0.6 (riot) .. 1.25 (very happy). */
    default double moodMultiplier(final UUID countryId) {
        return 1.0;
    }

    /** True while citizens of the country protest or riot. */
    default boolean unrest(final UUID countryId) {
        return false;
    }

    /** True while a festival is held in the country's capital. */
    default boolean festival(final UUID countryId) {
        return false;
    }
}
