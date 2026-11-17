// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** A list of {@link TextRule}s of one category (ambient bubbles, reactions, dialogues…). Immutable, thread-safe. */
public final class TextPool {

    public static final TextPool EMPTY = new TextPool(List.of());

    private final List<TextRule> rules;

    public TextPool(final List<TextRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<TextRule> rules() {
        return this.rules;
    }

    public int size() {
        return this.rules.size();
    }

    /** Rules matching the context. */
    public List<TextRule> matching(final TextContext c) {
        final List<TextRule> out = new ArrayList<>();
        for (final TextRule r : this.rules) {
            if (!r.variants().isEmpty() && r.effectiveWeight() > 0 && r.matches(c)) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Picks a rule (weighted, specific rules first) and one of its variants, avoiding the first lines in
     * {@code avoid} (recently said) when another variant is possible.
     */
    public Optional<List<String>> pick(final TextContext c, final RandomGenerator random, final Collection<String> avoid) {
        final List<TextRule> candidates = this.matching(c);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int total = 0;
        for (final TextRule r : candidates) {
            total += r.effectiveWeight();
        }
        int roll = random.nextInt(total);
        TextRule chosen = candidates.getLast();
        for (final TextRule r : candidates) {
            roll -= r.effectiveWeight();
            if (roll < 0) {
                chosen = r;
                break;
            }
        }
        final List<List<String>> fresh = new ArrayList<>();
        for (final List<String> v : chosen.variants()) {
            if (avoid == null || v.isEmpty() || !avoid.contains(v.getFirst())) {
                fresh.add(v);
            }
        }
        final List<List<String>> from = fresh.isEmpty() ? chosen.variants() : fresh;
        return Optional.of(from.get(random.nextInt(from.size())));
    }

    /** Single line (bubbles). */
    public Optional<String> pickLine(final TextContext c, final RandomGenerator random, final Collection<String> avoid) {
        return this.pick(c, random, avoid).filter(v -> !v.isEmpty()).map(List::getFirst);
    }
}
