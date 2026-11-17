// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Citizens' money (pure, unit tested). The savings of a country's citizens are ONE Skarbiec account
 * ({@code ludnosc:obywatele_<country>}); this class keeps the per-citizen share book of that account. Every
 * function only redistributes amounts it is given — it never creates money: sums are preserved exactly
 * (integer copper, deterministic remainders) and nobody's share goes below zero.
 */
public final class Wages {

    private Wages() {
    }

    /** Skarbiec account holding the savings of a country's citizens. */
    public static String account(final UUID country) {
        return "ludnosc:obywatele_" + country;
    }

    public static long sum(final Map<UUID, Long> book) {
        long s = 0;
        for (final long v : book.values()) {
            s = Math.addExact(s, v);
        }
        return s;
    }

    /**
     * Splits {@code amount} proportionally to {@code weights} (ids in a stable order). The result sums exactly to
     * {@code min(amount, ...)} = {@code amount} when any weight is positive; the remainder goes one copper at a time to
     * the largest fractional parts (ties: id order). Zero/negative weights get nothing.
     */
    public static Map<UUID, Long> split(final long amount, final Map<UUID, Long> weights) {
        final Map<UUID, Long> out = new LinkedHashMap<>();
        final List<UUID> ids = new ArrayList<>(weights.keySet());
        ids.sort(Comparator.comparing(UUID::toString));
        long total = 0;
        for (final UUID id : ids) {
            total += Math.max(0, weights.get(id));
            out.put(id, 0L);
        }
        if (amount <= 0 || total <= 0) {
            return out;
        }
        long given = 0;
        final List<double[]> fractions = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            final long w = Math.max(0, weights.get(ids.get(i)));
            if (w == 0) {
                continue;
            }
            final double exact = (double) amount * w / total;
            final long whole = (long) Math.floor(exact);
            out.put(ids.get(i), whole);
            given += whole;
            fractions.add(new double[] {exact - whole, i});
        }
        fractions.sort((a, b) -> a[0] != b[0] ? Double.compare(b[0], a[0]) : Double.compare(a[1], b[1]));
        long left = amount - given;
        for (int k = 0; left > 0 && !fractions.isEmpty(); k = (k + 1) % fractions.size()) {
            final UUID id = ids.get((int) fractions.get(k)[1]);
            out.merge(id, 1L, Long::sum);
            left--;
        }
        return out;
    }

    /**
     * Wages actually paid: the treasury can afford {@code available} (never below zero), the workers are owed
     * {@code due}. Everybody gets the same fraction of what they are owed.
     */
    public static Map<UUID, Long> pay(final Map<UUID, Long> due, final long available) {
        final long total = sum(due);
        final long paid = Math.max(0, Math.min(total, available));
        if (paid == total) {
            return new LinkedHashMap<>(due);
        }
        return split(paid, due);
    }

    /** Adds {@code credits} to the book (new map). */
    public static Map<UUID, Long> credit(final Map<UUID, Long> book, final Map<UUID, Long> credits) {
        final Map<UUID, Long> out = new LinkedHashMap<>(book);
        credits.forEach((id, v) -> {
            if (v > 0) {
                out.merge(id, v, Long::sum);
            }
        });
        return out;
    }

    /**
     * Takes {@code amount} from the book proportionally to the shares (huts paid together). Requires
     * {@code amount <= sum(book)}; otherwise nothing is taken and null is returned.
     */
    public static Map<UUID, Long> debit(final Map<UUID, Long> book, final long amount) {
        final long total = sum(book);
        if (amount < 0 || amount > total) {
            return null;
        }
        final Map<UUID, Long> take = split(amount, book);
        final Map<UUID, Long> out = new LinkedHashMap<>(book);
        take.forEach((id, v) -> out.put(id, out.get(id) - v));
        return out;
    }

    /**
     * Meals bought from the country's granary. {@code payerOf} maps every eater to who pays (children: a parent);
     * each eater costs {@code price × fedRatio}. A payer never pays more than its share; the shortfall is simply not
     * paid (the food was eaten anyway). Returns payer → amount.
     */
    public static Map<UUID, Long> meals(final Map<UUID, Long> book, final Map<UUID, UUID> payerOf, final long price,
                                        final double fedRatio) {
        final Map<UUID, Long> owed = new LinkedHashMap<>();
        final long each = Math.round(Math.max(0, price) * Math.max(0, Math.min(1, fedRatio)));
        if (each <= 0) {
            return owed;
        }
        final List<UUID> eaters = new ArrayList<>(payerOf.keySet());
        eaters.sort(Comparator.comparing(UUID::toString));
        for (final UUID eater : eaters) {
            final UUID payer = payerOf.get(eater) == null ? eater : payerOf.get(eater);
            owed.merge(payer, each, Long::sum);
        }
        final Map<UUID, Long> out = new LinkedHashMap<>();
        owed.forEach((payer, v) -> {
            final long has = Math.max(0, book.getOrDefault(payer, 0L));
            final long pays = Math.min(has, v);
            if (pays > 0) {
                out.put(payer, pays);
            }
        });
        return out;
    }

    /**
     * Makes the share book match the real account balance (after a crash between a transfer and the next save, an
     * admin change or citizens who left): a surplus is spread evenly over {@code members} (id order for the copper
     * remainder), a deficit lowers every share proportionally. Shares of non-members are dropped first (their money
     * stays in the account and is spread to the members). The result always sums to {@code actual} when there are
     * members; with no members the result is empty.
     */
    public static Map<UUID, Long> reconcile(final Map<UUID, Long> book, final List<UUID> members, final long actual) {
        final Map<UUID, Long> kept = new LinkedHashMap<>();
        final List<UUID> ids = new ArrayList<>(members);
        ids.sort(Comparator.comparing(UUID::toString));
        for (final UUID id : ids) {
            kept.put(id, Math.max(0, book.getOrDefault(id, 0L)));
        }
        if (ids.isEmpty()) {
            return kept;
        }
        final long target = Math.max(0, actual);
        final long current = sum(kept);
        if (current == target) {
            return kept;
        }
        if (current > target) {
            return split(target, kept);
        }
        long surplus = target - current;
        final long each = surplus / ids.size();
        long rest = surplus % ids.size();
        for (final UUID id : ids) {
            kept.put(id, kept.get(id) + each + (rest-- > 0 ? 1 : 0));
        }
        return kept;
    }

    /**
     * Daily income from work (from the world's households pool, the same source the NPC tax always came from): the
     * citizen's tax share times {@code factor} rounded up, plus the meals of the citizen and of the children it pays
     * for. With {@code factor >= 1} a fed citizen can always pay its tax and meals.
     */
    public static long income(final long taxShare, final double factor, final long mealPrice, final int dependents) {
        return (long) Math.ceil(Math.max(0, taxShare) * Math.max(0, factor) - 1e-9) + Math.max(0, mealPrice) * (1 + Math.max(0, dependents));
    }

    /** Tax actually collected from each citizen: its share, never more than its savings. */
    public static Map<UUID, Long> collect(final Map<UUID, Long> book, final Map<UUID, Long> shares) {
        final Map<UUID, Long> out = new LinkedHashMap<>();
        shares.forEach((id, v) -> {
            final long pays = Math.min(Math.max(0, v), Math.max(0, book.getOrDefault(id, 0L)));
            if (pays > 0) {
                out.put(id, pays);
            }
        });
        return out;
    }

    /** Subtracts payments from the book (new map); a payment above a share is clamped at zero. */
    /**
     * Market purchases: every citizen with more than {@code buffer} spends {@code share} of the part above it (rounded
     * down). Never below the buffer, never negative; the money goes back to the households pool.
     */
    public static Map<UUID, Long> market(final Map<UUID, Long> book, final long buffer, final double share) {
        final Map<UUID, Long> out = new LinkedHashMap<>();
        book.forEach((id, v) -> {
            final long above = Math.max(0, (v == null ? 0 : v) - Math.max(0, buffer));
            final long buy = (long) Math.floor(above * Math.max(0, Math.min(1, share)));
            if (buy > 0) {
                out.put(id, buy);
            }
        });
        return out;
    }

    public static Map<UUID, Long> minus(final Map<UUID, Long> book, final Map<UUID, Long> payments) {
        final Map<UUID, Long> out = new LinkedHashMap<>(book);
        payments.forEach((id, v) -> out.put(id, Math.max(0, out.getOrDefault(id, 0L) - v)));
        return out;
    }

    /** Average share (0 without members). */
    public static long average(final long total, final int members) {
        return members <= 0 ? 0 : Math.max(0, total) / members;
    }
}
