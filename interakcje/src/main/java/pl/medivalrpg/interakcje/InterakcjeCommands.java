// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Brigadier commands ({@code LifecycleEvents.COMMANDS}): one command per interaction / pose from the
 * config, the hold release command and {@code /interakcje} ({@code /int}).
 */
public final class InterakcjeCommands {
    public static final String PERM_ADMIN = "interakcje.admin";
    public static final String ROOT = "interakcje";
    public static final List<String> ROOT_ALIASES = List.of("int");

    private final InterakcjePlugin plugin;

    public InterakcjeCommands(InterakcjePlugin plugin) {
        this.plugin = plugin;
    }

    public void registerAll(Commands registrar) {
        for (InteractionDef def : plugin.interactions().values()) {
            safeRegister(registrar, interactionCommand(def), Messages.plain(def.name()), def.aliases());
        }
        for (PoseDef pose : plugin.poseDefs().values()) {
            safeRegister(registrar, poseCommand(pose), Messages.plain(pose.name()), pose.aliases());
        }
        for (MotionGroup group : plugin.motionGroups().values()) {
            safeRegister(registrar, motionCommand(group), group.key().equals(InterakcjePlugin.DANCES)
                    ? "Taniec (animacja postaci)" : "Emotki (animacje postaci)", group.aliases());
        }
        Settings s = plugin.settings();
        safeRegister(registrar, Commands.literal(s.releaseCommand())
                .requires(src -> src.getSender().hasPermission(InteractionService.PERM_USE))
                .executes(ctx -> withPlayer(ctx, p -> {
                    if (!plugin.holds().release(p)) {
                        plugin.messages().send(p, "hold-none");
                    }
                }))
                .build(), "Puść dłoń trzymanej osoby", s.releaseAliases());
        safeRegister(registrar, rootCommand(), "Interakcje RP między graczami", ROOT_ALIASES);
    }

    private void safeRegister(Commands registrar, LiteralCommandNode<CommandSourceStack> node, String description,
                              List<String> aliases) {
        try {
            registrar.register(node, description, aliases);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Nie udało się zarejestrować komendy /" + node.getLiteral() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ interactions & poses

    private LiteralCommandNode<CommandSourceStack> interactionCommand(InteractionDef registered) {
        String id = registered.id();
        Predicate<CommandSourceStack> perm = src -> src.getSender().hasPermission(InteractionService.PERM_USE)
                && src.getSender().hasPermission("interakcje." + id);
        return Commands.literal(registered.command())
                .requires(perm)
                .executes(ctx -> withPlayer(ctx, p -> {
                    InteractionDef def = plugin.interaction(id);
                    if (def != null && !def.targetOptional()) {
                        plugin.messages().send(p, "usage", Messages.text("command", def.command()));
                    } else {
                        plugin.service().attempt(p, def, null);
                    }
                }))
                .then(Commands.argument("gracz", StringArgumentType.word())
                        .suggests(nearbyPlayers(id))
                        .executes(ctx -> withPlayer(ctx, p -> {
                            String name = StringArgumentType.getString(ctx, "gracz");
                            Player target = Bukkit.getPlayerExact(name);
                            if (target == null || (!target.equals(p) && !plugin.service().canTarget(p, target))) {
                                plugin.messages().send(p, "player-not-found", Messages.text("player", name));
                                return;
                            }
                            plugin.service().attempt(p, plugin.interaction(id), target);
                        })))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> poseCommand(PoseDef registered) {
        String id = registered.id();
        return Commands.literal(registered.command())
                .requires(src -> src.getSender().hasPermission(InteractionService.PERM_USE)
                        && src.getSender().hasPermission("interakcje." + id))
                .executes(ctx -> withPlayer(ctx, p -> {
                    if (PoseDef.SIT.equals(id)) {
                        plugin.poses().toggleSit(p);
                    } else {
                        plugin.poses().toggleLay(p);
                    }
                }))
                .build();
    }

    // ------------------------------------------------------------------ dances & emotes

    /** {@code /taniec [1-3|stop]} and {@code /emotka <nazwa>}. */
    private LiteralCommandNode<CommandSourceStack> motionCommand(MotionGroup registered) {
        String key = registered.key();
        return Commands.literal(registered.command())
                .requires(src -> src.getSender().hasPermission(InteractionService.PERM_USE)
                        && src.getSender().hasPermission(registered.permission()))
                .executes(ctx -> withPlayer(ctx, p -> motionNoArgument(p, plugin.motionGroups().get(key))))
                .then(Commands.argument(registered.argumentName(), StringArgumentType.word())
                        .suggests(motionSuggestions(key))
                        .executes(ctx -> withPlayer(ctx, p -> runMotion(p, key,
                                StringArgumentType.getString(ctx, registered.argumentName()), null)))
                        // /emotka poklon <gracz>: turn to the target first (only for target: optional)
                        .then(Commands.argument("gracz", StringArgumentType.word())
                                .suggests(onlinePlayers())
                                .executes(ctx -> withPlayer(ctx, p -> runMotion(p, key,
                                        StringArgumentType.getString(ctx, registered.argumentName()),
                                        StringArgumentType.getString(ctx, "gracz"))))))
                .build();
    }

    private void runMotion(Player p, String key, String arg, String targetName) {
        MotionGroup group = plugin.motionGroups().get(key);
        if (STOP_WORDS.contains(arg.toLowerCase(Locale.ROOT))) {
            stopMotion(p, group, true);
            return;
        }
        MotionGroup.Motion m = group == null ? null : group.get(arg);
        if (m == null) {
            plugin.messages().send(p, "motion-unknown", Messages.text("name", arg),
                    Messages.text("list", String.join(", ", group == null ? List.of() : group.entries().keySet())));
            return;
        }
        Player target = null;
        if (targetName != null) {
            target = Bukkit.getPlayerExact(targetName);
            if (target == null || (!target.equals(p) && !plugin.service().canTarget(p, target))) {
                plugin.messages().send(p, "player-not-found", Messages.text("player", targetName));
                return;
            }
        }
        plugin.service().performMotion(p, group, m, target, false);
    }

    private static final List<String> STOP_WORDS = List.of("stop", "koniec", "przestan", "przestań");

    /** No argument: a dance stops when one is running, otherwise the first one starts; emotes are listed. */
    private void motionNoArgument(Player p, MotionGroup group) {
        if (group == null || group.entries().isEmpty()) {
            plugin.messages().send(p, "interaction-disabled");
            return;
        }
        if (group.key().equals(InterakcjePlugin.DANCES)) {
            if (!stopMotion(p, group, false)) {
                plugin.service().performMotion(p, group, group.entries().values().iterator().next());
            }
            return;
        }
        plugin.messages().send(p, "emote-list", Messages.text("command", group.command()),
                Messages.text("list", String.join(", ", group.entries().keySet())));
    }

    /** Stops a running dance / emote of the group. */
    private boolean stopMotion(Player p, MotionGroup group, boolean tell) {
        String current = plugin.animations().current(p.getUniqueId(), pl.medivalrpg.interakcje.anim.AnimationService.Kind.SOLO);
        boolean mine = current != null && group != null
                && group.entries().values().stream().anyMatch(m -> current.equals(m.animation()));
        if (mine) {
            plugin.animations().stop(p.getUniqueId(), false);
            plugin.messages().send(p, "motion-stopped");
        } else if (tell) {
            plugin.messages().send(p, "motion-none");
        }
        return mine;
    }

    private SuggestionProvider<CommandSourceStack> motionSuggestions(String key) {
        return (ctx, builder) -> {
            MotionGroup group = plugin.motionGroups().get(key);
            if (group == null) {
                return builder.buildFuture();
            }
            String rem = builder.getRemainingLowerCase();
            for (MotionGroup.Motion m : group.entries().values()) {
                if (m.enabled() && m.id().startsWith(rem)) {
                    Component tip = plugin.messages().parse(m.name());
                    if (m.target()) {
                        tip = tip.append(Component.text(" [gracz]"));
                    }
                    if (!m.description().isEmpty()) {
                        tip = tip.append(Component.text(" – ")).append(plugin.messages().parse(m.description()));
                    }
                    builder.suggest(m.id(), MessageComponentSerializer.message().serialize(tip));
                }
            }
            if ("stop".startsWith(rem)) {
                builder.suggest("stop", MessageComponentSerializer.message().serialize(plugin.messages().get("motion-stop-tooltip")));
            }
            return builder.buildFuture();
        };
    }

    // ------------------------------------------------------------------ /interakcje

    private LiteralCommandNode<CommandSourceStack> rootCommand() {
        Predicate<CommandSourceStack> use = src -> src.getSender().hasPermission(InteractionService.PERM_USE);
        Predicate<CommandSourceStack> admin = src -> src.getSender().hasPermission(PERM_ADMIN);
        return Commands.literal(ROOT)
                .requires(use)
                .executes(this::help)
                .then(Commands.literal("pomoc").executes(this::help))
                .then(Commands.literal("lista").executes(this::list))
                .then(Commands.literal("wlacz").executes(ctx -> withPlayer(ctx, p -> toggle(p, true))))
                .then(Commands.literal("wylacz").executes(ctx -> withPlayer(ctx, p -> toggle(p, false))))
                .then(Commands.literal("blokuj")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.messages().send(p, "usage",
                                Messages.text("command", ROOT + " blokuj"))))
                        .then(Commands.argument("gracz", StringArgumentType.word())
                                .suggests(onlinePlayers())
                                .executes(ctx -> withPlayer(ctx, p -> block(p, StringArgumentType.getString(ctx, "gracz"))))))
                .then(Commands.literal("odblokuj")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.messages().send(p, "usage",
                                Messages.text("command", ROOT + " odblokuj"))))
                        .then(Commands.argument("gracz", StringArgumentType.word())
                                .suggests(blockedPlayers())
                                .executes(ctx -> withPlayer(ctx, p -> unblock(p, StringArgumentType.getString(ctx, "gracz"))))))
                .then(Commands.literal("zablokowani").executes(ctx -> withPlayer(ctx, this::listBlocked)))
                // targets of the clickable [✔ Zgoda] / [✖ Odmowa] buttons
                .then(Commands.literal("akceptuj")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.service().accept(p, null)))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> withPlayer(ctx, p -> plugin.service().accept(p,
                                        IntegerArgumentType.getInteger(ctx, "id"))))))
                .then(Commands.literal("odmow")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.service().deny(p, null)))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> withPlayer(ctx, p -> plugin.service().deny(p,
                                        IntegerArgumentType.getInteger(ctx, "id"))))))
                .then(Commands.literal("pakiet")
                        .executes(ctx -> withPlayer(ctx, p -> plugin.messages().send(p,
                                plugin.resourcePack().send(p) ? "pack-resent" : "pack-not-ready"))))
                .then(Commands.literal("status")
                        .requires(admin)
                        .executes(ctx -> {
                            CommandSender s = ctx.getSource().getSender();
                            plugin.messages().send(s, "status-header");
                            List<String> lines = new ArrayList<>(plugin.animations().status());
                            lines.addAll(plugin.resourcePack().status());
                            lines.addAll(plugin.npcAnimations().status());
                            for (String line : lines) {
                                s.sendMessage(Component.text(" " + line, net.kyori.adventure.text.format.NamedTextColor.GRAY));
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("tytul")
                        .executes(ctx -> withPlayer(ctx, this::showTitle))
                        .then(Commands.argument("wybor", StringArgumentType.word())
                                .suggests((c, b2) -> {
                                    List<String> opts = new ArrayList<>(plugin.ceremonies().config().titles());
                                    opts.add("ukryj");
                                    opts.add("pokaz");
                                    return suggest(b2, opts);
                                })
                                .executes(ctx -> withPlayer(ctx, p -> plugin.ceremonies().chooseTitle(p,
                                        StringArgumentType.getString(ctx, "wybor"))))))
                .then(npcCommand(admin))
                .then(Commands.literal("reload")
                        .requires(admin)
                        .executes(ctx -> {
                            plugin.reloadAll();
                            plugin.messages().send(ctx.getSource().getSender(), "reloaded");
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void showTitle(Player p) {
        pl.medivalrpg.interakcje.PlayerDataStore.Title t = plugin.data().title(p.getUniqueId());
        if (t == null) {
            plugin.messages().send(p, "title-none");
        } else {
            plugin.messages().send(p, "title-info", Messages.text("title", t.title()), Messages.text("by", t.byName()),
                    Messages.text("state", t.hidden() ? "ukryty" : "widoczny"),
                    Messages.text("titles", String.join("|", plugin.ceremonies().config().titles())));
        }
    }

    /** {@code /interakcje npc test|ustaw|stop|usun|status} – work animations of NPCs (admin). */
    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> npcCommand(
            Predicate<CommandSourceStack> admin) {
        SuggestionProvider<CommandSourceStack> works = (c, b) -> suggest(b,
                new ArrayList<>(plugin.npcAnimations().animations()));
        return Commands.literal("npc")
                .requires(admin)
                .executes(ctx -> npcStatus(ctx.getSource().getSender()))
                .then(Commands.literal("status").executes(ctx -> npcStatus(ctx.getSource().getSender())))
                .then(Commands.literal("test")
                        .then(Commands.argument("praca", StringArgumentType.word()).suggests(works)
                                .executes(ctx -> withPlayer(ctx, p -> {
                                    String w = StringArgumentType.getString(ctx, "praca");
                                    if (plugin.animations().catalog().workClip(w) == null) {
                                        plugin.messages().send(p, "npc-unknown", Messages.text("name", w),
                                                Messages.text("list", String.join(", ", plugin.npcAnimations().animations())));
                                        return;
                                    }
                                    plugin.npcAnimations().spawnTest(p, w);
                                    plugin.messages().send(p, "npc-test", Messages.text("name", w));
                                }))))
                .then(Commands.literal("ustaw")
                        .then(Commands.argument("praca", StringArgumentType.word()).suggests(works)
                                .executes(ctx -> withPlayer(ctx, p -> {
                                    Entity e = p.getTargetEntity(8);
                                    String w = StringArgumentType.getString(ctx, "praca");
                                    boolean ok = e != null && plugin.npcAnimations().play(e, w,
                                            plugin.animations().catalog().workClip(w) != null
                                                    && plugin.animations().catalog().workClip(w).loop());
                                    plugin.messages().send(p, ok ? "npc-set" : "npc-not-set", Messages.text("name", w));
                                }))))
                .then(Commands.literal("stop").executes(ctx -> withPlayer(ctx, p -> {
                    Entity e = p.getTargetEntity(8);
                    if (e != null) {
                        plugin.npcAnimations().stop(e);
                    }
                    plugin.messages().send(p, e != null ? "npc-stopped" : "npc-not-set", Messages.text("name", "-"));
                })))
                .then(Commands.literal("usun").executes(ctx -> {
                    int n = plugin.npcAnimations().removeTestNpcs();
                    plugin.messages().send(ctx.getSource().getSender(), "npc-removed", Messages.text("count", Integer.toString(n)));
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private int npcStatus(CommandSender s) {
        plugin.messages().send(s, "status-header");
        for (String line : plugin.npcAnimations().status()) {
            s.sendMessage(Component.text(" " + line, net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        plugin.messages().send(ctx.getSource().getSender(), "help");
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        Messages msg = plugin.messages();
        msg.send(s, "list-header");
        for (InteractionDef d : plugin.interactions().values()) {
            if (d.enabled() && s.hasPermission(d.permission())) {
                sendListEntry(s, d.name(), d.description(), d.command() + (d.targetOptional() ? " [gracz]" : " <gracz>"),
                        "/" + d.command() + " ", d.consent());
            }
        }
        for (PoseDef p : plugin.poseDefs().values()) {
            if (p.enabled() && s.hasPermission(p.permission())) {
                sendListEntry(s, p.name(), p.description(), p.command(), "/" + p.command(), false);
            }
        }
        for (MotionGroup g : plugin.motionGroups().values()) {
            if (!g.enabled() || !s.hasPermission(g.permission())) {
                continue;
            }
            for (MotionGroup.Motion m : g.entries().values()) {
                if (m.enabled()) {
                    String usage = g.command() + " " + m.id();
                    sendListEntry(s, m.name(), m.description(), usage, "/" + usage, false);
                }
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private void sendListEntry(CommandSender s, String name, String description, String usage, String suggest,
                               boolean consent) {
        Messages msg = plugin.messages();
        Component line = msg.get("list-entry",
                Messages.component("name", msg.parse(name)),
                Messages.component("description", msg.parse(description)),
                Messages.text("command", usage),
                Messages.component("consent", consent ? msg.get("list-consent-tag") : Component.empty()));
        line = line.hoverEvent(HoverEvent.showText(msg.get("list-entry-hover", Messages.text("command", usage))))
                .clickEvent(ClickEvent.suggestCommand(suggest));
        s.sendMessage(line);
    }

    private void toggle(Player p, boolean enable) {
        boolean changed = plugin.data().setDisabled(p.getUniqueId(), !enable);
        if (enable) {
            plugin.messages().send(p, changed ? "toggle-on" : "toggle-already-on");
        } else {
            plugin.messages().send(p, changed ? "toggle-off" : "toggle-already-off");
        }
    }

    private void block(Player p, String name) {
        if (name.equalsIgnoreCase(p.getName())) {
            plugin.messages().send(p, "block-self");
            return;
        }
        UUID id;
        String realName;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            id = online.getUniqueId();
            realName = online.getName();
        } else {
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
            if (off == null) {
                plugin.messages().send(p, "block-unknown", Messages.text("player", name));
                return;
            }
            id = off.getUniqueId();
            realName = off.getName() != null ? off.getName() : name;
        }
        boolean added = plugin.data().block(p.getUniqueId(), id, realName);
        plugin.messages().send(p, added ? "block-added" : "block-already", Messages.text("player", realName));
    }

    private void unblock(Player p, String name) {
        UUID id = plugin.data().findBlockedByName(p.getUniqueId(), name);
        if (id == null) {
            plugin.messages().send(p, "block-not-blocked", Messages.text("player", name));
            return;
        }
        String shown = plugin.data().name(id) != null ? plugin.data().name(id) : name;
        plugin.data().unblock(p.getUniqueId(), id);
        plugin.messages().send(p, "block-removed", Messages.text("player", shown));
    }

    private void listBlocked(Player p) {
        List<String> names = plugin.data().blockedNames(p.getUniqueId());
        if (names.isEmpty()) {
            plugin.messages().send(p, "block-list-empty");
        } else {
            plugin.messages().send(p, "block-list", Messages.text("list", String.join(", ", names)));
        }
    }

    // ------------------------------------------------------------------ helpers

    private int withPlayer(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        Entity exec = ctx.getSource().getExecutor();
        if (exec instanceof Player p) {
            action.accept(p);
        } else if (ctx.getSource().getSender() instanceof Player p) {
            action.accept(p);
        } else {
            plugin.messages().send(ctx.getSource().getSender(), "players-only");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static Player senderPlayer(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player p) {
            return p;
        }
        return ctx.getSource().getSender() instanceof Player p ? p : null;
    }

    /**
     * Players within the interaction's range (nearest first, tooltip "2.1 bl."); when nobody is in range,
     * every visible online player. Reads the position snapshot, so it is safe off the main thread.
     */
    private SuggestionProvider<CommandSourceStack> nearbyPlayers(String interactionId) {
        return (ctx, builder) -> {
            Player me = senderPlayer(ctx);
            if (me == null) {
                return builder.buildFuture();
            }
            InteractionDef def = plugin.interaction(interactionId);
            double range = def == null ? 3.0 : def.range();
            PlayerSnapshot.Entry self = plugin.snapshot().find(me.getUniqueId());
            String rem = builder.getRemainingLowerCase();
            boolean main = Bukkit.isPrimaryThread();

            record Candidate(PlayerSnapshot.Entry e, double distance) {
            }
            List<Candidate> near = new ArrayList<>();
            List<Candidate> all = new ArrayList<>();
            for (PlayerSnapshot.Entry e : plugin.snapshot().entries()) {
                if (e.id().equals(me.getUniqueId()) || e.hidden()
                        || !e.name().toLowerCase(Locale.ROOT).startsWith(rem)) {
                    continue;
                }
                if (main) {
                    Player other = Bukkit.getPlayer(e.id());
                    if (other == null || !me.canSee(other)) {
                        continue;
                    }
                }
                double d = self != null && self.world().equals(e.world()) ? Math.sqrt(self.distanceSquared(e)) : Double.NaN;
                Candidate c = new Candidate(e, d);
                all.add(c);
                if (!Double.isNaN(d) && d <= range) {
                    near.add(c);
                }
            }
            List<Candidate> chosen = near.isEmpty() ? all : near;
            chosen.sort(Comparator.comparingDouble(c -> Double.isNaN(c.distance) ? Double.MAX_VALUE : c.distance));
            for (Candidate c : chosen) {
                Component tooltip = !Double.isNaN(c.distance) && c.distance <= range
                        ? plugin.messages().get("suggestion-distance",
                        Messages.text("distance", InteractionService.formatDistance(c.distance)))
                        : plugin.messages().get("suggestion-far");
                builder.suggest(c.e.name(), MessageComponentSerializer.message().serialize(tooltip));
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> onlinePlayers() {
        return (ctx, builder) -> {
            Player me = senderPlayer(ctx);
            List<String> names = new ArrayList<>();
            for (PlayerSnapshot.Entry e : plugin.snapshot().entries()) {
                if (!e.hidden() && (me == null || !e.id().equals(me.getUniqueId()))) {
                    names.add(e.name());
                }
            }
            return suggest(builder, names);
        };
    }

    private SuggestionProvider<CommandSourceStack> blockedPlayers() {
        return (ctx, builder) -> {
            Player me = senderPlayer(ctx);
            return me == null ? builder.buildFuture() : suggest(builder, plugin.data().blockedNames(me.getUniqueId()));
        };
    }

    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, List<String> options) {
        String rem = builder.getRemainingLowerCase();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(rem)) {
                builder.suggest(o);
            }
        }
        return builder.buildFuture();
    }
}
