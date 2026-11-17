// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.survey;

/**
 * Grade-limited vertical profile of a track. Given the desired heights {@code g[i]} at unit spacing and a maximum
 * grade {@code c} (rise per block), returns heights {@code y[i]} with {@code |y[i+1] - y[i]| <= c} that balance cuttings
 * and embankments: the average of the lower Lipschitz envelope (largest c-Lipschitz function below the ground — cuts
 * hills) and the upper one (smallest above — fills valleys). Both envelopes are computed in O(n) by two passes. The
 * result is then clamped into the cones around fixed start and end heights, which keeps it c-Lipschitz and makes the
 * ends exact.
 */
public final class VerticalProfile {

    private VerticalProfile() {
    }

    /**
     * @param ground desired heights (e.g. ground + 1 for a rail on the ground)
     * @param grade  maximum rise per unit step (e.g. 0.04 = 4 %)
     * @param startY fixed height at index 0 (NaN = free)
     * @param endY   fixed height at the last index (NaN = free)
     * @return heights, or {@code null} when the fixed ends cannot be joined within the grade
     */
    public static double[] fit(final double[] ground, final double grade, final double startY, final double endY) {
        final int n = ground.length;
        if (n == 0) {
            return new double[0];
        }
        final double last = n - 1;
        if (!Double.isNaN(startY) && !Double.isNaN(endY) && Math.abs(endY - startY) > grade * last + 1e-9) {
            return null;
        }
        final double[] lower = ground.clone();
        final double[] upper = ground.clone();
        for (int i = 1; i < n; i++) {
            lower[i] = Math.min(lower[i], lower[i - 1] + grade);
            upper[i] = Math.max(upper[i], upper[i - 1] - grade);
        }
        for (int i = n - 2; i >= 0; i--) {
            lower[i] = Math.min(lower[i], lower[i + 1] + grade);
            upper[i] = Math.max(upper[i], upper[i + 1] - grade);
        }
        final double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = (lower[i] + upper[i]) / 2;
            if (!Double.isNaN(startY)) {
                y[i] = clamp(y[i], startY - grade * i, startY + grade * i);
            }
            if (!Double.isNaN(endY)) {
                final double d = last - i;
                y[i] = clamp(y[i], endY - grade * d, endY + grade * d);
            }
        }
        return y;
    }

    /** Largest absolute step of a profile. */
    public static double maxStep(final double[] y) {
        double m = 0;
        for (int i = 1; i < y.length; i++) {
            m = Math.max(m, Math.abs(y[i] - y[i - 1]));
        }
        return m;
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
