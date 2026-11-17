// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.menu.MenuPlugin;
import pl.medivalrpg.menu.help.HelpCatalog;
import pl.medivalrpg.menu.help.HelpService;

/**
 * {@code /pomoc} – help center for players (data: {@code pomoc.yml}):
 *
 * <ul>
 *   <li>{@code /pomoc} – the help GUI with categories (console: the list on chat),</li>
 *   <li>{@code /pomoc <kategoria>} – one category on chat: "how to start" and clickable commands,</li>
 *   <li>{@code /pomoc lista} – all categories on chat,</li>
 *   <li>{@code /pomoc szukaj <fraza>} – search in all commands the player may use.</li>
 * </ul>
 */
public final class HelpCommand {

    private final MenuPlugin plugin;

    public HelpCommand(final MenuPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("pomoc")
            .requires(s -> s.getSender().hasPermission(MenuCommand.PERM_USE))
            .executes(ctx -> {
                final CommandSender sender = ctx.getSource().getSender();
                if (ctx.getSource().getExecutor() instanceof Player p) {
                    this.plugin.menus().open(p, HelpService.HUB_MENU);
                } else {
                    this.plugin.help().sendOverview(sender);
                }
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.literal("lista")
                .executes(ctx -> {
                    this.plugin.help().sendOverview(this.target(ctx.getSource()));
                    return Command.SINGLE_SUCCESS;
                }))
            .then(Commands.literal("szukaj")
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(this.plugin.messages().get("help-search-usage"));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("fraza", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        this.plugin.help().sendSearch(this.target(ctx.getSource()),
                            StringArgumentType.getString(ctx, "fraza"));
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.argument("kategoria", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    final CommandSender sender = ctx.getSource().getSender();
                    final boolean main = Bukkit.isPrimaryThread();
                    for (final HelpCatalog.Category c : this.plugin.help().catalog().categories().values()) {
                        if (main && !this.plugin.help().categoryIds(sender).contains(c.id())) {
                            continue;
                        }
                        if (c.id().startsWith(builder.getRemainingLowerCase())) {
                            builder.suggest(c.id(), MessageComponentSerializer.message().serialize(
                                Component.text(c.name() + " – " + c.description(), NamedTextColor.GRAY)));
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(ctx -> {
                    this.plugin.help().sendCategory(this.target(ctx.getSource()),
                        StringArgumentType.getString(ctx, "kategoria"));
                    return Command.SINGLE_SUCCESS;
                }))
            .build();
    }

    /** The player who runs the command (also via /execute as), else the sender. */
    private CommandSender target(final CommandSourceStack source) {
        return source.getExecutor() instanceof Player p ? p : source.getSender();
    }
}
