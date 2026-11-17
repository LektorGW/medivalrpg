// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.arena;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import java.util.Optional;
import org.bukkit.entity.Player;

/**
 * Bounding box of a player's WorldEdit selection. WorldEdit is a softdepend and this class references its types, so
 * callers touch it only after {@code Bukkit.getPluginManager().isPluginEnabled("WorldEdit")}.
 */
public final class WorldEditSelection {

    public static final String PLUGIN = "WorldEdit";

    private WorldEditSelection() {
    }

    /** Empty when there is no complete selection in the player's world. */
    public static Optional<Box> box(final Player player) {
        try {
            final LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
            final Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            if (region == null) {
                return Optional.empty();
            }
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            return Optional.of(Box.of(player.getWorld().getName(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z()));
        } catch (final IncompleteRegionException e) {
            return Optional.empty();
        }
    }
}
