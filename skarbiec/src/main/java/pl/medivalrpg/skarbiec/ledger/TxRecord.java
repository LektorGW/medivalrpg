// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import java.time.Instant;

/**
 * One committed account operation. Written as a single tab-separated line to the journal and the daily
 * transaction log; replaying the "balance after" values in sequence order restores the ledger.
 *
 * @param seq       strictly increasing sequence number
 * @param time      epoch millis
 * @param type      operation
 * @param from      debited account ({@code null} for deposit/set)
 * @param to        credited account ({@code null} for withdraw)
 * @param amount    amount moved (for {@link Type#SET}: the new balance)
 * @param fromAfter balance of {@code from} after the operation ({@code null} when no {@code from})
 * @param toAfter   balance of {@code to} after the operation ({@code null} when no {@code to})
 * @param reason    free text (sanitised: no tabs/new lines)
 */
public record TxRecord(long seq, long time, Type type, String from, String to, long amount,
                       Long fromAfter, Long toAfter, String reason) {

    public enum Type {
        DEPOSIT, WITHDRAW, TRANSFER, SET
    }

    private static final String NONE = "-";

    public boolean involves(final String account) {
        return account.equals(this.from) || account.equals(this.to);
    }

    public String encode() {
        return this.seq + "\t" + Instant.ofEpochMilli(this.time) + "\t" + this.type + "\t"
            + orNone(this.from) + "\t" + orNone(this.to) + "\t" + this.amount + "\t"
            + orNone(this.fromAfter) + "\t" + orNone(this.toAfter) + "\t" + sanitize(this.reason);
    }

    /** Parses a line written by {@link #encode()}; {@code null} for a malformed (e.g. torn) line. */
    public static TxRecord decode(final String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        final String[] p = line.split("\t", -1);
        if (p.length != 9) {
            return null;
        }
        try {
            return new TxRecord(Long.parseLong(p[0]), Instant.parse(p[1]).toEpochMilli(), Type.valueOf(p[2]),
                none(p[3]), none(p[4]), Long.parseLong(p[5]), longOrNull(p[6]), longOrNull(p[7]), p[8]);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    public static String sanitize(final String reason) {
        if (reason == null) {
            return "";
        }
        final String clean = reason.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }

    private static String orNone(final Object o) {
        return o == null ? NONE : o.toString();
    }

    private static String none(final String s) {
        return NONE.equals(s) ? null : s;
    }

    private static Long longOrNull(final String s) {
        return NONE.equals(s) ? null : Long.valueOf(s);
    }
}
