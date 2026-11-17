// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import java.util.List;

/**
 * Policy of the labour share of construction payments (pure Java). A construction payment of a country (or of its
 * citizens) goes to {@code world:payments}; {@code basisPoints} of it is the wage of the NPC citizens who do the work.
 * The share is NOT moved at payment time — orders can be refunded up to 100 % — but accrues as pending labour of the
 * payer, is reduced by refunds to that payer and moves to {@code world:households} only after {@code settleAfterHours}
 * (see {@link LaborLedger}).
 *
 * @param basisPoints      share in 1/100 % (6000 = 60 %); 0 disables
 * @param prefixes         reason prefixes of construction payments (as written by Ludnosc)
 * @param refundPrefixes   reason prefixes of refunds of those payments
 * @param dailyCap         at most this much settles per day (0 = no cap)
 * @param targetBalance    nothing settles while households already hold this much (0 = no target)
 * @param settleAfterHours age of pending labour before it settles
 */
public record LaborShare(int basisPoints, List<String> prefixes, List<String> refundPrefixes, long dailyCap, long targetBalance,
                         double settleAfterHours) {

    public static final LaborShare OFF = new LaborShare(0, List.of(), List.of(), 0, 0, 24);
    public static final String REASON_PREFIX = "robocizna: ";
    public static final List<String> DEFAULT_PREFIXES = List.of("Ludnosc: budowa ", "Ludnosc: drogi", "Ludnosc: naprawa ",
        "Ludnosc: modernizacja ", "Ludnosc: wyrównanie terenu", "Ludnosc: rozbudowa kopalni", "Ludnosc: obywatele opłacają budowę chaty");
    public static final List<String> DEFAULT_REFUND_PREFIXES = List.of("Ludnosc: zwrot");

    public LaborShare {
        basisPoints = Math.max(0, Math.min(9_000, basisPoints));
        prefixes = prefixes == null ? List.of() : List.copyOf(prefixes);
        refundPrefixes = refundPrefixes == null ? List.of() : List.copyOf(refundPrefixes);
        dailyCap = Math.max(0, dailyCap);
        targetBalance = Math.max(0, targetBalance);
        settleAfterHours = Math.max(0, Math.min(24 * 30, settleAfterHours));
    }

    private static boolean starts(final String reason, final List<String> list) {
        if (reason == null) {
            return false;
        }
        for (final String p : list) {
            if (!p.isEmpty() && reason.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public boolean payment(final String reason) {
        return this.basisPoints > 0 && starts(reason, this.prefixes);
    }

    public boolean refund(final String reason) {
        return starts(reason, this.refundPrefixes);
    }

    /** Labour part of an amount (floor). */
    public long part(final long amount) {
        return amount <= 0 ? 0 : amount * this.basisPoints / 10_000;
    }

    public long settleAfterMillis() {
        return (long) (this.settleAfterHours * 3_600_000L);
    }
}
