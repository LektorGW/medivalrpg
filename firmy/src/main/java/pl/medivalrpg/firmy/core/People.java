// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.Role;

/**
 * People of a company: invitations, roles, shares (always 100 %, changed only with consent of both sides), the master
 * hand-over, salaries. Offers live in memory (they expire in minutes).
 */
public final class People {

    private record Invite(String firm, Role role, long expires, String by) {
    }

    private record ShareOffer(String firm, UUID from, int bp, long expires) {
    }

    private record MasterOffer(String firm, UUID from, long expires) {
    }

    private final Ctx ctx;
    private final Registry registry;
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<UUID, ShareOffer> shareOffers = new HashMap<>();
    private final Map<UUID, MasterOffer> masterOffers = new HashMap<>();

    public People(final Ctx ctx, final Registry registry) {
        this.ctx = ctx;
        this.registry = registry;
    }

    private long expiry() {
        return this.ctx.now() + this.ctx.settings.inviteMinutes * 60_000L;
    }

    private static Optional<String> manageable(final Firm f) {
        if (!f.active()) {
            return Optional.of("Firma jest zlikwidowana.");
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ invitations

    public Outcome invite(final Firm f, final UUID actor, final UUID target, final String targetName, final Role role) {
        final Optional<String> m = manageable(f);
        if (m.isPresent()) {
            return Outcome.fail(m.get());
        }
        if (!f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("Zaprasza tylko mistrz firmy.");
        }
        if (role == null || role == Role.MISTRZ) {
            return Outcome.fail("Zaprosić można wspólnika albo czeladnika.");
        }
        if (f.members.containsKey(target.toString())) {
            return Outcome.fail(targetName + " już należy do firmy.");
        }
        final int max = this.ctx.settings.maxPeople.getOrDefault(f.type, 12);
        if (f.members.size() >= max) {
            return Outcome.fail("Firma ma już najwięcej ludzi (" + max + ").");
        }
        final Optional<String> p = this.registry.memberProblem(target, false);
        if (p.isPresent()) {
            return Outcome.fail(targetName + ": " + p.get());
        }
        this.invites.put(target, new Invite(f.id, role, this.expiry(), this.ctx.world.playerName(actor)));
        this.ctx.notifier.player(target, "<gold>[Firmy]</gold> Zaproszenie do firmy <white>„" + f.name + "”</white> jako <yellow>"
            + role.displayName() + "</yellow>. <click:run_command:'/firma przyjmij " + f.id + "'><green>[Przyjmij]</green></click> "
            + "<click:run_command:'/firma odrzuc " + f.id + "'><red>[Odrzuć]</red></click>");
        return Outcome.ok("Zaproszono " + targetName + " jako " + role.displayName() + " (ważne " + this.ctx.settings.inviteMinutes + " min).");
    }

    /** Name of the company the player is invited to (valid invitation), for the start screen. */
    public Optional<String> invitation(final UUID player) {
        final Invite inv = this.invites.get(player);
        if (inv == null || inv.expires() < this.ctx.now()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.ctx.data.firms.get(inv.firm())).filter(Firm::active).map(f -> f.name + " (" + inv.role().displayName() + ")");
    }

    public Outcome acceptInvite(final UUID player, final String playerName, final String firmId) {
        final Invite inv = this.invites.get(player);
        if (inv == null || inv.expires() < this.ctx.now() || (firmId != null && !firmId.isBlank() && !inv.firm().equals(firmId)
            && this.ctx.firm(firmId).map(x -> !x.id.equals(inv.firm())).orElse(true))) {
            return Outcome.fail("Nie masz ważnego zaproszenia do tej firmy.");
        }
        this.invites.remove(player);
        final Firm f = this.ctx.data.firms.get(inv.firm());
        if (f == null || !f.active()) {
            return Outcome.fail("Ta firma już nie istnieje.");
        }
        final Optional<String> p = this.registry.memberProblem(player, false);
        if (p.isPresent()) {
            return Outcome.fail(p.get());
        }
        if (f.members.size() >= this.ctx.settings.maxPeople.getOrDefault(f.type, 12)) {
            return Outcome.fail("Firma ma już komplet ludzi.");
        }
        f.members.put(player.toString(), new Firm.Member(player, playerName, inv.role(), 0, this.ctx.now()));
        f.lastActivity = this.ctx.now();
        this.ctx.journal(f, "ludzie", playerName + " dołącza jako " + inv.role().displayName(), 0, playerName);
        this.ctx.save();
        this.ctx.members(f, "<gold>[Firmy]</gold> <yellow>" + playerName + "</yellow> dołącza do firmy „" + f.name + "” jako "
            + inv.role().displayName() + ".");
        return Outcome.ok("Dołączasz do firmy „" + f.name + "”.");
    }

    public Outcome refuseInvite(final UUID player) {
        return this.invites.remove(player) != null ? Outcome.ok("Odrzucono zaproszenie.") : Outcome.fail("Nie masz zaproszenia.");
    }

    // ------------------------------------------------------------------ leaving, kicking, roles

    public Outcome kick(final Firm f, final UUID actor, final UUID target) {
        if (!f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("Wyrzuca tylko mistrz firmy.");
        }
        final Optional<Firm.Member> m = f.member(target);
        if (m.isEmpty()) {
            return Outcome.fail("Ten gracz nie należy do firmy.");
        }
        if (m.get().role == Role.MISTRZ) {
            return Outcome.fail("Mistrza nie można wyrzucić.");
        }
        if (m.get().shareBp > 0) {
            return Outcome.fail(m.get().name + " ma udziały (" + pct(m.get().shareBp)
                + "). Udziały zmieniają się tylko za zgodą obu stron: najpierw odkupcie je (/firma udzialy).");
        }
        f.members.remove(target.toString());
        this.ctx.journal(f, "ludzie", m.get().name + " usunięty z firmy", 0, this.ctx.world.playerName(actor));
        this.ctx.save();
        this.ctx.notifier.player(target, "<gold>[Firmy]</gold> Nie należysz już do firmy „" + f.name + "”."
            + (f.arrears.getOrDefault(target.toString(), 0L) > 0 ? " Zaległa pensja zostanie wypłacona, gdy firma będzie miała środki." : ""));
        return Outcome.ok("Usunięto " + m.get().name + " z firmy.");
    }

    /** A member leaves; their shares (if any) go to the master — they agreed by leaving. */
    public Outcome leave(final Firm f, final UUID player) {
        final Optional<Firm.Member> m = f.member(player);
        if (m.isEmpty()) {
            return Outcome.fail("Nie należysz do tej firmy.");
        }
        if (m.get().role == Role.MISTRZ) {
            return Outcome.fail("Mistrz nie może odejść: przekaż firmę (/firma mistrz <gracz>) albo ją zlikwiduj.");
        }
        final int shares = m.get().shareBp;
        f.members.remove(player.toString());
        if (shares > 0) {
            f.master().ifPresent(master -> master.shareBp += shares);
        }
        this.ctx.journal(f, "ludzie", m.get().name + " odchodzi z firmy" + (shares > 0 ? " (udziały " + pct(shares) + " przechodzą na mistrza)" : ""),
            0, m.get().name);
        this.ctx.save();
        this.ctx.members(f, "<gold>[Firmy]</gold> " + m.get().name + " odchodzi z firmy „" + f.name + "”.");
        return Outcome.ok("Odchodzisz z firmy „" + f.name + "”.");
    }

    public Outcome setRole(final Firm f, final UUID actor, final UUID target, final Role role) {
        if (!f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("Role nadaje mistrz.");
        }
        final Optional<Firm.Member> m = f.member(target);
        if (m.isEmpty()) {
            return Outcome.fail("Ten gracz nie należy do firmy.");
        }
        if (m.get().role == Role.MISTRZ || role == Role.MISTRZ) {
            return Outcome.fail("Mistrza zmienia się przez /firma mistrz <gracz>.");
        }
        if (role == Role.CZELADNIK && m.get().shareBp > 0) {
            return Outcome.fail("Czeladnik nie może mieć udziałów: najpierw przekażcie udziały.");
        }
        m.get().role = role;
        this.ctx.journal(f, "ludzie", m.get().name + " ma teraz rolę " + role.displayName(), 0, this.ctx.world.playerName(actor));
        this.ctx.save();
        this.ctx.notifier.player(target, "<gold>[Firmy]</gold> Twoja rola w „" + f.name + "”: <yellow>" + role.displayName() + "</yellow>.");
        return Outcome.ok(m.get().name + ": " + role.displayName() + ".");
    }

    // ------------------------------------------------------------------ shares

    /** The giver offers {@code bp} of their shares; the receiver must accept. Money for shares is settled between players. */
    public Outcome offerShares(final Firm f, final UUID giver, final UUID receiver, final int bp) {
        if (!f.active()) {
            return Outcome.fail("Firma jest zlikwidowana.");
        }
        final Optional<Firm.Member> g = f.member(giver);
        final Optional<Firm.Member> r = f.member(receiver);
        if (g.isEmpty() || r.isEmpty()) {
            return Outcome.fail("Obie strony muszą należeć do firmy.");
        }
        if (giver.equals(receiver)) {
            return Outcome.fail("Nie można przekazać udziałów samemu sobie.");
        }
        if (r.get().role == Role.CZELADNIK) {
            return Outcome.fail("Udziały może mieć tylko wspólnik albo mistrz (nadaj rolę: /firma rola).");
        }
        if (bp <= 0 || bp > g.get().shareBp) {
            return Outcome.fail("Masz " + pct(g.get().shareBp) + " udziałów.");
        }
        this.shareOffers.put(receiver, new ShareOffer(f.id, giver, bp, this.expiry()));
        this.ctx.notifier.player(receiver, "<gold>[Firmy]</gold> " + g.get().name + " przekazuje ci <yellow>" + pct(bp)
            + "</yellow> udziałów w „" + f.name + "”. Zapłatę rozliczacie między sobą przelewem. "
            + "<click:run_command:'/firma udzialy przyjmij'><green>[Przyjmij]</green></click>");
        return Outcome.ok("Zaproponowano " + r.get().name + " " + pct(bp) + " udziałów. Czeka na przyjęcie.");
    }

    public Outcome acceptShares(final UUID receiver) {
        final ShareOffer o = this.shareOffers.remove(receiver);
        if (o == null || o.expires() < this.ctx.now()) {
            return Outcome.fail("Nie masz ważnej propozycji udziałów.");
        }
        final Firm f = this.ctx.data.firms.get(o.firm());
        if (f == null || !f.active()) {
            return Outcome.fail("Firma już nie istnieje.");
        }
        final Optional<Firm.Member> g = f.member(o.from());
        final Optional<Firm.Member> r = f.member(receiver);
        if (g.isEmpty() || r.isEmpty() || g.get().shareBp < o.bp() || r.get().role == Role.CZELADNIK) {
            return Outcome.fail("Propozycja jest już nieaktualna.");
        }
        g.get().shareBp -= o.bp();
        r.get().shareBp += o.bp();
        this.ctx.journal(f, "udzialy", g.get().name + " → " + r.get().name + ": " + pct(o.bp()) + " udziałów", 0, r.get().name);
        this.ctx.save();
        this.ctx.partners(f, "<gold>[Firmy]</gold> Udziały w „" + f.name + "”: " + g.get().name + " → " + r.get().name + " " + pct(o.bp()) + ".");
        return Outcome.ok("Przyjęto " + pct(o.bp()) + " udziałów w „" + f.name + "”.");
    }

    // ------------------------------------------------------------------ master hand-over

    public Outcome offerMaster(final Firm f, final UUID actor, final UUID target) {
        if (!f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("Firmę przekazuje mistrz.");
        }
        final Optional<Firm.Member> t = f.member(target);
        if (t.isEmpty() || t.get().role != Role.WSPOLNIK) {
            return Outcome.fail("Firmę można przekazać tylko wspólnikowi.");
        }
        this.masterOffers.put(target, new MasterOffer(f.id, actor, this.expiry()));
        this.ctx.notifier.player(target, "<gold>[Firmy]</gold> Proponują ci rolę mistrza firmy „" + f.name + "”. "
            + "<click:run_command:'/firma mistrz przyjmij'><green>[Przyjmij]</green></click>");
        return Outcome.ok("Zaproponowano " + t.get().name + " rolę mistrza.");
    }

    public Outcome acceptMaster(final UUID player) {
        final MasterOffer o = this.masterOffers.remove(player);
        if (o == null || o.expires() < this.ctx.now()) {
            return Outcome.fail("Nie masz ważnej propozycji.");
        }
        final Firm f = this.ctx.data.firms.get(o.firm());
        if (f == null || !f.active() || !f.has(o.from(), Role.MISTRZ) || f.member(player).map(m -> m.role != Role.WSPOLNIK).orElse(true)) {
            return Outcome.fail("Propozycja jest już nieaktualna.");
        }
        final long masterOf = this.ctx.firmsOf(player).stream().filter(x -> x.has(player, Role.MISTRZ)).count();
        if (masterOf >= this.ctx.settings.maxMasterOf) {
            return Outcome.fail("Jesteś już mistrzem innej firmy.");
        }
        f.member(o.from()).ifPresent(m -> m.role = Role.WSPOLNIK);
        f.member(player).ifPresent(m -> m.role = Role.MISTRZ);
        final String name = this.ctx.world.playerName(player);
        this.ctx.journal(f, "ludzie", "Nowy mistrz: " + name, 0, name);
        this.ctx.save();
        this.ctx.members(f, "<gold>[Firmy]</gold> Nowy mistrz firmy „" + f.name + "”: <yellow>" + name + "</yellow>.");
        return Outcome.ok("Jesteś mistrzem firmy „" + f.name + "”.");
    }

    // ------------------------------------------------------------------ salaries

    public Outcome setSalary(final Firm f, final UUID actor, final UUID target, final long amount) {
        if (!f.active()) {
            return Outcome.fail("Firma jest zlikwidowana.");
        }
        if (!f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("Pensje ustala mistrz.");
        }
        final Optional<Firm.Member> m = f.member(target);
        if (m.isEmpty()) {
            return Outcome.fail("Ten gracz nie należy do firmy.");
        }
        if (amount < 0 || amount > this.ctx.settings.maxSalary) {
            return Outcome.fail("Pensja może wynosić 0–" + this.ctx.fmt(this.ctx.settings.maxSalary) + " dziennie.");
        }
        m.get().salary = amount;
        this.ctx.journal(f, "pensja", "Pensja " + m.get().name + ": " + this.ctx.fmt(amount) + " dziennie", 0, this.ctx.world.playerName(actor));
        this.ctx.save();
        this.ctx.notifier.player(target, "<gold>[Firmy]</gold> Twoja pensja w „" + f.name + "”: <yellow>" + this.ctx.fmt(amount) + "</yellow> dziennie.");
        return Outcome.ok("Pensja " + m.get().name + ": " + this.ctx.fmt(amount) + " dziennie (wypłata codziennie o "
            + this.ctx.settings.dailyHour + ":00).");
    }

    public static String pct(final int bp) {
        if (bp % 100 == 0) {
            return (bp / 100) + " %";
        }
        return String.format(java.util.Locale.ROOT, "%.2f %%", bp / 100.0).replace('.', ',');
    }
}
