// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.szlaki;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class InventoryLocksTest {
    private static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }

    private static AssertionError unexpected(String method) {
        return new AssertionError("Unexpected Bukkit call: "+method);
    }

    private static Block block() {
        return proxy(Block.class,(p,m,a)-> { throw unexpected(m.getName()); });
    }

    private static Inventory inventory(InventoryHolder holder) {
        return proxy(Inventory.class,(p,m,a)-> {
            if(m.getName().equals("getHolder")) return noSnapshot(a,holder);
            throw unexpected(m.getName());
        });
    }

    /** Hopper events are hot: the lock check must ask for the holder without a block-state snapshot. */
    private static <T> T noSnapshot(Object[] args,T holder) {
        if(args==null || args.length!=1 || !Boolean.FALSE.equals(args[0])) throw new AssertionError("Holder requested with a block-state snapshot");
        return holder;
    }

    private static Container container(Block block,Inventory[] merged,int[] inventoryReads) {
        return proxy(Container.class,(p,m,a)-> switch(m.getName()) {
            case "getBlock" -> block;
            case "getInventory" -> {
                inventoryReads[0]++;
                // Model Paper's cyclic merged inventory, but fail fast under the old algorithm.
                if(inventoryReads[0]>4) throw new AssertionError("Recursive merged inventory traversal");
                yield merged[0];
            }
            default -> throw unexpected(m.getName());
        });
    }

    @ParameterizedTest @CsvSource({"false,false","true,false","false,true","true,true"})
    void doubleChestChecksEitherPhysicalBlockWithoutReenteringMergedInventory(boolean leftLocked,boolean rightLocked) {
        Block left=block(),right=block();
        Inventory[] merged=new Inventory[1];
        int[] inventoryReads={0};
        Container leftHolder=container(left,merged,inventoryReads),rightHolder=container(right,merged,inventoryReads);
        DoubleChest[] holder=new DoubleChest[1];
        merged[0]=proxy(DoubleChestInventory.class,(p,m,a)-> switch(m.getName()) {
            case "getHolder" -> noSnapshot(a,holder[0]);
            case "getLeftSide" -> inventory(leftHolder);
            case "getRightSide" -> inventory(rightHolder);
            default -> throw unexpected(m.getName());
        });
        holder[0]=new DoubleChest((DoubleChestInventory)merged[0]);
        Predicate<Block> locked=b-> (b==left && leftLocked) || (b==right && rightLocked);
        assertEquals(leftLocked || rightLocked,InventoryLocks.locked(merged[0],locked,e->{ throw unexpected("entity"); }));
        assertEquals(0,inventoryReads[0],"A chest half's inventory must never be read");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void singleContainerUsesItsOwnBlock(boolean locked) {
        Block block=block();
        int[] inventoryReads={0};
        Container holder=container(block,new Inventory[1],inventoryReads);
        assertEquals(locked,InventoryLocks.locked(inventory(holder),b->{ assertSame(block,b); return locked; },e->{ throw unexpected("entity"); }));
        assertEquals(0,inventoryReads[0]);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void inventoryEntityUsesShipmentMarker(boolean marked) {
        StorageMinecart holder=proxy(StorageMinecart.class,(p,m,a)-> { throw unexpected(m.getName()); });
        assertEquals(marked,InventoryLocks.locked(inventory(holder),b->{ throw unexpected("block"); },e->{ assertSame(holder,e); return marked; }));
    }

    @Test void unknownHolderRemainsUnlockedWithoutReadingItsInventory() {
        InventoryHolder holder=()-> { throw unexpected("getInventory"); };
        assertFalse(InventoryLocks.locked(inventory(holder),b->{ throw unexpected("block"); },e->{ throw unexpected("entity"); }));
    }

    @Test void absentHolderRemainsUnlocked() {
        assertFalse(InventoryLocks.locked(inventory(null),b->{ throw unexpected("block"); },e->{ throw unexpected("entity"); }));
    }
}
