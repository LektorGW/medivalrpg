// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.straznik.StraznikPlugin;
import pl.medivalrpg.straznik.account.Account;
import pl.medivalrpg.straznik.config.Messages;
import pl.medivalrpg.straznik.nlogin.ImportReport;
import pl.medivalrpg.straznik.nlogin.NLoginExporter;

/**
 * {@code /straznik} (alias {@code /auth}) for administrators ({@code straznik.admin}): help, status, account info
 * WITHOUT the hash, password set, unregister, unlock, nLogin import/export and reload. Password and file operations
 * can be limited to the console ({@code admin.hasla-tylko-z-konsoli}).
 */
public final class AdminCommand {

    public static final String PERM = "straznik.admin";

    /** Help entries: syntax, text inserted on click, description (hover). */
    record Entry(String syntax, String insert, String description) {
    }

    static final List<Entry> HELP = List.of(
        new Entry("/straznik stan", "/straznik stan", "Liczba kont, oczekujących na logowanie i zalogowanych, parametry hasła."),
        new Entry("/straznik info <gracz>", "/straznik info ", "Konto gracza bez hasła: data rejestracji, ostatnie logowanie, źródło, adres."),
        new Entry("/straznik haslo <gracz> <nowe>", "/straznik haslo ", "Ustawia nowe hasło (zwykle tylko z konsoli). Kończy sesje i sesje panelu."),
        new Entry("/straznik wyrejestruj <gracz>", "/straznik wyrejestruj ", "Usuwa konto; gracz online zostaje wyrzucony i zarejestruje się ponownie."),
        new Entry("/straznik odblokuj <gracz|ip>", "/straznik odblokuj ", "Zdejmuje blokadę po zbyt wielu złych hasłach."),
        new Entry("/straznik import nlogin [potwierdz]", "/straznik import nlogin", "Import kont z nLogin (konsola). Bez 'potwierdz' tylko raport."),
        new Entry("/straznik eksport nlogin", "/straznik eksport nlogin", "Kopia bazy nLogin z bieżącymi kontami — na wypadek powrotu do nLogin (konsola)."),
        new Entry("/straznik przeladuj", "/straznik przeladuj", "Wczytuje config.yml i listę słabych haseł.")
    );

    private final StraznikPlugin plugin;

    public AdminCommand(final StraznikPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return this.plugin.messages();
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        final Predicate<CommandSourceStack> admin = s -> s.getSender().hasPermission(PERM);
        return Commands.literal("straznik")
            .requires(admin)
            .executes(this.run(this::help))
            .then(Commands.literal("pomoc").executes(this.run(this::help)))
            .then(Commands.literal("stan").executes(this.run(this::status)))
            .then(Commands.literal("info")
                .then(Commands.argument("gracz", StringArgumentType.word()).suggests(this::nicks)
                    .executes(ctx -> this.exec(ctx, s -> this.info(s, StringArgumentType.getString(ctx, "gracz"))))))
            .then(Commands.literal("haslo")
                .then(Commands.argument("gracz", StringArgumentType.word()).suggests(this::nicks)
                    .then(Commands.argument("nowe_hasło", StringArgumentType.greedyString())
                        .executes(ctx -> this.exec(ctx, s -> this.setPassword(s, StringArgumentType.getString(ctx, "gracz"),
                            AuthCommands.tokens(StringArgumentType.getString(ctx, "nowe_hasło"))))))))
            .then(Commands.literal("wyrejestruj")
                .then(Commands.argument("gracz", StringArgumentType.word()).suggests(this::nicks)
                    .executes(ctx -> this.exec(ctx, s -> this.unregister(s, StringArgumentType.getString(ctx, "gracz"))))))
            .then(Commands.literal("odblokuj")
                .then(Commands.argument("gracz_lub_ip", StringArgumentType.greedyString())
                    .executes(ctx -> this.exec(ctx, s -> this.unlock(s, StringArgumentType.getString(ctx, "gracz_lub_ip").strip())))))
            .then(Commands.literal("import")
                .then(Commands.literal("nlogin")
                    .executes(this.run(s -> this.importNLogin(s, false)))
                    .then(Commands.literal("potwierdz").executes(this.run(s -> this.importNLogin(s, true))))))
            .then(Commands.literal("eksport")
                .then(Commands.literal("nlogin").executes(this.run(this::exportNLogin))))
            .then(Commands.literal("przeladuj").executes(this.run(this::reload)))
            .build();
    }

    private Command<CommandSourceStack> run(final Consumer<CommandSender> action) {
        return ctx -> this.exec(ctx, action);
    }

    private int exec(final CommandContext<CommandSourceStack> ctx, final Consumer<CommandSender> action) {
        action.accept(ctx.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }

    private CompletableFuture<Suggestions> nicks(final CommandContext<CommandSourceStack> ctx, final SuggestionsBuilder b) {
        final String prefix = b.getRemainingLowerCase();
        for (final Account a : this.plugin.store().all()) {
            if (a.nick().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                b.suggest(a.nick());
            }
        }
        return b.buildFuture();
    }

    private boolean consoleOnly(final CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }
        sender.sendMessage(this.m().get("tylko-konsola"));
        return false;
    }

    private void help(final CommandSender sender) {
        sender.sendMessage(this.m().get("admin-pomoc-naglowek"));
        for (final Entry e : HELP) {
            sender.sendMessage(Component.text(" " + e.syntax(), NamedTextColor.GOLD)
                .append(Component.text(" — " + e.description(), NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(Component.text(e.description() + "\nKliknij, aby wpisać.")))
                .clickEvent(ClickEvent.suggestCommand(e.insert())));
        }
    }

    private void status(final CommandSender sender) {
        for (final String line : this.plugin.statusLines()) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
    }

    private void info(final CommandSender sender, final String nick) {
        final Optional<Account> a = this.plugin.store().find(nick);
        if (a.isEmpty()) {
            sender.sendMessage(this.m().get("admin-brak-konta", Messages.text("nick", nick)));
            return;
        }
        final Account acc = a.get();
        final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        final Player online = Bukkit.getPlayerExact(acc.nick());
        sender.sendMessage(this.m().get("admin-info",
            Messages.text("nick", acc.nick()),
            Messages.text("uuid", acc.uuid().toString()),
            Messages.text("zrodlo", acc.source()),
            Messages.text("schemat", Account.SCHEME_NLOGIN.equals(acc.scheme()) ? "z nLogin (czeka na przehaszowanie)" : "Argon2id (Strażnik)"),
            Messages.text("utworzono", acc.created() > 0 ? f.format(new Date(acc.created())) : "-"),
            Messages.text("logowanie", acc.lastLogin() > 0 ? f.format(new Date(acc.lastLogin())) : "-"),
            Messages.text("zmiana", acc.passwordChanged() > 0 ? f.format(new Date(acc.passwordChanged())) : "-"),
            Messages.text("ip", acc.lastIp() == null ? "-" : acc.lastIp()),
            Messages.text("stan", online == null ? "offline" : this.plugin.auth().isAuthenticated(online.getUniqueId())
                ? "online, zalogowany" : "online, czeka na logowanie")));
    }

    private void setPassword(final CommandSender sender, final String nick, final String[] password) {
        if (this.plugin.settings().adminPasswordsConsoleOnly && !this.consoleOnly(sender)) {
            return;
        }
        if (password.length != 1) {
            sender.sendMessage(this.m().get("haslo-spacja"));
            return;
        }
        this.plugin.auth().adminSetPassword(nick, password[0], sender.getName(), sender::sendMessage);
    }

    private void unregister(final CommandSender sender, final String nick) {
        if (this.plugin.auth().adminUnregister(nick, sender.getName())) {
            sender.sendMessage(this.m().get("admin-wyrejestrowano", Messages.text("nick", nick)));
        } else {
            sender.sendMessage(this.m().get("admin-brak-konta", Messages.text("nick", nick)));
        }
    }

    private void unlock(final CommandSender sender, final String key) {
        sender.sendMessage(this.m().get(this.plugin.auth().guard().unlock(key) ? "admin-odblokowano" : "admin-nie-zablokowany",
            Messages.text("cel", key)));
    }

    private void importNLogin(final CommandSender sender, final boolean apply) {
        if (!this.consoleOnly(sender)) {
            return;
        }
        try {
            final ImportReport r = this.plugin.runImport(apply);
            sender.sendMessage(Component.text(r.summary(), NamedTextColor.YELLOW));
        } catch (final Exception e) {
            sender.sendMessage(this.m().get("import-blad", Messages.text("blad", e.getMessage())));
        }
    }

    private void exportNLogin(final CommandSender sender) {
        if (!this.consoleOnly(sender)) {
            return;
        }
        try {
            final NLoginExporter.Result r = this.plugin.runExport();
            sender.sendMessage(Component.text("Eksport do " + r.output() + ": zaktualizowano " + r.updated() + ", dodano "
                + r.inserted() + ", tylko w nLogin " + r.onlyInNLogin(), NamedTextColor.YELLOW));
        } catch (final Exception e) {
            sender.sendMessage(this.m().get("import-blad", Messages.text("blad", e.getMessage())));
        }
    }

    private void reload(final CommandSender sender) {
        final List<String> warnings = this.plugin.reloadAll();
        sender.sendMessage(this.m().get("przeladowano", Messages.number("ostrzezenia", warnings.size())));
        warnings.forEach(w -> sender.sendMessage(Component.text(" • " + w, NamedTextColor.YELLOW)));
    }
}
