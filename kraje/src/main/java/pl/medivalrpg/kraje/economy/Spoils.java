// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import java.util.List;

/**
 * Money movements of the spoils of war and of a vanishing treasury, over the {@link Economy} interface (no Bukkit, so
 * they are testable with a fake economy). With {@link Economy#NONE} (no Skarbiec) every method does nothing and
 * returns 0. Main thread only.
 */
public final class Spoils {

    private Spoils() {
    }

    /** Moves {@code ⌊balance(from) × fraction⌋} from {@code from} to {@code to}; returns the amount moved. */
    public static long take(final Economy eco, final String from, final String to, final double fraction, final String reason) {
        if (!eco.enabled()) {
            return 0L;
        }
        final long amount = LootMath.share(eco.balance(from), fraction);
        return amount > 0 && eco.transfer(from, to, amount, reason) ? amount : 0L;
    }

    /**
     * Splits the whole balance of {@code from} equally among {@code to} (rest coppers to the first accounts).
     * Returns what each account received (same order; 0 where the transfer failed).
     */
    public static long[] payOut(final Economy eco, final String from, final List<String> to, final String reason) {
        final long[] paid = new long[to.size()];
        if (!eco.enabled() || to.isEmpty()) {
            return paid;
        }
        final long[] shares = LootMath.split(eco.balance(from), to.size());
        for (int i = 0; i < to.size(); i++) {
            if (shares[i] > 0 && eco.transfer(from, to.get(i), shares[i], reason)) {
                paid[i] = shares[i];
            }
        }
        return paid;
    }

    /** Burns the whole balance of {@code account}; returns the amount destroyed. */
    public static long burn(final Economy eco, final String account, final String reason) {
        if (!eco.enabled()) {
            return 0L;
        }
        final long rest = eco.balance(account);
        return rest > 0 && eco.withdraw(account, rest, reason) ? rest : 0L;
    }
}
