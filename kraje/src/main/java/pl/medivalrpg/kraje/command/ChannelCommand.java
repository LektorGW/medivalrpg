// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.listener.ChatService;

/**
 * {@code /kc <wiadomość>} (country chat) and {@code /ks <wiadomość>} (alliance chat). The text is used as a
 * plain {@link Component}; it is never parsed as MiniMessage.
 */
public final class ChannelCommand {

    private final KrajePlugin plugin;
    private final ChatService.Channel channel;

    public ChannelCommand(final KrajePlugin plugin, final ChatService.Channel channel) {
        this.plugin = plugin;
        this.channel = channel;
    }

    /** Brigadier node {@code /<name> <wiadomość>} (greedy). */
    public LiteralCommandNode<CommandSourceStack> build(final String name) {
        return Commands.literal(name)
            .requires(s -> s.getSender().hasPermission(KrajCommandTree.PERM_USE))
            .executes(ctx -> {
                this.send(ctx.getSource().getSender(), null);
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.argument("wiadomość", StringArgumentType.greedyString())
                .executes(ctx -> {
                    this.send(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "wiadomość"));
                    return Command.SINGLE_SUCCESS;
                }))
            .build();
    }

    private void send(final CommandSender sender, final String message) {
        if (!(sender instanceof Player player)) {
            this.plugin.messages().send(sender, "players-only");
            return;
        }
        if (!this.plugin.cfg().chatEnabled) {
            this.plugin.messages().send(sender, "chat-disabled");
            return;
        }
        if (message == null || message.isBlank()) {
            this.plugin.messages().send(sender, this.channel == ChatService.Channel.COUNTRY ? "usage-kc" : "usage-ks");
            return;
        }
        final String text = message.replaceAll("[\\p{Cntrl}§]", "");
        this.plugin.chat().sendChannel(player, this.channel, Component.text(text));
    }
}
