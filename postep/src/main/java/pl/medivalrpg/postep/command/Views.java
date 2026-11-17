// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.postep.config.Messages;
import pl.medivalrpg.postep.config.PostepConfig;
import pl.medivalrpg.postep.logic.InvestmentMath;
import pl.medivalrpg.postep.logic.ResearchMath;
import pl.medivalrpg.postep.logic.TechTree;
import pl.medivalrpg.postep.model.Discovery;
import pl.medivalrpg.postep.model.EraDef;
import pl.medivalrpg.postep.service.ProgressService;
import pl.medivalrpg.postep.service.Snapshot;

/** Chat views: status, available discoveries, ranking, eras, discovery tooltips. */
public final class Views {

    private final ProgressService service;

    public Views(final ProgressService service) {
        this.service = service;
    }

    private Messages m() {
        return this.service.messages();
    }

    private PostepConfig cfg() {
        return this.service.cfg();
    }

    private static String fmt(final double v) {
        return ProgressService.fmt(v);
    }

    public static String percent(final double progress) {
        return Integer.toString((int) Math.floor(Math.max(0, Math.min(1, progress)) * 100));
    }

    /** {@code ■■■■□□□□□□} coloured by the era colour. */
    public Component bar(final double progress, final TextColor color) {
        final int len = 20;
        final int full = (int) Math.round(Math.max(0, Math.min(1, progress)) * len);
        return Component.text()
            .append(Component.text("■".repeat(full), color))
            .append(Component.text("■".repeat(len - full), TextColor.color(0x3A3A3A)))
            .build();
    }

    public TagResolver eraTags(final EraDef era, final String prefix) {
        return TagResolver.resolver(
            Placeholder.unparsed(prefix, era.name()),
            Placeholder.unparsed(prefix + "_acc", era.accusative()),
            Placeholder.unparsed(prefix + "_icon", era.icon()),
            Placeholder.unparsed(prefix + "_years", era.years()),
            Placeholder.styling(prefix + "_color", ProgressService.color(era.color())));
    }

    // ------------------------------------------------------------------ status

    public List<Component> status(final KrajInfo info, final Snapshot s, final boolean own) {
        final Messages m = this.m();
        final List<Component> lines = new ArrayList<>();
        final EraDef era = this.service.era(s.era());
        final TagResolver country = this.service.countryResolver(info);
        final TagResolver eraTags = this.eraTags(era, "era");
        lines.add(m.get("status-header", country, eraTags));

        if (s.check().atMaxEra()) {
            lines.add(m.get("status-progress-max", country, eraTags));
        } else {
            final EraDef next = this.service.era(s.era() + 1);
            lines.add(m.get("status-progress", eraTags, this.eraTags(next, "next"),
                Placeholder.component("bar", this.bar(s.check().progress(), ProgressService.color(next.color()))),
                Placeholder.unparsed("percent", percent(s.check().progress()))));
        }

        final ResearchMath.Breakdown d = s.daily();
        final Component hover = m.get("status-points-hover",
            Placeholder.unparsed("base", fmt(d.base())),
            Placeholder.unparsed("population", fmt(d.population())),
            Placeholder.unparsed("citizens", s.population() < 0 ? "?" : Integer.toString(s.population())),
            Placeholder.unparsed("players", fmt(d.players())),
            Placeholder.unparsed("player_count", Integer.toString(s.players())),
            Placeholder.unparsed("buildings", fmt(d.buildings())),
            Placeholder.unparsed("building_list", this.buildingList(d)),
            Placeholder.unparsed("diplomacy", signed(d.diplomacyPercent())),
            Placeholder.unparsed("research", signed(d.researchPercent())),
            Placeholder.unparsed("era_mult", fmt(era.pointMultiplier())),
            Placeholder.unparsed("multiplier", fmt(d.multiplier())),
            Placeholder.unparsed("total", fmt(d.total())));
        lines.add(m.get("status-points",
                Placeholder.unparsed("points", fmt(Math.floor(s.points()))),
                Placeholder.unparsed("daily", fmt(d.total())),
                Placeholder.unparsed("total", fmt(Math.floor(s.totalPoints()))),
                Placeholder.unparsed("discoveries", Integer.toString(s.discoveries().size())),
                Placeholder.unparsed("all", Integer.toString(this.service.tree().size())))
            .hoverEvent(HoverEvent.showText(hover)));

        final double rate = InvestmentMath.perCopper(era.investPerGold());
        final double nextGold = InvestmentMath.gain(s.investedToday(), 100, rate, era.investDailyCap());
        lines.add(m.get(own ? "status-invest" : "status-invest-other",
            Placeholder.unparsed("invested", this.service.skarbiec().format((long) s.investedToday())),
            Placeholder.unparsed("points", fmt(s.investPointsToday())),
            Placeholder.unparsed("cap", fmt(era.investDailyCap())),
            Placeholder.unparsed("next_gold", fmt(nextGold))));

        if (s.target() != null) {
            final Optional<Discovery> t = this.service.tree().get(s.target());
            if (t.isPresent()) {
                final long pathCost = this.service.tree().pathTo(t.get().id(), s.discoverySet()).stream().mapToLong(Discovery::cost).sum();
                lines.add(m.get(own ? "status-target" : "status-target-other",
                        Placeholder.unparsed("target", t.get().name()),
                        Placeholder.unparsed("cost", Long.toString(pathCost)),
                        Placeholder.unparsed("eta", this.eta(pathCost - s.points(), d.total())))
                    .hoverEvent(HoverEvent.showText(this.discoveryHover(t.get(), s))));
            }
        } else {
            lines.add(m.get(own ? "status-target-none" : "status-target-none-other"));
        }

        if (!s.check().atMaxEra()) {
            final EraDef next = this.service.era(s.era() + 1);
            if (s.check().ready()) {
                lines.add(m.get(s.pendingAdvance() ? "status-ready-pending" : "status-ready", this.eraTags(next, "next")));
            } else {
                lines.add(m.get("status-missing-header", this.eraTags(next, "next")));
                for (final String miss : s.check().missing()) {
                    lines.add(m.get("status-missing-line", Placeholder.unparsed("text", miss)));
                }
            }
        }

        if (own) {
            lines.add(m.get("status-available-header"));
            lines.add(this.availableLine(s));
            lines.add(m.get("status-buttons"));
        }
        return lines;
    }

    private String buildingList(final ResearchMath.Breakdown d) {
        if (d.buildingDetail().isEmpty()) {
            return this.m().raw("status-no-buildings");
        }
        final List<String> parts = new ArrayList<>();
        d.buildingDetail().forEach((k, v) -> parts.add(k + " +" + fmt(v)));
        return String.join(", ", parts);
    }

    private static String signed(final double v) {
        return (v >= 0 ? "+" : "") + fmt(v);
    }

    /** "gotowe", "~3 dni", "brak punktów". */
    public String eta(final double missingPoints, final double perDay) {
        if (missingPoints <= 0) {
            return this.m().raw("eta-ready");
        }
        if (perDay <= 0.01) {
            return this.m().raw("eta-never");
        }
        final long days = (long) Math.ceil(missingPoints / perDay);
        return this.m().raw("eta-days").replace("{days}", Long.toString(days));
    }

    /** Clickable chips of the available discoveries (required ones marked). */
    public Component availableLine(final Snapshot s) {
        final TechTree tree = this.service.tree();
        final List<Discovery> available = tree.available(s.discoverySet(), s.era());
        if (available.isEmpty()) {
            return this.m().get("status-available-none");
        }
        final List<Component> chips = new ArrayList<>();
        for (final Discovery d : available) {
            chips.add(this.m().get(d.requiredForEra() && d.era() == s.era() ? "chip-required" : "chip",
                    Placeholder.unparsed("name", d.name()),
                    Placeholder.unparsed("cost", Long.toString(d.cost())))
                .hoverEvent(HoverEvent.showText(this.discoveryHover(d, s)))
                .clickEvent(ClickEvent.runCommand("/postep odkryj " + d.id())));
        }
        return Component.join(JoinConfiguration.separator(Component.space()), chips);
    }

    /** Tooltip of a discovery for a country (null snapshot = generic). */
    public Component discoveryHover(final Discovery d, final Snapshot s) {
        return Component.join(JoinConfiguration.newlines(), this.discoveryLines(d, s));
    }

    /** Lines of {@link #discoveryHover}; the first one is the title. */
    public List<Component> discoveryLines(final Discovery d, final Snapshot s) {
        final Messages m = this.m();
        final TechTree tree = this.service.tree();
        final Set<String> owned = s == null ? Set.of() : s.discoverySet();
        final int era = s == null ? 1 : s.era();
        final List<Component> lines = new ArrayList<>();
        lines.add(m.get("hover-title", Placeholder.unparsed("name", d.name()), this.eraTags(this.service.era(d.era()), "era"),
            Placeholder.unparsed("category", this.category(d.category()))));
        if (!d.description().isBlank()) {
            lines.add(m.get("hover-description", Placeholder.unparsed("text", d.description())));
        }
        if (!d.effectText().isBlank()) {
            lines.add(m.get("hover-effect", Placeholder.unparsed("text", d.effectText())));
        }
        lines.add(m.get("hover-cost", Placeholder.unparsed("cost", Long.toString(d.cost()))));
        if (d.requires().isEmpty()) {
            lines.add(m.get("hover-requires-none"));
        } else {
            final List<Component> reqs = new ArrayList<>();
            for (final String r : d.requires()) {
                final String name = tree.get(r).map(Discovery::name).orElse(r);
                reqs.add(m.get(owned.contains(r) ? "hover-req-owned" : "hover-req-missing", Placeholder.unparsed("name", name)));
            }
            lines.add(m.get("hover-requires", Placeholder.component("list",
                Component.join(JoinConfiguration.separator(m.get("hover-req-separator")), reqs))));
        }
        if (d.requiredForEra()) {
            lines.add(m.get("hover-required-for-era", this.eraTags(this.service.era(Math.min(d.era() + 1, this.cfg().maxEra())), "next")));
        }
        if (s != null) {
            final String key = switch (tree.status(d, owned, era)) {
                case OWNED -> "hover-status-owned";
                case AVAILABLE -> s.points() + 1e-9 >= d.cost() ? "hover-status-affordable" : "hover-status-available";
                case LOCKED_REQUIREMENTS -> "hover-status-locked";
                case LOCKED_ERA -> "hover-status-era";
            };
            lines.add(m.get(key, this.eraTags(this.service.era(d.era()), "era"),
                Placeholder.unparsed("missing", fmt(Math.max(0, d.cost() - s.points()))),
                Placeholder.unparsed("eta", this.eta(d.cost() - s.points(), s.daily().total()))));
            if (s.target() != null && s.target().equals(d.id())) {
                lines.add(m.get("hover-is-target"));
            }
        }
        return lines;
    }

    public String category(final String key) {
        final String path = "kategorie." + key;
        return this.m().has(path) ? this.m().raw(path) : key;
    }

    // ------------------------------------------------------------------ ranking

    public List<Component> ranking(final int pageArg) {
        final Messages m = this.m();
        final List<Snapshot> all = this.service.ranking();
        final int perPage = 10;
        final int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        final int page = Math.max(1, Math.min(pageArg, pages));
        final List<Component> lines = new ArrayList<>();
        lines.add(m.get("ranking-header", Placeholder.unparsed("page", Integer.toString(page)),
            Placeholder.unparsed("pages", Integer.toString(pages))));
        if (all.isEmpty()) {
            lines.add(m.get("ranking-empty"));
            return lines;
        }
        for (int i = (page - 1) * perPage; i < Math.min(all.size(), page * perPage); i++) {
            final Snapshot s = all.get(i);
            final EraDef era = this.service.era(s.era());
            final Component line = m.get("ranking-line",
                    Placeholder.unparsed("pos", Integer.toString(i + 1)),
                    Placeholder.component("country", Component.text(s.name(), ProgressService.color(s.color()))),
                    Placeholder.component("npc", s.npc() ? m.get("ranking-npc") : Component.empty()),
                    this.eraTags(era, "era"),
                    Placeholder.unparsed("percent", s.check().atMaxEra() ? "100" : percent(s.check().progress())),
                    Placeholder.unparsed("discoveries", Integer.toString(s.discoveries().size())),
                    Placeholder.unparsed("points", fmt(Math.floor(s.totalPoints()))))
                .clickEvent(ClickEvent.runCommand("/postep kraj " + s.name()))
                .hoverEvent(HoverEvent.showText(m.get("ranking-hover", Placeholder.unparsed("country", s.name()))));
            lines.add(line);
        }
        if (pages > 1) {
            Component prev = m.get(page > 1 ? "ranking-prev" : "ranking-prev-disabled");
            Component next = m.get(page < pages ? "ranking-next" : "ranking-next-disabled");
            if (page > 1) {
                prev = prev.clickEvent(ClickEvent.runCommand("/postep ranking " + (page - 1)));
            }
            if (page < pages) {
                next = next.clickEvent(ClickEvent.runCommand("/postep ranking " + (page + 1)));
            }
            lines.add(m.get("ranking-nav", Placeholder.component("prev", prev), Placeholder.component("next", next)));
        }
        return lines;
    }

    // ------------------------------------------------------------------ eras

    public List<Component> eras(final Snapshot own) {
        final Messages m = this.m();
        final PostepConfig c = this.cfg();
        final List<Component> lines = new ArrayList<>();
        lines.add(m.get("epoki-header"));
        for (final EraDef e : c.eras) {
            final List<String> required = this.service.tree().requiredToLeave(e.id()).stream().map(Discovery::name).toList();
            final Component hover = m.get("epoki-hover", this.eraTags(e, "era"),
                Placeholder.unparsed("description", e.description()),
                Placeholder.unparsed("min_pop", e.minPopulation() > 0 ? Integer.toString(e.minPopulation()) : "—"),
                Placeholder.unparsed("min_tier", e.minTier() > 0 ? c.tierName(e.minTier()) : "—"),
                Placeholder.unparsed("min_days", e.id() >= c.maxEra() ? "—" : fmt(e.minDays())),
                Placeholder.unparsed("required", required.isEmpty() ? "—" : String.join(", ", required)),
                Placeholder.unparsed("count", Integer.toString(this.service.tree().inEra(e.id()).size())),
                Placeholder.unparsed("mult", fmt(e.pointMultiplier())));
            final String key = own == null ? "epoki-line" : own.era() == e.id() ? "epoki-line-current"
                : own.era() > e.id() ? "epoki-line-past" : "epoki-line";
            lines.add(m.get(key, this.eraTags(e, "era"), Placeholder.unparsed("description", e.description()))
                .hoverEvent(HoverEvent.showText(hover)));
        }
        return lines;
    }

    /** Chat list of the whole tree for the console (no GUI). */
    public List<Component> treeText(final Snapshot s) {
        final Messages m = this.m();
        final List<Component> lines = new ArrayList<>();
        final TechTree tree = this.service.tree();
        for (final EraDef e : this.cfg().eras) {
            lines.add(m.get("tree-era", this.eraTags(e, "era")));
            for (final Discovery d : tree.inEra(e.id())) {
                final String key = s == null ? "tree-line-locked" : switch (tree.status(d, s.discoverySet(), s.era())) {
                    case OWNED -> "tree-line-owned";
                    case AVAILABLE -> "tree-line-available";
                    default -> "tree-line-locked";
                };
                lines.add(m.get(key, Placeholder.unparsed("name", d.name()), Placeholder.unparsed("id", d.id()),
                        Placeholder.unparsed("cost", Long.toString(d.cost())))
                    .hoverEvent(HoverEvent.showText(this.discoveryHover(d, s))));
            }
        }
        return lines;
    }
}
