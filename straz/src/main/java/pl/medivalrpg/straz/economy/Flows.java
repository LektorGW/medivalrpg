// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.economy;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Money flows of one account in a time window, from Skarbiec's transaction log lines (pure). Line format (tabs):
 * {@code seq, time (ISO instant), type, from, to, amount, fromAfter, toAfter, reason}. Transfers from/to the world
 * reserve are one-off grants or returns and are kept apart from the organic flow used for the forecast.
 */
public final class Flows {

    public static final String RESERVE = "world:reserve";

    /** Result for one account. */
    public record Flow(String account, long in, long out, long grantsIn, long returnsOut, int transactions) {

        /** Organic net change (without grants from / returns to the reserve). */
        public long net() {
            return this.in - this.out;
        }
    }

    private Flows() {
    }

    public static Flow of(final String account, final List<String> lines, final long fromMs, final long toMs) {
        long in = 0, out = 0, grants = 0, returns = 0;
        int n = 0;
        for (final String line : lines) {
            final String[] f = line.split("\t", 9);
            if (f.length < 6) {
                continue;
            }
            final long time;
            try {
                time = Instant.parse(f[1]).toEpochMilli();
            } catch (final DateTimeParseException e) {
                continue;
            }
            if (time < fromMs || time > toMs || "SET".equals(f[2])) {
                continue;
            }
            final long amount;
            try {
                amount = Long.parseLong(f[5]);
            } catch (final NumberFormatException e) {
                continue;
            }
            final String from = f[3];
            final String to = f[4];
            if (account.equals(to) && !account.equals(from)) {
                if (RESERVE.equals(from)) {
                    grants += amount;
                } else {
                    in += amount;
                }
                n++;
            } else if (account.equals(from) && !account.equals(to)) {
                if (RESERVE.equals(to)) {
                    returns += amount;
                } else {
                    out += amount;
                }
                n++;
            }
        }
        return new Flow(account, in, out, grants, returns, n);
    }

    /**
     * Days until the balance runs out at the given organic daily net (negative = shrinking). Empty (-1) when the
     * balance is not shrinking.
     */
    public static long daysLeft(final long balance, final double dailyNet) {
        if (dailyNet >= 0 || balance <= 0) {
            return balance <= 0 && dailyNet < 0 ? 0 : -1;
        }
        return (long) Math.floor(balance / -dailyNet);
    }
}
