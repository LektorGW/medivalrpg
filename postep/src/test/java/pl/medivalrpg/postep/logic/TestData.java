// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import java.util.List;
import java.util.Map;
import pl.medivalrpg.postep.model.Discovery;

/** Small hand-made discoveries for the tests. */
final class TestData {

    private TestData() {
    }

    static Discovery d(final String id, final int era, final long cost, final boolean required, final String category,
                       final String... requires) {
        return new Discovery(id, id.toUpperCase(), era, cost, List.of(requires), required, category, "PAPER", "", "", Map.of());
    }

    /** a, b (era 1, required), c needs a+b (era 1, required), x (era 1 optional wojsko), d needs c (era 2). */
    static TechTree small() {
        return TechTree.of(List.of(
            d("a", 1, 100, true, "gospodarka"),
            d("b", 1, 100, true, "nauka"),
            d("c", 1, 200, true, "budownictwo", "a", "b"),
            d("x", 1, 100, false, "wojsko"),
            d("d", 2, 300, true, "nauka", "c")));
    }
}
