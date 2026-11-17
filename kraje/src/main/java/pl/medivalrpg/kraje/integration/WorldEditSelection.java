// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.integration;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.manager.Notify;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * Chunks of a player's WorldEdit selection ({@code /kraj admin zajmij|porzuc <kraj> zaznaczenie}). WorldEdit is a
 * softdepend and this class references WorldEdit types, so callers touch it only after
 * {@code Bukkit.getPluginManager().isPluginEnabled("WorldEdit")} (see {@code AdminTerritory}).
 */
public final class WorldEditSelection {

    /** Name of the WorldEdit plugin (FastAsyncWorldEdit registers under the same name). */
    public static final String PLUGIN = "WorldEdit";

    private WorldEditSelection() {
    }

    /**
     * Chunks touched by the selection of {@code player} in its current world. A selection whose bounding box spans
     * more than {@code maxChunks} chunks is refused before its chunks are listed.
     *
     * @throws KrajeException {@code admin-area-no-selection} / {@code admin-area-selection-too-large}
     */
    public static List<Long> chunks(final Player player, final int maxChunks) {
        final Region region;
        try {
            final LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
            region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
        } catch (final IncompleteRegionException e) {
            throw new KrajeException("admin-area-no-selection");
        }
        if (region == null) {
            throw new KrajeException("admin-area-no-selection");
        }
        final BlockVector3 min = region.getMinimumPoint();
        final BlockVector3 max = region.getMaximumPoint();
        final long spanX = (long) (max.x() >> 4) - (min.x() >> 4) + 1;
        final long spanZ = (long) (max.z() >> 4) - (min.z() >> 4) + 1;
        if (spanX * spanZ > maxChunks) {
            throw new KrajeException("admin-area-selection-too-large",
                Notify.unparsed("size", spanX * spanZ), Notify.unparsed("max", maxChunks));
        }
        final List<Long> keys = new ArrayList<>();
        for (final BlockVector2 chunk : region.getChunks()) {
            keys.add(ChunkKeys.key(chunk.x(), chunk.z()));
        }
        return keys;
    }
}
