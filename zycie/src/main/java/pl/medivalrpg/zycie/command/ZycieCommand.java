// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.zycie.ZyciePlugin;
import pl.medivalrpg.zycie.config.Messages;
import pl.medivalrpg.zycie.service.MoodSnapshot;

/**
 * Brigadier tree of {@code /zycie} (alias {@code /miasto}) with Polish argument names and tooltips. Country names may
 * contain spaces, so "kraj + value" admin arguments are one greedy argument whose LAST word is the value.
 */
public final class ZycieCommand {

    public static final String PERM_USE = "zycie.use";
    public static final String PERM_ADMIN = "zycie.admin";

    private final ZyciePlugin plugin;

    public ZycieCommand(final ZyciePlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return this.plugin.messages();
    }

    public LiteralCommandNode<CommandSourceStack> build(final String name) {
        return Commands.literal(name)
            .requires(s -> s.getSender().hasPermission(PERM_USE))
            .executes(ctx -> this.run(ctx, this::own))
            .then(Commands.literal("pomoc")
                .executes(ctx -> this.run(ctx, s -> this.help(s, null)))
                .then(Commands.argument("strona", StringArgumentType.greedyString())
                    .suggests(this.provider(this::suggestHelpPages))
                    .executes(ctx -> this.run(ctx, s -> this.help(s, arg(ctx, "strona"))))))
            .then(Commands.literal("kraj")
                .executes(ctx -> this.run(ctx, this::own))
                .then(Commands.argument("kraj", StringArgumentType.greedyString())
                    .suggests(this.provider(this::suggestCountries))
                    .executes(ctx -> this.run(ctx, s -> this.other(s, arg(ctx, "kraj"))))))
            .then(Commands.literal("admin")
                .requires(s -> s.getSender().hasPermission(PERM_ADMIN))
                .executes(ctx -> this.run(ctx, s -> this.help(s, "admin")))
                .then(Commands.literal("swieto")
                    .executes(ctx -> this.run(ctx, s -> this.m().send(s, "admin-usage-swieto")))
                    .then(Commands.argument("kraj", StringArgumentType.greedyString())
                        .suggests(this.provider(this::suggestCountries))
                        .executes(ctx -> this.run(ctx, s -> this.festival(s, arg(ctx, "kraj"))))))
                .then(Commands.literal("nastroj")
                    .executes(ctx -> this.run(ctx, s -> this.m().send(s, "admin-usage-nastroj")))
                    .then(Commands.argument("kraj i zmiana", StringArgumentType.greedyString())
                        .suggests(this.provider(this::suggestCountryThenDelta))
                        .executes(ctx -> this.run(ctx, s -> this.adjust(s, arg(ctx, "kraj i zmiana"))))))
                .then(Commands.literal("info").executes(ctx -> this.run(ctx, this::info)))
                .then(Commands.literal("reload").executes(ctx -> this.run(ctx, this::reload)))
                .then(Commands.literal("przeladuj").executes(ctx -> this.run(ctx, this::reload))))
            .build();
    }

    private int run(final CommandContext<CommandSourceStack> ctx, final Consumer<CommandSender> handler) {
        final CommandSender sender = ctx.getSource().getSender();
        try {
            handler.accept(sender);
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Błąd komendy /zycie", e);
            this.m().send(sender, "error-internal");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String arg(final CommandContext<CommandSourceStack> ctx, final String name) {
        return StringArgumentType.getString(ctx, name);
    }

    // ------------------------------------------------------------------ handlers

    private void help(final CommandSender sender, final String page) {
        HelpBook.render(this.m(), sender.hasPermission(PERM_ADMIN), page).forEach(sender::sendMessage);
    }

    private void own(final CommandSender sender) {
        if (!(sender instanceof Player p)) {
            this.help(sender, null);
            return;
        }
        final Optional<KrajInfo> k = this.plugin.kraje().getCountryOf(p.getUniqueId());
        if (k.isEmpty()) {
            this.m().send(sender, "error-no-country");
            return;
        }
        this.show(sender, k.get());
    }

    private void other(final CommandSender sender, final String name) {
        final Optional<KrajInfo> k = this.plugin.kraje().getCountryByName(name.trim());
        if (k.isEmpty()) {
            this.m().send(sender, "error-unknown-country", Placeholder.unparsed("name", name.trim()));
            return;
        }
        this.show(sender, k.get());
    }

    private void show(final CommandSender sender, final KrajInfo k) {
        MoodSnapshot s = this.plugin.mood().snapshot(k.id());
        if (s == null) {
            this.plugin.mood().recalc(k);
            s = this.plugin.mood().snapshot(k.id());
        }
        if (s == null) {
            this.m().send(sender, "view-unknown", Placeholder.unparsed("country", k.name()));
            return;
        }
        Views.mood(this.m(), this.plugin.cfg(), k, s).forEach(sender::sendMessage);
    }

    private void festival(final CommandSender sender, final String name) {
        final Optional<KrajInfo> k = this.plugin.kraje().getCountryByName(name.trim());
        if (k.isEmpty()) {
            this.m().send(sender, "error-unknown-country", Placeholder.unparsed("name", name.trim()));
            return;
        }
        this.plugin.mood().startFestival(k.get().id(), this.m().raw("reason-admin"), true);
        this.m().send(sender, "admin-festival", Placeholder.unparsed("country", k.get().name()));
    }

    private void adjust(final CommandSender sender, final String text) {
        final String trimmed = text.trim();
        final int space = trimmed.lastIndexOf(' ');
        if (space <= 0) {
            this.m().send(sender, "admin-usage-nastroj");
            return;
        }
        final String name = trimmed.substring(0, space).trim();
        final String value = trimmed.substring(space + 1).trim();
        final Optional<KrajInfo> k = this.plugin.kraje().getCountryByName(name);
        if (k.isEmpty()) {
            this.m().send(sender, "error-unknown-country", Placeholder.unparsed("name", name));
            return;
        }
        final int delta;
        try {
            delta = Integer.parseInt(value.startsWith("+") ? value.substring(1) : value);
        } catch (final NumberFormatException e) {
            this.m().send(sender, "error-number");
            return;
        }
        final int mood = this.plugin.mood().adminAdjust(k.get().id(), Math.max(-100, Math.min(100, delta)));
        this.m().send(sender, "admin-mood", Placeholder.unparsed("country", k.get().name()),
            Placeholder.unparsed("delta", (delta >= 0 ? "+" : "") + delta), Placeholder.unparsed("mood", Integer.toString(mood)));
    }

    private void info(final CommandSender sender) {
        this.m().send(sender, "admin-info",
            Placeholder.unparsed("queue", Integer.toString(this.plugin.life().queueSize())),
            Placeholder.unparsed("tracked", Integer.toString(this.plugin.life().tracked())),
            Placeholder.unparsed("bubbles", Integer.toString(this.plugin.bubbles().activeCount())),
            Placeholder.unparsed("moves", Integer.toString(this.plugin.life().movesLastMinute())),
            Placeholder.unparsed("failed", Integer.toString(this.plugin.life().failedLastMinute())),
            Placeholder.unparsed("talks", Long.toString(this.plugin.conversations().started())),
            Placeholder.unparsed("postep", this.plugin.integrations().postepPresent() ? "tak" : "nie"),
            Placeholder.unparsed("armia", this.plugin.integrations().armiaPresent() ? "tak" : "nie"),
            Placeholder.unparsed("skarbiec", this.plugin.skarbiecPresent() ? "tak" : "nie"));
        sender.sendMessage(net.kyori.adventure.text.Component.text(this.plugin.fires().diagnostics(),net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }

    private void reload(final CommandSender sender) {
        final List<String> problems = this.plugin.reloadAll();
        this.m().send(sender, "admin-reloaded");
        if (!problems.isEmpty()) {
            this.m().send(sender, "admin-reload-problems", Placeholder.unparsed("count", Integer.toString(problems.size())));
            for (final String p : problems) {
                this.m().send(sender, "admin-problem", Placeholder.unparsed("text", p));
            }
        }
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

    private Component countryTooltip(final KrajInfo k) {
        final MoodSnapshot s = this.plugin.mood().snapshot(k.id());
        return this.m().get("suggest-country", Placeholder.unparsed("country", k.name()), Placeholder.styling("color", k.color()),
            Placeholder.unparsed("mood", s == null ? "?" : Integer.toString(s.mood())));
    }

    private void suggestCountries(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final KrajInfo k : this.plugin.kraje().getCountries()) {
            add(builder, k.name(), this.countryTooltip(k));
        }
    }

    private void suggestCountryThenDelta(final CommandSender sender, final SuggestionsBuilder builder) {
        final String remaining = builder.getRemaining();
        final String lower = remaining.toLowerCase(Locale.ROOT);
        KrajInfo matched = null;
        for (final KrajInfo k : this.plugin.kraje().getCountries()) {
            final String prefix = k.name().toLowerCase(Locale.ROOT) + " ";
            if (lower.startsWith(prefix) && (matched == null || k.name().length() > matched.name().length())) {
                matched = k;
            }
        }
        if (matched != null) {
            final String rest = remaining.substring(matched.name().length() + 1);
            if (rest.indexOf(' ') < 0) {
                final SuggestionsBuilder b2 = builder.createOffset(builder.getStart() + matched.name().length() + 1);
                for (final String v : List.of("+10", "+25", "-10", "-25")) {
                    add(b2, v, this.m().get("suggest-delta", Placeholder.unparsed("value", v)));
                }
                builder.add(b2);
            }
            return;
        }
        this.suggestCountries(sender, builder);
    }
}
