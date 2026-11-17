// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * One-shot chat prompts ("type the amount in chat"). The next chat message of the player is swallowed (never
 * broadcast) and handed to the callback on the main thread. Expires after a timeout.
 */
public final class ChatInput implements Listener {

    private record Pending(long expiresAt, Consumer<String> callback) {
    }

    private final Plugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatInput(final Plugin plugin) {
        this.plugin = plugin;
    }

    public void ask(final Player player, final int timeoutSeconds, final Consumer<String> callback) {
        this.pending.put(player.getUniqueId(), new Pending(System.currentTimeMillis() + timeoutSeconds * 1000L, callback));
    }

    public void cancel(final UUID player) {
        this.pending.remove(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(final AsyncChatEvent event) {
        final Pending p = this.pending.remove(event.getPlayer().getUniqueId());
        if (p == null) {
            return;
        }
        if (p.expiresAt() < System.currentTimeMillis()) {
            return; // expired: normal chat message
        }
        event.setCancelled(true);
        final String text = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        final UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                p.callback().accept(text);
            }
        });
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.pending.remove(event.getPlayer().getUniqueId());
    }

    public void clear() {
        this.pending.clear();
    }
}
