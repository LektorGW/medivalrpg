// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

/**
 * One line of the country treasury history shown by {@code /kraj skarbiec}.
 *
 * @param time   epoch millis
 * @param type   operation key (see {@code messages.treasury-op.<type>}), e.g. {@code deposit}, {@code upkeep}
 * @param amount copper; positive = income, negative = expense
 * @param actor  player / source name (may be empty)
 */
public record TreasuryEntry(long time, String type, long amount, String actor) {
}
