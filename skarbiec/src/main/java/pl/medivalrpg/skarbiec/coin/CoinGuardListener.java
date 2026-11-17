// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.coin;

import org.bukkit.block.Crafter;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;

/**
 * Keeps coins money: they cannot be crafted (nuggets → ingots, lanterns, chains…), renamed in an anvil, burnt,
 * smelted, offered to villagers or picked up by mobs (piglins, allays, foxes).
 */
public final class CoinGuardListener implements Listener {

    private final CoinService coins;

    public CoinGuardListener(final CoinService coins) {
        this.coins = coins;
    }

    private boolean anyCoin(final ItemStack[] items) {
        for (final ItemStack item : items) {
            if (this.coins.isCoin(item)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ crafting

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreative(final org.bukkit.event.inventory.InventoryCreativeEvent event) {
        if(this.coins.closedSupply() && (this.coins.containsCoins(event.getCursor()) || this.coins.containsCoins(event.getCurrentItem())))event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDespawn(final org.bukkit.event.entity.ItemDespawnEvent event) {
        if(this.coins.closedSupply() && this.coins.containsCoins(event.getEntity().getItemStack()))event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCoinDamage(final org.bukkit.event.entity.EntityDamageEvent event) {
        if(this.coins.closedSupply() && event.getEntity() instanceof org.bukkit.entity.Item item && this.coins.containsCoins(item.getItemStack()))event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(final PrepareItemCraftEvent event) {
        if (this.anyCoin(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCraft(final CraftItemEvent event) {
        if (this.anyCoin(event.getInventory().getMatrix())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCrafter(final CrafterCraftEvent event) {
        if (event.getBlock().getState(false) instanceof Crafter crafter
            && this.anyCoin(crafter.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ anvil

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(final PrepareAnvilEvent event) {
        final AnvilInventory inv = event.getInventory();
        if (this.coins.isCoin(inv.getFirstItem()) || this.coins.isCoin(inv.getSecondItem())) {
            event.setResult(null);
        }
    }

    // ------------------------------------------------------------------ furnaces

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBurn(final FurnaceBurnEvent event) {
        if (this.coins.isCoin(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSmelt(final FurnaceSmeltEvent event) {
        if (this.coins.isCoin(event.getSource())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ villagers & anvil result

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        final Inventory top = event.getView().getTopInventory();
        if (top instanceof AnvilInventory && event.getRawSlot() == 2
            && (this.coins.isCoin(((AnvilInventory) top).getFirstItem()) || this.coins.isCoin(((AnvilInventory) top).getSecondItem()))) {
            event.setCancelled(true);
            return;
        }
        if (!(top instanceof MerchantInventory)) {
            return;
        }
        // Merchant input slots are raw slots 0 and 1.
        final boolean intoInput = event.getRawSlot() == 0 || event.getRawSlot() == 1;
        if (intoInput && (this.coins.isCoin(event.getCursor()) || this.hotbarCoin(event))) {
            event.setCancelled(true);
        } else if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
            && event.getClickedInventory() != top && this.coins.isCoin(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    private boolean hotbarCoin(final InventoryClickEvent event) {
        if (event.getAction() != InventoryAction.HOTBAR_SWAP) {
            return false;
        }
        final HumanEntity who = event.getWhoClicked();
        final int button = event.getHotbarButton();
        final ItemStack swapped = button >= 0 ? who.getInventory().getItem(button) : who.getInventory().getItemInOffHand();
        return this.coins.isCoin(swapped);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(final InventoryDragEvent event) {
        if (event.getView().getTopInventory() instanceof MerchantInventory
            && (event.getRawSlots().contains(0) || event.getRawSlots().contains(1))
            && this.coins.isCoin(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ mobs

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player) && this.coins.isCoin(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ look refresh

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        this.coins.normalize(event.getPlayer());
    }
}
