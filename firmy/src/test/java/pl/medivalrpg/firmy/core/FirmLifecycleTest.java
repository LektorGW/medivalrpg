// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmType;
import pl.medivalrpg.firmy.model.Role;

class FirmLifecycleTest {

    private final TestKit k = new TestKit();
    private final UUID player = this.k.world.country("Mazowsze", false);
    private final UUID npc = this.k.world.country("Księstwo Wschodnie", true);
    private final UUID ruler = this.k.world.player("Bolko");
    private final UUID anna = this.k.world.player("Anna");
    private final UUID jan = this.k.world.player("Jan");
    private final UUID ola = this.k.world.player("Ola");

    FirmLifecycleTest() {
        this.k.world.join(this.ruler, this.player, 3);
        this.k.world.join(this.anna, this.player, 1);
        for (final UUID u : new UUID[] {this.ruler, this.anna, this.jan, this.ola}) {
            this.k.bank.give("player:" + u, 1_000_000);
        }
    }

    private Firm found(final UUID who, final String name, final UUID seat) {
        final Outcome o = this.k.service.registry.found(who, this.k.world.playerName(who), name, FirmType.CECH, seat, 10_000, false);
        assertTrue(o.ok(), o.message());
        return this.k.ctx.firm(o.id()).orElseThrow();
    }

    private void poolIntact() {
        assertEquals(TestKit.POOL, this.k.bank.sum());
        assertTrue(this.k.bank.noNegative());
    }

    @Test
    void npcSeatIsApprovedAutomaticallyAndFeeGoesToTheTreasury() {
        final Firm f = this.found(this.jan, "Kowale Wschodu", this.npc);
        assertEquals(10_000, this.k.bank.balance(f.account()));
        assertEquals(2_500, this.k.bank.balance("country:" + this.npc));
        assertEquals(1_000_000 - 12_500, this.k.bank.balance("player:" + this.jan));
        assertEquals(Role.MISTRZ, f.role(this.jan).orElseThrow());
        assertEquals(10_000, f.totalShares());
        this.poolIntact();
    }

    @Test
    void playerCountryNeedsTheRulerAndRefundsOnRejection() {
        final Outcome o = this.k.service.registry.found(this.anna, "Anna", "Piekarnia Anny", FirmType.CECH, this.player, 5_000, false);
        assertTrue(o.ok());
        assertTrue(this.k.ctx.data.firms.isEmpty());
        assertEquals(7_500, this.k.bank.balance("firma-wniosek:" + o.id()));
        assertFalse(this.k.service.registry.decide(o.id(), this.anna, false, true).ok(), "a citizen cannot approve");
        assertTrue(this.k.service.registry.decide(o.id(), this.ruler, false, false).ok());
        assertEquals(0, this.k.bank.balance("firma-wniosek:" + o.id()));
        assertEquals(1_000_000, this.k.bank.balance("player:" + this.anna));
        this.poolIntact();
    }

    @Test
    void approvalByRulerCreatesTheFirm() {
        final Outcome o = this.k.service.registry.found(this.anna, "Anna", "Piekarnia Anny", FirmType.CECH, this.player, 5_000, false);
        assertTrue(this.k.service.registry.decide(o.id(), this.ruler, false, true).ok());
        final Firm f = this.k.ctx.firm("Piekarnia Anny").orElseThrow();
        assertEquals(5_000, this.k.bank.balance(f.account()));
        assertEquals(2_500, this.k.bank.balance("country:" + this.player));
        this.poolIntact();
    }

    @Test
    void rulerFoundingInOwnCountryIsAutomatic() {
        final Firm f = this.found(this.ruler, "Królewska Mennica Win", this.player);
        assertNotNull(f);
    }

    @Test
    void applicationExpiresAfter72HoursWithFullRefund() {
        final Outcome o = this.k.service.registry.found(this.anna, "Anna", "Cech Cieśli", FirmType.CECH, this.player, 2_500, false);
        this.k.clock.plusHours(73);
        this.k.service.registry.expire();
        assertTrue(this.k.ctx.data.applications.isEmpty());
        assertEquals(0, this.k.bank.balance("firma-wniosek:" + o.id()));
        assertEquals(1_000_000, this.k.bank.balance("player:" + this.anna));
        this.poolIntact();
    }

    @Test
    void nameRulesUniquenessAndReservationAfterLiquidation() {
        assertFalse(this.k.service.registry.nameProblem("Ab").isEmpty());
        assertFalse(this.k.service.registry.nameProblem("Admin Corp").isEmpty());
        assertFalse(this.k.service.registry.nameProblem("1Firma").isEmpty());
        final Firm f = this.found(this.jan, "Złota Igła", this.npc);
        assertFalse(this.k.service.registry.nameProblem("zlota igla").isEmpty(), "case/diacritics-insensitive duplicate");
        assertTrue(this.k.service.treasury.liquidate(f, "test", "admin").ok());
        assertFalse(this.k.service.registry.nameProblem("Złota Igła").isEmpty(), "reserved for 30 days");
        this.k.clock.plusHours(31 * 24);
        assertTrue(this.k.service.registry.nameProblem("Złota Igła").isEmpty());
    }

    @Test
    void limitsOfMastershipMembershipAndSeasoning() {
        this.found(this.jan, "Pierwsza", this.npc);
        assertFalse(this.k.service.registry.found(this.jan, "Jan", "Druga", FirmType.CECH, this.npc, 5_000, false).ok(), "master of one");
        final UUID fresh = this.k.world.player("Nowy");
        this.k.bank.give("player:" + fresh, 100_000);
        this.k.world.minutes.put(fresh, 30L);
        final Outcome o = this.k.service.registry.found(fresh, "Nowy", "Trzecia", FirmType.CECH, this.npc, 5_000, false);
        assertFalse(o.ok());
        assertTrue(o.message().contains("h gry"));
        assertFalse(this.k.service.registry.found(this.ola, "Ola", "Manu", FirmType.MANUFAKTURA, this.npc, 5_000, false).ok(), "era 2");
        this.k.world.eras.put(this.npc, 2);
        assertTrue(this.k.service.registry.found(this.ola, "Ola", "Manu", FirmType.MANUFAKTURA, this.npc, 5_000, false).ok());
        assertFalse(this.k.service.registry.found(this.anna, "Anna", "Dom", FirmType.DOM_HANDLOWY, this.npc, 5_000, false).ok());
    }

    @Test
    void npcSeatNeedsReputationAndPeace() {
        this.k.world.reputation.put(this.ola, -40);
        assertFalse(this.k.service.registry.found(this.ola, "Ola", "Nieufni", FirmType.CECH, this.npc, 5_000, false).ok());
        this.k.world.reputation.put(this.ola, 0);
        this.k.world.join(this.ola, this.player, 1);
        this.k.world.wars.put(this.player + ":" + this.npc, true);
        assertFalse(this.k.service.registry.found(this.ola, "Ola", "Wojenni", FirmType.CECH, this.npc, 5_000, false).ok());
    }

    @Test
    void membersInviteSharesConsentAndLeaving() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        assertTrue(this.k.service.people.invite(f, this.jan, this.anna, "Anna", Role.WSPOLNIK).ok());
        assertTrue(this.k.service.people.acceptInvite(this.anna, "Anna", f.id).ok());
        assertFalse(this.k.service.people.invite(f, this.anna, this.ola, "Ola", Role.CZELADNIK).ok(), "only the master invites");
        assertTrue(this.k.service.people.offerShares(f, this.jan, this.anna, 3_000).ok());
        assertEquals(0, f.member(this.anna).orElseThrow().shareBp, "no change before consent");
        assertTrue(this.k.service.people.acceptShares(this.anna).ok());
        assertEquals(3_000, f.member(this.anna).orElseThrow().shareBp);
        assertEquals(10_000, f.totalShares());
        assertFalse(this.k.service.people.kick(f, this.jan, this.anna).ok(), "cannot kick a shareholder");
        assertFalse(this.k.service.people.setRole(f, this.jan, this.anna, Role.CZELADNIK).ok());
        assertTrue(this.k.service.people.leave(f, this.anna).ok());
        assertEquals(10_000, f.member(this.jan).orElseThrow().shareBp, "shares of a leaver go to the master");
    }

    @Test
    void contributionAndExactDividendBySharesAndLargePayoutDelay() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        this.k.service.people.invite(f, this.jan, this.anna, "Anna", Role.WSPOLNIK);
        this.k.service.people.acceptInvite(this.anna, "Anna", f.id);
        this.k.service.people.offerShares(f, this.jan, this.anna, 3_333);
        this.k.service.people.acceptShares(this.anna);
        assertTrue(this.k.service.treasury.contribute(f, this.anna, 90_000).ok());
        assertEquals(100_000, this.k.bank.balance(f.account()));
        final long janBefore = this.k.bank.balance("player:" + this.jan);
        final long annaBefore = this.k.bank.balance("player:" + this.anna);
        assertFalse(this.k.service.treasury.dividend(f, this.anna, 1_000).ok(), "partners cannot pay out");
        assertTrue(this.k.service.treasury.dividend(f, this.jan, 1_001).ok());
        final long janGot = this.k.bank.balance("player:" + this.jan) - janBefore;
        final long annaGot = this.k.bank.balance("player:" + this.anna) - annaBefore;
        assertEquals(1_001, janGot + annaGot, "exact split, no copper lost");
        assertEquals(334, annaGot);
        // 30 000 is over 25 % of the balance → waits 24 h
        final Outcome big = this.k.service.treasury.dividend(f, this.jan, 30_000);
        assertTrue(big.ok());
        assertEquals(1, f.payouts.size());
        final long before = this.k.bank.balance(f.account());
        this.k.clock.plusHours(23);
        this.k.service.treasury.hourly();
        assertEquals(before, this.k.bank.balance(f.account()));
        this.k.clock.plusHours(2);
        this.k.service.treasury.hourly();
        assertEquals(before - 30_000, this.k.bank.balance(f.account()));
        assertTrue(f.payouts.isEmpty());
        assertTrue(this.k.messages.sent.stream().anyMatch(m -> m.startsWith(this.anna.toString()) && m.contains("duża wypłata")));
        this.poolIntact();
    }

    @Test
    void salariesArrearsInsolvencyAndForcedLiquidationOrder() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        this.k.service.people.invite(f, this.jan, this.ola, "Ola", Role.CZELADNIK);
        this.k.service.people.acceptInvite(this.ola, "Ola", f.id);
        assertFalse(this.k.service.people.setSalary(f, this.jan, this.ola, 6_000).ok(), "cap 50 zł");
        assertTrue(this.k.service.people.setSalary(f, this.jan, this.ola, 4_000).ok());
        this.k.service.treasury.daily(); // 10 000 → 6 000
        assertEquals(6_000, this.k.bank.balance(f.account()));
        this.k.service.treasury.daily(); // → 2 000
        this.k.service.treasury.daily(); // cannot pay 4 000 → arrears
        assertEquals(4_000, f.totalArrears());
        assertEquals(1, f.unpaidDays);
        this.k.service.treasury.daily();
        this.k.service.treasury.daily();
        assertTrue(this.k.service.treasury.insolvent(f));
        for (int i = 0; i < 4; i++) {
            this.k.service.treasury.daily();
        }
        assertFalse(f.active(), "liquidated after 7 unpaid days");
        assertEquals(0, this.k.bank.balance(f.account()));
        this.poolIntact();
    }

    @Test
    void arrearsArePaidBeforeNewSalariesAndAfterAContribution() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        this.k.service.people.invite(f, this.jan, this.ola, "Ola", Role.CZELADNIK);
        this.k.service.people.acceptInvite(this.ola, "Ola", f.id);
        this.k.service.people.setSalary(f, this.jan, this.ola, 5_000);
        this.k.service.treasury.daily();
        this.k.service.treasury.daily();
        this.k.service.treasury.daily();
        assertEquals(5_000, f.totalArrears());
        final long olaBefore = this.k.bank.balance("player:" + this.ola);
        this.k.service.treasury.contribute(f, this.jan, 7_000);
        assertEquals(0, f.totalArrears());
        assertEquals(olaBefore + 5_000, this.k.bank.balance("player:" + this.ola));
        assertEquals(0, f.unpaidDays);
        assertFalse(this.k.service.treasury.dividend(f, this.jan, 100).ok() && f.totalArrears() > 0);
    }

    @Test
    void liquidationPaysArrearsProportionallyThenShares() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        this.k.service.people.invite(f, this.jan, this.anna, "Anna", Role.WSPOLNIK);
        this.k.service.people.acceptInvite(this.anna, "Anna", f.id);
        this.k.service.people.offerShares(f, this.jan, this.anna, 2_500);
        this.k.service.people.acceptShares(this.anna);
        f.arrears.put(this.ola.toString(), 3_000L);
        f.arrears.put(this.anna.toString(), 1_000L);
        final long jan0 = this.k.bank.balance("player:" + this.jan);
        final long anna0 = this.k.bank.balance("player:" + this.anna);
        final long ola0 = this.k.bank.balance("player:" + this.ola);
        assertTrue(this.k.service.treasury.liquidate(f, "test", "admin").ok());
        assertEquals(3_000, this.k.bank.balance("player:" + this.ola) - ola0);
        // 10 000 - 4 000 arrears = 6 000 by shares 75/25 → 4 500 / 1 500 (+ Anna's 1 000 arrears)
        assertEquals(4_500, this.k.bank.balance("player:" + this.jan) - jan0);
        assertEquals(2_500, this.k.bank.balance("player:" + this.anna) - anna0);
        assertEquals(0, this.k.bank.balance(f.account()));
        this.poolIntact();
    }

    @Test
    void liquidationWithTooLittleMoneySplitsArrearsProportionally() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        f.arrears.put(this.ola.toString(), 15_000L);
        f.arrears.put(this.anna.toString(), 5_000L);
        final long ola0 = this.k.bank.balance("player:" + this.ola);
        final long anna0 = this.k.bank.balance("player:" + this.anna);
        this.k.service.treasury.liquidate(f, "test", "admin");
        assertEquals(7_500, this.k.bank.balance("player:" + this.ola) - ola0);
        assertEquals(2_500, this.k.bank.balance("player:" + this.anna) - anna0);
        this.poolIntact();
    }

    @Test
    void voluntaryLiquidationNeedsMoreThanHalfOfShares() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        this.k.service.people.invite(f, this.jan, this.anna, "Anna", Role.WSPOLNIK);
        this.k.service.people.acceptInvite(this.anna, "Anna", f.id);
        this.k.service.people.offerShares(f, this.jan, this.anna, 5_000);
        this.k.service.people.acceptShares(this.anna);
        assertTrue(this.k.service.treasury.voteLiquidation(f, this.jan).ok());
        assertTrue(f.active(), "exactly 50 % is not enough");
        assertTrue(this.k.service.treasury.voteLiquidation(f, this.anna).ok());
        assertFalse(f.active());
        assertEquals(this.jan.toString(), f.exMaster);
        assertTrue(this.k.service.treasury.chestAccess(f, this.jan));
        assertFalse(this.k.service.treasury.chestAccess(f, this.anna));
        this.k.clock.plusHours(8 * 24);
        assertFalse(this.k.service.treasury.chestAccess(f, this.jan));
    }

    @Test
    void dormancyAfter30DaysAndAutomaticLiquidationAfter60() {
        final Firm f = this.found(this.jan, "Uśpiona", this.npc);
        this.k.clock.plusHours(31 * 24);
        this.k.service.treasury.daily();
        assertTrue(f.dormantSince != 0);
        assertTrue(this.k.service.treasury.shopBlocked(f, false, this.npc).isPresent());
        this.k.service.treasury.activity(this.jan);
        assertEquals(0, f.dormantSince);
        this.k.clock.plusHours(61 * 24);
        this.k.service.treasury.daily();
        assertFalse(f.active());
        assertEquals(0, this.k.bank.balance(f.account()));
        this.poolIntact();
    }

    @Test
    void collectSplitsIncomeAndTaxAtomically() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        this.k.bank.give("klient:1", 10_000);
        final Outcome o = this.k.service.treasury.collect(f, "klient:1", 10_000, "kontrakt");
        assertTrue(o.ok(), o.message());
        assertEquals(10_000 + 9_500, this.k.bank.balance(f.account()));
        assertEquals(2_500 + 500, this.k.bank.balance("country:" + this.npc));
        assertEquals(10_000, f.income(this.k.ctx.windowStart(30)));
        assertEquals(500, f.taxTotal);
        this.poolIntact();
    }

    @Test
    void playerCountryTaxIsSetByTheRulerWithinLimits() {
        final Firm f = this.found(this.ruler, "Królewski Cech", this.player);
        assertFalse(this.k.service.registry.setTax(this.player, this.anna, 10, false).ok());
        assertFalse(this.k.service.registry.setTax(this.player, this.ruler, 25, false).ok());
        assertTrue(this.k.service.registry.setTax(this.player, this.ruler, 12.5, false).ok());
        assertEquals(1_250, this.k.service.registry.taxBp(this.player));
        assertEquals(500, this.k.service.registry.taxBp(this.npc));
        assertEquals(125, Treasury.tax(1_000, 1_250));
        assertNotNull(f);
    }

    @Test
    void shopRulesSeatWarSuspensionAndFreeze() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        assertTrue(this.k.service.treasury.shopBlocked(f, false, this.npc).isEmpty());
        assertTrue(this.k.service.treasury.shopBlocked(f, false, this.player).isPresent(), "outside the seat country");
        this.k.service.treasury.freeze(f, true, "test");
        assertTrue(this.k.service.treasury.shopBlocked(f, false, this.npc).isEmpty(), "selling still works when frozen");
        assertTrue(this.k.service.treasury.shopBlocked(f, true, this.npc).isPresent(), "buying (outflow) is stopped");
        this.k.service.treasury.freeze(f, false, "");
        this.k.world.join(this.jan, this.player, 1);
        assertFalse(this.k.service.treasury.warSuspend(f, this.anna, false, true).ok());
        this.k.world.wars.put(this.player + ":" + this.npc, true);
        assertTrue(this.k.service.treasury.warSuspend(f, null, true, true).ok());
        assertTrue(this.k.service.treasury.shopBlocked(f, false, this.npc).isPresent());
        this.k.world.wars.clear();
        assertTrue(this.k.service.treasury.shopBlocked(f, false, this.npc).isEmpty(), "suspension lapses with the war");
    }

    @Test
    void crashDuringFundingIsFinishedFromTheEscrowBalance() {
        final Outcome o = this.k.service.registry.found(this.anna, "Anna", "Cech Po Awarii", FirmType.CECH, this.player, 5_000, false);
        final var app = this.k.ctx.data.applications.get(o.id());
        // simulate: bank moved the money, then the server died before Firmy wrote the firm
        app.state = "FUNDING";
        this.k.bank.transfer(app.account(), "country:" + this.player, 2_500, "x");
        this.k.bank.transfer(app.account(), "firma:" + app.id, 5_000, "x");
        this.k.service.startup();
        assertTrue(this.k.ctx.firm("Cech Po Awarii").isPresent());
        assertTrue(this.k.ctx.data.applications.isEmpty());
        this.poolIntact();
    }

    @Test
    void uncertainOperationFreezesTheFirmForAudit() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        this.k.ctx.intent(f.id, "dywidenda", "test", java.util.List.of(new Bank.Leg(f.account(), "player:x", 1, "r")));
        this.k.service.startup();
        assertTrue(f.frozen);
        assertFalse(this.k.service.treasury.dividend(f, this.jan, 10).ok());
        this.k.service.treasury.freeze(f, false, "sprawdzone");
        assertTrue(this.k.ctx.intents.isEmpty());
    }

    @Test
    void failedBatchMovesNothing() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        final Map<String, Long> before = new LinkedHashMap<>(this.k.bank.balances);
        this.k.bank.failNext = true;
        assertFalse(this.k.service.treasury.dividend(f, this.jan, 100).ok());
        assertEquals(before, this.k.bank.balances);
        assertTrue(this.k.ctx.intents.isEmpty());
    }

    @Test
    void promotionNeedsThirtyDayIncome() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        assertFalse(this.k.service.treasury.promote(f, this.jan).ok());
        this.k.bank.give("klient:1", 600_000);
        this.k.service.treasury.collect(f, "klient:1", 600_000, "hurt");
        assertTrue(this.k.service.treasury.promote(f, this.jan).ok());
        assertEquals(FirmType.DOM_HANDLOWY, f.type);
    }

    @Test
    void dailyTickRunsOncePerDayAfterTheHour() {
        final Firm f = this.found(this.jan, "Kompania Jana", this.npc);
        this.k.service.people.setSalary(f, this.jan, this.jan, 1_000);
        this.k.service.tick(); // 10:00 < 12:00
        assertEquals(10_000, this.k.bank.balance(f.account()));
        this.k.clock.plusHours(3);
        this.k.service.tick();
        this.k.service.tick();
        assertEquals(9_000, this.k.bank.balance(f.account()));
        this.k.clock.plusHours(24);
        this.k.service.tick();
        assertEquals(8_000, this.k.bank.balance(f.account()));
    }

    @Test
    void plainTextCannotOpenMiniMessageTags() {
        assertEquals("‹click:run_command:'/op x'›Most‹/click›", Names.plain("<click:run_command:'/op x'>Most</click>"));
        assertEquals("", Names.plain(null));
        assertEquals("Kowale z Krakowa", Names.plain("Kowale z Krakowa"), "ordinary text is unchanged");
        assertEquals(Names.plain("a<b>c"), pl.medivalrpg.firmy.command.FirmaCommand.escape("a<b>c"), "one escaping everywhere");
    }

    @Test
    void ranking() {
        final Firm a = this.found(this.jan, "Alfa", this.npc);
        final Firm b = this.found(this.ola, "Beta", this.npc);
        this.k.bank.give("klient:1", 5_000);
        this.k.service.treasury.collect(b, "klient:1", 5_000, "x");
        assertEquals(b, this.k.service.ranking("obrot").getFirst());
        assertEquals(a.name, this.k.service.ranking("ludzie").get(0).name);
    }
}
