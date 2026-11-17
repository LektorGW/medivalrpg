// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

import java.util.UUID;

/**
 * Vassalage of the country that holds it (the vassal): who its overlord is, until when, the tribute terms frozen at
 * capitulation and the unpaid tribute. Mutated only on the main thread ({@code CountryManager}, daily settlement).
 */
public final class Vassalage {

    private final UUID overlord;
    private final long since;
    private final long until;
    private final double tributeShare;
    private final long tributeMin;
    private long debt;
    private int debtDays;
    private long tributePaid;

    public Vassalage(final UUID overlord, final long since, final long until, final double tributeShare, final long tributeMin) {
        this.overlord = overlord;
        this.since = since;
        this.until = until;
        this.tributeShare = Math.max(0, Math.min(1, tributeShare));
        this.tributeMin = Math.max(0, tributeMin);
    }

    public UUID overlord() {
        return this.overlord;
    }

    /** Epoch millis of the capitulation. */
    public long since() {
        return this.since;
    }

    /** Epoch millis when the vassalage ends by itself. */
    public long until() {
        return this.until;
    }

    public double tributeShare() {
        return this.tributeShare;
    }

    public long tributeMin() {
        return this.tributeMin;
    }

    /** Unpaid tribute in copper. */
    public long debt() {
        return this.debt;
    }

    public void debt(final long debt) {
        this.debt = Math.max(0, debt);
    }

    /** Consecutive daily settlements that ended with unpaid tribute. */
    public int debtDays() {
        return this.debtDays;
    }

    public void debtDays(final int days) {
        this.debtDays = Math.max(0, days);
    }

    /** Tribute paid so far (copper). */
    public long tributePaid() {
        return this.tributePaid;
    }

    public void tributePaid(final long paid) {
        this.tributePaid = Math.max(0, paid);
    }

    public boolean expired(final long now) {
        return now >= this.until;
    }

    /** Millis left (0 when over). */
    public long remainingMillis(final long now) {
        return Math.max(0, this.until - now);
    }
}
