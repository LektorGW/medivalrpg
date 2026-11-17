// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.union;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.KrajeResult.Status;
import pl.medivalrpg.kraje.api.event.UnionDissolvedEvent;
import pl.medivalrpg.kraje.api.event.UnionDissolvedEvent.Reason;
import pl.medivalrpg.kraje.api.event.UnionFormedEvent;
import pl.medivalrpg.kraje.api.event.UnionProposalEvent;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.manager.Notify;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.PersonalUnion;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.util.Format;

/**
 * Personal unions: proposals, formation (shared ruler, alliance), breaking (by the monarch or by the partner's regent),
 * automatic dissolution (ruler change, war between the two, vassalage, fall, disbanding), the partner joining wars and
 * the "acting country" of the monarch for {@code /kraj} commands ({@code /kraj unia rzadz}). Rule violations throw
 * {@link KrajeException}. Main thread only.
 */
public final class UnionService {

    private record Pair(UUID from, UUID to) {
    }

    private final KrajePlugin plugin;
    /** (home, partner) → expiry of the proposal. */
    private final Map<Pair, Long> proposals = new HashMap<>();
    /** {a, b} → epoch millis when their last union ended (memory only; re-union cooldown). */
    private final Map<Set<UUID>, Long> endedAt = new HashMap<>();
    /** monarch → partner id while he issues /kraj commands as ruler of the partner. */
    private final Map<UUID, UUID> acting = new HashMap<>();

    public UnionService(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    private CountryManager manager() {
        return this.plugin.manager();
    }

    private KrajeConfig cfg() {
        return this.plugin.cfg();
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    // ------------------------------------------------------------------ state & rules

    public UnionRules.State state() {
        final CountryManager m = this.manager();
        return new UnionRules.State() {
            @Override
            public boolean exists(final UUID country) {
                return m.get(country) != null;
            }

            @Override
            public boolean npc(final UUID country) {
                final Country c = m.get(country);
                return c != null && c.npc();
            }

            @Override
            public UUID overlordOf(final UUID country) {
                final Country c = m.get(country);
                return c == null ? null : c.overlord();
            }

            @Override
            public UUID unionPartnerOf(final UUID country) {
                final Country partner = m.unionPartnerOf(m.get(country));
                return partner == null ? null : partner.id();
            }

            @Override
            public Relation relation(final UUID a, final UUID b) {
                return m.relation(a, b);
            }

            @Override
            public long unionEndedAt(final UUID a, final UUID b) {
                return UnionService.this.endedAt.getOrDefault(Set.of(a, b), 0L);
            }
        };
    }

    private void requireForm(final Country home, final Country partner) {
        final UnionRules.Verdict v = UnionRules.form(this.cfg().unionRules, this.state(), home.id(), partner.id(),
            System.currentTimeMillis());
        switch (v) {
            case ALLOWED -> {
            }
            case DISABLED -> throw new KrajeException(Status.DENIED, "union-disabled");
            case NOT_FOUND -> throw new KrajeException(Status.NOT_FOUND, "api-country-not-found");
            case SAME_COUNTRY -> throw new KrajeException(Status.INVALID, "cannot-target-own-country");
            case NPC_HOME -> throw new KrajeException(Status.DENIED, "union-npc-home");
            case NPC_PARTNER -> throw new KrajeException(Status.DENIED, "union-npc-refused", Notify.country(partner));
            case HOME_IN_UNION -> throw new KrajeException(Status.DENIED, "union-already", Notify.country(home));
            case PARTNER_IN_UNION -> throw new KrajeException(Status.DENIED, "union-already", Notify.country(partner));
            case AT_WAR -> throw new KrajeException(Status.DENIED, "union-at-war", Notify.country(partner));
            case HOME_VASSAL -> throw new KrajeException(Status.DENIED, "union-vassal", Notify.country(home));
            case PARTNER_VASSAL -> throw new KrajeException(Status.DENIED, "union-vassal", Notify.country(partner));
            case COOLDOWN -> throw new KrajeException(Status.DENIED, "union-cooldown", Notify.country(partner),
                Notify.unparsed("time", Format.duration(UnionRules.reformAvailableIn(this.cfg().unionRules,
                    this.endedAt.getOrDefault(Set.of(home.id(), partner.id()), 0L), System.currentTimeMillis()))));
        }
    }

    /** {@code /kraj wojna}: union partners never fight each other. */
    public void requireWarAllowed(final Country from, final Country to) {
        if (UnionRules.warForbidden(this.state(), from.id(), to.id())) {
            throw new KrajeException(Status.DENIED, "union-war-partner", Notify.country(to));
        }
    }

    /** {@code /kraj zerwij}: the alliance of union partners ends only with the union. */
    public void requireAllianceBreakAllowed(final Country from, final Country to) {
        if (UnionRules.warForbidden(this.state(), from.id(), to.id())) {
            throw new KrajeException(Status.DENIED, "union-break-alliance", Notify.country(to));
        }
    }

    /**
     * The partner of a union must keep at least one regular member (the future ruler when the union ends): its last
     * real member may not leave or be kicked.
     */
    public void requireCanLoseMember(final Country c, final UUID player) {
        if (c.union() != null && !c.npc() && c.isMember(player) && !c.isTitular(player) && c.realMemberCount() <= 1) {
            throw new KrajeException(Status.DENIED, "union-last-member", Notify.country(c));
        }
    }

    // ------------------------------------------------------------------ proposals

    /** {@code /kraj unia zaproponuj <kraj>}: the ruler of {@code home} offers to rule {@code partner} too. */
    public void propose(final Country home, final Country partner) {
        this.requireForm(home, partner);
        if (partner.npc() && this.cfg().unionNpcAutoRefuse) {
            throw new KrajeException(Status.DENIED, "union-npc-refused", Notify.country(partner));
        }
        this.plugin.treasury().requireFunds(home, this.cfg().unionFormCost);
        final long expiresAt = System.currentTimeMillis() + this.cfg().unionProposalExpiryMillis;
        this.proposals.put(new Pair(home.id(), partner.id()), expiresAt);
        Notify.leaders(this.messages(), partner, "union-proposal-received", Notify.pair(home, partner),
            Notify.unparsed("monarch", home.rulerName()),
            Notify.unparsed("minutes", Math.max(1, this.cfg().unionProposalExpiryMillis / 60_000L)),
            Placeholder.styling("accept", ClickEvent.runCommand("/kraj unia przyjmij " + home.name())),
            Placeholder.styling("refuse", ClickEvent.runCommand("/kraj unia odrzuc " + home.name())));
        Bukkit.getPluginManager().callEvent(new UnionProposalEvent(home.id(), partner.id(), partner.npc(), expiresAt));
    }

    /** A valid proposal exists (an expired one is dropped). The caller removes it once the answer is settled. */
    private boolean pending(final UUID home, final UUID partner) {
        final Pair key = new Pair(home, partner);
        final Long expiry = this.proposals.get(key);
        if (expiry != null && expiry < System.currentTimeMillis()) {
            this.proposals.remove(key);
            return false;
        }
        return expiry != null;
    }

    /** Valid proposals made to {@code partner} (suggestions). */
    public List<UUID> proposalsTo(final UUID partner) {
        final long now = System.currentTimeMillis();
        this.proposals.values().removeIf(e -> e < now);
        return this.proposals.keySet().stream().filter(p -> p.to().equals(partner)).map(Pair::from).toList();
    }

    /** {@code /kraj unia przyjmij|odrzuc <kraj>} and the API: {@code partner} answers {@code home}. */
    public void respond(final Country partner, final Country home, final boolean accept) {
        if (!this.pending(home.id(), partner.id())) {
            throw new KrajeException(Status.NO_PROPOSAL, "union-no-proposal", Notify.country(home));
        }
        if (!accept) {
            this.proposals.remove(new Pair(home.id(), partner.id()));
            Notify.leaders(this.messages(), home, "union-refused", Notify.country(partner));
            return;
        }
        // conditions and payment first: a refusal (e.g. no money in the treasury) keeps the proposal for a retry
        this.requireForm(home, partner);
        this.plugin.treasury().charge(home, this.cfg().unionFormCost, "union", home.rulerName());
        this.proposals.remove(new Pair(home.id(), partner.id()));
        this.form(home, partner);
    }

    private void form(final Country home, final Country partner) {
        final Member monarch = home.rulerMember();
        final Member regent = partner.npc() ? null : partner.rulerMember();
        final PersonalUnion union = new PersonalUnion(home.id(), home.ruler(), monarch == null ? home.rulerName() : monarch.name(),
            System.currentTimeMillis(), regent == null ? null : regent.uuid(), regent == null ? partner.rulerName() : regent.name());
        if (home.relationTo(partner.id()) != Relation.ALLIANCE) {
            this.plugin.actions().applyRelationQuiet(home, partner, Relation.ALLIANCE);
        }
        this.manager().formUnion(home, partner, union);
        this.proposals.keySet().removeIf(p -> p.from().equals(home.id()) || p.to().equals(home.id())
            || p.from().equals(partner.id()) || p.to().equals(partner.id()));
        Notify.broadcast(this.messages(), "union-formed-broadcast", Notify.pair(home, partner),
            Notify.unparsed("monarch", union.monarchName()), Notify.unparsed("regent", union.regentName().isEmpty() ? "—" : union.regentName()));
        this.plugin.getLogger().info("Unia personalna: " + home.name() + " + " + partner.name() + " (władca " + union.monarchName() + ").");
        Bukkit.getPluginManager().callEvent(new UnionFormedEvent(home.id(), partner.id(), union.monarch()));
    }

    // ------------------------------------------------------------------ breaking & dissolution

    /**
     * {@code /kraj unia zerwij}: {@code actor} (the monarch, or the regent of the partner when allowed) breaks the union of
     * {@code side} after the minimum duration; the breaking side pays {@code union.break-cost}.
     */
    public Reason breakUnion(final Country side, final UUID actor, final String actorName) {
        final Country partner = this.manager().unionPartnerSide(side);
        if (partner == null) {
            throw new KrajeException(Status.NOT_FOUND, "union-none", Notify.country(side));
        }
        final PersonalUnion u = partner.union();
        final Country home = this.manager().get(u.home());
        final Reason reason;
        if (u.monarch().equals(actor)) {
            reason = Reason.BROKEN;
        } else if (this.cfg().unionRegentCanSecede && actor != null && actor.equals(u.regent()) && partner.isMember(actor)) {
            reason = Reason.SECEDED;
        } else {
            throw new KrajeException(Status.DENIED, "union-break-denied");
        }
        final long now = System.currentTimeMillis();
        if (!UnionRules.canBreak(this.cfg().unionRules, u.since(), now)) {
            throw new KrajeException(Status.DENIED, "union-too-early", Notify.unparsed("time",
                Format.duration(UnionRules.breakableAt(this.cfg().unionRules, u.since()) - now)));
        }
        final Country payer = reason == Reason.BROKEN && home != null ? home : partner;
        this.plugin.treasury().charge(payer, this.cfg().unionBreakCost, "union", actorName);
        this.dissolve(partner, reason);
        return reason;
    }

    /** Ends the union {@code country} belongs to (no checks); no-op without a union. */
    public void dissolve(final Country country, final Reason reason) {
        final Country partner = this.manager().unionPartnerSide(country);
        if (partner == null) {
            return;
        }
        final PersonalUnion u = partner.union();
        final Country home = this.manager().get(u.home());
        final boolean wasNpc = partner.npc();
        final Member next = this.manager().endUnion(partner);
        this.acting.remove(u.monarch());
        this.endedAt.put(Set.of(u.home(), partner.id()), System.currentTimeMillis());
        if (home != null && !this.cfg().unionKeepAlliance && reason != Reason.WAR
            && home.relationTo(partner.id()) == Relation.ALLIANCE) {
            this.plugin.actions().applyRelationQuiet(home, partner, Relation.NEUTRAL);
        }
        final TagResolver r = TagResolver.resolver(
            home == null ? TagResolver.resolver(Notify.unparsed("a", "?"), Placeholder.styling("color_a", partner.textColor()),
                Notify.unparsed("b", Notify.displayName(partner)), Placeholder.styling("color_b", partner.textColor()))
                : Notify.pair(home, partner),
            Notify.unparsed("monarch", u.monarchName()), Notify.unparsed("regent", u.regentName().isEmpty() ? "—" : u.regentName()));
        final String key = "union-dissolved-" + switch (reason) {
            case BROKEN -> "broken";
            case SECEDED -> "seceded";
            case RULER_CHANGED -> "ruler";
            case WAR -> "war";
            case VASSALAGE -> "vassal";
            case FALL, DISBANDED -> "gone";
            case ADMIN -> "admin";
        };
        if (reason == Reason.FALL || reason == Reason.DISBANDED) {
            Notify.members(this.messages(), partner, key, r);
            if (home != null) {
                Notify.members(this.messages(), home, key, r);
            }
        } else {
            Notify.broadcast(this.messages(), key, r);
        }
        if (next != null) {
            Notify.members(this.messages(), partner, "union-new-ruler", Notify.country(partner), Notify.unparsed("player", next.name()));
        } else if (!wasNpc && partner.npc() && reason != Reason.FALL && reason != Reason.DISBANDED) {
            Notify.broadcast(this.messages(), "union-partner-npc", Notify.country(partner));
        }
        this.plugin.getLogger().info("Koniec unii " + (home == null ? "?" : home.name()) + " + " + partner.name() + " (" + reason + ").");
        Bukkit.getPluginManager().callEvent(new UnionDissolvedEvent(u.home(), partner.id(), u.monarch(), reason));
    }

    // ------------------------------------------------------------------ hooks

    /** A country is about to be disbanded or fell: its union ends first. */
    public void onDisband(final Country c, final ClaimCause cause) {
        this.dissolve(c, cause == ClaimCause.CONQUEST ? Reason.FALL : Reason.DISBANDED);
        this.proposals.keySet().removeIf(p -> p.from().equals(c.id()) || p.to().equals(c.id()));
    }

    /** {@code vassal} capitulated: a vassal cannot stay in a personal union. */
    public void onVassalage(final Country vassal) {
        this.dissolve(vassal, Reason.VASSALAGE);
    }

    /** The ruler of {@code c} changed: the union ends when the monarch no longer holds both thrones. */
    public void onRulerChanged(final Country c) {
        final Country partner = this.manager().unionPartnerSide(c);
        if (partner == null) {
            return;
        }
        final PersonalUnion u = partner.union();
        final Country home = this.manager().get(u.home());
        if (home == null || !u.monarch().equals(home.ruler()) || (!partner.npc() && !u.monarch().equals(partner.ruler()))) {
            this.dissolve(partner, Reason.RULER_CHANGED);
        }
    }

    /**
     * {@code attacker} and {@code defender} are now at war: a war between union partners (admin/AI) ends the union;
     * otherwise the defender's partner joins as a defender ({@code union.defend-partner}) and the attacker's partner
     * joins the attack ({@code union.join-offensive-wars}).
     */
    public void onWar(final Country attacker, final Country defender) {
        if (defender.id().equals(this.state().unionPartnerOf(attacker.id()))) {
            this.dissolve(attacker, Reason.WAR);
            return;
        }
        final UnionRules.Settings s = this.cfg().unionRules;
        final Country helpDefender = this.manager().get(UnionRules.joiningDefender(s, this.state(), attacker.id(), defender.id()));
        if (helpDefender != null) {
            Notify.members(this.messages(), helpDefender, "union-partner-defends", Notify.pair(defender, attacker));
            this.plugin.actions().applyRelation(attacker, helpDefender, Relation.WAR);
        }
        if (this.manager().get(attacker.id()) == null || this.manager().get(defender.id()) == null) {
            return;
        }
        final Country helpAttacker = this.manager().get(UnionRules.joiningAttacker(s, this.state(), attacker.id(), defender.id()));
        if (helpAttacker != null) {
            Notify.members(this.messages(), helpAttacker, "union-partner-attacks", Notify.pair(attacker, defender));
            this.plugin.actions().applyRelation(helpAttacker, defender, Relation.WAR);
        }
    }

    // ------------------------------------------------------------------ acting country of the monarch

    /** {@code /kraj unia rzadz}: toggles the monarch's /kraj commands between his home and the partner. Returns the new one. */
    public Country toggleActing(final UUID player, final Country home) {
        final Country partner = this.manager().unionPartnerOf(home);
        if (partner == null || partner.union() == null || partner.npc() || !partner.isTitular(player)) {
            throw new KrajeException(Status.DENIED, "union-not-monarch");
        }
        if (partner.id().equals(this.acting.get(player))) {
            this.acting.remove(player);
            return home;
        }
        this.acting.put(player, partner.id());
        return partner;
    }

    /** Country the player's /kraj commands act on: the union partner while toggled, otherwise his own country. */
    public Country actingCountry(final UUID player, final Country own) {
        final UUID target = this.acting.get(player);
        if (target == null || own == null) {
            return own;
        }
        final Country partner = this.manager().unionPartnerOf(own);
        if (partner != null && partner.id().equals(target) && partner.isTitular(player) && player.equals(partner.ruler())) {
            return partner;
        }
        this.acting.remove(player);
        return own;
    }

    public boolean isActing(final UUID player) {
        return this.acting.containsKey(player);
    }

    public void clear() {
        this.proposals.clear();
        this.acting.clear();
    }
}
