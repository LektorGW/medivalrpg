// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.listener;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.util.Format;

/**
 * Chat integration.
 *
 * <ul>
 *   <li>Public chat: only {@link AsyncChatEvent#renderer(ChatRenderer)} is replaced, the event is never
 *       cancelled, so signed chat and other plugins keep working. The player's message is inserted as the
 *       event's {@link Component}, never parsed as MiniMessage.</li>
 *   <li>Country / alliance channels: the event is cancelled and the message is delivered on the main
 *       thread to members (and allies), spying admins and the console.</li>
 * </ul>
 *
 * <p>AsyncChatEvent runs off the main thread, so only the manager's thread-safe snapshots are read there.</p>
 */
public final class ChatService implements Listener {

    public enum Channel { COUNTRY, ALLIANCE }

    private final KrajePlugin plugin;
    private final Set<UUID> countryChatMode = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spies = ConcurrentHashMap.newKeySet();

    public ChatService(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean toggleCountryChat(final UUID player) {
        if (this.countryChatMode.remove(player)) {
            return false;
        }
        this.countryChatMode.add(player);
        return true;
    }

    public void disableCountryChat(final UUID player) {
        this.countryChatMode.remove(player);
    }

    public boolean toggleSpy(final UUID player) {
        if (this.spies.remove(player)) {
            return false;
        }
        this.spies.add(player);
        return true;
    }

    public void clear() {
        this.countryChatMode.clear();
        this.spies.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final KrajeConfig cfg = this.plugin.cfg();
        if (!cfg.chatEnabled) {
            return;
        }
        final Player player = event.getPlayer();
        if (this.countryChatMode.contains(player.getUniqueId())) {
            final KrajInfo info = this.plugin.manager().infoOf(player.getUniqueId());
            if (info != null) {
                event.setCancelled(true);
                final Component message = event.message();
                Bukkit.getScheduler().runTask(this.plugin, () -> this.sendChannel(player, Channel.COUNTRY, message));
                return;
            }
            this.countryChatMode.remove(player.getUniqueId());
        }
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> this.renderPublic(source, displayName, message)));
    }

    private Component renderPublic(final Player source, final Component displayName, final Component message) {
        final KrajeConfig cfg = this.plugin.cfg();
        final Messages messages = this.plugin.messages();
        final KrajInfo info = this.plugin.manager().infoOf(source.getUniqueId());
        final Rank rank = info == null ? null : info.rankOf(source.getUniqueId());
        return messages.parse(cfg.chatFormat,
            Placeholder.component("country_tag", this.countryTag(info)),
            Placeholder.component("player", displayName),
            Placeholder.component("message", message),
            Placeholder.parsed("rank_icon", cfg.rankIcon(rank)),
            Placeholder.unparsed("rank", rank == null ? "" : rank.displayName()));
    }

    /** Coloured country tag with hover details and a click running {@code /kraj info <country>}. */
    public Component countryTag(final KrajInfo info) {
        final KrajeConfig cfg = this.plugin.cfg();
        final Messages messages = this.plugin.messages();
        if (info == null) {
            return messages.parse(cfg.chatNoCountryTag);
        }
        final TagResolver r = TagResolver.resolver(
            Placeholder.unparsed("country", this.plugin.displayName(info.id(), info.name(), info.npc())),
            Placeholder.styling("color", info.color()),
            Placeholder.unparsed("ruler", info.rulerName()),
            Placeholder.unparsed("members", Integer.toString(info.memberCount())),
            Placeholder.unparsed("area", Format.area(info.chunkCount())));
        final Component hover = messages.parse(cfg.chatTagHover, r);
        final String command = cfg.chatTagClickCommand.replace("<country>", info.name());
        return messages.parse(cfg.chatCountryTag, r)
            .hoverEvent(HoverEvent.showText(hover))
            .clickEvent(ClickEvent.runCommand(command));
    }

    /** Sends a country / alliance channel message. Main thread only. */
    public void sendChannel(final Player sender, final Channel channel, final Component message) {
        if (!sender.isOnline()) {
            return;
        }
        final KrajeConfig cfg = this.plugin.cfg();
        final Messages messages = this.plugin.messages();
        final KrajInfo info = this.plugin.manager().infoOf(sender.getUniqueId());
        if (info == null) {
            messages.send(sender, "not-in-country");
            return;
        }
        final Rank rank = info.rankOf(sender.getUniqueId());
        final Component formatted = messages.parse(channel == Channel.COUNTRY ? cfg.chatCountryFormat : cfg.chatAllyFormat,
            Placeholder.unparsed("country", this.plugin.displayName(info.id(), info.name(), info.npc())),
            Placeholder.styling("color", info.color()),
            Placeholder.component("player", sender.displayName()),
            Placeholder.component("message", message),
            Placeholder.parsed("rank_icon", cfg.rankIcon(rank)),
            Placeholder.unparsed("rank", rank == null ? "" : rank.displayName()));

        final Set<UUID> countries = new LinkedHashSet<>();
        countries.add(info.id());
        if (channel == Channel.ALLIANCE) {
            countries.addAll(info.allies());
        }
        final Set<UUID> delivered = new LinkedHashSet<>();
        for (final Player online : Bukkit.getOnlinePlayers()) {
            final UUID countryId = this.plugin.manager().countryIdOf(online.getUniqueId());
            if (countryId != null && countries.contains(countryId)) {
                online.sendMessage(formatted);
                delivered.add(online.getUniqueId());
            }
        }
        final Component spyCopy = messages.parse(cfg.chatSpyPrefix).append(formatted);
        for (final UUID spy : this.spies) {
            if (delivered.contains(spy)) {
                continue;
            }
            final Player p = Bukkit.getPlayer(spy);
            if (p != null && p.hasPermission("kraje.admin")) {
                p.sendMessage(spyCopy);
            }
        }
        if (cfg.chatLogToConsole) {
            final Audience console = Bukkit.getConsoleSender();
            console.sendMessage(spyCopy);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        // Spy and channel modes are kept for the session only.
        this.countryChatMode.remove(event.getPlayer().getUniqueId());
    }
}
