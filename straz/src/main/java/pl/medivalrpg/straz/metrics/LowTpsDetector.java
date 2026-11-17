// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.metrics;

/**
 * Detects "TPS below the threshold for longer than N minutes" from one sample per minute (pure). Fires once per
 * streak; a sample at/above the threshold ends the streak (and reports its length for the recovery log line).
 */
public final class LowTpsDetector {

    /** Result of one sample. */
    public enum Signal { NONE, ALERT, RECOVERED }

    private final double threshold;
    private final int minutes;
    private int streak;
    private boolean alerted;
    private double streakMin = 20.0;
    private int lastStreak;

    public LowTpsDetector(final double threshold, final int minutes) {
        this.threshold = threshold;
        this.minutes = Math.max(1, minutes);
    }

    public boolean isLow(final double tps) {
        return tps < this.threshold;
    }

    public Signal sample(final double tps) {
        if (this.isLow(tps)) {
            this.streak++;
            this.streakMin = Math.min(this.streakMin, tps);
            if (!this.alerted && this.streak >= this.minutes) {
                this.alerted = true;
                return Signal.ALERT;
            }
            return Signal.NONE;
        }
        final boolean wasAlerted = this.alerted;
        this.lastStreak = this.streak;
        this.streak = 0;
        this.alerted = false;
        this.streakMin = 20.0;
        return wasAlerted ? Signal.RECOVERED : Signal.NONE;
    }

    /** Minutes of the current low streak. */
    public int streak() {
        return this.streak;
    }

    /** Lowest TPS of the current streak. */
    public double streakMin() {
        return this.streakMin;
    }

    /** Length of the streak that just ended (after {@link Signal#RECOVERED}). */
    public int lastStreak() {
        return this.lastStreak;
    }

    public double threshold() {
        return this.threshold;
    }

    public int minutes() {
        return this.minutes;
    }
}
