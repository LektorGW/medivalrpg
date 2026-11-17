// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * {@code /glos} (aliasy {@code vc}, {@code voice}, {@code audio}) – rejestrowane przez Paper Brigadier
 * ({@code LifecycleEvents.COMMANDS}).
 */
public final class GlosCommand {
    public static final String PERM_USE = "glos.use";
    public static final String PERM_ADMIN = "glos.admin";

    private static final List<String> TIME_SUGGESTIONS = List.of("10m", "30m", "1h", "2h", "12h", "1d", "7d");

    private final GlosPlugin plugin;

    public GlosCommand(GlosPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        Predicate<CommandSourceStack> use = s -> s.getSender().hasPermission(PERM_USE);
        Predicate<CommandSourceStack> admin = s -> s.getSender().hasPermission(PERM_ADMIN);

        return Commands.literal("glos")
                .requires(use)
                .executes(ctx -> withPlayer(ctx, p -> plugin.voice().sendLink(p)))
                .then(Commands.literal("szept")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.voice().setMode(p, VoiceMode.WHISPER, false))))
                .then(Commands.literal("normalnie")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.voice().setMode(p, VoiceMode.NORMAL, false))))
                .then(Commands.literal("krzyk")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.voice().setMode(p, VoiceMode.SHOUT, false))))
                .then(Commands.literal("rozlacz")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.voice().disconnect(p))))
                .then(Commands.literal("pasek")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.voice().toggleActionbar(p, null)))
                        .then(Commands.literal("wlacz")
                                .executes(ctx -> withPlayer(ctx, p -> plugin.voice().toggleActionbar(p, true))))
                        .then(Commands.literal("wylacz")
                                .executes(ctx -> withPlayer(ctx, p -> plugin.voice().toggleActionbar(p, false)))))
                .then(Commands.literal("wskaznik")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.voice().toggleIndicators(p, null)))
                        .then(Commands.literal("wlacz")
                                .executes(ctx -> withPlayer(ctx, p -> plugin.voice().toggleIndicators(p, true))))
                        .then(Commands.literal("wylacz")
                                .executes(ctx -> withPlayer(ctx, p -> plugin.voice().toggleIndicators(p, false)))))
                .then(Commands.literal("pomoc")
                        .executes(this::help))
                .then(Commands.literal("mute")
                        .requires(admin)
                        .executes(ctx -> usage(ctx, "usage-mute"))
                        .then(Commands.argument("gracz", StringArgumentType.word())
                                .suggests(onlinePlayers())
                                .executes(ctx -> mute(ctx, null))
                                .then(Commands.argument("czas", StringArgumentType.word())
                                        .suggests(times())
                                        .executes(ctx -> mute(ctx, StringArgumentType.getString(ctx, "czas"))))))
                .then(Commands.literal("unmute")
                        .requires(admin)
                        .executes(ctx -> usage(ctx, "usage-unmute"))
                        .then(Commands.argument("gracz", StringArgumentType.word())
                                .suggests(mutedOrOnlinePlayers())
                                .executes(this::unmute)))
                .then(Commands.literal("lista")
                        .requires(admin)
                        .executes(ctx -> {
                            plugin.voice().sendList(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("reload")
                        .requires(admin)
                        .executes(ctx -> {
                            plugin.reloadAll();
                            plugin.messages().send(ctx.getSource().getSender(), "reloaded");
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    // ------------------------------------------------------------------ obsługa

    private interface PlayerAction {
        void run(Player p);
    }

    private int withPlayer(CommandContext<CommandSourceStack> ctx, PlayerAction action) {
        Entity exec = ctx.getSource().getExecutor();
        if (exec instanceof Player p) {
            action.run(p);
        } else if (ctx.getSource().getSender() instanceof Player p) {
            action.run(p);
        } else {
            plugin.messages().send(ctx.getSource().getSender(), "players-only");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int usage(CommandContext<CommandSourceStack> ctx, String key) {
        plugin.messages().send(ctx.getSource().getSender(), key);
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        plugin.messages().send(s, "help");
        plugin.messages().send(s, "help-prefs");
        if (s.hasPermission(PERM_ADMIN)) {
            plugin.messages().send(s, "help-admin");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int mute(CommandContext<CommandSourceStack> ctx, String time) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "gracz");
        long duration = -1;
        if (time != null) {
            duration = Durations.parseMillis(time);
            if (duration <= 0) {
                plugin.messages().send(sender, "invalid-time", Messages.text("time", time));
                return 0;
            }
        }
        Target t = resolve(name, false);
        if (t == null) {
            plugin.messages().send(sender, "player-not-found", Messages.text("player", name));
            return 0;
        }
        plugin.voice().mute(sender, t.id, t.name, duration);
        return Command.SINGLE_SUCCESS;
    }

    private int unmute(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "gracz");
        Target t = resolve(name, true);
        if (t == null) {
            plugin.messages().send(sender, "player-not-found", Messages.text("player", name));
            return 0;
        }
        plugin.voice().unmute(sender, t.id, t.name);
        return Command.SINGLE_SUCCESS;
    }

    private record Target(UUID id, String name) {
    }

    /** Gracz online (dokładny nick), potem wyciszony offline, potem z cache serwera. */
    private Target resolve(String name, boolean preferMuted) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName());
        }
        if (preferMuted) {
            UUID muted = plugin.mutes().findMutedByName(name);
            if (muted != null) {
                return new Target(muted, name);
            }
        }
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off != null) {
            return new Target(off.getUniqueId(), off.getName() != null ? off.getName() : name);
        }
        return null;
    }

    // ------------------------------------------------------------------ podpowiedzi (bezpieczne wątkowo)

    private SuggestionProvider<CommandSourceStack> onlinePlayers() {
        return (ctx, builder) -> suggest(builder, plugin.voice().onlineNames());
    }

    private SuggestionProvider<CommandSourceStack> mutedOrOnlinePlayers() {
        return (ctx, builder) -> {
            List<String> names = new ArrayList<>(plugin.mutes().mutedNames());
            for (String n : plugin.voice().onlineNames()) {
                if (!names.contains(n)) {
                    names.add(n);
                }
            }
            return suggest(builder, names);
        };
    }

    private SuggestionProvider<CommandSourceStack> times() {
        return (ctx, builder) -> suggest(builder, TIME_SUGGESTIONS);
    }

    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, Collection<String> options) {
        String rem = builder.getRemainingLowerCase();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(rem)) {
                builder.suggest(o);
            }
        }
        return builder.buildFuture();
    }
}
