// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import pl.medivalrpg.kraje.protection.ProtectionService.Flag;

/**
 * Protection against actions of players who are not members of the country owning the land.
 */
public final class ProtectionListener implements Listener {

    private final ProtectionService protection;

    public ProtectionListener(final ProtectionService protection) {
        this.protection = protection;
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (!this.protection.check(event.getPlayer(), event.getBlock(), Flag.BLOCK_BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        final Player player = event.getPlayer();
        if (!this.protection.check(player, event.getBlockPlaced(), Flag.BLOCK_PLACE)) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (final BlockState state : multi.getReplacedBlockStates()) {
                if (!this.protection.check(player, state.getLocation(), Flag.BLOCK_PLACE)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (block == null || !this.protection.enabled()) {
            return;
        }
        final UUID owner = this.protection.owner(block);
        if (owner == null) {
            return;
        }
        final Player player = event.getPlayer();
        if (this.protection.isTrusted(player, owner)) {
            return;
        }
        final Action action = event.getAction();
        Flag flag = null;
        if (action == Action.PHYSICAL) {
            flag = ProtectionService.classifyPhysical(block);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            flag = ProtectionService.classifyInteraction(block);
            if (flag == null && ProtectionService.isWorldModifyingItem(event.getMaterial())) {
                flag = Flag.BLOCK_BREAK;
            }
        } else if (action == Action.LEFT_CLICK_BLOCK && block.getType() == Material.DRAGON_EGG) {
            flag = Flag.BLOCK_BREAK;
        }
        if (flag != null && !this.protection.canAct(player, owner, flag) && !this.protection.siegeAllows(player, block, flag)) {
            event.setCancelled(true);
            if (action != Action.PHYSICAL) {
                this.protection.notifyDenied(player, owner);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        if (!this.protection.check(event.getPlayer(), event.getBlock(), Flag.BUCKETS)
            || !this.protection.check(event.getPlayer(), event.getBlockClicked(), Flag.BUCKETS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        if (!this.protection.check(event.getPlayer(), event.getBlockClicked(), Flag.BUCKETS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEntity(final PlayerBucketEntityEvent event) {
        if (!this.protection.check(event.getPlayer(), event.getEntity().getLocation(), Flag.ANIMALS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            player = ProtectionService.responsiblePlayer(event.getIgnitingEntity());
        }
        if (player != null && !this.protection.canPrimeTnt(player,event.getBlock()) && !this.protection.check(player, event.getBlock(), Flag.FIRE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTntPrime(final TNTPrimeEvent event) {
        final Player player = ProtectionService.responsiblePlayer(event.getPrimingEntity());
        if (player != null && !this.protection.canPrimeTnt(player,event.getBlock()) && !this.protection.check(player, event.getBlock(), Flag.FIRE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Player player
            && !this.protection.check(player, event.getBlock(), Flag.BLOCK_BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShearBlock(final PlayerShearBlockEvent event) {
        if (!this.protection.check(event.getPlayer(), event.getBlock(), Flag.BLOCK_BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHarvest(final PlayerHarvestBlockEvent event) {
        if (!this.protection.check(event.getPlayer(), event.getHarvestedBlock(), Flag.OTHER)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLecternTake(final PlayerTakeLecternBookEvent event) {
        if (!this.protection.check(event.getPlayer(), event.getLectern().getLocation(), Flag.CONTAINERS)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ entities

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        final Player attacker = ProtectionService.responsiblePlayer(event.getDamager());
        if (attacker == null) {
            return;
        }
        final Entity victim = event.getEntity();
        final Flag flag;
        if (victim instanceof Animals) {
            if (victim instanceof Tameable tameable && attacker.getUniqueId().equals(tameable.getOwnerUniqueId())) {
                return;
            }
            flag = Flag.ANIMALS;
        } else if (victim instanceof ArmorStand || victim instanceof Hanging || victim instanceof EnderCrystal) {
            flag = Flag.DECORATIONS;
        } else {
            return;
        }
        if (!this.protection.check(attacker, victim.getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        final Entity entity = event.getRightClicked();
        final Flag flag;
        if (entity instanceof ItemFrame) {
            flag = Flag.DECORATIONS;
        } else if (entity instanceof StorageMinecart || entity instanceof HopperMinecart || entity instanceof ChestBoat) {
            flag = Flag.CONTAINERS;
        } else if (entity instanceof AbstractHorse horse && horse.isTamed()
            && !event.getPlayer().getUniqueId().equals(horse.getOwnerUniqueId())) {
            flag = Flag.ANIMALS;
        } else {
            return;
        }
        if (!this.protection.check(event.getPlayer(), entity.getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(final PlayerArmorStandManipulateEvent event) {
        if (!this.protection.check(event.getPlayer(), event.getRightClicked().getLocation(), Flag.DECORATIONS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(final HangingBreakByEntityEvent event) {
        final Player player = ProtectionService.responsiblePlayer(event.getRemover());
        if (player != null && !this.protection.check(player, event.getEntity().getLocation(), Flag.DECORATIONS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(final HangingPlaceEvent event) {
        final Player player = event.getPlayer();
        if (player != null && !this.protection.check(player, event.getEntity().getLocation(), Flag.BLOCK_PLACE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(final EntityPlaceEvent event) {
        final Player player = event.getPlayer();
        if (player != null && !this.protection.check(player, event.getEntity().getLocation(), Flag.BLOCK_PLACE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(final VehicleDestroyEvent event) {
        final Player player = ProtectionService.responsiblePlayer(event.getAttacker());
        if (player != null && !this.protection.check(player, event.getVehicle().getLocation(), Flag.DECORATIONS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShearEntity(final PlayerShearEntityEvent event) {
        if (!this.protection.check(event.getPlayer(), event.getEntity().getLocation(), Flag.ANIMALS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeash(final PlayerLeashEntityEvent event) {
        if (!this.protection.check(event.getPlayer(), event.getEntity().getLocation(), Flag.ANIMALS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.protection.forget(event.getPlayer().getUniqueId());
    }
}
