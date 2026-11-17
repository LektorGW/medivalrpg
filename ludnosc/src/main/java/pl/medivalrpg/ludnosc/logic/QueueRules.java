// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import pl.medivalrpg.ludnosc.model.BlockBox;

/** Construction queue rules (pure, unit tested). */
public final class QueueRules {

    /** What happens with a new (valid) order. */
    public enum Admission {
        /** A site slot is free and nobody waits: build now. */
        START,
        /** All slots busy (or others wait first): pay and wait in the queue. */
        QUEUE,
        /** Slots and queue full: {@code TOO_MANY_ORDERS}. */
        FULL
    }

    private QueueRules() {
    }

    /** Orders never jump the queue: with waiting orders a new one queues even if a slot is momentarily free. */
    /** Site slots: base + perTier × settlement level index + perEra × (epoch − 1), at most {@code cap}. */
    public static int siteSlots(final int base, final int perTier, final int levelIndex, final int perEra, final int era,
                                final int cap) {
        return Math.max(1, Math.min(cap, base + perTier * Math.max(0, levelIndex) + perEra * Math.max(0, era - 1)));
    }

    public static Admission admit(final int activeSites, final int queued, final int maxSites, final int queueSize) {
        if (activeSites < maxSites && queued == 0) {
            return Admission.START;
        }
        return queued < queueSize ? Admission.QUEUE : Admission.FULL;
    }

    /** How many queued orders may start now. */
    public static int startable(final int activeSites, final int queued, final int maxSites) {
        return Math.max(0, Math.min(queued, maxSites - activeSites));
    }

    /** True when {@code box} overlaps any of {@code taken}. */
    public static boolean collides(final BlockBox box, final Collection<BlockBox> taken) {
        for (final BlockBox t : taken) {
            if (t != null && t.intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /** Refund of a cancelled order: queued orders get everything back, running sites {@code fraction} of the cost. */
    public static long refund(final long cost, final boolean queued, final double fraction) {
        if (cost <= 0) {
            return 0;
        }
        return queued ? cost : Math.round(cost * Math.max(0, Math.min(1, fraction)));
    }

    /**
     * Removes orders from the front of {@code queue} until one passes {@code valid}; rejected ones go to
     * {@code rejected} (refund + message). Returns the order to start (already removed), or null when none is valid.
     */
    public static <T> T pollFirstValid(final List<T> queue, final Predicate<T> valid, final Consumer<T> rejected) {
        final Iterator<T> it = queue.iterator();
        while (it.hasNext()) {
            final T next = it.next();
            it.remove();
            if (valid.test(next)) {
                return next;
            }
            rejected.accept(next);
        }
        return null;
    }

    /**
     * Like {@link #pollFirstValid}, but orders matching {@code waiting} (e.g. a repair of a besieged chunk) stay where
     * they are and are skipped for now.
     */
    public static <T> T pollFirstStartable(final List<T> queue, final Predicate<T> waiting, final Predicate<T> valid,
                                           final Consumer<T> rejected) {
        final Iterator<T> it = queue.iterator();
        while (it.hasNext()) {
            final T next = it.next();
            if (waiting.test(next)) {
                continue;
            }
            it.remove();
            if (valid.test(next)) {
                return next;
            }
            rejected.accept(next);
        }
        return null;
    }

    /** Swaps entries {@code index} and {@code index + delta}; false when out of range. */
    public static <T> boolean move(final List<T> list, final int index, final int delta) {
        final int target = index + delta;
        if (index < 0 || index >= list.size() || target < 0 || target >= list.size() || delta == 0) {
            return false;
        }
        final T a = list.get(index);
        list.set(index, list.get(target));
        list.set(target, a);
        return true;
    }
}
