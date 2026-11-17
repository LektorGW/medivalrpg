// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmType;
import pl.medivalrpg.firmy.model.Role;
import pl.medivalrpg.firmy.panel.FirmyPanel;
import pl.medivalrpg.firmy.stage2.Contracts;
import pl.medivalrpg.firmy.stage2.Courts;
import pl.medivalrpg.firmy.stage2.Stage2;
import pl.medivalrpg.firmy.stage2.Stage2Data;
import pl.medivalrpg.firmy.stage2.Stage2Data.ContractState;
import pl.medivalrpg.firmy.stage2.Stage2Data.Kind;
import pl.medivalrpg.firmy.stage2.Stage2Data.LoanState;
import pl.medivalrpg.firmy.stage2.Stage2Data.Party;
import pl.medivalrpg.firmy.stage2.Stage2Settings;

/** Contracts with deposits and bonds, wholesale orders of NPC courts, limited loans — money only moves, never appears. */
class Stage2Test {

    private final TestKit k = new TestKit();
    private final UUID npc = this.k.world.country("Księstwo", true);
    private final UUID jan = this.k.world.player("Jan");
    private final UUID ola = this.k.world.player("Ola");
    private final UUID ewa = this.k.world.player("Ewa");
    private final List<String> reputation = new ArrayList<>();
    private Optional<Courts.Budget> wladcy = Optional.empty();
    private final Courts courts = new Courts() {
        @Override
        public List<Court> npcCountries() {
            return List.of(new Court(Stage2Test.this.npc, "Księstwo", "country:" + Stage2Test.this.npc, "world", 0, 0));
        }

        @Override
        public Optional<Budget> wladcyBudget(final UUID n) {
            return Stage2Test.this.wladcy;
        }

        @Override
        public void booked(final UUID n, final long amount) {
        }

        @Override
        public void reputation(final UUID n, final UUID player, final String name, final int delta) {
            Stage2Test.this.reputation.add(name + " " + delta);
        }

        @Override
        public Optional<String> allocateLoan(final UUID owner, final String escrow, final long amount, final String firmName) {
            return Stage2Test.this.k.bank.transfer2("world:reserve", escrow, amount) ? Optional.empty() : Optional.of("brak");
        }

        @Override
        public Optional<String> returnLoan(final UUID owner, final String escrow, final long amount) {
            return Stage2Test.this.k.bank.transfer2(escrow, "world:reserve", amount) ? Optional.empty() : Optional.of("brak");
        }
    };
    private final Stage2Settings settings = new Stage2Settings();
    private final Stage2 s2 = new Stage2(this.k.service, new Stage2Data(), this.courts, this.settings, () -> { });

    Stage2Test() {
        this.k.service.addExtension(this.s2);
        for (final UUID u : new UUID[] {this.jan, this.ola, this.ewa}) {
            this.k.bank.give("player:" + u, 2_000_000);
        }
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

    private Contracts.Payer playerPayer(final UUID u) {
        return new Contracts.Payer(Party.PLAYER, u.toString(), this.k.world.playerName(u), "player:" + u);
    }

    // ------------------------------------------------------------------ contracts

    @Test
    void goodsContractPaysFromDepositWithTaxAndReturnsTheBond() {
        final Firm f = this.found(this.jan, "Kompania");
        final Outcome c = this.s2.contracts.create(this.ola, this.playerPayer(this.ola), Kind.GOODS, "", "WHEAT", 64, 10_000, 2_000, 48,
            "world:1:2:3", null);
        assertTrue(c.ok(), c.message());
        assertEquals(10_000, this.k.bank.balance("firma-kontrakt:" + c.id()));
        assertTrue(this.s2.contracts.accept(c.id(), this.jan, f).ok());
        assertEquals(12_000, this.k.bank.balance("firma-kontrakt:" + c.id()), "bond joins the deposit");
        assertEquals(8_000, this.k.bank.balance(f.account()));
        assertTrue(this.s2.contracts.canDeliver(c.id(), this.jan).ok());
        assertFalse(this.s2.contracts.canDeliver(c.id(), this.ola).ok(), "only people of the contractor deliver");
        this.s2.contracts.delivered(c.id(), this.jan, 40);
        assertEquals(ContractState.ACCEPTED, this.s2.contracts.get(c.id()).orElseThrow().state);
        final Outcome done = this.s2.contracts.delivered(c.id(), this.jan, 24);
        assertTrue(done.ok(), done.message());
        assertEquals(0, this.k.bank.balance("firma-kontrakt:" + c.id()));
        assertEquals(8_000 + 9_500 + 2_000, this.k.bank.balance(f.account()));
        assertEquals(2_500 + 500, this.k.bank.balance("country:" + this.npc) - 1_000_000);
        assertEquals(1, f.contractsDone);
        this.poolIntact();
    }

    @Test
    void missedDeadlinePaysProRataAndTheBondGoesToTheClient() {
        final Firm f = this.found(this.jan, "Kompania");
        final Outcome c = this.s2.contracts.create(this.ola, this.playerPayer(this.ola), Kind.GOODS, "", "BREAD", 100, 10_000, 4_000, 24,
            "world:1:2:3", null);
        this.s2.contracts.accept(c.id(), this.jan, f);
        this.s2.contracts.delivered(c.id(), this.jan, 25);
        final long ola0 = this.k.bank.balance("player:" + this.ola);
        final long firm0 = this.k.bank.balance(f.account());
        this.k.clock.plusHours(25);
        this.s2.contracts.tick();
        assertEquals(ContractState.FAILED, this.s2.contracts.get(c.id()).orElseThrow().state);
        assertEquals(2_500 - 125, this.k.bank.balance(f.account()) - firm0, "25 % of the price, taxed");
        assertEquals(7_500 + 4_000, this.k.bank.balance("player:" + this.ola) - ola0, "rest of the price and the bond");
        this.poolIntact();
    }

    @Test
    void refusedRefundNeverPaysTheDeliveredPartTwice() {
        // review 2026-10-29: pro-rata payment and refund used to be two transfers; a refused refund left the contract open
        // and every following tick paid the delivered part again from the client's deposit
        final Firm f = this.found(this.jan, "Kompania");
        final Outcome c = this.s2.contracts.create(this.ola, this.playerPayer(this.ola), Kind.GOODS, "", "BREAD", 100, 10_000, 4_000, 24,
            "world:1:2:3", null);
        this.s2.contracts.accept(c.id(), this.jan, f);
        this.s2.contracts.delivered(c.id(), this.jan, 25);
        final long ola0 = this.k.bank.balance("player:" + this.ola);
        final long firm0 = this.k.bank.balance(f.account());
        this.k.bank.rejectTo.add("player:" + this.ola);
        this.k.clock.plusHours(25);
        this.s2.contracts.tick();
        this.k.clock.plusHours(1);
        this.s2.contracts.tick();
        assertEquals(ContractState.ACCEPTED, this.s2.contracts.get(c.id()).orElseThrow().state, "retried while the refund is refused");
        assertEquals(firm0, this.k.bank.balance(f.account()), "nothing paid while the refund is refused");
        assertEquals(14_000, this.k.bank.balance("firma-kontrakt:" + c.id()), "the deposit stays whole");
        this.k.bank.rejectTo.clear();
        this.k.clock.plusHours(1);
        this.s2.contracts.tick();
        assertEquals(ContractState.FAILED, this.s2.contracts.get(c.id()).orElseThrow().state);
        assertEquals(2_500 - 125, this.k.bank.balance(f.account()) - firm0, "the delivered part is paid exactly once");
        assertEquals(7_500 + 4_000, this.k.bank.balance("player:" + this.ola) - ola0);
        assertEquals(0, this.k.bank.balance("firma-kontrakt:" + c.id()));
        this.poolIntact();
    }

    @Test
    void expiredOfferTellsTheClientOnlyAfterTheRefund() {
        final Outcome c = this.s2.contracts.create(this.ola, this.playerPayer(this.ola), Kind.SERVICE, "Most", null, 0, 5_000, 0, 24, null, null);
        this.k.bank.rejectTo.add("player:" + this.ola);
        this.k.clock.plusHours(73);
        this.s2.contracts.tick();
        assertEquals(ContractState.OPEN, this.s2.contracts.get(c.id()).orElseThrow().state);
        assertTrue(this.k.messages.sent.stream().noneMatch(m -> m.contains("depozyt wrócił")), "no false 'money is back' message");
        this.k.bank.rejectTo.clear();
        this.s2.contracts.tick();
        assertEquals(ContractState.CANCELLED, this.s2.contracts.get(c.id()).orElseThrow().state);
        assertEquals(1, this.k.messages.sent.stream().filter(m -> m.contains("depozyt wrócił")).count());
        this.poolIntact();
    }

    @Test
    void contractTitleAndDisputeReasonCannotCarryMiniMessageTags() {
        final Firm f = this.found(this.jan, "Kompania");
        final Outcome c = this.s2.contracts.create(this.ola, this.playerPayer(this.ola), Kind.SERVICE,
            "<click:run_command:'/firma likwiduj x'>Most</click>", null, 0, 5_000, 0, 24, null, f.id);
        assertTrue(c.ok(), c.message());
        final Stage2Data.Contract contract = this.s2.contracts.get(c.id()).orElseThrow();
        assertFalse(contract.title.contains("<") || contract.title.contains(">"), contract.title);
        assertTrue(this.k.messages.sent.stream().noneMatch(m -> m.contains("<click:run_command:'/firma likwiduj")),
            "partners of the contractor get no player-made click event");
        this.s2.contracts.accept(c.id(), this.jan, f);
        assertTrue(this.s2.contracts.dispute(c.id(), this.ola, "<red>oszustwo").ok());
        assertFalse(contract.disputeReason.contains("<"), contract.disputeReason);
    }

    @Test
    void openOfferCanBeCancelledAndExpires() {
        final Outcome c = this.s2.contracts.create(this.ola, this.playerPayer(this.ola), Kind.SERVICE, "Most", null, 0, 5_000, 0, 24, null, null);
        assertTrue(c.ok(), c.message());
        assertFalse(this.s2.contracts.cancel(c.id(), this.jan, false).ok());
        assertTrue(this.s2.contracts.cancel(c.id(), this.ola, false).ok());
        final Outcome c2 = this.s2.contracts.create(this.ola, this.playerPayer(this.ola), Kind.SERVICE, "Wieża", null, 0, 5_000, 0, 24, null, null);
        this.k.clock.plusHours(73);
        this.s2.contracts.tick();
        assertEquals(ContractState.CANCELLED, this.s2.contracts.get(c2.id()).orElseThrow().state);
        assertEquals(2_000_000, this.k.bank.balance("player:" + this.ola));
        this.poolIntact();
    }

    @Test
    void serviceNeedsConfirmationOrIsReleasedAfterTheWindow() {
        final Firm f = this.found(this.jan, "Budowniczowie");
        final Outcome c = this.s2.contracts.create(this.ola, this.playerPayer(this.ola), Kind.SERVICE, "Dom", null, 0, 6_000, 0, 48, null, null);
        this.s2.contracts.accept(c.id(), this.jan, f);
        assertTrue(this.s2.contracts.report(c.id(), this.jan).ok());
        assertFalse(this.s2.contracts.confirm(c.id(), this.jan).ok(), "the contractor cannot confirm");
        this.k.clock.plusHours(73);
        this.s2.contracts.tick();
        assertEquals(ContractState.DONE, this.s2.contracts.get(c.id()).orElseThrow().state);
        assertEquals(10_000 + 5_700, this.k.bank.balance(f.account()));
        this.poolIntact();
    }

    @Test
    void disputeFreezesTheDepositUntilTheOwnerDecides() {
        final Firm f = this.found(this.jan, "Kompania");
        final Outcome c = this.s2.contracts.create(this.ola, this.playerPayer(this.ola), Kind.SERVICE, "Most", null, 0, 8_000, 2_000, 48, null, null);
        this.s2.contracts.accept(c.id(), this.jan, f);
        assertFalse(this.s2.contracts.dispute(c.id(), this.ewa, "x").ok(), "only parties");
        assertTrue(this.s2.contracts.dispute(c.id(), this.ola, "nie zrobili").ok());
        this.k.clock.plusHours(200);
        this.s2.contracts.tick();
        assertEquals(ContractState.DISPUTED, this.s2.contracts.get(c.id()).orElseThrow().state, "no automatic payment in a dispute");
        assertTrue(this.s2.blocksVoluntaryLiquidation(f).isPresent());
        final long ola0 = this.k.bank.balance("player:" + this.ola);
        final long firm0 = this.k.bank.balance(f.account());
        assertTrue(this.s2.contracts.resolve(c.id(), "split", "pół na pół", "WorldSteward").ok());
        assertEquals(4_000, this.k.bank.balance("player:" + this.ola) - ola0);
        assertEquals(4_000 - 200 + 2_000, this.k.bank.balance(f.account()) - firm0);
        this.poolIntact();
    }

    @Test
    void contractorLiquidationRefundsTheClientFirst() {
        final Firm f = this.found(this.jan, "Kompania");
        final Outcome c = this.s2.contracts.create(this.ola, this.playerPayer(this.ola), Kind.GOODS, "", "WHEAT", 10, 3_000, 1_000, 48,
            "world:1:2:3", null);
        this.s2.contracts.accept(c.id(), this.jan, f);
        final long ola0 = this.k.bank.balance("player:" + this.ola);
        this.k.service.treasury.liquidate(f, "test", "admin");
        assertEquals(4_000, this.k.bank.balance("player:" + this.ola) - ola0, "price and bond back to the client");
        assertEquals(0, this.k.bank.balance("firma-kontrakt:" + c.id()));
        this.poolIntact();
    }

    @Test
    void wholesaleOrdersAreFundedByTheNpcTreasuryOnlyWhenTakenAndWithinLimits() {
        final Firm f = this.found(this.jan, "Hurtownia");
        final long npc0 = this.k.bank.balance("country:" + this.npc);
        assertEquals(2, this.s2.contracts.generateWholesale(42));
        assertEquals(npc0, this.k.bank.balance("country:" + this.npc), "no money moves for offers");
        final Stage2Data.Contract w = this.s2.contracts.wholesale().getFirst();
        assertTrue(w.price <= npc0 * 3 / 100, "≤ 3 % of the treasury per order");
        assertTrue(this.s2.contracts.accept(w.id, this.jan, f).ok());
        assertEquals(npc0 - w.price, this.k.bank.balance("country:" + this.npc));
        this.s2.contracts.delivered(w.id, this.jan, w.quantity);
        assertEquals(ContractState.DONE, w.state);
        assertEquals(List.of("Jan 6"), this.reputation, "reputation for the delivering player");
        this.wladcy = Optional.of(new Courts.Budget(100, 100));
        final Stage2Data.Contract w2 = this.s2.contracts.wholesale().getFirst();
        assertFalse(this.s2.contracts.accept(w2.id, this.jan, f).ok(), "shared Wladcy limit refuses");
        this.poolIntact();
    }

    // ------------------------------------------------------------------ loans

    private Firm withIncome(final UUID who, final String name, final long income) {
        final Firm f = this.found(who, name);
        this.k.bank.give("klient:1", income);
        assertTrue(this.k.service.treasury.collect(f, "klient:1", income, "obrót").ok());
        return f;
    }

    @Test
    void loanLimitIsTheSmallerOfTwiceCapitalAndHalfOfIncomeAndTheHardCap() {
        final Firm f = this.withIncome(this.jan, "Pożyczkowa", 20_000); // balance 10 000 + 19 000
        final var lim = this.s2.loans.limit(f);
        assertEquals(29_000, lim.capital());
        assertEquals(10_000, lim.limit(), "50 % of 20 000 income");
        assertFalse(this.s2.loans.offer(this.ola, null, f, 10_001, 500, 14).ok());
        assertFalse(this.s2.loans.offer(this.ola, null, f, 5_000, 1_001, 14).ok(), "max 10 % interest");
        assertFalse(this.s2.loans.offer(this.ola, null, f, 5_000, 500, 6).ok(), "min 7 days");
        assertFalse(this.s2.loans.offer(this.ola, null, f, 5_000, 500, 31).ok(), "max 30 days");
        assertFalse(this.s2.loans.offer(this.jan, null, f, 5_000, 500, 14).ok(), "members do not lend to their own firm");
        final Firm fresh = this.found(this.ewa, "Nowa");
        assertFalse(this.s2.loans.offer(this.ola, null, fresh, 100, 500, 14).ok(), "no income, no loan");
    }

    @Test
    void lenderMoneyWaitsInEscrowThenIsDisbursedAndRepaidAutomatically() {
        final Firm f = this.withIncome(this.jan, "Pożyczkowa", 40_000);
        final Outcome o = this.s2.loans.offer(this.ola, null, f, 10_000, 1_000, 14);
        assertTrue(o.ok(), o.message());
        assertEquals(10_000, this.k.bank.balance("firma-kredyt:" + o.id()));
        final long firm0 = this.k.bank.balance(f.account());
        assertFalse(this.s2.loans.accept(o.id(), this.ewa).ok(), "only the master");
        assertTrue(this.s2.loans.accept(o.id(), this.jan).ok());
        assertEquals(0, this.k.bank.balance("firma-kredyt:" + o.id()));
        assertEquals(firm0 + 10_000, this.k.bank.balance(f.account()));
        final var loan = this.s2.loans.get(o.id()).orElseThrow();
        assertEquals(11_000, loan.total());
        assertFalse(this.k.service.treasury.dividend(f, this.jan, this.k.bank.balance(f.account()) - 5_000).ok(), "debt is reserved");
        final Outcome second = this.s2.loans.offer(this.ewa, null, f, 1_000, 0, 7);
        assertTrue(second.ok());
        assertFalse(this.s2.loans.accept(second.id(), this.jan).ok(), "one live loan per firm");
        assertTrue(this.s2.loans.refuse(second.id(), this.jan, false).ok());
        final long ola0 = this.k.bank.balance("player:" + this.ola);
        this.k.bank.give("klient:2", 10_000);
        this.k.service.treasury.collect(f, "klient:2", 10_000, "sprzedaż"); // net 9 500 → 50 % = 4 750 repaid
        assertEquals(4_750, this.k.bank.balance("player:" + this.ola) - ola0);
        this.k.clock.plusHours(15 * 24);
        this.s2.loans.daily();
        assertEquals(LoanState.REPAID, loan.state);
        assertEquals(11_000, this.k.bank.balance("player:" + this.ola) - ola0, "principal + 10 %");
        this.poolIntact();
    }

    @Test
    void aBorrowingFirmCannotLendAndOffersCanBeWithdrawn() {
        final Firm borrower = this.withIncome(this.jan, "Dłużnik", 40_000);
        final Firm lender = this.withIncome(this.ola, "Bank", 40_000);
        final Firm third = this.withIncome(this.ewa, "Trzecia", 40_000);
        final Outcome fromFirm = this.s2.loans.offer(this.ola, lender, borrower, 5_000, 500, 10);
        assertTrue(fromFirm.ok(), fromFirm.message());
        assertTrue(this.s2.loans.accept(fromFirm.id(), this.jan).ok());
        assertFalse(this.s2.loans.offer(this.jan, borrower, third, 1_000, 0, 7).ok(), "no lending of borrowed money");
        final Outcome withdraw = this.s2.loans.offer(this.ewa, null, lender, 1_000, 0, 7);
        assertTrue(this.s2.loans.refuse(withdraw.id(), this.ewa, true).ok());
        assertEquals(LoanState.CANCELLED, this.s2.loans.get(withdraw.id()).orElseThrow().state);
        this.poolIntact();
    }

    @Test
    void worldTreasuryLoanNeedsTheOwnerAndRepaymentsWaitForTheSweep() {
        final Firm f = this.withIncome(this.jan, "Królewska", 40_000);
        final long reserve0 = this.k.bank.balance("world:reserve");
        final Outcome req = this.s2.loans.requestWorld(f, this.jan, 8_000, 500, 10, "statek");
        assertTrue(req.ok(), req.message());
        assertEquals(reserve0, this.k.bank.balance("world:reserve"), "no money before the decision");
        assertFalse(this.s2.loans.decideWorld(req.id(), this.jan, true, "").ok(), "only the owner decides");
        assertTrue(this.s2.loans.decideWorld(req.id(), FirmyPanel.OWNER, true, "ok").ok());
        assertEquals(reserve0 - 8_000, this.k.bank.balance("world:reserve"));
        this.k.clock.plusHours(11 * 24);
        this.s2.loans.daily();
        final var loan = this.s2.loans.get(req.id()).orElseThrow();
        assertEquals(LoanState.REPAID, loan.state);
        assertEquals(8_400, this.k.bank.balance("firma-kredyt:" + req.id()), "repayments wait in the escrow");
        assertTrue(this.s2.loans.sweepWorld(req.id(), FirmyPanel.OWNER).ok());
        assertEquals(reserve0 + 400, this.k.bank.balance("world:reserve"));
        this.poolIntact();
    }

    @Test
    void lenderHasPriorityAfterContractsBeforeSalaryArrearsInLiquidation() {
        final Firm f = this.withIncome(this.jan, "Upadła", 40_000);
        final Outcome o = this.s2.loans.offer(this.ola, null, f, 10_000, 1_000, 14);
        this.s2.loans.accept(o.id(), this.jan);
        // spend most of the money: only 12 000 left
        final long bal = this.k.bank.balance(f.account());
        this.k.bank.transfer(f.account(), "klient:x", bal - 12_000, "test");
        f.arrears.put(this.ewa.toString(), 5_000L);
        final long ola0 = this.k.bank.balance("player:" + this.ola);
        final long ewa0 = this.k.bank.balance("player:" + this.ewa);
        this.k.service.treasury.liquidate(f, "test", "admin");
        assertEquals(11_000, this.k.bank.balance("player:" + this.ola) - ola0, "lender paid in full first");
        assertEquals(1_000, this.k.bank.balance("player:" + this.ewa) - ewa0, "arrears get what is left");
        assertEquals(LoanState.REPAID, this.s2.loans.get(o.id()).orElseThrow().state);
        this.poolIntact();
    }

    @Test
    void unpaidLoanEndsInForcedLiquidation() {
        final Firm f = this.withIncome(this.jan, "Zadłużona", 40_000);
        final Outcome o = this.s2.loans.offer(this.ola, null, f, 10_000, 1_000, 7);
        this.s2.loans.accept(o.id(), this.jan);
        this.k.bank.transfer(f.account(), "klient:x", this.k.bank.balance(f.account()), "test");
        this.k.clock.plusHours(8 * 24);
        this.s2.loans.daily();
        assertTrue(f.active());
        this.k.clock.plusHours(7 * 24);
        this.s2.loans.daily();
        assertFalse(f.active());
        assertEquals(LoanState.DEFAULTED, this.s2.loans.get(o.id()).orElseThrow().state);
        this.poolIntact();
    }

    @Test
    void firmClientPaysFromItsAccountOnlyByTheMaster() {
        final Firm client = this.found(this.ola, "Zleceniodawca");
        final Firm contractor = this.found(this.jan, "Wykonawca");
        this.k.service.people.invite(client, this.ola, this.ewa, "Ewa", Role.WSPOLNIK);
        this.k.service.people.acceptInvite(this.ewa, "Ewa", client.id);
        final Contracts.Payer payer = new Contracts.Payer(Party.FIRM, client.id, client.name, client.account());
        assertFalse(this.s2.contracts.create(this.ewa, payer, Kind.SERVICE, "x", null, 0, 1_000, 0, 24, null, contractor.id).ok());
        final Outcome c = this.s2.contracts.create(this.ola, payer, Kind.SERVICE, "x", null, 0, 1_000, 0, 24, null, contractor.id);
        assertTrue(c.ok(), c.message());
        assertFalse(this.s2.contracts.accept(c.id(), this.ola, client).ok(), "reserved for another firm and no self-dealing");
        assertTrue(this.s2.contracts.accept(c.id(), this.jan, contractor).ok());
        this.poolIntact();
    }
}
