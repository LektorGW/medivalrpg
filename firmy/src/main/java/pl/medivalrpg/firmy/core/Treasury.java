// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmType;
import pl.medivalrpg.firmy.model.FirmsData;
import pl.medivalrpg.firmy.model.Role;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Money of companies: contributions, income and tax, dividends (only proportional to shares, large ones wait 24 h),
 * daily salaries with arrears, insolvency, dormancy and liquidation. Every movement is a transfer between existing
 * accounts; a liquidation empties the company account to the last copper.
 */
public final class Treasury {

    private final Ctx ctx;
    private final Registry registry;
    private final List<Extension> extensions;
    /** Voluntary liquidation votes: firm → (voters, expires). */
    private final Map<String, Set<UUID>> votes = new HashMap<>();
    private final Map<String, Long> votesExpire = new HashMap<>();

    public Treasury(final Ctx ctx, final Registry registry, final List<Extension> extensions) {
        this.ctx = ctx;
        this.registry = registry;
        this.extensions = extensions;
    }

    private Settings s() {
        return this.ctx.settings;
    }

    public long balance(final Firm f) {
        return this.ctx.bank.balance(f.account());
    }

    private long reserved(final Firm f) {
        long r = 0;
        for (final Extension e : this.extensions) {
            r += Math.max(0, e.reserved(f));
        }
        return r;
    }

    // ------------------------------------------------------------------ contributions

    /** {@code /firma wplac}: any member pays from their account into the company (capital, not income: no tax). */
    public Outcome contribute(final Firm f, final UUID player, final long amount) {
        if (!f.active()) {
            return Outcome.fail("Firma jest zlikwidowana.");
        }
        final Optional<Firm.Member> m = f.member(player);
        if (m.isEmpty()) {
            return Outcome.fail("Wpłacają członkowie firmy.");
        }
        if (amount <= 0) {
            return Outcome.fail("Podaj dodatnią kwotę.");
        }
        final TxResult r = this.ctx.bank.transfer("player:" + player, f.account(), amount,
            "Firmy: wpłata do firmy " + f.name + " (" + m.get().name + ")");
        if (!r.ok()) {
            return Outcome.fail(r == TxResult.INSUFFICIENT_FUNDS ? "Za mało pieniędzy na koncie." : "Bank odrzucił wpłatę (" + r + ").");
        }
        f.lastActivity = this.ctx.now();
        f.dormantSince = 0;
        this.ctx.journal(f, "wplata", "Wpłata od " + m.get().name, amount, m.get().name);
        this.payArrears(f);
        this.ctx.save();
        return Outcome.ok("Wpłacono " + this.ctx.fmt(amount) + " do firmy „" + f.name + "”. Saldo: " + this.ctx.fmt(this.balance(f)) + ".");
    }

    // ------------------------------------------------------------------ income and tax

    /** Seat-country tax rate (basis points) and treasury account; empty when the seat is lost. */
    public Optional<Map.Entry<String, Integer>> taxTarget(final Firm f) {
        if (f.seatCountry == null) {
            return Optional.empty();
        }
        final UUID seat = UUID.fromString(f.seatCountry);
        return this.ctx.world.country(seat).map(c -> Map.entry(c.treasuryAccount(), this.registry.taxBp(seat)));
    }

    public static long tax(final long gross, final int bp) {
        if (gross <= 0 || bp <= 0) {
            return 0;
        }
        return Math.multiplyExact(gross, (long) bp) / 10_000L;
    }

    /** Records income that already reached the company account (shop sales: Skarbiec moved and taxed the money). */
    public void recordIncome(final Firm f, final long gross, final long tax, final String what) {
        if (gross <= 0) {
            return;
        }
        final String day = this.ctx.today();
        f.incomeByDay.merge(day, gross, Long::sum);
        f.incomeTotal += gross;
        if (tax > 0) {
            f.taxByDay.merge(day, tax, Long::sum);
            f.taxTotal += tax;
        }
        f.lastActivity = this.ctx.now();
        f.dormantSince = 0;
        this.ctx.journal(f, "przychod", what + (tax > 0 ? " (podatek " + this.ctx.fmt(tax) + ")" : ""), gross - tax, null);
        final long net = gross - tax;
        for (final Extension e : this.extensions) {
            e.onIncome(f, net, what);
        }
        this.payArrears(f);
        this.ctx.save();
    }

    /**
     * Income paid by Firmy itself (contracts, wholesale orders): {@code payer} → company (net) and → seat treasury (tax),
     * atomically. Refused while the seat country is lost (nobody to tax).
     */
    public Outcome collect(final Firm f, final String payer, final long gross, final String what) {
        return this.collect(f, payer, gross, what, List.of());
    }

    /** As {@link #collect(Firm, String, long, String)} with further legs in the same atomic batch (e.g. a returned bond). */
    public Outcome collect(final Firm f, final String payer, final long gross, final String what, final List<Bank.Leg> extra) {
        final Optional<Map.Entry<String, Integer>> t = this.taxTarget(f);
        if (t.isEmpty()) {
            return Outcome.fail("Firma nie ma kraju siedziby — najpierw przenieś siedzibę.");
        }
        final long tax = tax(gross, t.get().getValue());
        final List<Bank.Leg> legs = new ArrayList<>();
        if (gross - tax > 0) {
            legs.add(new Bank.Leg(payer, f.account(), gross - tax, "Firmy: " + what + " — przychód firmy " + f.name));
        }
        if (tax > 0) {
            legs.add(new Bank.Leg(payer, t.get().getKey(), tax, "Firmy: podatek od przychodu firmy " + f.name + " (" + what + ")"));
        }
        legs.addAll(extra);
        if (legs.isEmpty()) {
            return Outcome.ok("Brak kwoty.");
        }
        final FirmsData.Intent i = this.ctx.intent(f.id, "przychod", what, legs);
        final TxResult r = this.ctx.bank.transferAll(legs);
        if (!r.ok()) {
            this.ctx.drop(i);
            return Outcome.fail("Bank odrzucił wypłatę (" + r + ").");
        }
        this.recordIncome(f, gross, tax, what);
        this.ctx.settle(i);
        return Outcome.ok("Przychód " + this.ctx.fmt(gross) + ", podatek " + this.ctx.fmt(tax) + ".");
    }

    // ------------------------------------------------------------------ dividends

    public Outcome dividend(final Firm f, final UUID actor, final long amount) {
        if (!f.active()) {
            return Outcome.fail("Firma jest zlikwidowana.");
        }
        if (!f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("Dywidendę ogłasza mistrz.");
        }
        if (f.frozen) {
            return Outcome.fail("Firma jest zamrożona (" + f.frozenReason + "): wypłaty wstrzymane.");
        }
        if (amount <= 0) {
            return Outcome.fail("Podaj dodatnią kwotę.");
        }
        if (f.totalArrears() > 0) {
            return Outcome.fail("Najpierw trzeba wypłacić zaległe pensje (" + this.ctx.fmt(f.totalArrears()) + ").");
        }
        final long free = this.balance(f) - this.reserved(f);
        if (amount > free) {
            return Outcome.fail("Firma może wypłacić najwyżej " + this.ctx.fmt(Math.max(0, free)) + ".");
        }
        this.rollPayoutDay(f);
        final long balance = this.balance(f);
        final boolean large = amount * 10_000L > balance * (long) this.s().payoutBalanceBp
            || f.paidOutToday + amount > this.s().payoutDailyLimit;
        final String who = this.ctx.world.playerName(actor);
        if (large && this.s().payoutDelayHours > 0) {
            final long now = this.ctx.now();
            final Firm.Payout p = new Firm.Payout(Ctx.newId(), amount, now, now + this.s().payoutDelayHours * Ctx.HOUR, who);
            f.payouts.add(p);
            this.ctx.journal(f, "dywidenda", "Duża dywidenda " + this.ctx.fmt(amount) + " — wypłata za " + this.s().payoutDelayHours + " h", 0, who);
            this.ctx.save();
            this.ctx.partners(f, "<gold>[Firmy]</gold> <yellow>" + who + "</yellow> ogłosił dywidendę <yellow>" + this.ctx.fmt(amount)
                + "</yellow> w „" + f.name + "”. To duża wypłata: nastąpi za " + this.s().payoutDelayHours + " h.");
            return Outcome.ok("Dywidenda " + this.ctx.fmt(amount) + " przekracza próg (" + (this.s().payoutBalanceBp / 100) + " % salda albo "
                + this.ctx.fmt(this.s().payoutDailyLimit) + " dziennie): wypłata za " + this.s().payoutDelayHours + " h, wspólnicy powiadomieni.", p.id);
        }
        return this.payDividend(f, amount, who);
    }

    private void rollPayoutDay(final Firm f) {
        final String today = this.ctx.today();
        if (!today.equals(f.payoutDay)) {
            f.payoutDay = today;
            f.paidOutToday = 0;
        }
    }

    /** Dividend split over all shareholders by shares (largest remainder: exact to the copper). */
    Outcome payDividend(final Firm f, final long amount, final String who) {
        final Map<String, Long> weights = new LinkedHashMap<>();
        for (final Firm.Member m : f.members.values()) {
            if (m.shareBp > 0) {
                weights.put(m.uuid, (long) m.shareBp);
            }
        }
        if (weights.isEmpty()) {
            return Outcome.fail("Nikt nie ma udziałów.");
        }
        final Map<String, Long> parts = Split.proportional(amount, weights);
        final List<Bank.Leg> legs = new ArrayList<>();
        for (final var e : parts.entrySet()) {
            if (e.getValue() > 0) {
                legs.add(new Bank.Leg(f.account(), "player:" + e.getKey(), e.getValue(),
                    "Firmy: dywidenda firmy " + f.name + " (" + People.pct(f.members.get(e.getKey()).shareBp) + " udziałów)"));
            }
        }
        final FirmsData.Intent i = this.ctx.intent(f.id, "dywidenda", "Dywidenda " + amount, legs);
        final TxResult r = this.ctx.bank.transferAll(legs);
        if (!r.ok()) {
            this.ctx.drop(i);
            return Outcome.fail(r == TxResult.INSUFFICIENT_FUNDS ? "Za mało pieniędzy na koncie firmy." : "Bank odrzucił wypłatę (" + r + ").");
        }
        this.rollPayoutDay(f);
        f.paidOutToday += amount;
        f.lastActivity = this.ctx.now();
        this.ctx.journal(f, "dywidenda", "Dywidenda dla " + legs.size() + " udziałowców", -amount, who);
        for (final var e : parts.entrySet()) {
            if (e.getValue() > 0) {
                this.ctx.notifier.player(UUID.fromString(e.getKey()), "<gold>[Firmy]</gold> Dywidenda z „" + f.name + "”: <yellow>"
                    + this.ctx.fmt(e.getValue()) + "</yellow> na twoje konto.");
            }
        }
        this.ctx.settle(i);
        return Outcome.ok("Wypłacono dywidendę " + this.ctx.fmt(amount) + " (" + legs.size() + " udziałowców, według udziałów).");
    }

    public Outcome cancelPayout(final Firm f, final UUID actor, final boolean admin, final String payoutId) {
        if (!admin && !f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("Wypłatę odwołuje mistrz albo administrator.");
        }
        final boolean removed = f.payouts.removeIf(p -> payoutId == null || payoutId.isBlank() || p.id.equals(payoutId));
        if (!removed) {
            return Outcome.fail("Brak oczekującej wypłaty.");
        }
        this.ctx.journal(f, "dywidenda", "Odwołano oczekującą dywidendę", 0, admin ? "admin" : this.ctx.world.playerName(actor));
        this.ctx.save();
        return Outcome.ok("Odwołano oczekującą wypłatę.");
    }

    private void duePayouts(final Firm f) {
        final long now = this.ctx.now();
        for (final Firm.Payout p : List.copyOf(f.payouts)) {
            if (p.dueAt > now) {
                continue;
            }
            f.payouts.remove(p);
            if (f.frozen || f.totalArrears() > 0 || this.balance(f) - this.reserved(f) < p.amount) {
                this.ctx.journal(f, "dywidenda", "Oczekująca dywidenda " + this.ctx.fmt(p.amount) + " anulowana (brak wolnych środków, zaległości albo zamrożenie)", 0, "system");
                this.ctx.partners(f, "<gold>[Firmy]</gold> Oczekująca dywidenda „" + f.name + "” anulowana: brak wolnych środków.");
                continue;
            }
            this.payDividend(f, p.amount, p.by);
        }
    }

    // ------------------------------------------------------------------ salaries

    /** Pays arrears (oldest first) while the company has free money; no new arrears are created here. */
    public long payArrears(final Firm f) {
        if (f.arrears.isEmpty() || f.frozen) {
            return 0;
        }
        long paid = 0;
        for (final String who : List.copyOf(f.arrears.keySet())) {
            final long owed = f.arrears.getOrDefault(who, 0L);
            // salaries are operating costs: paid even from money reserved for a loan (only dividends respect the reserve)
            final long can = Math.min(owed, this.balance(f));
            if (can <= 0) {
                break;
            }
            if (this.paySalary(f, who, can, "zaległa pensja")) {
                paid += can;
                if (can == owed) {
                    f.arrears.remove(who);
                } else {
                    f.arrears.put(who, owed - can);
                }
            }
        }
        if (f.arrears.isEmpty()) {
            f.unpaidDays = 0;
        }
        return paid;
    }

    private boolean paySalary(final Firm f, final String uuid, final long amount, final String what) {
        final List<Bank.Leg> legs = List.of(new Bank.Leg(f.account(), "player:" + uuid, amount, "Firmy: " + what + " w firmie " + f.name));
        final FirmsData.Intent i = this.ctx.intent(f.id, "pensja", what + " " + uuid, legs);
        final TxResult r = this.ctx.bank.transferAll(legs);
        if (!r.ok()) {
            this.ctx.drop(i);
            return false;
        }
        final Firm.Member m = f.members.get(uuid);
        final String name = m != null ? m.name : this.ctx.world.playerName(UUID.fromString(uuid));
        this.ctx.journal(f, "pensja", (what.startsWith("zaległa") ? "Zaległa pensja: " : "Pensja: ") + name, -amount, "system");
        this.ctx.notifier.player(UUID.fromString(uuid), "<gold>[Firmy]</gold> " + (what.startsWith("zaległa") ? "Zaległa pensja" : "Pensja")
            + " z „" + f.name + "”: <yellow>" + this.ctx.fmt(amount) + "</yellow>.");
        this.ctx.settle(i);
        return true;
    }

    /** Daily salaries of one company: arrears first, then today's salaries; what cannot be paid becomes arrears. */
    void daySalaries(final Firm f) {
        this.payArrears(f);
        boolean unpaid = !f.arrears.isEmpty();
        for (final Firm.Member m : List.copyOf(f.members.values())) {
            if (m.salary <= 0) {
                continue;
            }
            final long free = this.balance(f);
            if (!f.frozen && !unpaid && free >= m.salary && this.paySalary(f, m.uuid, m.salary, "pensja")) {
                continue;
            }
            f.arrears.merge(m.uuid, m.salary, Long::sum);
            unpaid = true;
        }
        if (unpaid) {
            f.unpaidDays++;
            this.ctx.journal(f, "pensja", "Brak środków na pensje — zaległości " + this.ctx.fmt(f.totalArrears()) + " (dzień " + f.unpaidDays + ")", 0, "system");
            this.ctx.partners(f, "<gold>[Firmy]</gold> <red>Firmie „" + f.name + "” brakuje na pensje</red> (zaległości "
                + this.ctx.fmt(f.totalArrears()) + ", dzień " + f.unpaidDays + "). Wpłać: /firma wplac <kwota>.");
            if (f.unpaidDays == this.s().insolventDays) {
                this.ctx.members(f, "<gold>[Firmy]</gold> <red>Firma „" + f.name + "” jest niewypłacalna.</red> Bez spłaty zaległości po "
                    + this.s().liquidationDays + " dniach nastąpi likwidacja przymusowa.");
                for (final Extension e : this.extensions) {
                    e.onInsolvent(f);
                }
            }
        } else {
            f.unpaidDays = 0;
        }
    }

    public boolean insolvent(final Firm f) {
        return f.unpaidDays >= this.s().insolventDays;
    }

    // ------------------------------------------------------------------ daily settlement

    /** Daily settlement of every company (salaries, insolvency, dormancy, due payouts, statistics). */
    public void daily() {
        final long now = this.ctx.now();
        final String cutoff = this.ctx.windowStart(35);
        for (final Firm f : List.copyOf(this.ctx.data.firms.values())) {
            if (!f.active()) {
                continue;
            }
            f.incomeByDay.headMap(cutoff, false).clear();
            f.taxByDay.headMap(cutoff, false).clear();
            if (f.seatCountry != null && this.ctx.world.country(UUID.fromString(f.seatCountry)).isEmpty()) {
                if (f.seatLostAt == 0) {
                    f.seatLostAt = now;
                    this.ctx.journal(f, "siedziba", "Kraj siedziby przestał istnieć — sklepy stoją do przeniesienia siedziby", 0, "system");
                    this.ctx.members(f, "<gold>[Firmy]</gold> <red>Kraj siedziby firmy „" + f.name + "” upadł.</red> Przenieś siedzibę: "
                        + "stań na ziemi innego kraju i wpisz /firma siedziba.");
                }
            }
            if (f.frozen) {
                // a frozen company (audit or administrator) stands still: no new salary arrears, no automatic insolvency,
                // dormancy or liquidation — the clock runs again once it is unfrozen
                f.lastActivity = Math.max(f.lastActivity, now);
                continue;
            }
            final long idleDays = (now - f.lastActivity) / Ctx.DAY;
            if (idleDays >= this.s().dormantLiquidationDays) {
                this.liquidate(f, "automatyczna likwidacja po " + idleDays + " dniach bez aktywności", "system");
                continue;
            }
            if (idleDays >= this.s().dormantDays && f.dormantSince == 0) {
                f.dormantSince = now;
                this.ctx.journal(f, "uspienie", "Firma uśpiona po " + idleDays + " dniach bez aktywności — sklepy stoją", 0, "system");
            }
            this.duePayouts(f);
            this.daySalaries(f);
            if (f.unpaidDays >= this.s().liquidationDays) {
                this.liquidate(f, "likwidacja przymusowa: " + f.unpaidDays + " dni bez spłaty zaległych pensji", "system");
            }
        }
        for (final Extension e : this.extensions) {
            e.daily(now);
        }
        this.ctx.save();
    }

    /** Hourly: due large payouts (they should not wait for the daily settlement). */
    public void hourly() {
        for (final Firm f : List.copyOf(this.ctx.data.firms.values())) {
            if (f.active() && !f.payouts.isEmpty()) {
                this.duePayouts(f);
            }
        }
    }

    /** A member joined the server: the company is active again. */
    public void activity(final UUID player) {
        for (final Firm f : this.ctx.firmsOf(player)) {
            f.lastActivity = this.ctx.now();
            if (f.dormantSince != 0) {
                f.dormantSince = 0;
                this.ctx.journal(f, "uspienie", "Firma znów aktywna", 0, this.ctx.world.playerName(player));
            }
        }
    }

    // ------------------------------------------------------------------ liquidation

    public Outcome voteLiquidation(final Firm f, final UUID player) {
        if (!f.active()) {
            return Outcome.fail("Firma jest już zlikwidowana.");
        }
        final Optional<Firm.Member> m = f.member(player);
        if (m.isEmpty() || m.get().shareBp <= 0) {
            return Outcome.fail("O likwidacji głosują udziałowcy.");
        }
        for (final Extension e : this.extensions) {
            final Optional<String> block = e.blocksVoluntaryLiquidation(f);
            if (block.isPresent()) {
                return Outcome.fail(block.get());
            }
        }
        final long now = this.ctx.now();
        if (this.votesExpire.getOrDefault(f.id, 0L) < now) {
            this.votes.remove(f.id);
        }
        final Set<UUID> v = this.votes.computeIfAbsent(f.id, k -> new HashSet<>());
        v.add(player);
        this.votesExpire.put(f.id, now + Ctx.DAY);
        int yes = 0;
        for (final UUID u : v) {
            yes += f.member(u).map(x -> x.shareBp).orElse(0);
        }
        if (yes * 2 > f.totalShares()) {
            this.votes.remove(f.id);
            return this.liquidate(f, "likwidacja dobrowolna (" + People.pct(yes) + " udziałów za)", this.ctx.world.playerName(player));
        }
        this.ctx.partners(f, "<gold>[Firmy]</gold> " + m.get().name + " głosuje za likwidacją „" + f.name + "” (za: " + People.pct(yes)
            + "). Potrzeba ponad 50 % udziałów. <click:run_command:'/firma likwiduj " + f.id + "'><red>[Głosuję za]</red></click>");
        return Outcome.ok("Głos zapisany: za likwidacją " + People.pct(yes) + " udziałów (potrzeba ponad 50 %, ważne 24 h).");
    }

    /**
     * Liquidation: open contracts first (extensions), then priority creditors (loans), then salary arrears (proportional),
     * the rest to the shareholders by shares. One atomic batch; the company account ends at 0.
     */
    public Outcome liquidate(final Firm f, final String reason, final String by) {
        if (!f.active()) {
            return Outcome.fail("Firma jest już zlikwidowana.");
        }
        for (final Extension e : this.extensions) {
            e.beforeLiquidation(f);
        }
        long left = this.balance(f);
        final List<Bank.Leg> legs = new ArrayList<>();
        final List<Runnable> after = new ArrayList<>();
        final StringBuilder summary = new StringBuilder();
        for (final Extension e : this.extensions) {
            for (final Extension.Claim c : e.liquidationClaims(f)) {
                final long pay = Math.min(left, Math.max(0, c.amount()));
                if (pay > 0) {
                    legs.add(new Bank.Leg(f.account(), c.to(), pay, "Firmy: likwidacja " + f.name + " — " + c.label()));
                    left -= pay;
                }
                after.add(() -> c.paid().accept(pay));
                summary.append(c.label()).append(": ").append(this.ctx.fmt(pay)).append("; ");
            }
        }
        final long arrears = f.totalArrears();
        long arrearsPaid = 0;
        if (arrears > 0 && left > 0) {
            final long pool = Math.min(left, arrears);
            arrearsPaid = pool;
            final Map<String, Long> parts = Split.proportional(pool, new LinkedHashMap<>(f.arrears));
            for (final var e : parts.entrySet()) {
                if (e.getValue() > 0) {
                    legs.add(new Bank.Leg(f.account(), "player:" + e.getKey(), e.getValue(), "Firmy: likwidacja " + f.name + " — zaległa pensja"));
                }
            }
            left -= pool;
            summary.append("zaległe pensje: ").append(this.ctx.fmt(pool)).append(" z ").append(this.ctx.fmt(arrears)).append("; ");
        }
        if (left > 0) {
            final Map<String, Long> weights = new LinkedHashMap<>();
            for (final Firm.Member m : f.members.values()) {
                if (m.shareBp > 0) {
                    weights.put(m.uuid, (long) m.shareBp);
                }
            }
            if (weights.isEmpty()) {
                f.master().ifPresent(m -> weights.put(m.uuid, 1L));
            }
            final Map<String, Long> parts = Split.proportional(left, weights);
            for (final var e : parts.entrySet()) {
                if (e.getValue() > 0) {
                    legs.add(new Bank.Leg(f.account(), "player:" + e.getKey(), e.getValue(), "Firmy: likwidacja " + f.name + " — podział majątku"));
                }
            }
            summary.append("wspólnicy: ").append(this.ctx.fmt(left));
        }
        // merge legs to the same account (Skarbiec batches accept duplicates, but one leg per recipient reads better)
        final Map<String, Long> merged = new LinkedHashMap<>();
        final Map<String, String> reasons = new HashMap<>();
        for (final Bank.Leg l : legs) {
            merged.merge(l.to(), l.amount(), Long::sum);
            reasons.merge(l.to(), l.reason(), (a, b) -> a.equals(b) ? a : "Firmy: likwidacja " + f.name);
        }
        final List<Bank.Leg> finalLegs = new ArrayList<>();
        merged.forEach((to, amount) -> finalLegs.add(new Bank.Leg(f.account(), to, amount, reasons.get(to))));
        if (!finalLegs.isEmpty()) {
            final FirmsData.Intent i = this.ctx.intent(f.id, "likwidacja", reason, finalLegs);
            final TxResult r = this.ctx.bank.transferAll(finalLegs);
            if (!r.ok()) {
                this.ctx.drop(i);
                f.frozen = true;
                f.frozenReason = "likwidacja nie powiodła się (" + r + ")";
                this.ctx.notifier.admin("Firmy: likwidacja " + f.name + " odrzucona przez bank: " + r, true);
                this.ctx.save();
                return Outcome.fail("Bank odrzucił podział majątku (" + r + "). Firma zamrożona do decyzji administratora.");
            }
            this.ctx.settle(i);
        }
        after.forEach(Runnable::run);
        final long now = this.ctx.now();
        f.state = Firm.State.LIQUIDATED;
        f.liquidatedAt = now;
        f.liquidationReason = reason;
        f.payouts.clear();
        if (arrears > arrearsPaid) {
            this.ctx.journal(f, "likwidacja", "Niespłacona część zaległych pensji przepada: " + this.ctx.fmt(arrears - arrearsPaid), 0, by);
        }
        f.arrears.clear();
        f.unpaidDays = 0;
        f.exMaster = f.master().map(m -> m.uuid).orElse(null);
        this.ctx.data.reservedNames.put(Names.normalize(f.name), now + this.s().nameReservedDays * Ctx.DAY);
        this.ctx.journal(f, "likwidacja", "Likwidacja: " + reason + ". " + summary, 0, by);
        for (final Extension e : this.extensions) {
            e.afterLiquidation(f);
        }
        this.ctx.save();
        this.ctx.members(f, "<gold>[Firmy]</gold> <red>Firma „" + f.name + "” została zlikwidowana</red> (" + reason + "). " + summary);
        this.ctx.notifier.admin("Firmy: likwidacja " + f.name + " (" + f.id + "): " + reason + " — " + summary, false);
        return Outcome.ok("Firma „" + f.name + "” zlikwidowana. " + summary);
    }

    // ------------------------------------------------------------------ administration and the seat country

    public Outcome freeze(final Firm f, final boolean frozen, final String reason) {
        f.frozen = frozen;
        f.frozenReason = frozen ? reason : "";
        this.ctx.journal(f, "admin", frozen ? "Zamrożenie: " + reason : "Odmrożenie" + (reason == null || reason.isBlank() ? "" : ": " + reason), 0, "admin");
        if (!frozen) {
            this.ctx.intents.removeIf(i -> f.id.equals(i.firm));
            this.ctx.store.saveIntentsNow(this.ctx.intents);
        }
        this.ctx.save();
        return Outcome.ok(frozen ? "Firma „" + f.name + "” zamrożona." : "Firma „" + f.name + "” odmrożona.");
    }

    /** Ruler of the seat may suspend the shops of a company whose master is a citizen of an enemy at war. */
    public Outcome warSuspend(final Firm f, final UUID actor, final boolean admin, final boolean suspend) {
        final UUID seat = f.seatCountry == null ? null : UUID.fromString(f.seatCountry);
        if (!admin && (seat == null || this.ctx.world.rank(seat, actor) < 3)) {
            return Outcome.fail("Sklepy firm zawiesza Władca kraju siedziby.");
        }
        if (suspend && !admin) {
            final Optional<UUID> masterCountry = f.master().flatMap(m -> this.ctx.world.countryOf(m.id())).map(GameWorld.Country::id);
            if (masterCountry.isEmpty() || !this.ctx.world.atWar(seat, masterCountry.get())) {
                return Outcome.fail("Można zawiesić tylko firmę, której mistrz jest obywatelem kraju w wojnie z twoim.");
            }
        }
        f.warSuspended = suspend;
        this.ctx.journal(f, "siedziba", suspend ? "Kraj siedziby zawiesił sklepy na czas wojny" : "Zawieszenie sklepów zniesione", 0,
            admin ? "admin" : this.ctx.world.playerName(actor));
        this.ctx.save();
        this.ctx.partners(f, "<gold>[Firmy]</gold> " + (suspend ? "<red>Sklepy „" + f.name + "” zawieszone na czas wojny.</red>"
            : "Sklepy „" + f.name + "” znów działają."));
        return Outcome.ok(suspend ? "Zawieszono sklepy firmy „" + f.name + "”." : "Przywrócono sklepy firmy „" + f.name + "”.");
    }

    /** Is the war suspension still in force (it lapses when the war ends)? */
    public boolean warSuspended(final Firm f) {
        if (!f.warSuspended) {
            return false;
        }
        final Optional<UUID> masterCountry = f.master().flatMap(m -> this.ctx.world.countryOf(m.id())).map(GameWorld.Country::id);
        if (f.seatCountry == null || masterCountry.isEmpty() || !this.ctx.world.atWar(UUID.fromString(f.seatCountry), masterCountry.get())) {
            f.warSuspended = false;
            return false;
        }
        return true;
    }

    /** {@code /firma siedziba}: move the seat to the country the master stands in (no fee). */
    public Outcome moveSeat(final Firm f, final UUID actor, final UUID country) {
        if (!f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("Siedzibę przenosi mistrz.");
        }
        if (country == null) {
            return Outcome.fail("Stań na ziemi kraju, który ma być nową siedzibą.");
        }
        if (country.toString().equals(f.seatCountry)) {
            return Outcome.fail("To już jest kraj siedziby.");
        }
        final Optional<GameWorld.Country> c = this.ctx.world.country(country);
        if (c.isEmpty()) {
            return Outcome.fail("Nie ma takiego kraju.");
        }
        if (f.type == FirmType.MANUFAKTURA && this.ctx.world.era(country) < this.s().manufactoryEra) {
            return Outcome.fail("Manufaktura wymaga epoki Renesansu w kraju siedziby.");
        }
        if (c.get().npc()) {
            final Optional<Integer> rep = this.ctx.world.reputation(country, actor);
            if (rep.isPresent() && rep.get() < this.s().npcMinReputation) {
                return Outcome.fail("Za niska reputacja u " + c.get().name() + ".");
            }
        } else if (this.ctx.world.rank(country, actor) < 2) {
            this.ctx.notifier.countryLeaders(country, "<gold>[Firmy]</gold> Firma „" + f.name + "” chce przenieść siedzibę do twojego kraju (bez opłaty). "
                + "<click:run_command:'/firma kraj przyjmij " + f.id + "'><green>[Przyjmij firmę]</green></click>");
            this.pendingSeat.put(f.id, country);
            return Outcome.ok("Poproszono Władcę/Doradców kraju " + c.get().name() + " o przyjęcie firmy.");
        }
        return this.setSeat(f, c.get(), this.ctx.world.playerName(actor));
    }

    private final Map<String, UUID> pendingSeat = new HashMap<>();

    public Outcome acceptSeat(final String firmId, final UUID actor, final boolean admin) {
        final Firm f = this.ctx.data.firms.get(firmId);
        final UUID country = this.pendingSeat.get(firmId);
        if (f == null || !f.active() || country == null) {
            return Outcome.fail("Brak prośby o przeniesienie siedziby.");
        }
        if (!admin && this.ctx.world.rank(country, actor) < 2) {
            return Outcome.fail("Firmę przyjmuje Władca albo Doradca.");
        }
        this.pendingSeat.remove(firmId);
        final Optional<GameWorld.Country> c = this.ctx.world.country(country);
        if (c.isEmpty()) {
            return Outcome.fail("Nie ma takiego kraju.");
        }
        return this.setSeat(f, c.get(), admin ? "admin" : this.ctx.world.playerName(actor));
    }

    private Outcome setSeat(final Firm f, final GameWorld.Country c, final String by) {
        f.seatCountry = c.id().toString();
        f.seatCountryName = c.name();
        f.seatLostAt = 0;
        f.warSuspended = false;
        this.ctx.journal(f, "siedziba", "Nowa siedziba: " + c.name(), 0, by);
        this.ctx.save();
        this.ctx.members(f, "<gold>[Firmy]</gold> Nowa siedziba firmy „" + f.name + "”: <yellow>" + c.name() + "</yellow>. "
            + "Sklepy działają tylko na ziemi kraju siedziby.");
        return Outcome.ok("Siedziba firmy „" + f.name + "”: " + c.name() + ".");
    }

    /** {@code /firma awans}: cech or kompania with 30-day income over the threshold becomes a trading house. */
    public Outcome promote(final Firm f, final UUID actor) {
        if (!f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("O awans wnioskuje mistrz.");
        }
        if (f.type == FirmType.DOM_HANDLOWY) {
            return Outcome.fail("Firma już jest domem handlowym.");
        }
        if (f.type == FirmType.MANUFAKTURA) {
            return Outcome.fail("Manufaktura pozostaje manufakturą (robotnicy NPC).");
        }
        final long income = f.income(this.ctx.windowStart(30));
        if (income < this.s().promotionIncome) {
            return Outcome.fail("Awans wymaga przychodu z 30 dni co najmniej " + this.ctx.fmt(this.s().promotionIncome) + " (jest " + this.ctx.fmt(income) + ").");
        }
        f.type = FirmType.DOM_HANDLOWY;
        f.promotedAt = this.ctx.now();
        this.ctx.journal(f, "awans", "Awans na Dom handlowy", 0, this.ctx.world.playerName(actor));
        this.ctx.save();
        this.ctx.members(f, "<gold>[Firmy]</gold> „" + f.name + "” jest teraz <gold>Domem handlowym</gold>!");
        return Outcome.ok("„" + f.name + "” awansuje na Dom handlowy.");
    }

    // ------------------------------------------------------------------ shops (decisions for the Skarbiec hook)

    /** Why a company shop may not trade now (empty = it may). {@code buy} = the shop pays (Kupię). */
    public Optional<String> shopBlocked(final Firm f, final boolean buy, final UUID shopCountry) {
        if (!f.active()) {
            return Optional.of("firma jest zlikwidowana");
        }
        if (f.dormantSince != 0) {
            return Optional.of("firma jest uśpiona");
        }
        if (f.seatLostAt != 0 || f.seatCountry == null) {
            return Optional.of("firma nie ma kraju siedziby");
        }
        if (shopCountry == null || !shopCountry.toString().equals(f.seatCountry)) {
            return Optional.of("sklep stoi poza krajem siedziby firmy");
        }
        if (this.warSuspended(f)) {
            return Optional.of("sklepy firmy są zawieszone na czas wojny");
        }
        if (buy && f.frozen) {
            return Optional.of("firma jest zamrożona");
        }
        if (buy && this.insolvent(f)) {
            return Optional.of("firma jest niewypłacalna");
        }
        return Optional.empty();
    }

    /** May the player open company chests (shops and warehouse)? Members always; the former master for N days. */
    public boolean chestAccess(final Firm f, final UUID player) {
        if (f.active()) {
            return f.members.containsKey(player.toString());
        }
        return player.toString().equals(f.exMaster) && this.ctx.now() - f.liquidatedAt < this.s().exMasterDays * Ctx.DAY;
    }
}
