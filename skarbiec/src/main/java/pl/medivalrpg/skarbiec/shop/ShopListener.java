// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.shop;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
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
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.config.Messages;
import pl.medivalrpg.skarbiec.config.SkarbiecConfig;
import pl.medivalrpg.skarbiec.integration.Territory;
import pl.medivalrpg.skarbiec.money.Money;

/**
 * Player shops: creation from a {@code [Sklep]} sign, using the sign, and protection of the sign and its
 * container (only the owner/admin opens or breaks them; hoppers, explosions, pistons, fire and mobs cannot
 * touch them).
 */
public final class ShopListener implements Listener {

    public static final String PERM_CREATE = "skarbiec.sklep";
    public static final String PERM_ADMIN = "skarbiec.admin";

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final SkarbiecPlugin plugin;
    private final ShopManager shops;

    public ShopListener(final SkarbiecPlugin plugin, final ShopManager shops) {
        this.plugin = plugin;
        this.shops = shops;
    }

    private Messages m() {
        return this.plugin.messages();
    }

    private static String norm(final Component c) {
        final String s = c == null ? "" : PLAIN.serialize(c);
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('ł', 'l'), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").strip();
    }

    /**
     * May the player open ({@code action = "open"}) or break ({@code "break"}) the shop? Personal shop: its builder.
     * Company shop: whoever the company policy allows (members; master/partners for breaking); without the policy only
     * the builder. Admins always.
     */
    private boolean mayHandle(final Player player, final Shop s, final String action) {
        if (player.hasPermission(PERM_ADMIN)) {
            return true;
        }
        if (s.account() == null || !this.shops.accounts().has(s.account())) {
            return s.owner().equals(player.getUniqueId());
        }
        return ShopAccounts.allowed(this.shops.accounts().ask(s.account(), "access",
            java.util.Map.of("player", player.getUniqueId().toString(), "action", action, "shop", s.id())));
    }

    private void denied(final Player player, final Shop s) {
        if (s.account() != null) {
            this.m().send(player, "shop.firm-protected", Placeholder.unparsed("owner", ShopManager.display(s)));
        } else {
            this.m().send(player, "shop.protected", Placeholder.unparsed("owner", s.ownerName()));
        }
    }

    /** Container a shop sign belongs to: the block a wall sign hangs on, or the block under a standing sign. */
    private Block containerFor(final Block sign) {
        if (sign.getBlockData() instanceof WallSign wall) {
            return sign.getRelative(wall.getFacing().getOppositeFace());
        }
        return sign.getRelative(BlockFace.DOWN);
    }

    // ------------------------------------------------------------------ creation

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(final SignChangeEvent event) {
        if (this.shops.atSign(event.getBlock()) != null) {
            event.setCancelled(true); // an existing shop sign is never edited
            return;
        }
        final String head = norm(event.line(0));
        if (!head.equals("[sklep]") && !head.equals("sklep") && !head.equals("[shop]")) {
            return;
        }
        final Player player = event.getPlayer();
        final Messages m = this.m();
        final SkarbiecConfig cfg = this.plugin.cfg();
        if (!cfg.shopsEnabled) {
            m.send(player, "shop.disabled");
            return;
        }
        if (!player.hasPermission(PERM_CREATE)) {
            m.send(player, "no-permission");
            event.setCancelled(true);
            return;
        }
        final Block sign = event.getBlock();
        final Block container = this.containerFor(sign);
        if (!cfg.shopContainers.contains(container.getType()) || !(container.getState(false) instanceof Container c)) {
            m.send(player, "shop.need-container");
            return;
        }
        if (this.shops.atContainer(container) != null) {
            m.send(player, "shop.already-shop");
            event.setCancelled(true);
            return;
        }
        final String typeText = norm(event.line(1));
        final Shop.Type type;
        if (typeText.startsWith("sprzeda") || typeText.equals("s") || typeText.equals("sell")) {
            type = Shop.Type.SELL;
        } else if (typeText.startsWith("kupi") || typeText.startsWith("kupuj") || typeText.equals("k") || typeText.equals("buy")) {
            type = Shop.Type.BUY;
        } else {
            m.send(player, "shop.usage");
            event.setCancelled(true);
            return;
        }
        final int amount;
        try {
            amount = Integer.parseInt(norm(event.line(2)).replace("x", "").replace("szt", "").replace(".", "").strip());
        } catch (final NumberFormatException e) {
            m.send(player, "shop.bad-amount", Placeholder.unparsed("max", Integer.toString(cfg.shopsMaxAmount)));
            event.setCancelled(true);
            return;
        }
        if (amount < 1 || amount > cfg.shopsMaxAmount) {
            m.send(player, "shop.bad-amount", Placeholder.unparsed("max", Integer.toString(cfg.shopsMaxAmount)));
            event.setCancelled(true);
            return;
        }
        final Optional<Long> price = Money.parse(event.line(3) == null ? "" : PLAIN.serialize(event.line(3)));
        if (price.isEmpty() || price.get() <= 0 || price.get() > cfg.shopsMaxPrice) {
            m.send(player, "shop.bad-price", SkarbiecPlugin.amount("max", cfg.shopsMaxPrice));
            event.setCancelled(true);
            return;
        }
        // The goods: first item in the container, else the item in the player's hand.
        ItemStack item = null;
        for (final ItemStack it : c.getInventory().getStorageContents()) {
            if (it != null && !it.isEmpty()) {
                item = it;
                break;
            }
        }
        if (item == null && !player.getInventory().getItemInMainHand().isEmpty()) {
            item = player.getInventory().getItemInMainHand();
        }
        if (item == null) {
            m.send(player, "shop.need-item");
            event.setCancelled(true);
            return;
        }
        if (this.plugin.coins().isCoin(item)) {
            m.send(player, "shop.no-coins-as-goods");
            event.setCancelled(true);
            return;
        }
        final Territory.Verdict verdict = this.plugin.territory().check(player, container.getLocation(), cfg.shopTerritory);
        if (verdict != Territory.Verdict.OK && !player.hasPermission(PERM_ADMIN)) {
            m.send(player, verdict == Territory.Verdict.FOREIGN_COUNTRY ? "shop.territory-foreign" : "shop.territory-wild");
            event.setCancelled(true);
            return;
        }
        if (!player.hasPermission(PERM_ADMIN) && cfg.shopsMaxPerPlayer > 0
            && this.shops.ownedBy(player.getUniqueId()).size() >= cfg.shopsMaxPerPlayer) {
            m.send(player, "shop.limit", Placeholder.unparsed("max", Integer.toString(cfg.shopsMaxPerPlayer)));
            event.setCancelled(true);
            return;
        }
        final Shop shop = this.shops.create(player, type, amount, price.get(), item, container, sign);
        final List<Component> lines = this.shops.frontLines(shop);
        for (int i = 0; i < 4; i++) {
            event.line(i, lines.get(i));
        }
        // Back side, wax: after the event has written the front text.
        Bukkit.getScheduler().runTask(this.plugin, () -> this.shops.writeSign(shop));
        m.send(player, "shop.created", Placeholder.component("item", shop.item().effectiveName()),
            Placeholder.unparsed("count", Integer.toString(amount)), SkarbiecPlugin.amount(price.get()),
            Placeholder.unparsed("type", m.raw(type == Shop.Type.SELL ? "shop.type-sell" : "shop.type-buy")));
    }

    // ------------------------------------------------------------------ use & access

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        final Block b = event.getClickedBlock();
        if (b == null || (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK)) {
            return;
        }
        final Player player = event.getPlayer();
        final Shop bySign = this.shops.atSign(b);
        if (bySign != null) {
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                if (event.getHand() == EquipmentSlot.HAND) {
                    this.shops.info(player, bySign);
                }
                return; // breaking is decided in onBreak
            }
            event.setCancelled(true); // no sign editing, no item use on the sign
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
            if (bySign.account() != null && this.mayHandle(player, bySign, "open") && !player.hasPermission(PERM_ADMIN)
                || bySign.account() != null && bySign.owner().equals(player.getUniqueId())) {
                // people of the company never trade with their own company through its shop (no payouts past the rules)
                this.shops.info(player, bySign);
                this.m().send(player, "shop.firm-hint");
            } else if (bySign.account() == null && bySign.owner().equals(player.getUniqueId())) {
                this.shops.info(player, bySign);
                this.m().send(player, "shop.owner-hint");
            } else {
                this.shops.use(player, bySign);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Shop byChest = this.shops.atContainer(b);
        if (byChest != null && !this.mayHandle(player, byChest, "open")) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND) {
                this.denied(player, byChest);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(final BlockBreakEvent event) {
        final Shop s = this.shops.at(event.getBlock());
        if (s == null) {
            return;
        }
        final Player player = event.getPlayer();
        if (!this.mayHandle(player, s, "break")) {
            event.setCancelled(true);
            this.denied(player, s);
        }
    }

    /** The shop disappears only when the break really happens (no other plugin cancelled it). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(final BlockBreakEvent event) {
        final Shop s = this.shops.at(event.getBlock());
        if (s != null) {
            this.shops.remove(s, event.getPlayer().getName());
            this.m().send(event.getPlayer(), "shop.removed");
            if (s.account() != null) {
                this.shops.accounts().ask(s.account(), "removed", java.util.Map.of("shop", s.id(), "player", event.getPlayer().getUniqueId().toString()));
            }
        }
    }

    /** No new chest next to a shop chest (it would merge into a double chest the shop owner does not control). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        final Material type = event.getBlockPlaced().getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) {
            return;
        }
        for (final BlockFace face : HORIZONTAL) {
            final Shop s = this.shops.atContainer(event.getBlockPlaced().getRelative(face));
            if (s != null && !(s.account() == null ? s.owner().equals(event.getPlayer().getUniqueId()) : this.mayHandle(event.getPlayer(), s, "open"))) {
                event.setCancelled(true);
                this.denied(event.getPlayer(), s);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ environment

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopper(final InventoryMoveItemEvent event) {
        if (this.shops.ofInventory(event.getSource()) != null || this.shops.ofInventory(event.getDestination()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        event.blockList().removeIf(b -> this.shops.at(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        event.blockList().removeIf(b -> this.shops.at(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        for (final Block b : event.getBlocks()) {
            if (this.shops.at(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        for (final Block b : event.getBlocks()) {
            if (this.shops.at(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        if (this.shops.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(final EntityChangeBlockEvent event) {
        if (this.shops.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }
}
