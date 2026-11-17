// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmType;
import pl.medivalrpg.firmy.model.FirmsData;
import pl.medivalrpg.firmy.model.Role;

/**
 * Fixes 2026-10-31: co-founding (zrzutka) for players who cannot pay 50 zł alone, the readiness checklist of the start
 * screen and a frozen company that no longer drifts into arrears and forced liquidation. Money only moves.
 */
class CoFoundingTest {

    private final TestKit k = new TestKit();
    private final UUID land = this.k.world.country("Watykan", false);
    private final UUID npc = this.k.world.country("Litwa", true);
    private final UUID ruler = this.k.world.player("Radek");
    private final UUID jan = this.k.world.player("Jan");
    private final UUID ola = this.k.world.player("Ola");
    private final UUID ewa = this.k.world.player("Ewa");

    CoFoundingTest() {
        this.k.world.join(this.ruler, this.land, 3);
        this.k.world.join(this.jan, this.land, 1);
        this.k.bank.give("player:" + this.ruler, 100_000);
        this.k.bank.give("player:" + this.jan, 1_500);
        this.k.bank.give("player:" + this.ola, 2_000);
        this.k.bank.give("player:" + this.ewa, 3_000);
    }

    private void poolIntact() {
        assertEquals(TestKit.POOL, this.k.bank.sum());
        assertTrue(this.k.bank.noNegative());
    }

    private long bal(final UUID u) {
        return this.k.bank.balance("player:" + u);
    }

    @Test
    void foundingAloneWithoutMoneyPointsToTheGathering() {
        final Outcome o = this.k.service.registry.found(this.jan, "Jan", "Piekarnia", FirmType.CECH, this.npc, 2_500, false);
        assertFalse(o.ok());
        assertTrue(o.message().contains("/firma zrzutka zaloz"), o.message());
        assertTrue(o.message().contains("masz"), "tells how much the player has");
    }

    @Test
    void gatheringInAnNpcSeatFoundsTheCompanyWithSharesByContribution() {
        final Outcome g = this.k.service.registry.gather(this.jan, "Jan", "Piekarnia Wspólna", FirmType.CECH, this.npc, 1_000);
        assertTrue(g.ok(), g.message());
        final FirmsData.Application a = this.k.data.applications.get(g.id());
        assertEquals("ZBIORKA", a.state);
        assertEquals(1_000, this.k.bank.balance(a.account()));
        assertFalse(this.k.service.registry.decide(a.id, this.ruler, true, true).ok(), "nobody approves an unfinished gathering");
        assertTrue(this.k.service.registry.contribute(a.id, this.ola, "Ola", 2_000).ok());
        final Outcome done = this.k.service.registry.contribute("piekarnia wspolna", this.ewa, "Ewa", 2_000);
        assertTrue(done.ok(), done.message());
        final Firm f = this.k.ctx.firm(a.id).orElseThrow();
        assertEquals(Role.MISTRZ, f.role(this.jan).orElseThrow());
        assertEquals(Role.WSPOLNIK, f.role(this.ola).orElseThrow());
        assertEquals(2_000, f.member(this.jan).orElseThrow().shareBp);
        assertEquals(4_000, f.member(this.ola).orElseThrow().shareBp);
        assertEquals(4_000, f.member(this.ewa).orElseThrow().shareBp);
        assertEquals(10_000, f.totalShares());
        assertEquals(2_500, this.k.bank.balance("country:" + this.npc), "fee to the seat");
        assertEquals(2_500, this.k.bank.balance(f.account()), "the rest is capital");
        assertEquals(0, this.k.bank.balance(a.account()));
        assertTrue(this.k.data.applications.isEmpty());
        this.poolIntact();
    }

    @Test
    void gatheringInAPlayerCountryGoesToTheRulerOnlyWhenComplete() {
        final Outcome g = this.k.service.registry.gather(this.jan, "Jan", "Kompania Tybru", FirmType.KOMPANIA, this.land, 1_000);
        assertTrue(g.ok(), g.message());
        assertTrue(this.k.service.registry.contribute(g.id(), this.ewa, "Ewa", 3_000).ok());
        assertEquals("ZBIORKA", this.k.data.applications.get(g.id()).state);
        assertTrue(this.k.service.registry.contribute(g.id(), this.ola, "Ola", 1_000).ok());
        assertEquals("OPEN", this.k.data.applications.get(g.id()).state);
        assertTrue(this.k.messages.sent.stream().anyMatch(m -> m.startsWith("leaders " + this.land)), "the ruler is asked");
        assertTrue(this.k.service.registry.decide(g.id(), this.ruler, false, true).ok());
        final Firm f = this.k.ctx.firm(g.id()).orElseThrow();
        assertEquals(3, f.members.size());
        assertEquals(6_000, f.member(this.ewa).orElseThrow().shareBp);
        this.poolIntact();
    }

    @Test
    void expiredGatheringReturnsEveryContributionExactly() {
        final long jan0 = this.bal(this.jan);
        final long ola0 = this.bal(this.ola);
        final Outcome g = this.k.service.registry.gather(this.jan, "Jan", "Cech Szewców", FirmType.CECH, this.npc, 700);
        assertTrue(g.ok(), g.message());
        assertTrue(this.k.service.registry.contribute(g.id(), this.ola, "Ola", 1_333).ok());
        this.k.clock.plusHours(73);
        this.k.service.registry.expire();
        assertTrue(this.k.data.applications.isEmpty());
        assertEquals(jan0, this.bal(this.jan));
        assertEquals(ola0, this.bal(this.ola));
        this.poolIntact();
    }

    @Test
    void contributorLeavesAndFounderCancels() {
        final long ola0 = this.bal(this.ola);
        final long jan0 = this.bal(this.jan);
        final Outcome g = this.k.service.registry.gather(this.jan, "Jan", "Cech Kowali", FirmType.CECH, this.npc, 1_000);
        assertTrue(this.k.service.registry.contribute(g.id(), this.ola, "Ola", 1_500).ok());
        assertTrue(this.k.service.registry.leaveGathering(this.ola).ok());
        assertEquals(ola0, this.bal(this.ola));
        assertFalse(this.k.data.applications.get(g.id()).contributions.containsKey(this.ola.toString()));
        assertTrue(this.k.service.registry.withdraw(this.jan).ok());
        assertEquals(jan0, this.bal(this.jan));
        assertTrue(this.k.data.applications.isEmpty());
        this.poolIntact();
    }

    @Test
    void contributionRulesAreChecked() {
        final Outcome g = this.k.service.registry.gather(this.jan, "Jan", "Cech Cieśli", FirmType.CECH, this.npc, 1_000);
        assertFalse(this.k.service.registry.contribute(g.id(), this.ola, "Ola", 100).ok(), "below the minimum contribution");
        this.k.world.minutes.put(this.ewa, 10L);
        assertFalse(this.k.service.registry.contribute(g.id(), this.ewa, "Ewa", 1_000).ok(), "5 h of play also for co-founders");
        assertFalse(this.k.service.registry.gather(this.ola, "Ola", "X", FirmType.CECH, this.npc, 1_000).ok(), "name too short");
        assertFalse(this.k.service.registry.gather(this.ola, "Ola", "Cech Nowy", FirmType.CECH, this.npc, 100).ok());
        // a pending contribution counts as a membership
        assertTrue(this.k.service.registry.contribute(g.id(), this.ola, "Ola", 1_000).ok());
        this.k.settings.maxMemberships = 1;
        final Outcome other = this.k.service.registry.gather(this.ola, "Ola", "Cech Inny", FirmType.CECH, this.npc, 500);
        assertFalse(other.ok());
        assertTrue(other.message().contains("najwyżej do 1"), other.message());
        this.poolIntact();
    }

    @Test
    void enoughMoneyInAGatheringIsANormalFounding() {
        final Outcome o = this.k.service.registry.gather(this.ruler, "Radek", "Watykański Chleb", FirmType.CECH, this.land, 6_000);
        assertTrue(o.ok(), o.message());
        final Firm f = this.k.ctx.firm(o.id()).orElseThrow();
        assertEquals(3_500, this.k.bank.balance(f.account()), "6 000 minus the 2 500 fee");
        assertEquals(10_000, f.member(this.ruler).orElseThrow().shareBp);
        this.poolIntact();
    }

    @Test
    void crashDuringAContributionIsSettledFromTheEscrow() {
        final Outcome g = this.k.service.registry.gather(this.jan, "Jan", "Cech Młynarzy", FirmType.CECH, this.npc, 1_000);
        final FirmsData.Application a = this.k.data.applications.get(g.id());
        // money moved, record not finished
        a.pending = "+" + this.ola + "|1500";
        a.contributorNames.put(this.ola.toString(), "Ola");
        this.k.bank.transfer("player:" + this.ola, a.account(), 1_500, "test");
        this.k.service.registry.reconcile();
        assertNull(a.pending);
        assertEquals(1_500, a.contributions.get(this.ola.toString()));
        // nothing moved
        a.pending = "+" + this.ewa + "|900";
        this.k.service.registry.reconcile();
        assertNull(a.pending);
        assertFalse(a.contributions.containsKey(this.ewa.toString()));
        assertEquals(2_500, this.k.bank.balance(a.account()));
        this.poolIntact();
    }

    @Test
    void gatheringWithoutItsContributionListIsHeldForTheAdministrator() {
        final Outcome g = this.k.service.registry.gather(this.jan, "Jan", "Cech Bednarzy", FirmType.CECH, this.npc, 1_000);
        assertTrue(this.k.service.registry.contribute(g.id(), this.ola, "Ola", 1_500).ok());
        final FirmsData.Application a = this.k.data.applications.get(g.id());
        // an older jar rewrote firmy.json without the new fields
        a.contributions = null;
        a.contributorNames = null;
        this.k.service.registry.reconcile();
        assertEquals(Registry.LOST, a.pending);
        this.k.clock.plusHours(100);
        this.k.service.registry.expire();
        assertTrue(this.k.data.applications.containsKey(a.id), "never refunded to the founder alone automatically");
        assertFalse(this.k.service.registry.withdraw(this.jan).ok());
        assertEquals(2_500, this.k.bank.balance(a.account()));
        assertEquals(1, this.k.service.registry.coFounded().size());
        this.poolIntact();
    }

    @Test
    void contributionsSurviveARollbackRoundTripThroughTheBackupFile() {
        final Outcome g = this.k.service.registry.gather(this.jan, "Jan", "Cech Tkaczy", FirmType.CECH, this.npc, 1_000);
        assertTrue(this.k.service.registry.contribute(g.id(), this.ola, "Ola", 1_500).ok());
        final FirmsData.Application a = this.k.data.applications.get(g.id());
        final var backup = FirmsData.GatheringBackup.of(this.k.data.applications);
        // the 29.10 jar rewrote firmy.json without the co-founding fields
        a.contributions = null;
        a.contributorNames = null;
        a.autoApprove = false;
        assertEquals(1, FirmsData.GatheringBackup.restore(this.k.data.applications, backup));
        this.k.service.registry.reconcile();
        assertNull(a.pending);
        final Outcome done = this.k.service.registry.contribute(g.id(), this.ewa, "Ewa", 2_500);
        assertTrue(done.ok(), done.message());
        final Firm f = this.k.ctx.firm(g.id()).orElseThrow();
        assertEquals(3, f.members.size());
        assertEquals(3_000, f.member(this.ola).orElseThrow().shareBp);
        assertEquals(5_000, f.member(this.ewa).orElseThrow().shareBp);
        this.poolIntact();
    }

    @Test
    void adminMayCloseAGathering() {
        final long jan0 = this.bal(this.jan);
        final Outcome g = this.k.service.registry.gather(this.jan, "Jan", "Cech Rybaków", FirmType.CECH, this.npc, 1_000);
        assertTrue(this.k.service.registry.decide(g.id(), null, true, false).ok());
        assertEquals(jan0, this.bal(this.jan));
        this.poolIntact();
    }

    @Test
    void readinessListsWhatIsMissing() {
        final List<Registry.Check> wild = this.k.service.registry.readiness(this.jan, null);
        assertTrue(wild.stream().anyMatch(c -> !c.ok() && c.text().contains("dziczy")));
        final List<Registry.Check> here = this.k.service.registry.readiness(this.ruler, this.land);
        assertTrue(here.stream().allMatch(Registry.Check::ok), here.toString());
        assertTrue(here.stream().anyMatch(c -> c.text().contains("Władcą")));
        this.k.world.minutes.put(this.ola, 30L);
        assertTrue(this.k.service.registry.readiness(this.ola, this.npc).stream().anyMatch(c -> !c.ok() && c.text().startsWith("Czas gry")));
    }

    @Test
    void frozenCompanyNeitherRunsIntoArrearsNorIsLiquidated() {
        final Outcome o = this.k.service.registry.found(this.ruler, "Radek", "Mennica", FirmType.CECH, this.land, 2_500, false);
        final Firm f = this.k.ctx.firm(o.id()).orElseThrow();
        f.member(this.ruler).orElseThrow().salary = 5_000;
        this.k.service.treasury.freeze(f, true, "audyt");
        for (int d = 0; d < 70; d++) {
            this.k.clock.plusHours(24);
            this.k.service.treasury.daily();
        }
        assertTrue(f.active(), "no forced liquidation while frozen");
        assertEquals(0, f.totalArrears());
        assertEquals(0, f.unpaidDays);
        assertEquals(0, f.dormantSince);
        this.k.service.treasury.freeze(f, false, "sprawdzone");
        this.k.clock.plusHours(24);
        this.k.service.treasury.daily();
        assertEquals(1, f.unpaidDays, "the clock runs again after unfreezing");
        this.poolIntact();
    }
}
