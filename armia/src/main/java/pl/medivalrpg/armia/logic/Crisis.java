// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

/** Crisis mode (defence) and rebuilding after a war. Pure. */
public final class Crisis {

    private Crisis() {
    }

    /**
     * In a war and losing land or besieged: a chunk lost within {@code crisisHours}, a siege of our land running now
     * or started within half of that time.
     */
    public static boolean active(final AiRules r, final boolean atWar, final long now, final long lastChunkLostAt,
                                 final boolean besiegedNow, final long lastSiegeOnUsAt) {
        if (!atWar) {
            return false;
        }
        final long window = Math.round(r.crisisHours() * 3_600_000L);
        return besiegedNow
            || (lastChunkLostAt > 0 && now - lastChunkLostAt <= window)
            || (lastSiegeOnUsAt > 0 && now - lastSiegeOnUsAt <= window / 2);
    }

    /** Rebuilding: at peace, within {@code rebuildHours} after the last war ended. */
    public static boolean rebuilding(final AiRules r, final boolean atWar, final long peaceSince, final long now) {
        return !atWar && peaceSince > 0 && now - peaceSince <= Math.round(r.rebuildHours() * 3_600_000L);
    }

    /** Lost land is a reason for a new war only after the cooldown since the last peace with anybody. */
    public static boolean reclaimReady(final AiRules r, final boolean atWar, final long peaceSince, final long now) {
        return atWar || peaceSince <= 0 || now - peaceSince >= r.reclaimCooldownMinutes() * 60_000L;
    }

    /**
     * Building type to order with priority, or null: in the crisis walls and towers (first the one we have fewer of);
     * while rebuilding houses when housing is (almost) full, else a granary when there is none.
     *
     * @param wallAvailable  "mur" can be built now
     * @param towerAvailable "wieza" can be built now
     */
    public static String priorityBuilding(final boolean crisis, final boolean rebuilding, final boolean wallAvailable, final int walls,
                                          final boolean towerAvailable, final int towers, final int population, final int housing,
                                          final boolean houseAvailable, final boolean granaryAvailable, final int granaries) {
        if (crisis) {
            if (wallAvailable && (!towerAvailable || walls <= towers)) {
                return "mur";
            }
            if (towerAvailable) {
                return "wieza";
            }
            return null;
        }
        if (rebuilding) {
            if (houseAvailable && housing - population <= 1) {
                return "dom";
            }
            if (granaryAvailable && granaries == 0) {
                return "spichlerz";
            }
        }
        return null;
    }
}
