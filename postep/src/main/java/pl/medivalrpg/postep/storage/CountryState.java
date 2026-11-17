// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent progress of one country (Gson). Mutated on the main thread only. */
public final class CountryState {

    /** One era change. */
    public static final class EraChange {
        public int era;
        public long at;
        public String cause;

        public EraChange() {
        }

        public EraChange(final int era, final long at, final String cause) {
            this.era = era;
            this.at = at;
            this.cause = cause;
        }
    }

    public String name = "";
    public int era = 1;
    public long eraSince;
    /** Spendable research points. */
    public double points;
    /** Points earned in total. */
    public double totalPoints;
    /** Copper invested since the last daily tick (diminishing returns). */
    public double investedToday;
    public double investPointsToday;
    /** Discovery id → epoch millis, in discovery order. */
    public Map<String, Long> discoveries = new LinkedHashMap<>();
    /** Current research target (null = none). */
    public String target;
    /** All conditions met, waiting for the ruler's confirmation ({@code awans.potwierdzenie-wladcy}). */
    public boolean pendingAdvance;
    /** NPC investment share in percent, -1 = automatic. */
    public int npcInvestShare = -1;
    /** Points of the last daily tick (for the "+N/dzień" display). */
    public double lastDaily;
    public List<EraChange> history = new ArrayList<>();

    /** Restricts an existing country without deleting points or its discovery history. */
    public boolean limitEra(final int maximum, final long now) {
        final int limited = Math.clamp(this.era, 1, Math.max(1, maximum));
        if (limited == this.era) {
            return false;
        }
        this.era = limited;
        this.eraSince = now;
        this.pendingAdvance = false;
        this.history.add(new EraChange(limited, now, "limit-epok"));
        return true;
    }

    /** Gson leaves missing collections null for old files. */
    public CountryState normalize() {
        if (this.discoveries == null) {
            this.discoveries = new LinkedHashMap<>();
        }
        if (this.history == null) {
            this.history = new ArrayList<>();
        }
        if (this.name == null) {
            this.name = "";
        }
        if (this.era < 1) {
            this.era = 1;
        }
        return this;
    }
}
