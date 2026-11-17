// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.permit;

import java.util.Optional;
import java.util.UUID;

/**
 * HOOK for the embassies being added to Kraje by another module. Kolej does not depend on it yet.
 *
 * <p>To wire it once Kraje exposes embassies (proposed additive API in {@code medival-api/kraje-api}):
 * <pre>{@code
 * // KrajeApi (since API 1.5):
 * default Optional<Boolean> hasEmbassy(UUID host, UUID guest) { return Optional.empty(); }
 * }</pre>
 * then return {@code kraje.hasEmbassy(target, home)} here. {@link #UNKNOWN} reports "no information", which only
 * matters when {@code zgody.wymagaj-ambasady: true}.</p>
 */
@FunctionalInterface
public interface EmbassyCheck {

    EmbassyCheck UNKNOWN = (host, guest) -> Optional.empty();

    /** Whether {@code guest} has an embassy in {@code host}; empty when unknown. */
    Optional<Boolean> hasEmbassy(UUID host, UUID guest);
}
