// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.wladcy.config.Messages;
import pl.medivalrpg.wladcy.quest.QuestService;
import pl.medivalrpg.wladcy.quest.RepTier;
import pl.medivalrpg.wladcy.quest.ReputationBook;

/**
 * {@code /zlecenia} (quests of NPC rulers, without the LLM) and {@code /reputacja} (reputation with countries),
 * with their admin subcommands and one interactive help book.
 */
public final class ZleceniaCommand {

    static final HelpBook HELP = new HelpBook("help-zlecenia", "/zlecenia",
        List.of("zlecenia", "reputacja", "admin"),
        List.of(
            new HelpBook.Entry("lista", "zlecenia", "/zlecenia", "/zlecenia", false),
            new HelpBook.Entry("kraj", "zlecenia", "/zlecenia kraj <kraj>", "/zlecenia kraj ", false),
            new HelpBook.Entry("przyjmij", "zlecenia", "/zlecenia przyjmij <numer>", "/zlecenia przyjmij ", false),
            new HelpBook.Entry("oddaj", "zlecenia", "/zlecenia oddaj [numer]", "/zlecenia oddaj", false),
            new HelpBook.Entry("porzuc", "zlecenia", "/zlecenia porzuc [numer]", "/zlecenia porzuc ", false),
            new HelpBook.Entry("odrzuc", "zlecenia", "/zlecenia odrzuc <numer>", "/zlecenia odrzuc ", false),
            new HelpBook.Entry("pomoc", "zlecenia", "/zlecenia pomoc [strona]", "/zlecenia pomoc ", false),
            new HelpBook.Entry("reputacja", "reputacja", "/reputacja [kraj]", "/reputacja", false),
            new HelpBook.Entry("rozmowa", "reputacja", "/wladca rozmowa", "/wladca rozmowa", false),
            new HelpBook.Entry("odswiez", "admin", "/zlecenia admin odswiez [kraj]", "/zlecenia admin odswiez", true),
            new HelpBook.Entry("przeladuj", "admin", "/zlecenia admin przeladuj", "/zlecenia admin przeladuj", true),
            new HelpBook.Entry("rep-pokaz", "admin", "/reputacja admin pokaz <gracz> [kraj]", "/reputacja admin pokaz ", true),
            new HelpBook.Entry("rep-daj", "admin", "/reputacja admin daj <gracz> <ilość> <kraj>", "/reputacja admin daj ", true),
            new HelpBook.Entry("rep-ustaw", "admin", "/reputacja admin ustaw <gracz> <wartość> <kraj>", "/reputacja admin ustaw ", true),
            new HelpBook.Entry("rep-resetuj", "admin", "/reputacja admin resetuj <gracz> [kraj]", "/reputacja admin resetuj ", true)));

    private final Supplier<QuestService> quests;
    private final Supplier<Messages> messages;
    private final Runnable reload;

    public ZleceniaCommand(final Supplier<QuestService> quests, final Supplier<Messages> messages, final Runnable reload) {
        this.quests = quests;
        this.messages = messages;
        this.reload = reload;
    }

    // ------------------------------------------------------------------ /zlecenia

    public LiteralCommandNode<CommandSourceStack> buildQuests() {
        return Commands.literal("zlecenia")
            .requires(s -> s.getSender().hasPermission(WladcaCommand.PERM_USE))
            .executes(ctx -> this.player(ctx, p -> this.quests.get().list(p)))
            .then(this.helpNode())
            .then(Commands.literal("kraj")
                .then(Commands.argument("kraj", StringArgumentType.greedyString())
                    .suggests(this.countries())
                    .executes(ctx -> this.player(ctx, p -> {
                        final String name = StringArgumentType.getString(ctx, "kraj");
                        final Optional<KrajInfo> k = this.quests.get().findCountry(name, true);
                        if (k.isEmpty()) {
                            this.messages.get().send(p, "quest-unknown-country", Messages.p("name", name));
                        } else {
                            this.quests.get().showBoard(p, k.get());
                        }
                    }))))
            .then(Commands.literal("przyjmij")
                .then(Commands.argument("numer", StringArgumentType.word())
                    .suggests(this.ids(true))
                    .executes(ctx -> this.player(ctx, p -> this.quests.get().accept(p, StringArgumentType.getString(ctx, "numer"))))))
            .then(Commands.literal("odrzuc")
                .then(Commands.argument("numer", StringArgumentType.word())
                    .suggests(this.ids(true))
                    .executes(ctx -> this.player(ctx, p -> this.quests.get().refuse(p, StringArgumentType.getString(ctx, "numer"))))))
            .then(Commands.literal("oddaj")
                .executes(ctx -> this.player(ctx, p -> this.quests.get().turnIn(p, null)))
                .then(Commands.argument("numer", StringArgumentType.word())
                    .suggests(this.ids(false))
                    .executes(ctx -> this.player(ctx, p -> this.quests.get().turnIn(p, StringArgumentType.getString(ctx, "numer"))))))
            .then(Commands.literal("porzuc")
                .executes(ctx -> this.player(ctx, p -> this.quests.get().abandon(p, null)))
                .then(Commands.argument("numer", StringArgumentType.word())
                    .suggests(this.ids(false))
                    .executes(ctx -> this.player(ctx, p -> this.quests.get().abandon(p, StringArgumentType.getString(ctx, "numer"))))))
            .then(Commands.literal("admin")
                .requires(s -> s.getSender().hasPermission(WladcaCommand.PERM_ADMIN))
                .then(Commands.literal("odswiez")
                    .executes(ctx -> this.refresh(ctx.getSource().getSender(), null))
                    .then(Commands.argument("kraj", StringArgumentType.greedyString())
                        .suggests(this.countries())
                        .executes(ctx -> this.refresh(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "kraj")))))
                .then(Commands.literal("przeladuj")
                    .executes(ctx -> {
                        this.reload.run();
                        this.messages.get().send(ctx.getSource().getSender(), "quest-reloaded");
                        return Command.SINGLE_SUCCESS;
                    })))
            .build();
    }

    private int refresh(final CommandSender s, final String name) {
        KrajInfo k = null;
        if (name != null) {
            final Optional<KrajInfo> found = this.quests.get().findCountry(name, true);
            if (found.isEmpty()) {
                this.messages.get().send(s, "quest-unknown-country", Messages.p("name", name));
                return Command.SINGLE_SUCCESS;
            }
            k = found.get();
        }
        final int n = this.quests.get().refreshBoards(k);
        this.messages.get().send(s, "quest-boards-refreshed", Messages.p("count", n));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ /reputacja

    public LiteralCommandNode<CommandSourceStack> buildReputation() {
        return Commands.literal("reputacja")
            .requires(s -> s.getSender().hasPermission(WladcaCommand.PERM_USE))
            .executes(ctx -> this.player(ctx, p -> this.quests.get().showReputation(p, p.getUniqueId(), p.getName(), null)))
            .then(Commands.literal("pomoc")
                .executes(ctx -> this.help(ctx, "2")))
            .then(Commands.literal("admin")
                .requires(s -> s.getSender().hasPermission(WladcaCommand.PERM_ADMIN))
                .then(Commands.literal("pokaz")
                    .then(Commands.argument("gracz", StringArgumentType.word())
                        .suggests(this.players())
                        .executes(ctx -> this.adminShow(ctx, null))
                        .then(Commands.argument("kraj", StringArgumentType.greedyString())
                            .suggests(this.countries())
                            .executes(ctx -> this.adminShow(ctx, StringArgumentType.getString(ctx, "kraj"))))))
                .then(Commands.literal("daj")
                    .then(Commands.argument("gracz", StringArgumentType.word())
                        .suggests(this.players())
                        .then(Commands.argument("ilosc", IntegerArgumentType.integer(-200, 200))
                            .then(Commands.argument("kraj", StringArgumentType.greedyString())
                                .suggests(this.countries())
                                .executes(ctx -> this.adminChange(ctx, true))))))
                .then(Commands.literal("ustaw")
                    .then(Commands.argument("gracz", StringArgumentType.word())
                        .suggests(this.players())
                        .then(Commands.argument("wartosc", IntegerArgumentType.integer(RepTier.MIN, RepTier.MAX))
                            .then(Commands.argument("kraj", StringArgumentType.greedyString())
                                .suggests(this.countries())
                                .executes(ctx -> this.adminChange(ctx, false))))))
                .then(Commands.literal("resetuj")
                    .then(Commands.argument("gracz", StringArgumentType.word())
                        .suggests(this.players())
                        .executes(ctx -> this.adminReset(ctx, null))
                        .then(Commands.argument("kraj", StringArgumentType.greedyString())
                            .suggests(this.countries())
                            .executes(ctx -> this.adminReset(ctx, StringArgumentType.getString(ctx, "kraj")))))))
            .then(Commands.argument("kraj", StringArgumentType.greedyString())
                .suggests(this.countries())
                .executes(ctx -> this.player(ctx, p -> {
                    final String name = StringArgumentType.getString(ctx, "kraj");
                    final Optional<KrajInfo> k = this.quests.get().findCountry(name, false);
                    if (k.isEmpty()) {
                        this.messages.get().send(p, "quest-unknown-country", Messages.p("name", name));
                    } else {
                        this.quests.get().showReputation(p, p.getUniqueId(), p.getName(), k.get());
                    }
                })))
            .build();
    }

    /** Player by exact online name, else from the server's profile cache (offline mode UUIDs). */
    private static Optional<OfflinePlayer> resolve(final String name) {
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online);
        }
        return Optional.ofNullable(Bukkit.getOfflinePlayerIfCached(name));
    }

    private int adminShow(final CommandContext<CommandSourceStack> ctx, final String country) {
        final CommandSender s = ctx.getSource().getSender();
        final String name = StringArgumentType.getString(ctx, "gracz");
        final Optional<OfflinePlayer> op = resolve(name);
        if (op.isEmpty()) {
            this.messages.get().send(s, "rep-unknown-player", Messages.p("name", name));
            return Command.SINGLE_SUCCESS;
        }
        KrajInfo k = null;
        if (country != null) {
            final Optional<KrajInfo> found = this.quests.get().findCountry(country, false);
            if (found.isEmpty()) {
                this.messages.get().send(s, "quest-unknown-country", Messages.p("name", country));
                return Command.SINGLE_SUCCESS;
            }
            k = found.get();
        }
        this.quests.get().showReputation(s, op.get().getUniqueId(), op.get().getName() == null ? name : op.get().getName(), k);
        return Command.SINGLE_SUCCESS;
    }

    private int adminChange(final CommandContext<CommandSourceStack> ctx, final boolean add) {
        final CommandSender s = ctx.getSource().getSender();
        final String name = StringArgumentType.getString(ctx, "gracz");
        final String country = StringArgumentType.getString(ctx, "kraj");
        final Optional<OfflinePlayer> op = resolve(name);
        if (op.isEmpty()) {
            this.messages.get().send(s, "rep-unknown-player", Messages.p("name", name));
            return Command.SINGLE_SUCCESS;
        }
        final Optional<KrajInfo> k = this.quests.get().findCountry(country, false);
        if (k.isEmpty()) {
            this.messages.get().send(s, "quest-unknown-country", Messages.p("name", country));
            return Command.SINGLE_SUCCESS;
        }
        final UUID id = op.get().getUniqueId();
        final String shown = op.get().getName() == null ? name : op.get().getName();
        final ReputationBook book = this.quests.get().reputation();
        final ReputationBook.Change ch = add
            ? book.add(k.get().id(), id, shown, IntegerArgumentType.getInteger(ctx, "ilosc"), 0)
            : book.set(k.get().id(), id, shown, IntegerArgumentType.getInteger(ctx, "wartosc"));
        this.messages.get().send(s, "rep-admin-set", Messages.p("player", shown), Messages.c("country", Component.text(k.get().name(), k.get().color())),
            Messages.p("value", ch.value()), Messages.c("tier", this.quests.get().tierLabel(ch.after())));
        return Command.SINGLE_SUCCESS;
    }

    private int adminReset(final CommandContext<CommandSourceStack> ctx, final String country) {
        final CommandSender s = ctx.getSource().getSender();
        final String name = StringArgumentType.getString(ctx, "gracz");
        final Optional<OfflinePlayer> op = resolve(name);
        if (op.isEmpty()) {
            this.messages.get().send(s, "rep-unknown-player", Messages.p("name", name));
            return Command.SINGLE_SUCCESS;
        }
        UUID countryId = null;
        if (country != null) {
            final Optional<KrajInfo> k = this.quests.get().findCountry(country, false);
            if (k.isEmpty()) {
                this.messages.get().send(s, "quest-unknown-country", Messages.p("name", country));
                return Command.SINGLE_SUCCESS;
            }
            countryId = k.get().id();
        }
        final int n = this.quests.get().reputation().reset(op.get().getUniqueId(), countryId);
        this.messages.get().send(s, "rep-admin-reset", Messages.p("player", op.get().getName() == null ? name : op.get().getName()), Messages.p("count", n));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ shared

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> helpNode() {
        return Commands.literal("pomoc")
            .executes(ctx -> this.help(ctx, null))
            .then(Commands.argument("strona", StringArgumentType.greedyString())
                .suggests((ctx, b) -> {
                    final boolean admin = ctx.getSource().getSender().hasPermission(WladcaCommand.PERM_ADMIN);
                    final List<String> pages = HELP.pages(admin);
                    for (int i = 0; i < pages.size(); i++) {
                        b.suggest(Integer.toString(i + 1), MessageComponentSerializer.message().serialize(
                            this.messages.get().parse(HELP.categoryName(this.messages.get(), pages.get(i)))));
                    }
                    return b.buildFuture();
                })
                .executes(ctx -> this.help(ctx, StringArgumentType.getString(ctx, "strona"))));
    }

    private SuggestionProvider<CommandSourceStack> countries() {
        return (ctx, b) -> {
            final String typed = b.getRemaining().toLowerCase(Locale.ROOT);
            for (final String n : this.quests.get().npcNames()) {
                if (n.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    b.suggest(n);
                }
            }
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> ids(final boolean offers) {
        return (ctx, b) -> {
            if (ctx.getSource().getSender() instanceof Player p) {
                final String typed = b.getRemaining().toLowerCase(Locale.ROOT);
                for (final String id : this.quests.get().suggestIds(p.getUniqueId(), offers)) {
                    if (id.startsWith(typed)) {
                        b.suggest(id);
                    }
                }
            }
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> players() {
        return (ctx, b) -> {
            final String typed = b.getRemaining().toLowerCase(Locale.ROOT);
            for (final Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    b.suggest(p.getName());
                }
            }
            return b.buildFuture();
        };
    }

    private int help(final CommandContext<CommandSourceStack> ctx, final String page) {
        final CommandSender s = ctx.getSource().getSender();
        for (final Component line : HELP.render(this.messages.get(), s.hasPermission(WladcaCommand.PERM_ADMIN), page)) {
            s.sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int player(final CommandContext<CommandSourceStack> ctx, final Consumer<Player> action) {
        if (ctx.getSource().getSender() instanceof Player p) {
            action.accept(p);
        } else {
            this.messages.get().send(ctx.getSource().getSender(), "players-only");
        }
        return Command.SINGLE_SUCCESS;
    }
}
