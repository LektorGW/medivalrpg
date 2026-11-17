// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pl.medivalrpg.zycie.logic.FestivalRules;
import pl.medivalrpg.zycie.logic.UnrestRules;

/** Persistent mood state of one country (Gson; main thread only). Times are ticks of the main world's full time. */
public final class CountryState {

    /** Temporary mood modifier (event): points until the tick {@code until}. */
    public static final class Modifier {
        public String key;
        public int points;
        public long until;

        public Modifier() {
        }

        public Modifier(final String key, final int points, final long until) {
            this.key = key;
            this.points = points;
            this.until = until;
        }
    }

    /** Current mood 0..100, -1 = not computed yet. */
    public int mood = -1;
    public long lowSince = -1L;
    public boolean protest;
    public long festivalUntil = -1L;
    public long festivalLastStart = -1L;
    public String festivalReason = "";
    public List<Modifier> modifiers = new ArrayList<>();
    /** Number of great buildings (palace, cathedral…) at the last check, -1 = unknown. */
    public int greatBuildings = -1;
    /** Number of all buildings at the last check, -1 = unknown ("new building" events). */
    public int buildingCount = -1;
    /** Last known era (to notice era advances without Postep's event). */
    public int era = -1;

    public void normalize() {
        if (this.modifiers == null) {
            this.modifiers = new ArrayList<>();
        }
        this.modifiers.removeIf(m -> m == null || m.key == null);
        if (this.festivalReason == null) {
            this.festivalReason = "";
        }
    }

    public UnrestRules.State unrest() {
        return new UnrestRules.State(this.lowSince, this.protest);
    }

    public void unrest(final UnrestRules.State st) {
        this.lowSince = st.lowSince();
        this.protest = st.protest();
    }

    public FestivalRules.State festival() {
        return new FestivalRules.State(this.festivalUntil, this.festivalLastStart);
    }

    public void festival(final FestivalRules.State st) {
        this.festivalUntil = st.until();
        this.festivalLastStart = st.lastStart();
    }

    /** Adds a modifier (same key: the newer replaces the older one). */
    public void addModifier(final String key, final int points, final long until) {
        this.modifiers.removeIf(m -> m.key.equals(key));
        if (points != 0) {
            this.modifiers.add(new Modifier(key, points, until));
        }
    }

    /** Active modifiers summed by key; expired ones (or ones far in the future after /time set) are dropped. */
    public Map<String, Integer> activeModifiers(final long now, final long maxFuture) {
        this.modifiers.removeIf(m -> m.until <= now || m.until - now > maxFuture);
        final Map<String, Integer> out = new HashMap<>();
        for (final Modifier m : this.modifiers) {
            out.merge(m.key, m.points, Integer::sum);
        }
        return out;
    }
}
