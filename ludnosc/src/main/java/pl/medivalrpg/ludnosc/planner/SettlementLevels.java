// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Settlement levels (Osada → Wieś → Miasteczko → Miasto → Stolica → Metropolia) from population and treasury (pure). A level is
 * reached when both requirements are met; levels are ordered and the index is the {@code tier} of templates.
 */
public final class SettlementLevels {

    /** One level: name and minimal population / treasury (copper). */
    public record Level(String name, int population, long treasury) {
    }

    public static final List<Level> DEFAULT = List.of(
        new Level("Osada", 0, 0),
        new Level("Wieś", 8, 2_000),
        new Level("Miasteczko", 20, 10_000),
        new Level("Miasto", 40, 30_000),
        new Level("Stolica", 70, 80_000),
        new Level("Metropolia", 150, 300_000));

    private final List<Level> levels;

    public SettlementLevels(final List<Level> levels) {
        final List<Level> copy = new ArrayList<>(levels == null || levels.isEmpty() ? DEFAULT : levels);
        copy.sort(Comparator.comparingInt(Level::population).thenComparingLong(Level::treasury));
        this.levels = List.copyOf(copy);
    }

    public List<Level> levels() {
        return this.levels;
    }

    /** Highest level index whose requirements are met (0 at least). */
    public int index(final int population, final long treasury) {
        int idx = 0;
        for (int i = 0; i < this.levels.size(); i++) {
            final Level l = this.levels.get(i);
            if (population >= l.population() && treasury >= l.treasury()) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }

    public Level level(final int index) {
        return this.levels.get(Math.max(0, Math.min(index, this.levels.size() - 1)));
    }

    /** The next level, or null at the top. */
    public Level next(final int index) {
        return index + 1 < this.levels.size() ? this.levels.get(index + 1) : null;
    }

    public int max() {
        return this.levels.size() - 1;
    }
}
