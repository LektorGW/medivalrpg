// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import pl.medivalrpg.interakcje.anim.AnimationService;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Validation, consent requests and execution of interactions. Main thread only. */
public final class InteractionService {
    public static final String PERM_USE = "interakcje.use";
    public static final String PERM_BYPASS_COOLDOWN = "interakcje.bypass.cooldown";

    private final InterakcjePlugin plugin;
    private final Cooldowns cooldowns = new Cooldowns();
    private final RequestManager requests = new RequestManager();

    public InteractionService(InterakcjePlugin plugin) {
        this.plugin = plugin;
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }

    // ------------------------------------------------------------------ visibility

    /** Vanished (metadata from vanish plugins) or spectator. */
    @SuppressWarnings("deprecation") // metadata is still how vanish plugins expose their state
    public boolean isHidden(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        for (String key : plugin.settings().vanishMetadata()) {
            for (MetadataValue v : p.getMetadata(key)) {
                if (v.asBoolean()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Can {@code viewer} pick {@code target} at all (ignoring distance)? */
    public boolean canTarget(Player viewer, Player target) {
        return !viewer.equals(target) && target.isOnline() && viewer.canSee(target) && !isHidden(target);
    }

    // ------------------------------------------------------------------ entry point

    /** Command / menu entry point. {@code target} may be {@code null} only for optional-target interactions. */
    public void attempt(Player sender, InteractionDef def, Player target) {
        Messages msg = plugin.messages();
        if (def == null || !def.enabled()) {
            msg.send(sender, "interaction-disabled");
            return;
        }
        if (!sender.hasPermission(PERM_USE) || !sender.hasPermission(def.permission())) {
            msg.send(sender, "no-permission");
            return;
        }
        if (target == null && !def.targetOptional()) {
            msg.send(sender, "usage", Messages.text("command", def.command()));
            return;
        }
        String fail = check(sender, def, target);
        if (fail != null) {
            msg.send(sender, fail, failResolvers(sender, def, target));
            return;
        }

        long now = System.currentTimeMillis();
        if (!sender.hasPermission(PERM_BYPASS_COOLDOWN)) {
            long left = cooldowns.remaining(sender.getUniqueId(), def.id(), now);
            if (left > 0) {
                msg.send(sender, "cooldown", Messages.text("seconds", Long.toString(Cooldowns.secondsLeft(left))));
                return;
            }
            if (cooldowns.globalRemaining(sender.getUniqueId(), plugin.settings().globalCooldownMs(), now) > 0) {
                msg.send(sender, "global-cooldown");
                return;
            }
        }

        if (def.consent() && target != null) {
            sendRequest(sender, def, target, now);
            return;
        }
        cooldowns.mark(sender.getUniqueId(), def.id(), def.cooldownMs(), now);
        execute(sender, def, target);
    }

    /**
     * Checks everything except permissions and cooldowns.
     *
     * @return message key of the failure, or {@code null} when the interaction may happen
     */
    String check(Player sender, InteractionDef def, Player target) {
        PlayerDataStore data = plugin.data();
        if (sender.getGameMode() == GameMode.SPECTATOR) {
            return "sender-spectator";
        }
        if (data.isDisabled(sender.getUniqueId())) {
            return "sender-disabled";
        }
        if (target == null) {
            // optional-target interaction to everyone nearby (or a ceremony of the sender alone)
            return plugin.ceremonies().checkRequirement(def, sender) == null ? null : "requires-ruler-self";
        }
        if (sender.equals(target)) {
            return "self";
        }
        if (!canTarget(sender, target)) {
            return "player-not-found";
        }
        if (!sender.getWorld().equals(target.getWorld())
                || sender.getLocation().distanceSquared(target.getLocation()) > def.range() * def.range()) {
            return "too-far";
        }
        if (data.isDisabled(target.getUniqueId())) {
            return "target-disabled";
        }
        if (data.isBlocked(target.getUniqueId(), sender.getUniqueId())) {
            return "target-blocked-you";
        }
        if (data.isBlocked(sender.getUniqueId(), target.getUniqueId())) {
            return "you-blocked-target";
        }
        if (def.isExempt(target.getGameMode().name())) {
            return "target-exempt";
        }
        String ruler = plugin.ceremonies().checkRequirement(def, target);
        if (ruler != null) {
            return ruler;
        }
        if (def.hold() != null && (plugin.holds().isHolding(sender.getUniqueId())
                || plugin.holds().isHolding(target.getUniqueId()))) {
            return "hold-already";
        }
        return null;
    }

    private TagResolver failResolvers(Player sender, InteractionDef def, Player target) {
        return TagResolver.resolver(
                plugin.names(sender, target),
                Messages.text("player", target == null ? "" : target.getName()),
                Messages.text("range", formatDistance(def.range())),
                Messages.text("command", def.command()));
    }

    // ------------------------------------------------------------------ consent

    private void sendRequest(Player sender, InteractionDef def, Player target, long now) {
        Messages msg = plugin.messages();
        RequestManager.Request r = requests.create(sender.getUniqueId(), target.getUniqueId(), def.id(), now,
                plugin.settings().requestTimeoutMs());
        if (r == null) {
            msg.send(sender, "request-pending", plugin.names(sender, target));
            return;
        }
        cooldowns.mark(sender.getUniqueId(), def.id(), def.cooldownMs(), now);

        Component accept = msg.get("request-accept-button")
                .hoverEvent(HoverEvent.showText(msg.get("request-accept-hover")))
                .clickEvent(ClickEvent.runCommand("/interakcje akceptuj " + r.id()));
        Component deny = msg.get("request-deny-button")
                .hoverEvent(HoverEvent.showText(msg.get("request-deny-hover")))
                .clickEvent(ClickEvent.runCommand("/interakcje odmow " + r.id()));
        msg.send(target, "request-received",
                plugin.names(sender, target),
                Messages.component("action", msg.parse(def.requestText())),
                Messages.component("accept", accept),
                Messages.component("deny", deny));
        msg.send(sender, "request-sent", plugin.names(sender, target));
    }

    /** {@code /interakcje akceptuj [id]} – {@code id == null} accepts the newest request. */
    public void accept(Player target, Integer id) {
        Messages msg = plugin.messages();
        long now = System.currentTimeMillis();
        RequestManager.Request r = id == null ? requests.latestFor(target.getUniqueId(), now)
                : requests.find(id, target.getUniqueId(), now);
        if (r == null) {
            msg.send(target, "request-not-found");
            return;
        }
        requests.remove(r);
        Player sender = Bukkit.getPlayer(r.sender());
        InteractionDef def = plugin.interaction(r.interactionId());
        if (sender == null || !sender.isOnline()) {
            msg.send(target, "request-not-found");
            return;
        }
        if (def == null || !def.enabled()) {
            msg.send(target, "interaction-disabled");
            return;
        }
        String fail = check(sender, def, target);
        if ("too-far".equals(fail)) {
            msg.send(sender, "request-too-far");
            msg.send(target, "request-too-far");
            return;
        }
        if (fail != null) {
            msg.send(sender, fail, failResolvers(sender, def, target));
            msg.send(target, "request-not-found");
            return;
        }
        execute(sender, def, target);
    }

    public void deny(Player target, Integer id) {
        Messages msg = plugin.messages();
        long now = System.currentTimeMillis();
        RequestManager.Request r = id == null ? requests.latestFor(target.getUniqueId(), now)
                : requests.find(id, target.getUniqueId(), now);
        if (r == null) {
            msg.send(target, "request-not-found");
            return;
        }
        requests.remove(r);
        Player sender = Bukkit.getPlayer(r.sender());
        if (sender != null) {
            msg.send(sender, "request-denied-sender", plugin.names(sender, target));
            msg.send(target, "request-denied-target", plugin.names(sender, target));
        } else {
            msg.send(target, "request-not-found");
        }
    }

    // ------------------------------------------------------------------ execution

    void execute(Player sender, InteractionDef def, Player target) {
        boolean knockback = target == null || !plugin.poses().isPosing(target.getUniqueId());
        if (def.hold() != null && target != null) {
            plugin.holds().start(def, sender, target); // before the animation: it ends older holds silently
        }
        AnimationService.Started anim = plugin.animations().playInteraction(def, sender, target);
        if (anim == null) {
            plugin.effects().play(def.effects(), sender, target, knockback);
        } else {
            playTimed(def.effects(), sender, target, knockback, anim.impactTicks());
        }
        Component line = target == null
                ? plugin.messages().parse(def.broadcastNoTarget(), plugin.names(sender, null))
                : plugin.messages().parse(def.broadcast(), plugin.names(sender, target));
        broadcastNear(sender, target, line);
        ceremonyFollowUp(def, sender, target, anim);
    }

    /** Knight title (after the last touch of the sword) and the bows of the people around (at the crowning). */
    private void ceremonyFollowUp(InteractionDef def, Player sender, Player target, AnimationService.Started anim) {
        if (!def.grantTitle() && !def.audienceBow()) {
            return;
        }
        List<Integer> ticks = anim == null ? List.of() : anim.impactTicks();
        int first = ticks.isEmpty() ? 0 : ticks.getFirst();
        int last = ticks.isEmpty() ? 0 : ticks.getLast();
        UUID senderId = sender.getUniqueId();
        UUID targetId = target == null ? null : target.getUniqueId();
        if (def.grantTitle() && target != null) {
            later(last + 10, () -> {
                Player s = Bukkit.getPlayer(senderId);
                Player t = Bukkit.getPlayer(targetId);
                if (s != null && t != null) {
                    plugin.ceremonies().knighted(s, t);
                }
            });
        }
        if (def.audienceBow()) {
            later(first, () -> {
                Player s = Bukkit.getPlayer(senderId);
                Player subject = targetId == null ? s : Bukkit.getPlayer(targetId);
                if (subject != null) {
                    plugin.ceremonies().audienceBow(subject, targetId == null ? null : s);
                }
            });
        }
    }

    private void later(int ticks, Runnable r) {
        if (ticks <= 0) {
            r.run();
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, r, ticks);
        }
    }

    /**
     * Particles / sounds (and the knockback, once) at the impact moments of a running animation:
     * the clap of a high five, the hit of a slap, every clap of the clapping emote.
     */
    public void playTimed(InteractionDef.Effects fx, Player sender, Player target, boolean knockback, List<Integer> ticks) {
        if (ticks.isEmpty()) {
            plugin.effects().play(fx, sender, target, knockback, true);
            return;
        }
        UUID senderId = sender.getUniqueId();
        UUID targetId = target == null ? null : target.getUniqueId();
        boolean first = true;
        for (int tick : ticks) {
            boolean kb = knockback && first;
            first = false;
            Runnable r = () -> {
                Player s = Bukkit.getPlayer(senderId);
                Player t = targetId == null ? null : Bukkit.getPlayer(targetId);
                if (s != null && (targetId == null || t != null)) {
                    plugin.effects().play(fx, s, t, kb, true);
                }
            };
            if (tick <= 0) {
                r.run();
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, r, tick);
            }
        }
    }

    // ------------------------------------------------------------------ dances & emotes

    /** {@code /taniec <n>}, {@code /emotka <nazwa>}. */
    public void performMotion(Player p, MotionGroup group, MotionGroup.Motion motion) {
        performMotion(p, group, motion, null, false);
    }

    /** Range of {@code /emotka <nazwa> <gracz>} (turning to the target). */
    public static final double MOTION_TARGET_RANGE = 10.0;

    /**
     * @param target the player to turn to (emotes with {@code target: optional}), or {@code null}
     * @param quiet  started by a click (drink trigger): no messages about cooldowns or refusals
     */
    public void performMotion(Player p, MotionGroup group, MotionGroup.Motion motion, Player target, boolean quiet) {
        Messages msg = quiet ? null : plugin.messages();
        if (quiet) {
            if (!group.enabled() || motion == null || !motion.enabled() || !p.hasPermission(PERM_USE)
                    || !p.hasPermission(group.permission()) || p.getGameMode() == GameMode.SPECTATOR
                    || plugin.poses().isPosing(p.getUniqueId()) || plugin.animations().isAnimating(p.getUniqueId())) {
                return;
            }
            long now = System.currentTimeMillis();
            if (cooldowns.remaining(p.getUniqueId(), group.cooldownKey(motion), now) > 0) {
                return;
            }
            runMotion(p, group, motion, null, now);
            return;
        }
        if (target != null && motion != null) {
            if (!motion.target()) {
                msg.send(p, "motion-no-target");
                return;
            }
            if (target.equals(p)) {
                msg.send(p, "self");
                return;
            }
            if (!canTarget(p, target) || !p.getWorld().equals(target.getWorld())
                    || p.getLocation().distanceSquared(target.getLocation()) > MOTION_TARGET_RANGE * MOTION_TARGET_RANGE) {
                msg.send(p, "player-not-found", Messages.text("player", target.getName()));
                return;
            }
        }
        if (!group.enabled() || motion == null || !motion.enabled()) {
            msg.send(p, "interaction-disabled");
            return;
        }
        if (!p.hasPermission(PERM_USE) || !p.hasPermission(group.permission())) {
            msg.send(p, "no-permission");
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR) {
            msg.send(p, "sender-spectator");
            return;
        }
        if (plugin.poses().isPosing(p.getUniqueId())) {
            msg.send(p, "pose-busy");
            return;
        }
        long now = System.currentTimeMillis();
        if (!p.hasPermission(PERM_BYPASS_COOLDOWN)) {
            long left = cooldowns.remaining(p.getUniqueId(), group.cooldownKey(motion), now);
            if (left > 0) {
                msg.send(p, "cooldown", Messages.text("seconds", Long.toString(Cooldowns.secondsLeft(left))));
                return;
            }
            if (cooldowns.globalRemaining(p.getUniqueId(), plugin.settings().globalCooldownMs(), now) > 0) {
                msg.send(p, "global-cooldown");
                return;
            }
        }
        runMotion(p, group, motion, target, now);
    }

    private void runMotion(Player p, MotionGroup group, MotionGroup.Motion motion, Player target, long now) {
        cooldowns.mark(p.getUniqueId(), group.cooldownKey(motion), motion.cooldownMs(), now);
        AnimationService.Started anim = motion.animation() == null ? null
                : plugin.animations().playSolo(motion.animation(), p, target);
        if (anim == null) {
            if (target != null) {
                p.lookAt(target, io.papermc.paper.entity.LookAnchor.EYES, io.papermc.paper.entity.LookAnchor.EYES);
            }
            plugin.effects().play(motion.effects(), p, null, false);
        } else {
            playTimed(motion.effects(), p, null, false, anim.impactTicks());
        }
        String line = target != null && !motion.broadcastTarget().isEmpty() ? motion.broadcastTarget() : motion.broadcast();
        if (!line.isEmpty()) {
            broadcastNear(p, target, plugin.messages().parse(line,
                    Messages.component("player", plugin.displayName(p)),
                    Messages.component("target", target == null ? Component.empty() : plugin.displayName(target)),
                    Messages.component("name", plugin.messages().parse(motion.name()))));
        }
    }

    /** Sends {@code line} to everyone within the broadcast radius of {@code a} or {@code b} (and to both). */
    public void broadcastNear(Player a, Player b, Component line) {
        double r = plugin.settings().broadcastRadius();
        double r2 = r * r;
        Set<Player> to = new HashSet<>();
        to.add(a);
        if (b != null) {
            to.add(b);
        }
        for (Player p : a.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(a.getLocation()) <= r2
                    || (b != null && b.getWorld().equals(p.getWorld())
                    && p.getLocation().distanceSquared(b.getLocation()) <= r2)) {
                to.add(p);
            }
        }
        to.forEach(p -> p.sendMessage(line));
    }

    // ------------------------------------------------------------------ housekeeping

    /** Once per second: expired requests, cooldown map cleanup. */
    public void tickSecond() {
        long now = System.currentTimeMillis();
        for (RequestManager.Request r : requests.expire(now)) {
            Player sender = Bukkit.getPlayer(r.sender());
            Player target = Bukkit.getPlayer(r.target());
            if (sender != null) {
                plugin.messages().send(sender, "request-expired-sender", plugin.names(sender, target));
            }
            if (target != null) {
                plugin.messages().send(target, "request-expired-target", plugin.names(sender, target));
            }
        }
        cooldowns.purge(now, plugin.settings().globalCooldownMs());
    }

    public void playerQuit(Player player) {
        List<RequestManager.Request> gone = requests.removeInvolving(player.getUniqueId());
        for (RequestManager.Request r : gone) {
            UUID otherId = r.sender().equals(player.getUniqueId()) ? r.target() : r.sender();
            Player other = Bukkit.getPlayer(otherId);
            if (other != null) {
                plugin.messages().send(other, "request-cancelled", Messages.text("player", player.getName()));
            }
        }
    }

    public void clear() {
        requests.clear();
    }

    public static String formatDistance(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
