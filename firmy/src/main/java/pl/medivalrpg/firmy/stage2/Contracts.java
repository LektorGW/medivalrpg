// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import pl.medivalrpg.firmy.core.Bank;
import pl.medivalrpg.firmy.core.Ctx;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.core.GameWorld;
import pl.medivalrpg.firmy.core.Names;
import pl.medivalrpg.firmy.core.Outcome;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.Role;
import pl.medivalrpg.firmy.stage2.Stage2Data.Contract;
import pl.medivalrpg.firmy.stage2.Stage2Data.ContractState;
import pl.medivalrpg.firmy.stage2.Stage2Data.Kind;
import pl.medivalrpg.firmy.stage2.Stage2Data.Party;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Contracts with a deposit: the client pays the whole price into {@code firma-kontrakt:<id>} when creating the order;
 * the contractor company may add a bond. Goods are delivered physically (the game moves real items into the client's
 * chest, or to the NPC capital), a service is confirmed by the client. The deposit pays the contractor (taxed as income)
 * only when the condition is met; a missed deadline refunds the client (pro rata for partial goods) and the bond goes
 * to the client. A dispute freezes the deposit until the owner decides in the panel. Pure Java, main thread.
 */
public final class Contracts {

    private final FirmService service;
    private final Stage2Data data;
    private final Courts courts;
    private volatile Stage2Settings s;
    private final Runnable save;

    public Contracts(final FirmService service, final Stage2Data data, final Courts courts, final Stage2Settings settings, final Runnable save) {
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

    private long now() {
        return this.ctx().now();
    }

    private void log(final Contract c, final String text) {
        c.log.add(this.ctx().day(this.now()) + " " + text);
        if (c.log.size() > 40) {
            c.log.remove(0);
        }
    }

    public Optional<Contract> get(final String id) {
        return Optional.ofNullable(id == null ? null : this.data.contracts.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<Contract> forFirm(final String firmId) {
        final List<Contract> out = new ArrayList<>();
        for (final Contract c : this.data.contracts.values()) {
            if (firmId.equals(c.contractor) || c.client == Party.FIRM && firmId.equals(c.clientId) || firmId.equals(c.reservedFor)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Open offers anybody (or the given company) may take. */
    public List<Contract> board(final String firmId) {
        final List<Contract> out = new ArrayList<>();
        for (final Contract c : this.data.contracts.values()) {
            if (c.state == ContractState.OPEN && (c.reservedFor == null || c.reservedFor.equals(firmId))) {
                out.add(c);
            }
        }
        return out;
    }

    private Optional<Firm> firm(final String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(this.ctx().data.firms.get(id));
    }

    // ------------------------------------------------------------------ creating

    /** Who pays: a player, a company (its master) or a country treasury (its ruler). */
    public record Payer(Party party, String id, String name, String account) {
    }

    public Optional<String> payerProblem(final Payer p, final UUID actor) {
        switch (p.party()) {
            case PLAYER -> {
                return actor.toString().equals(p.id()) ? Optional.empty() : Optional.of("Płacić z konta gracza może tylko on sam.");
            }
            case FIRM -> {
                final Optional<Firm> f = this.firm(p.id());
                if (f.isEmpty() || !f.get().active()) {
                    return Optional.of("Nie ma takiej firmy.");
                }
                if (!f.get().has(actor, Role.MISTRZ)) {
                    return Optional.of("Z konta firmy zleca mistrz.");
                }
                if (f.get().frozen) {
                    return Optional.of("Firma jest zamrożona.");
                }
                return Optional.empty();
            }
            case COUNTRY -> {
                return this.ctx().world.rank(UUID.fromString(p.id()), actor) >= 3 ? Optional.empty()
                    : Optional.of("Ze skarbca kraju zleca Władca.");
            }
            default -> {
                return Optional.of("Nieobsługiwany zleceniodawca.");
            }
        }
    }

    public Outcome create(final UUID actor, final Payer payer, final Kind kind, final String title, final String item, final int quantity,
                          final long price, final long bond, final int hours, final String chest, final String reservedFor) {
        final Optional<String> pp = this.payerProblem(payer, actor);
        if (pp.isPresent()) {
            return Outcome.fail(pp.get());
        }
        if (price <= 0) {
            return Outcome.fail("Podaj dodatnią zapłatę.");
        }
        if (bond < 0 || bond * 100 > price * this.s.maxBondPct) {
            return Outcome.fail("Wadium może wynosić najwyżej " + this.s.maxBondPct + " % zapłaty.");
        }
        if (hours < this.s.minHours || hours > this.s.maxHours) {
            return Outcome.fail("Termin: " + this.s.minHours + "–" + this.s.maxHours + " h.");
        }
        if (kind == Kind.GOODS && (quantity < 1 || quantity > this.s.maxQuantity || item == null || item.isBlank() || chest == null)) {
            return Outcome.fail("Kontrakt na towar wymaga przedmiotu, ilości (1–" + this.s.maxQuantity + ") i skrzyni odbiorczej.");
        }
        if (reservedFor != null && this.firm(reservedFor).map(f -> !f.active()).orElse(true)) {
            return Outcome.fail("Nie ma takiej firmy wykonawcy.");
        }
        if (payer.party() == Party.FIRM && payer.id().equals(reservedFor)) {
            return Outcome.fail("Firma nie zleca kontraktu sama sobie.");
        }
        final Contract c = new Contract();
        c.id = Ctx.newId();
        c.title = title == null || title.isBlank() ? (kind == Kind.GOODS ? quantity + "× " + item : "usługa") : title.strip();
        if (c.title.length() > 60) {
            c.title = c.title.substring(0, 60);
        }
        // the title is shown inside MiniMessage notifications (partners, client): no player-made tags or click events
        c.title = Names.plain(c.title);
        c.kind = kind;
        c.client = payer.party();
        c.clientId = payer.id();
        c.clientName = payer.name();
        c.clientAccount = payer.account();
        c.createdBy = actor.toString();
        c.reservedFor = reservedFor;
        c.item = item == null ? null : item.toUpperCase(Locale.ROOT);
        c.quantity = kind == Kind.GOODS ? quantity : 1;
        c.chest = chest;
        c.price = price;
        c.bond = bond;
        c.hours = hours;
        c.createdAt = this.now();
        c.offerUntil = c.createdAt + this.s.offerHours * Ctx.HOUR;
        this.data.contracts.put(c.id, c);
        this.save.run();
        final TxResult r = this.ctx().bank.transfer(payer.account(), c.account(), price,
            "Firmy: depozyt kontraktu " + c.id + " (" + c.title + ") od " + payer.name());
        if (!r.ok()) {
            this.data.contracts.remove(c.id);
            this.save.run();
            return Outcome.fail(r == TxResult.INSUFFICIENT_FUNDS ? "Za mało pieniędzy na koncie zleceniodawcy." : "Bank odrzucił depozyt (" + r + ").");
        }
        c.funded = true;
        this.log(c, "zlecenie i depozyt " + this.ctx().fmt(price) + " (" + payer.name() + ")");
        this.save.run();
        this.firm(reservedFor).ifPresent(f -> this.ctx().partners(f, "<gold>[Firmy]</gold> Nowy kontrakt dla „" + f.name + "”: <white>" + c.title
            + "</white> za " + this.ctx().fmt(price) + (bond > 0 ? ", wadium " + this.ctx().fmt(bond) : "") + ". "
            + "<click:run_command:'/firma kontrakt przyjmij " + c.id + "'><green>[Przyjmij]</green></click>"));
        return Outcome.ok("Kontrakt " + c.id + " wystawiony, zapłata " + this.ctx().fmt(price) + " czeka w depozycie.", c.id);
    }

    // ------------------------------------------------------------------ taking

    public Outcome accept(final String id, final UUID actor, final Firm f) {
        final Optional<Contract> oc = this.get(id);
        if (oc.isEmpty() || oc.get().state != ContractState.OPEN) {
            return Outcome.fail("Ten kontrakt nie czeka na wykonawcę.");
        }
        final Contract c = oc.get();
        if (!f.active() || !f.has(actor, Role.WSPOLNIK)) {
            return Outcome.fail("Kontrakt przyjmuje mistrz albo wspólnik firmy.");
        }
        if (f.frozen || this.service.treasury.insolvent(f)) {
            return Outcome.fail("Zamrożona albo niewypłacalna firma nie przyjmuje kontraktów.");
        }
        if (f.seatLostAt != 0 || this.service.treasury.taxTarget(f).isEmpty()) {
            return Outcome.fail("Firma bez kraju siedziby nie przyjmuje kontraktów.");
        }
        if (c.reservedFor != null && !c.reservedFor.equals(f.id)) {
            return Outcome.fail("Ten kontrakt jest dla innej firmy.");
        }
        if (c.client == Party.FIRM && f.id.equals(c.clientId)) {
            return Outcome.fail("Firma nie wykonuje własnego zlecenia.");
        }
        long open = this.data.contracts.values().stream().filter(x -> f.id.equals(x.contractor) && x.state.open()).count();
        if (open >= this.s.openPerFirm) {
            return Outcome.fail("Firma ma już " + open + " kontraktów w realizacji.");
        }
        if (c.client == Party.NPC && !c.funded) {
            final Optional<String> funding = this.fundNpc(c);
            if (funding.isPresent()) {
                return Outcome.fail(funding.get());
            }
        }
        if (c.bond > 0) {
            if (this.service.treasury.balance(f) < c.bond) {
                return Outcome.fail("Firma musi wpłacić wadium " + this.ctx().fmt(c.bond) + " — za mało na koncie.");
            }
            final TxResult r = this.ctx().bank.transfer(f.account(), c.account(), c.bond, "Firmy: wadium kontraktu " + c.id + " (" + f.name + ")");
            if (!r.ok()) {
                return Outcome.fail("Bank odrzucił wadium (" + r + ").");
            }
            c.bondPaid = true;
            this.ctx().journal(f, "kontrakt", "Wadium kontraktu " + c.id + " (" + c.title + ")", -c.bond, this.ctx().world.playerName(actor));
        }
        c.contractor = f.id;
        c.contractorName = f.name;
        c.acceptedAt = this.now();
        c.deadline = c.acceptedAt + c.hours * Ctx.HOUR;
        c.state = ContractState.ACCEPTED;
        this.log(c, "przyjęty przez " + f.name);
        f.lastActivity = this.now();
        this.ctx().save();
        this.save.run();
        this.notifyClient(c, "<gold>[Firmy]</gold> Firma <white>" + f.name + "</white> przyjęła kontrakt „" + c.title + "”.");
        return Outcome.ok("Przyjęto kontrakt „" + c.title + "”. Termin: " + c.hours + " h" + (c.kind == Kind.GOODS
            ? ". Oddaj towar: stań przy skrzyni odbiorczej" + (c.client == Party.NPC ? " (stolica " + c.clientName + ")" : "")
            + " i wpisz /firma kontrakt oddaj " + c.id : ". Zgłoś wykonanie: /firma kontrakt oddaj " + c.id) + ".", c.id);
    }

    /** NPC order: the NPC treasury funds the deposit when a company takes it, within the shared treasury limits. */
    private Optional<String> fundNpc(final Contract c) {
        final UUID npc = UUID.fromString(c.clientId);
        final long cap = this.npcBudget(npc).max();
        if (cap < c.price && c.quantity > 1 && cap > 0) {
            // the treasury shrank since the offer was made: the court orders as many pieces as it can pay today (same rate)
            final long rate = Math.max(1, c.price / c.quantity);
            final int fit = (int) Math.min(c.quantity, cap / rate);
            if (fit >= Math.max(1, c.quantity / 2)) {
                this.log(c, "zmniejszone do " + fit + " szt. (limit skarbca " + this.ctx().fmt(cap) + ")");
                c.quantity = fit;
                c.price = fit * rate;
                c.title = "Hurt dla " + c.clientName + ": " + fit + "× " + c.item;
            }
        }
        if (cap < c.price) {
            return Optional.of("Skarbiec " + c.clientName + " nie może dziś sfinansować tego zlecenia (limit " + this.ctx().fmt(cap)
                + "). Spróbuj jutro albo wybierz zlecenie innego dworu.");
        }
        final TxResult r = this.ctx().bank.transfer(c.clientAccount, c.account(), c.price,
            "Firmy: depozyt zlecenia hurtowego " + c.id + " (" + c.title + ") ze skarbca " + c.clientName);
        if (!r.ok()) {
            return Optional.of("Skarbiec " + c.clientName + " nie ma środków na to zlecenie.");
        }
        c.funded = true;
        this.courts.booked(npc, c.price);
        this.data.npcBooked(this.ctx().today(), npc.toString(), c.price);
        this.log(c, "depozyt ze skarbca " + c.clientName);
        return Optional.empty();
    }

    /** Same treasury percentages as the quests of Wladcy (shared when Wladcy offers the bridge). */
    public Courts.Budget npcBudget(final UUID npc) {
        final Optional<Courts.Budget> w = this.courts.wladcyBudget(npc);
        if (w.isPresent()) {
            return w.get();
        }
        final String account = this.ctx().world.country(npc).map(GameWorld.Country::treasuryAccount).orElse("country:" + npc);
        final long t = this.ctx().bank.balance(account);
        final long perOrder = (long) Math.floor(t * this.s.treasuryPctPerOrder / 100.0);
        final long perDay = (long) Math.floor(t * this.s.treasuryPctPerDay / 100.0) - this.data.npcBookedToday(this.ctx().today(), npc.toString());
        return new Courts.Budget(perOrder, Math.max(0, perDay));
    }

    // ------------------------------------------------------------------ delivering

    /** How many pieces the actor may still deliver now (0 + reason when not allowed). */
    public Outcome canDeliver(final String id, final UUID actor) {
        final Optional<Contract> oc = this.get(id);
        if (oc.isEmpty() || oc.get().state != ContractState.ACCEPTED) {
            return Outcome.fail("Ten kontrakt nie jest w realizacji.");
        }
        final Contract c = oc.get();
        final Optional<Firm> f = this.firm(c.contractor);
        if (f.isEmpty() || !f.get().members.containsKey(actor.toString())) {
            return Outcome.fail("Towar oddają ludzie firmy wykonawcy.");
        }
        if (c.kind != Kind.GOODS) {
            return Outcome.fail("To kontrakt na usługę: zgłoś wykonanie, a zleceniodawca potwierdzi.");
        }
        return Outcome.ok(Integer.toString(c.quantity - c.delivered), c.id);
    }

    /** The Bukkit layer moved {@code pieces} real items into the receiving chest (or gave them to the NPC court). */
    public Outcome delivered(final String id, final UUID actor, final int pieces) {
        final Contract c = this.get(id).orElseThrow();
        c.delivered = Math.min(c.quantity, c.delivered + Math.max(0, pieces));
        c.deliveredBy = actor.toString();
        this.log(c, this.ctx().world.playerName(actor) + " oddał " + pieces + " szt. (" + c.delivered + "/" + c.quantity + ")");
        this.save.run();
        if (c.delivered < c.quantity) {
            return Outcome.ok("Oddano " + pieces + " szt. — brakuje jeszcze " + (c.quantity - c.delivered) + ".");
        }
        return this.complete(c, "towar dostarczony w całości");
    }

    /** Service: the contractor reports it done; the client confirms (or the payment is released after N hours). */
    public Outcome report(final String id, final UUID actor) {
        final Optional<Contract> oc = this.get(id);
        if (oc.isEmpty() || oc.get().state != ContractState.ACCEPTED || oc.get().kind != Kind.SERVICE) {
            return Outcome.fail("To nie jest usługa w realizacji.");
        }
        final Contract c = oc.get();
        if (this.firm(c.contractor).map(f -> !f.has(actor, Role.WSPOLNIK)).orElse(true)) {
            return Outcome.fail("Wykonanie zgłasza mistrz albo wspólnik firmy wykonawcy.");
        }
        c.state = ContractState.REPORTED;
        c.reportedAt = this.now();
        this.log(c, "zgłoszono wykonanie");
        this.save.run();
        this.notifyClient(c, "<gold>[Firmy]</gold> Firma " + c.contractorName + " zgłasza wykonanie usługi „" + c.title + "”. "
            + "<click:run_command:'/firma kontrakt potwierdz " + c.id + "'><green>[Potwierdź]</green></click> "
            + "<click:suggest_command:'/firma kontrakt spor " + c.id + " '><red>[Spór]</red></click> — bez odpowiedzi zapłata zostanie zwolniona po "
            + this.s.confirmHours + " h.");
        return Outcome.ok("Zgłoszono wykonanie. Zleceniodawca ma " + this.s.confirmHours + " h na potwierdzenie albo spór.");
    }

    private boolean isClient(final Contract c, final UUID actor) {
        return switch (c.client) {
            case PLAYER -> actor.toString().equals(c.clientId);
            case FIRM -> this.firm(c.clientId).map(f -> f.has(actor, Role.WSPOLNIK)).orElse(false);
            case COUNTRY -> this.ctx().world.rank(UUID.fromString(c.clientId), actor) >= 2;
            case NPC -> false;
        };
    }

    public Outcome confirm(final String id, final UUID actor) {
        final Optional<Contract> oc = this.get(id);
        if (oc.isEmpty() || oc.get().state != ContractState.REPORTED) {
            return Outcome.fail("Ten kontrakt nie czeka na potwierdzenie.");
        }
        if (!this.isClient(oc.get(), actor)) {
            return Outcome.fail("Potwierdza zleceniodawca.");
        }
        return this.complete(oc.get(), "zleceniodawca potwierdził wykonanie");
    }

    /** Pays the contractor from the deposit: the price as taxed income, the bond back. */
    private Outcome complete(final Contract c, final String why) {
        final Optional<Firm> of = this.firm(c.contractor);
        if (of.isEmpty() || !of.get().active()) {
            return this.fail(c, "firma wykonawcy już nie istnieje");
        }
        final Firm f = of.get();
        final List<Bank.Leg> extra = new ArrayList<>();
        final long bondHeld = c.bondPaid ? c.bond : 0;
        if (bondHeld > 0) {
            extra.add(new Bank.Leg(c.account(), f.account(), bondHeld, "Firmy: zwrot wadium kontraktu " + c.id));
        }
        final Outcome o = this.service.treasury.collect(f, c.account(), c.price, "kontrakt " + c.id + " „" + c.title + "”", extra);
        if (!o.ok()) {
            return o;
        }
        c.state = ContractState.DONE;
        f.contractsDone++;
        this.log(c, "zapłata dla " + f.name + " (" + why + ")");
        if (c.client == Party.NPC && c.deliveredBy != null) {
            this.courts.reputation(UUID.fromString(c.clientId), UUID.fromString(c.deliveredBy),
                this.ctx().world.playerName(UUID.fromString(c.deliveredBy)), this.s.reputation);
        }
        this.ctx().save();
        this.save.run();
        this.ctx().partners(f, "<gold>[Firmy]</gold> Kontrakt „" + c.title + "” wykonany: <green>+" + this.ctx().fmt(c.price)
            + "</green> (przed podatkiem)" + (bondHeld > 0 ? ", wadium wraca" : "") + ".");
        this.notifyClient(c, "<gold>[Firmy]</gold> Kontrakt „" + c.title + "” wykonany przez " + f.name + ".");
        return Outcome.ok("Kontrakt wykonany: zapłata " + this.ctx().fmt(c.price) + " trafiła na konto firmy (" + o.message() + ").");
    }

    /**
     * Deadline missed (or the contractor vanished): delivered goods are paid pro rata, the rest of the price and the
     * bond go to the client.
     */
    private Outcome fail(final Contract c, final String why) {
        final long held = this.ctx().bank.balance(c.account());
        final long proRata = c.kind == Kind.GOODS && c.quantity > 0 ? Math.multiplyExact(c.price, (long) c.delivered) / c.quantity : 0;
        final Optional<Firm> f = this.firm(c.contractor);
        final String refundReason = "Firmy: zwrot depozytu" + (c.bondPaid ? " i wadium" : "") + " kontraktu " + c.id + " (" + why + ")";
        long paidContractor = 0;
        long refunded = 0;
        if (proRata > 0 && proRata <= held && f.isPresent() && f.get().active() && this.service.treasury.taxTarget(f.get()).isPresent()) {
            // pro-rata payment and the client's refund in ONE atomic batch: when the refund is refused nothing moves, so the
            // next tick can never pay the delivered part a second time
            final long refund = held - proRata;
            final List<Bank.Leg> extra = refund > 0 ? List.of(new Bank.Leg(c.account(), c.clientAccount, refund, refundReason)) : List.of();
            final Outcome o = this.service.treasury.collect(f.get(), c.account(), proRata,
                "kontrakt " + c.id + " (część: " + c.delivered + "/" + c.quantity + ")", extra);
            if (o.ok()) {
                paidContractor = proRata;
                refunded = refund;
            }
        }
        final long rest = this.ctx().bank.balance(c.account());
        if (rest > 0) {
            final TxResult r = this.ctx().bank.transfer(c.account(), c.clientAccount, rest, refundReason);
            if (!r.ok()) {
                this.ctx().notifier.admin("Firmy: zwrot depozytu kontraktu " + c.id + " odrzucony: " + r, true);
                return Outcome.fail("Bank odrzucił zwrot depozytu (" + r + ").");
            }
            refunded += rest;
        }
        final long toClient = refunded;
        c.state = ContractState.FAILED;
        this.log(c, why + ": " + this.ctx().fmt(paidContractor) + " dla wykonawcy, " + this.ctx().fmt(toClient) + " dla zleceniodawcy (z " + this.ctx().fmt(held) + ")");
        this.save.run();
        f.ifPresent(x -> this.ctx().partners(x, "<gold>[Firmy]</gold> <red>Kontrakt „" + c.title + "” niedotrzymany</red> (" + why + ")"
            + (c.bondPaid ? ": wadium przepada na rzecz zleceniodawcy." : ".")));
        this.notifyClient(c, "<gold>[Firmy]</gold> Kontrakt „" + c.title + "” niedotrzymany (" + why + "): zwrot " + this.ctx().fmt(toClient) + ".");
        return Outcome.ok("Kontrakt zamknięty: " + why + ".");
    }

    public Outcome cancel(final String id, final UUID actor, final boolean admin) {
        final Optional<Contract> oc = this.get(id);
        if (oc.isEmpty() || oc.get().state != ContractState.OPEN) {
            return Outcome.fail("Odwołać można tylko kontrakt, którego nikt nie przyjął.");
        }
        final Contract c = oc.get();
        if (!admin && !this.isClient(c, actor) && !actor.toString().equals(c.createdBy)) {
            return Outcome.fail("Odwołuje zleceniodawca.");
        }
        return this.refundOpen(c, admin ? "odwołany przez administratora" : "odwołany przez zleceniodawcę");
    }

    private Outcome refundOpen(final Contract c, final String why) {
        final long held = this.ctx().bank.balance(c.account());
        if (held > 0) {
            final TxResult r = this.ctx().bank.transfer(c.account(), c.clientAccount, held, "Firmy: zwrot depozytu kontraktu " + c.id + " (" + why + ")");
            if (!r.ok()) {
                return Outcome.fail("Bank odrzucił zwrot (" + r + ").");
            }
        }
        c.state = ContractState.CANCELLED;
        this.log(c, why);
        this.save.run();
        return Outcome.ok("Kontrakt " + c.id + " odwołany, zwrócono " + this.ctx().fmt(held) + ".");
    }

    // ------------------------------------------------------------------ disputes

    public Outcome dispute(final String id, final UUID actor, final String reason) {
        final Optional<Contract> oc = this.get(id);
        if (oc.isEmpty() || oc.get().state != ContractState.ACCEPTED && oc.get().state != ContractState.REPORTED) {
            return Outcome.fail("Spór można otworzyć dla kontraktu w realizacji.");
        }
        final Contract c = oc.get();
        final boolean contractor = this.firm(c.contractor).map(f -> f.has(actor, Role.WSPOLNIK)).orElse(false);
        if (!contractor && !this.isClient(c, actor)) {
            return Outcome.fail("Spór otwiera strona kontraktu.");
        }
        c.state = ContractState.DISPUTED;
        c.disputeReason = Names.plain(reason == null ? "" : reason.strip());
        if (c.disputeReason.length() > 200) {
            c.disputeReason = c.disputeReason.substring(0, 200);
        }
        c.disputeBy = this.ctx().world.playerName(actor);
        c.disputeAt = this.now();
        this.log(c, "spór: " + c.disputeBy + " — " + c.disputeReason);
        this.save.run();
        this.ctx().notifier.admin("Firmy: spór w kontrakcie " + c.id + " (" + c.title + "): " + c.disputeReason, false);
        return Outcome.ok("Spór zgłoszony. Depozyt jest zamrożony do decyzji administratora (panel WWW → Firmy → Spory).");
    }

    /** Owner's decision: {@code client} (refund + bond), {@code contractor} (paid + bond back), {@code split} (half each). */
    public Outcome resolve(final String id, final String winner, final String note, final String by) {
        final Optional<Contract> oc = this.get(id);
        if (oc.isEmpty() || oc.get().state != ContractState.DISPUTED) {
            return Outcome.fail("Ten kontrakt nie jest w sporze.");
        }
        final Contract c = oc.get();
        final Optional<Firm> f = this.firm(c.contractor);
        final String what = "rozstrzygnięcie sporu kontraktu " + c.id;
        switch (winner) {
            case "contractor" -> {
                if (f.isEmpty() || !f.get().active()) {
                    return Outcome.fail("Firma wykonawcy nie istnieje — rozstrzygnij na korzyść zleceniodawcy.");
                }
                final List<Bank.Leg> extra = new ArrayList<>();
                if (c.bondPaid && c.bond > 0) {
                    extra.add(new Bank.Leg(c.account(), f.get().account(), c.bond, "Firmy: zwrot wadium (" + what + ")"));
                }
                final Outcome o = this.service.treasury.collect(f.get(), c.account(), c.price, what, extra);
                if (!o.ok()) {
                    return o;
                }
                f.get().contractsDone++;
            }
            case "split" -> {
                if (f.isEmpty() || !f.get().active()) {
                    return Outcome.fail("Firma wykonawcy nie istnieje — rozstrzygnij na korzyść zleceniodawcy.");
                }
                final long half = c.price / 2;
                final List<Bank.Leg> extra = new ArrayList<>();
                extra.add(new Bank.Leg(c.account(), c.clientAccount, c.price - half, "Firmy: zwrot połowy depozytu (" + what + ")"));
                if (c.bondPaid && c.bond > 0) {
                    extra.add(new Bank.Leg(c.account(), f.get().account(), c.bond, "Firmy: zwrot wadium (" + what + ")"));
                }
                final Outcome o = this.service.treasury.collect(f.get(), c.account(), half, what, extra);
                if (!o.ok()) {
                    return o;
                }
            }
            case "client" -> {
                final long held = this.ctx().bank.balance(c.account());
                if (held > 0) {
                    final TxResult r = this.ctx().bank.transfer(c.account(), c.clientAccount, held, "Firmy: zwrot depozytu i wadium (" + what + ")");
                    if (!r.ok()) {
                        return Outcome.fail("Bank odrzucił zwrot (" + r + ").");
                    }
                }
            }
            default -> {
                return Outcome.fail("Rozstrzygnięcie: client, contractor albo split.");
            }
        }
        c.state = ContractState.RESOLVED;
        c.resolution = winner + (note == null || note.isBlank() ? "" : ": " + note.strip());
        this.log(c, "spór rozstrzygnięty (" + by + "): " + c.resolution);
        this.ctx().save();
        this.save.run();
        f.ifPresent(x -> this.ctx().partners(x, "<gold>[Firmy]</gold> Spór w kontrakcie „" + c.title + "” rozstrzygnięty: " + c.resolution));
        this.notifyClient(c, "<gold>[Firmy]</gold> Spór w kontrakcie „" + c.title + "” rozstrzygnięty: " + c.resolution);
        return Outcome.ok("Spór rozstrzygnięty: " + c.resolution + ".");
    }

    public List<Contract> disputes() {
        return this.data.contracts.values().stream().filter(c -> c.state == ContractState.DISPUTED).toList();
    }

    // ------------------------------------------------------------------ time and liquidation

    /** Expired offers, missed deadlines, unconfirmed services; old closed contracts are forgotten after 30 days. */
    public void tick() {
        final long now = this.now();
        for (final Contract c : List.copyOf(this.data.contracts.values())) {
            switch (c.state) {
                case OPEN -> {
                    if (c.offerUntil <= now) {
                        if (c.client == Party.NPC && !c.funded) {
                            c.state = ContractState.CANCELLED;
                            this.log(c, "oferta wygasła");
                            this.save.run();
                        } else if (this.refundOpen(c, "oferta wygasła bez wykonawcy").ok()) {
                            // a refused refund is retried next minute: tell the client only once the money is back
                            this.notifyClient(c, "<gold>[Firmy]</gold> Nikt nie przyjął kontraktu „" + c.title + "” — depozyt wrócił.");
                        }
                    }
                }
                case ACCEPTED -> {
                    if (c.deadline <= now) {
                        this.fail(c, "minął termin");
                    }
                }
                case REPORTED -> {
                    if (c.reportedAt + this.s.confirmHours * Ctx.HOUR <= now) {
                        this.complete(c, "zleceniodawca nie odpowiedział w " + this.s.confirmHours + " h");
                    }
                }
                default -> {
                    final long closedAt = Math.max(c.deadline, Math.max(c.offerUntil, c.disputeAt));
                    if (!c.state.open() && closedAt > 0 && now - closedAt > 30 * Ctx.DAY) {
                        this.data.contracts.remove(c.id);
                        this.save.run();
                    }
                }
            }
        }
    }

    /** First step of liquidating {@code f}: its open contracts are closed by their rules before the money is split. */
    public void liquidating(final Firm f) {
        for (final Contract c : List.copyOf(this.data.contracts.values())) {
            if (!c.state.open()) {
                continue;
            }
            if (f.id.equals(c.contractor)) {
                if (c.state == ContractState.DISPUTED) {
                    this.ctx().notifier.admin("Firmy: likwidacja " + f.name + " — kontrakt " + c.id + " w sporze zostaje do decyzji (depozyt bez zmian)", true);
                    continue;
                }
                this.fail(c, "likwidacja firmy wykonawcy");
            } else if (c.client == Party.FIRM && f.id.equals(c.clientId)) {
                if (c.state == ContractState.OPEN) {
                    this.refundOpen(c, "likwidacja firmy zleceniodawcy");
                } else if (c.state == ContractState.ACCEPTED) {
                    // the contractor keeps its bond; the price goes back to the client company (it is distributed with the rest)
                    final List<Bank.Leg> legs = new ArrayList<>();
                    final long held = this.ctx().bank.balance(c.account());
                    final long bond = c.bondPaid ? Math.min(c.bond, held) : 0;
                    final Optional<Firm> contractor = this.firm(c.contractor);
                    if (bond > 0 && contractor.isPresent()) {
                        legs.add(new Bank.Leg(c.account(), contractor.get().account(), bond, "Firmy: zwrot wadium (likwidacja zleceniodawcy)"));
                    }
                    if (held - bond > 0) {
                        legs.add(new Bank.Leg(c.account(), c.clientAccount, held - bond, "Firmy: zwrot depozytu kontraktu " + c.id + " (likwidacja zleceniodawcy)"));
                    }
                    if (!legs.isEmpty() && this.ctx().bank.transferAll(legs).ok()) {
                        c.state = ContractState.CANCELLED;
                        this.log(c, "likwidacja firmy zleceniodawcy");
                        this.save.run();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ notifications

    private void notifyClient(final Contract c, final String msg) {
        switch (c.client) {
            case PLAYER -> this.ctx().notifier.player(UUID.fromString(c.clientId), msg);
            case FIRM -> this.firm(c.clientId).ifPresent(f -> this.ctx().partners(f, msg));
            case COUNTRY -> this.ctx().notifier.countryLeaders(UUID.fromString(c.clientId), msg);
            case NPC -> {
                if (c.deliveredBy != null) {
                    this.ctx().notifier.player(UUID.fromString(c.deliveredBy), msg);
                }
            }
        }
    }

    // ------------------------------------------------------------------ wholesale orders (NPC courts)

    /** Creates today's wholesale offers of NPC courts (no money moves until a company takes one). */
    public int generateWholesale(final long seed) {
        if (!this.s.wholesale || this.s.goods.isEmpty()) {
            return 0;
        }
        int made = 0;
        // offers nobody took yet hold no money: they follow the current rates (e.g. the ×5 decision) at once
        for (final Contract c : this.data.contracts.values()) {
            final Stage2Settings.Good g = c.item == null ? null : this.s.goods.get(c.item);
            if (c.client == Party.NPC && c.state == ContractState.OPEN && !c.funded && g != null && c.quantity > 0
                && c.price < c.quantity * this.s.rate(g)) {
                this.log(c, "nowa stawka " + this.ctx().fmt(this.s.rate(g)) + "/szt.");
                c.price = c.quantity * this.s.rate(g);
                made++;
            }
        }
        final int repriced = made;
        made = 0;
        final java.util.Random rnd = new java.util.Random(seed);
        final List<Stage2Settings.Good> goods = new ArrayList<>(this.s.goods.values());
        for (final Courts.Court court : this.courts.npcCountries()) {
            final long open = this.data.contracts.values().stream()
                .filter(c -> c.client == Party.NPC && court.id().toString().equals(c.clientId) && c.state.open()).count();
            for (long i = open; i < this.s.perCountry; i++) {
                final long budget = this.npcBudget(court.id()).max();
                if (budget < this.s.wholesaleMin) {
                    break;
                }
                final Stage2Settings.Good g = goods.get(rnd.nextInt(goods.size()));
                final long rate = this.s.rate(g);
                int qty = g.min() + rnd.nextInt(g.max() - g.min() + 1);
                qty = (int) Math.min(qty, budget / rate);
                if (qty < g.min() / 2 || qty < 1) {
                    continue;
                }
                final Contract c = new Contract();
                c.id = Ctx.newId();
                c.kind = Kind.GOODS;
                c.client = Party.NPC;
                c.clientId = court.id().toString();
                c.clientName = court.name();
                c.clientAccount = court.treasuryAccount();
                c.createdBy = "npc";
                c.item = g.material();
                c.quantity = qty;
                c.price = qty * rate;
                c.bond = 0;
                c.hours = this.s.wholesaleHours;
                c.createdAt = this.now();
                c.offerUntil = c.createdAt + this.s.wholesaleOfferHours * Ctx.HOUR;
                c.chest = null;
                c.title = "Hurt dla " + court.name() + ": " + qty + "× " + g.material();
                this.log(c, "zlecenie hurtowe dworu " + court.name());
                this.data.contracts.put(c.id, c);
                made++;
            }
        }
        if (made > 0 || repriced > 0) {
            this.save.run();
        }
        return made;
    }

    public List<Contract> wholesale() {
        return this.data.contracts.values().stream().filter(c -> c.client == Party.NPC && c.state == ContractState.OPEN).toList();
    }

    public Optional<Courts.Court> court(final String id) {
        return this.courts.npcCountries().stream().filter(c -> c.id().toString().equals(id)).findFirst();
    }

    /** Used by FirmsData-style audits: open deposits of contracts (for the panel). */
    public long held(final Contract c) {
        return this.ctx().bank.balance(c.account());
    }
}
