// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

import java.util.UUID;

/**
 * Vassalage of one country (see {@link KrajeApi#getVassalage}). Immutable snapshot, amounts in copper.
 *
 * @param vassal        the vassal (the country that capitulated)
 * @param overlord      its overlord (the winner)
 * @param since         epoch millis when the vassalage started
 * @param until         epoch millis when it ends by itself
 * @param tributeShare  share of the vassal's daily tax income (player and NPC taxes) paid as tribute, 0..1
 * @param tributeMin    minimum daily tribute
 * @param dailyTribute  tribute expected at the next daily settlement (estimate from the current taxes)
 * @param debt          unpaid tribute
 * @param debtDays      consecutive daily settlements that ended with unpaid tribute (0 = no debt)
 * @param tributePaid   tribute paid so far
 */
public record VassalInfo(UUID vassal, UUID overlord, long since, long until, double tributeShare, long tributeMin,
                         long dailyTribute, long debt, int debtDays, long tributePaid) {

    /** True when the vassal owes tribute. */
    public boolean inDebt() {
        return this.debt > 0;
    }
}
