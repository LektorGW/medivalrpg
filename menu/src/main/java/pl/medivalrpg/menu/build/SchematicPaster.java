// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.build;

import java.io.File;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * Pastes a schematic through WorldEdit. The only implementation ({@code WorldEditPaster}) is instantiated by
 * reflection when WorldEdit is enabled, so Menu loads without WorldEdit.
 */
public interface SchematicPaster {

    /** Extra block changes recorded in the same edit session (so one undo reverts everything). */
    @FunctionalInterface
    interface BlockSink {
        void set(int x, int y, int z, BlockData data);
    }

    /** Handle of the finished edit session. */
    interface Undo {
        /** Number of changed blocks. */
        int changed();

        /** Reverts the paste and every change made through the {@link BlockSink}. Main thread. */
        void undo();
    }

    /**
     * Pastes {@code schematic} with its origin at (x, y, z) (like {@code //paste} standing there), then runs
     * {@code after} with a sink writing into the same session. Main thread.
     *
     * @param ignoreAir like {@code //paste -a}
     */
    Undo paste(World world, File schematic, int x, int y, int z, boolean ignoreAir, Player actor,
               Consumer<BlockSink> after) throws Exception;
}
