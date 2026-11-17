// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.tutorial;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Progress of one player through the tutorial.
 *
 * @param steps   step id → done, in menu order
 * @param changed the stored entry was changed by this evaluation and must be saved
 */
public record TutorialProgress(Map<String, Boolean> steps, boolean changed) {

    public int done() {
        return (int) this.steps.values().stream().filter(Boolean::booleanValue).count();
    }

    public int total() {
        return this.steps.size();
    }

    public boolean finished() {
        return !this.steps.isEmpty() && this.done() == this.total();
    }

    /** First step not done yet, {@code null} when everything is done. */
    public String next() {
        return this.steps.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /** A step proven by the game state is still open, so a login reminder makes sense. */
    public boolean remind() {
        return this.steps.entrySet().stream().anyMatch(e -> !e.getValue() && TutorialRules.STATE_STEPS.contains(e.getKey()));
    }

    /**
     * Evaluates the steps for one player and makes proven steps sticky in {@code entry}.
     *
     * <p>A step is done when it was done before, when the game state proves it, or — for an informational step and
     * for a step whose state cannot be read — when the player has clicked it.</p>
     */
    public static TutorialProgress evaluate(final List<String> stepIds, final TutorialStore.Entry entry,
                                            final WorldState state, final long moneyGoal) {
        final Map<String, Boolean> steps = new LinkedHashMap<>();
        boolean changed = false;
        for (final String id : stepIds) {
            boolean done = entry.done.contains(id);
            if (!done) {
                final Boolean proven = TutorialRules.done(id, state, moneyGoal);
                done = proven != null ? proven : entry.seen.contains(id);
                if (done) {
                    entry.done.add(id);
                    changed = true;
                }
            }
            steps.put(id, done);
        }
        return new TutorialProgress(Collections.unmodifiableMap(steps), changed);
    }
}
