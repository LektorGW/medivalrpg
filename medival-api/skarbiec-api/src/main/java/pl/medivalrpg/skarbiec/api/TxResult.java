// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.api;

/** Outcome of an account operation in {@link SkarbiecApi}. */
public enum TxResult {
    OK,
    INSUFFICIENT_FUNDS,
    /** Amount was zero or negative, or the two accounts of a transfer were the same. */
    INVALID_AMOUNT,
    /** Account id is malformed. */
    INVALID_ACCOUNT;

    public boolean ok() {
        return this == OK;
    }
}
