// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import pl.medivalrpg.straznik.auth.AuthService;
import pl.medivalrpg.straznik.config.Messages;

/**
 * Player commands: {@code /login}, {@code /register}, {@code /zmienhaslo}, {@code /wyloguj}. Passwords are one
 * greedy argument split on spaces (Brigadier's plain strings would refuse characters like {@code !} or {@code ą}),
 * exactly like nLogin read them: a password never contains a space.
 */
public final class AuthCommands {

    private final AuthService auth;
    private final Supplier<Messages> messages;

    public AuthCommands(final AuthService auth, final Supplier<Messages> messages) {
        this.auth = auth;
        this.messages = messages;
    }

    static String[] tokens(final String raw) {
        final String t = raw == null ? "" : raw.strip();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }

    /** Password tokens when there are at least {@code needed} of them, otherwise {@code null} (usage is shown). */
    static String[] passwords(final String raw, final int needed) {
        final String[] t = tokens(raw);
        return t.length < needed ? null : t;
    }

    /**
     * Runs {@code action} for a player with at least {@code needed} words in {@code argument} (whitespace only, e.g.
     * "/login" followed by two spaces, gives the usage message instead of an exception that Paper would log).
     */
    private int player(final CommandContext<CommandSourceStack> ctx, final BiConsumer<Player, String[]> action,
                       final String argument, final int needed, final String usageKey) {
        if (!(ctx.getSource().getExecutor() instanceof Player p) || ctx.getSource().getSender() != p) {
            ctx.getSource().getSender().sendMessage(this.messages.get().get("tylko-gracz"));
            return 0;
        }
        final String[] t = argument == null ? new String[0] : passwords(StringArgumentType.getString(ctx, argument), needed);
        if (t == null) {
            p.sendMessage(this.messages.get().get(usageKey));
        } else {
            action.accept(p, t);
        }
        return Command.SINGLE_SUCCESS;
    }

    private Command<CommandSourceStack> usage(final String key) {
        return ctx -> {
            ctx.getSource().getSender().sendMessage(this.messages.get().get(key));
            return Command.SINGLE_SUCCESS;
        };
    }

    public LiteralCommandNode<CommandSourceStack> login() {
        return Commands.literal("login")
            .executes(this.usage("uzycie-login"))
            .then(Commands.argument("hasło", StringArgumentType.greedyString())
                .executes(ctx -> this.player(ctx, (p, t) -> this.auth.login(p, t[0]), "hasło", 1, "uzycie-login")))
            .build();
    }

    public LiteralCommandNode<CommandSourceStack> register() {
        return Commands.literal("register")
            .executes(this.usage("uzycie-rejestracja"))
            .then(Commands.argument("hasło_i_powtórzenie", StringArgumentType.greedyString())
                .executes(ctx -> this.player(ctx, (p, t) -> this.auth.register(p, t[0], t[1]), "hasło_i_powtórzenie", 2,
                    "uzycie-rejestracja")))
            .build();
    }

    public LiteralCommandNode<CommandSourceStack> changePassword() {
        return Commands.literal("zmienhaslo")
            .executes(this.usage("uzycie-zmiana"))
            .then(Commands.argument("stare_i_nowe_hasło", StringArgumentType.greedyString())
                .executes(ctx -> this.player(ctx, (p, t) -> this.auth.changePassword(p, t[0], t[1]), "stare_i_nowe_hasło", 2,
                    "uzycie-zmiana")))
            .build();
    }

    public LiteralCommandNode<CommandSourceStack> logout() {
        return Commands.literal("wyloguj")
            .executes(ctx -> this.player(ctx, (p, t) -> this.auth.logout(p), null, 0, null))
            .build();
    }
}
