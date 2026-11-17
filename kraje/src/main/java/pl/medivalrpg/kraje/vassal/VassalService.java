// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.vassal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.api.KrajeResult.Status;
import pl.medivalrpg.kraje.api.event.CapitulationOfferEvent;
import pl.medivalrpg.kraje.api.event.VassalageEndEvent;
import pl.medivalrpg.kraje.api.event.VassalageStartEvent;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.economy.TreasuryService;
import pl.medivalrpg.kraje.economy.TributeMath;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.manager.Notify;
import pl.medivalrpg.kraje.manager.RequestManager.CapitulationOffer;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.Vassalage;
import pl.medivalrpg.kraje.util.Format;

/**
 * Capitulation and vassalage: offers, acceptance (war ends, loser becomes vassal), daily tribute and debt, release,
 * buyout, expiry, and the end of a vassalage when either side disappears or the two go to war. Rule violations throw
 * {@link KrajeException} like {@code KrajeActions}. Main thread only.
 */
public final class VassalService {

    static final long DAY = 86_400_000L;

    private final KrajePlugin plugin;

    public VassalService(final KrajePlugin plugin) {
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

    private TreasuryService treasury() {
        return this.plugin.treasury();
    }

    /** Share as a percentage without float noise, e.g. 0.25 → "25", 0.125 → "12.5". */
    public static String percent(final double share) {
        return BigDecimal.valueOf(share).movePointRight(2).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ rules

    /** Current state for {@link VassalRules}. */
    public VassalRules.State state() {
        final CountryManager m = this.manager();
        return new VassalRules.State() {
            @Override
            public UUID overlordOf(final UUID country) {
                final Country c = m.get(country);
                return c == null ? null : c.overlord();
            }

            @Override
            public Relation relation(final UUID a, final UUID b) {
                return m.relation(a, b);
            }

            @Override
            public int debtDays(final UUID vassal) {
                final Country c = m.get(vassal);
                return c == null || c.vassalage() == null ? 0 : c.vassalage().debtDays();
            }
        };
    }

    /** Throws when {@code from} may not declare war on {@code to}; returns the verdict otherwise. */
    public VassalRules.Verdict requireWarAllowed(final Country from, final Country to) {
        final VassalRules.Verdict verdict = VassalRules.war(this.cfg().vassalRules, this.state(), from.id(), to.id());
        switch (verdict) {
            case DENIED_OVERLORD -> throw new KrajeException(Status.DENIED, "vassal-war-overlord", Notify.country(to));
            case DENIED_OVERLORD_ALLY -> {
                final Country overlord = this.manager().get(from.overlord());
                throw new KrajeException(Status.DENIED, "vassal-war-overlord-ally", Notify.country(to),
                    Notify.unparsed("overlord", overlord == null ? "?" : overlord.name()));
            }
            case DENIED_OWN_VASSAL -> throw new KrajeException(Status.DENIED, "overlord-war-vassal-denied", Notify.country(to),
                Notify.unparsed("days", this.cfg().vassalRules.debtWarDays()));
            default -> {
                return verdict;
            }
        }
    }

    /** Throws when {@code a} and {@code b} may not become allies. */
    public void requireAllianceAllowed(final Country a, final Country b) {
        if (VassalRules.alliance(this.cfg().vassalRules, this.state(), a.id(), b.id()) == VassalRules.Verdict.DENIED_OVERLORD_ENEMY) {
            throw new KrajeException(Status.DENIED, "vassal-alliance-overlord-enemy", Notify.pair(a, b));
        }
    }

    private void requireEnabled() {
        if (!this.cfg().vassalEnabled) {
            throw new KrajeException(Status.DENIED, "capitulation-disabled");
        }
    }

    // ------------------------------------------------------------------ tribute estimate

    /** Expected daily tribute of a vassal at the next settlement (0 without economy or when independent). */
    public long estimateTribute(final Country vassal) {
        final Vassalage v = vassal.vassalage();
        if (v == null || !this.treasury().active()) {
            return 0L;
        }
        return TributeMath.daily(v.tributeShare(), v.tributeMin(), this.treasury().estimatedDailyIncome(vassal));
    }

    private long estimateTribute(final Country c, final double share, final long min) {
        return this.treasury().active() ? TributeMath.daily(share, min, this.treasury().estimatedDailyIncome(c)) : 0L;
    }

    // ------------------------------------------------------------------ capitulation

    /** {@code /kraj kapitulacja <wróg> [dni]}: {@code loser} offers to become the vassal of {@code winner}. */
    public CapitulationOffer offer(final Country loser, final Country winner, final int days) {
        this.requireEnabled();
        if (loser == winner) {
            throw new KrajeException(Status.INVALID, "cannot-target-own-country");
        }
        if (loser.relationTo(winner.id()) != Relation.WAR) {
            throw new KrajeException(Status.NOT_AT_WAR, "not-at-war", Notify.country(winner));
        }
        if (loser.vassalage() != null) {
            throw new KrajeException(Status.DENIED, "capitulation-already-vassal", Notify.country(loser));
        }
        final KrajeConfig cfg = this.cfg();
        final int length = days <= 0 ? cfg.vassalDefaultDays : days;
        if (length < cfg.vassalMinDays || length > cfg.vassalMaxDays) {
            throw new KrajeException(Status.INVALID, "capitulation-days-range",
                Notify.unparsed("min", cfg.vassalMinDays), Notify.unparsed("max", cfg.vassalMaxDays));
        }
        final long expiresAt = System.currentTimeMillis() + cfg.capitulationExpiryMillis;
        final CapitulationOffer offer = new CapitulationOffer(loser.id(), winner.id(), length, cfg.tributeShare, cfg.tributeMin, expiresAt);
        this.plugin.requests().offerCapitulation(offer);
        final long estimate = this.estimateTribute(loser, offer.tributeShare(), offer.tributeMin());
        Notify.leaders(this.messages(), winner, "capitulation-received", this.terms(offer, estimate), Notify.country(loser),
            Placeholder.styling("accept", ClickEvent.runCommand("/kraj kapitulacja przyjmij " + loser.name())),
            Placeholder.styling("refuse", ClickEvent.runCommand("/kraj kapitulacja odrzuc " + loser.name())));
        Bukkit.getPluginManager().callEvent(new CapitulationOfferEvent(loser.id(), winner.id(), length, offer.tributeShare(),
            offer.tributeMin(), estimate, winner.npc(), expiresAt));
        return offer;
    }

    /** {@code <days> <share> <min> <estimate> <minutes>} of an offer. */
    public TagResolver terms(final CapitulationOffer offer, final long estimate) {
        return TagResolver.resolver(
            Notify.unparsed("days", offer.days()),
            Notify.unparsed("share", percent(offer.tributeShare())),
            Notify.unparsed("min", this.treasury().format(offer.tributeMin())),
            Notify.unparsed("estimate", this.treasury().format(estimate)),
            Notify.unparsed("minutes", Math.max(1, (offer.expiresAt() - System.currentTimeMillis() + 59_999L) / 60_000L)));
    }

    /** Estimated daily tribute for a fresh offer from {@code loser} (for messages). */
    public long offerEstimate(final Country loser) {
        return this.estimateTribute(loser, this.cfg().tributeShare, this.cfg().tributeMin);
    }

    /** {@code /kraj kapitulacja przyjmij|odrzuc <kraj>} and {@code KrajeApi#respondCapitulation}. */
    public KrajeResult respond(final Country winner, final Country loser, final boolean accept) {
        final CapitulationOffer offer = this.plugin.requests().capitulation(loser.id(), winner.id(), true);
        if (offer == null) {
            throw new KrajeException(Status.NO_PROPOSAL, "capitulation-no-offer", Notify.country(loser));
        }
        if (!accept) {
            Notify.leaders(this.messages(), loser, "capitulation-refused", Notify.country(winner));
            return KrajeResult.ok(winner.id());
        }
        this.requireEnabled();
        if (winner.relationTo(loser.id()) != Relation.WAR) {
            throw new KrajeException(Status.NOT_AT_WAR, "not-at-war", Notify.country(loser));
        }
        if (loser.vassalage() != null) {
            throw new KrajeException(Status.DENIED, "capitulation-already-vassal", Notify.country(loser));
        }
        this.start(loser, winner, offer);
        return KrajeResult.ok(loser.id());
    }

    private void start(final Country vassal, final Country overlord, final CapitulationOffer offer) {
        final long now = System.currentTimeMillis();
        final Vassalage v = new Vassalage(overlord.id(), now, now + offer.days() * DAY, offer.tributeShare(), offer.tributeMin());
        // War ends first (no minimum war time, sieges stop), then the vassalage begins.
        this.plugin.actions().endWarSilently(overlord, vassal);
        // A vassal cannot stay in a personal union (nor rule through one).
        this.plugin.unions().onVassalage(vassal);
        this.manager().setVassalage(vassal, v);
        Notify.broadcast(this.messages(), "capitulation-accepted-broadcast", Notify.pair(vassal, overlord),
            this.terms(offer, this.estimateTribute(vassal)), Notify.unparsed("until", Format.dateTime(v.until())));
        this.plugin.getLogger().info("Kapitulacja: " + vassal.name() + " zostaje wasalem " + overlord.name() + " na " + offer.days() + " dni.");
        Bukkit.getPluginManager().callEvent(new VassalageStartEvent(vassal.id(), overlord.id(), v.until(), v.tributeShare(), v.tributeMin()));
    }

    // ------------------------------------------------------------------ end of a vassalage

    /** Ends the vassalage of {@code vassal} (no-op when independent), announces it and fires {@link VassalageEndEvent}. */
    public void end(final Country vassal, final VassalageEndEvent.Reason reason, final long amount) {
        final Vassalage v = vassal.vassalage();
        if (v == null) {
            return;
        }
        final Country overlord = this.manager().get(v.overlord());
        this.manager().setVassalage(vassal, null);
        final TagResolver r = TagResolver.resolver(
            overlord == null ? TagResolver.resolver(Notify.unparsed("a", vassal.name()), Placeholder.styling("color_a", vassal.textColor()),
                Notify.unparsed("b", "?"), Placeholder.styling("color_b", vassal.textColor())) : Notify.pair(vassal, overlord),
            Notify.unparsed("amount", this.treasury().format(amount)));
        final String key = switch (reason) {
            case EXPIRED -> "vassal-ended-expired";
            case BOUGHT_OUT -> "vassal-ended-bought";
            case RELEASED -> "vassal-ended-released";
            case WAR -> "vassal-ended-war";
            case FALL, DISBANDED -> "vassal-ended-gone";
        };
        if (reason == VassalageEndEvent.Reason.FALL || reason == VassalageEndEvent.Reason.DISBANDED) {
            Notify.members(this.messages(), vassal, key, r);
            if (overlord != null) {
                Notify.members(this.messages(), overlord, key, r);
            }
        } else {
            Notify.broadcast(this.messages(), key, r);
        }
        Bukkit.getPluginManager().callEvent(new VassalageEndEvent(vassal.id(), v.overlord(), reason));
    }

    /** {@code /kraj zwolnij <wasal>}. */
    public void release(final Country overlord, final Country vassal) {
        if (!overlord.id().equals(vassal.overlord())) {
            throw new KrajeException(Status.NOT_FOUND, "release-not-vassal", Notify.country(vassal));
        }
        this.end(vassal, VassalageEndEvent.Reason.RELEASED, 0);
    }

    /** Price of a buyout now, -1 when not a vassal. */
    public long buyoutCost(final Country vassal) {
        final Vassalage v = vassal.vassalage();
        if (v == null) {
            return -1L;
        }
        return TributeMath.buyout(this.cfg().buyoutMultiplier, this.estimateTribute(vassal),
            TributeMath.remainingDays(System.currentTimeMillis(), v.until()), v.debt());
    }

    /** {@code /kraj wykup}: pays {@link #buyoutCost} to the overlord and ends the vassalage. Returns the price. */
    public long buyout(final Country vassal) {
        final Vassalage v = vassal.vassalage();
        if (v == null) {
            throw new KrajeException(Status.NOT_FOUND, "buyout-not-vassal");
        }
        this.treasury().requireActive();
        final long cost = this.buyoutCost(vassal);
        final Country overlord = this.manager().get(v.overlord());
        if (overlord != null && cost > 0) {
            this.treasury().requireFunds(vassal, cost);
            final long moved = this.treasury().transferBetween(vassal, overlord, cost, "buyout");
            if (moved < cost) {
                // Balance changed between the check and the transfer (should not happen on the main thread): give it back.
                if (moved > 0) {
                    this.treasury().transferBetween(overlord, vassal, moved, "buyout");
                }
                throw new KrajeException(Status.INSUFFICIENT_FUNDS, "treasury-insufficient",
                    Notify.unparsed("cost", this.treasury().format(cost)),
                    Notify.unparsed("balance", this.treasury().format(this.treasury().balance(vassal))));
            }
        }
        this.end(vassal, VassalageEndEvent.Reason.BOUGHT_OUT, cost);
        return cost;
    }

    /** Ends vassalages whose time ran out (called every minute). */
    public void checkExpiry() {
        for (final Country c : expired(this.manager().allVassals(), System.currentTimeMillis())) {
            this.end(c, VassalageEndEvent.Reason.EXPIRED, 0);
        }
    }

    /** Vassals whose vassalage is over at {@code now} (pure). */
    public static List<Country> expired(final java.util.Collection<Country> countries, final long now) {
        return countries.stream().filter(c -> c.vassalage() != null && c.vassalage().expired(now)).toList();
    }

    /** A country is about to be disbanded: every vassalage it is part of ends ({@code FALL} for a conquest). */
    public void onDisband(final Country c, final ClaimCause cause) {
        final VassalageEndEvent.Reason reason = cause == ClaimCause.CONQUEST ? VassalageEndEvent.Reason.FALL : VassalageEndEvent.Reason.DISBANDED;
        if (c.vassalage() != null) {
            this.end(c, reason, 0);
        }
        for (final Country vassal : this.manager().vassalsOf(c.id())) {
            this.end(vassal, reason, 0);
        }
    }

    /**
     * Relation of {@code a} (the side that caused it, e.g. the declarer) and {@code b} became war: a war between overlord
     * and vassal ends the vassalage; vassals of both sides are told (and join the war with {@code vassal.auto-join-overlord-wars}).
     */
    public void onWar(final Country a, final Country b) {
        if (a.id().equals(b.overlord())) {
            this.end(b, VassalageEndEvent.Reason.WAR, 0);
        } else if (b.id().equals(a.overlord())) {
            this.end(a, VassalageEndEvent.Reason.WAR, 0);
        }
        this.notifyVassals(a, b);
        this.notifyVassals(b, a);
    }

    private void notifyVassals(final Country overlord, final Country enemy) {
        final KrajeConfig cfg = this.cfg();
        for (final Country vassal : this.manager().vassalsOf(overlord.id())) {
            if (vassal == enemy) {
                continue;
            }
            final Relation rel = vassal.relationTo(enemy.id());
            final boolean join = cfg.vassalAutoJoinWars && rel == Relation.NEUTRAL && !enemy.id().equals(vassal.overlord())
                && !vassal.id().equals(enemy.overlord());
            if (cfg.vassalNotifyWars || join) {
                Notify.members(this.messages(), vassal, join ? "vassal-overlord-war-joined" : "vassal-overlord-war", Notify.pair(overlord, enemy));
            }
            if (join && this.manager().get(vassal.id()) != null && this.manager().get(enemy.id()) != null) {
                this.plugin.actions().applyRelation(vassal, enemy, Relation.WAR);
            }
        }
    }

    // ------------------------------------------------------------------ daily tribute

    /**
     * Daily settlement of a vassal ({@code income} = today's tax income: player taxes collected + NPC taxes): today's
     * tribute plus older debt is paid to the overlord as far as the treasury allows; the rest becomes debt.
     */
    public void settleTribute(final Country vassal, final long income) {
        final Vassalage v = vassal.vassalage();
        if (v == null || !this.treasury().active()) {
            return;
        }
        final Country overlord = this.manager().get(v.overlord());
        if (overlord == null) {
            return;
        }
        final long daily = TributeMath.daily(v.tributeShare(), v.tributeMin(), income);
        final TributeMath.Settlement s = TributeMath.settle(daily, v.debt(), v.debtDays(), this.treasury().balance(vassal));
        final long paid = s.paid() > 0 ? this.treasury().transferBetween(vassal, overlord, s.paid(), "tribute") : 0;
        final long debt = s.due() - paid;
        v.debt(debt);
        v.debtDays(debt > 0 ? v.debtDays() + 1 : 0);
        v.tributePaid(v.tributePaid() + paid);
        final KrajeConfig cfg = this.cfg();
        final TagResolver r = TagResolver.resolver(Notify.unparsed("paid", this.treasury().format(paid)),
            Notify.unparsed("due", this.treasury().format(s.due())), Notify.unparsed("debt", this.treasury().format(debt)),
            Notify.unparsed("days", v.debtDays()), Notify.unparsed("limit", cfg.vassalRules.debtWarDays()));
        if (debt > 0) {
            Notify.leaders(this.messages(), vassal, "tribute-unpaid", Notify.country(overlord), r);
            if (v.debtDays() >= cfg.vassalRules.debtWarDays()) {
                Notify.leaders(this.messages(), overlord, "tribute-debt-war-allowed", Notify.country(vassal), r,
                    Placeholder.styling("war", ClickEvent.suggestCommand("/kraj wojna " + vassal.name())));
            } else {
                Notify.leaders(this.messages(), overlord, "tribute-debt-overlord", Notify.country(vassal), r);
            }
        } else if (paid > 0) {
            Notify.leaders(this.messages(), vassal, "tribute-paid", Notify.country(overlord), r);
        }
        if (paid > 0) {
            Notify.leaders(this.messages(), overlord, "tribute-received", Notify.country(vassal), r);
        }
        this.manager().markChanged();
    }

    /** Vassals of a country (main thread). */
    public List<Country> vassalsOf(final Country overlord) {
        return this.manager().vassalsOf(overlord.id());
    }
}
