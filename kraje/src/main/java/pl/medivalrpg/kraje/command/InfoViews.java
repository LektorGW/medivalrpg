// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.OptionalInt;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.economy.TreasuryService;
import pl.medivalrpg.kraje.model.TreasuryEntry;
import pl.medivalrpg.kraje.siege.SiegeManager;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.util.ChunkKeys;
import pl.medivalrpg.kraje.model.Vassalage;
import pl.medivalrpg.kraje.manager.Notify;
import pl.medivalrpg.kraje.util.Format;
import pl.medivalrpg.kraje.vassal.VassalService;

/**
 * Renders {@code /kraj info}, {@code /kraj lista}, {@code /kraj mapa}, {@code /kraj oblezenia},
 * {@code /kraj skarbiec} and {@code /kraj podatek}.
 */
final class InfoViews {

    private static final int MAP_RADIUS = 5; // 11 x 11
    /**
     * One glyph for every cell (also the player's), so all rows have the same pixel width. U+2588 FULL BLOCK
     * comes from the bitmap {@code ascii.png} of the default font (advance 9 px, measured in the 26.3 client).
     * No row prefix (a space is 4 px, {@code N} 6 px), no bold (bold adds 1 px per glyph). The player is
     * marked by colour only.
     */
    static final String CELL = "█";
    private static final TextColor WILDERNESS = TextColor.color(0x3A3A3A);
    private static final TextColor PLAYER = TextColor.color(0xFFFFFF);
    private static final TextColor PLAYER_ALT = TextColor.color(0xFFFF55);
    static final int PAGE_SIZE = 10;

    private final KrajePlugin plugin;

    InfoViews(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    static Component countryName(final Country c) {
        return Component.text(Notify.displayName(c), c.textColor())
            .hoverEvent(HoverEvent.showText(Component.text("Kliknij: /kraj info " + c.name(), NamedTextColor.GRAY)))
            .clickEvent(ClickEvent.runCommand("/kraj info " + c.name()));
    }

    private Component joinCountries(final List<Country> list) {
        if (list.isEmpty()) {
            return Component.text("—", NamedTextColor.DARK_GRAY);
        }
        return Component.join(JoinConfiguration.commas(true), list.stream().map(InfoViews::countryName).toList());
    }

    void info(final CommandSender sender, final Country c) {
        final List<Country> allies = new ArrayList<>();
        final List<Country> wars = new ArrayList<>();
        for (final Map.Entry<UUID, Relation> e : c.relationsMap().entrySet()) {
            final Country other = this.plugin.manager().get(e.getKey());
            if (other == null) {
                continue;
            }
            if (e.getValue() == Relation.ALLIANCE) {
                allies.add(other);
            } else if (e.getValue() == Relation.WAR) {
                wars.add(other);
            }
        }
        final List<String> advisors = c.membersWithRank(Rank.ADVISOR).stream().map(Member::name).toList();
        final int citizens = c.membersWithRank(Rank.CITIZEN).size();
        final Component extra = this.infoExtra(sender, c);
        final TagResolver r = TagResolver.resolver(
            Placeholder.component("npc", c.npc() ? this.messages().get("npc-suffix") : Component.empty()),
            Placeholder.component("extra", extra),
            Placeholder.unparsed("country", Notify.displayName(c)),
            Placeholder.styling("color", c.textColor()),
            Placeholder.unparsed("hex", c.hexColor()),
            Placeholder.unparsed("description", c.description().isEmpty() ? "—" : c.description()),
            Placeholder.unparsed("ruler", c.rulerName()),
            Placeholder.unparsed("advisors", advisors.isEmpty() ? "—" : String.join(", ", advisors)),
            Placeholder.unparsed("citizens", Integer.toString(citizens)),
            Placeholder.unparsed("members", Integer.toString(c.memberCount())),
            Placeholder.unparsed("area", Format.area(c.claimCount())),
            Placeholder.unparsed("limit", Integer.toString(this.plugin.actions().limitOf(c).total())),
            Placeholder.unparsed("capital", c.capital().centerBlockX() + ", " + c.capital().centerBlockZ()
                + " (" + c.capital().world() + ")"),
            Placeholder.component("allies", this.joinCountries(allies)),
            Placeholder.component("wars", this.joinCountries(wars)),
            Placeholder.unparsed("founded", Format.date(c.foundedAt())));
        final String template = this.messages().raw("info");
        this.messages().send(sender, "info", r);
        // Older config.yml files on the server have no <extra>/<npc>: show the new lines after the old block.
        if (!template.contains("<extra>") && !extra.equals(Component.empty())) {
            sender.sendMessage(extra);
        }
    }

    /** NPC mark, economy, population, army, claim limit and sieges (only lines that have data). */
    private Component infoExtra(final CommandSender sender, final Country c) {
        final List<Component> lines = new ArrayList<>();
        final TagResolver country = KrajCommand.country(c);
        final String template = this.messages().raw("info");
        if (c.npc() && !template.contains("<npc>")) {
            lines.add(this.messages().get("info-npc", country));
        }
        if (!Notify.displayName(c).equals(c.name())) {
            lines.add(this.messages().get("info-real-name", country, Placeholder.unparsed("name", c.name())));
        }
        this.plugin.integrations().era(c.id()).ifPresent(era -> lines.add(this.messages().get(
            era.max() ? "info-era-max" : "info-era", country,
            Placeholder.parsed("badge", era.badge()),
            Placeholder.unparsed("era", era.name()),
            Placeholder.unparsed("progress", Format.percent(era.progress())),
            Placeholder.unparsed("name", c.name()))));
        final TreasuryService treasury = this.plugin.treasury();
        if (treasury.active()) {
            lines.add(this.messages().get("info-economy", country,
                Placeholder.unparsed("tax", treasury.format(c.taxPerCitizen())),
                Placeholder.unparsed("upkeep", treasury.format(treasury.upkeep(c))),
                Placeholder.unparsed("claim_cost", treasury.format(treasury.claimCost(c)))));
            final boolean insider = sender.hasPermission(KrajCommandTree.PERM_ADMIN)
                || (sender instanceof Player p && c.isMember(p.getUniqueId()));
            if (insider) {
                lines.add(this.messages().get("info-treasury", country, Placeholder.unparsed("balance", treasury.format(treasury.balance(c)))));
            }
            if (c.unpaidUpkeepDays() > 0) {
                lines.add(this.messages().get("info-bankrupt", country, Placeholder.unparsed("days", Integer.toString(c.unpaidUpkeepDays())),
                    Placeholder.unparsed("grace", Integer.toString(this.plugin.cfg().graceDays))));
            }
        }
        final OptionalInt population = this.plugin.integrations().population(c.id());
        if (population.isPresent()) {
            final OptionalInt housing = this.plugin.integrations().housing(c.id());
            lines.add(this.messages().get("info-population", country,
                Placeholder.unparsed("population", Integer.toString(population.getAsInt())),
                Placeholder.unparsed("housing", housing.isPresent() ? Integer.toString(housing.getAsInt()) : "—")));
        }
        final OptionalInt soldiers = this.plugin.integrations().soldiers(c.id());
        if (soldiers.isPresent()) {
            lines.add(this.messages().get("info-army", country, Placeholder.unparsed("soldiers", Integer.toString(soldiers.getAsInt()))));
        }
        final Country overlord = this.plugin.manager().get(c.overlord());
        if (overlord != null) {
            lines.add(this.messages().get("info-vassal-of", country,
                Placeholder.component("overlord", countryName(overlord)),
                Placeholder.unparsed("until", Format.dateTime(c.vassalage().until()))));
        }
        final List<Country> vassals = this.plugin.manager().vassalsOf(c.id());
        if (!vassals.isEmpty()) {
            lines.add(this.messages().get("info-vassals", country, Placeholder.component("vassals", this.joinCountries(vassals))));
        }
        final pl.medivalrpg.kraje.api.UnionInfo union = this.plugin.manager().unionInfo(c.id());
        final Country unionPartner = union == null ? null : this.plugin.manager().get(union.other(c.id()));
        if (unionPartner != null) {
            lines.add(this.messages().get("info-union", country, Placeholder.component("partner", countryName(unionPartner)),
                Placeholder.unparsed("monarch", union.monarchName()),
                Placeholder.unparsed("regent", union.regentName().isEmpty() ? "—" : union.regentName()),
                Placeholder.unparsed("since", Format.date(union.since()))));
        }
        final List<Country> embassyHosts = this.plugin.embassies().hostsOf(c);
        if (!embassyHosts.isEmpty()) {
            lines.add(this.messages().get("info-embassies-abroad", country, Placeholder.component("countries", this.joinCountries(embassyHosts))));
        }
        final List<Country> embassyGuests = this.plugin.manager().embassyGuestsIn(c.id());
        if (!embassyGuests.isEmpty()) {
            lines.add(this.messages().get("info-embassies-hosted", country, Placeholder.component("countries", this.joinCountries(embassyGuests))));
        }
        lines.add(this.messages().get("info-limit", country, this.plugin.actions().limitResolvers(c)));
        for (final SiegeManager.Siege s : this.plugin.sieges().sieges()) {
            if (s.attacker().equals(c.id()) || s.defender().equals(c.id())) {
                lines.add(this.siegeLine(s));
            }
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private Component siegeLine(final SiegeManager.Siege s) {
        final Country a = this.plugin.manager().get(s.attacker());
        final Country d = this.plugin.manager().get(s.defender());
        return this.messages().get("siege-list-entry",
            Placeholder.unparsed("a", a == null ? "?" : a.name()), Placeholder.styling("color_a", a == null ? NamedTextColor.GRAY : a.textColor()),
            Placeholder.unparsed("b", d == null ? "?" : d.name()), Placeholder.styling("color_b", d == null ? NamedTextColor.GRAY : d.textColor()),
            Placeholder.unparsed("x", Integer.toString(s.pos().centerBlockX())), Placeholder.unparsed("z", Integer.toString(s.pos().centerBlockZ())),
            Placeholder.unparsed("world", s.pos().world()),
            Placeholder.unparsed("progress", Integer.toString((int) Math.floor(s.progress() * 100))),
            Placeholder.unparsed("time", Format.clock(s.endsAt() - System.currentTimeMillis())));
    }

    /** {@code /kraj wasale [kraj]}: overlord (with terms and debt) and the list of vassals. */
    void vassals(final CommandSender sender, final Country c) {
        final TreasuryService t = this.plugin.treasury();
        final long now = System.currentTimeMillis();
        this.messages().send(sender, "vassals-header", KrajCommand.country(c));
        final Vassalage v = c.vassalage();
        final Country overlord = this.plugin.manager().get(c.overlord());
        if (v != null && overlord != null) {
            this.messages().send(sender, "vassals-overlord",
                Placeholder.component("overlord", countryName(overlord)),
                Placeholder.unparsed("until", Format.dateTime(v.until())),
                Placeholder.unparsed("left", Format.duration(v.remainingMillis(now))));
            this.messages().send(sender, "vassals-terms",
                Placeholder.unparsed("share", VassalService.percent(v.tributeShare())),
                Placeholder.unparsed("min", t.format(v.tributeMin())),
                Placeholder.unparsed("estimate", t.format(this.plugin.vassals().estimateTribute(c))),
                Placeholder.unparsed("paid", t.format(v.tributePaid())),
                Placeholder.component("debt", v.debt() > 0
                    ? this.messages().get("vassals-debt", Placeholder.unparsed("debt", t.format(v.debt())),
                        Placeholder.unparsed("days", Integer.toString(v.debtDays())))
                    : Component.empty()));
            if (t.active() && sender instanceof Player p && KrajCommand.rankOf(c, p.getUniqueId()) == Rank.RULER) {
                this.messages().send(sender, "vassals-buyout",
                    Placeholder.unparsed("cost", t.format(this.plugin.vassals().buyoutCost(c))),
                    Placeholder.styling("buyout", ClickEvent.runCommand("/kraj wykup")));
            }
        } else {
            this.messages().send(sender, "vassals-no-overlord");
        }
        final List<Country> vassals = this.plugin.manager().vassalsOf(c.id());
        if (vassals.isEmpty()) {
            this.messages().send(sender, "vassals-none");
            return;
        }
        this.messages().send(sender, "vassals-list-header", Placeholder.unparsed("count", Integer.toString(vassals.size())));
        final boolean ruler = sender instanceof Player p && KrajCommand.rankOf(c, p.getUniqueId()) == Rank.RULER && c.isMember(p.getUniqueId());
        for (final Country vassal : vassals) {
            final Vassalage vv = vassal.vassalage();
            this.messages().send(sender, "vassals-entry",
                Placeholder.component("vassal", countryName(vassal)),
                Placeholder.unparsed("until", Format.dateTime(vv.until())),
                Placeholder.unparsed("estimate", t.format(this.plugin.vassals().estimateTribute(vassal))),
                Placeholder.component("debt", vv.debt() > 0
                    ? this.messages().get("vassals-debt", Placeholder.unparsed("debt", t.format(vv.debt())),
                        Placeholder.unparsed("days", Integer.toString(vv.debtDays())))
                    : Component.empty()),
                Placeholder.component("release", ruler
                    ? this.messages().get("vassals-release-button").clickEvent(ClickEvent.suggestCommand("/kraj zwolnij " + vassal.name()))
                    : Component.empty()));
        }
    }

    /** {@code /kraj oblezenia}. */
    void sieges(final CommandSender sender) {
        final var all = this.plugin.sieges().sieges();
        if (all.isEmpty()) {
            this.messages().send(sender, "siege-list-empty");
            return;
        }
        this.messages().send(sender, "siege-list-header", Placeholder.unparsed("count", Integer.toString(all.size())));
        for (final SiegeManager.Siege s : all) {
            sender.sendMessage(this.siegeLine(s));
        }
    }

    /** {@code /kraj skarbiec}: balance, daily balance sheet and the last operations. */
    void treasury(final CommandSender sender, final Country c) {
        final TreasuryService t = this.plugin.treasury();
        final KrajeConfig cfg = this.plugin.cfg();
        final long taxpayers = c.members().stream().filter(m -> !(cfg.taxExemptRuler && m.rank() == Rank.RULER)).count();
        this.messages().send(sender, "treasury-header", KrajCommand.country(c),
            Placeholder.unparsed("balance", t.format(t.balance(c))),
            Placeholder.unparsed("tax", t.format(c.taxPerCitizen())),
            Placeholder.unparsed("taxpayers", Long.toString(taxpayers)),
            Placeholder.unparsed("income", t.format(Math.min(c.taxPerCitizen(), cfg.taxMax) * taxpayers)),
            Placeholder.unparsed("upkeep", t.format(t.upkeep(c))),
            Placeholder.unparsed("claim_cost", t.format(t.claimCost(c))),
            Placeholder.unparsed("time", cfg.dailyTickTime.toString()));
        if (c.unpaidUpkeepDays() > 0) {
            this.messages().send(sender, "info-bankrupt", KrajCommand.country(c),
                Placeholder.unparsed("days", Integer.toString(c.unpaidUpkeepDays())),
                Placeholder.unparsed("grace", Integer.toString(cfg.graceDays)));
        }
        if (c.treasuryLog().isEmpty()) {
            this.messages().send(sender, "treasury-log-empty");
            return;
        }
        this.messages().send(sender, "treasury-log-header");
        for (final TreasuryEntry e : c.treasuryLog()) {
            final String op = this.messages().raw("treasury-op." + e.type());
            this.messages().send(sender, e.amount() >= 0 ? "treasury-entry-plus" : "treasury-entry-minus",
                Placeholder.unparsed("date", Format.dateTime(e.time())),
                Placeholder.parsed("op", op),
                Placeholder.unparsed("amount", t.format(Math.abs(e.amount()))),
                Placeholder.unparsed("actor", e.actor()));
        }
    }

    /** {@code /kraj podatek}: current tax and debtors (with a kick button once allowed). */
    void taxes(final Player player, final Country c) {
        final TreasuryService t = this.plugin.treasury();
        final KrajeConfig cfg = this.plugin.cfg();
        this.messages().send(player, "tax-info", KrajCommand.country(c),
            Placeholder.unparsed("amount", t.format(c.taxPerCitizen())),
            Placeholder.unparsed("max", t.format(cfg.taxMax)),
            Placeholder.unparsed("time", cfg.dailyTickTime.toString()),
            Placeholder.unparsed("days", Integer.toString(cfg.taxKickAfterDays)));
        final List<Member> debtors = t.debtors(c);
        if (debtors.isEmpty()) {
            this.messages().send(player, "tax-no-debtors");
            return;
        }
        final Rank own = KrajCommand.rankOf(c, player.getUniqueId());
        for (final Member m : debtors) {
            final boolean kickable = own.atLeast(Rank.ADVISOR) && t.canKickForTaxes(m) && m.rank().weight() < own.weight();
            this.messages().send(player, "tax-debtor",
                Placeholder.unparsed("player", m.name()),
                Placeholder.unparsed("debt", t.format(m.taxDebt())),
                Placeholder.unparsed("days", Integer.toString(m.debtDays())),
                Placeholder.component("kick", kickable
                    ? this.messages().get("tax-kick-button").clickEvent(ClickEvent.runCommand("/kraj wyrzuc " + m.name()))
                    : Component.empty()));
        }
    }

    void list(final CommandSender sender, final int requestedPage) {
        final List<Country> sorted = this.plugin.manager().countries().stream()
            .sorted(Comparator.comparingInt(Country::claimCount).reversed()
                .thenComparing(c -> c.name().toLowerCase(java.util.Locale.ROOT)))
            .toList();
        if (sorted.isEmpty()) {
            this.messages().send(sender, "list-empty");
            return;
        }
        final int pages = (sorted.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        final int page = Math.max(1, Math.min(requestedPage, pages));
        this.messages().send(sender, "list-header",
            Placeholder.unparsed("page", Integer.toString(page)), Placeholder.unparsed("pages", Integer.toString(pages)),
            Placeholder.unparsed("count", Integer.toString(sorted.size())));
        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(sorted.size(), page * PAGE_SIZE); i++) {
            final Country c = sorted.get(i);
            this.messages().send(sender, "list-entry",
                Placeholder.unparsed("position", Integer.toString(i + 1)),
                Placeholder.component("country", c.npc() ? countryName(c).append(this.messages().get("npc-suffix")) : countryName(c)),
                Placeholder.styling("color", c.textColor()),
                Placeholder.unparsed("ruler", c.rulerName()),
                Placeholder.unparsed("members", Integer.toString(c.memberCount())),
                Placeholder.unparsed("area", Format.area(c.claimCount())));
        }
    }

    /** White, or light yellow when the chunk under the player is itself (almost) white. */
    static TextColor playerColor(final TextColor under) {
        return distance(under, PLAYER) >= distance(under, PLAYER_ALT) ? PLAYER : PLAYER_ALT;
    }

    private static int distance(final TextColor a, final TextColor b) {
        final int dr = a.red() - b.red();
        final int dg = a.green() - b.green();
        final int db = a.blue() - b.blue();
        return dr * dr + dg * dg + db * db;
    }

    void map(final Player player) {
        final String world = player.getWorld().getName();
        final int cx = player.getLocation().getBlockX() >> 4;
        final int cz = player.getLocation().getBlockZ() >> 4;
        this.messages().send(player, "map-header",
            Placeholder.unparsed("x", Integer.toString(cx)), Placeholder.unparsed("z", Integer.toString(cz)),
            Placeholder.unparsed("world", world));
        final Map<UUID, Country> legend = new LinkedHashMap<>();
        for (int dz = -MAP_RADIUS; dz <= MAP_RADIUS; dz++) {
            final TextComponent.Builder row = Component.text();
            for (int dx = -MAP_RADIUS; dx <= MAP_RADIUS; dx++) {
                final int x = cx + dx;
                final int z = cz + dz;
                final Country owner = this.plugin.manager().get(this.plugin.manager().ownerAt(world, ChunkKeys.key(x, z)));
                final boolean here = dx == 0 && dz == 0;
                final TextColor color = owner == null ? WILDERNESS : owner.textColor();
                final Component hover = Component.text()
                    .append(owner == null ? Component.text("Dzicz", NamedTextColor.DARK_GREEN) : Component.text(owner.name(), owner.textColor()))
                    .append(Component.newline())
                    .append(Component.text("Chunk " + x + ", " + z + (here ? " (tu stoisz)" : ""), NamedTextColor.GRAY))
                    .build();
                row.append(Component.text(CELL, here ? playerColor(color) : color)
                    .hoverEvent(HoverEvent.showText(hover)));
                if (owner != null) {
                    legend.putIfAbsent(owner.id(), owner);
                }
            }
            player.sendMessage(row.build());
        }
        if (!legend.isEmpty()) {
            final List<Component> entries = new ArrayList<>();
            for (final Country c : legend.values()) {
                entries.add(Component.text(CELL + " ", c.textColor()).append(countryName(c)));
            }
            this.messages().send(player, "map-legend",
                Placeholder.component("legend", Component.join(JoinConfiguration.separator(Component.text("  ")), entries)));
        }
        final Country here = this.plugin.manager().get(this.plugin.manager().ownerAt(world, ChunkKeys.key(cx, cz)));
        this.messages().send(player, "map-you", Placeholder.unparsed("symbol", CELL),
            Placeholder.styling("you", playerColor(here == null ? WILDERNESS : here.textColor())),
            Placeholder.styling("wild", WILDERNESS));
        final String url = this.plugin.cfg().mapUrl;
        if (!url.isEmpty()) {
            this.messages().send(player, "map-link",
                Placeholder.unparsed("url", url),
                Placeholder.styling("link", ClickEvent.openUrl(url)));
        }
    }
}
