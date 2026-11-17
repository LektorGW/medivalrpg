// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.listener;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import pl.medivalrpg.ludnosc.service.GranaryService;
import pl.medivalrpg.ludnosc.service.SupplyDeliveryService;

/** Preserve the two exact inventories while intent/final receipt is pending or ambiguous. */
public final class SupplyProtectionListener implements Listener {
    private final SupplyDeliveryService supply;
    public SupplyProtectionListener(SupplyDeliveryService supply) { this.supply=supply; }
    private boolean player(org.bukkit.entity.HumanEntity p) { return supply.locked(p.getUniqueId()); }
    private boolean block(Block b) { return supply.locked(GranaryService.pos(b)); }
    private boolean topology(Block b) {
        if(block(b)) return true;
        for(var face:new BlockFace[]{BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST}) {
            int x=b.getX()+face.getModX(), z=b.getZ()+face.getModZ();
            if(supply.locked(new pl.medivalrpg.ludnosc.model.BlockPos(b.getWorld().getName(),x,b.getY(),z))) return true;
        }
        return false;
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e) {
        if(player(e.getWhoClicked()) || supply.locked(e.getView().getTopInventory()) || supply.locked(e.getView().getBottomInventory())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e) {
        if(player(e.getWhoClicked()) || supply.locked(e.getView().getTopInventory()) || supply.locked(e.getView().getBottomInventory())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void open(InventoryOpenEvent e) {
        if(player(e.getPlayer()) || supply.locked(e.getInventory())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void move(InventoryMoveItemEvent e) {
        if(supply.locked(e.getSource()) || supply.locked(e.getDestination())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void inventoryPickup(InventoryPickupItemEvent e) {
        if(supply.locked(e.getInventory())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent e) {
        if(e.getEntity() instanceof Player p && player(p)) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e) { if(player(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e) { if(player(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent e) { if(player(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void itemDamage(PlayerItemDamageEvent e) { if(player(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void mend(PlayerItemMendEvent e) { if(player(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e) {
        if(player(e.getPlayer()) || e.getClickedBlock()!=null && topology(e.getClickedBlock())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void entityInteract(PlayerInteractEntityEvent e) { if(player(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void armorStand(PlayerArmorStandManipulateEvent e) { if(player(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e) {
        if(player(e.getPlayer()) || topology(e.getBlock())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e) {
        if(player(e.getPlayer()) || topology(e.getBlockPlaced())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent e) {
        if(e.getEntity() instanceof Player p && player(p)) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent e) {
        if(player(e.getEntity())) { e.setKeepInventory(true); e.getDrops().clear(); }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void explode(EntityExplodeEvent e) { e.blockList().removeIf(this::topology); }
    @EventHandler(priority=EventPriority.HIGHEST) public void explode(BlockExplodeEvent e) { e.blockList().removeIf(this::topology); }
    @EventHandler(priority=EventPriority.HIGHEST) public void burn(BlockBurnEvent e) { if(block(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void fluid(BlockFromToEvent e) { if(topology(e.getToBlock())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void piston(BlockPistonExtendEvent e) {
        if(e.getBlocks().stream().anyMatch(b -> topology(b) || topology(b.getRelative(e.getDirection())))) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void piston(BlockPistonRetractEvent e) {
        if(e.getBlocks().stream().anyMatch(b -> topology(b) || topology(b.getRelative(e.getDirection())))) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void dispense(BlockDispenseEvent e) { if(block(e.getBlock())) e.setCancelled(true); }
}
