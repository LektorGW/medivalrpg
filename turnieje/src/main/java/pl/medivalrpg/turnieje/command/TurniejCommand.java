// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.turnieje.arena.Arena;
import pl.medivalrpg.turnieje.arena.ArenaType;
import pl.medivalrpg.turnieje.config.Messages;
import pl.medivalrpg.turnieje.data.Storage;
import pl.medivalrpg.turnieje.game.SessionManager;
import pl.medivalrpg.turnieje.integration.Services;

/**
 * {@code /turniej} (alias {@code /arena}) — Brigadier tree with Polish argument names and tooltips, interactive help
 * ({@code /turniej pomoc}).
 */
public final class TurniejCommand {

    public static final String PERM_USE = "turnieje.use";
    public static final String PERM_ORGANIZE = "turnieje.organize";
    public static final String PERM_ADMIN = "turnieje.admin";

    static final HelpBook HELP = new HelpBook("help", "/turniej",
        List.of("gracz", "pojedynki", "wladca", "admin"),
        List.of(
            new HelpBook.Entry("zapisz", "gracz", "/turniej zapisz", "/turniej zapisz", false),
            new HelpBook.Entry("wypisz", "gracz", "/turniej wypisz", "/turniej wypisz", false),
            new HelpBook.Entry("info", "gracz", "/turniej info", "/turniej info", false),
            new HelpBook.Entry("ranking", "gracz", "/turniej ranking [strona]", "/turniej ranking", false),
            new HelpBook.Entry("ogladaj", "gracz", "/turniej ogladaj [arena]", "/turniej ogladaj", false),
            new HelpBook.Entry("pomoc", "gracz", "/turniej pomoc [strona]", "/turniej pomoc ", false),
            new HelpBook.Entry("wyzwij", "pojedynki", "/turniej wyzwij <gracz>", "/turniej wyzwij ", false),
            new HelpBook.Entry("przyjmij", "pojedynki", "/turniej przyjmij", "/turniej przyjmij", false),
            new HelpBook.Entry("odrzuc", "pojedynki", "/turniej odrzuc", "/turniej odrzuc", false),
            new HelpBook.Entry("zorganizuj", "wladca", "/turniej zorganizuj <arena> <pula> [wpisowe] [minuty]", "/turniej zorganizuj ", false),
            new HelpBook.Entry("arena-rog", "admin", "/turniej arena rog <1|2>", "/turniej arena rog ", true),
            new HelpBook.Entry("arena-stworz", "admin", "/turniej arena stworz <nazwa> <typ>", "/turniej arena stworz ", true),
            new HelpBook.Entry("arena-spawn", "admin", "/turniej arena spawn <arena> [dodaj|wyczysc]", "/turniej arena spawn ", true),
            new HelpBook.Entry("arena-widownia", "admin", "/turniej arena widownia <arena>", "/turniej arena widownia ", true),
            new HelpBook.Entry("arena-kraj", "admin", "/turniej arena kraj <arena> <kraj|publiczna>", "/turniej arena kraj ", true),
            new HelpBook.Entry("arena-konno", "admin", "/turniej arena konno <arena> <tak|nie>", "/turniej arena konno ", true),
            new HelpBook.Entry("arena-ekwipunek", "admin", "/turniej arena ekwipunek <arena> <zestaw|wlasny|domyslny>", "/turniej arena ekwipunek ", true),
            new HelpBook.Entry("arena-lista", "admin", "/turniej arena lista", "/turniej arena lista", true),
            new HelpBook.Entry("arena-usun", "admin", "/turniej arena usun <arena>", "/turniej arena usun ", true),
            new HelpBook.Entry("start", "admin", "/turniej start [arena] [minuty]", "/turniej start", true),
            new HelpBook.Entry("stop", "admin", "/turniej stop [arena]", "/turniej stop", true),
            new HelpBook.Entry("reload", "admin", "/turniej reload", "/turniej reload", true)));

    /** Plugin actions used by the command. */
    public interface Actions {
        void reload();
    }

    private final SessionManager sessions;
    private final Storage storage;
    private final Services services;
    private final Actions actions;
    private final ArenaAdmin arenas;

    public TurniejCommand(final SessionManager sessions, final Storage storage, final Services services, final Actions actions) {
        this.sessions = sessions;
        this.storage = storage;
        this.services = services;
        this.actions = actions;
        this.arenas = new ArenaAdmin(storage, sessions, services);
    }

    private Messages msg() {
        return this.sessions.messages();
    }

    private static Component tip(final Messages m, final String key) {
        return m.get("tip." + key);
    }

    private SuggestionProvider<CommandSourceStack> arenaSuggestions(final boolean tournamentsOnly) {
        return (ctx, b) -> {
            final String rem = b.getRemainingLowerCase();
            for (final Arena a : this.storage.arenas().values()) {
                if ((!tournamentsOnly || a.type.hostsTournaments()) && a.id.startsWith(rem)) {
                    b.suggest(a.id, MessageComponentSerializer.message().serialize(Component.text(this.msg().plain("type." + a.type.id()))));
                }
            }
            return b.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> words(final String... options) {
        return (ctx, b) -> {
            for (final String o : options) {
                if (o.startsWith(b.getRemainingLowerCase())) {
                    b.suggest(o);
                }
            }
            return b.buildFuture();
        };
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> arenaArg(final boolean tournamentsOnly) {
        return Commands.argument("arena", StringArgumentType.word()).suggests(this.arenaSuggestions(tournamentsOnly));
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        final Messages m = this.msg();
        return Commands.literal("turniej")
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
                                this.msg().parse(HELP.categoryName(this.msg(), pages.get(i)))));
                        }
                        return b.buildFuture();
                    })
                    .executes(ctx -> this.help(ctx, StringArgumentType.getString(ctx, "strona")))))
            .then(Commands.literal("zapisz").executes(ctx -> this.player(ctx, this.sessions::signup)))
            .then(Commands.literal("wypisz").executes(ctx -> this.player(ctx, this.sessions::signout)))
            .then(Commands.literal("info").executes(ctx -> {
                this.sessions.info().forEach(ctx.getSource().getSender()::sendMessage);
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("ranking")
                .executes(ctx -> this.ranking(ctx, 1))
                .then(Commands.argument("strona", IntegerArgumentType.integer(1, 1000))
                    .executes(ctx -> this.ranking(ctx, IntegerArgumentType.getInteger(ctx, "strona")))))
            .then(Commands.literal("ogladaj")
                .executes(ctx -> this.player(ctx, p -> this.sessions.spectate(p, null)))
                .then(this.arenaArg(false)
                    .executes(ctx -> this.player(ctx, p -> this.sessions.spectate(p, StringArgumentType.getString(ctx, "arena"))))))
            .then(this.organize(m))
            .then(Commands.literal("wyzwij")
                .then(Commands.argument("gracz", StringArgumentType.word())
                    .suggests((ctx, b) -> {
                        for (final Player p : Bukkit.getOnlinePlayers()) {
                            if (p.getName().toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase()) && !p.equals(ctx.getSource().getSender())) {
                                b.suggest(p.getName());
                            }
                        }
                        return b.buildFuture();
                    })
                    .executes(ctx -> this.player(ctx, p -> this.sessions.challenge(p, StringArgumentType.getString(ctx, "gracz"))))))
            .then(Commands.literal("przyjmij").executes(ctx -> this.player(ctx, this.sessions::acceptDuel)))
            .then(Commands.literal("odrzuc").executes(ctx -> this.player(ctx, this.sessions::declineDuel)))
            .then(this.arenaAdmin())
            .then(Commands.literal("start")
                .requires(s -> s.getSender().hasPermission(PERM_ADMIN))
                .executes(ctx -> {
                    this.sessions.adminStart(ctx.getSource().getSender(), null, null);
                    return Command.SINGLE_SUCCESS;
                })
                .then(this.arenaArg(true)
                    .executes(ctx -> {
                        this.sessions.adminStart(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "arena").toLowerCase(Locale.ROOT), null);
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("minuty", IntegerArgumentType.integer(1, 1440))
                        .executes(ctx -> {
                            this.sessions.adminStart(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "arena").toLowerCase(Locale.ROOT),
                                IntegerArgumentType.getInteger(ctx, "minuty"));
                            return Command.SINGLE_SUCCESS;
                        }))))
            .then(Commands.literal("stop")
                .requires(s -> s.getSender().hasPermission(PERM_ADMIN))
                .executes(ctx -> {
                    this.sessions.adminStop(ctx.getSource().getSender(), null);
                    return Command.SINGLE_SUCCESS;
                })
                .then(this.arenaArg(false)
                    .executes(ctx -> {
                        this.sessions.adminStop(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "arena").toLowerCase(Locale.ROOT));
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.literal("reload")
                .requires(s -> s.getSender().hasPermission(PERM_ADMIN))
                .executes(ctx -> {
                    this.actions.reload();
                    this.msg().send(ctx.getSource().getSender(), "reloaded");
                    return Command.SINGLE_SUCCESS;
                }))
            .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> organize(final Messages m) {
        return Commands.literal("zorganizuj")
            .requires(s -> s.getSender().hasPermission(PERM_ORGANIZE))
            .then(this.arenaArg(true)
                .then(Commands.argument("pula", StringArgumentType.word())
                    .suggests((ctx, b) -> {
                        b.suggest("5zl", MessageComponentSerializer.message().serialize(tip(this.msg(), "pula")));
                        b.suggest("20zl", MessageComponentSerializer.message().serialize(tip(this.msg(), "pula")));
                        return b.buildFuture();
                    })
                    .executes(ctx -> this.player(ctx, p -> this.sessions.organize(p, StringArgumentType.getString(ctx, "arena"),
                        StringArgumentType.getString(ctx, "pula"), null, null)))
                    .then(Commands.argument("wpisowe", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            b.suggest("0", MessageComponentSerializer.message().serialize(tip(this.msg(), "wpisowe")));
                            b.suggest("50", MessageComponentSerializer.message().serialize(tip(this.msg(), "wpisowe")));
                            return b.buildFuture();
                        })
                        .executes(ctx -> this.player(ctx, p -> this.sessions.organize(p, StringArgumentType.getString(ctx, "arena"),
                            StringArgumentType.getString(ctx, "pula"), StringArgumentType.getString(ctx, "wpisowe"), null)))
                        .then(Commands.argument("minuty", IntegerArgumentType.integer(1, 1440))
                            .executes(ctx -> this.player(ctx, p -> this.sessions.organize(p, StringArgumentType.getString(ctx, "arena"),
                                StringArgumentType.getString(ctx, "pula"), StringArgumentType.getString(ctx, "wpisowe"),
                                IntegerArgumentType.getInteger(ctx, "minuty"))))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> arenaAdmin() {
        return Commands.literal("arena")
            .requires(s -> s.getSender().hasPermission(PERM_ADMIN))
            .executes(ctx -> this.help(ctx, "admin"))
            .then(Commands.literal("rog")
                .then(Commands.argument("numer", IntegerArgumentType.integer(1, 2))
                    .executes(ctx -> this.player(ctx, p -> this.arenas.corner(p, IntegerArgumentType.getInteger(ctx, "numer"))))))
            .then(Commands.literal("stworz")
                .then(Commands.argument("nazwa", StringArgumentType.word())
                    .then(Commands.argument("typ", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            for (final ArenaType t : ArenaType.values()) {
                                if (t.id().startsWith(b.getRemainingLowerCase())) {
                                    b.suggest(t.id(), MessageComponentSerializer.message().serialize(this.msg().get("type." + t.id())));
                                }
                            }
                            return b.buildFuture();
                        })
                        .executes(ctx -> this.player(ctx, p -> this.arenas.create(p, StringArgumentType.getString(ctx, "nazwa"),
                            StringArgumentType.getString(ctx, "typ")))))))
            .then(Commands.literal("usun")
                .then(this.arenaArg(false).executes(ctx -> this.sender(ctx, s -> this.arenas.remove(s, StringArgumentType.getString(ctx, "arena"))))))
            .then(Commands.literal("lista").executes(ctx -> this.sender(ctx, this.arenas::list)))
            .then(Commands.literal("spawn")
                .then(this.arenaArg(false)
                    .executes(ctx -> this.player(ctx, p -> this.arenas.addSpawn(p, StringArgumentType.getString(ctx, "arena"))))
                    .then(Commands.literal("dodaj")
                        .executes(ctx -> this.player(ctx, p -> this.arenas.addSpawn(p, StringArgumentType.getString(ctx, "arena")))))
                    .then(Commands.literal("wyczysc")
                        .executes(ctx -> this.sender(ctx, s -> this.arenas.clearSpawns(s, StringArgumentType.getString(ctx, "arena")))))))
            .then(Commands.literal("widownia")
                .then(this.arenaArg(false)
                    .executes(ctx -> this.player(ctx, p -> this.arenas.setSpectators(p, StringArgumentType.getString(ctx, "arena"))))))
            .then(Commands.literal("kraj")
                .then(this.arenaArg(false)
                    .then(Commands.argument("kraj", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> {
                            b.suggest("publiczna");
                            for (final String n : this.services.countries().names()) {
                                if (n.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) {
                                    b.suggest(n);
                                }
                            }
                            return b.buildFuture();
                        })
                        .executes(ctx -> this.sender(ctx, s -> this.arenas.setCountry(s, StringArgumentType.getString(ctx, "arena"),
                            StringArgumentType.getString(ctx, "kraj")))))))
            .then(Commands.literal("konno")
                .then(this.arenaArg(true)
                    .then(Commands.argument("wartosc", StringArgumentType.word())
                        .suggests(words("tak", "nie"))
                        .executes(ctx -> this.sender(ctx, s -> this.arenas.setMounted(s, StringArgumentType.getString(ctx, "arena"),
                            StringArgumentType.getString(ctx, "wartosc").equalsIgnoreCase("tak")))))))
            .then(Commands.literal("ekwipunek")
                .then(this.arenaArg(false)
                    .then(Commands.argument("tryb", StringArgumentType.word())
                        .suggests(words("zestaw", "wlasny", "domyslny"))
                        .executes(ctx -> this.sender(ctx, s -> this.arenas.setGear(s, StringArgumentType.getString(ctx, "arena"),
                            StringArgumentType.getString(ctx, "tryb")))))));
    }

    private int help(final CommandContext<CommandSourceStack> ctx, final String page) {
        final CommandSender s = ctx.getSource().getSender();
        for (final Component line : HELP.render(this.msg(), s.hasPermission(PERM_ADMIN), page)) {
            s.sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int ranking(final CommandContext<CommandSourceStack> ctx, final int page) {
        final CommandSender s = ctx.getSource().getSender();
        this.sessions.ranking(s instanceof Player p ? p : null, page).forEach(s::sendMessage);
        return Command.SINGLE_SUCCESS;
    }

    private int player(final CommandContext<CommandSourceStack> ctx, final Consumer<Player> action) {
        if (ctx.getSource().getSender() instanceof Player p) {
            action.accept(p);
        } else {
            this.msg().send(ctx.getSource().getSender(), "players-only");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int sender(final CommandContext<CommandSourceStack> ctx, final Consumer<CommandSender> action) {
        action.accept(ctx.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }
}
