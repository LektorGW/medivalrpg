// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.UUID;
import pl.medivalrpg.armia.api.NpcGoal;

/** The current goal of an NPC country as a short Polish sentence (web panel "cel AI", {@code /nk info}). Pure. */
public final class GoalPlanner {

    /** A country referenced by a goal. */
    public record Ref(UUID id, String name) {
    }

    /**
     * Situation of the country.
     *
     * @param crisis          crisis mode
     * @param enemy           main enemy (at war), or null
     * @param siegeOf         country whose chunk we besiege now, or null
     * @param siegeProgress   progress of our siege 0..1, or null
     * @param reclaimFrom     the enemy / neighbour holding our lost land, or null
     * @param lostChunks      chunks it holds
     * @param overlord        our overlord when we are a vassal, or null
     * @param buyoutCost      price of the buyout (≤ 0 unknown)
     * @param balance         treasury
     * @param capitulatingTo  we offered to capitulate to this country (pending), or null
     * @param rebuilding      rebuilding after a war
     * @param saving          a rich country saving for a representative building
     * @param soldiers        current army
     * @param desiredSoldiers desired army
     * @param chunks          land
     * @param maxChunks       land cap
     * @param expansion       ekspansja
     * @param builtRecently   an order was placed in the last ticks
     */
    public record Inputs(boolean crisis, Ref enemy, Ref siegeOf, Double siegeProgress, Ref reclaimFrom, int lostChunks,
                         Ref overlord, long buyoutCost, long balance, Ref capitulatingTo, boolean rebuilding, boolean saving,
                         int soldiers, int desiredSoldiers, int chunks, int maxChunks, int expansion, boolean builtRecently) {
    }

    private GoalPlanner() {
    }

    public static NpcGoal goal(final Inputs in) {
        if (in.capitulatingTo() != null) {
            return new NpcGoal("DIPLOMACY", "Proponuje kapitulację: " + in.capitulatingTo().name(), in.capitulatingTo().id(), null);
        }
        if (in.crisis() && in.enemy() != null) {
            return new NpcGoal("WAR", "Broni się przed: " + in.enemy().name() + " (tryb kryzysowy)", in.enemy().id(), null);
        }
        if (in.siegeOf() != null) {
            final boolean reclaim = in.reclaimFrom() != null && in.reclaimFrom().id().equals(in.siegeOf().id());
            return new NpcGoal("WAR", (reclaim ? "Odbija utracone ziemie: " : "Oblega ziemie: ") + in.siegeOf().name(),
                in.siegeOf().id(), in.siegeProgress());
        }
        if (in.enemy() != null) {
            final boolean reclaim = in.reclaimFrom() != null && in.reclaimFrom().id().equals(in.enemy().id());
            return new NpcGoal("WAR", (reclaim ? "Walczy o odzyskanie ziem z: " : "Prowadzi wojnę z: ") + in.enemy().name(), in.enemy().id(), null);
        }
        if (in.overlord() != null) {
            if (in.buyoutCost() > 0) {
                return new NpcGoal("SAVING", "Odkłada na wykup z wasalstwa u: " + in.overlord().name(), in.overlord().id(),
                    Math.max(0.0, Math.min(1.0, in.balance() / (double) in.buyoutCost())));
            }
            return new NpcGoal("DIPLOMACY", "Płaci trybut suwerenowi: " + in.overlord().name(), in.overlord().id(), null);
        }
        if (in.rebuilding()) {
            return new NpcGoal("BUILDING", "Odbudowuje kraj po wojnie", null, null);
        }
        if (in.reclaimFrom() != null && in.lostChunks() > 0) {
            return new NpcGoal("WAR", "Szykuje się do odbicia ziem (" + in.lostChunks() + ") zabranych przez: " + in.reclaimFrom().name(),
                in.reclaimFrom().id(), null);
        }
        if (in.desiredSoldiers() > 0 && in.soldiers() < Math.ceil(in.desiredSoldiers() * 0.8)) {
            return new NpcGoal("RECRUITING", "Rekrutuje wojsko (" + in.soldiers() + "/" + in.desiredSoldiers() + ")", null,
                Math.min(1.0, in.soldiers() / (double) in.desiredSoldiers()));
        }
        if (in.saving()) {
            return new NpcGoal("SAVING", "Odkłada na rozbudowę osady", null, null);
        }
        if (in.expansion() >= 30 && in.chunks() < in.maxChunks()) {
            return new NpcGoal("EXPANSION", "Powiększa terytorium (" + in.chunks() + "/" + in.maxChunks() + " chunków)", null,
                Math.min(1.0, in.chunks() / (double) Math.max(1, in.maxChunks())));
        }
        if (in.builtRecently()) {
            return new NpcGoal("BUILDING", "Rozbudowuje osadę", null, null);
        }
        return new NpcGoal("IDLE", "Rządzi w spokoju", null, null);
    }
}
