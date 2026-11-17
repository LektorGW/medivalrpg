// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/** Sizes and timing of a hauler's trip (pure, unit tested). */
public final class HaulPlan {

    private HaulPlan() {
    }

    /**
     * Items for one trip: enough to cover {@code units} of a material when one item gives {@code perItem} units,
     * never more than the source has or one load carries.
     */
    public static int amount(final long units, final int perItem, final int available, final int load) {
        if (units <= 0 || perItem <= 0 || available <= 0 || load <= 0) {
            return 0;
        }
        final long items = (units + perItem - 1) / perItem;
        return (int) Math.min(items, Math.min(available, load));
    }

    /** A workplace store is emptied into the warehouse once this many of its slots are taken. */
    public static boolean shouldUnload(final int usedSlots, final int slots, final double fill) {
        if (slots <= 0) {
            return false;
        }
        return usedSlots >= Math.max(1, (int) Math.ceil(slots * Math.max(0.1, Math.min(1.0, fill))));
    }

    /** Time a hauler gets for a walk of {@code distance} blocks: 2.5× the plain walk plus 20 s for detours. */
    public static long deadlineMillis(final double distance, final double speed) {
        return (long) (Math.max(0, distance) / Math.max(0.1, speed) * 2500) + 20_000L;
    }
}
