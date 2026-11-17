// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.trade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.config.Messages;

/** {@code /handel}: requests, running trades and their settlement. Main thread only. */
public final class TradeManager implements Listener {

    private final SkarbiecPlugin plugin;
    /** target → (requester → expiry millis) */
    private final Map<UUID, Map<UUID, Long>> requests = new HashMap<>();
    private final Map<UUID, TradeSession> sessions = new HashMap<>();
    private BukkitTask task;

    public TradeManager(final SkarbiecPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 10L, 10L);
    }

    private Messages m() {
        return this.plugin.messages();
    }

    private boolean inRange(final Player a, final Player b) {
        final double r = this.plugin.cfg().tradeRange;
        return a.getWorld().equals(b.getWorld()) && a.getLocation().distanceSquared(b.getLocation()) <= r * r;
    }

    public boolean inTrade(final Player p) {
        return this.sessions.containsKey(p.getUniqueId());
    }

    // ------------------------------------------------------------------ requests

    /** {@code /handel <gracz>}: accepts a pending request from the target, otherwise sends one. */
    public void request(final Player from, final Player target) {
        final Messages m = this.m();
        if (from.equals(target)) {
            m.send(from, "trade.self");
            return;
        }
        if (this.inTrade(from) || this.inTrade(target)) {
            m.send(from, "trade.busy", Placeholder.unparsed("player", target.getName()));
            return;
        }
        if (!this.inRange(from, target)) {
            m.send(from, "trade.too-far", Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("range", Integer.toString((int) this.plugin.cfg().tradeRange)));
            return;
        }
        final long now = System.currentTimeMillis();
        final Map<UUID, Long> toMe = this.requests.get(from.getUniqueId());
        if (toMe != null) {
            final Long expiry = toMe.remove(target.getUniqueId());
            if (expiry != null && expiry >= now) {
                this.open(target, from);
                return;
            }
        }
        this.requests.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>())
            .put(from.getUniqueId(), now + this.plugin.cfg().tradeRequestTimeoutSeconds * 1000L);
        m.send(from, "trade.request-sent", Placeholder.unparsed("player", target.getName()));
        target.sendMessage(m.get("trade.request-received", Placeholder.unparsed("player", from.getName()),
                Placeholder.unparsed("timeout", Integer.toString(this.plugin.cfg().tradeRequestTimeoutSeconds)))
            .hoverEvent(HoverEvent.showText(m.get("trade.request-hover", Placeholder.unparsed("player", from.getName()))))
            .clickEvent(ClickEvent.runCommand("/handel " + from.getName())));
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 0.8f);
    }

    private void open(final Player a, final Player b) {
        this.requests.remove(a.getUniqueId());
        this.requests.remove(b.getUniqueId());
        final TradeSession s = new TradeSession(this.plugin, this, a, b);
        this.sessions.put(a.getUniqueId(), s);
        this.sessions.put(b.getUniqueId(), s);
        s.openBoth();
        this.m().send(a, "trade.started", Placeholder.unparsed("player", b.getName()));
        this.m().send(b, "trade.started", Placeholder.unparsed("player", a.getName()));
    }

    // ------------------------------------------------------------------ ending

    /** Ends without exchange: everyone gets their own items back. Idempotent. */
    void cancel(final TradeSession s, final String reasonKey, final Player cause) {
        this.cancel(s, reasonKey, cause, null);
    }

    private void cancel(final TradeSession s, final String reasonKey, final Player cause, final PlayerDeathEvent death) {
        if (s.finished()) {
            return;
        }
        s.finish();
        this.sessions.remove(s.a.getUniqueId());
        this.sessions.remove(s.b.getUniqueId());
        this.returnItems(s.a, s.takeItems(true), death);
        this.returnItems(s.b, s.takeItems(false), death);
        s.closeAll(true);
        final var who = Placeholder.unparsed("player", cause == null ? "?" : cause.getName());
        for (final Player p : List.of(s.a, s.b)) {
            if (p.isOnline()) {
                this.m().send(p, reasonKey, who);
            }
        }
    }

    private void returnItems(final Player owner, final List<ItemStack> items, final PlayerDeathEvent death) {
        if (items.isEmpty()) {
            return;
        }
        if (death != null && death.getPlayer().equals(owner) && !death.getKeepInventory()) {
            death.getDrops().addAll(items); // the inventory is cleared after the event: drop them with the rest
        } else {
            TradeSession.giveBack(owner, items);
        }
    }

    /** Countdown finished: verify everything again, then swap. */
    void complete(final TradeSession s) {
        synchronized(this.plugin.ledger()){completeLocked(s);}
    }
    private void completeLocked(final TradeSession s) {
        if (s.finished()) {
            return;
        }
        final Messages m = this.m();
        if (!s.a.isOnline() || !s.b.isOnline() || !s.viewing(s.a) || !s.viewing(s.b)) {
            this.cancel(s, "trade.cancelled", null);
            return;
        }
        if (!this.inRange(s.a, s.b)) {
            this.cancel(s, "trade.cancelled-distance", null);
            return;
        }
        final long coinsA = this.plugin.coins().countCoins(s.a);
        final long coinsB = this.plugin.coins().countCoins(s.b);
        if (coinsA < s.offerA() || coinsB < s.offerB()) {
            s.unconfirm();
            m.send(s.a, "trade.coins-missing");
            m.send(s.b, "trade.coins-missing");
            return;
        }
        // Point of no return: nothing can enter or leave the window from here on.
        s.finish();
        this.sessions.remove(s.a.getUniqueId());
        this.sessions.remove(s.b.getUniqueId());
        final List<ItemStack> fromA = s.takeItems(true);
        final List<ItemStack> fromB = s.takeItems(false);
        if (s.offerA() > 0 && !this.plugin.coins().takeCoins(s.a, s.offerA())) {
            TradeSession.giveBack(s.a, fromA);
            TradeSession.giveBack(s.b, fromB);
            s.closeAll(false);
            m.send(s.a, "trade.coins-missing");
            m.send(s.b, "trade.coins-missing");
            return;
        }
        if (s.offerB() > 0 && !this.plugin.coins().takeCoins(s.b, s.offerB())) {
            this.plugin.coins().giveCoins(s.a, s.offerA());
            TradeSession.giveBack(s.a, fromA);
            TradeSession.giveBack(s.b, fromB);
            s.closeAll(false);
            m.send(s.a, "trade.coins-missing");
            m.send(s.b, "trade.coins-missing");
            return;
        }
        s.closeAll(false);
        TradeSession.giveBack(s.a, fromB);
        TradeSession.giveBack(s.b, fromA);
        this.plugin.coins().giveCoins(s.a, s.offerB());
        this.plugin.coins().giveCoins(s.b, s.offerA());
        this.plugin.store().audit("TRADE\t" + s.a.getUniqueId() + "\t" + s.a.getName() + "\t<-> " + s.b.getName() + " "
            + s.b.getUniqueId() + " | " + s.a.getName() + " dał: " + describe(fromA) + " +" + s.offerA() + " md | "
            + s.b.getName() + " dał: " + describe(fromB) + " +" + s.offerB() + " md");
        for (final Player p : List.of(s.a, s.b)) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
            m.send(p, "trade.done", Placeholder.unparsed("player", s.other(p).getName()));
        }
    }

    private static String describe(final List<ItemStack> items) {
        if (items.isEmpty()) {
            return "-";
        }
        final StringBuilder sb = new StringBuilder();
        for (final ItemStack i : items) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(i.getAmount()).append('x').append(i.getType());
        }
        return sb.toString();
    }

    /** Plugin disable: every running trade is cancelled and items go back. */
    public void cancelAll() {
        if (this.task != null) {
            this.task.cancel();
        }
        for (final TradeSession s : List.copyOf(this.sessions.values())) {
            if (!s.finished()) {
                s.finish();
                TradeSession.giveBack(s.a, s.takeItems(true));
                TradeSession.giveBack(s.b, s.takeItems(false));
                s.closeAll(false);
            }
        }
        this.sessions.clear();
        this.requests.clear();
    }

    private void tick() {
        final long now = System.currentTimeMillis();
        this.requests.values().forEach(map -> map.values().removeIf(exp -> exp < now));
        this.requests.values().removeIf(Map::isEmpty);
        for (final TradeSession s : List.copyOf(this.sessions.values())) {
            if (s.finished()) {
                continue;
            }
            if (!s.a.isOnline() || !s.b.isOnline()) {
                this.cancel(s, "trade.cancelled", null);
            } else if (!this.inRange(s.a, s.b)) {
                this.cancel(s, "trade.cancelled-distance", null);
            }
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        final TradeSession s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s != null) {
            this.cancel(s, "trade.cancelled-quit", event.getPlayer());
        }
        this.requests.remove(event.getPlayer().getUniqueId());
    }

    /** HIGHEST + ignoreCancelled: only a death that really happens ends the trade (items join the drops). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        final TradeSession s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s != null) {
            this.cancel(s, "trade.cancelled-death", event.getPlayer(), event);
        }
    }
}
