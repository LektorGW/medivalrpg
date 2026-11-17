// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import pl.medivalrpg.menu.MenuPlugin;
import pl.medivalrpg.menu.home.HomeService;

/**
 * Teleport commands (Paper Brigadier):
 *
 * <ul>
 *   <li>{@code /spawn} ({@code /sp}) – countdown teleport to the server spawn,</li>
 *   <li>{@code /ustawspawn} – admin: saves the spawn here (also the world spawn); {@code zbuduj [noair]},
 *       {@code zbuduj potwierdz}, {@code cofnij} – builds the spawn town from the bundled schematic,</li>
 *   <li>{@code /powrot} ({@code /back}) – once after joining: back to the last position,</li>
 *   <li>{@code /ustawdom [nazwa]}, {@code /dom [nazwa]}, {@code /usundom <nazwa>}, {@code /domy} – player homes.</li>
 * </ul>
 */
public final class TeleportCommands {

    public static final String PERM_SPAWN = "menu.spawn";

    private final MenuPlugin plugin;

    public TeleportCommands(final MenuPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> spawn() {
        return Commands.literal("spawn")
            .requires(s -> s.getSender().hasPermission(PERM_SPAWN))
            .executes(ctx -> this.player(ctx, p -> this.plugin.spawn().teleport(p)))
            .build();
    }

    public LiteralCommandNode<CommandSourceStack> setSpawn() {
        return Commands.literal("ustawspawn")
            .requires(s -> s.getSender().hasPermission(MenuCommand.PERM_ADMIN))
            .executes(ctx -> this.player(ctx, this.plugin::setSpawnHere))
            .then(Commands.literal("zbuduj")
                .executes(ctx -> this.player(ctx, p -> this.plugin.builder().prepare(p, false)))
                .then(Commands.literal("noair")
                    .executes(ctx -> this.player(ctx, p -> this.plugin.builder().prepare(p, true))))
                .then(Commands.literal("potwierdz")
                    .executes(ctx -> this.player(ctx, p -> this.plugin.builder().confirm(p)))))
            .then(Commands.literal("cofnij")
                .executes(ctx -> this.player(ctx, p -> this.plugin.builder().undo(p))))
            .build();
    }

    public LiteralCommandNode<CommandSourceStack> returnBack() {
        return Commands.literal("powrot")
            .requires(s -> s.getSender().hasPermission(PERM_SPAWN))
            .executes(ctx -> this.player(ctx, p -> this.plugin.spawn().returnBack(p)))
            .build();
    }

    public LiteralCommandNode<CommandSourceStack> home() {
        return Commands.literal("dom")
            .requires(s -> s.getSender().hasPermission(HomeService.PERM_USE))
            .executes(ctx -> this.player(ctx, p -> this.plugin.homes().teleport(p, null)))
            .then(Commands.argument("nazwa", StringArgumentType.word())
                .suggests(this.homeNames("Teleport do domu "))
                .executes(ctx -> this.player(ctx, p ->
                    this.plugin.homes().teleport(p, StringArgumentType.getString(ctx, "nazwa")))))
            .build();
    }

    public LiteralCommandNode<CommandSourceStack> setHome() {
        return Commands.literal("ustawdom")
            .requires(s -> s.getSender().hasPermission(HomeService.PERM_USE))
            .executes(ctx -> this.player(ctx, p -> this.plugin.homes().set(p, null)))
            .then(Commands.argument("nazwa", StringArgumentType.word())
                .suggests(this.homeNames("Nadpisz dom "))
                .executes(ctx -> this.player(ctx, p ->
                    this.plugin.homes().set(p, StringArgumentType.getString(ctx, "nazwa")))))
            .build();
    }

    public LiteralCommandNode<CommandSourceStack> deleteHome() {
        return Commands.literal("usundom")
            .requires(s -> s.getSender().hasPermission(HomeService.PERM_USE))
            .then(Commands.argument("nazwa", StringArgumentType.word())
                .suggests(this.homeNames("Usuń dom "))
                .executes(ctx -> this.player(ctx, p ->
                    this.plugin.homes().delete(p, StringArgumentType.getString(ctx, "nazwa")))))
            .build();
    }

    public LiteralCommandNode<CommandSourceStack> listHomes() {
        return Commands.literal("domy")
            .requires(s -> s.getSender().hasPermission(HomeService.PERM_USE))
            .executes(ctx -> this.player(ctx, p -> this.plugin.homes().list(p)))
            .build();
    }

    private SuggestionProvider<CommandSourceStack> homeNames(final String tooltip) {
        return (ctx, builder) -> {
            if (ctx.getSource().getExecutor() instanceof Player p) {
                for (final String name : this.plugin.homes().names(p)) {
                    if (name.startsWith(builder.getRemainingLowerCase())) {
                        builder.suggest(name, MessageComponentSerializer.message().serialize(
                            Component.text(tooltip + name, NamedTextColor.GRAY)));
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    private int player(final CommandContext<CommandSourceStack> ctx, final Consumer<Player> action) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(this.plugin.messages().get("players-only"));
            return 0;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
