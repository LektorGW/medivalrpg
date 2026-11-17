// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.postep.PostepPlugin;
import pl.medivalrpg.postep.api.PostepApi;
import pl.medivalrpg.postep.config.Messages;
import pl.medivalrpg.postep.logic.TechTree;
import pl.medivalrpg.postep.model.Discovery;
import pl.medivalrpg.postep.model.EraDef;
import pl.medivalrpg.postep.service.ProgressService;
import pl.medivalrpg.postep.service.Snapshot;

/**
 * Brigadier tree of {@code /postep} (alias {@code /epoka}) with Polish argument names and tooltips. Country names may
 * contain Polish letters and spaces, so admin arguments "kraj + value" are one greedy argument whose LAST word is the
 * value.
 */
public final class PostepCommand {

    public static final String PERM_USE = "postep.use";
    public static final String PERM_ADMIN = "postep.admin";

    private final PostepPlugin plugin;
    private final Views views;

    public PostepCommand(final PostepPlugin plugin, final Views views) {
        this.plugin = plugin;
        this.views = views;
    }

    private ProgressService service() {
        return this.plugin.service();
    }

    private Messages m() {
        return this.service().messages();
    }

    // ------------------------------------------------------------------ tree

    public LiteralCommandNode<CommandSourceStack> build(final String name) {
        final Predicate<CommandSourceStack> admin = s -> s.getSender().hasPermission(PERM_ADMIN);
        return Commands.literal(name)
            .requires(s -> s.getSender().hasPermission(PERM_USE))
            .executes(ctx -> this.run(ctx, this::status))
            .then(Commands.literal("pomoc")
                .executes(ctx -> this.run(ctx, s -> this.help(s, null)))
                .then(Commands.argument("strona", StringArgumentType.greedyString())
                    .suggests(this.provider(this::suggestHelpPages))
                    .executes(ctx -> this.run(ctx, s -> this.help(s, arg(ctx, "strona"))))))
            .then(Commands.literal("drzewko").executes(ctx -> this.run(ctx, this::tree)))
            .then(Commands.literal("odkrycia").executes(ctx -> this.run(ctx, this::available)))
            .then(Commands.literal("epoki").executes(ctx -> this.run(ctx, this::eras)))
            .then(Commands.literal("ranking")
                .executes(ctx -> this.run(ctx, s -> this.ranking(s, "1")))
                .then(Commands.argument("strona", StringArgumentType.word())
                    .suggests(this.provider(this::suggestRankingPages))
                    .executes(ctx -> this.run(ctx, s -> this.ranking(s, arg(ctx, "strona"))))))
            .then(Commands.literal("kraj")
                .executes(ctx -> this.run(ctx, this::status))
                .then(Commands.argument("kraj", StringArgumentType.greedyString())
                    .suggests(this.provider(this::suggestCountries))
                    .executes(ctx -> this.run(ctx, s -> this.other(s, arg(ctx, "kraj"))))))
            .then(Commands.literal("odkryj")
                .executes(ctx -> this.run(ctx, this::available))
                .then(Commands.argument("odkrycie", StringArgumentType.word())
                    .suggests(this.provider(this::suggestOwnDiscoveries))
                    .executes(ctx -> this.run(ctx, s -> this.discover(s, arg(ctx, "odkrycie"))))))
            .then(Commands.literal("cel")
                .executes(ctx -> this.run(ctx, this::target))
                .then(Commands.literal("wyczysc").executes(ctx -> this.run(ctx, this::clearTarget))))
            .then(Commands.literal("inwestuj")
                .executes(ctx -> this.run(ctx, s -> this.invest(s, null)))
                .then(Commands.argument("kwota", StringArgumentType.greedyString())
                    .suggests(this.provider(this::suggestAmounts))
                    .executes(ctx -> this.run(ctx, s -> this.invest(s, arg(ctx, "kwota"))))))
            .then(Commands.literal("awansuj").executes(ctx -> this.run(ctx, this::advance)))
            .then(Commands.literal("admin")
                .requires(admin)
                .executes(ctx -> this.run(ctx, s -> this.help(s, "admin")))
                .then(this.adminGreedy("ustaw", "kraj i epoka", this::suggestCountryThenEra, this::adminSetEra))
                .then(this.adminGreedy("punkty", "kraj i liczba", this::suggestCountryThenPoints, this::adminPoints))
                .then(this.adminGreedy("odkryj", "kraj i odkrycie", this::suggestCountryThenDiscovery, this::adminGrant))
                .then(this.adminGreedy("zabierz", "kraj i odkrycie", this::suggestCountryThenOwned, this::adminRevoke))
                .then(Commands.literal("tick").executes(ctx -> this.run(ctx, this::adminTick)))
                .then(Commands.literal("info").executes(ctx -> this.run(ctx, this::adminInfo)))
                .then(Commands.literal("przeladuj").executes(ctx -> this.run(ctx, this::adminReload)))
                .then(Commands.literal("reload").executes(ctx -> this.run(ctx, this::adminReload))))
            .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> adminGreedy(final String name, final String argName,
                                                                  final BiConsumer<CommandSender, SuggestionsBuilder> suggest,
                                                                  final BiConsumer<CommandSender, String> handler) {
        return Commands.literal(name)
            .executes(ctx -> this.run(ctx, s -> this.m().send(s, "admin-usage-" + name)))
            .then(Commands.argument(argName, StringArgumentType.greedyString())
                .suggests(this.provider(suggest))
                .executes(ctx -> this.run(ctx, s -> handler.accept(s, arg(ctx, argName)))));
    }

    private int run(final CommandContext<CommandSourceStack> ctx, final java.util.function.Consumer<CommandSender> handler) {
        final CommandSender sender = ctx.getSource().getSender();
        try {
            handler.accept(sender);
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Błąd komendy /postep", e);
            this.m().send(sender, "error-internal");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String arg(final CommandContext<CommandSourceStack> ctx, final String name) {
        return StringArgumentType.getString(ctx, name);
    }

    // ------------------------------------------------------------------ helpers

    private Optional<KrajInfo> ownCountry(final CommandSender sender) {
        if (!(sender instanceof Player p)) {
            return Optional.empty();
        }
        return this.service().kraje().getCountryOf(p.getUniqueId());
    }

    /** Own country + snapshot, or a message and empty. */
    private Optional<KrajInfo> requireCountry(final CommandSender sender) {
        if (!(sender instanceof Player)) {
            this.m().send(sender, "error-player-only");
            return Optional.empty();
        }
        final Optional<KrajInfo> info = this.ownCountry(sender);
        if (info.isEmpty()) {
            this.m().send(sender, "error-no-country");
        }
        return info;
    }

    private boolean hasRank(final CommandSender sender, final KrajInfo info, final Rank needed) {
        if (sender.hasPermission(PERM_ADMIN)) {
            return true;
        }
        final Rank rank = sender instanceof Player p ? info.rankOf(p.getUniqueId()) : null;
        if (rank == null || !rank.atLeast(needed)) {
            this.m().send(sender, "error-rank", Placeholder.unparsed("rank", needed.displayName()));
            return false;
        }
        return true;
    }

    private Snapshot snapshotOf(final KrajInfo info) {
        Optional<Snapshot> s = this.service().snapshot(info.id());
        if (s.isEmpty()) {
            this.service().ensureCountry(info.id());
            s = this.service().snapshot(info.id());
        }
        return s.orElseThrow();
    }

    private void sendLines(final CommandSender sender, final List<Component> lines) {
        lines.forEach(sender::sendMessage);
    }

    /** Splits "Kraj Wiele Słów wartość" into the country and the last word. */
    private record CountryAndValue(KrajInfo country, String value) {
    }

    private Optional<CountryAndValue> parseCountryAndValue(final CommandSender sender, final String text, final String usageKey) {
        final String trimmed = text.trim();
        final int space = trimmed.lastIndexOf(' ');
        if (space <= 0) {
            this.m().send(sender, usageKey);
            return Optional.empty();
        }
        final String name = trimmed.substring(0, space).trim();
        final String value = trimmed.substring(space + 1).trim();
        final Optional<KrajInfo> info = this.service().kraje().getCountryByName(name);
        if (info.isEmpty()) {
            this.m().send(sender, "error-unknown-country", Placeholder.unparsed("name", name));
            return Optional.empty();
        }
        return Optional.of(new CountryAndValue(info.get(), value));
    }

    // ------------------------------------------------------------------ player commands

    private void help(final CommandSender sender, final String page) {
        this.sendLines(sender, HelpBook.render(this.m(), sender.hasPermission(PERM_ADMIN), page));
    }

    private void status(final CommandSender sender) {
        if (!(sender instanceof Player)) {
            this.adminInfo(sender);
            return;
        }
        final Optional<KrajInfo> info = this.ownCountry(sender);
        if (info.isEmpty()) {
            this.m().send(sender, "status-no-country");
            return;
        }
        this.sendLines(sender, this.views.status(info.get(), this.snapshotOf(info.get()), true));
    }

    private void other(final CommandSender sender, final String name) {
        final Optional<KrajInfo> info = this.service().kraje().getCountryByName(name.trim());
        if (info.isEmpty()) {
            this.m().send(sender, "error-unknown-country", Placeholder.unparsed("name", name.trim()));
            return;
        }
        final boolean own = this.ownCountry(sender).map(k -> k.id().equals(info.get().id())).orElse(false);
        this.sendLines(sender, this.views.status(info.get(), this.snapshotOf(info.get()), own));
    }

    private void tree(final CommandSender sender) {
        if (sender instanceof Player p) {
            this.plugin.menus().open(p);
            return;
        }
        this.sendLines(sender, this.views.treeText(null));
    }

    private void available(final CommandSender sender) {
        final Optional<KrajInfo> info = this.requireCountry(sender);
        if (info.isEmpty()) {
            return;
        }
        final Snapshot s = this.snapshotOf(info.get());
        this.m().send(sender, "available-header", Placeholder.unparsed("points", ProgressService.fmt(Math.floor(s.points()))));
        sender.sendMessage(this.views.availableLine(s));
    }

    private void eras(final CommandSender sender) {
        final Snapshot own = this.ownCountry(sender).flatMap(k -> this.service().snapshot(k.id())).orElse(null);
        this.sendLines(sender, this.views.eras(own));
    }

    private void ranking(final CommandSender sender, final String page) {
        int p;
        try {
            p = Integer.parseInt(page.trim());
        } catch (final NumberFormatException e) {
            p = 1;
        }
        this.sendLines(sender, this.views.ranking(p));
    }

    /** Player version of {@link ProgressService#discover} with messages; also used by the GUI. */
    public void discover(final CommandSender sender, final String id) {
        final Optional<KrajInfo> info = this.requireCountry(sender);
        if (info.isEmpty() || !this.hasRank(sender, info.get(), this.service().cfg().discoverRank)) {
            return;
        }
        final ProgressService.DiscoverResult r = this.service().discover(info.get().id(), id);
        final Snapshot s = this.service().snapshot(info.get().id()).orElse(null);
        switch (r.kind()) {
            case UNKNOWN -> this.m().send(sender, "discover-unknown", Placeholder.unparsed("id", id));
            case NO_COUNTRY -> this.m().send(sender, "error-no-country");
            case ALREADY_OWNED -> this.m().send(sender, "discover-owned", Placeholder.unparsed("discovery", r.discovery().name()));
            case LATER_ERA -> this.m().send(sender, "discover-later-era", Placeholder.unparsed("discovery", r.discovery().name()),
                this.views.eraTags(this.service().era(r.discovery().era()), "era"));
            case UNLOCKED -> this.m().send(sender, "discover-unlocked", Placeholder.unparsed("discovery", r.discovery().name()),
                Placeholder.unparsed("effect", r.discovery().effectText()));
            case TARGET_SET -> {
                final String path = r.path().size() > 1
                    ? String.join(" → ", r.path().stream().map(Discovery::name).toList()) : r.discovery().name();
                this.m().send(sender, "discover-target", Placeholder.unparsed("discovery", r.discovery().name()),
                    Placeholder.unparsed("path", path),
                    Placeholder.unparsed("missing", ProgressService.fmt(Math.ceil(r.missingPoints()))),
                    Placeholder.unparsed("eta", this.views.eta(r.missingPoints(), s == null ? 0 : s.daily().total())));
            }
        }
    }

    private void target(final CommandSender sender) {
        final Optional<KrajInfo> info = this.requireCountry(sender);
        if (info.isEmpty()) {
            return;
        }
        final Snapshot s = this.snapshotOf(info.get());
        if (s.target() == null) {
            this.m().send(sender, "target-none");
            sender.sendMessage(this.views.availableLine(s));
            return;
        }
        final Discovery d = this.service().tree().get(s.target()).orElse(null);
        if (d == null) {
            this.m().send(sender, "target-none");
            return;
        }
        final List<Discovery> path = this.service().tree().pathTo(d.id(), s.discoverySet());
        final long cost = path.stream().mapToLong(Discovery::cost).sum();
        this.m().send(sender, "target-info", Placeholder.unparsed("discovery", d.name()),
            Placeholder.unparsed("path", String.join(" → ", path.stream().map(Discovery::name).toList())),
            Placeholder.unparsed("cost", Long.toString(cost)),
            Placeholder.unparsed("points", ProgressService.fmt(Math.floor(s.points()))),
            Placeholder.unparsed("eta", this.views.eta(cost - s.points(), s.daily().total())));
    }

    private void clearTarget(final CommandSender sender) {
        final Optional<KrajInfo> info = this.requireCountry(sender);
        if (info.isEmpty() || !this.hasRank(sender, info.get(), this.service().cfg().discoverRank)) {
            return;
        }
        this.m().send(sender, this.service().clearTarget(info.get().id()) ? "target-cleared" : "target-none");
    }

    private void invest(final CommandSender sender, final String amountText) {
        final Optional<KrajInfo> info = this.requireCountry(sender);
        if (info.isEmpty()) {
            return;
        }
        final Snapshot s = this.snapshotOf(info.get());
        final EraDef era = this.service().era(s.era());
        final String account = info.get().treasuryAccount() == null || info.get().treasuryAccount().isBlank()
            ? pl.medivalrpg.skarbiec.api.SkarbiecApi.countryAccount(info.get().id()) : info.get().treasuryAccount();
        if (amountText == null || amountText.isBlank()) {
            this.m().send(sender, "invest-info",
                Placeholder.unparsed("balance", this.service().skarbiec().format(this.service().skarbiec().balance(account))),
                Placeholder.unparsed("invested", this.service().skarbiec().format((long) s.investedToday())),
                Placeholder.unparsed("points", ProgressService.fmt(s.investPointsToday())),
                Placeholder.unparsed("cap", ProgressService.fmt(era.investDailyCap())),
                Placeholder.unparsed("next_gold", ProgressService.fmt(this.service().investPreview(info.get().id(), 100))),
                Placeholder.unparsed("next_ten", ProgressService.fmt(this.service().investPreview(info.get().id(), 1000))),
                Placeholder.unparsed("rank", this.service().cfg().investRank.displayName()));
            return;
        }
        if (!this.hasRank(sender, info.get(), this.service().cfg().investRank)) {
            return;
        }
        final Optional<Long> amount = this.service().skarbiec().parse(amountText.trim());
        if (amount.isEmpty() || amount.get() <= 0) {
            this.m().send(sender, "invest-bad-amount", Placeholder.unparsed("text", amountText.trim()));
            return;
        }
        final UUID by = sender instanceof Player p ? p.getUniqueId() : null;
        final PostepApi.InvestResult r = this.service().invest(info.get().id(), amount.get(), by);
        if (!r.ok()) {
            this.m().send(sender, "invest-failed", Placeholder.unparsed("reason", r.message()));
            return;
        }
        final Snapshot after = this.snapshotOf(info.get());
        final TagResolver tags = TagResolver.resolver(
            Placeholder.unparsed("player", sender.getName()),
            Placeholder.unparsed("amount", this.service().skarbiec().format(r.paid())),
            Placeholder.unparsed("points", ProgressService.fmt(r.points())),
            Placeholder.unparsed("total", ProgressService.fmt(Math.floor(after.points()))),
            Placeholder.unparsed("next_gold", ProgressService.fmt(this.service().investPreview(info.get().id(), 100))));
        this.m().send(sender, "invest-ok", tags);
        for (final UUID member : info.get().members().keySet()) {
            final Player p = Bukkit.getPlayer(member);
            if (p != null && !p.equals(sender)) {
                this.m().send(p, "invest-ok-members", tags);
            }
        }
    }

    private void advance(final CommandSender sender) {
        final Optional<KrajInfo> info = this.requireCountry(sender);
        if (info.isEmpty() || !this.hasRank(sender, info.get(), Rank.RULER)) {
            return;
        }
        if (!this.service().confirmAdvance(info.get().id())) {
            this.m().send(sender, "advance-not-ready");
            this.status(sender);
        }
    }

    // ------------------------------------------------------------------ admin

    private void adminSetEra(final CommandSender sender, final String text) {
        this.parseCountryAndValue(sender, text, "admin-usage-ustaw").ifPresent(cv -> {
            final int era = this.parseEra(cv.value());
            if (era < 1) {
                this.m().send(sender, "admin-bad-era", Placeholder.unparsed("text", cv.value()));
                return;
            }
            this.service().adminSetEra(cv.country().id(), era);
            this.m().send(sender, "admin-era-set", this.service().countryResolver(cv.country()),
                this.views.eraTags(this.service().era(era), "era"));
        });
    }

    private int parseEra(final String text) {
        try {
            final int n = Integer.parseInt(text.trim());
            return n >= 1 && n <= this.service().cfg().maxEra() ? n : -1;
        } catch (final NumberFormatException e) {
            final String norm = pl.medivalrpg.postep.logic.ResearchMath.normalize(text);
            for (final EraDef e2 : this.service().cfg().eras) {
                if (pl.medivalrpg.postep.logic.ResearchMath.normalize(e2.name()).startsWith(norm)) {
                    return e2.id();
                }
            }
            return -1;
        }
    }

    private void adminPoints(final CommandSender sender, final String text) {
        this.parseCountryAndValue(sender, text, "admin-usage-punkty").ifPresent(cv -> {
            final double delta;
            try {
                delta = Double.parseDouble(cv.value().replace(',', '.').replace("+", ""));
            } catch (final NumberFormatException e) {
                this.m().send(sender, "admin-bad-number", Placeholder.unparsed("text", cv.value()));
                return;
            }
            this.service().adminPoints(cv.country().id(), delta);
            final double now = this.service().snapshot(cv.country().id()).map(Snapshot::points).orElse(0.0);
            this.m().send(sender, "admin-points", this.service().countryResolver(cv.country()),
                Placeholder.unparsed("delta", (delta >= 0 ? "+" : "") + ProgressService.fmt(delta)),
                Placeholder.unparsed("points", ProgressService.fmt(Math.floor(now))));
        });
    }

    private void adminGrant(final CommandSender sender, final String text) {
        this.parseCountryAndValue(sender, text, "admin-usage-odkryj").ifPresent(cv -> {
            final Optional<Discovery> d = this.service().tree().get(cv.value().toLowerCase(Locale.ROOT));
            if (d.isEmpty()) {
                this.m().send(sender, "discover-unknown", Placeholder.unparsed("id", cv.value()));
                return;
            }
            final List<Discovery> granted = this.service().adminGrant(cv.country().id(), d.get().id());
            if (granted.isEmpty()) {
                this.m().send(sender, "admin-already", this.service().countryResolver(cv.country()),
                    Placeholder.unparsed("discovery", d.get().name()));
                return;
            }
            this.m().send(sender, "admin-granted", this.service().countryResolver(cv.country()),
                Placeholder.unparsed("list", String.join(", ", granted.stream().map(Discovery::name).toList())));
        });
    }

    private void adminRevoke(final CommandSender sender, final String text) {
        this.parseCountryAndValue(sender, text, "admin-usage-zabierz").ifPresent(cv -> {
            final List<String> removed = this.service().adminRevoke(cv.country().id(), cv.value().toLowerCase(Locale.ROOT));
            if (removed.isEmpty()) {
                this.m().send(sender, "admin-not-owned", this.service().countryResolver(cv.country()),
                    Placeholder.unparsed("id", cv.value()));
                return;
            }
            final TechTree t = this.service().tree();
            this.m().send(sender, "admin-revoked", this.service().countryResolver(cv.country()),
                Placeholder.unparsed("list", String.join(", ", removed.stream().map(id -> t.get(id).map(Discovery::name).orElse(id)).toList())));
        });
    }

    private void adminTick(final CommandSender sender) {
        this.service().dailyTick(System.currentTimeMillis());
        this.service().refreshAll();
        this.m().send(sender, "admin-tick", Placeholder.unparsed("count", Integer.toString(this.service().snapshots().size())));
    }

    private void adminInfo(final CommandSender sender) {
        final ProgressService s = this.service();
        final java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(s.cfg().zone);
        this.m().send(sender, "admin-info",
            Placeholder.unparsed("countries", Integer.toString(s.snapshots().size())),
            Placeholder.unparsed("discoveries", Integer.toString(s.tree().size())),
            Placeholder.unparsed("eras", Integer.toString(s.cfg().maxEra())),
            Placeholder.unparsed("last", f.format(java.time.Instant.ofEpochMilli(s.lastTickAt()))),
            Placeholder.unparsed("next", f.format(java.time.Instant.ofEpochMilli(s.nextTickAt()))),
            Placeholder.unparsed("ludnosc", this.plugin.integrations().population().present() ? "tak" : "nie"),
            Placeholder.unparsed("armia", this.plugin.integrations().npcAi().present() ? "tak" : "nie"));
        this.sendLines(sender, this.views.ranking(1));
    }

    private void adminReload(final CommandSender sender) {
        final List<String> problems = this.plugin.reloadAll();
        this.m().send(sender, problems.isEmpty() ? "admin-reloaded" : "admin-reloaded-problems",
            Placeholder.unparsed("count", Integer.toString(problems.size())));
        problems.forEach(p -> sender.sendMessage(Component.text(" • " + p)));
    }

    // ------------------------------------------------------------------ suggestions

    private SuggestionProvider<CommandSourceStack> provider(final BiConsumer<CommandSender, SuggestionsBuilder> fill) {
        return (ctx, builder) -> {
            final CommandSender sender = ctx.getSource().getSender();
            if (Bukkit.isPrimaryThread()) {
                fill.accept(sender, builder);
                return builder.buildFuture();
            }
            try {
                return CompletableFuture.supplyAsync(() -> {
                    fill.accept(sender, builder);
                    return builder.build();
                }, Bukkit.getScheduler().getMainThreadExecutor(this.plugin));
            } catch (final RuntimeException e) {
                return Suggestions.empty();
            }
        };
    }

    private static void add(final SuggestionsBuilder builder, final String text, final Component tooltip) {
        if (text.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
            builder.suggest(text, MessageComponentSerializer.message().serialize(tooltip));
        }
    }

    private void suggestHelpPages(final CommandSender sender, final SuggestionsBuilder builder) {
        final List<HelpBook.Category> pages = HelpBook.pages(sender.hasPermission(PERM_ADMIN));
        for (int i = 0; i < pages.size(); i++) {
            add(builder, Integer.toString(i + 1), this.m().parse(HelpBook.categoryName(this.m(), pages.get(i))));
        }
    }

    private void suggestRankingPages(final CommandSender sender, final SuggestionsBuilder builder) {
        final int pages = Math.max(1, (this.service().snapshots().size() + 9) / 10);
        for (int i = 1; i <= pages; i++) {
            add(builder, Integer.toString(i), this.m().get("suggest-page", Placeholder.unparsed("page", Integer.toString(i))));
        }
    }

    private Component countryTooltip(final KrajInfo k) {
        final Snapshot s = this.service().snapshot(k.id()).orElse(null);
        final EraDef era = this.service().era(s == null ? 1 : s.era());
        return this.m().get("suggest-country", this.service().countryResolver(k), this.views.eraTags(era, "era"),
            Placeholder.unparsed("percent", s == null ? "0" : Views.percent(s.check().progress())));
    }

    private void suggestCountries(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final KrajInfo k : this.service().kraje().getCountries()) {
            add(builder, k.name(), this.countryTooltip(k));
        }
    }

    private Component discoveryTooltip(final Discovery d, final Snapshot s) {
        final String statusKey;
        if (s == null) {
            statusKey = "suggest-status-none";
        } else {
            statusKey = switch (this.service().tree().status(d, s.discoverySet(), s.era())) {
                case OWNED -> "suggest-status-owned";
                case AVAILABLE -> s.points() + 1e-9 >= d.cost() ? "suggest-status-affordable" : "suggest-status-available";
                case LOCKED_REQUIREMENTS -> "suggest-status-path";
                case LOCKED_ERA -> "suggest-status-era";
            };
        }
        return this.m().get("suggest-discovery", Placeholder.unparsed("name", d.name()),
            Placeholder.unparsed("cost", Long.toString(d.cost())),
            Placeholder.component("status", this.m().get(statusKey)),
            this.views.eraTags(this.service().era(d.era()), "era"));
    }

    private void suggestOwnDiscoveries(final CommandSender sender, final SuggestionsBuilder builder) {
        final Snapshot s = this.ownCountry(sender).flatMap(k -> this.service().snapshot(k.id())).orElse(null);
        for (final Discovery d : this.service().tree().all()) {
            if (s != null && (s.discoverySet().contains(d.id()) || d.era() > s.era())) {
                continue;
            }
            add(builder, d.id(), this.discoveryTooltip(d, s));
        }
    }

    private void suggestAmounts(final CommandSender sender, final SuggestionsBuilder builder) {
        final Optional<KrajInfo> k = this.ownCountry(sender);
        for (final String text : List.of("50", "1zl", "5zl", "10zl", "25zl", "50zl", "100zl")) {
            final long amount = this.service().skarbiec().parse(text).orElse(0L);
            final double pts = k.map(info -> this.service().investPreview(info.id(), amount)).orElse(0.0);
            add(builder, text, this.m().get("suggest-amount", Placeholder.unparsed("amount", this.service().skarbiec().format(amount)),
                Placeholder.unparsed("points", ProgressService.fmt(pts))));
        }
    }

    /** First the country (may contain spaces), then the value after "<country> ". */
    private void countryThen(final SuggestionsBuilder builder, final java.util.function.BiConsumer<KrajInfo, SuggestionsBuilder> second) {
        final String remaining = builder.getRemaining();
        final String lower = remaining.toLowerCase(Locale.ROOT);
        KrajInfo matched = null;
        for (final KrajInfo k : this.service().kraje().getCountries()) {
            final String prefix = k.name().toLowerCase(Locale.ROOT) + " ";
            if (lower.startsWith(prefix) && (matched == null || k.name().length() > matched.name().length())) {
                matched = k;
            }
        }
        if (matched != null) {
            final String rest = remaining.substring(matched.name().length() + 1);
            if (rest.indexOf(' ') < 0) {
                final SuggestionsBuilder b2 = builder.createOffset(builder.getStart() + matched.name().length() + 1);
                second.accept(matched, b2);
                builder.add(b2);
            }
            return;
        }
        for (final KrajInfo k : this.service().kraje().getCountries()) {
            add(builder, k.name(), this.countryTooltip(k));
        }
    }

    private void suggestCountryThenEra(final CommandSender sender, final SuggestionsBuilder builder) {
        this.countryThen(builder, (k, b) -> {
            for (final EraDef e : this.service().cfg().eras) {
                add(b, Integer.toString(e.id()), this.m().get("suggest-era", this.views.eraTags(e, "era")));
            }
        });
    }

    private void suggestCountryThenPoints(final CommandSender sender, final SuggestionsBuilder builder) {
        this.countryThen(builder, (k, b) -> {
            for (final String v : List.of("+100", "+500", "+1000", "-100", "-500")) {
                add(b, v, this.m().get("suggest-points", Placeholder.unparsed("value", v)));
            }
        });
    }

    private void suggestCountryThenDiscovery(final CommandSender sender, final SuggestionsBuilder builder) {
        this.countryThen(builder, (k, b) -> {
            final Snapshot s = this.service().snapshot(k.id()).orElse(null);
            for (final Discovery d : this.service().tree().all()) {
                if (s == null || !s.discoverySet().contains(d.id())) {
                    add(b, d.id(), this.discoveryTooltip(d, s));
                }
            }
        });
    }

    private void suggestCountryThenOwned(final CommandSender sender, final SuggestionsBuilder builder) {
        this.countryThen(builder, (k, b) -> {
            final Snapshot s = this.service().snapshot(k.id()).orElse(null);
            if (s == null) {
                return;
            }
            for (final String id : s.discoveries()) {
                this.service().tree().get(id).ifPresent(d -> add(b, d.id(), this.discoveryTooltip(d, s)));
            }
        });
    }
}
