// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/** Random Polish names without quick repetitions: shuffles the list and deals it like a deck. */
public final class NamePool {

    private final List<String> names;
    private final Random random;
    private final Deque<String> deck = new ArrayDeque<>();

    public NamePool(final List<String> names, final Random random) {
        final List<String> clean = new ArrayList<>();
        for (final String n : names) {
            if (n != null && !n.isBlank()) {
                clean.add(n.trim());
            }
        }
        if (clean.isEmpty()) {
            clean.add("Osadnik");
        }
        this.names = List.copyOf(clean);
        this.random = random;
    }

    public synchronized String next() {
        if (this.deck.isEmpty()) {
            final List<String> copy = new ArrayList<>(this.names);
            Collections.shuffle(copy, this.random);
            this.deck.addAll(copy);
        }
        return this.deck.poll();
    }

    public int size() {
        return this.names.size();
    }
}
