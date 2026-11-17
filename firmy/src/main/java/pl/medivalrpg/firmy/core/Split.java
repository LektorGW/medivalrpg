// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact proportional splitting of an amount (largest remainder method): the parts always add up to the amount, no
 * copper is created or lost by rounding. Ties are broken by the input order, so the result is deterministic. Pure.
 */
public final class Split {

    private Split() {
    }

    /**
     * @param amount  copper to split (≥ 0)
     * @param weights key → weight (≥ 0); keys with weight 0 get 0
     * @return key → part (same order as {@code weights}); empty map when all weights are 0
     */
    public static <K> Map<K, Long> proportional(final long amount, final Map<K, Long> weights) {
        final Map<K, Long> out = new LinkedHashMap<>();
        if (amount < 0) {
            throw new IllegalArgumentException("Ujemna kwota");
        }
        long total = 0;
        for (final long w : weights.values()) {
            if (w < 0) {
                throw new IllegalArgumentException("Ujemna waga");
            }
            total = Math.addExact(total, w);
        }
        if (total == 0) {
            return out;
        }
        record Rem(Object key, long rem, int order) {
        }
        final List<Rem> rems = new ArrayList<>();
        long given = 0;
        int i = 0;
        for (final var e : weights.entrySet()) {
            // amount * w / total without overflow for amounts up to 1e8 and weights up to 1e10
            final java.math.BigInteger num = java.math.BigInteger.valueOf(amount).multiply(java.math.BigInteger.valueOf(e.getValue()));
            final java.math.BigInteger[] qr = num.divideAndRemainder(java.math.BigInteger.valueOf(total));
            final long base = qr[0].longValueExact();
            out.put(e.getKey(), base);
            given += base;
            rems.add(new Rem(e.getKey(), qr[1].longValueExact(), i++));
        }
        long left = amount - given;
        rems.sort(Comparator.comparingLong(Rem::rem).reversed().thenComparingInt(Rem::order));
        for (final Rem r : rems) {
            if (left <= 0) {
                break;
            }
            if (r.rem() == 0) {
                continue;
            }
            @SuppressWarnings("unchecked") final K k = (K) r.key();
            out.merge(k, 1L, Long::sum);
            left--;
        }
        if (left != 0) {
            throw new IllegalStateException("Podział nie sumuje się do kwoty");
        }
        return out;
    }
}
