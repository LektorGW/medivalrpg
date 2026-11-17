// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bracket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a prize pool (copper) between places. Pure logic.
 *
 * <p>Place {@code i} gets {@code percents[i]}% of the pool, shared equally by everybody on that place (e.g. two
 * semi-final losers share the 3rd-place share); rounding dust of a shared place goes to its first member. Shares of
 * empty places and the part above the percent sum are the {@link Result#remainder()} (returned to the source), unless
 * {@code unclaimedToChampion} gives empty places' shares to the champion.</p>
 */
public final class PrizeSplit {

    /** Payouts per entrant (in placement order) and what is left in the pool. */
    public record Result<T>(Map<T, Long> payouts, long remainder) {
        public long paid() {
            return this.payouts.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private PrizeSplit() {
    }

    /** {@code pool * percent / 100} without overflow for any non-negative pool. */
    static long share(final long pool, final int percent) {
        return pool / 100 * percent + pool % 100 * percent / 100;
    }

    /**
     * @throws IllegalArgumentException for a negative pool, negative percents or a sum above 100
     */
    public static <T> Result<T> split(final long pool, final List<Integer> percents, final List<List<T>> placements,
                                      final boolean unclaimedToChampion) {
        if (pool < 0) {
            throw new IllegalArgumentException("pool < 0");
        }
        int sum = 0;
        for (final int p : percents) {
            if (p < 0) {
                throw new IllegalArgumentException("negative percent");
            }
            sum += p;
        }
        if (sum > 100) {
            throw new IllegalArgumentException("percents sum above 100: " + sum);
        }
        final Map<T, Long> payouts = new LinkedHashMap<>();
        long unclaimed = 0;
        for (int place = 0; place < percents.size(); place++) {
            final long share = share(pool, percents.get(place));
            final List<T> members = place < placements.size() ? placements.get(place) : List.of();
            if (members.isEmpty()) {
                unclaimed += share;
                continue;
            }
            final long each = share / members.size();
            final long dust = share - each * members.size();
            for (int i = 0; i < members.size(); i++) {
                payouts.merge(members.get(i), each + (i == 0 ? dust : 0), Long::sum);
            }
        }
        final T champion = !placements.isEmpty() && !placements.getFirst().isEmpty() ? placements.getFirst().getFirst() : null;
        if (unclaimedToChampion && champion != null && unclaimed > 0) {
            payouts.merge(champion, unclaimed, Long::sum);
        }
        payouts.values().removeIf(v -> v <= 0);
        final long paid = payouts.values().stream().mapToLong(Long::longValue).sum();
        return new Result<>(payouts, pool - paid);
    }
}
