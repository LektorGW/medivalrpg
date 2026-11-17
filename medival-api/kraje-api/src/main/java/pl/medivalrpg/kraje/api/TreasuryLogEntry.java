// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

/**
 * One line of a country treasury history (newest first in {@link KrajeApi#treasuryLog}).
 *
 * @param time   epoch millis
 * @param type   operation key, e.g. {@code deposit}, {@code withdraw}, {@code claim}, {@code war}, {@code tax}, {@code upkeep}
 * @param amount copper; positive = income, negative = expense
 * @param actor  player / source name (may be empty)
 */
public record TreasuryLogEntry(long time, String type, long amount, String actor) {
}
