// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Active "holding hands" sessions. A player can be in at most one session. Main thread only. */
public final class HoldManager {

    private final InterakcjePlugin plugin;
    private final Map<UUID, Active> byPlayer = new HashMap<>();

    private record Active(HoldSession session, BukkitTask task) {
    }

    public HoldManager(InterakcjePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isHolding(UUID player) {
        return byPlayer.containsKey(player);
    }

    public void start(InteractionDef def, Player sender, Player target) {
        InteractionDef.HoldSpec spec = def.hold();
        end(sender.getUniqueId(), HoldSession.EndReason.SHUTDOWN, false);
        end(target.getUniqueId(), HoldSession.EndReason.SHUTDOWN, false);

        HoldSession session = new HoldSession(sender.getUniqueId(), target.getUniqueId(), def.id(),
                System.currentTimeMillis(), spec.durationSec() * 1000L, spec.maxDistance());
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(session),
                spec.intervalTicks(), spec.intervalTicks());
        Active active = new Active(session, task);
        byPlayer.put(sender.getUniqueId(), active);
        byPlayer.put(target.getUniqueId(), active);

        plugin.messages().send(sender, "hold-started");
        plugin.messages().send(target, "hold-started");
    }

    private void tick(HoldSession session) {
        Player a = Bukkit.getPlayer(session.first());
        Player b = Bukkit.getPlayer(session.second());
        boolean online = a != null && b != null && a.isOnline() && b.isOnline();
        double distance = Double.NaN;
        if (online && a.getWorld().equals(b.getWorld())) {
            distance = a.getLocation().distance(b.getLocation());
        }
        HoldSession.EndReason reason = session.check(System.currentTimeMillis(), online, distance);
        if (reason != null) {
            end(session.first(), reason, true);
            return;
        }
        InteractionDef def = plugin.interaction(session.interactionId());
        if (def != null && def.hold() != null) {
            plugin.effects().spawnParticles(def.hold().particles(), a, b);
            Player sender = a;
            Player target = b;
            plugin.animations().maintainHold(def, sender, target);
        }
    }

    /** {@code /pusc}: the player lets go. */
    public boolean release(Player player) {
        if (!isHolding(player.getUniqueId())) {
            return false;
        }
        end(player.getUniqueId(), HoldSession.EndReason.RELEASE, true);
        return true;
    }

    public void playerQuit(Player player) {
        end(player.getUniqueId(), HoldSession.EndReason.QUIT, true);
    }

    /**
     * Ends the session of {@code who}.
     *
     * @param who    for RELEASE / QUIT the player who caused the end
     * @param notify send end messages
     */
    private void end(UUID who, HoldSession.EndReason reason, boolean notify) {
        Active active = byPlayer.get(who);
        if (active == null) {
            return;
        }
        active.task.cancel();
        HoldSession s = active.session;
        byPlayer.remove(s.first());
        byPlayer.remove(s.second());
        plugin.animations().stopHold(s.first(), s.second());
        if (!notify) {
            return;
        }
        Player actor = Bukkit.getPlayer(who);
        Player other = Bukkit.getPlayer(s.other(who));
        switch (reason) {
            case QUIT -> {
                if (other != null && actor != null) {
                    plugin.messages().send(other, "hold-end-quit", plugin.names(actor, null));
                }
            }
            case RELEASE -> broadcastEnd("hold-end-release", actor, other);
            case TIMEOUT -> broadcastEnd("hold-end-timeout", actor, other);
            case DISTANCE -> broadcastEnd("hold-end-distance", actor, other);
            case SHUTDOWN -> {
                // silent
            }
        }
    }

    private void broadcastEnd(String key, Player actor, Player other) {
        if (actor == null || other == null) {
            return;
        }
        var msg = plugin.messages().get(key,
                Messages.component("player", plugin.displayName(actor)),
                Messages.component("other", plugin.displayName(other)));
        plugin.service().broadcastNear(actor, other, msg);
    }

    public void stopAll() {
        List<Active> all = new ArrayList<>(byPlayer.values());
        byPlayer.clear();
        all.forEach(a -> a.task.cancel());
    }
}
