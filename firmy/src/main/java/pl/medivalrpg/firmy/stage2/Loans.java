// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import pl.medivalrpg.firmy.core.Ctx;
import pl.medivalrpg.firmy.core.Extension;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.core.Outcome;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.Role;
import pl.medivalrpg.firmy.panel.FirmyPanel;
import pl.medivalrpg.firmy.stage2.Stage2Data.Lender;
import pl.medivalrpg.firmy.stage2.Stage2Data.Loan;
import pl.medivalrpg.firmy.stage2.Stage2Data.LoanState;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Limited company loans (decision 2026-10-29). Only existing money moves: a player or a company lends its own money
 * (held in the escrow {@code firma-kredyt:<id>} until the borrower takes it), the World Treasury only after the owner's
 * manual approval. Limits: at most min(2 × capital, 50 % of the 30-day income, hard cap); interest at most 10 % for the
 * whole period of 7–30 days; one live loan per company; a borrowing company cannot lend (no loan repaid with a loan);
 * money owed is reserved (no dividends from it); repayment is automatic from income and at the due date; an unpaid loan
 * leads to liquidation where the lender is paid right after the contract deposits. Pure Java, main thread.
 */
public final class Loans {

    private final FirmService service;
    private final Stage2Data data;
    private final Courts courts;
    private volatile Stage2Settings s;
    private final Runnable save;

    public Loans(final FirmService service, final Stage2Data data, final Courts courts, final Stage2Settings settings, final Runnable save) {
        this.service = service;
        this.data = data;
        this.courts = courts;
        this.s = settings;
        this.save = save;
    }

    public void settings(final Stage2Settings settings) {
        this.s = settings;
    }

    private Ctx ctx() {
        return this.service.ctx;
    }

    private void log(final Loan l, final String text) {
        l.log.add(this.ctx().day(this.ctx().now()) + " " + text);
        if (l.log.size() > 40) {
            l.log.remove(0);
        }
    }

    public Optional<Loan> get(final String id) {
        return Optional.ofNullable(id == null ? null : this.data.loans.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<Loan> of(final Firm f) {
        return this.data.loans.values().stream().filter(l -> f.id.equals(l.firm)).toList();
    }

    public Optional<Loan> active(final Firm f) {
        return this.data.loans.values().stream().filter(l -> f.id.equals(l.firm) && l.state == LoanState.ACTIVE).findFirst();
    }

    private boolean hasLiveAsBorrower(final String firmId, final boolean countOffers) {
        return this.data.loans.values().stream().anyMatch(l -> firmId.equals(l.firm)
            && (l.state == LoanState.ACTIVE || l.state == LoanState.REQUESTED || countOffers && l.state == LoanState.OFFERED));
    }

    // ------------------------------------------------------------------ limits

    public record Limit(long capital, long income30, long byCapital, long byIncome, long hardCap, long limit) {
    }

    /** Capital = the company balance; income = gross income of the last 30 days. */
    public Limit limit(final Firm f) {
        final long capital = Math.max(0, this.service.treasury.balance(f));
        final long income = f.income(this.ctx().windowStart(30));
        final long byCapital = capital * this.s.capitalMultiplier;
        final long byIncome = income * this.s.incomeSharePct / 100;
        return new Limit(capital, income, byCapital, byIncome, this.s.hardCap, Math.max(0, Math.min(this.s.hardCap, Math.min(byCapital, byIncome))));
    }

    private Optional<String> termsProblem(final Firm f, final long amount, final int interestBp, final int days) {
        if (!this.s.loans) {
            return Optional.of("Kredyty są wyłączone.");
        }
        if (!f.active() || f.frozen) {
            return Optional.of("Firma " + f.name + " nie może teraz brać kredytu (zlikwidowana albo zamrożona).");
        }
        if (amount <= 0) {
            return Optional.of("Podaj dodatnią kwotę.");
        }
        if (interestBp < 0 || interestBp > this.s.maxInterestBp) {
            return Optional.of("Odsetki najwyżej " + this.s.maxInterestBp / 100.0 + " % za cały okres.");
        }
        if (days < this.s.minDays || days > this.s.maxDays) {
            return Optional.of("Okres kredytu: " + this.s.minDays + "–" + this.s.maxDays + " dni.");
        }
        final Limit lim = this.limit(f);
        if (amount > lim.limit()) {
            return Optional.of("Limit kredytu firmy " + f.name + ": " + this.ctx().fmt(lim.limit()) + " (2× kapitał " + this.ctx().fmt(lim.byCapital())
                + ", 50 % obrotu 30 dni " + this.ctx().fmt(lim.byIncome()) + ", limit kwotowy " + this.ctx().fmt(lim.hardCap()) + ").");
        }
        if (this.service.treasury.insolvent(f) || f.totalArrears() > 0) {
            return Optional.of("Firma z zaległymi pensjami nie dostanie kredytu.");
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ World Treasury (manual approval)

    public Outcome requestWorld(final Firm f, final UUID actor, final long amount, final int interestBp, final int days, final String purpose) {
        if (!f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("O kredyt wnioskuje mistrz.");
        }
        if (this.hasLiveAsBorrower(f.id, false)) {
            return Outcome.fail("Firma ma już kredyt albo wniosek w toku (najwyżej jeden).");
        }
        final Optional<String> p = this.termsProblem(f, amount, interestBp, days);
        if (p.isPresent()) {
            return Outcome.fail(p.get());
        }
        final Loan l = this.newLoan(f, Lender.WORLD, "world", "Skarb Świata", amount, interestBp, days);
        l.purpose = purpose == null ? "" : purpose.strip();
        if (l.purpose.length() > 120) {
            l.purpose = l.purpose.substring(0, 120);
        }
        l.repayTo = l.account();
        l.state = LoanState.REQUESTED;
        this.log(l, "wniosek do Skarbu Świata: " + this.ctx().fmt(amount) + ", " + interestBp / 100.0 + " %, " + days + " dni");
        this.ctx().journal(f, "kredyt", "Wniosek o kredyt Skarbu Świata " + this.ctx().fmt(amount) + " (" + l.id + ")", 0, this.ctx().world.playerName(actor));
        this.save.run();
        this.ctx().save();
        this.ctx().notifier.player(FirmyPanel.OWNER, "<gold>[Firmy]</gold> Firma „" + f.name + "” prosi Skarb Świata o kredyt "
            + this.ctx().fmt(amount) + " na " + days + " dni (" + interestBp / 100.0 + " %). Decyzja: panel WWW → Skarb Świata.");
        this.ctx().notifier.admin("Firmy: wniosek o kredyt Skarbu Świata " + l.id + " — " + f.name + " " + amount + " md", false);
        return Outcome.ok("Wniosek o kredyt ze Skarbu Świata złożony (" + l.id + "). Decyzję podejmuje ręcznie właściciel.", l.id);
    }

    public Outcome decideWorld(final String id, final UUID owner, final boolean approve, final String note) {
        if (!FirmyPanel.owner(owner)) {
            return Outcome.fail("Kredyty Skarbu Świata zatwierdza tylko właściciel.");
        }
        final Optional<Loan> ol = this.get(id);
        if (ol.isEmpty() || ol.get().state != LoanState.REQUESTED) {
            return Outcome.fail("Brak takiego wniosku.");
        }
        final Loan l = ol.get();
        l.note = note == null ? "" : note.strip();
        l.decidedBy = "WorldSteward";
        final Firm f = this.ctx().data.firms.get(l.firm);
        if (!approve) {
            l.state = LoanState.REJECTED;
            this.log(l, "odrzucony przez właściciela" + (l.note.isEmpty() ? "" : ": " + l.note));
            this.save.run();
            if (f != null) {
                this.ctx().partners(f, "<gold>[Firmy]</gold> Skarb Świata odrzucił wniosek o kredyt" + (l.note.isEmpty() ? "." : ": " + l.note));
            }
            return Outcome.ok("Wniosek odrzucony.");
        }
        if (f == null) {
            return Outcome.fail("Firma już nie istnieje.");
        }
        final Optional<String> p = this.termsProblem(f, l.principal, l.interestBp, l.days);
        if (p.isPresent()) {
            return Outcome.fail("Warunki nie są już spełnione: " + p.get());
        }
        if (this.ctx().bank.balance(l.account()) < l.principal) {
            final Optional<String> refused = this.courts.allocateLoan(owner, l.account(), l.principal - this.ctx().bank.balance(l.account()), f.name);
            if (refused.isPresent()) {
                return Outcome.fail("Skarb Świata nie przelał środków: " + refused.get());
            }
        }
        this.log(l, "zatwierdzony przez właściciela");
        return this.disburse(l, f);
    }

    /** Owner returns repayments collected in a World Treasury loan escrow to the reserve. */
    public Outcome sweepWorld(final String id, final UUID owner) {
        if (!FirmyPanel.owner(owner)) {
            return Outcome.fail("Tylko właściciel.");
        }
        final Optional<Loan> ol = this.get(id);
        if (ol.isEmpty() || ol.get().lender != Lender.WORLD) {
            return Outcome.fail("To nie jest kredyt Skarbu Świata.");
        }
        final Loan l = ol.get();
        final long held = this.ctx().bank.balance(l.account());
        if (held <= 0 || l.state == LoanState.REQUESTED) {
            return Outcome.fail("W depozycie kredytu nie ma spłat do zwrotu.");
        }
        final Optional<String> refused = this.courts.returnLoan(owner, l.account(), held);
        if (refused.isPresent()) {
            return Outcome.fail(refused.get());
        }
        l.returnedToReserve += held;
        this.log(l, "zwrot " + this.ctx().fmt(held) + " do rezerwy");
        this.save.run();
        return Outcome.ok("Zwrócono " + this.ctx().fmt(held) + " do rezerwy Skarbu Świata.");
    }

    // ------------------------------------------------------------------ players and companies

    /**
     * A player ({@code lenderFirm} null) or a company (its master) offers a loan to {@code borrower}: the money goes to
     * the escrow now and waits for the borrower's master.
     */
    public Outcome offer(final UUID actor, final Firm lenderFirm, final Firm borrower, final long amount, final int interestBp, final int days) {
        if (!borrower.active()) {
            return Outcome.fail("Nie ma takiej firmy.");
        }
        if (borrower.members.containsKey(actor.toString()) && lenderFirm == null) {
            return Outcome.fail("Członek firmy nie pożycza własnej firmie — wpłać do niej (/firma wplac).");
        }
        if (lenderFirm != null) {
            if (!lenderFirm.has(actor, Role.MISTRZ)) {
                return Outcome.fail("Z konta firmy pożycza mistrz.");
            }
            if (lenderFirm.id.equals(borrower.id)) {
                return Outcome.fail("Firma nie pożycza sama sobie.");
            }
            if (lenderFirm.frozen || !lenderFirm.active()) {
                return Outcome.fail("Firma pożyczkodawcy jest zamrożona.");
            }
            if (this.hasLiveAsBorrower(lenderFirm.id, false)) {
                return Outcome.fail("Firma spłacająca kredyt nie może pożyczać (zakaz spłacania kredytu kredytem).");
            }
            if (this.service.treasury.balance(lenderFirm) - this.reserved(lenderFirm) < amount) {
                return Outcome.fail("Firma pożyczkodawcy nie ma tylu wolnych środków.");
            }
        }
        final Optional<String> p = this.termsProblem(borrower, amount, interestBp, days);
        if (p.isPresent()) {
            return Outcome.fail(p.get());
        }
        final String from = lenderFirm == null ? "player:" + actor : lenderFirm.account();
        final String name = lenderFirm == null ? this.ctx().world.playerName(actor) : lenderFirm.name;
        final Loan l = this.newLoan(borrower, lenderFirm == null ? Lender.PLAYER : Lender.FIRM,
            lenderFirm == null ? actor.toString() : lenderFirm.id, name, amount, interestBp, days);
        l.repayTo = from;
        l.state = LoanState.OFFERED;
        l.offerUntil = this.ctx().now() + this.s.loanOfferHours * Ctx.HOUR;
        this.data.loans.put(l.id, l);
        this.save.run();
        final TxResult r = this.ctx().bank.transfer(from, l.account(), amount, "Firmy: depozyt oferty kredytu " + l.id + " dla firmy " + borrower.name
            + " od " + name);
        if (!r.ok()) {
            this.data.loans.remove(l.id);
            this.save.run();
            return Outcome.fail(r == TxResult.INSUFFICIENT_FUNDS ? "Za mało pieniędzy na koncie." : "Bank odrzucił depozyt (" + r + ").");
        }
        if (lenderFirm != null) {
            this.ctx().journal(lenderFirm, "kredyt", "Oferta kredytu dla " + borrower.name + " (" + l.id + ") — w depozycie", -amount,
                this.ctx().world.playerName(actor));
            this.ctx().save();
        }
        this.log(l, "oferta " + name + ": " + this.ctx().fmt(amount) + " w depozycie");
        this.save.run();
        borrower.master().ifPresent(m -> this.ctx().notifier.player(m.id(), "<gold>[Firmy]</gold> " + name + " oferuje firmie „" + borrower.name
            + "” kredyt " + this.ctx().fmt(amount) + " na " + days + " dni, odsetki " + interestBp / 100.0 + " % (do spłaty " + this.ctx().fmt(l.total())
            + "). <click:run_command:'/firma kredyt przyjmij " + l.id + "'><green>[Przyjmij]</green></click> "
            + "<click:run_command:'/firma kredyt odrzuc " + l.id + "'><red>[Odrzuć]</red></click>"));
        return Outcome.ok("Oferta kredytu " + l.id + " złożona: " + this.ctx().fmt(amount) + " czeka w depozycie na decyzję firmy " + borrower.name + ".", l.id);
    }

    private Loan newLoan(final Firm f, final Lender lender, final String lenderId, final String lenderName, final long amount,
                         final int interestBp, final int days) {
        final Loan l = new Loan();
        l.id = Ctx.newId();
        l.firm = f.id;
        l.firmName = f.name;
        l.lender = lender;
        l.lenderId = lenderId;
        l.lenderName = lenderName;
        l.principal = amount;
        l.interestBp = interestBp;
        l.days = days;
        l.createdAt = this.ctx().now();
        this.data.loans.put(l.id, l);
        return l;
    }

    public Outcome accept(final String id, final UUID actor) {
        final Optional<Loan> ol = this.get(id);
        if (ol.isEmpty() || ol.get().state != LoanState.OFFERED) {
            return Outcome.fail("Brak takiej oferty kredytu.");
        }
        final Loan l = ol.get();
        final Firm f = this.ctx().data.firms.get(l.firm);
        if (f == null || !f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("Kredyt przyjmuje mistrz firmy.");
        }
        if (this.hasLiveAsBorrower(f.id, false)) {
            return Outcome.fail("Firma ma już kredyt (najwyżej jeden naraz).");
        }
        final Optional<String> p = this.termsProblem(f, l.principal, l.interestBp, l.days);
        if (p.isPresent()) {
            return Outcome.fail(p.get());
        }
        return this.disburse(l, f);
    }

    private Outcome disburse(final Loan l, final Firm f) {
        final TxResult r = this.ctx().bank.transfer(l.account(), f.account(), l.principal, "Firmy: wypłata kredytu " + l.id + " od " + l.lenderName
            + " (" + l.interestBp / 100.0 + " %, " + l.days + " dni)");
        if (!r.ok()) {
            return Outcome.fail("Bank odrzucił wypłatę kredytu (" + r + ").");
        }
        l.state = LoanState.ACTIVE;
        l.disbursedAt = this.ctx().now();
        l.dueAt = l.disbursedAt + l.days * Ctx.DAY;
        this.log(l, "wypłacono " + this.ctx().fmt(l.principal) + ", do spłaty " + this.ctx().fmt(l.total()));
        this.ctx().journal(f, "kredyt", "Kredyt od " + l.lenderName + " (" + l.id + "), do spłaty " + this.ctx().fmt(l.total()) + " w "
            + l.days + " dni", l.principal, "system");
        f.lastActivity = this.ctx().now();
        this.save.run();
        this.ctx().save();
        this.ctx().partners(f, "<gold>[Firmy]</gold> Firma „" + f.name + "” wzięła kredyt " + this.ctx().fmt(l.principal) + " od " + l.lenderName
            + ". Spłata " + this.ctx().fmt(l.total()) + ": automatycznie " + this.s.repayFromIncomePct + " % każdego przychodu, reszta w terminie.");
        this.notifyLender(l, "<gold>[Firmy]</gold> Firma „" + f.name + "” przyjęła twój kredyt " + l.id + " (" + this.ctx().fmt(l.principal) + ").");
        return Outcome.ok("Kredyt " + l.id + " wypłacony: " + this.ctx().fmt(l.principal) + ", do spłaty " + this.ctx().fmt(l.total()) + ".", l.id);
    }

    public Outcome refuse(final String id, final UUID actor, final boolean lenderSide) {
        final Optional<Loan> ol = this.get(id);
        if (ol.isEmpty() || ol.get().state != LoanState.OFFERED && ol.get().state != LoanState.REQUESTED) {
            return Outcome.fail("Brak takiej oferty.");
        }
        final Loan l = ol.get();
        final Firm f = this.ctx().data.firms.get(l.firm);
        final boolean borrowerMaster = f != null && f.has(actor, Role.MISTRZ);
        final boolean lender = l.lender == Lender.PLAYER && actor.toString().equals(l.lenderId)
            || l.lender == Lender.FIRM && this.ctx().data.firms.containsKey(l.lenderId) && this.ctx().data.firms.get(l.lenderId).has(actor, Role.MISTRZ);
        if (!(lenderSide ? lender : borrowerMaster)) {
            return Outcome.fail(lenderSide ? "Ofertę wycofuje pożyczkodawca." : "Ofertę odrzuca mistrz firmy.");
        }
        return this.close(l, lenderSide ? LoanState.CANCELLED : LoanState.REJECTED, lenderSide ? "wycofana przez pożyczkodawcę" : "odrzucona przez firmę");
    }

    private Outcome close(final Loan l, final LoanState state, final String why) {
        final long held = this.ctx().bank.balance(l.account());
        if (held > 0 && l.lender != Lender.WORLD) {
            final TxResult r = this.ctx().bank.transfer(l.account(), l.repayTo, held, "Firmy: zwrot depozytu oferty kredytu " + l.id + " (" + why + ")");
            if (!r.ok()) {
                return Outcome.fail("Bank odrzucił zwrot (" + r + ").");
            }
            if (l.lender == Lender.FIRM) {
                final Firm lf = this.ctx().data.firms.get(l.lenderId);
                if (lf != null) {
                    this.ctx().journal(lf, "kredyt", "Zwrot depozytu oferty kredytu " + l.id + " (" + why + ")", held, "system");
                    this.ctx().save();
                }
            }
        }
        l.state = state;
        this.log(l, why);
        this.save.run();
        this.notifyLender(l, "<gold>[Firmy]</gold> Oferta kredytu " + l.id + " dla " + l.firmName + ": " + why + ".");
        return Outcome.ok("Oferta kredytu " + l.id + " " + why + ".");
    }

    // ------------------------------------------------------------------ repayment

    /** Money of the company reserved for its loan (no dividends from it). */
    public long reserved(final Firm f) {
        return this.active(f).map(Loan::outstanding).orElse(0L);
    }

    /** Automatic repayment: a share of every net income goes to the lender. */
    public void onIncome(final Firm f, final long net) {
        final Optional<Loan> ol = this.active(f);
        if (ol.isEmpty() || net <= 0 || f.frozen) {
            return;
        }
        final long part = Math.min(ol.get().outstanding(), Math.max(1, net * this.s.repayFromIncomePct / 100));
        this.repay(f, ol.get(), Math.min(part, this.service.treasury.balance(f)), "z przychodu");
    }

    private long repay(final Firm f, final Loan l, final long amount, final String how) {
        if (amount <= 0) {
            return 0;
        }
        final TxResult r = this.ctx().bank.transfer(f.account(), l.repayTo, amount, "Firmy: spłata kredytu " + l.id + " (" + how + ") dla "
            + l.lenderName);
        if (!r.ok()) {
            return 0;
        }
        l.repaid += amount;
        this.ctx().journal(f, "kredyt", "Spłata kredytu " + l.id + " (" + how + ")", -amount, "system");
        if (l.lender == Lender.FIRM) {
            final Firm lf = this.ctx().data.firms.get(l.lenderId);
            if (lf != null) {
                this.ctx().journal(lf, "kredyt", "Spłata kredytu " + l.id + " od " + f.name, amount, "system");
            }
        }
        if (l.outstanding() == 0) {
            l.state = LoanState.REPAID;
            this.log(l, "spłacony w całości");
            this.ctx().partners(f, "<gold>[Firmy]</gold> Kredyt " + l.id + " spłacony w całości.");
            this.notifyLender(l, "<gold>[Firmy]</gold> Firma „" + f.name + "” spłaciła kredyt " + l.id + " (" + this.ctx().fmt(l.total()) + ").");
        }
        this.save.run();
        this.ctx().save();
        return amount;
    }

    /** Daily: expired offers back to lenders, due loans paid from the balance, long overdue ones end in liquidation. */
    public void daily() {
        final long now = this.ctx().now();
        for (final Loan l : List.copyOf(this.data.loans.values())) {
            if (l.state == LoanState.OFFERED && l.offerUntil <= now) {
                this.close(l, LoanState.CANCELLED, "oferta wygasła");
            } else if (l.state == LoanState.REQUESTED && now - l.createdAt > 14 * Ctx.DAY) {
                l.state = LoanState.REJECTED;
                this.log(l, "wniosek wygasł bez decyzji");
                this.save.run();
            } else if (l.state == LoanState.ACTIVE && l.dueAt <= now) {
                final Firm f = this.ctx().data.firms.get(l.firm);
                if (f == null || !f.active() || f.frozen) {
                    // a frozen company moves no money and is not liquidated automatically (administrator decides)
                    continue;
                }
                this.repay(f, l, Math.min(l.outstanding(), this.service.treasury.balance(f)), "w terminie");
                if (l.state == LoanState.ACTIVE) {
                    final long overdue = (now - l.dueAt) / Ctx.DAY;
                    this.ctx().partners(f, "<gold>[Firmy]</gold> <red>Kredyt " + l.id + " po terminie</red>: brakuje " + this.ctx().fmt(l.outstanding())
                        + " (dzień " + overdue + "). Po " + this.s.overdueLiquidationDays + " dniach — likwidacja przymusowa.");
                    if (overdue >= this.s.overdueLiquidationDays) {
                        this.service.treasury.liquidate(f, "likwidacja przymusowa: niespłacony kredyt " + l.id, "system");
                    }
                }
            }
        }
    }

    /** Liquidation: the lender is paid right after the contract deposits (before salary arrears). */
    public List<Extension.Claim> claims(final Firm f) {
        final List<Extension.Claim> out = new ArrayList<>();
        this.active(f).ifPresent(l -> out.add(new Extension.Claim(l.repayTo, l.outstanding(), "kredyt " + l.id + " (" + l.lenderName + ")", paid -> {
            l.repaid += paid;
            l.state = l.outstanding() == 0 ? LoanState.REPAID : LoanState.DEFAULTED;
            this.log(l, "likwidacja dłużnika: spłacono " + this.ctx().fmt(paid) + (l.outstanding() > 0 ? ", przepada " + this.ctx().fmt(l.outstanding()) : ""));
            this.notifyLender(l, "<gold>[Firmy]</gold> Likwidacja firmy „" + f.name + "”: z kredytu " + l.id + " odzyskano " + this.ctx().fmt(paid) + ".");
            this.save.run();
        })));
        // offers waiting for this borrower go back to their lenders
        for (final Loan l : List.copyOf(this.data.loans.values())) {
            if (f.id.equals(l.firm) && (l.state == LoanState.OFFERED || l.state == LoanState.REQUESTED)) {
                this.close(l, LoanState.CANCELLED, "firma w likwidacji");
            }
        }
        return out;
    }

    private void notifyLender(final Loan l, final String msg) {
        switch (l.lender) {
            case PLAYER -> this.ctx().notifier.player(UUID.fromString(l.lenderId), msg);
            case FIRM -> {
                final Firm lf = this.ctx().data.firms.get(l.lenderId);
                if (lf != null) {
                    this.ctx().partners(lf, msg);
                }
            }
            case WORLD -> this.ctx().notifier.player(FirmyPanel.OWNER, msg);
        }
    }

    /** World Treasury loans for the owner's card: applications, active ones, repayments waiting in escrows. */
    public List<Loan> world() {
        return this.data.loans.values().stream().filter(l -> l.lender == Lender.WORLD).toList();
    }
}
