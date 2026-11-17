// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.config.Messages;
import pl.medivalrpg.menu.integration.KrajeHook;

/**
 * Countdown teleports shared by {@code /spawn} and {@code /dom}.
 *
 * <ol>
 *   <li>Without the bypass permission: cooldown, PvP combat tag and a running siege on the player's chunk
 *       ({@code KrajeApi#getSiege}) block the request.</li>
 *   <li>The countdown is shown in the action bar with portal particles; moving more than
 *       {@code teleport.max-move} blocks, taking damage or hitting a player cancels it.</li>
 *   <li>At the end the destination is resolved again (a home may have been deleted or conquered), its chunk is
 *       loaded asynchronously and the player is moved with {@link Player#teleportAsync}.</li>
 * </ol>
 * Main thread only.
 */
public final class TeleportService implements Listener {

    /** Result of resolving a destination. */
    public sealed interface Target permits Target.Ok, Target.Denied {
        record Ok(Location location) implements Target {
        }

        record Denied(Component message) implements Target {
        }
    }

    /** Resolves where the player goes; called at the start and again at the end of the countdown. */
    @FunctionalInterface
    public interface Destination {
        Target resolve(Player player);
    }

    /**
     * One kind of teleport.
     *
     * @param id       cooldown key ("spawn", "home")
     * @param warmup   countdown seconds (0 = instant)
     * @param cooldown seconds after a successful teleport
     * @param bypass   permission skipping countdown, cooldown, combat and siege checks
     */
    public record Kind(String id, int warmup, int cooldown, String bypass) {
    }

    private static final long TICK_PERIOD = 5L;

    private final Plugin plugin;
    private final Supplier<MenuSettings> settings;
    private final Supplier<Messages> messages;
    private final KrajeHook kraje;
    private final Cooldowns cooldowns = new Cooldowns();
    private final CombatTracker combat = new CombatTracker();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> inFlight = new HashSet<>();

    private final class Pending {
        final Kind kind;
        final Warmup warmup;
        final Destination destination;
        final Component success;
        final Runnable onArrive;
        BukkitTask task;
        int shownSeconds = -1;

        Pending(final Kind kind, final Warmup warmup, final Destination destination, final Component success,
                final Runnable onArrive) {
            this.kind = kind;
            this.warmup = warmup;
            this.destination = destination;
            this.success = success;
            this.onArrive = onArrive;
        }
    }

    public TeleportService(final Plugin plugin, final Supplier<MenuSettings> settings,
                           final Supplier<Messages> messages, final KrajeHook kraje) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.kraje = kraje;
    }

    /**
     * Starts a teleport. A previous pending teleport of the player is replaced.
     *
     * @param success message after arriving ({@code null} = none)
     */
    public void request(final Player player, final Kind kind, final Destination destination, final Component success) {
        this.request(player, kind, destination, success, null);
    }

    /** As {@link #request(Player, Kind, Destination, Component)}; {@code onArrive} runs after a successful teleport. */
    public void request(final Player player, final Kind kind, final Destination destination, final Component success,
                        final Runnable onArrive) {
        final UUID id = player.getUniqueId();
        this.cancel(id);
        if (this.inFlight.contains(id)) {
            return;
        }
        final boolean bypass = player.hasPermission(kind.bypass());
        if (!bypass) {
            final long cd = this.cooldowns.remainingMs(id, kind.id(), System.currentTimeMillis());
            if (cd > 0) {
                player.sendMessage(this.messages.get().get("teleport-cooldown",
                    Placeholder.unparsed("seconds", Long.toString(Cooldowns.seconds(cd)))));
                return;
            }
            final Component blocked = this.blocked(player);
            if (blocked != null) {
                player.sendMessage(blocked);
                return;
            }
        }
        final Target target = destination.resolve(player);
        if (target instanceof Target.Denied denied) {
            player.sendMessage(denied.message());
            return;
        }
        final int seconds = bypass ? 0 : kind.warmup();
        if (seconds <= 0) {
            this.execute(player, ((Target.Ok) target).location(), kind, success, onArrive);
            return;
        }
        final Location here = player.getLocation();
        final Pending p = new Pending(kind, new Warmup(here.getWorld().getName(), here.getX(), here.getY(), here.getZ(),
            this.settings.get().teleportMaxMove, System.currentTimeMillis(), seconds), destination, success, onArrive);
        this.pending.put(id, p);
        player.sendMessage(this.messages.get().get("teleport-start",
            Placeholder.unparsed("seconds", Integer.toString(seconds))));
        p.task = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> this.tick(player, p), 0L, TICK_PERIOD);
    }

    /** Combat tag or siege message, {@code null} when the player may teleport. */
    private Component blocked(final Player player) {
        final MenuSettings s = this.settings.get();
        final long tag = this.combat.remainingMs(player.getUniqueId(), s.combatTagSeconds * 1000L,
            System.currentTimeMillis());
        if (tag > 0) {
            return this.messages.get().get("teleport-combat",
                Placeholder.unparsed("seconds", Long.toString(Cooldowns.seconds(tag))));
        }
        if (s.blockDuringSiege) {
            final Location l = player.getLocation();
            if (this.kraje.siegeAt(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4)) {
                return this.messages.get().get("teleport-siege");
            }
        }
        return null;
    }

    private void tick(final Player player, final Pending p) {
        if (this.pending.get(player.getUniqueId()) != p) {
            p.task.cancel();
            return;
        }
        if (!player.isOnline()) {
            this.cancel(player.getUniqueId());
            return;
        }
        final long now = System.currentTimeMillis();
        final MenuSettings s = this.settings.get();
        if (p.warmup.finished(now)) {
            this.cancel(player.getUniqueId());
            final Component blocked = this.blocked(player);
            if (blocked != null) {
                player.sendActionBar(Component.empty());
                player.sendMessage(blocked);
                return;
            }
            final Target target = p.destination.resolve(player);
            if (target instanceof Target.Denied denied) {
                player.sendActionBar(Component.empty());
                player.sendMessage(denied.message());
                return;
            }
            this.execute(player, ((Target.Ok) target).location(), p.kind, p.success, p.onArrive);
            return;
        }
        final int left = p.warmup.secondsLeft(now);
        if (left != p.shownSeconds) {
            p.shownSeconds = left;
            player.sendActionBar(this.messages.get().get("teleport-actionbar",
                Placeholder.unparsed("seconds", Integer.toString(left))));
            sound(player, s.teleportSoundTick, 0.5f, 1.0f + 0.1f * Math.max(0, 5 - left));
        }
        if (s.teleportParticles) {
            player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 24, 0.4, 0.8, 0.4, 0.3);
        }
    }

    /** Loads the destination chunk asynchronously, then {@link Player#teleportAsync}. */
    private void execute(final Player player, final Location to, final Kind kind, final Component success,
                         final Runnable onArrive) {
        final UUID id = player.getUniqueId();
        final World world = to.getWorld();
        if (world == null) {
            player.sendMessage(this.messages.get().get("teleport-failed"));
            return;
        }
        this.inFlight.add(id);
        world.getChunkAtAsync(to).whenComplete((chunk, error) -> sync(() -> {
            if (error != null || !player.isOnline()) {
                this.inFlight.remove(id);
                if (error != null) {
                    player.sendMessage(this.messages.get().get("teleport-failed"));
                }
                return;
            }
            player.teleportAsync(to, PlayerTeleportEvent.TeleportCause.COMMAND).whenComplete((ok, err) -> sync(() -> {
                this.inFlight.remove(id);
                if (err != null || !Boolean.TRUE.equals(ok)) {
                    if (player.isOnline()) {
                        player.sendMessage(this.messages.get().get("teleport-failed"));
                    }
                    return;
                }
                this.cooldowns.start(id, kind.id(), kind.cooldown(), System.currentTimeMillis());
                final MenuSettings s = this.settings.get();
                player.sendActionBar(Component.empty());
                sound(player, s.teleportSoundDone, 0.8f, 1.0f);
                if (s.teleportParticles) {
                    to.getWorld().spawnParticle(Particle.PORTAL, to.clone().add(0, 1, 0), 60, 0.5, 1.0, 0.5, 0.5);
                }
                if (success != null) {
                    player.sendMessage(success);
                }
                if (onArrive != null) {
                    onArrive.run();
                }
            }));
        }));
    }

    /** Teleport without countdown or checks (join, new players). Chunk is loaded first. */
    public void teleportNow(final Player player, final Location to) {
        this.teleportNow(player, to, null);
    }

    /** As {@link #teleportNow(Player, Location)}; {@code done} gets {@code true} after a successful teleport. */
    public void teleportNow(final Player player, final Location to, final java.util.function.Consumer<Boolean> done) {
        final World world = to.getWorld();
        if (world == null) {
            if (done != null) {
                done.accept(false);
            }
            return;
        }
        world.getChunkAtAsync(to).whenComplete((chunk, error) -> sync(() -> {
            if (error != null || !player.isOnline()) {
                if (done != null) {
                    done.accept(false);
                }
                return;
            }
            player.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, err) -> sync(() -> {
                if (done != null) {
                    done.accept(err == null && Boolean.TRUE.equals(ok));
                }
            }));
        }));
    }

    private void sync(final Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else if (this.plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(this.plugin, r);
        }
    }

    /** Cancels a pending countdown silently; {@code true} when there was one. */
    public boolean cancel(final UUID player) {
        final Pending p = this.pending.remove(player);
        if (p == null) {
            return false;
        }
        if (p.task != null) {
            p.task.cancel();
        }
        return true;
    }

    private void cancelWith(final Player player, final String messageKey) {
        if (this.cancel(player.getUniqueId())) {
            player.sendActionBar(Component.empty());
            player.sendMessage(this.messages.get().get(messageKey));
            sound(player, "block.note_block.bass", 0.6f, 0.8f);
        }
    }

    public boolean isPending(final UUID player) {
        return this.pending.containsKey(player);
    }

    public void stop() {
        for (final UUID id : Set.copyOf(this.pending.keySet())) {
            this.cancel(id);
        }
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        final Pending p = this.pending.get(event.getPlayer().getUniqueId());
        final Location to = event.getTo();
        if (p != null && p.warmup.movedTooFar(to.getWorld().getName(), to.getX(), to.getY(), to.getZ())) {
            this.cancelWith(event.getPlayer(), "teleport-cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOtherTeleport(final PlayerTeleportEvent event) {
        final Pending p = this.pending.get(event.getPlayer().getUniqueId());
        final Location to = event.getTo();
        if (p != null && p.warmup.movedTooFar(to.getWorld().getName(), to.getX(), to.getY(), to.getZ())) {
            this.cancelWith(event.getPlayer(), "teleport-cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0) {
            this.cancelWith(player, "teleport-cancelled-damage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvp(final EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        final Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        this.combat.hit(attacker.getUniqueId(), victim.getUniqueId(), System.currentTimeMillis());
        this.cancelWith(attacker, "teleport-cancelled-combat");
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.cancel(event.getPlayer().getUniqueId());
        this.inFlight.remove(event.getPlayer().getUniqueId());
    }

    private static Player attacker(final Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    static void sound(final Player player, final String key, final float volume, final float pitch) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            player.playSound(Sound.sound(Key.key(key.strip().toLowerCase(Locale.ROOT)), Sound.Source.MASTER, volume, pitch));
        } catch (final RuntimeException ignored) {
            // invalid key in config
        }
    }
}
