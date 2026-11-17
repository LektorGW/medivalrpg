// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic.conquest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.armia.logic.ChunkPlanner;

/**
 * Balance rules of background conquests (pure): rate limits, war exhaustion, overextension, revolts, vassal secession,
 * the coalition against the strongest NPC country, peace terms and the choice of target chunks. Kraje re-checks every
 * territorial rule when the result is applied; these functions only decide what the AI tries.
 */
public final class ConquestRules {

    public static final long HOUR = 3_600_000L;
    public static final long DAY = 24 * HOUR;

    private ConquestRules() {
    }

    // ------------------------------------------------------------------ rolling day

    /** Events in the last 24 h (timestamps); older ones are dropped from the list. */
    public static int lastDay(final List<Long> times, final long now) {
        times.removeIf(t -> t == null || now - t >= DAY);
        return times.size();
    }

    // ------------------------------------------------------------------ exhaustion

    /** Exhaustion after {@code hours}: grows at war, recovers at peace; 0..100. */
    public static double exhaustion(final double current, final double hours, final boolean atWar, final ConquestSettings s) {
        final double h = Math.max(0, hours);
        final double next = atWar ? current + s.exhaustion().perHourAtWar() * h : current - s.exhaustion().decayPerHourPeace() * h;
        return clamp(next);
    }

    public static double clamp(final double v) {
        return Math.max(0, Math.min(100, v));
    }

    // ------------------------------------------------------------------ offensive start

    /** Everything the start decision needs. */
    public record StartView(boolean aiActive, boolean atWar, double warMinutes, double minutesSinceLastOffensive,
                            int takenToday, int globalToday, double exhaustion, int freeSoldiers, long treasury, long cost,
                            boolean defensivePosture, boolean reclaiming, boolean physicalSiege, boolean crisisAtHome) {
    }

    /** Null = may start; otherwise a short Polish reason (log). */
    public static String cannotStart(final StartView v, final ConquestSettings s) {
        if (!v.aiActive()) {
            return "AI wstrzymana";
        }
        if (!v.atWar()) {
            return "brak wojny";
        }
        if (v.physicalSiege()) {
            return "trwa oblężenie fizyczne";
        }
        if (v.warMinutes() < s.minWarMinutes()) {
            return "wojna trwa za krótko";
        }
        if (v.minutesSinceLastOffensive() < s.offensiveCooldownMinutes()) {
            return "przerwa po ostatniej ofensywie";
        }
        if (v.takenToday() >= s.maxChunksPerDay()) {
            return "dzienny limit zdobyczy kraju";
        }
        if (v.globalToday() >= s.globalMaxChunksPerDay()) {
            return "dzienny limit zdobyczy na serwerze";
        }
        if (v.exhaustion() >= s.exhaustion().blockOffensive()) {
            return "zmęczenie wojną";
        }
        if (v.defensivePosture() && !v.reclaiming()) {
            return "postawa obronna";
        }
        if (v.freeSoldiers() < s.minSoldiers()) {
            return "za mało wolnych żołnierzy";
        }
        if (v.treasury() < v.cost()) {
            return "brak pieniędzy na wyprawę";
        }
        return null;
    }

    /** Soldiers committed: at most {@code maxForce}, never the home guard share of the whole army. */
    public static int forceSize(final int total, final int free, final ConquestSettings s) {
        final int mayLeave = (int) Math.floor(Math.max(0, total) * (1.0 - s.homeGuardShare()));
        return Math.max(0, Math.min(Math.min(free, mayLeave), s.maxForce()));
    }

    // ------------------------------------------------------------------ overextension

    /** Extra daily upkeep of a big country: costPerChunk × excess × (1 + excess/free). */
    public static long overextensionCost(final int chunks, final ConquestSettings s) {
        final int free = s.overextension().freeChunks();
        final int excess = chunks - free;
        if (excess <= 0 || s.overextension().costPerChunk() <= 0) {
            return 0;
        }
        return Math.round(s.overextension().costPerChunk() * excess * (1.0 + (double) excess / free));
    }

    // ------------------------------------------------------------------ revolts

    /** Daily chance that a chunk conquered {@code hours} ago revolts; 0 outside the window. */
    public static double revoltChancePerDay(final double hours, final int holderChunks, final double holderExhaustion,
                                            final ConquestSettings s) {
        if (hours < 0 || hours > s.revolt().windowHours()) {
            return 0;
        }
        final int free = s.overextension().freeChunks();
        final double over = Math.max(0, (double) (holderChunks - free) / free);
        return Math.min(1.0, s.revolt().chancePerDay() * (1.0 + over + holderExhaustion / 50.0));
    }

    /** Probability per cycle of {@code minutes} for a daily probability. */
    public static double perCycle(final double perDay, final double minutes) {
        if (perDay <= 0) {
            return 0;
        }
        if (perDay >= 1) {
            return 1;
        }
        return 1.0 - Math.pow(1.0 - perDay, Math.max(0, minutes) / 1440.0);
    }

    // ------------------------------------------------------------------ vassals

    /** A vassal may break away from an exhausted or overextended NPC overlord it does not like. */
    public static boolean secessionPossible(final double overlordExhaustion, final int overlordChunks, final int vassalOpinion,
                                            final ConquestSettings s) {
        final boolean weak = overlordExhaustion >= s.secessionOverlordExhaustion()
            || overlordChunks > s.overextension().freeChunks() * 1.5;
        return weak && vassalOpinion < 0;
    }

    // ------------------------------------------------------------------ coalition

    /** The hegemon among NPC countries (land share or ratio to the second biggest), if any. Needs ≥ 3 NPC countries. */
    public static Optional<UUID> hegemon(final Map<UUID, Integer> npcChunks, final ConquestSettings s) {
        if (npcChunks.size() < 3) {
            return Optional.empty();
        }
        long total = 0;
        UUID best = null;
        int first = -1;
        int second = -1;
        for (final Map.Entry<UUID, Integer> e : npcChunks.entrySet()) {
            final int v = Math.max(0, e.getValue());
            total += v;
            if (v > first || (v == first && best != null && e.getKey().compareTo(best) < 0)) {
                second = first;
                first = v;
                best = e.getKey();
            } else if (v > second) {
                second = v;
            }
        }
        if (best == null || total <= 0 || first < s.overextension().freeChunks() / 2) {
            return Optional.empty();
        }
        final boolean share = (double) first / total >= s.coalition().share();
        final boolean ratio = second > 0 && (double) first / second >= s.coalition().ratio();
        return share || ratio ? Optional.of(best) : Optional.empty();
    }

    /** Opinion of the hegemon after {@code days} of coalition pressure (never pushed below the floor). */
    public static int coalitionOpinion(final int opinion, final double days, final ConquestSettings s) {
        if (opinion <= s.coalition().opinionFloor()) {
            return opinion;
        }
        final int drop = (int) Math.round(s.coalition().opinionPerDay() * Math.max(0, days));
        return Math.max(s.coalition().opinionFloor(), opinion - drop);
    }

    // ------------------------------------------------------------------ peace terms

    /** Daily tribute the loser of a war pays after a peace without capitulation (0 when nothing was taken). */
    public static long peaceTribute(final long loserDailyIncome, final int chunksTaken, final ConquestSettings s) {
        if (chunksTaken <= 0) {
            return 0;
        }
        final long t = Math.round(Math.max(0, loserDailyIncome) * s.peaceTributeShare()) + s.peaceTributePerChunk() * chunksTaken;
        return Math.max(1, Math.min(s.peaceTributeMax(), t));
    }

    // ------------------------------------------------------------------ targets

    /** A target chunk: key, expedition (no common border), distance from our land in chunks. */
    public record Target(long key, boolean expedition, int distance) {
    }

    /** Kraje's siege rule: no split of the defender, the capital only as its last chunk. */
    public static boolean siegeable(final Set<Long> enemy, final long key, final Long enemyCapital) {
        if (!enemy.contains(key)) {
            return false;
        }
        if (enemy.size() == 1) {
            return true; // the last chunk (also the capital): its fall ends the country
        }
        if (enemyCapital != null && enemyCapital == key && enemy.size() > 1) {
            return false;
        }
        return ChunkPlanner.siegeKeepsConnectivity(enemy, key, enemyCapital);
    }

    /** Chebyshev distance (chunks) from {@code key} to the nearest chunk of {@code land}; MAX_VALUE when empty. */
    public static int distance(final Set<Long> land, final long key) {
        final int x = ChunkPlanner.cx(key);
        final int z = ChunkPlanner.cz(key);
        int best = Integer.MAX_VALUE;
        for (final long k : land) {
            final int d = Math.max(Math.abs(ChunkPlanner.cx(k) - x), Math.abs(ChunkPlanner.cz(k) - z));
            if (d < best) {
                best = d;
                if (d <= 1) {
                    break;
                }
            }
        }
        return best;
    }

    /**
     * Chunks of one enemy we may attack: first the land front (bordering our land, sorted by key), otherwise — when
     * expeditions are on — the enemy's edge chunks within reach, nearest first. Lost chunks we want back come first.
     */
    public static List<Target> targets(final Set<Long> own, final Set<Long> enemy, final Long enemyCapital,
                                       final Set<Long> preferred, final Set<Long> retryBlocked, final ConquestSettings s) {
        final List<Target> front = new ArrayList<>();
        for (final long k : enemy) {
            if (retryBlocked.contains(k)) {
                continue;
            }
            boolean adjacent = false;
            for (final long n : ChunkPlanner.neighbors4(k)) {
                if (own.contains(n)) {
                    adjacent = true;
                    break;
                }
            }
            if (adjacent && siegeable(enemy, k, enemyCapital)) {
                front.add(new Target(k, false, 1));
            }
        }
        final Comparator<Target> order = Comparator.<Target>comparingInt(t -> preferred.contains(t.key()) ? 0 : 1)
            .thenComparingInt(Target::distance).thenComparingLong(Target::key);
        if (!front.isEmpty()) {
            front.sort(order);
            return front;
        }
        if (!s.expeditions() || own.isEmpty()) {
            return List.of();
        }
        final List<Target> far = new ArrayList<>();
        for (final long k : enemy) {
            if (retryBlocked.contains(k)) {
                continue;
            }
            boolean edge = false;
            for (final long n : ChunkPlanner.neighbors4(k)) {
                if (!enemy.contains(n)) {
                    edge = true;
                    break;
                }
            }
            if (!edge || !siegeable(enemy, k, enemyCapital)) {
                continue;
            }
            final int d = distance(own, k);
            if (d <= s.expeditionMaxDistance()) {
                far.add(new Target(k, true, d));
            }
        }
        far.sort(order);
        return far;
    }

    /** Minimum Chebyshev distance between two territories (cheap enough for ~100 × 100 chunks). */
    public static int gap(final Set<Long> a, final Set<Long> b) {
        int best = Integer.MAX_VALUE;
        for (final long k : b) {
            best = Math.min(best, distance(a, k));
            if (best <= 1) {
                return best;
            }
        }
        return best;
    }
}
