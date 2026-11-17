// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.firmy.bridge.BukkitNotifier;
import pl.medivalrpg.firmy.bridge.ShopBridge;
import pl.medivalrpg.firmy.core.AltWatch;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.core.Warehouse;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.Role;

/**
 * Warehouse chests (protection and the "who took what" log), company shop chests on land of a country the member is
 * not a citizen of (Kraje would refuse them), member activity, the mailbox and address hashes.
 */
public final class FirmyListener implements Listener {

    public static final String PERM_ADMIN = "firmy.admin";
    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final Plugin plugin;
    private final FirmService service;
    private final ShopBridge shops;
    private final BukkitNotifier notifier;
    private final AltWatch alts;
    /** Open warehouse inventories: player → (firm, chest key, contents before). */
    private final Map<UUID, Open> open = new HashMap<>();

    private record Open(String firm, String chest, Map<String, Integer> before) {
    }

    public FirmyListener(final Plugin plugin, final FirmService service, final ShopBridge shops, final BukkitNotifier notifier,
                         final AltWatch alts) {
        this.plugin = plugin;
        this.service = service;
        this.shops = shops;
        this.notifier = notifier;
        this.alts = alts;
    }

    private Warehouse wh() {
        return this.service.warehouse;
    }

    /** Warehouse company of the block or the other half of its double chest. */
    public Optional<Firm> warehouseAt(final Block b) {
        if (!this.wh().any()) {
            return Optional.empty();
        }
        final Optional<Firm> direct = this.wh().owner(Warehouse.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        if (direct.isPresent() || (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST)) {
            return direct;
        }
        final BlockState st = b.getState(false);
        if (st instanceof org.bukkit.block.Chest c && c.getInventory().getHolder(false) instanceof DoubleChest dc) {
            return this.ofHolder(dc);
        }
        return Optional.empty();
    }

    private Optional<Firm> ofHolder(final InventoryHolder holder) {
        if (holder instanceof DoubleChest dc) {
            final Optional<Firm> l = this.ofHolder(dc.getLeftSide(false));
            return l.isPresent() ? l : this.ofHolder(dc.getRightSide(false));
        }
        if (holder instanceof BlockState bs) {
            final Block b = bs.getBlock();
            return this.wh().owner(Warehouse.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        }
        return Optional.empty();
    }

    private String chestKey(final InventoryHolder holder) {
        if (holder instanceof DoubleChest dc && dc.getLeftSide(false) instanceof BlockState bs) {
            return Warehouse.key(bs.getWorld().getName(), bs.getX(), bs.getY(), bs.getZ());
        }
        if (holder instanceof BlockState bs) {
            return Warehouse.key(bs.getWorld().getName(), bs.getX(), bs.getY(), bs.getZ());
        }
        return "?";
    }

    // ------------------------------------------------------------------ opening

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEarly(final PlayerInteractEvent event) {
        final Block b = event.getClickedBlock();
        if (b == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Optional<Firm> f = this.warehouseAt(b);
        if (f.isEmpty()) {
            return;
        }
        final Player p = event.getPlayer();
        if (!p.hasPermission(PERM_ADMIN) && !this.service.treasury.chestAccess(f.get(), p.getUniqueId())) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND) {
                p.sendMessage(BukkitNotifier.parse("<gold>[Firmy]</gold> <red>To magazyn firmy „" + f.get().name + "” — otwierają go tylko jej ludzie."));
            }
        }
    }

    /**
     * After Kraje (LOW): a member of the company may open its chests (warehouse and shops) on the seat country's land even
     * when they are not a citizen of it — foreign trading posts are part of the design. Nothing else is allowed here.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractLate(final PlayerInteractEvent event) {
        final Block b = event.getClickedBlock();
        if (b == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() != Event.Result.DENY
            || !(b.getState(false) instanceof Container)) {
            return;
        }
        final Player p = event.getPlayer();
        Optional<Firm> f = this.warehouseAt(b);
        if (f.isEmpty() && this.shops.available()) {
            final Map<String, Object> shop = this.shops.shopAt(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            if (shop != null && shop.get("account") instanceof String acc && acc.startsWith(Firm.ACCOUNT_PREFIX)) {
                f = Optional.ofNullable(this.service.ctx.data.firms.get(acc.substring(Firm.ACCOUNT_PREFIX.length())));
            }
        }
        if (f.isPresent() && f.get().active() && f.get().members.containsKey(p.getUniqueId().toString())
            && f.get().seatCountry != null) {
            event.setUseInteractedBlock(Event.Result.ALLOW);
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(final InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p) || !this.wh().any()) {
            return;
        }
        final InventoryHolder holder = event.getInventory().getHolder(false);
        final Optional<Firm> f = this.ofHolder(holder);
        if (f.isPresent()) {
            this.open.put(p.getUniqueId(), new Open(f.get().id, this.chestKey(holder), count(event.getInventory())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        final Open o = this.open.remove(event.getPlayer().getUniqueId());
        if (o == null) {
            return;
        }
        final Firm f = this.service.ctx.data.firms.get(o.firm());
        if (f == null) {
            return;
        }
        final Map<String, Integer> after = count(event.getInventory());
        final Map<String, Integer> delta = new HashMap<>();
        for (final var e : after.entrySet()) {
            final int d = e.getValue() - o.before().getOrDefault(e.getKey(), 0);
            if (d != 0) {
                delta.put(e.getKey(), d);
            }
        }
        for (final var e : o.before().entrySet()) {
            if (!after.containsKey(e.getKey())) {
                delta.put(e.getKey(), -e.getValue());
            }
        }
        this.wh().log(f, o.chest(), event.getPlayer().getName(), delta);
    }

    private static Map<String, Integer> count(final Inventory inv) {
        final Map<String, Integer> m = new HashMap<>();
        for (final ItemStack it : inv.getStorageContents()) {
            if (it == null || it.isEmpty()) {
                continue;
            }
            String key = it.getType().name();
            if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) {
                key += " „" + PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName()) + "”";
            }
            m.merge(key, it.getAmount(), Integer::sum);
        }
        return m;
    }

    // ------------------------------------------------------------------ breaking and the environment

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(final BlockBreakEvent event) {
        final Optional<Firm> f = this.warehouseAt(event.getBlock());
        if (f.isEmpty()) {
            return;
        }
        final Player p = event.getPlayer();
        final boolean ok = p.hasPermission(PERM_ADMIN) || (f.get().active() ? f.get().has(p.getUniqueId(), Role.WSPOLNIK)
            : this.service.treasury.chestAccess(f.get(), p.getUniqueId()));
        if (!ok) {
            event.setCancelled(true);
            p.sendMessage(BukkitNotifier.parse("<gold>[Firmy]</gold> <red>Magazyn firmy „" + f.get().name + "” rozbiera mistrz albo wspólnik."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(final BlockBreakEvent event) {
        final Block b = event.getBlock();
        final String key = Warehouse.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        this.wh().owner(key).ifPresent(f -> this.wh().remove(f, event.getPlayer().getUniqueId(), true, key));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        final Material t = event.getBlockPlaced().getType();
        if ((t != Material.CHEST && t != Material.TRAPPED_CHEST) || !this.wh().any()) {
            return;
        }
        for (final BlockFace face : HORIZONTAL) {
            final Optional<Firm> f = this.warehouseAt(event.getBlockPlaced().getRelative(face));
            if (f.isPresent() && !f.get().members.containsKey(event.getPlayer().getUniqueId().toString())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopper(final InventoryMoveItemEvent event) {
        if (!this.wh().any()) {
            return;
        }
        if (this.ofHolder(event.getSource().getHolder(false)).isPresent()
            || this.ofHolder(event.getDestination().getHolder(false)).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        if (this.wh().any()) {
            event.blockList().removeIf(b -> this.warehouseAt(b).isPresent());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        if (this.wh().any()) {
            event.blockList().removeIf(b -> this.warehouseAt(b).isPresent());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        if (this.wh().any() && event.getBlocks().stream().anyMatch(b -> this.warehouseAt(b).isPresent())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        if (this.wh().any() && event.getBlocks().stream().anyMatch(b -> this.warehouseAt(b).isPresent())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        if (this.wh().any() && this.warehouseAt(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(final EntityChangeBlockEvent event) {
        if (this.wh().any() && this.warehouseAt(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ players

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        this.service.treasury.activity(p.getUniqueId());
        if (p.getAddress() != null && p.getAddress().getAddress() != null) {
            this.alts.seen(p.getUniqueId().toString(), p.getAddress().getAddress().getHostAddress(), this.service.ctx.now());
        }
        StraznikAddresses.addresses(p.getUniqueId()).forEach(ip -> this.alts.seen(p.getUniqueId().toString(), ip, this.service.ctx.now()));
        for (final Firm f : this.service.ctx.firmsOf(p.getUniqueId())) {
            final Optional<Firm.Member> m = f.member(p.getUniqueId());
            if (m.isPresent() && !m.get().name.equals(p.getName())) {
                m.get().name = p.getName();
            }
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline()) {
                this.notifier.deliver(p);
            }
        }, 60L);
        this.service.ctx.save();
    }
}
