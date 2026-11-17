// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import java.util.UUID;

/** Per-block policy, never inferred from the explosion's present location. */
public final class BlastPolicy {
    private BlastPolicy() { }
    public static boolean allows(final UUID sourceCountry,final UUID owner,final boolean verified,
                                 final boolean protectedBlock,final boolean atWar,final boolean siegeBreakAllowed) {
        if (protectedBlock) return false;
        if (owner==null) return true; // Ordinary wilderness behaviour remains unchanged.
        if (!verified || sourceCountry==null) return false;
        return sourceCountry.equals(owner) || atWar && siegeBreakAllowed;
    }
}
