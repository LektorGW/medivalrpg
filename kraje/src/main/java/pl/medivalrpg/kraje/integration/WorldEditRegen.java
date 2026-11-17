// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.integration;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionIntersection;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.RegenOptions;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.World;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * Regenerates whole chunks from the world's generator and seed through the WorldEdit API
 * ({@code /kraj admin przywroc}). WorldEdit generates the chunks in a temporary world with the same seed and copies
 * every block (and the biomes) over the real ones; block entities (chests…) are replaced, entities are not touched.
 * Synchronous, main thread only. WorldEdit is a softdepend: callers touch this class only after
 * {@code Bukkit.getPluginManager().isPluginEnabled("WorldEdit")}.
 */
public final class WorldEditRegen {

    private static final SideEffectSet SIDE_EFFECTS = SideEffectSet.none()
        .with(SideEffect.LIGHTING, SideEffect.State.ON)
        .with(SideEffect.POI_UPDATE, SideEffect.State.ON)
        .with(SideEffect.NETWORK, SideEffect.State.ON);

    private WorldEditRegen() {
    }

    /**
     * Regenerates the given chunks of {@code world} (full height) in one WorldEdit operation.
     *
     * @return {@code false} when WorldEdit reports that it could not regenerate
     * @throws RuntimeException any WorldEdit / generator failure
     */
    public static boolean regenerate(final World world, final List<Long> chunks) {
        final com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        final int minY = world.getMinHeight();
        final int maxY = world.getMaxHeight() - 1;
        final List<Region> parts = new ArrayList<>(chunks.size());
        for (final long key : chunks) {
            final int bx = ChunkKeys.x(key) << 4;
            final int bz = ChunkKeys.z(key) << 4;
            parts.add(new CuboidRegion(weWorld, BlockVector3.at(bx, minY, bz), BlockVector3.at(bx + 15, maxY, bz + 15)));
        }
        final Region region = parts.size() == 1 ? parts.getFirst() : new RegionIntersection(weWorld, parts);
        // No seed override: the original seed is used (WorldEdit for 26.1+ does not support another one anyway).
        final RegenOptions options = RegenOptions.builder().regenBiomes(true).build();
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(weWorld).maxBlocks(-1).build()) {
            // Straight into the world: no reordering buffer, no block physics / neighbour updates (a fresh copy of
            // generated terrain must not start flowing or dropping), only lighting, POI and the client updates.
            session.disableBuffering();
            session.setSideEffectApplier(SIDE_EFFECTS);
            return weWorld.regenerate(region, session, options);
        }
    }
}
