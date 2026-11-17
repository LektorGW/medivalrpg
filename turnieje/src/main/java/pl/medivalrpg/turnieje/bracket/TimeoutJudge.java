// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bracket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Decides a fight that hit its time limit: most damage dealt wins, then the higher remaining health share, then a coin
 * toss ({@code random}). Pure logic.
 */
public final class TimeoutJudge {

    /** @param healthShare current health / max health, 0..1 */
    public record Score<T>(T who, double damageDealt, double healthShare) {
    }

    private TimeoutJudge() {
    }

    /** Fighters best first. */
    public static <T> List<T> rank(final List<Score<T>> scores, final Random random) {
        final List<Score<T>> order = new ArrayList<>(scores);
        Collections.shuffle(order, random); // ties broken randomly; the sort below is stable
        order.sort(Comparator.<Score<T>>comparingDouble(s -> -round(s.damageDealt()))
            .thenComparingDouble(s -> -round(s.healthShare())));
        final List<T> out = new ArrayList<>(order.size());
        for (final Score<T> s : order) {
            out.add(s.who());
        }
        return out;
    }

    /** Ignores floating noise below 0.001 so that "equal" values tie. */
    private static double round(final double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
