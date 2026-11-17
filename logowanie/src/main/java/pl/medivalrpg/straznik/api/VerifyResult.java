// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.api;

/** Result of {@link StraznikApi#verifyPassword}. Names are stable (consumers may compare {@code name()}). */
public enum VerifyResult {
    OK, WRONG, NOT_REGISTERED, UNAVAILABLE
}
