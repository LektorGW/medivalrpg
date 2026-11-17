// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.build;

import java.util.List;
import org.bukkit.World;

/**
 * Creates the WorldGuard region of the spawn. The only implementation ({@code WorldGuardRegion}) is instantiated
 * by reflection when WorldGuard is enabled.
 */
public interface RegionProtector {

    /**
     * @param greeting  greeting text in legacy {@code &} codes (WorldGuard formats it itself)
     * @param farewell  farewell text in legacy {@code &} codes
     * @param denySpawn entity keys for {@code deny-spawn} (e.g. {@code minecraft:zombie}); empty = {@code mob-spawning deny}
     */
    record Spec(String id, int priority, boolean invincible, String greeting, String farewell, List<String> denySpawn) {
    }

    /** Creates or replaces the region and saves the region file. Returns the number of flags set. Main thread. */
    int apply(World world, Box box, Spec spec) throws Exception;

    /** Removes the region (and saves); {@code false} when it did not exist. */
    boolean remove(World world, String id) throws Exception;

    /** {@code true} when a region with this id exists. */
    boolean exists(World world, String id);
}
