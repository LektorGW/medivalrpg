// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import pl.medivalrpg.armia.config.ArmiaConfig;

/**
 * Buława dowódcy: right click a block → the squad goes there; right click a creature (also from afar) → attack it;
 * sneak + right click → follow me / stop. The item cannot be used in crafting or placed.
 */
public final class MaceListener implements Listener {

    private final ArmyService army;
    private final MaceItem mace;
    private final Supplier<ArmiaConfig> config;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public MaceListener(final ArmyService army, final MaceItem mace, final Supplier<ArmiaConfig> config) {
        this.army = army;
        this.mace = mace;
        this.config = config;
    }

    private boolean cooled(final Player p) {
        final long now = System.currentTimeMillis();
        final Long last = this.cooldown.get(p.getUniqueId());
        if (last != null && now - last < 300) {
            return false;
        }
        this.cooldown.put(p.getUniqueId(), now);
        return true;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !this.mace.isMace(event.getItem())) {
            return;
        }
        final Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        final Player p = event.getPlayer();
        if (!this.cooled(p)) {
            return;
        }
        if (p.isSneaking()) {
            this.army.toggle(p);
            return;
        }
        final int range = this.config.get().maceRange;
        final LivingEntity target = this.army.lookedAt(p, range);
        if (target != null) {
            this.army.attack(p, target);
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            final RayTraceResult hit = p.rayTraceBlocks(range);
            block = hit == null ? null : hit.getHitBlock();
        }
        if (block != null) {
            this.army.moveSquad(p, ArmyService.standOn(block));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUseOnEntity(final PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player p = event.getPlayer();
        if (!this.mace.isMace(p.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);
        if (!this.cooled(p)) {
            return;
        }
        if (p.isSneaking()) {
            this.army.toggle(p);
        } else if (event.getRightClicked() instanceof LivingEntity le) {
            this.army.attack(p, le);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(final PrepareItemCraftEvent event) {
        for (final ItemStack item : event.getInventory().getMatrix()) {
            if (this.mace.isMace(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (this.mace.isMace(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }
}
