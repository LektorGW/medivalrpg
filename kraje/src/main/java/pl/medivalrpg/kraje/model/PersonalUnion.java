// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

import java.util.UUID;

/**
 * Personal union, stored on the PARTNER country (the one whose throne the monarch took). The monarch is a regular
 * member (ruler) of {@link #home()}; in the partner he is a "titular" member with the ruler rank that is NOT in the
 * player → country index (a player belongs to exactly one country). {@link #regent()} is the partner's former ruler,
 * now its advisor ("namiestnik"); null for an NPC partner, which stays AI-run.
 *
 * @param home        country of the monarch
 * @param monarch     shared ruler
 * @param monarchName cached name of the monarch
 * @param since       epoch millis of the formation
 * @param regent      former ruler of the partner (null when none)
 * @param regentName  cached name of the regent ("" when none)
 */
public record PersonalUnion(UUID home, UUID monarch, String monarchName, long since, UUID regent, String regentName) {

    public PersonalUnion {
        monarchName = monarchName == null ? "" : monarchName;
        regentName = regentName == null ? "" : regentName;
    }
}
