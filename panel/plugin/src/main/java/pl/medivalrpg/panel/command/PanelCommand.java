// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.command;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.panel.Messages;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.data.PanelData;
import pl.medivalrpg.panel.net.Protocol;

/**
 * {@code /panel}: login link, logout, and (console or panel admin) role management. Brigadier tree with Polish
 * argument names and tooltips. Executed on the main thread.
 */
public final class PanelCommand {

    public static final String PERM_USE = "panel.use";

    private final PanelPlugin plugin;
    private final Map<UUID, Long> lastLink = new HashMap<>();

    public PanelCommand(final PanelPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return this.plugin.messages();
    }

    /** Console / RCON, or a player who is a panel admin. Command blocks are not trusted. */
    private boolean admin(final CommandSender s) {
        if (s instanceof ConsoleCommandSender || s instanceof RemoteConsoleCommandSender) {
            return true;
        }
        return s instanceof Player p && this.plugin.roles().isAdmin(p.getUniqueId(), p.getName());
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("panel")
            .requires(s -> s.getSender().hasPermission(PERM_USE) || this.admin(s.getSender()))
            .executes(ctx -> this.player(ctx, this::link))
            .then(Commands.literal("wyloguj").executes(ctx -> this.player(ctx, this::logout)))
            .then(Commands.literal("pomoc").executes(this::help))
            .then(Commands.literal("reload")
                .requires(s -> this.admin(s.getSender()))
                .executes(ctx -> {
                    this.plugin.reloadAll();
                    this.m().send(ctx.getSource().getSender(), "reloaded");
                    return Command.SINGLE_SUCCESS;
                }))
            .then(Commands.literal("status")
                .requires(s -> this.admin(s.getSender()))
                .executes(ctx -> {
                    this.m().send(ctx.getSource().getSender(), "status",
                        Placeholder.component("status", this.m().get(this.plugin.connected() ? "status-online" : "status-offline")),
                        Messages.text("url", this.plugin.cfg().workerUrl));
                    return Command.SINGLE_SUCCESS;
                }))
            .then(Commands.literal("wydajnosc")
                .requires(s -> this.admin(s.getSender()) || s.getSender().hasPermission(this.plugin.cfg().health.alerts().permission()))
                .executes(ctx -> this.health(ctx.getSource().getSender())))
            .then(this.roleNode("admin", true))
            .then(this.roleNode("zarzadca", false)
                .then(Commands.literal("przypisz")
                    .then(Commands.argument("kraj", StringArgumentType.string())
                        .suggests(this.provider((ctx, b) -> {
                            for (final KrajInfo k : this.plugin.kraje().getCountries()) {
                                if (k.npc()) {
                                    add(b, StringArgumentType.escapeIfRequired(k.name()), this.m().get("suggest-npc-country"));
                                }
                            }
                        }))
                        .then(Commands.argument("gracz", StringArgumentType.word())
                            .suggests(this.provider((ctx, b) -> {
                                add(b, "-", this.m().get("suggest-none"));
                                this.suggestPlayers(b);
                            }))
                            .executes(this::assign)))))
            .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> roleNode(final String literal, final boolean adminRole) {
        return Commands.literal(literal)
            .requires(s -> this.admin(s.getSender()))
            .executes(ctx -> this.list(ctx.getSource().getSender(), adminRole))
            .then(Commands.literal("lista").executes(ctx -> this.list(ctx.getSource().getSender(), adminRole)))
            .then(Commands.literal("dodaj")
                .then(Commands.argument("gracz", StringArgumentType.word())
                    .suggests(this.provider((ctx, b) -> this.suggestPlayers(b)))
                    .executes(ctx -> this.change(ctx, adminRole, true))))
            .then(Commands.literal("usun")
                .then(Commands.argument("gracz", StringArgumentType.word())
                    .suggests(this.provider((ctx, b) -> {
                        for (final String n : adminRole ? this.plugin.getConfig().getStringList("admins")
                            : this.plugin.getConfig().getStringList("npc-managers")) {
                            add(b, n, null);
                        }
                    }))
                    .executes(ctx -> this.change(ctx, adminRole, false))));
    }

    // ------------------------------------------------------------------ actions

    private interface PlayerAction {
        void run(Player p);
    }

    private int player(final CommandContext<CommandSourceStack> ctx, final PlayerAction action) {
        if (ctx.getSource().getSender() instanceof Player p) {
            action.run(p);
        } else if (ctx.getSource().getExecutor() instanceof Player p) {
            action.run(p);
        } else {
            this.m().send(ctx.getSource().getSender(), "players-only");
        }
        return Command.SINGLE_SUCCESS;
    }

    private void link(final Player p) {
        if (!this.plugin.connected() || this.plugin.cfg().workerUrl.isEmpty()) {
            this.m().send(p, "unavailable");
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = this.lastLink.get(p.getUniqueId());
        final long cooldown = this.plugin.cfg().linkCooldownMillis;
        if (last != null && now - last < cooldown) {
            this.m().send(p, "link-cooldown", Messages.text("seconds", Math.max(1, (cooldown - (now - last) + 999) / 1000)));
            return;
        }
        final String token = this.plugin.auth().newToken();
        if (!this.plugin.send(Protocol.linkToken(token, p.getUniqueId(), p.getName()))) {
            this.m().send(p, "unavailable");
            return;
        }
        this.lastLink.put(p.getUniqueId(), now);
        final String url = this.plugin.cfg().workerUrl + "/login?t=" + token;
        this.m().send(p, "link",
            Placeholder.styling("link", ClickEvent.openUrl(url), HoverEvent.showText(this.m().get("link-hover"))),
            Messages.text("minutes", this.plugin.cfg().linkMinutes),
            Messages.text("url", url));
    }

    private void logout(final Player p) {
        if (!this.plugin.send(Protocol.revoke(p.getUniqueId()))) {
            this.m().send(p, "unavailable");
            return;
        }
        this.m().send(p, "logged-out");
    }

    /** {@code /panel wydajnosc}: last health probe and active warnings. */
    private int health(final CommandSender s) {
        final pl.medivalrpg.panel.health.HealthSettings cfg = this.plugin.cfg().health;
        if (!cfg.enabled()) {
            this.m().send(s, "health-disabled");
            return Command.SINGLE_SUCCESS;
        }
        final pl.medivalrpg.panel.health.HealthJson.Current c = this.plugin.health().current();
        if (c == null) {
            this.m().send(s, "health-none");
            return Command.SINGLE_SUCCESS;
        }
        final pl.medivalrpg.panel.health.HealthWarnings.Thresholds th = cfg.thresholds();
        final List<String> parts = new ArrayList<>();
        c.npc().forEach((id, n) -> {
            if (n > 0) {
                parts.add(pl.medivalrpg.panel.health.NpcCensus.NAMES.getOrDefault(id, id) + " " + n);
            }
        });
        this.m().send(s, "health-status",
            Messages.text("seconds", Math.max(0, (System.currentTimeMillis() - c.t()) / 1000)),
            Placeholder.component("tps", this.level(fmt(c.tps1()), c.tps1() < th.tpsCritical(), c.tps1() < th.tps())),
            Messages.text("tps5", fmt(c.tps5())),
            Messages.text("tps15", fmt(c.tps15())),
            Placeholder.component("mspt", this.level(fmt(c.mspt()), th.msptCritical() > 0 && c.mspt() > th.msptCritical(),
                th.mspt() > 0 && c.mspt() > th.mspt())),
            Messages.text("players", c.players()),
            Messages.text("chunks", c.chunks()),
            Messages.text("entities", c.entities()),
            Messages.text("npc", c.npcScan() ? String.valueOf(c.npcTotal()) : "-"),
            Messages.text("list", parts.isEmpty() ? "-" : String.join(", ", parts)));
        final List<pl.medivalrpg.panel.health.HealthWarnings.Warning> active = this.plugin.health().warnings().active();
        if (active.isEmpty()) {
            this.m().send(s, "health-ok");
        }
        for (final pl.medivalrpg.panel.health.HealthWarnings.Warning w : active) {
            this.m().send(s, "health-warning-line", Messages.text("value", w.text()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private Component level(final String text, final boolean bad, final boolean warn) {
        return this.m().get(bad ? "health-bad" : warn ? "health-warn" : "health-good", Messages.text("value", text));
    }

    private static String fmt(final double v) {
        return String.format(Locale.forLanguageTag("pl-PL"), "%.1f", v);
    }

    private int help(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender s = ctx.getSource().getSender();
        this.m().send(s, "help");
        if (this.admin(s)) {
            this.m().send(s, "help-admin");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int list(final CommandSender s, final boolean adminRole) {
        final String key = adminRole ? "admins" : "npc-managers";
        final List<String> names = this.plugin.getConfig().getStringList(key);
        final String list = names.isEmpty() ? "-" : String.join(", ", names);
        if (adminRole) {
            this.m().send(s, "admin-list", Messages.text("list", list), Messages.text("ops", this.plugin.cfg().opsAreAdmins ? "tak" : "nie"));
            return Command.SINGLE_SUCCESS;
        }
        this.m().send(s, "manager-list", Messages.text("list", list));
        for (final Map.Entry<UUID, PanelData.Manager> e : this.plugin.data().managers().entrySet()) {
            final String country = this.plugin.kraje().getCountry(e.getKey()).map(KrajInfo::name).orElse(e.getKey().toString());
            this.m().send(s, "manager-list-assigned", Messages.text("country", country), Messages.text("player", e.getValue().name()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int change(final CommandContext<CommandSourceStack> ctx, final boolean adminRole, final boolean add) {
        final CommandSender s = ctx.getSource().getSender();
        final String input = StringArgumentType.getString(ctx, "gracz");
        final String prefix = adminRole ? "admin-" : "manager-";
        if (add) {
            final Optional<OfflinePlayer> p = known(input);
            if (p.isEmpty()) {
                this.m().send(s, "player-not-found", Messages.text("player", input));
                return 0;
            }
            final String name = p.get().getName() == null ? input : p.get().getName();
            final boolean changed = this.plugin.editRoleList(adminRole ? "admins" : "npc-managers", name, true);
            this.m().send(s, changed ? prefix + "added" : prefix + "already", Messages.text("player", name));
        } else {
            final boolean changed = this.plugin.editRoleList(adminRole ? "admins" : "npc-managers", input, false);
            this.m().send(s, changed ? prefix + "removed" : prefix + "not-found", Messages.text("player", input));
        }
        // Admin sub-commands appear/disappear in the player's command tree without relogging.
        final Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            online.updateCommands();
        }
        return Command.SINGLE_SUCCESS;
    }

    private int assign(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender s = ctx.getSource().getSender();
        final String countryName = StringArgumentType.getString(ctx, "kraj");
        final String input = StringArgumentType.getString(ctx, "gracz");
        final Optional<KrajInfo> k = this.plugin.kraje().getCountries().stream()
            .filter(c -> c.name().equalsIgnoreCase(countryName)).findFirst();
        if (k.isEmpty()) {
            this.m().send(s, "country-not-found", Messages.text("country", countryName));
            return 0;
        }
        if (!k.get().npc()) {
            this.m().send(s, "country-not-npc", Messages.text("country", k.get().name()));
            return 0;
        }
        if (input.equals("-")) {
            this.plugin.data().setManager(k.get().id(), null);
            this.m().send(s, "manager-unassigned", Messages.text("country", k.get().name()));
            return Command.SINGLE_SUCCESS;
        }
        final Optional<OfflinePlayer> p = known(input);
        if (p.isEmpty()) {
            this.m().send(s, "player-not-found", Messages.text("player", input));
            return 0;
        }
        final String name = p.get().getName() == null ? input : p.get().getName();
        this.plugin.data().setManager(k.get().id(), new PanelData.Manager(p.get().getUniqueId(), name));
        this.m().send(s, "manager-assigned", Messages.text("player", name), Messages.text("country", k.get().name()));
        return Command.SINGLE_SUCCESS;
    }

    private static Optional<OfflinePlayer> known(final String name) {
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online);
        }
        return Optional.ofNullable(Bukkit.getOfflinePlayerIfCached(name));
    }

    // ------------------------------------------------------------------ suggestions

    private void suggestPlayers(final SuggestionsBuilder b) {
        final List<String> names = new ArrayList<>();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        for (final String n : names) {
            add(b, n, this.m().get("suggest-player"));
        }
    }

    /** Suggestions computed on the main thread (Bukkit state). */
    private SuggestionProvider<CommandSourceStack> provider(final BiConsumer<CommandContext<CommandSourceStack>, SuggestionsBuilder> fill) {
        return (ctx, builder) -> {
            if (Bukkit.isPrimaryThread()) {
                fill.accept(ctx, builder);
                return builder.buildFuture();
            }
            try {
                return CompletableFuture.supplyAsync(() -> {
                    fill.accept(ctx, builder);
                    return builder.build();
                }, Bukkit.getScheduler().getMainThreadExecutor(this.plugin));
            } catch (final RuntimeException e) {
                return Suggestions.empty();
            }
        };
    }

    private static void add(final SuggestionsBuilder builder, final String text, final Component tooltip) {
        final String typed = builder.getRemainingLowerCase().replace("\"", "");
        if (text.replace("\"", "").toLowerCase(Locale.ROOT).startsWith(typed)) {
            builder.suggest(text, tooltip == null ? null : MessageComponentSerializer.message().serialize(tooltip));
        }
    }

    /** Forgets link cooldowns of players who left. */
    public void forget(final UUID player) {
        this.lastLink.remove(player);
    }
}
