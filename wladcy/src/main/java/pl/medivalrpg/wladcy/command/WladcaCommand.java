// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.wladcy.config.Messages;
import pl.medivalrpg.wladcy.cost.BudgetTracker;
import pl.medivalrpg.wladcy.game.ConversationManager;

/** {@code /wladca}: conversations with NPC rulers, tribute clicks, cost report and the console-only API key. */
public final class WladcaCommand {

    public static final String PERM_USE = "wladcy.use";
    public static final String PERM_ADMIN = "wladcy.admin";

    static final HelpBook HELP = new HelpBook("help-wladca", "/wladca",
        List.of("rozmowa", "admin"),
        List.of(
            new HelpBook.Entry("rozmowa", "rozmowa", "/wladca rozmowa", "/wladca rozmowa", false),
            new HelpBook.Entry("koniec", "rozmowa", "/wladca koniec", "/wladca koniec", false),
            new HelpBook.Entry("pomoc", "rozmowa", "/wladca pomoc [strona]", "/wladca pomoc ", false),
            new HelpBook.Entry("koszt", "admin", "/wladca koszt", "/wladca koszt", true),
            new HelpBook.Entry("klucz", "admin", "/wladca klucz <klucz> (konsola)", "/wladca klucz ", true),
            new HelpBook.Entry("przeladuj", "admin", "/wladca przeladuj", "/wladca przeladuj", true)));

    /** Actions of the plugin used by the command. */
    public interface Actions {
        ConversationManager conversations();

        BudgetTracker budget();

        void setApiKey(String key);

        void reload();

        boolean hasApiKey();

        String model();
    }

    private final Actions actions;
    private final Supplier<Messages> messages;

    public WladcaCommand(final Actions actions, final Supplier<Messages> messages) {
        this.actions = actions;
        this.messages = messages;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("wladca")
            .requires(s -> s.getSender().hasPermission(PERM_USE))
            .executes(ctx -> this.help(ctx, null))
            .then(Commands.literal("pomoc")
                .executes(ctx -> this.help(ctx, null))
                .then(Commands.argument("strona", StringArgumentType.greedyString())
                    .suggests((ctx, b) -> {
                        final boolean admin = ctx.getSource().getSender().hasPermission(PERM_ADMIN);
                        final List<String> pages = HELP.pages(admin);
                        for (int i = 0; i < pages.size(); i++) {
                            b.suggest(Integer.toString(i + 1), MessageComponentSerializer.message().serialize(
                                this.messages.get().parse(HELP.categoryName(this.messages.get(), pages.get(i)))));
                        }
                        return b.buildFuture();
                    })
                    .executes(ctx -> this.help(ctx, StringArgumentType.getString(ctx, "strona")))))
            .then(Commands.literal("rozmowa").executes(ctx -> this.player(ctx, p -> this.actions.conversations().begin(p))))
            .then(Commands.literal("koniec").executes(ctx -> this.player(ctx, p -> this.actions.conversations().endByPlayer(p))))
            .then(Commands.literal("trybut")
                .then(Commands.literal("zaplac")
                    .then(Commands.argument("kod", StringArgumentType.word())
                        .executes(ctx -> this.player(ctx, p -> this.actions.conversations().tributeClick(p, StringArgumentType.getString(ctx, "kod"), true)))))
                .then(Commands.literal("odmow")
                    .then(Commands.argument("kod", StringArgumentType.word())
                        .executes(ctx -> this.player(ctx, p -> this.actions.conversations().tributeClick(p, StringArgumentType.getString(ctx, "kod"), false))))))
            .then(Commands.literal("koszt")
                .requires(s -> s.getSender().hasPermission(PERM_ADMIN))
                .executes(this::cost))
            .then(Commands.literal("klucz")
                .requires(s -> s.getSender() instanceof ConsoleCommandSender)
                .then(Commands.argument("klucz", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        final String key = normalizeKey(StringArgumentType.getString(ctx, "klucz"));
                        this.actions.setApiKey(key);
                        this.messages.get().send(ctx.getSource().getSender(), key.isEmpty() ? "key-cleared" : "key-set");
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.literal("przeladuj")
                .requires(s -> s.getSender().hasPermission(PERM_ADMIN))
                .executes(ctx -> {
                    this.actions.reload();
                    this.messages.get().send(ctx.getSource().getSender(), "reloaded",
                        Messages.p("model", this.actions.model()), Messages.p("key", this.messages.get().plain(this.actions.hasApiKey() ? "key-present" : "key-missing")));
                    return Command.SINGLE_SUCCESS;
                }))
            .build();
    }

    private int cost(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender s = ctx.getSource().getSender();
        final BudgetTracker.Report r = this.actions.budget().report();
        final Messages m = this.messages.get();
        final long totalIn = r.dayInput() + r.dayCacheRead() + r.dayCacheWrite();
        final String hit = totalIn == 0 ? "—" : String.format(Locale.ROOT, "%.0f%%", 100.0 * r.dayCacheRead() / totalIn);
        m.send(s, "cost-report",
            Messages.p("day", r.day()), Messages.p("day_usd", usd(r.dayUsd())), Messages.p("daily_budget", r.dailyBudget() > 0 ? usd(r.dailyBudget()) : "∞"),
            Messages.p("day_requests", r.dayRequests()), Messages.p("month", r.month()), Messages.p("month_usd", usd(r.monthUsd())),
            Messages.p("monthly_budget", r.monthlyBudget() > 0 ? usd(r.monthlyBudget()) : "∞"), Messages.p("month_requests", r.monthRequests()),
            Messages.p("total_usd", usd(r.totalUsd())), Messages.p("input", r.dayInput()), Messages.p("output", r.dayOutput()),
            Messages.p("cache_read", r.dayCacheRead()), Messages.p("cache_write", r.dayCacheWrite()), Messages.p("hit", hit),
            Messages.p("model", this.actions.model()), Messages.p("conversations", this.actions.conversations().count()),
            Messages.p("avg", r.dayRequests() == 0 ? "—" : usd(r.dayUsd() / r.dayRequests())));
        return Command.SINGLE_SUCCESS;
    }

    /** Strips surrounding quotes; {@code -} clears the key. */
    static String normalizeKey(final String raw) {
        String k = raw == null ? "" : raw.trim();
        if (k.length() >= 2 && (k.startsWith("\"") && k.endsWith("\"") || k.startsWith("'") && k.endsWith("'"))) {
            k = k.substring(1, k.length() - 1).trim();
        }
        return "-".equals(k) ? "" : k;
    }

    static String usd(final double v) {
        return String.format(Locale.ROOT, "$%.4f", v);
    }

    private int help(final CommandContext<CommandSourceStack> ctx, final String page) {
        final CommandSender s = ctx.getSource().getSender();
        for (final Component line : HELP.render(this.messages.get(), s.hasPermission(PERM_ADMIN), page)) {
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
