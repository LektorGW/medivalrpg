// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.model.*;

class DiplomacyPermissionsTest {
    @Test
    void actualMembershipAndRankAreRequiredAndTitularPowerEndsWithUnion() {
        final CountryManager manager = new CountryManager(Logger.getAnonymousLogger());
        manager.loadAll(List.of(), 0L);
        final UUID king = UUID.randomUUID(), duke = UUID.randomUUID(), citizen = UUID.randomUUID();
        final ChunkPos a = new ChunkPos("world", 0, 0), b = new ChunkPos("world", 10, 0);
        final SpawnPoint spot = new SpawnPoint("world", 0, 64, 0, 0, 0);
        final Country home = manager.create(king, "Krol", "A", 1, a, spot, ClaimCause.PLAYER);
        final Country partner = manager.create(duke, "Ksiaze", "B", 2, b, spot, ClaimCause.PLAYER);
        manager.addMember(partner, citizen, "Obywatel", Rank.CITIZEN);
        assertFalse(KrajCommand.hasRank(partner, king, Rank.CITIZEN));
        assertTrue(KrajCommand.hasRank(partner, citizen, Rank.CITIZEN));
        assertFalse(KrajCommand.hasRank(partner, citizen, Rank.ADVISOR));
        manager.formUnion(home, partner, new PersonalUnion(home.id(), king, "Krol", 1L, duke, "Ksiaze"));
        assertTrue(KrajCommand.hasRank(partner, king, Rank.RULER));
        assertFalse(KrajCommand.hasRank(partner, duke, Rank.RULER));
        assertTrue(KrajCommand.hasRank(partner, duke, Rank.ADVISOR));
        manager.endUnion(partner);
        assertFalse(KrajCommand.hasRank(partner, king, Rank.CITIZEN));
        assertTrue(KrajCommand.hasRank(partner, duke, Rank.RULER));
    }
}
