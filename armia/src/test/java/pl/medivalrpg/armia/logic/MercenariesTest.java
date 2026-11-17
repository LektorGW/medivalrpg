// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mercenaries: cost and wage, contract expiry, leaving when unpaid, sub-limit, AI hiring; default skins. */
class MercenariesTest {

    private static final Mercenaries.Rules R = Mercenaries.Rules.defaults();
    private static final long H = 3_600_000L;

    @Test
    void costAndWageMultipliers() {
        assertEquals(750, Mercenaries.cost(R, 300), "piechota 300 × 2,5");
        assertEquals(40, Mercenaries.wage(R, 20), "żołd 20 × 2");
        assertEquals(375, Mercenaries.extendCost(R, 750));
        assertEquals(0, Mercenaries.cost(R, -5));
    }

    @Test
    void contractExpiresAndExtends() {
        final long now = 1000 * H;
        final long end = Mercenaries.contractEnd(R, now);
        assertEquals(now + 72 * H, end);
        assertFalse(Mercenaries.expired(end, now + 71 * H));
        assertTrue(Mercenaries.expired(end, end));
        assertFalse(Mercenaries.expired(0, now), "0 = regular soldier");
        assertEquals(end + 72 * H, Mercenaries.extended(R, end, now), "from the current end");
        assertEquals(now + 100 * H + 72 * H, Mercenaries.extended(R, end, now + 100 * H), "already expired: from now");
    }

    @Test
    void unpaidMercenariesLeaveAtOnceRegularSoldiersOnlyLoseMorale() {
        final UUID merc = UUID.randomUUID();
        final UUID regular = UUID.randomUUID();
        final UUID paidMerc = UUID.randomUUID();
        // 60 copper: pays one wage of 40 (lowest morale first), nobody else
        final Payroll.Result r = Payroll.pay(List.of(new Payroll.Soldier(paidMerc, 40, 50), new Payroll.Soldier(merc, 40, 100),
            new Payroll.Soldier(regular, 20, 100)), 60, Payroll.Rules.defaults());
        assertTrue(r.paidIds().contains(paidMerc));
        final List<UUID> leave = Mercenaries.unpaid(r, Set.of(merc, paidMerc));
        assertEquals(List.of(merc), leave.stream().filter(x -> x.equals(merc)).toList());
        assertFalse(leave.contains(paidMerc));
        assertFalse(leave.contains(regular), "regular soldiers follow the morale rules");
        assertFalse(r.deserters().contains(merc), "with morale 100 a regular soldier would not desert yet");
    }

    @Test
    void subLimitAndArmyLimit() {
        assertEquals(12, Mercenaries.maxMercenaries(R, 40), "30% of 40");
        assertEquals(5, Mercenaries.allowedHire(R, 5, 10, 0, 40, 10, 150));
        assertEquals(2, Mercenaries.allowedHire(R, 5, 10, 10, 40, 10, 150), "sub-limit 12, 10 hired");
        assertEquals(1, Mercenaries.allowedHire(R, 5, 39, 0, 40, 39, 150), "army limit");
        assertEquals(0, Mercenaries.allowedHire(R, 5, 10, 0, 40, 150, 150), "global cap");
    }

    @Test
    void defectOnlyWhenEnabledAndBroke() {
        assertFalse(Mercenaries.mayDefect(R, 0));
        final Mercenaries.Rules on = new Mercenaries.Rules(2.5, 2, 72, 0.5, 0.3, true, 100, 10, 5000, 2);
        assertTrue(Mercenaries.mayDefect(on, 50));
        assertFalse(Mercenaries.mayDefect(on, 500));
    }

    @Test
    void aiHiresOnlyInCrisisWithFewCitizensAndMoney() {
        assertEquals(2, Mercenaries.aiHire(R, true, 5, 20_000, 1000, 750, 10, 0, 12));
        assertEquals(0, Mercenaries.aiHire(R, false, 5, 20_000, 1000, 750, 10, 0, 12), "no crisis");
        assertEquals(0, Mercenaries.aiHire(R, true, 30, 20_000, 1000, 750, 10, 0, 12), "enough citizens to conscript");
        assertEquals(0, Mercenaries.aiHire(R, true, 5, 3_000, 1000, 750, 10, 0, 12), "poor");
        assertEquals(1, Mercenaries.aiHire(R, true, 5, 20_000, 1000, 750, 10, 11, 12), "sub-limit");
        assertEquals(1, Mercenaries.aiHire(R, true, 5, 6_000, 5_000, 750, 10, 0, 12), "money above the reserve");
    }

    @Test
    void defaultSkinsAndNickLists() {
        final UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertTrue(SkinChoice.isRandom("losowy"));
        assertTrue(SkinChoice.isRandom(""));
        assertTrue(SkinChoice.isRandom(null));
        assertEquals("Notch", SkinChoice.pick("Notch", a).orElseThrow());
        final List<String> picks = List.of("Alfa", "Beta", "Gamma");
        final String chosen = SkinChoice.pick("Alfa, Beta,Gamma", a).orElseThrow();
        assertTrue(picks.contains(chosen));
        assertEquals(chosen, SkinChoice.pick("Alfa, Beta,Gamma", a).orElseThrow(), "stable for the same id");
        assertEquals(List.of("Alfa"), SkinChoice.names("losowy, Alfa, zły nick!"));
    }
}
