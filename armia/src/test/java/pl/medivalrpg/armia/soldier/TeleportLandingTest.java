// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * Przegląd C 2026-10-29: {@link SoldierController#landing} (teleport of a squad / relocation) must never probe the
 * height of — and so synchronously load — a neighbouring chunk that is not loaded.
 */
class TeleportLandingTest {

    /** World stub: only the listed chunks are loaded; a height probe outside them fails the test. */
    private static World world(final int groundY, final long... loadedChunks) {
        final Set<Long> loaded = new HashSet<>();
        for (final long k : loadedChunks) loaded.add(k);
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "isChunkLoaded" -> {
                    if (args != null && args.length == 2 && args[0] instanceof Integer x && args[1] instanceof Integer z) {
                        return loaded.contains(key(x, z));
                    }
                }
                case "getHighestBlockYAt" -> {
                    if (args != null && args.length == 2 && args[0] instanceof Integer x && args[1] instanceof Integer z) {
                        if (!loaded.contains(key(x >> 4, z >> 4))) {
                            throw new AssertionError("synchronous chunk load: height probe at " + x + "," + z);
                        }
                        return groundY;
                    }
                }
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "equals" -> { return proxy == args[0]; }
                case "toString" -> { return "world-stub"; }
                default -> { }
            }
            throw new UnsupportedOperationException(m.toString());
        });
    }

    private static long key(final int cx, final int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    @Test void formationSpotInUnloadedNeighbourChunkFallsBackToTarget() {
        // target at x=15.5 (chunk 0), formation spot 2 blocks east at x=17.5 (chunk 1, not loaded)
        final World w = world(70, key(0, 0));
        final Location where = new Location(w, 15.5, 71, 8.5);
        final Location to = SoldierController.landing(new Location(w, 17.5, 71, 8.5, 45f, 0f), where);
        assertEquals(15.5, to.getX());
        assertEquals(71, to.getY());
        assertEquals(8.5, to.getZ());
        assertEquals(45f, to.getYaw(), "the formation facing is kept");
    }

    @Test void formationSpotOnLoadedGroundIsUsed() {
        final World w = world(70, key(0, 0), key(1, 0));
        final Location where = new Location(w, 15.5, 71, 8.5);
        final Location to = SoldierController.landing(new Location(w, 17.5, 71, 8.5), where);
        assertEquals(17.5, to.getX());
        assertEquals(71.0, to.getY(), "standing on the highest block");
        assertEquals(8.5, to.getZ());
    }

    @Test void cliffOrRoofAtFormationSpotFallsBackToTarget() {
        // ground 10 blocks above the target (roof, cliff): unchanged rule, the soldier lands on the target
        final World w = world(80, key(0, 0));
        final Location where = new Location(w, 5.5, 64, 5.5);
        final Location to = SoldierController.landing(new Location(w, 7.5, 64, 5.5), where);
        assertEquals(5.5, to.getX());
        assertEquals(64, to.getY());
    }

    @Test void negativeCoordinatesUseFloorChunks() {
        // x=-0.5 is chunk -1; only chunk 0 loaded → fallback, no probe of chunk -1
        final World w = world(70, key(0, 0));
        final Location where = new Location(w, 1.5, 71, 1.5);
        final Location to = SoldierController.landing(new Location(w, -0.5, 71, 1.5), where);
        assertEquals(1.5, to.getX());
    }
}
