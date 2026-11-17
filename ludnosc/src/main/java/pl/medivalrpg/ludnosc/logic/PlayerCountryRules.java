// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.List;
import java.util.Collection;

/** Country policy; applied only to new orders, never existing sites or buildings. */
public final class PlayerCountryRules {
    private PlayerCountryRules() {}
    public static final List<String> DEFAULT_BUILDINGS = List.of("chata", "kopalnia", "kamieniolom", "tartak",
        "pole", "przystan", "huta_szkla", "cegielnia", "owczarnia", "kuznia", "spichlerz", "magazyn", "studnia", "koszary");
    public static boolean allowed(boolean npc, boolean blueprint, String id, String base, Collection<String> allowed) {
        return npc || blueprint || allowed.contains(id) || allowed.contains(base);
    }
    public static boolean automatic(boolean npc) { return npc; }
    /** Spaced orders give a persistent rolling daily cap using the existing lastHut field. */
    public static boolean hutDue(long now, long last, int dailyLimit) {
        return dailyLimit > 0 && (last == 0 || now - last >= (86_400_000L + dailyLimit - 1) / dailyLimit);
    }
}
