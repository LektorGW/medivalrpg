// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.szlaki;

import java.util.function.Predicate;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class InventoryLocks {
    private InventoryLocks() {}

    static boolean locked(Inventory inventory,Predicate<Block> blockLocked,Predicate<Entity> marked) {
        // No block-state snapshot: hoppers fire InventoryMoveItemEvent constantly and a snapshot copies the contents.
        InventoryHolder holder=inventory.getHolder(false);
        if(holder instanceof DoubleChest chest) {
            // A half's getInventory() can return this same merged inventory on Paper.
            return direct(chest.getLeftSide(false),blockLocked,marked)
                || direct(chest.getRightSide(false),blockLocked,marked);
        }
        return direct(holder,blockLocked,marked);
    }

    private static boolean direct(InventoryHolder holder,Predicate<Block> blockLocked,Predicate<Entity> marked) {
        if(holder instanceof Container container) return blockLocked.test(container.getBlock());
        if(holder instanceof Entity entity) return marked.test(entity);
        return false;
    }
}
