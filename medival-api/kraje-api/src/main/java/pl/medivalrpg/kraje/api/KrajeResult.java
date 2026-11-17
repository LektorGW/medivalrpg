// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

import java.util.UUID;

/**
 * Result of a mutating {@link KrajeApi} call.
 *
 * @param status    outcome
 * @param countryId affected / created country id (may be null on failure)
 * @param message   short Polish explanation for logs or admins (never null; empty on success)
 */
public record KrajeResult(Status status, UUID countryId, String message) {

    public enum Status {
        OK,
        NOT_FOUND,
        NAME_TAKEN,
        INVALID,
        TOO_CLOSE,
        ALREADY_CLAIMED,
        NOT_ADJACENT,
        LIMIT_REACHED,
        INSUFFICIENT_FUNDS,
        NOT_AT_WAR,
        NO_PROPOSAL,
        DENIED
    }

    public boolean ok() {
        return this.status == Status.OK;
    }

    public static KrajeResult ok(final UUID countryId) {
        return new KrajeResult(Status.OK, countryId, "");
    }

    public static KrajeResult fail(final Status status, final String message) {
        return new KrajeResult(status, null, message == null ? "" : message);
    }
}
