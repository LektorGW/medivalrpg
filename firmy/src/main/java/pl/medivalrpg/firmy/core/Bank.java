// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * The only way Firmy moves money: transfers between existing accounts in Skarbiec. Never deposit/withdraw, so the
 * closed pool of 100 000 000 md is never changed by a company.
 */
public interface Bank {

    /** One move of a batch. */
    record Leg(String from, String to, long amount, String reason) {
        public Leg {
            if (amount <= 0) {
                throw new IllegalArgumentException("Kwota musi być dodatnia");
            }
        }
    }

    long balance(String account);

    TxResult transfer(String from, String to, long amount, String reason);

    /**
     * All legs or none. Skarbiec's atomic batch when available; otherwise legs one by one with compensation of the
     * already done legs on the first failure.
     */
    TxResult transferAll(List<Leg> legs);

    /** Completes when everything committed so far is on disk in the bank journal. */
    CompletableFuture<Void> durable();

    String format(long copper);
}
