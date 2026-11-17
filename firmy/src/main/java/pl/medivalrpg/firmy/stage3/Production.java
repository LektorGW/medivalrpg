// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage3;

import java.util.List;
import java.util.Map;

/**
 * One batch of a workshop on real items (pure Java). Nothing is produced from nothing: inputs (and fuel) are removed
 * from the input box and the output is added to the output box, or nothing happens. A full output box stops the work
 * before anything is taken (like the mines of Ludnosc).
 */
public final class Production {

    public static final List<String> FUELS = List.of("COAL", "CHARCOAL");

    /** Item storage (a chest in the game, a map in tests). Removals are all-or-nothing. */
    public interface Box {
        int count(String item);

        boolean remove(String item, int amount);

        boolean fits(String item, int amount);

        void add(String item, int amount);
    }

    public enum Result {
        DONE("pracuje"),
        NO_INPUT("brak surowców w skrzyni wejścia"),
        NO_FUEL("brak węgla w skrzyni wejścia"),
        OUTPUT_FULL("pełna skrzynia wyjścia");

        private final String label;

        Result(final String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    private Production() {
    }

    public static Result batch(final Recipe r, final Stage3Data.Workshop w, final Box in, final Box out) {
        for (final Map.Entry<String, Integer> e : r.inputs().entrySet()) {
            if (in.count(e.getKey()) < e.getValue()) {
                return Result.NO_INPUT;
            }
        }
        if (!out.fits(r.output(), r.outputCount())) {
            return Result.OUTPUT_FULL;
        }
        String fuel = null;
        if (r.fuel() && w.fuelLeft <= 0) {
            for (final String f : FUELS) {
                // the fuel must not be one of the inputs of this batch (e.g. charcoal recipe input is a log, fine)
                final int needed = r.inputs().getOrDefault(f, 0) + 1;
                if (in.count(f) >= needed) {
                    fuel = f;
                    break;
                }
            }
            if (fuel == null) {
                return Result.NO_FUEL;
            }
        }
        for (final Map.Entry<String, Integer> e : r.inputs().entrySet()) {
            if (!in.remove(e.getKey(), e.getValue())) {
                throw new IllegalStateException("Skrzynia wejścia zmieniła się w trakcie partii");
            }
        }
        if (fuel != null) {
            if (!in.remove(fuel, 1)) {
                throw new IllegalStateException("Paliwo zniknęło w trakcie partii");
            }
            w.fuelLeft += Recipe.SMELTS_PER_FUEL;
        }
        if (r.fuel()) {
            w.fuelLeft--;
        }
        out.add(r.output(), r.outputCount());
        w.produced += r.outputCount();
        return Result.DONE;
    }
}
