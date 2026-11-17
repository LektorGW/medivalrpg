// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

import java.util.UUID;

/**
 * A country member. The last known name is cached so offline members can be shown without lookups.
 * {@link #taxDebt()} / {@link #debtDays()} track unpaid daily taxes (economy enabled only).
 */
public final class Member {

    private final UUID uuid;
    private String name;
    private Rank rank;
    private final long joinedAt;
    private long taxDebt;
    private int debtDays;

    public Member(final UUID uuid, final String name, final Rank rank, final long joinedAt) {
        this.uuid = uuid;
        this.name = name;
        this.rank = rank;
        this.joinedAt = joinedAt;
    }

    public UUID uuid() {
        return this.uuid;
    }

    public String name() {
        return this.name;
    }

    public void name(final String name) {
        this.name = name;
    }

    public Rank rank() {
        return this.rank;
    }

    public void rank(final Rank rank) {
        this.rank = rank;
    }

    public long joinedAt() {
        return this.joinedAt;
    }

    /** Unpaid taxes in copper. */
    public long taxDebt() {
        return this.taxDebt;
    }

    public void taxDebt(final long taxDebt) {
        this.taxDebt = Math.max(0, taxDebt);
    }

    /** Consecutive daily ticks that ended with unpaid taxes (0 when the debt is cleared). */
    public int debtDays() {
        return this.debtDays;
    }

    public void debtDays(final int debtDays) {
        this.debtDays = Math.max(0, debtDays);
    }
}
