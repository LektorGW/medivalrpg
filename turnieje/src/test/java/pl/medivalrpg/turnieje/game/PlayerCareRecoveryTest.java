// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.turnieje.data.*;

class PlayerCareRecoveryTest {
    @TempDir Path dir;
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    static Object defaultValue(Class<?> c) {
        if (c==boolean.class) return false;
        if (c==int.class) return 0;
        if (c==double.class) return 0.0;
        if (c==float.class) return 0f;
        if (c==long.class) return 0L;
        return null;
    }
    @Test void incompleteInventorySnapshotIsRejectedBeforeClearingItems() {
        assertThrows(IllegalArgumentException.class, () -> PlayerCare.decode(null, 41, new ArrayList<>()));
        assertThrows(IllegalArgumentException.class, () -> PlayerCare.decode(List.of(), 41, new ArrayList<>()));
    }
    @Test void deadPlayerKeepsSnapshotForRespawn() {
        var store=new Storage(dir,Logger.getAnonymousLogger()); store.load(); var id=UUID.randomUUID();
        var snap=new Snapshot(); snap.session="capture"; store.putSnapshot(id,snap);
        var player=proxy(Player.class,(p,m,a)->switch(m.getName()) {
            case "getUniqueId" -> id; case "isDead" -> true; default -> throw new AssertionError(m.getName()); });
        assertFalse(new PlayerCare(null,store,()->null,null).restore(player));
        assertTrue(store.hasSnapshot(id)); store.shutdown();
    }
    @Test void durableReceiptSkipsInventoryReplayAndRetiresSnapshot() {
        receiptCase(false);
    }
    @Test void failedPlayerSaveKeepsSnapshotEvenWithInMemoryReceipt() {
        receiptCase(true);
    }
    void receiptCase(boolean failSave) {
        var store=new Storage(dir,Logger.getAnonymousLogger()); store.load(); var id=UUID.randomUUID();
        var snap=new Snapshot(); snap.session="capture"; snap.inventoryStored=true; store.putSnapshot(id,snap);
        var plugin=proxy(Plugin.class,(p,m,a)->(m.getName().equals("getName") || m.getName().equals("namespace")) ? "turnieje" : defaultValue(m.getReturnType()));
        var keys=new Keys(plugin); var calls=new ArrayList<String>();
        var data=proxy(PersistentDataContainer.class,(p,m,a)-> {
            if (m.getName().equals("get")) return "capture";
            throw new AssertionError(m.getName());
        });
        var player=proxy(Player.class,(p,m,a)->switch(m.getName()) {
            case "getUniqueId" -> id; case "isDead" -> false; case "getPersistentDataContainer" -> data;
            case "saveData" -> { calls.add("save"); assertTrue(store.hasSnapshot(id));
                if(failSave) throw new IllegalStateException("disk"); yield null; }
            default -> throw new AssertionError("Receipt must not replay inventory: "+m.getName());
        });
        var care=new PlayerCare(null,store,()->null,keys);
        if(failSave) assertThrows(IllegalStateException.class,()->care.restore(player));
        else assertTrue(care.restore(player));
        assertEquals(List.of("save"),calls); assertEquals(failSave,store.hasSnapshot(id)); store.shutdown();
    }
    /** Snapshot of a kit tournament: 41 empty slots (decoding them needs no server). */
    static Snapshot kitSnapshot(boolean withLocation) {
        var snap=new Snapshot(); snap.session="capture"; snap.inventoryStored=true;
        for(int i=0;i<41;i++) snap.items.add(null);
        if(withLocation) snap.location=new pl.medivalrpg.turnieje.arena.Pos("world",1,64,1,0,0);
        return snap;
    }
    /** Player whose return teleport is refused (e.g. login limbo); every call is recorded. */
    Player refusingPlayer(UUID id,List<String> calls) {
        var data=proxy(PersistentDataContainer.class,(p,m,a)-> {
            if (m.getName().equals("get")) return "another-session";
            throw new AssertionError(m.getName());
        });
        var inv=proxy(PlayerInventory.class,(p,m,a)-> {
            if(m.getName().equals("getSize")) return 41;
            calls.add("inventory."+m.getName()); return defaultValue(m.getReturnType());
        });
        return proxy(Player.class,(p,m,a)->switch(m.getName()) {
            case "getUniqueId" -> id; case "isDead", "isInsideVehicle" -> false; case "getPersistentDataContainer" -> data;
            case "getInventory" -> inv;
            case "teleport" -> { calls.add("teleport"); yield false; }
            default -> { calls.add(m.getName()); yield defaultValue(m.getReturnType()); }
        });
    }
    Keys keys() {
        var plugin=proxy(Plugin.class,(p,m,a)->(m.getName().equals("getName") || m.getName().equals("namespace")) ? "turnieje" : defaultValue(m.getReturnType()));
        return new Keys(plugin);
    }
    @Test void refusedReturnTeleportLeavesInventoryAndSnapshotUntouched() {
        var store=new Storage(dir,Logger.getAnonymousLogger()); store.load(); var id=UUID.randomUUID();
        store.putSnapshot(id,kitSnapshot(true));
        var calls=new ArrayList<String>();
        var care=new PlayerCare(null,store,()->null,keys());
        var e=assertThrows(IllegalStateException.class,()->care.restore(refusingPlayer(id,calls),pos->new org.bukkit.Location(null,pos.x(),pos.y(),pos.z())));
        assertEquals("Teleport powrotny anulowany",e.getMessage());
        // Nothing restored that another plugin (Logowanie limbo) could keep while the snapshot is still pending.
        assertEquals(List.of("closeInventory","teleport"),calls);
        assertTrue(store.hasSnapshot(id)); assertFalse(care.isOwnTeleport(id)); store.shutdown();
    }
    @Test void missingReturnWorldIsDetectedBeforeTheInventoryIsTouched() {
        var store=new Storage(dir,Logger.getAnonymousLogger()); store.load(); var id=UUID.randomUUID();
        store.putSnapshot(id,kitSnapshot(true));
        var calls=new ArrayList<String>();
        var care=new PlayerCare(null,store,()->null,keys());
        assertThrows(IllegalStateException.class,()->care.restore(refusingPlayer(id,calls),pos->null));
        assertEquals(List.of(),calls); assertTrue(store.hasSnapshot(id)); store.shutdown();
    }
    @Test void cursorItemBlocksCaptureOnlyWhenPresent() {
        var held=new org.bukkit.inventory.ItemStack() { @Override public boolean isEmpty() { return false; } };
        var empty=new org.bukkit.inventory.ItemStack() { @Override public boolean isEmpty() { return true; } };
        for (var cursor : new org.bukkit.inventory.ItemStack[] {null, empty, held}) {
            var player=proxy(Player.class,(p,m,a)-> { if(m.getName().equals("getItemOnCursor")) return cursor; throw new AssertionError(m.getName()); });
            assertEquals(cursor!=held,PlayerCare.canCapture(player));
        }
    }
    @Test void logoutClearsLoanedInventoryButKeepsOriginalSnapshot() {
        var store=new Storage(dir,Logger.getAnonymousLogger()); store.load(); var id=UUID.randomUUID();
        var snap=new Snapshot(); snap.session="capture"; snap.inventoryStored=true; snap.items.add("original"); store.putSnapshot(id,snap);
        var calls=new ArrayList<String>();
        var inv=proxy(PlayerInventory.class,(p,m,a)-> { if(m.getName().equals("clear")) { calls.add("clear"); return null; }
            throw new AssertionError(m.getName()); });
        var player=proxy(Player.class,(p,m,a)->switch(m.getName()) {
            case "getUniqueId" -> id; case "isInsideVehicle" -> false; case "getInventory" -> inv;
            case "setItemOnCursor" -> {calls.add("cursor"); yield null;} default -> throw new AssertionError(m.getName());
        });
        new PlayerCare(null,store,()->null,null).onQuitInside(player);
        assertEquals(List.of("clear","cursor"),calls); assertEquals("original",store.snapshot(id).items.getFirst()); store.shutdown();
    }
}
