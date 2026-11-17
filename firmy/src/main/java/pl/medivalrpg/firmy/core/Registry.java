// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmType;
import pl.medivalrpg.firmy.model.FirmsData;
import pl.medivalrpg.firmy.model.Role;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Founding: names, applications with an escrow (fee + capital), approval by the seat country, company tax policy of
 * countries. The escrow account {@code firma-wniosek:<id>} is dedicated to one application, so after a crash its
 * balance alone tells whether the money already moved (no double funding, no lost capital).
 */
public final class Registry {

    private final Ctx ctx;

    public Registry(final Ctx ctx) {
        this.ctx = ctx;
    }

    private Settings s() {
        return this.ctx.settings;
    }

    // ------------------------------------------------------------------ names

    public Optional<String> nameProblem(final String raw) {
        final Optional<String> p = Names.problem(raw, this.s());
        if (p.isPresent()) {
            return p;
        }
        final String n = Names.normalize(raw);
        final long now = this.ctx.now();
        final Long reserved = this.ctx.data.reservedNames.get(n);
        if (reserved != null && reserved > now) {
            return Optional.of("Ta nazwa jest zastrzeżona po likwidacji firmy jeszcze przez "
                + Math.max(1, (reserved - now + Ctx.DAY - 1) / Ctx.DAY) + " dni.");
        }
        for (final Firm f : this.ctx.data.firms.values()) {
            if (f.active() && Names.normalize(f.name).equals(n)) {
                return Optional.of("Firma o tej nazwie już istnieje.");
            }
        }
        for (final FirmsData.Application a : this.ctx.data.applications.values()) {
            if (Names.normalize(a.name).equals(n)) {
                return Optional.of("Ktoś już złożył wniosek o firmę o tej nazwie.");
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ people limits (shared with People)

    /** Empty when the player may become a member (play time, account age, number of companies). */
    public Optional<String> memberProblem(final UUID player, final boolean asMaster) {
        final Settings s = this.s();
        final long minutes = this.ctx.world.playMinutes(player);
        if (s.minPlayMinutes > 0 && minutes >= 0 && minutes < s.minPlayMinutes) {
            return Optional.of("Potrzeba co najmniej " + (s.minPlayMinutes / 60) + " h gry (jest " + (minutes / 60) + " h).");
        }
        final long first = this.ctx.world.firstPlayed(player);
        if (s.minAccountDays > 0 && first > 0 && this.ctx.now() - first < s.minAccountDays * Ctx.DAY) {
            return Optional.of("Konto musi mieć co najmniej " + s.minAccountDays + " dni.");
        }
        final List<Firm> mine = this.ctx.firmsOf(player);
        int pendingAsFounder = 0;
        int pendingAsContributor = 0;
        for (final FirmsData.Application a : this.ctx.data.applications.values()) {
            if (a.founder.equals(player.toString())) {
                pendingAsFounder++;
            } else if (a.contributions != null && a.contributions.containsKey(player.toString())) {
                pendingAsContributor++;
            }
        }
        if (mine.size() + pendingAsFounder + pendingAsContributor >= s.maxMemberships) {
            return Optional.of("Można należeć najwyżej do " + s.maxMemberships + " firm.");
        }
        if (asMaster) {
            final long masterOf = mine.stream().filter(f -> f.has(player, Role.MISTRZ)).count() + pendingAsFounder;
            if (masterOf >= s.maxMasterOf) {
                return Optional.of("Można być mistrzem najwyżej " + s.maxMasterOf + " firmy (liczą się też złożone wnioski).");
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ founding

    /**
     * Why the player may not found a company of this type with the seat here (empty = may). {@code auto[0]} tells
     * whether the application is approved without the ruler (NPC seat with enough reputation, the founder rules the seat).
     */
    private Optional<String> foundingProblem(final UUID founder, final String name, final FirmType type, final UUID seatCountry,
                                             final boolean bypassPeopleRules, final boolean[] auto) {
        final Settings s = this.s();
        if (type == null || !type.foundable()) {
            return Optional.of("Ten typ firmy nie może być założony (Dom handlowy powstaje tylko przez awans).");
        }
        final Optional<String> np = this.nameProblem(name);
        if (np.isPresent()) {
            return np;
        }
        if (seatCountry == null) {
            return Optional.of("Firmę zakłada się na ziemi kraju, który ma być jej siedzibą. W dziczy firm nie ma.");
        }
        final Optional<GameWorld.Country> seat = this.ctx.world.country(seatCountry);
        if (seat.isEmpty()) {
            return Optional.of("Nie ma takiego kraju.");
        }
        if (!bypassPeopleRules) {
            final Optional<String> mp = this.memberProblem(founder, true);
            if (mp.isPresent()) {
                return mp;
            }
        } else {
            final long masterOf = this.ctx.firmsOf(founder).stream().filter(f -> f.has(founder, Role.MISTRZ)).count();
            if (masterOf >= s.maxMasterOf) {
                return Optional.of("Można być mistrzem najwyżej " + s.maxMasterOf + " firmy.");
            }
        }
        if (type == FirmType.MANUFAKTURA && this.ctx.world.era(seatCountry) < s.manufactoryEra) {
            return Optional.of("Manufaktura wymaga epoki Renesansu w kraju siedziby (" + seat.get().name() + " jest w epoce "
                + this.ctx.world.era(seatCountry) + "). Załóż cech albo kompanię.");
        }
        final GameWorld.Country c = seat.get();
        auto[0] = false;
        if (c.npc()) {
            final Optional<GameWorld.Country> own = this.ctx.world.countryOf(founder);
            if (own.isPresent() && this.ctx.world.atWar(own.get().id(), c.id())) {
                return Optional.of("Twój kraj jest w wojnie z " + c.name() + ".");
            }
            final Optional<Integer> rep = this.ctx.world.reputation(c.id(), founder);
            if (rep.isPresent() && rep.get() < s.npcMinReputation) {
                return Optional.of("Za niska reputacja u " + c.name() + " (" + rep.get() + "). Potrzebny co najmniej „Obcy”.");
            }
            if (rep.isEmpty() && !s.npcAllowWithoutReputation) {
                return Optional.of("Nie można teraz sprawdzić reputacji u władcy " + c.name() + ".");
            }
            auto[0] = true;
        } else if (this.ctx.world.rank(c.id(), founder) >= 3) {
            auto[0] = true;
        }
        return Optional.empty();
    }

    private String newApplicationId() {
        String id = Ctx.newId();
        while (this.ctx.data.firms.containsKey(id) || this.ctx.data.applications.containsKey(id)) {
            id = Ctx.newId();
        }
        return id;
    }

    /**
     * {@code /firma zaloz}: escrow of fee + capital, then either an automatic approval (NPC seat with enough reputation,
     * the founder rules the seat) or a waiting application for the ruler/advisors of the seat.
     */
    public Outcome found(final UUID founder, final String founderName, final String rawName, final FirmType type,
                         final UUID seatCountry, final long capital, final boolean bypassPeopleRules) {
        final Settings s = this.s();
        final String name = Names.clean(rawName);
        final boolean[] autoFlag = {false};
        final Optional<String> problem = this.foundingProblem(founder, name, type, seatCountry, bypassPeopleRules, autoFlag);
        if (problem.isPresent()) {
            return Outcome.fail(problem.get());
        }
        if (capital < s.minCapital) {
            return Outcome.fail("Kapitał początkowy musi wynosić co najmniej " + this.ctx.fmt(s.minCapital) + ".");
        }
        final GameWorld.Country c = this.ctx.world.country(seatCountry).orElseThrow();
        final long total;
        try {
            total = Math.addExact(s.fee, capital);
        } catch (final ArithmeticException e) {
            return Outcome.fail("Nieprawidłowa kwota.");
        }
        final String playerAccount = "player:" + founder;
        final long have = this.ctx.bank.balance(playerAccount);
        if (have < total) {
            return Outcome.fail("Potrzebujesz na koncie " + this.ctx.fmt(total) + " (opłata " + this.ctx.fmt(s.fee)
                + " + kapitał " + this.ctx.fmt(capital) + "), masz " + this.ctx.fmt(have) + "."
                + (s.gatheringEnabled ? " Złóżcie się razem: /firma zrzutka zaloz " + type.key() + " <twój wkład> " + name
                + " — inni gracze dopłacają i dostają udziały według wkładu." : ""));
        }
        final FirmsData.Application a = new FirmsData.Application();
        a.id = this.newApplicationId();
        a.founder = founder.toString();
        a.founderName = founderName;
        a.name = name;
        a.type = type;
        a.country = c.id().toString();
        a.countryName = c.name();
        a.fee = s.fee;
        a.capital = capital;
        a.createdAt = this.ctx.now();
        a.expiresAt = a.createdAt + s.applicationHours * Ctx.HOUR;
        a.state = "OPEN";
        // The application is on disk before the money leaves the founder (escrow balance decides after a crash).
        this.ctx.data.applications.put(a.id, a);
        this.ctx.store.saveNow();
        final TxResult r = this.ctx.bank.transfer(playerAccount, a.account(), total,
            "Firmy: wniosek o założenie firmy " + name + " (opłata + kapitał)");
        if (!r.ok()) {
            this.ctx.data.applications.remove(a.id);
            this.ctx.save();
            return Outcome.fail(r == TxResult.INSUFFICIENT_FUNDS ? "Za mało pieniędzy na koncie." : "Bank odrzucił wpłatę (" + r + ").");
        }
        this.ctx.save();
        return this.submitted(a, c, autoFlag[0], total);
    }

    /** The escrow holds fee + capital: approve at once (auto) or ask the ruler/advisors of the seat. */
    private Outcome submitted(final FirmsData.Application a, final GameWorld.Country c, final boolean auto, final long total) {
        if (auto) {
            final Outcome o = this.approve(a, null, "automatycznie");
            if (o.ok()) {
                return Outcome.ok("Firma „" + a.name + "” założona. Siedziba: " + c.name() + ". Opłata " + this.ctx.fmt(a.fee)
                    + " trafiła do skarbca kraju, kapitał " + this.ctx.fmt(a.capital) + " jest na koncie firmy.", a.id);
            }
            return o;
        }
        this.ctx.notifier.countryLeaders(c.id(), "<gold>[Firmy]</gold> <yellow>" + Names.plain(a.founderName) + "</yellow> prosi o zgodę na firmę <white>„"
            + Names.plain(a.name) + "”</white> (" + a.type.displayName() + ") z siedzibą w twoim kraju. Opłata " + this.ctx.fmt(a.fee)
            + " trafi do skarbca. <click:run_command:'/firma kraj zatwierdz " + a.id + "'><green>[Zatwierdź]</green></click> "
            + "<click:run_command:'/firma kraj odrzuc " + a.id + "'><red>[Odrzuć]</red></click>");
        return Outcome.ok("Wniosek złożony. " + this.ctx.fmt(total) + " czeka w depozycie, aż Władca albo Doradca kraju "
            + c.name() + " go rozpatrzy (do " + this.s().applicationHours + " h). Odrzucony albo przeterminowany wniosek zwraca całą kwotę.", a.id);
    }

    // ------------------------------------------------------------------ co-founding (zrzutka)

    /** Open gatherings (anybody may join one with a contribution). */
    public List<FirmsData.Application> gatherings() {
        final List<FirmsData.Application> out = new ArrayList<>();
        for (final FirmsData.Application a : this.ctx.data.applications.values()) {
            if (a.gathering()) {
                out.add(a);
            }
        }
        return out;
    }

    /** Fee + minimum capital: what a gathering has to collect before it goes to the seat country. */
    public long gatheringTarget() {
        return this.s().fee + this.s().minCapital;
    }

    /**
     * {@code /firma zrzutka zaloz}: the founder starts a co-founding with a first contribution. Other players add money
     * ({@link #contribute}); when fee + capital are collected the application goes to the seat country like a normal
     * one. Shares follow the contributions, the founder becomes the master, the others partners.
     */
    public Outcome gather(final UUID founder, final String founderName, final String rawName, final FirmType type,
                          final UUID seatCountry, final long contribution) {
        final Settings s = this.s();
        if (!s.gatheringEnabled) {
            return Outcome.fail("Zrzutki na firmy są wyłączone.");
        }
        final String name = Names.clean(rawName);
        final boolean[] autoFlag = {false};
        final Optional<String> problem = this.foundingProblem(founder, name, type, seatCountry, false, autoFlag);
        if (problem.isPresent()) {
            return Outcome.fail(problem.get());
        }
        if (contribution < s.gatheringMinContribution) {
            return Outcome.fail("Najmniejszy wkład do zrzutki: " + this.ctx.fmt(s.gatheringMinContribution) + ".");
        }
        if (contribution >= this.gatheringTarget()) {
            // enough alone: a normal application, the money above the fee is capital
            return this.found(founder, founderName, name, type, seatCountry, contribution - s.fee, false);
        }
        final String playerAccount = "player:" + founder;
        if (this.ctx.bank.balance(playerAccount) < contribution) {
            return Outcome.fail("Masz na koncie tylko " + this.ctx.fmt(this.ctx.bank.balance(playerAccount)) + ".");
        }
        final GameWorld.Country c = this.ctx.world.country(seatCountry).orElseThrow();
        final FirmsData.Application a = new FirmsData.Application();
        a.id = this.newApplicationId();
        a.founder = founder.toString();
        a.founderName = founderName;
        a.name = name;
        a.type = type;
        a.country = c.id().toString();
        a.countryName = c.name();
        a.fee = s.fee;
        a.capital = 0;
        a.createdAt = this.ctx.now();
        a.expiresAt = a.createdAt + s.applicationHours * Ctx.HOUR;
        a.state = "ZBIORKA";
        a.autoApprove = autoFlag[0];
        a.contributorNames.put(a.founder, founderName);
        a.pending = "+" + a.founder + "|" + contribution;
        this.ctx.data.applications.put(a.id, a);
        this.ctx.store.saveNow();
        final TxResult r = this.ctx.bank.transfer(playerAccount, a.account(), contribution, "Firmy: wkład do zrzutki na firmę " + name);
        if (!r.ok()) {
            this.ctx.data.applications.remove(a.id);
            this.ctx.save();
            return Outcome.fail(r == TxResult.INSUFFICIENT_FUNDS ? "Za mało pieniędzy na koncie." : "Bank odrzucił wpłatę (" + r + ").");
        }
        a.contributions.put(a.founder, contribution);
        a.pending = null;
        this.ctx.save();
        return Outcome.ok("Zrzutka na firmę „" + name + "” (" + type.displayName() + ", siedziba " + c.name() + ") ruszyła: zebrano "
            + this.ctx.fmt(contribution) + " z " + this.ctx.fmt(this.gatheringTarget()) + ". Inni dołączają w /firma (Zrzutki) albo "
            + "/firma zrzutka dolacz " + a.id + " <kwota>. Udziały według wkładu, termin " + s.applicationHours
            + " h — bez kompletu każdy dostaje swój wkład z powrotem.", a.id);
    }

    /** A gathering by id or by company name. */
    public Optional<FirmsData.Application> gatheringOf(final String idOrName) {
        if (idOrName == null) {
            return Optional.empty();
        }
        final FirmsData.Application byId = this.ctx.data.applications.get(idOrName.toLowerCase(java.util.Locale.ROOT));
        if (byId != null && byId.gathering()) {
            return Optional.of(byId);
        }
        final String n = Names.normalize(idOrName);
        return this.gatherings().stream().filter(a -> Names.normalize(a.name).equals(n)).findFirst();
    }

    /** {@code /firma zrzutka dolacz}: a contribution to an open gathering (also a top-up of one's own contribution). */
    public Outcome contribute(final String appIdOrName, final UUID player, final String playerName, final long amount) {
        final Settings s = this.s();
        final Optional<FirmsData.Application> oa = this.gatheringOf(appIdOrName);
        if (oa.isEmpty()) {
            return Outcome.fail("Nie ma takiej zrzutki (mogła się już zakończyć).");
        }
        final FirmsData.Application a = oa.get();
        if (a.pending != null) {
            return Outcome.fail("Poprzednia wpłata do tej zrzutki jeszcze się księguje — spróbuj za chwilę.");
        }
        if (amount <= 0) {
            return Outcome.fail("Podaj dodatnią kwotę.");
        }
        final String key = player.toString();
        final long remaining = Math.max(1, this.gatheringTarget() - a.collected());
        final long min = Math.min(s.gatheringMinContribution, remaining);
        if (amount < min) {
            return Outcome.fail("Najmniejszy wkład: " + this.ctx.fmt(min) + ".");
        }
        if (!a.contributions.containsKey(key)) {
            final Optional<String> mp = this.memberProblem(player, false);
            if (mp.isPresent()) {
                return Outcome.fail(mp.get());
            }
            final int limit = Math.min(s.gatheringMaxPeople, s.maxPeople.getOrDefault(a.type, 12));
            if (a.contributions.size() >= limit) {
                return Outcome.fail("Ta zrzutka ma już komplet " + limit + " wspólników.");
            }
        }
        final String playerAccount = "player:" + player;
        if (this.ctx.bank.balance(playerAccount) < amount) {
            return Outcome.fail("Masz na koncie tylko " + this.ctx.fmt(this.ctx.bank.balance(playerAccount)) + ".");
        }
        final boolean known = a.contributions.containsKey(key);
        a.pending = "+" + key + "|" + amount;
        a.contributorNames.put(key, playerName);
        this.ctx.store.saveNow();
        final TxResult r = this.ctx.bank.transfer(playerAccount, a.account(), amount, "Firmy: wkład do zrzutki na firmę " + a.name);
        if (!r.ok()) {
            a.pending = null;
            if (!known) {
                a.contributorNames.remove(key);
            }
            this.ctx.save();
            return Outcome.fail(r == TxResult.INSUFFICIENT_FUNDS ? "Za mało pieniędzy na koncie." : "Bank odrzucił wpłatę (" + r + ").");
        }
        a.contributions.merge(key, amount, Long::sum);
        a.pending = null;
        this.ctx.save();
        if (!key.equals(a.founder)) {
            this.ctx.notifier.player(UUID.fromString(a.founder), "<gold>[Firmy]</gold> <yellow>" + Names.plain(playerName) + "</yellow> dokłada "
                + this.ctx.fmt(amount) + " do zrzutki na „" + Names.plain(a.name) + "” (" + this.ctx.fmt(a.collected()) + " z "
                + this.ctx.fmt(this.gatheringTarget()) + ").");
        }
        if (a.collected() >= this.gatheringTarget()) {
            return this.gatheringComplete(a);
        }
        return Outcome.ok("Dołożono " + this.ctx.fmt(amount) + " do zrzutki na „" + a.name + "”: zebrano " + this.ctx.fmt(a.collected()) + " z "
            + this.ctx.fmt(this.gatheringTarget()) + ".", a.id);
    }

    /** The gathering reached fee + capital: from now on it is a normal application (or approved at once). */
    private Outcome gatheringComplete(final FirmsData.Application a) {
        final Optional<GameWorld.Country> c = this.ctx.world.country(UUID.fromString(a.country));
        if (c.isEmpty()) {
            return this.refund(a, "kraj siedziby już nie istnieje");
        }
        a.capital = a.collected() - a.fee;
        a.state = "OPEN";
        a.expiresAt = this.ctx.now() + this.s().applicationHours * Ctx.HOUR;
        this.ctx.save();
        for (final String u : a.contributions.keySet()) {
            this.ctx.notifier.player(UUID.fromString(u), "<gold>[Firmy]</gold> Zrzutka na „" + Names.plain(a.name) + "” zebrała "
                + this.ctx.fmt(a.collected()) + (a.autoApprove ? " — zakładamy firmę."
                : " — wniosek trafia do władz kraju " + Names.plain(a.countryName) + "."));
        }
        return this.submitted(a, c.get(), a.autoApprove, a.collected());
    }

    /** A contributor takes their money back while the gathering still collects (the founder cancels the whole gathering). */
    public Outcome leaveGathering(final UUID player) {
        final String key = player.toString();
        for (final FirmsData.Application a : List.copyOf(this.ctx.data.applications.values())) {
            if (!a.gathering() || !a.contributions.containsKey(key)) {
                continue;
            }
            if (key.equals(a.founder)) {
                return this.refund(a, "zrzutka odwołana przez zakładającego");
            }
            if (a.pending != null) {
                return Outcome.fail("Wpłata do tej zrzutki jeszcze się księguje — spróbuj za chwilę.");
            }
            final long amount = a.contributions.get(key);
            a.pending = "-" + key + "|" + amount;
            this.ctx.store.saveNow();
            final TxResult r = this.ctx.bank.transfer(a.account(), "player:" + key, amount, "Firmy: zwrot wkładu do zrzutki na firmę " + a.name);
            if (!r.ok()) {
                a.pending = null;
                this.ctx.save();
                return Outcome.fail("Bank odrzucił zwrot (" + r + ").");
            }
            a.contributions.remove(key);
            a.contributorNames.remove(key);
            a.pending = null;
            this.ctx.save();
            return Outcome.ok("Wycofano wkład " + this.ctx.fmt(amount) + " ze zrzutki na „" + a.name + "”.");
        }
        return Outcome.fail("Nie bierzesz udziału w żadnej trwającej zrzutce.");
    }

    // ------------------------------------------------------------------ readiness (the start screen of /firma)

    /** One line of the founding checklist. */
    public record Check(boolean ok, String text) {
    }

    /** What a player still lacks to found a company with the seat in {@code here} (null = wilderness). */
    public List<Check> readiness(final UUID player, final UUID here) {
        final Settings s = this.s();
        final List<Check> out = new ArrayList<>();
        final long minutes = this.ctx.world.playMinutes(player);
        if (s.minPlayMinutes > 0) {
            out.add(new Check(minutes < 0 || minutes >= s.minPlayMinutes, "Czas gry: " + (minutes < 0 ? "?" : (minutes / 60) + " h")
                + " (potrzeba " + (s.minPlayMinutes / 60) + " h)"));
        }
        final long first = this.ctx.world.firstPlayed(player);
        if (s.minAccountDays > 0) {
            final long days = first > 0 ? (this.ctx.now() - first) / Ctx.DAY : -1;
            out.add(new Check(days < 0 || days >= s.minAccountDays, "Wiek konta: " + (days < 0 ? "?" : days + " dni")
                + " (potrzeba " + s.minAccountDays + ")"));
        }
        final long masterOf = this.ctx.firmsOf(player).stream().filter(f -> f.has(player, Role.MISTRZ)).count();
        final int memberOf = this.ctx.firmsOf(player).size();
        out.add(new Check(memberOf < s.maxMemberships && masterOf < s.maxMasterOf,
            "Firmy: " + memberOf + "/" + s.maxMemberships + ", jako mistrz: " + masterOf + "/" + s.maxMasterOf));
        final long have = this.ctx.bank.balance("player:" + player);
        final long target = this.gatheringTarget();
        out.add(new Check(have >= target || s.gatheringEnabled && have >= s.gatheringMinContribution,
            "Na koncie: " + this.ctx.fmt(have) + " (sam: " + this.ctx.fmt(target) + (s.gatheringEnabled
                ? "; zrzutka: od " + this.ctx.fmt(s.gatheringMinContribution) + ")" : ")")));
        final Optional<GameWorld.Country> c = here == null ? Optional.empty() : this.ctx.world.country(here);
        if (c.isEmpty()) {
            out.add(new Check(false, "Stoisz w dziczy — stań na ziemi kraju, który ma być siedzibą"));
        } else if (c.get().npc()) {
            final Optional<Integer> rep = this.ctx.world.reputation(here, player);
            final Optional<GameWorld.Country> own = this.ctx.world.countryOf(player);
            final boolean war = own.isPresent() && this.ctx.world.atWar(own.get().id(), here);
            final boolean ok = !war && (rep.isEmpty() ? s.npcAllowWithoutReputation : rep.get() >= s.npcMinReputation);
            out.add(new Check(ok, "Siedziba: " + c.get().name() + " (NPC) — " + (war ? "wojna z twoim krajem"
                : rep.isPresent() && rep.get() < s.npcMinReputation ? "za niska reputacja (" + rep.get() + ")"
                : "zgoda od razu" + (rep.isPresent() ? ", reputacja " + rep.get() : ""))));
        } else {
            final int rank = this.ctx.world.rank(here, player);
            out.add(new Check(true, "Siedziba: " + c.get().name() + " — " + (rank >= 3 ? "jesteś Władcą, zgoda od razu"
                : "zgodę daje Władca albo Doradca (do " + s.applicationHours + " h)")));
        }
        if (c.isPresent()) {
            final int era = this.ctx.world.era(here);
            out.add(new Check(true, "Manufaktura: " + (era >= s.manufactoryEra ? "dostępna"
                : "wymaga Renesansu (kraj jest w epoce " + era + ") — cech ma 1 warsztat średniowieczny od epoki 1")));
        }
        return out;
    }

    public Optional<FirmsData.Application> application(final String id) {
        return Optional.ofNullable(this.ctx.data.applications.get(id));
    }

    public List<FirmsData.Application> applicationsFor(final UUID country) {
        final List<FirmsData.Application> out = new ArrayList<>();
        for (final FirmsData.Application a : this.ctx.data.applications.values()) {
            if (a.country.equals(country.toString())) {
                out.add(a);
            }
        }
        return out;
    }

    /** Ruler/advisor (or admin) decision on an application. */
    public Outcome decide(final String appId, final UUID actor, final boolean admin, final boolean accept) {
        final FirmsData.Application a = this.ctx.data.applications.get(appId);
        if (a != null && a.gathering() && admin && !accept) {
            return this.refund(a, "zrzutka odrzucona przez administratora");
        }
        if (a != null && a.gathering()) {
            return Outcome.fail("Zrzutka na „" + a.name + "” jeszcze zbiera pieniądze (" + this.ctx.fmt(a.collected()) + " z "
                + this.ctx.fmt(this.gatheringTarget()) + ") — wniosek przyjdzie do władz kraju po zebraniu kwoty.");
        }
        if (a == null || !"OPEN".equals(a.state)) {
            return Outcome.fail("Nie ma takiego wniosku (mógł już zostać rozpatrzony albo wygasnąć).");
        }
        final UUID country = UUID.fromString(a.country);
        if (!admin && this.ctx.world.rank(country, actor) < 2) {
            return Outcome.fail("Wnioski rozpatruje Władca albo Doradca kraju siedziby.");
        }
        final String who = actor == null ? "admin" : this.ctx.world.playerName(actor);
        if (!accept) {
            return this.refund(a, "odrzucony przez " + who);
        }
        if (this.ctx.world.country(country).isEmpty()) {
            return this.refund(a, "kraj już nie istnieje");
        }
        final Outcome o = this.approve(a, actor, who);
        if (o.ok()) {
            return Outcome.ok("Zatwierdzono firmę „" + a.name + "”. Opłata " + this.ctx.fmt(a.fee) + " trafiła do skarbca kraju.", a.id);
        }
        return o;
    }

    private Outcome approve(final FirmsData.Application a, final UUID actor, final String who) {
        final long held = this.ctx.bank.balance(a.account());
        if (held < a.fee + a.capital) {
            return Outcome.fail("Depozyt wniosku jest niepełny — sprawa dla administratora.");
        }
        final Optional<GameWorld.Country> c = this.ctx.world.country(UUID.fromString(a.country));
        if (c.isEmpty()) {
            return this.refund(a, "kraj już nie istnieje");
        }
        final List<Bank.Leg> legs = new ArrayList<>();
        if (a.fee > 0) {
            legs.add(new Bank.Leg(a.account(), c.get().treasuryAccount(), a.fee, "Firmy: opłata rejestrowa firmy " + a.name));
        }
        legs.add(new Bank.Leg(a.account(), Firm.ACCOUNT_PREFIX + a.id, a.capital, "Firmy: kapitał początkowy firmy " + a.name));
        a.state = "FUNDING";
        this.ctx.store.saveNow();
        final TxResult r = this.ctx.bank.transferAll(legs);
        if (!r.ok()) {
            a.state = "OPEN";
            this.ctx.save();
            return Outcome.fail("Bank odrzucił założenie firmy (" + r + ").");
        }
        final Firm f = this.create(a);
        this.ctx.journal(f, "oplata", "Opłata rejestrowa do skarbca " + c.get().name() + " (" + who + ")", 0, who);
        this.ctx.journal(f, "wplata", "Kapitał początkowy od " + founders(a), a.capital, a.founderName);
        this.ctx.save();
        for (final Firm.Member m : f.members.values()) {
            this.ctx.notifier.player(m.id(), "<gold>[Firmy]</gold> Firma <white>„" + Names.plain(a.name) + "”</white> została założona ("
                + Names.plain(who) + ")" + (f.members.size() > 1 ? "; twoje udziały: " + People.pct(m.shareBp) : "")
                + ". Otwórz ją: <click:run_command:'/firma'><yellow>/firma</yellow></click>");
        }
        return Outcome.ok("Firma założona.", f.id);
    }

    private static String founders(final FirmsData.Application a) {
        if (a.contributions == null || a.contributions.size() <= 1) {
            return a.founderName;
        }
        final List<String> names = new ArrayList<>();
        for (final String u : a.contributions.keySet()) {
            names.add(a.contributorNames.getOrDefault(u, u.substring(0, 8)));
        }
        return "zrzutki: " + String.join(", ", names);
    }

    private Firm create(final FirmsData.Application a) {
        final Firm f = new Firm();
        f.id = a.id;
        f.name = a.name;
        f.type = a.type;
        f.seatCountry = a.country;
        f.seatCountryName = a.countryName;
        f.createdAt = this.ctx.now();
        f.lastActivity = f.createdAt;
        final UUID founder = UUID.fromString(a.founder);
        final boolean alone = a.contributions == null || a.contributions.isEmpty()
            || a.contributions.size() == 1 && a.contributions.containsKey(a.founder);
        if (alone) {
            f.members.put(a.founder, new Firm.Member(founder, a.founderName, Role.MISTRZ, 10_000, f.createdAt));
        } else {
            // co-founded: shares by contribution (exact to the basis point), the founder is the master, the others partners
            final Map<String, Long> shares = Split.proportional(10_000L, new LinkedHashMap<>(a.contributions));
            f.members.put(a.founder, new Firm.Member(founder, a.founderName, Role.MISTRZ,
                (int) (long) shares.getOrDefault(a.founder, 0L), f.createdAt));
            for (final var e : shares.entrySet()) {
                if (!e.getKey().equals(a.founder)) {
                    final UUID u = UUID.fromString(e.getKey());
                    f.members.put(e.getKey(), new Firm.Member(u, a.contributorNames.getOrDefault(e.getKey(), this.ctx.world.playerName(u)),
                        Role.WSPOLNIK, (int) (long) e.getValue(), f.createdAt));
                }
            }
        }
        this.ctx.data.firms.put(f.id, f);
        this.ctx.data.applications.remove(a.id);
        return f;
    }

    /** Whole escrow back: to the founder, or (co-founding) to every contributor exactly by their contribution. */
    private Outcome refund(final FirmsData.Application a, final String why) {
        final long held = this.ctx.bank.balance(a.account());
        final String before = a.state;
        final boolean gathering = "ZBIORKA".equals(before);
        a.state = "REFUNDING";
        this.ctx.store.saveNow();
        final Map<String, Long> parts = a.contributions == null || a.contributions.isEmpty() ? Map.of(a.founder, held)
            : Split.proportional(held, new LinkedHashMap<>(a.contributions));
        if (held > 0) {
            final List<Bank.Leg> legs = new ArrayList<>();
            for (final var e : parts.entrySet()) {
                if (e.getValue() > 0) {
                    legs.add(new Bank.Leg(a.account(), "player:" + e.getKey(), e.getValue(),
                        "Firmy: zwrot depozytu wniosku o firmę " + a.name + " (" + why + ")"));
                }
            }
            final TxResult r = this.ctx.bank.transferAll(legs);
            if (!r.ok()) {
                a.state = before;
                this.ctx.save();
                return Outcome.fail("Bank odrzucił zwrot depozytu (" + r + ").");
            }
        }
        this.ctx.data.applications.remove(a.id);
        this.ctx.save();
        final String what = gathering ? "Zrzutka" : "Wniosek";
        for (final var e : parts.entrySet()) {
            this.ctx.notifier.player(UUID.fromString(e.getKey()), "<gold>[Firmy]</gold> " + what + " na firmę „" + Names.plain(a.name) + "” — "
                + why + ". Zwrócono " + this.ctx.fmt(e.getValue()) + " na twoje konto.");
        }
        return Outcome.ok(what + " na firmę „" + a.name + "” zamknięty (" + why + "), zwrócono " + this.ctx.fmt(held) + ".");
    }

    /** The founder withdraws a waiting application (or cancels their gathering: everybody gets their money back). */
    public Outcome withdraw(final UUID founder) {
        for (final FirmsData.Application a : List.copyOf(this.ctx.data.applications.values())) {
            if (a.founder.equals(founder.toString()) && ("OPEN".equals(a.state) || a.gathering() && a.pending == null)) {
                return this.refund(a, a.gathering() ? "zrzutka odwołana przez zakładającego" : "wycofany przez zakładającego");
            }
        }
        return Outcome.fail("Nie masz oczekującego wniosku ani zrzutki.");
    }

    /** Expired applications and gatherings get their whole escrow back. */
    public void expire() {
        final long now = this.ctx.now();
        for (final FirmsData.Application a : List.copyOf(this.ctx.data.applications.values())) {
            if ("OPEN".equals(a.state) && a.expiresAt <= now) {
                this.refund(a, "wygasł po " + this.s().applicationHours + " h bez decyzji");
            } else if (a.gathering() && a.pending == null && a.expiresAt <= now) {
                this.refund(a, "nie zebrano " + this.ctx.fmt(this.gatheringTarget()) + " w " + this.s().applicationHours + " h");
            }
        }
    }

    /**
     * After a restart: an application caught mid-way is finished from the escrow balance (FUNDING + empty escrow + money
     * on the company account = the company exists; REFUNDING + empty escrow = refunded; a contribution in flight is
     * booked only when the dedicated escrow shows it).
     */
    public List<String> reconcile() {
        final List<String> notes = new ArrayList<>();
        for (final FirmsData.Application a : List.copyOf(this.ctx.data.applications.values())) {
            if (a.contributions == null) {
                a.contributions = new LinkedHashMap<>();
            }
            if (a.contributorNames == null) {
                a.contributorNames = new LinkedHashMap<>();
            }
            final long held = this.ctx.bank.balance(a.account());
            if (a.pending != null && !LOST.equals(a.pending)) {
                notes.add(this.settlePending(a, held));
                if (a.gathering() && a.contributions.isEmpty() && held == 0) {
                    this.ctx.data.applications.remove(a.id);
                    continue;
                }
            }
            if (a.gathering() && a.contributions.isEmpty() && held > 0 && a.pending == null) {
                // the list of contributions is gone (e.g. firmy.json rewritten by an older jar): never refund it all to the
                // founder automatically — the administrator decides from the escrow's ledger (/firma admin zrzutki)
                a.pending = LOST;
                this.ctx.notifier.admin("Firmy: zrzutka " + a.id + " (" + a.name + ") nie ma listy wkładów, a depozyt ma " + held
                    + " md — wstrzymana do decyzji administratora (/firma admin zrzutki)", true);
            }
            if (LOST.equals(a.pending)) {
                notes.add("Zrzutka " + a.name + " (" + a.id + "): brak listy wkładów — wstrzymana do decyzji administratora.");
            }
            if ("FUNDING".equals(a.state)) {
                if (held == 0 && this.ctx.bank.balance(Firm.ACCOUNT_PREFIX + a.id) >= a.capital) {
                    final Firm f = this.create(a);
                    this.ctx.journal(f, "wplata", "Kapitał początkowy od " + founders(a) + " (dokończone po restarcie)", a.capital, "system");
                    notes.add("Dokończono założenie firmy " + a.name + " po restarcie.");
                } else {
                    a.state = "OPEN";
                    notes.add("Wniosek " + a.name + " wrócił do oczekujących (założenie nie doszło do skutku).");
                }
            } else if ("REFUNDING".equals(a.state)) {
                if (held == 0) {
                    this.ctx.data.applications.remove(a.id);
                    notes.add("Wniosek " + a.name + " był już zwrócony.");
                } else {
                    a.state = !a.contributions.isEmpty() && a.collected() < this.gatheringTarget() ? "ZBIORKA" : "OPEN";
                }
            }
        }
        if (!notes.isEmpty()) {
            this.ctx.save();
        }
        return notes;
    }

    /** Marker of a gathering whose contributions are unknown: frozen for the administrator. */
    public static final String LOST = "?";

    /** Co-founded applications (gatherings and completed ones waiting for the seat), for the administrator. */
    public List<FirmsData.Application> coFounded() {
        final List<FirmsData.Application> out = new ArrayList<>();
        for (final FirmsData.Application a : this.ctx.data.applications.values()) {
            if (a.gathering() || a.contributions != null && a.contributions.size() > 1) {
                out.add(a);
            }
        }
        return out;
    }

    /** A contribution caught mid-way by a crash: the dedicated escrow balance tells whether the money moved. */
    private String settlePending(final FirmsData.Application a, final long held) {
        final String p = a.pending;
        a.pending = null;
        try {
            final boolean in = p.startsWith("+");
            final String[] kv = p.substring(1).split("\\|");
            final String who = kv[0];
            final long amount = Long.parseLong(kv[1]);
            final long before = a.collected();
            if (in && held == before + amount) {
                a.contributions.merge(who, amount, Long::sum);
                return "Zrzutka " + a.name + ": wkład " + amount + " md zaksięgowany po restarcie.";
            }
            if (!in && held == before - amount) {
                a.contributions.remove(who);
                a.contributorNames.remove(who);
                return "Zrzutka " + a.name + ": zwrot wkładu " + amount + " md zaksięgowany po restarcie.";
            }
            if (held == before) {
                if (in && !a.contributions.containsKey(who)) {
                    a.contributorNames.remove(who);
                }
                return "Zrzutka " + a.name + ": przerwana operacja nie przesunęła pieniędzy.";
            }
            this.ctx.notifier.admin("Firmy: zrzutka " + a.id + " (" + a.name + ") — depozyt " + held + " md nie zgadza się z wkładami "
                + before + " md (operacja " + p + "); zostaje do decyzji administratora", true);
            a.pending = p;
            return "Zrzutka " + a.name + ": NIEZGODNY depozyt — do sprawdzenia przez administratora.";
        } catch (final RuntimeException e) {
            a.pending = p;
            return "Zrzutka " + a.name + ": nieczytelna operacja " + p;
        }
    }

    // ------------------------------------------------------------------ country policy

    /** Tax on company income in basis points for the seat country. */
    public int taxBp(final UUID country) {
        final Optional<GameWorld.Country> c = this.ctx.world.country(country);
        if (c.isPresent() && c.get().npc()) {
            return this.s().npcTaxBp;
        }
        final FirmsData.CountryPolicy p = this.ctx.data.countries.get(country.toString());
        final int bp = p == null || p.taxBp < 0 ? this.s().defaultTaxBp : p.taxBp;
        return Math.max(0, Math.min(this.s().maxTaxBp, bp));
    }

    /** {@code /firma kraj podatek <procent>}: only the ruler of a player country. */
    public Outcome setTax(final UUID country, final UUID actor, final double percent, final boolean admin) {
        final Optional<GameWorld.Country> c = this.ctx.world.country(country);
        if (c.isEmpty()) {
            return Outcome.fail("Nie ma takiego kraju.");
        }
        if (c.get().npc()) {
            return Outcome.fail("Kraje NPC mają stały podatek od firm: " + (this.s().npcTaxBp / 100.0) + " %.");
        }
        if (!admin && this.ctx.world.rank(country, actor) < 3) {
            return Outcome.fail("Podatek od firm ustala Władca.");
        }
        final int bp = (int) Math.round(percent * 100.0);
        if (bp < 0 || bp > this.s().maxTaxBp) {
            return Outcome.fail("Podatek musi być w granicach 0–" + (this.s().maxTaxBp / 100) + " %.");
        }
        final FirmsData.CountryPolicy p = this.ctx.data.countries.computeIfAbsent(country.toString(), k -> new FirmsData.CountryPolicy());
        p.taxBp = bp;
        p.setBy = actor == null ? "admin" : actor.toString();
        p.setAt = this.ctx.now();
        this.ctx.save();
        for (final Firm f : this.ctx.data.firms.values()) {
            if (f.active() && country.toString().equals(f.seatCountry)) {
                this.ctx.journal(f, "podatek", "Kraj " + c.get().name() + " ustalił podatek od przychodu: " + (bp / 100.0) + " %", 0,
                    actor == null ? "admin" : this.ctx.world.playerName(actor));
                this.ctx.partners(f, "<gold>[Firmy]</gold> " + c.get().name() + " ustala podatek od przychodu firm: <yellow>"
                    + (bp / 100.0) + " %</yellow>.");
            }
        }
        return Outcome.ok("Podatek od przychodu firm w kraju " + c.get().name() + ": " + (bp / 100.0) + " %.");
    }
}
