// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import pl.medivalrpg.firmy.model.Firm;

/**
 * Hooks of the later stages (contracts and loans, workshops) into the stage-1 company life cycle. Every method has a
 * neutral default, so stage 1 runs without any extension.
 */
public interface Extension {

    /**
     * A creditor paid in a liquidation before the salary arrears (loans: the lender has priority right after the
     * contract deposits). {@code paid} receives the copper actually paid (≤ amount) once the money moved.
     */
    record Claim(String to, long amount, String label, LongConsumer paid) {
    }

    /** Net income (after tax) reached the company account; loans repay from it. */
    default void onIncome(final Firm firm, final long net, final String source) {
    }

    /** First step of a liquidation: settle open contracts (deposits back by their rules). */
    default void beforeLiquidation(final Firm firm) {
    }

    /** Creditors with priority over salary arrears (in order). */
    default List<Claim> liquidationClaims(final Firm firm) {
        return List.of();
    }

    default void afterLiquidation(final Firm firm) {
    }

    /** The company became insolvent (unpaid salaries for N days): NPC wages stop, workers return. */
    default void onInsolvent(final Firm firm) {
    }

    /** Daily settlement (after salaries). */
    default void daily(final long now) {
    }

    /** Reason why the company may not be liquidated voluntarily right now (e.g. an active loan), empty = allowed. */
    default Optional<String> blocksVoluntaryLiquidation(final Firm firm) {
        return Optional.empty();
    }

    /** Copper of the company balance that is reserved (e.g. loan instalment due) and may not be paid out as dividend. */
    default long reserved(final Firm firm) {
        return 0L;
    }
}
