// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.api;

/**
 * The current server-wide season (see {@link LudnoscApi#season()}). Seasons follow real time: one season lasts
 * {@code daysPerSeason} real days, the cycle is spring → summer → autumn → winter.
 *
 * @param id             {@code wiosna}, {@code lato}, {@code jesien} or {@code zima}
 * @param name           Polish display name, e.g. {@code "Jesień"}
 * @param index          0 spring, 1 summer, 2 autumn, 3 winter
 * @param day            day of the season, 1-based
 * @param daysPerSeason  length of one season in (whole) real days
 * @param startedAt      epoch millis when this season started
 * @param endsAt         epoch millis when the next season starts
 * @param foodMultiplier production multiplier of farms / farmers in this season (before poor harvests)
 */
public record SeasonInfo(String id, String name, int index, int day, int daysPerSeason, long startedAt, long endsAt,
                         double foodMultiplier) {
}
