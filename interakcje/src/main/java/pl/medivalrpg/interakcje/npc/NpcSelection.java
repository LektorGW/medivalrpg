// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.npc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Which NPCs should be animated right now: those with a viewer within range, nearest first, at most
 * {@code maxActive}. Pure logic (no Bukkit), so it is unit tested.
 */
public final class NpcSelection {

    /**
     * @param id                 the NPC
     * @param nearestViewerSq    squared distance to the nearest eligible viewer ({@link Double#POSITIVE_INFINITY}: none)
     * @param ready              the NPC can be animated (skin resolved, entity valid)
     */
    public record Candidate(UUID id, double nearestViewerSq, boolean ready) {
    }

    /** What to change: stop these (all of them), start these (in this order, as the budget allows). */
    public record Plan(List<UUID> stop, List<UUID> start, Set<UUID> wanted) {
    }

    private NpcSelection() {
    }

    /**
     * @param candidates every NPC with a work animation in a loaded chunk
     * @param active     NPCs animated now
     * @param range      blocks; NPCs farther than this from every viewer are not animated
     * @param hysteresis extra blocks an already animated NPC may be away before it is stopped (no flicker at the edge)
     * @param maxActive  global limit
     */
    public static Plan plan(List<Candidate> candidates, Set<UUID> active, double range, double hysteresis, int maxActive) {
        double r2 = range * range;
        double keep2 = (range + hysteresis) * (range + hysteresis);
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!c.ready()) {
                continue;
            }
            double limit = active.contains(c.id()) ? keep2 : r2;
            if (c.nearestViewerSq() <= limit) {
                eligible.add(c);
            }
        }
        // nearest first; on a tie the already active one wins (stable, no swapping every scan)
        eligible.sort(Comparator.comparingDouble((Candidate c) -> c.nearestViewerSq())
                .thenComparing(c -> active.contains(c.id()) ? 0 : 1));
        Set<UUID> wanted = new HashSet<>();
        for (Candidate c : eligible) {
            if (wanted.size() >= Math.max(0, maxActive)) {
                break;
            }
            wanted.add(c.id());
        }
        List<UUID> stop = new ArrayList<>();
        for (UUID id : active) {
            if (!wanted.contains(id)) {
                stop.add(id);
            }
        }
        List<UUID> start = new ArrayList<>();
        for (Candidate c : eligible) {
            if (wanted.contains(c.id()) && !active.contains(c.id())) {
                start.add(c.id());
            }
        }
        return new Plan(stop, start, wanted);
    }
}
