// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import pl.medivalrpg.zycie.api.ZycieApi;

/** {@link ZycieApi} over the thread-safe {@link MoodSnapshot}s (unknown countries: neutral 50 / ×1.0). */
public final class ZycieApiImpl implements ZycieApi {

    private final MoodService mood;

    public ZycieApiImpl(final MoodService mood) {
        this.mood = mood;
    }

    private MoodSnapshot snap(final UUID country) {
        return this.mood.snapshotOrNeutral(country);
    }

    @Override
    public int mood(final UUID countryId) {
        return this.snap(countryId).mood();
    }

    @Override
    public Map<String, Integer> moodFactors(final UUID countryId) {
        return this.snap(countryId).factors();
    }

    @Override
    public List<String> concerns(final UUID countryId) {
        return this.snap(countryId).concerns();
    }

    @Override
    public double moodMultiplier(final UUID countryId) {
        return this.snap(countryId).multiplier();
    }

    @Override
    public boolean unrest(final UUID countryId) {
        return this.snap(countryId).unrest();
    }

    @Override
    public boolean festival(final UUID countryId) {
        return this.snap(countryId).festival();
    }
}
