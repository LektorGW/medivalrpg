// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.skarbiec.coin.CoinService;

/**
 * Coin trail: logs where physical coin stacks move (pick up, drop, death, into/out of containers, hoppers), with
 * player, place and amount. Coins stay identical and stackable — no serial numbers; the trail records stack movements.
 * Every handler is MONITOR/ignoreCancelled and first checks the item material (three nugget types) before reading
 * persistent data, so ordinary items cost one enum comparison. Hopper moves are summed per source/target and flushed
 * every 30 s; at most {@code max-events-per-second} other events are written (the rest only counted as dropped).
 */
public final class CoinTrailListener implements Listener {

    private static final Set<Material> COINS = Set.of(Material.COPPER_NUGGET, Material.IRON_NUGGET, Material.GOLD_NUGGET);

    private record Open(Inventory inventory, long value, String world, Integer x, Integer y, Integer z, String label) {
    }

    private static final class HopperSum {
        long amount;
        int stacks;
        int moves;
        String world;
        Integer x;
        Integer y;
        Integer z;
        String target;
    }

    private final CoinService coins;
    private final TrailLog log;
    private final int maxPerSecond;
    private final Map<UUID, Open> open = new HashMap<>();
    private final Map<String, HopperSum> hoppers = new HashMap<>();
    private long second;
    private int inSecond;
    private BukkitTask flush;

    public CoinTrailListener(final CoinService coins, final TrailLog log, final int maxPerSecond) {
        this.coins = coins;
        this.log = log;
        this.maxPerSecond = Math.max(10, maxPerSecond);
    }

    public void start(final Plugin plugin) {
        this.flush = Bukkit.getScheduler().runTaskTimer(plugin, this::flushHoppers, 600L, 600L);
    }

    public void stop() {
        if (this.flush != null) {
            this.flush.cancel();
        }
        this.flushHoppers();
    }

    // ------------------------------------------------------------------ helpers

    private long value(final ItemStack item) {
        if (item == null || !COINS.contains(item.getType())) {
            return 0;
        }
        return this.coins.coinValue(item);
    }

    private long value(final Iterable<ItemStack> items, final int[] stacks) {
        long v = 0;
        for (final ItemStack i : items) {
            final long c = this.value(i);
            if (c > 0) {
                v += c;
                stacks[0]++;
            }
        }
        return v;
    }

    private long value(final ItemStack[] items) {
        long v = 0;
        for (final ItemStack i : items) {
            v += this.value(i);
        }
        return v;
    }

    private boolean allow() {
        final long now = System.currentTimeMillis() / 1000;
        if (now != this.second) {
            this.second = now;
            this.inSecond = 0;
        }
        return ++this.inSecond <= this.maxPerSecond;
    }

    private void emit(final String kind, final HumanEntity who, final long amount, final int stacks, final Location at, final String target,
                      final String note) {
        if (amount <= 0) {
            return;
        }
        if (!this.allow()) {
            this.log.countDropped();
            return;
        }
        this.log.append(new TrailLog.Event(System.currentTimeMillis(), kind, who == null ? null : who.getUniqueId().toString(),
            who == null ? null : who.getName(), amount, stacks, at == null || at.getWorld() == null ? null : at.getWorld().getName(),
            at == null ? null : at.getBlockX(), at == null ? null : at.getBlockY(), at == null ? null : at.getBlockZ(), target, note));
    }

    /** Container location and label, or null when the inventory is not a world container (GUIs, player inventory). */
    private Open describe(final Inventory inv) {
        if (inv == null) {
            return null;
        }
        if (inv.getType() == InventoryType.ENDER_CHEST) {
            final Location l = inv.getLocation();
            return new Open(inv, 0, l == null ? null : l.getWorld().getName(), l == null ? null : l.getBlockX(),
                l == null ? null : l.getBlockY(), l == null ? null : l.getBlockZ(), "Ender chest");
        }
        final InventoryHolder holder = inv.getHolder(false);
        String label = null;
        if (holder instanceof DoubleChest) {
            label = CoinTally.holderLabel("minecraft:chest") + " podwójna";
        } else if (holder instanceof BlockState state) {
            label = CoinTally.holderLabel(state.getType().getKey().toString());
        } else if (holder instanceof BlockInventoryHolder bh) {
            label = CoinTally.holderLabel(bh.getBlock().getType().getKey().toString());
        } else if (holder instanceof Entity e && !(holder instanceof Player)) {
            label = CoinTally.holderLabel(e.getType().getKey().toString());
        }
        if (label == null) {
            return null;
        }
        final Location l = inv.getLocation();
        return new Open(inv, 0, l == null || l.getWorld() == null ? null : l.getWorld().getName(), l == null ? null : l.getBlockX(),
            l == null ? null : l.getBlockY(), l == null ? null : l.getBlockZ(), label);
    }

    // ------------------------------------------------------------------ player <-> world

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p) {
            final ItemStack item = event.getItem().getItemStack();
            final long v = this.value(item);
            this.emit("PICKUP", p, v, 1, event.getItem().getLocation(), "Ziemia", "");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        final long v = this.value(event.getItemDrop().getItemStack());
        this.emit("DROP", event.getPlayer(), v, 1, event.getItemDrop().getLocation(), "Ziemia", "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        final int[] stacks = {0};
        final long v = this.value(event.getDrops(), stacks);
        this.emit("DEATH", event.getPlayer(), v, stacks[0], event.getPlayer().getLocation(), "Ziemia",
            event.getKeepInventory() ? "zachowany ekwipunek" : "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (event.getBlock().getState(false) instanceof Container c && !(c.getInventory().getHolder(false) instanceof DoubleChest)) {
            final ItemStack[] items = c.getInventory().getContents();
            final long v = this.value(items);
            if (v > 0) {
                int stacks = 0;
                for (final ItemStack i : items) {
                    stacks += this.value(i) > 0 ? 1 : 0;
                }
                this.emit("DROP", event.getPlayer(), v, stacks, event.getBlock().getLocation(),
                    CoinTally.holderLabel(event.getBlock().getType().getKey().toString()), "zniszczony pojemnik, monety wypadły");
            }
        }
    }

    // ------------------------------------------------------------------ containers

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(final InventoryOpenEvent event) {
        final Open o = this.describe(event.getInventory());
        if (o != null) {
            this.open.put(event.getPlayer().getUniqueId(), new Open(o.inventory(), this.value(event.getInventory().getContents()), o.world(),
                o.x(), o.y(), o.z(), o.label()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        final Open o = this.open.remove(event.getPlayer().getUniqueId());
        if (o == null || o.inventory() != event.getInventory()) {
            return;
        }
        final long now = this.value(event.getInventory().getContents());
        final long diff = now - o.value();
        if (diff == 0) {
            return;
        }
        final Location at = o.world() == null ? null : new Location(Bukkit.getWorld(o.world()), o.x(), o.y(), o.z());
        this.emit(diff > 0 ? "CONTAINER_IN" : "CONTAINER_OUT", event.getPlayer(), Math.abs(diff), 0, at, o.label(),
            "w pojemniku teraz " + now + " md");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final InventoryMoveItemEvent event) {
        final ItemStack item = event.getItem();
        if (item == null || !COINS.contains(item.getType())) {
            return;
        }
        final long v = this.coins.coinValue(item);
        if (v <= 0) {
            return;
        }
        final Open src = this.describe(event.getSource());
        final Open dst = this.describe(event.getDestination());
        final String key = place(src) + ">" + place(dst);
        final HopperSum s = this.hoppers.computeIfAbsent(key, k -> new HopperSum());
        s.amount += v;
        s.stacks++;
        s.moves++;
        if (s.target == null) {
            final Open at = dst != null ? dst : src;
            if (at != null) {
                s.world = at.world();
                s.x = at.x();
                s.y = at.y();
                s.z = at.z();
            }
            s.target = (src == null ? "?" : src.label() + " " + coords(src)) + " → " + (dst == null ? "?" : dst.label() + " " + coords(dst));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(final InventoryPickupItemEvent event) {
        final long v = this.value(event.getItem().getItemStack());
        if (v <= 0) {
            return;
        }
        final Open dst = this.describe(event.getInventory());
        final String key = "ground>" + place(dst);
        final HopperSum s = this.hoppers.computeIfAbsent("P" + key, k -> new HopperSum());
        s.amount += v;
        s.stacks++;
        s.moves++;
        if (s.target == null) {
            final Location l = event.getItem().getLocation();
            s.world = l.getWorld().getName();
            s.x = l.getBlockX();
            s.y = l.getBlockY();
            s.z = l.getBlockZ();
            s.target = "Ziemia → " + (dst == null ? "?" : dst.label() + " " + coords(dst));
        }
    }

    private static String place(final Open o) {
        return o == null ? "?" : o.world() + ":" + o.x() + ":" + o.y() + ":" + o.z();
    }

    private static String coords(final Open o) {
        return o.x() == null ? "" : "(" + o.x() + " " + o.y() + " " + o.z() + ")";
    }

    private void flushHoppers() {
        if (this.hoppers.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        this.hoppers.forEach((key, s) -> this.log.append(new TrailLog.Event(now, key.startsWith("P") ? "HOPPER_PICKUP" : "HOPPER", null, null,
            s.amount, s.stacks, s.world, s.x, s.y, s.z, s.target, s.moves + " ruchów w ostatnich 30 s")));
        this.hoppers.clear();
    }
}
