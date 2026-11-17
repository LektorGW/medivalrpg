// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import pl.medivalrpg.kraje.KrajePlugin;

/** {@code /mapa} (alias {@code /mapaswiata}): sends the clickable link to the web map ({@code map-url}). */
public final class MapLinkCommand {

    private final KrajePlugin plugin;

    public MapLinkCommand(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    /** Brigadier node {@code /mapa} (no arguments). */
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("mapa")
            .requires(s -> s.getSender().hasPermission(KrajCommandTree.PERM_USE))
            .executes(ctx -> {
                this.send(ctx.getSource().getSender());
                return Command.SINGLE_SUCCESS;
            })
            .build();
    }

    private void send(final CommandSender sender) {
        final String url = this.plugin.cfg().mapUrl;
        if (url.isEmpty()) {
            this.plugin.messages().send(sender, "map-link-missing");
            return;
        }
        this.plugin.messages().send(sender, "map-link",
            Placeholder.unparsed("url", url),
            Placeholder.styling("link", ClickEvent.openUrl(url)));
    }
}
