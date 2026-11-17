// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.UnionInfo;
import pl.medivalrpg.kraje.api.event.UnionDissolvedEvent;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.embassy.EmbassyService;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Embassy;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.util.Format;

/**
 * {@code /kraj unia ...} (personal union) and {@code /kraj ambasada ...} (embassies). Rules live in
 * {@code UnionService} / {@code EmbassyService}; this class only resolves the sender's country, checks ranks and
 * renders the answers.
 */
final class DiplomacyCommands {

    static final String CONFIRM_BREAK = "unia-zerwij";

    private final KrajCommand cmd;

    DiplomacyCommands(final KrajCommand cmd) {
        this.cmd = cmd;
    }

    private KrajePlugin plugin() {
        return this.cmd.plugin();
    }

    private Messages messages() {
        return this.cmd.messages();
    }

    // ------------------------------------------------------------------ /kraj unia

    void union(final CommandSender sender, final String[] args) {
        final String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        final String[] rest = args.length == 0 ? new String[0] : java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "zaproponuj" -> this.unionPropose(KrajCommand.requirePlayer(sender), rest);
            case "przyjmij", "odrzuc" -> this.unionRespond(KrajCommand.requirePlayer(sender), rest, sub.equals("przyjmij"));
            case "zerwij" -> this.unionBreak(KrajCommand.requirePlayer(sender), rest);
            case "info" -> this.unionInfo(sender, rest);
            case "rzadz" -> this.unionActing(KrajCommand.requirePlayer(sender));
            default -> throw new KrajeException("usage-unia");
        }
    }

    private void unionPropose(final Player player, final String[] args) {
        KrajCommand.requireArgs(args, 1, "usage-unia");
        final Country own = this.cmd.requireCountry(player);
        KrajCommand.requireRank(own, player, Rank.RULER);
        final Country other = this.cmd.requireCountryArg(args[0]);
        if (other == own) {
            throw new KrajeException("cannot-target-own-country");
        }
        this.plugin().unions().propose(own, other);
        this.messages().send(player, other.npc() ? "union-proposed-npc" : "union-proposed", KrajCommand.country(other),
            KrajCommand.unparsed("cost", this.cmd.money(this.plugin().cfg().unionFormCost)));
    }

    private void unionRespond(final Player player, final String[] args, final boolean accept) {
        KrajCommand.requireArgs(args, 1, "usage-unia");
        final Country own = this.cmd.requireCountry(player);
        KrajCommand.requireRank(own, player, Rank.RULER);
        final Country home = this.cmd.requireCountryArg(args[0]);
        if (home == own) {
            throw new KrajeException("cannot-target-own-country");
        }
        this.plugin().unions().respond(own, home, accept);
        if (!accept) {
            this.messages().send(player, "union-refused-sent", KrajCommand.country(home));
        }
    }

    private void unionBreak(final Player player, final String[] args) {
        final Country own = this.cmd.requireCountry(player);
        final Country partnerSide = this.plugin().manager().unionPartnerSide(own);
        if (partnerSide == null) {
            throw new KrajeException("union-none", KrajCommand.country(own));
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase(KrajCommand.CONFIRM)) {
            this.cmd.requests().requestConfirmation(player.getUniqueId(), CONFIRM_BREAK, partnerSide.id(),
                this.plugin().cfg().confirmExpiryMillis);
            final Country other = this.plugin().manager().unionPartnerOf(own);
            this.messages().send(player, "union-break-confirm", KrajCommand.country(other == null ? own : other),
                KrajCommand.unparsed("cost", this.cmd.money(this.plugin().cfg().unionBreakCost)),
                KrajCommand.unparsed("seconds", this.plugin().cfg().confirmExpiryMillis / 1000),
                Placeholder.styling("confirm", ClickEvent.runCommand("/kraj unia zerwij " + KrajCommand.CONFIRM)));
            return;
        }
        if (!this.cmd.requests().confirm(player.getUniqueId(), CONFIRM_BREAK, partnerSide.id())) {
            throw new KrajeException("no-pending-confirmation");
        }
        final UnionDissolvedEvent.Reason reason = this.plugin().unions().breakUnion(own, player.getUniqueId(), player.getName());
        if (this.plugin().treasury().active() && this.plugin().cfg().unionBreakCost > 0) {
            this.messages().send(player, "union-break-paid", KrajCommand.unparsed("cost", this.cmd.money(this.plugin().cfg().unionBreakCost)),
                KrajCommand.unparsed("reason", reason.name()));
        }
    }

    private void unionInfo(final CommandSender sender, final String[] args) {
        final Country c;
        if (args.length > 0) {
            c = this.cmd.requireCountryArg(args[0]);
        } else {
            c = this.cmd.requireCountry(KrajCommand.requirePlayer(sender));
        }
        final UnionInfo u = this.plugin().manager().unionInfo(c.id());
        final Country home = u == null ? null : this.plugin().manager().get(u.home());
        final Country partner = u == null ? null : this.plugin().manager().get(u.partner());
        if (u == null || home == null || partner == null) {
            this.messages().send(sender, "union-info-none", KrajCommand.country(c));
            if (sender instanceof Player p && this.plugin().manager().countryOf(p.getUniqueId()) == c) {
                this.messages().send(sender, "union-info-how");
            }
            return;
        }
        final long now = System.currentTimeMillis();
        this.messages().send(sender, "union-info", TagResolver.resolver(
            Placeholder.component("home", InfoViews.countryName(home)),
            Placeholder.component("partner", InfoViews.countryName(partner)),
            KrajCommand.unparsed("monarch", u.monarchName()),
            KrajCommand.unparsed("regent", u.regentName().isEmpty() ? "—" : u.regentName()),
            KrajCommand.unparsed("since", Format.dateTime(u.since())),
            Placeholder.component("breakable", u.breakableAt() <= now
                ? this.messages().get("union-info-breakable-now")
                : this.messages().get("union-info-breakable-at", KrajCommand.unparsed("time", Format.duration(u.breakableAt() - now)))),
            KrajCommand.unparsed("defend", this.plugin().cfg().unionRules.defendPartner() ? "tak" : "nie")));
        if (sender instanceof Player p && p.getUniqueId().equals(u.monarch())) {
            this.messages().send(sender, this.plugin().unions().isActing(p.getUniqueId()) ? "union-info-acting-partner" : "union-info-acting-home",
                KrajCommand.country(this.plugin().unions().actingCountry(p.getUniqueId(), home)),
                Placeholder.styling("toggle", ClickEvent.runCommand("/kraj unia rzadz")));
        }
    }

    private void unionActing(final Player player) {
        final Country home = this.plugin().manager().countryOf(player.getUniqueId());
        if (home == null) {
            throw new KrajeException("not-in-country");
        }
        final Country now = this.plugin().unions().toggleActing(player.getUniqueId(), home);
        this.messages().send(player, now == home ? "union-acting-home" : "union-acting-partner", KrajCommand.country(now));
    }

    // ------------------------------------------------------------------ /kraj ambasada

    void embassy(final CommandSender sender, final String[] args) {
        final String sub = args.length == 0 ? "lista" : args[0].toLowerCase(Locale.ROOT);
        final String[] rest = args.length == 0 ? new String[0] : java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "otworz" -> this.embassyOpen(KrajCommand.requirePlayer(sender), rest);
            case "przyjmij", "odrzuc" -> this.embassyRespond(KrajCommand.requirePlayer(sender), rest, sub.equals("przyjmij"));
            case "zamknij" -> this.embassyClose(KrajCommand.requirePlayer(sender), rest);
            case "wydal" -> this.embassyExpel(KrajCommand.requirePlayer(sender), rest);
            case "lista" -> this.embassyList(sender, rest);
            case "tp" -> this.embassyTeleport(KrajCommand.requirePlayer(sender), rest);
            default -> throw new KrajeException("usage-ambasada");
        }
    }

    private void embassyOpen(final Player player, final String[] args) {
        KrajCommand.requireArgs(args, 1, "usage-ambasada");
        final Country guest = this.cmd.requireCountry(player);
        KrajCommand.requireRank(guest, player, this.plugin().cfg().embassyMinRank);
        final Country host = this.cmd.requireCountryArg(args[0]);
        if (host == guest) {
            throw new KrajeException("cannot-target-own-country");
        }
        if (this.plugin().embassies().request(guest, host, player.getLocation()) == EmbassyService.Outcome.REQUESTED) {
            this.messages().send(player, host.npc() ? "embassy-requested-npc" : "embassy-requested", KrajCommand.country(host),
                KrajCommand.unparsed("cost", this.cmd.money(this.plugin().cfg().embassyOpenCost)));
        }
    }

    private void embassyRespond(final Player player, final String[] args, final boolean accept) {
        KrajCommand.requireArgs(args, 1, "usage-ambasada");
        final Country host = this.cmd.requireCountry(player);
        KrajCommand.requireRank(host, player, Rank.RULER);
        final Country guest = this.cmd.requireCountryArg(args[0]);
        this.plugin().embassies().respond(host, guest, accept);
        if (!accept) {
            this.messages().send(player, "embassy-refused-sent", KrajCommand.country(guest));
        }
    }

    private void embassyClose(final Player player, final String[] args) {
        KrajCommand.requireArgs(args, 1, "usage-ambasada");
        final Country guest = this.cmd.requireCountry(player);
        KrajCommand.requireRank(guest, player, this.plugin().cfg().embassyMinRank);
        this.plugin().embassies().closeByGuest(guest, this.cmd.requireCountryArg(args[0]));
    }

    private void embassyExpel(final Player player, final String[] args) {
        KrajCommand.requireArgs(args, 1, "usage-ambasada");
        final Country host = this.cmd.requireCountry(player);
        KrajCommand.requireRank(host, player, Rank.RULER);
        this.plugin().embassies().expel(host, this.cmd.requireCountryArg(args[0]));
    }

    private void embassyList(final CommandSender sender, final String[] args) {
        final Country c;
        if (args.length > 0) {
            c = this.cmd.requireCountryArg(args[0]);
        } else {
            c = this.cmd.requireCountry(KrajCommand.requirePlayer(sender));
        }
        final boolean member = sender instanceof Player p && c.isMember(p.getUniqueId());
        final boolean ruler = sender instanceof Player p && KrajCommand.rankOf(c, p.getUniqueId()) == Rank.RULER;
        this.messages().send(sender, "embassy-list-header", KrajCommand.country(c));
        final List<Embassy> abroad = List.copyOf(c.embassiesMap().values());
        if (abroad.isEmpty()) {
            this.messages().send(sender, "embassy-list-abroad-none");
        } else {
            this.messages().send(sender, "embassy-list-abroad", KrajCommand.unparsed("count", abroad.size()));
            for (final Embassy e : abroad) {
                final Country host = this.plugin().manager().get(e.host());
                if (host == null) {
                    continue;
                }
                this.messages().send(sender, "embassy-list-entry", this.entry(host, e),
                    Placeholder.component("buttons", member
                        ? this.messages().get("embassy-tp-button").clickEvent(ClickEvent.runCommand("/kraj ambasada tp " + host.name()))
                        : Component.empty()));
            }
        }
        final List<Country> guests = this.plugin().manager().embassyGuestsIn(c.id());
        if (guests.isEmpty()) {
            this.messages().send(sender, "embassy-list-hosted-none");
        } else {
            this.messages().send(sender, "embassy-list-hosted", KrajCommand.unparsed("count", guests.size()));
            for (final Country guest : guests) {
                final Embassy e = guest.embassyIn(c.id());
                this.messages().send(sender, "embassy-list-entry", this.entry(guest, e),
                    Placeholder.component("buttons", ruler && !e.expelled()
                        ? this.messages().get("embassy-expel-button").clickEvent(ClickEvent.suggestCommand("/kraj ambasada wydal " + guest.name()))
                        : Component.empty()));
            }
        }
    }

    private TagResolver entry(final Country other, final Embassy e) {
        return TagResolver.resolver(
            Placeholder.component("country", InfoViews.countryName(other)),
            KrajCommand.unparsed("x", e.chunk().centerBlockX()), KrajCommand.unparsed("z", e.chunk().centerBlockZ()),
            KrajCommand.unparsed("world", e.chunk().world()), KrajCommand.unparsed("since", Format.date(e.openedAt())),
            Placeholder.component("expel", e.expelled()
                ? this.messages().get("embassy-list-expelled", KrajCommand.unparsed("until", Format.dateTime(e.closesAt())))
                : Component.empty()));
    }

    private void embassyTeleport(final Player player, final String[] args) {
        KrajCommand.requireArgs(args, 1, "usage-ambasada");
        final Country guest = this.cmd.requireCountry(player);
        KrajCommand.requireRank(guest, player, this.plugin().cfg().embassyTpMinRank);
        final Country host = this.cmd.requireCountryArg(args[0]);
        this.plugin().embassies().spot(guest, host); // throws when there is no embassy
        final java.util.UUID guestId = guest.id();
        final java.util.UUID hostId = host.id();
        this.cmd.teleportWithWarmup(player, () -> {
            final Country g = this.plugin().manager().get(guestId);
            final Country h = this.plugin().manager().get(hostId);
            if (g == null || h == null || g.embassyIn(hostId) == null
                || !KrajCommand.hasRank(g, player.getUniqueId(), this.plugin().cfg().embassyTpMinRank)
                || this.plugin().unions().actingCountry(player.getUniqueId(),
                    this.plugin().manager().countryOf(player.getUniqueId())) != g) {
                return null;
            }
            final Embassy e = g.embassyIn(hostId);
            return (e.expelled() && e.closesAt() <= System.currentTimeMillis())
                || !hostId.equals(this.plugin().manager().ownerAt(e.chunk().world(), e.chunk().key()))
                || g.relationTo(hostId) == pl.medivalrpg.kraje.model.Relation.WAR
                ? null : e.spot().toLocation();
        }, "embassy-teleported", KrajCommand.country(host));
    }
}
