// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.engine;

/**
 * Outcome of one player message.
 *
 * @param kind     what happened
 * @param text     the ruler's words (raw model text, to be cleaned before display); empty unless {@link Kind#REPLY}
 * @param requests API requests made for this message
 * @param usd      their cost
 */
public record TurnResult(Kind kind, String text, int requests, double usd) {

    public enum Kind {
        /** The ruler answered (text may be empty when it only acted). */
        REPLY,
        /** stop_reason "refusal" (after fallbacks, when enabled): the ruler declines in character. */
        REFUSED,
        /** 429 / 5xx / overloaded / timeout / cut off: "the ruler is lost in thought". */
        BUSY,
        /** Authentication or permission problem with the API key. */
        UNAVAILABLE,
        /** Daily (or monthly) budget used up. */
        BUDGET,
        /** Invalid request or unexpected error (logged). */
        ERROR
    }
}
