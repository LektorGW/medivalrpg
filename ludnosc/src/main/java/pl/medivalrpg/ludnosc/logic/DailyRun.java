// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.time.LocalDate;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Order of the daily settlement (pure, unit tested). A day pays wages, income and taxes through the bank, whose ledger
 * is durable on its own; Ludnosc must therefore never settle the same day twice:
 * <ul>
 *   <li>the day is marked as processed and checkpointed BEFORE any money moves (at most once: a crash in the
 *       middle loses the rest of that day instead of paying the finished part again after a restart);</li>
 *   <li>one failing country never aborts the loop (it used to leave the day unmarked, so the countries before it
 *       were paid again every minute).</li>
 * </ul>
 */
public final class DailyRun {

    private DailyRun() {
    }

    /** Settles the due days, oldest first: commit (mark processed), checkpoint (save), then the day itself. */
    public static void settle(final List<LocalDate> due, final Consumer<LocalDate> commit, final Runnable checkpoint,
                              final Runnable day) {
        for (final LocalDate d : due) {
            commit.accept(d);
            checkpoint.run();
            day.run();
        }
    }

    /** Runs {@code action} for every item; a RuntimeException of one item goes to {@code onError}. Returns failures. */
    public static <T> int eachIsolated(final Iterable<T> items, final Consumer<T> action,
                                       final BiConsumer<T, RuntimeException> onError) {
        int failed = 0;
        for (final T item : items) {
            try {
                action.accept(item);
            } catch (final RuntimeException e) {
                failed++;
                onError.accept(item, e);
            }
        }
        return failed;
    }
}
