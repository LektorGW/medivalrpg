// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

import java.util.UUID;
import pl.medivalrpg.kraje.model.Rank;

/**
 * Snapshot of one player member of a country (see {@link KrajeApi#members}).
 *
 * @param uuid     player UUID
 * @param name     last known name
 * @param rank     rank in the country
 * @param joinedAt epoch millis
 * @param taxDebt  unpaid taxes in copper
 * @param debtDays consecutive daily ticks that ended with unpaid taxes
 */
public record MemberInfo(UUID uuid, String name, Rank rank, long joinedAt, long taxDebt, int debtDays) {
}
