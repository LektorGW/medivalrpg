// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.listener;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Relation;

/**
 * Shows "Wkraczasz do: ..." / "Dzicz" when a player crosses a border. The country lookup only runs when
 * the player's chunk changes, not on every head movement.
 */
public final class BorderListener implements Listener {

    private static final UUID WILDERNESS = new UUID(0L, 0L);

    private final KrajePlugin plugin;
    private final Map<UUID, UUID> lastArea = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastSound = new HashMap<>();
    private final java.util.Set<String> badSounds = new java.util.HashSet<>();

    public BorderListener(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Location from = event.getFrom();
        final Location to = event.getTo();
        if (from.getBlockX() >> 4 == to.getBlockX() >> 4 && from.getBlockZ() >> 4 == to.getBlockZ() >> 4
            && from.getWorld() == to.getWorld()) {
            return;
        }
        this.update(event.getPlayer(), to, true);
    }

    /** Players riding horses / boats / minecarts do not fire PlayerMoveEvent. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(final VehicleMoveEvent event) {
        final Location from = event.getFrom();
        final Location to = event.getTo();
        if (from.getBlockX() >> 4 == to.getBlockX() >> 4 && from.getBlockZ() >> 4 == to.getBlockZ() >> 4
            && from.getWorld() == to.getWorld()) {
            return;
        }
        for (final Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                this.update(player, to, true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        this.update(event.getPlayer(), event.getTo(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.update(event.getPlayer(), event.getPlayer().getLocation(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.lastArea.remove(event.getPlayer().getUniqueId());
        this.lastSound.remove(event.getPlayer().getUniqueId());
    }

    private void update(final Player player, final Location to, final boolean notify) {
        final KrajeConfig cfg = this.plugin.cfg();
        if (!cfg.borderEnabled || to == null || to.getWorld() == null) {
            return;
        }
        final UUID owner = Objects.requireNonNullElse(this.plugin.manager().ownerAt(to), WILDERNESS);
        final UUID previous = this.lastArea.put(player.getUniqueId(), owner);
        if (!notify || previous == null || previous.equals(owner)) {
            return;
        }
        final Country entered = this.plugin.manager().get(owner);
        final Country left = this.plugin.manager().get(previous);
        final Messages messages = this.plugin.messages();
        if (entered != null) {
            final ChunkPos pos = ChunkPos.of(to);
            final boolean sieged = this.plugin.sieges() != null && this.plugin.sieges().siegeAt(pos) != null;
            final UUID own = this.plugin.manager().countryIdOf(player.getUniqueId());
            final Relation relation = this.plugin.manager().relation(own, entered.id());
            final Country ownCountry = this.plugin.manager().get(own);
            final boolean overlord = ownCountry != null && entered.id().equals(ownCountry.overlord());
            final boolean vassal = own != null && own.equals(entered.overlord());
            final boolean union = own != null && entered.id().equals(this.plugin.manager().unionPartnerId(own));
            final String relationKey = entered.id().equals(own) ? "border-relation-own"
                : relation == Relation.WAR ? "border-relation-war"
                : union ? "border-relation-union"
                : overlord ? "border-relation-overlord"
                : vassal ? "border-relation-vassal"
                : relation == Relation.ALLIANCE ? "border-relation-ally" : "border-relation-neutral";
            final TagResolver base = TagResolver.resolver(
                Placeholder.unparsed("country", pl.medivalrpg.kraje.manager.Notify.displayName(entered)), Placeholder.styling("color", entered.textColor()),
                Placeholder.unparsed("ruler", entered.rulerName()));
            final TagResolver r = TagResolver.resolver(base,
                Placeholder.component("relation", messages.get(relationKey, base)),
                Placeholder.component("npc", entered.npc() ? messages.get("border-npc", base) : Component.empty()),
                Placeholder.component("siege", sieged ? messages.get("border-siege", base) : Component.empty()));
            this.showEnter(player, cfg, messages, r);
            final boolean enemy = sieged || relation == Relation.WAR;
            this.playSound(player, cfg, enemy);
        } else {
            final TagResolver r = left == null
                ? TagResolver.resolver(Placeholder.unparsed("country", "?"), Placeholder.styling("color", net.kyori.adventure.text.format.NamedTextColor.GRAY))
                : TagResolver.resolver(Placeholder.unparsed("country", pl.medivalrpg.kraje.manager.Notify.displayName(left)), Placeholder.styling("color", left.textColor()));
            this.show(player, cfg, messages, "border-wild-title", "border-wild-subtitle", "border-wild-actionbar", r);
        }
    }

    /** Title (country) + subtitle with ruler, relation, NPC mark and siege; in ACTIONBAR mode one line. */
    private void showEnter(final Player player, final KrajeConfig cfg, final Messages messages, final TagResolver r) {
        final String detailsRaw = messages.raw("border-enter-details");
        final Component details = detailsRaw.isEmpty() ? Component.empty() : messages.parse(detailsRaw, r);
        if (cfg.borderMode == KrajeConfig.BorderMode.ACTIONBAR) {
            Component line = messages.get("border-enter-actionbar", r);
            if (!detailsRaw.isEmpty()) {
                line = line.append(messages.get("border-actionbar-separator")).append(details);
            }
            player.sendActionBar(line);
            return;
        }
        final Component subtitle = detailsRaw.isEmpty() ? messages.get("border-enter-subtitle", r) : details;
        player.showTitle(Title.title(messages.get("border-enter-title", r), subtitle, Title.Times.times(
            Duration.ofMillis(cfg.titleFadeIn * 50L), Duration.ofMillis(cfg.titleStay * 50L), Duration.ofMillis(cfg.titleFadeOut * 50L))));
    }

    /** Quiet chime, or a more ominous sound for enemy / besieged land; each at most once per cooldown. */
    private void playSound(final Player player, final KrajeConfig cfg, final boolean enemy) {
        final String key = enemy ? cfg.borderSoundEnemy : cfg.borderSoundEnter;
        if (key == null || key.isBlank()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Map<String, Long> played = this.lastSound.computeIfAbsent(player.getUniqueId(), u -> new HashMap<>());
        final Long last = played.get(key);
        if (last != null && now - last < cfg.borderSoundCooldownMillis) {
            return;
        }
        played.put(key, now);
        try {
            player.playSound(Sound.sound(Key.key(key.trim().toLowerCase(java.util.Locale.ROOT)), Sound.Source.AMBIENT,
                enemy ? cfg.borderSoundEnemyVolume : cfg.borderSoundEnterVolume,
                enemy ? cfg.borderSoundEnemyPitch : cfg.borderSoundEnterPitch));
        } catch (final InvalidKeyException e) {
            if (this.badSounds.add(key)) {
                this.plugin.getLogger().warning("border.sound: nieprawidłowy klucz dźwięku " + key);
            }
        }
    }

    private void show(final Player player, final KrajeConfig cfg, final Messages messages,
                      final String titleKey, final String subtitleKey, final String actionBarKey, final TagResolver r) {
        if (cfg.borderMode == KrajeConfig.BorderMode.ACTIONBAR) {
            player.sendActionBar(messages.get(actionBarKey, r));
            return;
        }
        final Component title = messages.get(titleKey, r);
        final Component subtitle = messages.get(subtitleKey, r);
        player.showTitle(Title.title(title, subtitle, Title.Times.times(
            Duration.ofMillis(cfg.titleFadeIn * 50L), Duration.ofMillis(cfg.titleStay * 50L), Duration.ofMillis(cfg.titleFadeOut * 50L))));
    }
}
