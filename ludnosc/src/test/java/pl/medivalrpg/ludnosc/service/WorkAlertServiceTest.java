// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;

class WorkAlertServiceTest {
    private final UUID ruler = new UUID(0, 1), advisor = new UUID(0, 2), citizen = new UUID(0, 3), other = new UUID(0, 4);
    private KrajInfo country(boolean npc) {
        return new KrajInfo(new UUID(0, 10), "Wieś", NamedTextColor.GOLD, ruler, "Król",
            npc ? Map.of() : Map.of(advisor, Rank.ADVISOR, citizen, Rank.CITIZEN),
            1, Set.of(), Set.of(), npc, "world", 0, 0, 0, "country:test");
    }
    @Test void sharedRulerReceivesAlertsWithoutMembership() {
        assertTrue(WorkAlertService.responsible(country(false), ruler, Set.of()));
    }
    @Test void advisorReceivesButCitizenAndOutsiderDoNot() {
        assertTrue(WorkAlertService.responsible(country(false), advisor, Set.of()));
        assertFalse(WorkAlertService.responsible(country(false), citizen, Set.of()));
        assertFalse(WorkAlertService.responsible(country(false), other, Set.of()));
    }
    @Test void npcNeedsExplicitManager() {
        assertFalse(WorkAlertService.responsible(country(true), ruler, Set.of()));
        assertTrue(WorkAlertService.responsible(country(true), other, Set.of(other)));
    }
    @Test void npcManagerHasNoImplicitAccessToPlayerCountryAlerts() {
        assertFalse(WorkAlertService.responsible(country(false), other, Set.of(other)));
    }
    @Test void recipientLimitIncludesAllCountriesAndRecoveryLines() {
        var limit = new WorkAlertService.DeliveryWindow(100);
        assertTrue(limit.acquire(100)); assertTrue(limit.acquire(101)); assertTrue(limit.acquire(102));
        for (int n = 0; n < 1000; n++) assertFalse(limit.acquire(103 + n));
        assertFalse(limit.acquire(60_099)); assertTrue(limit.acquire(60_100));
    }
    @Test void separateRecipientsHaveSeparateBudgets() {
        var first = new WorkAlertService.DeliveryWindow(0);
        var second = new WorkAlertService.DeliveryWindow(0);
        for (int n = 0; n < 3; n++) assertTrue(first.acquire(n));
        assertFalse(first.acquire(4)); assertTrue(second.acquire(4));
    }
}
