// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

import java.util.UUID;

/**
 * A personal union (see {@link KrajeApi#getUnion}): two sovereign countries share one ruler. Each keeps its own
 * territory, treasury, citizens and laws. Immutable snapshot. Since API 1.5.
 *
 * @param home        the country the shared ruler (monarch) is a regular member of
 * @param partner     the other country; the monarch holds its ruler title as well
 * @param monarch     the shared ruler (player UUID)
 * @param monarchName last known name of the monarch
 * @param since       epoch millis when the union was formed
 * @param regent      former ruler of {@code partner}, now its advisor and "namiestnik" (null for an NPC partner or when unknown)
 * @param regentName  name of the regent (empty when none)
 * @param breakableAt epoch millis from which either side may break the union by command
 */
public record UnionInfo(UUID home, UUID partner, UUID monarch, String monarchName, long since, UUID regent,
                        String regentName, long breakableAt) {

    /** Whether {@code country} is one of the two countries. */
    public boolean involves(final UUID country) {
        return this.home.equals(country) || this.partner.equals(country);
    }

    /** The other country of the union ({@code null} when {@code country} is not part of it). */
    public UUID other(final UUID country) {
        if (this.home.equals(country)) {
            return this.partner;
        }
        return this.partner.equals(country) ? this.home : null;
    }
}
