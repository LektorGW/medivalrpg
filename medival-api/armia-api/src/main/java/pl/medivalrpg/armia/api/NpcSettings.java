// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI settings and state of one NPC country (see {@link ArmiaApi#npcSettings}).
 *
 * @param aggression   agresja 0–100
 * @param expansion    ekspansja 0–100
 * @param diplomacy    dyplomacja 0–100
 * @param wealth       bogactwo 0–100
 * @param maxChunks    max-chunki (≥ 1)
 * @param maxSoldiers  max-zolnierze (≥ 0)
 * @param active       aktywna
 * @param paused       paused by an admin for this country
 * @param globalPaused the whole NPC AI is paused or disabled in the config
 * @param nextTickAt   epoch millis of the next scheduled decision tick (0 = unknown)
 * @param honor        honor 0–100 (since API 1.3): word kept, contempt for base deals; set by an admin or the scenario,
 *                     otherwise {@link #deriveHonor derived} from the personality
 */
public record NpcSettings(int aggression, int expansion, int diplomacy, int wealth, int maxChunks, int maxSoldiers,
                          boolean active, boolean paused, boolean globalPaused, long nextTickAt, int honor) {

    /** Keys accepted by {@link ArmiaApi#setNpcSetting} (Polish, as in {@code /nk ustaw}; {@code honor auto} = derived). */
    public static final List<String> PARAMS = List.of("agresja", "ekspansja", "dyplomacja", "bogactwo", "max-chunki", "max-zolnierze", "aktywna", "honor");

    /** Pre-1.3 constructor: honor derived from the personality. */
    public NpcSettings(final int aggression, final int expansion, final int diplomacy, final int wealth, final int maxChunks, final int maxSoldiers,
                       final boolean active, final boolean paused, final boolean globalPaused, final long nextTickAt) {
        this(aggression, expansion, diplomacy, wealth, maxChunks, maxSoldiers, active, paused, globalPaused, nextTickAt,
            deriveHonor(aggression, diplomacy, wealth));
    }

    /**
     * Fallback honor when none is set: diplomatic and peaceful rulers keep their word, aggressive and very rich (greedy)
     * ones less so. {@code 50 + (dyplomacja−50)·0.5 − (agresja−50)·0.4 − max(0, bogactwo−60)·0.3}, clamped to 0–100.
     */
    public static int deriveHonor(final int aggression, final int diplomacy, final int wealth) {
        final double h = 50 + (diplomacy - 50) * 0.5 - (aggression - 50) * 0.4 - Math.max(0, wealth - 60) * 0.3;
        return (int) Math.max(0, Math.min(100, Math.round(h)));
    }

    /** Parameters by their {@link #PARAMS} key (ints, {@code aktywna} as boolean). */
    public Map<String, Object> asParams() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("agresja", this.aggression);
        m.put("ekspansja", this.expansion);
        m.put("dyplomacja", this.diplomacy);
        m.put("bogactwo", this.wealth);
        m.put("max-chunki", this.maxChunks);
        m.put("max-zolnierze", this.maxSoldiers);
        m.put("aktywna", this.active);
        m.put("honor", this.honor);
        return m;
    }
}
