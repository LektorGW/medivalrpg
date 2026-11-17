// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.List;
import java.util.Set;

/**
 * One entry of a text pool in config.yml: conditions + texts. Every condition that is set (non-empty set / non-null
 * value) must hold. A bubble rule has one-line variants; a dialogue rule has multi-line variants (speakers alternate).
 *
 * @param phases    {@code pory}: day phases
 * @param eras      {@code epoki}: eras 1..4
 * @param moodMin   {@code nastroj-od}: mood ≥
 * @param moodMax   {@code nastroj-do}: mood ≤
 * @param war       {@code wojna}
 * @param siege     {@code oblezenie}
 * @param hungry    {@code glod}
 * @param highTaxes {@code podatki}
 * @param homeless  {@code bezdomnosc}
 * @param festival  {@code swieto}
 * @param protest   {@code protest}
 * @param events    {@code zdarzenia}: at least one recent
 * @param jobs      {@code zawody}: vanilla profession or city job id
 * @param standings {@code relacje}: reactions only
 * @param weight    {@code waga} (default 1)
 * @param variants  texts; each variant is a list of lines (one line for bubbles)
 */
public record TextRule(Set<DayPhase> phases, Set<Integer> eras, Integer moodMin, Integer moodMax, Boolean war,
                       Boolean siege, Boolean hungry, Boolean highTaxes, Boolean homeless, Boolean festival,
                       Boolean protest, Set<String> events, Set<String> jobs, Set<String> standings, int weight,
                       List<List<String>> variants) {

    public boolean matches(final TextContext c) {
        if (!this.phases.isEmpty() && !this.phases.contains(c.phase())) {
            return false;
        }
        if (!this.eras.isEmpty() && !this.eras.contains(c.era())) {
            return false;
        }
        if (this.moodMin != null && c.mood() < this.moodMin) {
            return false;
        }
        if (this.moodMax != null && c.mood() > this.moodMax) {
            return false;
        }
        if (!flag(this.war, c.war()) || !flag(this.siege, c.siege()) || !flag(this.hungry, c.hungry())
            || !flag(this.highTaxes, c.highTaxes()) || !flag(this.homeless, c.homeless())
            || !flag(this.festival, c.festival()) || !flag(this.protest, c.protest())) {
            return false;
        }
        if (!this.events.isEmpty() && this.events.stream().noneMatch(c.events()::contains)) {
            return false;
        }
        if (!this.jobs.isEmpty() && this.jobs.stream().noneMatch(c.jobs()::contains)) {
            return false;
        }
        return this.standings.isEmpty() || (c.standing() != null && this.standings.contains(c.standing()));
    }

    private static boolean flag(final Boolean wanted, final boolean actual) {
        return wanted == null || wanted == actual;
    }

    /** Number of conditions set: more specific rules are preferred when they match. */
    public int specificity() {
        int n = 0;
        n += this.phases.isEmpty() ? 0 : 1;
        n += this.eras.isEmpty() ? 0 : 1;
        n += this.moodMin == null ? 0 : 1;
        n += this.moodMax == null ? 0 : 1;
        for (final Boolean b : new Boolean[] {this.war, this.siege, this.hungry, this.highTaxes, this.homeless, this.festival, this.protest}) {
            n += b == null ? 0 : (b ? 2 : 0);
        }
        n += this.events.isEmpty() ? 0 : 2;
        n += this.jobs.isEmpty() ? 0 : 1;
        n += this.standings.isEmpty() ? 0 : 1;
        return n;
    }

    /** Selection weight = {@code waga × (1 + specificity)}. */
    public int effectiveWeight() {
        return Math.max(0, this.weight) * (1 + this.specificity());
    }
}
