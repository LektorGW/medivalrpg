// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import pl.medivalrpg.menu.MenuPlugin;
import pl.medivalrpg.menu.tutorial.TutorialService;

/**
 * {@code /samouczek} – the new-player tutorial:
 *
 * <ul>
 *   <li>{@code /samouczek} – opens the tutorial menu (the same as {@code /menu samouczek}),</li>
 *   <li>{@code /samouczek ukryj} – no more reminders after login ("nie pokazuj"),</li>
 *   <li>{@code /samouczek przypominaj} – reminders on again.</li>
 * </ul>
 */
public final class TutorialCommand {

    private final MenuPlugin plugin;

    public TutorialCommand(final MenuPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("samouczek")
            .requires(s -> s.getSender().hasPermission(MenuCommand.PERM_USE))
            .executes(ctx -> this.player(ctx, p -> {
                if (!this.plugin.tutorial().available()) {
                    p.sendMessage(this.plugin.messages().get("tutorial-unavailable"));
                    return;
                }
                this.plugin.menus().open(p, TutorialService.MENU);
            }))
            .then(Commands.literal("ukryj")
                .executes(ctx -> this.player(ctx, p -> this.plugin.tutorial().setDismissed(p, true))))
            .then(Commands.literal("przypominaj")
                .executes(ctx -> this.player(ctx, p -> this.plugin.tutorial().setDismissed(p, false))))
            .build();
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
