// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.limbo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/** Loads chunks asynchronously; all collision reads and the final choice run on the server thread. */
public final class SafeReturn {
    private final Plugin plugin;
    public SafeReturn(Plugin plugin) { this.plugin = plugin; }

    public CompletableFuture<Location> resolve(Location original) {
        return column(original).thenCompose(found -> {
            if (found != null) return CompletableFuture.completedFuture(found);
            Location spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
            return load(spawn, 1).thenApply(ignored -> {
                // This callback runs on the main thread (load completes there).
                for (int r = 0; r <= 8; r++) for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                    Location candidate = spawn.clone().add(x, 0, z);
                    for (double y = Math.min(spawn.getWorld().getMaxHeight() - 2, spawn.getY() + 32);
                         y >= spawn.getWorld().getMinHeight(); y -= SafePosition.DEPTH) {
                        candidate.setY(y);
                        Location safe = select(candidate);
                        if (safe != null) return safe;
                    }
                }
                throw new IllegalStateException("Brak bezpiecznego gruntu przy spawnie serwera");
            });
        });
    }

    private CompletableFuture<Location> column(Location location) {
        if (location == null || location.getWorld() == null) return CompletableFuture.completedFuture(null);
        return load(location, 1).thenApply(ignored -> select(location));
    }

    private CompletableFuture<Void> load(Location location, int radius) {
        List<CompletableFuture<?>> chunks = new ArrayList<>();
        int cx = location.getBlockX() >> 4, cz = location.getBlockZ() >> 4;
        for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++)
            chunks.add(location.getWorld().getChunkAtAsync(x, z, true));
        CompletableFuture<Void> main = new CompletableFuture<>();
        CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).whenComplete((v, error) -> {
            Runnable done = () -> { if (error == null) main.complete(null); else main.completeExceptionally(error); };
            if (Bukkit.isPrimaryThread()) done.run(); else Bukkit.getScheduler().runTask(plugin, done);
        });
        return main;
    }

    private static Location select(Location at) {
        World world = at.getWorld();
        var y = SafePosition.select(at.getY(), world.getMinHeight(), world.getMaxHeight() - 1,
            blockY -> {
                Block block = world.getBlockAt(at.getBlockX(), blockY, at.getBlockZ());
                if (hazard(block.getType()) || block.isLiquid()) return Double.NaN;
                double top = Double.NaN;
                for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                    // Voxel boxes are relative to the block. Require the centre of the feet to be supported.
                    double x = at.getX() - at.getBlockX(), z = at.getZ() - at.getBlockZ();
                    if (x >= box.getMinX() && x <= box.getMaxX() && z >= box.getMinZ() && z <= box.getMaxZ())
                        top = Double.isNaN(top) ? blockY + box.getMaxY() : Math.max(top, blockY + box.getMaxY());
                }
                return top;
            }, feet -> clear(at, feet));
        if (y.isEmpty()) return null;
        Location result = at.clone();
        result.setY(y.getAsDouble());
        return world.getWorldBorder().isInside(result) ? result : null;
    }

    private static boolean clear(Location at, double y) {
        World w = at.getWorld();
        BoundingBox body = new BoundingBox(at.getX() - .3, y + .00001, at.getZ() - .3,
            at.getX() + .3, y + 1.8, at.getZ() + .3);
        for (int x = (int)Math.floor(body.getMinX()); x <= (int)Math.floor(body.getMaxX()); x++)
            for (int z = (int)Math.floor(body.getMinZ()); z <= (int)Math.floor(body.getMaxZ()); z++)
                for (int by = (int)Math.floor(y) - 1; by <= (int)Math.floor(y + 1.8); by++) {
                    if (by < w.getMinHeight() || by >= w.getMaxHeight()) return false;
                    Block block = w.getBlockAt(x, by, z);
                    if (block.isLiquid() || hazard(block.getType())) return false;
                    for (BoundingBox box : block.getCollisionShape().getBoundingBoxes())
                        if (body.overlaps(box.clone().shift(x, by, z))) return false;
                }
        return true;
    }

    private static boolean hazard(Material material) {
        return switch (material) {
            case LAVA, WATER, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE,
                 SWEET_BERRY_BUSH, WITHER_ROSE, POWDER_SNOW, POINTED_DRIPSTONE, NETHER_PORTAL,
                 END_PORTAL, END_GATEWAY -> true;
            default -> false;
        };
    }
}
