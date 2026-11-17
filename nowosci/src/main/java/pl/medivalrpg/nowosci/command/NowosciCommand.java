// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.nowosci.NowosciPlugin;
import pl.medivalrpg.nowosci.config.Messages;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.model.Category;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.Entry;
import pl.medivalrpg.nowosci.render.EntryFormat.Medium;

/**
 * Brigadier tree of {@code /nowosci} (aliases {@code /zmiany}, {@code /changelog}), registered through
 * {@code LifecycleEvents.COMMANDS}. Polish argument names and tooltips; admin subcommands are hidden from the
 * client without {@code nowosci.admin}. Suggestion providers only read immutable snapshots (changelog, config),
 * so they are safe on any thread.
 */
public final class NowosciCommand {

    public static final String PERM_USE = "nowosci.use";
    public static final String PERM_ADMIN = "nowosci.admin";
    static final int MAX_TITLE_LENGTH = 60;

    private final NowosciPlugin plugin;

    public NowosciCommand(final NowosciPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        final Predicate<CommandSourceStack> admin = s -> s.getSender().hasPermission(PERM_ADMIN);
        return Commands.literal("nowosci")
            .requires(s -> s.getSender().hasPermission(PERM_USE))
            .executes(this.run(this::openDefault))
            .then(Commands.literal("ksiazka").executes(this.run(this::openBook)))
            .then(Commands.literal("czat")
                .executes(this.run(s -> this.chat(s, 1)))
                .then(Commands.argument("strona", IntegerArgumentType.integer(1))
                    .suggests(this::suggestChatPages)
                    .executes(ctx -> this.exec(ctx, s -> this.chat(s, IntegerArgumentType.getInteger(ctx, "strona"))))))
            .then(Commands.literal("przeczytane").executes(this.run(this::markRead)))
            .then(Commands.literal("pomoc").executes(this.run(this::help)))
            // admin
            .then(Commands.literal("dodaj")
                .requires(admin)
                .executes(this.run(this::help))
                .then(Commands.argument("kategoria", StringArgumentType.word())
                    .suggests(this::suggestCategories)
                    .executes(this.run(this::help))
                    .then(Commands.argument("tytuł", StringArgumentType.greedyString())
                        .executes(ctx -> this.exec(ctx, s -> this.add(s,
                            StringArgumentType.getString(ctx, "kategoria"), StringArgumentType.getString(ctx, "tytuł")))))))
            .then(Commands.literal("punkt")
                .requires(admin)
                .executes(this.run(this::help))
                .then(Commands.argument("tekst", StringArgumentType.greedyString())
                    .executes(ctx -> this.exec(ctx, s -> this.addPoint(s, StringArgumentType.getString(ctx, "tekst"))))))
            .then(Commands.literal("usun")
                .requires(admin)
                .executes(this.run(this::help))
                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                    .suggests(this::suggestIds)
                    .executes(ctx -> this.exec(ctx, s -> this.remove(s, IntegerArgumentType.getInteger(ctx, "id"))))))
            .then(Commands.literal("oglos").requires(admin).executes(this.run(this::announce)))
            .then(Commands.literal("ogłoś").requires(admin).executes(this.run(this::announce)))
            .then(Commands.literal("reload").requires(admin).executes(this.run(this::reload)))
            .build();
    }

    // ------------------------------------------------------------------ plumbing

    private interface Action {
        void accept(CommandSender sender);
    }

    private Command<CommandSourceStack> run(final Action action) {
        return ctx -> this.exec(ctx, action);
    }

    private int exec(final CommandContext<CommandSourceStack> ctx, final Action action) {
        action.accept(ctx.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }

    private Messages m() {
        return this.plugin.messages();
    }

    private static void send(final CommandSender sender, final List<Component> lines) {
        lines.forEach(sender::sendMessage);
    }

    // ------------------------------------------------------------------ players

    private void openDefault(final CommandSender sender) {
        if (sender instanceof Player) {
            this.openBook(sender);
        } else {
            this.chat(sender, 1);
        }
    }

    private void openBook(final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            this.m().send(sender, "players-only");
            return;
        }
        this.plugin.news().openBook(player);
    }

    private void chat(final CommandSender sender, final int page) {
        send(sender, this.plugin.chat().chatPage(this.plugin.changelog(), page));
        if (sender instanceof Player player) {
            this.plugin.news().openedNews(player);
        }
    }

    private void markRead(final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            this.m().send(sender, "players-only");
            return;
        }
        this.plugin.news().markRead(player);
        this.m().send(sender, "marked-read");
    }

    private void help(final CommandSender sender) {
        send(sender, this.plugin.chat().help(sender.hasPermission(PERM_ADMIN)));
    }

    // ------------------------------------------------------------------ admin

    private void add(final CommandSender sender, final String categoryArg, final String titleArg) {
        final NowosciConfig cfg = this.plugin.cfg();
        final String key = categoryArg.toLowerCase(Locale.ROOT);
        if (!cfg.categories.containsKey(key)) {
            this.m().send(sender, "admin-unknown-category",
                Placeholder.unparsed("category", categoryArg),
                Placeholder.unparsed("categories", String.join(", ", cfg.categories.keySet())));
            return;
        }
        final String title = titleArg.strip();
        if (title.isEmpty()) {
            this.m().send(sender, "admin-empty-text");
            return;
        }
        final Changelog changelog = this.plugin.changelog();
        final Entry entry = new Entry(changelog.nextId(), LocalDate.now(),
            title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title, key, List.of());
        if (this.plugin.updateChangelog(sender, changelog.with(entry))) {
            this.m().send(sender, "admin-added", this.plugin.format().tags(entry, Medium.CHAT));
        }
    }

    private void addPoint(final CommandSender sender, final String text) {
        final String point = text.strip();
        if (point.isEmpty()) {
            this.m().send(sender, "admin-empty-text");
            return;
        }
        final Changelog changelog = this.plugin.changelog();
        final Optional<Entry> newest = changelog.newest();
        if (newest.isEmpty()) {
            this.m().send(sender, "admin-no-entries");
            return;
        }
        final Entry updated = newest.get().withPoint(point);
        if (this.plugin.updateChangelog(sender, changelog.with(updated))) {
            this.m().send(sender, "admin-point-added",
                Placeholder.unparsed("id", Integer.toString(updated.id())),
                Placeholder.unparsed("count", Integer.toString(updated.points().size())),
                Placeholder.component("point", this.plugin.format().point(point, Medium.CHAT)));
        }
    }

    private void remove(final CommandSender sender, final int id) {
        final Changelog changelog = this.plugin.changelog();
        final Optional<Entry> entry = changelog.byId(id);
        if (entry.isEmpty()) {
            this.m().send(sender, "admin-not-found", Placeholder.unparsed("id", Integer.toString(id)));
            return;
        }
        if (this.plugin.updateChangelog(sender, changelog.without(id))) {
            this.m().send(sender, "admin-removed", this.plugin.format().tags(entry.get(), Medium.CHAT));
        }
    }

    private void announce(final CommandSender sender) {
        final Optional<Entry> newest = this.plugin.changelog().newest();
        if (newest.isEmpty()) {
            this.m().send(sender, "admin-no-entries");
            return;
        }
        final int count = this.plugin.news().announce(newest.get());
        this.plugin.discord().publish(newest.get(), this.plugin.cfg());
        this.m().send(sender, "admin-announced",
            Placeholder.unparsed("id", Integer.toString(newest.get().id())),
            Placeholder.unparsed("count", Integer.toString(count)),
            Placeholder.unparsed("word", NowosciConfig.plural(this.plugin.cfg().pluralPlayers, count)));
    }

    private void reload(final CommandSender sender) {
        final List<String> warnings = this.plugin.reloadAll();
        final int size = this.plugin.changelog().size();
        this.m().send(sender, "admin-reloaded",
            Placeholder.unparsed("count", Integer.toString(size)),
            Placeholder.unparsed("word", NowosciConfig.plural(this.plugin.cfg().pluralEntries, size)));
        if (!warnings.isEmpty()) {
            this.m().send(sender, "admin-reload-warnings", Placeholder.unparsed("count", Integer.toString(warnings.size())));
        }
    }

    // ------------------------------------------------------------------ suggestions

    private static void suggest(final SuggestionsBuilder builder, final String text, final Component tooltip) {
        if (text.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
            builder.suggest(text, MessageComponentSerializer.message().serialize(tooltip));
        }
    }

    private CompletableFuture<Suggestions> suggestCategories(
            final CommandContext<CommandSourceStack> ctx, final SuggestionsBuilder builder) {
        for (final Category c : this.plugin.cfg().categories.values()) {
            suggest(builder, c.key(), this.m().get("suggest-category",
                Placeholder.unparsed("icon", c.icon()),
                Placeholder.unparsed("category", c.name()),
                Placeholder.styling("cat_color", c.chatColor())));
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestIds(
            final CommandContext<CommandSourceStack> ctx, final SuggestionsBuilder builder) {
        for (final Entry e : this.plugin.changelog().entries()) {
            suggest(builder, Integer.toString(e.id()), this.m().get("suggest-entry", this.plugin.format().tags(e, Medium.CHAT)));
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestChatPages(
            final CommandContext<CommandSourceStack> ctx, final SuggestionsBuilder builder) {
        final int pages = this.plugin.chat().chatPages(this.plugin.changelog());
        for (int i = 1; i <= pages; i++) {
            suggest(builder, Integer.toString(i), this.m().get("suggest-chat-page",
                Placeholder.unparsed("page", Integer.toString(i)), Placeholder.unparsed("pages", Integer.toString(pages))));
        }
        return builder.buildFuture();
    }
}
