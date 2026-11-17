// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmType;
import pl.medivalrpg.firmy.stage2.Courts;
import pl.medivalrpg.firmy.stage2.Stage2;
import pl.medivalrpg.firmy.stage2.Stage2Data;
import pl.medivalrpg.firmy.stage2.Stage2Data.ContractState;
import pl.medivalrpg.firmy.stage2.Stage2Data.LoanState;
import pl.medivalrpg.firmy.stage2.Stage2Settings;

/**
 * Decision of the user 2026-10-31: firm contract rates ×5 (configurable), still paid only from the NPC treasury within
 * its limits; the board is refilled during the day; a frozen company is not liquidated for an overdue loan.
 */
class Stage2RatesTest {

    private final TestKit k = new TestKit();
    private final UUID npc = this.k.world.country("Mazowsze", true);
    private final UUID jan = this.k.world.player("Jan");
    private final UUID ola = this.k.world.player("Ola");
    private Optional<Courts.Budget> wladcy = Optional.empty();
    private final Courts courts = new Courts() {
        @Override
        public List<Court> npcCountries() {
            return List.of(new Court(Stage2RatesTest.this.npc, "Mazowsze", "country:" + Stage2RatesTest.this.npc, "world", 0, 0));
        }

        @Override
        public Optional<Budget> wladcyBudget(final UUID n) {
            return Stage2RatesTest.this.wladcy;
        }

        @Override
        public void booked(final UUID n, final long amount) {
        }

        @Override
        public void reputation(final UUID n, final UUID player, final String name, final int delta) {
        }

        @Override
        public Optional<String> allocateLoan(final UUID owner, final String escrow, final long amount, final String firmName) {
            return Optional.of("brak");
        }

        @Override
        public Optional<String> returnLoan(final UUID owner, final String escrow, final long amount) {
            return Optional.of("brak");
        }
    };
    private final Stage2Settings settings = new Stage2Settings();
    private final Stage2Data data = new Stage2Data();
    private final Stage2 s2;

    Stage2RatesTest() {
        this.settings.goods = new java.util.LinkedHashMap<>(Map.of("BREAD", new Stage2Settings.Good("BREAD", 100, 100, 12)));
        this.s2 = new Stage2(this.k.service, this.data, this.courts, this.settings, () -> { });
        this.k.service.addExtension(this.s2);
        this.k.bank.give("player:" + this.jan, 1_000_000);
        this.k.bank.give("player:" + this.ola, 1_000_000);
        this.k.bank.give("country:" + this.npc, 1_000_000);
    }

    private Firm found(final UUID who, final String name) {
        final Outcome o = this.k.service.registry.found(who, this.k.world.playerName(who), name, FirmType.KOMPANIA, this.npc, 10_000, false);
        assertTrue(o.ok(), o.message());
        return this.k.ctx.firm(o.id()).orElseThrow();
    }

    private void poolIntact() {
        assertEquals(TestKit.POOL, this.k.bank.sum());
        assertTrue(this.k.bank.noNegative());
    }

    @Test
    void defaultRateIsFiveTimesTheConfiguredPrice() {
        assertEquals(5.0, new Stage2Settings().rateMultiplier);
        assertEquals(2, this.s2.contracts.generateWholesale(7));
        for (final Stage2Data.Contract c : this.s2.contracts.wholesale()) {
            assertEquals(100, c.quantity);
            assertEquals(100 * 12 * 5, c.price, "12 md × 5 per bread");
        }
        this.settings.rateMultiplier = 1.0;
        assertEquals(12, this.settings.rate(this.settings.goods.get("BREAD")));
    }

    @Test
    void openUnfundedOffersFollowTheNewRateButFundedOnesNever() {
        final Firm f = this.found(this.jan, "Hurtownia");
        this.settings.rateMultiplier = 1.0;
        this.s2.contracts.generateWholesale(1);
        final List<Stage2Data.Contract> offers = this.s2.contracts.wholesale();
        assertEquals(1_200, offers.getFirst().price);
        assertTrue(this.s2.contracts.accept(offers.getFirst().id, this.jan, f).ok());
        final Stage2Data.Contract taken = offers.getFirst();
        final Stage2Data.Contract open = offers.get(1);
        this.settings.rateMultiplier = 5.0;
        this.s2.contracts.generateWholesale(2);
        assertEquals(6_000, open.price, "an offer nobody took is repriced");
        assertEquals(1_200, taken.price, "money already in the deposit is never changed");
        this.poolIntact();
    }

    @Test
    void orderIsShrunkToTodaysTreasuryLimitInsteadOfRefused() {
        final Firm f = this.found(this.jan, "Hurtownia");
        this.s2.contracts.generateWholesale(3);
        final Stage2Data.Contract w = this.s2.contracts.wholesale().getFirst();
        assertEquals(6_000, w.price);
        this.wladcy = Optional.of(new Courts.Budget(4_000, 10_000));
        final long npc0 = this.k.bank.balance("country:" + this.npc);
        assertTrue(this.s2.contracts.accept(w.id, this.jan, f).ok());
        assertEquals(66, w.quantity, "4 000 md / 60 md per piece");
        assertEquals(3_960, w.price);
        assertEquals(npc0 - 3_960, this.k.bank.balance("country:" + this.npc));
        this.s2.contracts.delivered(w.id, this.jan, 66);
        assertEquals(ContractState.DONE, w.state);
        this.wladcy = Optional.of(new Courts.Budget(1_000, 10_000));
        final Stage2Data.Contract w2 = this.s2.contracts.wholesale().getFirst();
        assertFalse(this.s2.contracts.accept(w2.id, this.jan, f).ok(), "less than half of the order fits: refused, no money moves");
        assertEquals(0, this.k.bank.balance(w2.account()));
        this.poolIntact();
    }

    @Test
    void boardIsRefilledDuringTheDayWithinTheLimitPerCountry() {
        final Firm f = this.found(this.jan, "Hurtownia");
        this.s2.tick();
        assertEquals(2, this.s2.contracts.wholesale().size());
        final Stage2Data.Contract w = this.s2.contracts.wholesale().getFirst();
        assertTrue(this.s2.contracts.accept(w.id, this.jan, f).ok());
        this.s2.contracts.delivered(w.id, this.jan, w.quantity);
        this.k.clock.plusHours(0);
        this.k.clock.millis += 30 * 60_000L;
        this.s2.tick();
        assertEquals(1, this.s2.contracts.wholesale().size(), "not before the refill interval");
        this.k.clock.millis += 31 * 60_000L;
        this.s2.tick();
        assertEquals(2, this.s2.contracts.wholesale().size(), "a new order replaces the delivered one");
        this.k.clock.millis += 61 * 60_000L;
        this.s2.tick();
        assertEquals(2, this.s2.contracts.wholesale().size(), "never more than " + this.settings.perCountry + " per court");
        this.poolIntact();
    }

    @Test
    void frozenCompanyIsNotLiquidatedForAnOverdueLoan() {
        final Firm f = this.found(this.jan, "Pożyczkowa");
        this.k.bank.give("klient:1", 40_000);
        assertTrue(this.k.service.treasury.collect(f, "klient:1", 40_000, "obrót").ok());
        final Outcome offer = this.s2.loans.offer(this.ola, null, f, 10_000, 1_000, 7);
        assertTrue(offer.ok(), offer.message());
        assertTrue(this.s2.loans.accept(offer.id(), this.jan).ok());
        // the company pays everything out, then is frozen for an audit
        this.k.bank.transfer(f.account(), "player:" + this.jan, this.k.bank.balance(f.account()), "test");
        this.k.service.treasury.freeze(f, true, "audyt");
        for (int d = 0; d < 20; d++) {
            this.k.clock.plusHours(24);
            this.s2.daily(this.k.ctx.now());
        }
        assertTrue(f.active());
        assertEquals(LoanState.ACTIVE, this.data.loans.get(offer.id()).state);
        this.poolIntact();
    }
}
