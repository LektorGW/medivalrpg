// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.access;

import java.util.UUID;
import pl.medivalrpg.kraje.model.Rank;

/**
 * Permission rules of the panel (pure, unit-tested as a rank × method matrix).
 *
 * <ul>
 *   <li>M / A / R: the actor's own country with at least that rank; a panel admin passes everywhere.</li>
 *   <li>NPC managers never get M / A / R rights through their role; they only manage NPC countries.</li>
 *   <li>{@code admin.npc.*}: NPC country only; admin, global manager, or the manager assigned to that country.</li>
 *   <li>{@code admin.npc.create}: admin or global manager. Everything else under {@code admin.*}: admin only.</li>
 * </ul>
 */
public final class Access {

    private Access() {
    }

    /**
     * @param target    country the call acts on (null for global methods)
     * @param targetNpc whether {@code target} is an NPC country
     */
    public static boolean allowed(final Actor a, final Requirement r, final UUID target, final boolean targetNpc) {
        if (a == null || r == null) {
            return false;
        }
        return switch (r) {
            case LOGGED_IN -> true;
            case MEMBER -> rank(a, target, Rank.CITIZEN);
            case ADVISOR -> rank(a, target, Rank.ADVISOR);
            case ADVISOR_OR_NPC_MANAGE -> rank(a, target, Rank.ADVISOR) || canManageNpc(a, target, targetNpc);
            case RULER -> rank(a, target, Rank.RULER);
            case RULER_OR_NPC_MANAGE -> rank(a, target, Rank.RULER) || canManageNpc(a, target, targetNpc);
            case NPC_MANAGE -> canManageNpc(a, target, targetNpc);
            case NPC_CREATE -> a.admin() || a.npcManager();
            case ADMIN -> a.admin();
        };
    }

    private static boolean rank(final Actor a, final UUID target, final Rank needed) {
        if (a.admin()) {
            return true;
        }
        final Rank rank = a.rankIn(target);
        return rank != null && rank.atLeast(needed);
    }

    /** {@code admin.npc.*} on one country, and reading its {@code npc} part in {@code country.get}. */
    public static boolean canManageNpc(final Actor a, final UUID target, final boolean targetNpc) {
        if (a == null || target == null || !targetNpc) {
            return false;
        }
        return a.admin() || a.npcManager() || a.managedNpcCountries().contains(target);
    }

    /** {@code country.get}: member list with tax debts (M). */
    public static boolean canSeeMembers(final Actor a, final UUID target) {
        return allowed(a, Requirement.MEMBER, target, false);
    }

    /** {@code country.get}: treasury balance and history (A). */
    public static boolean canSeeTreasury(final Actor a, final UUID target) {
        return allowed(a, Requirement.ADVISOR, target, false);
    }
}
