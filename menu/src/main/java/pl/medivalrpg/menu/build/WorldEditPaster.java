// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.build;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * WorldEdit 7.4 implementation of {@link SchematicPaster}. Loaded only by reflection when WorldEdit is enabled
 * (this is the only class – with {@link WorldGuardRegion} – that touches WorldEdit classes).
 */
public final class WorldEditPaster implements SchematicPaster {

    @Override
    public Undo paste(final World world, final File schematic, final int x, final int y, final int z,
                      final boolean ignoreAir, final Player actor, final Consumer<BlockSink> after) throws Exception {
        final ClipboardFormat format = ClipboardFormats.findByPath(schematic.toPath());
        if (format == null) {
            throw new IOException("nieznany format schematu: " + schematic.getName());
        }
        final Clipboard clipboard;
        try (InputStream in = Files.newInputStream(schematic.toPath()); ClipboardReader reader = format.getReader(in)) {
            clipboard = reader.read();
        }
        final com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        final BukkitPlayer weActor = actor == null ? null : BukkitAdapter.adapt(actor);
        final EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
            .world(weWorld)
            .actor(weActor)
            .maxBlocks(-1)
            .build();
        try (session) {
            Operations.complete(new ClipboardHolder(clipboard).createPaste(session)
                .to(BlockVector3.at(x, y, z))
                .ignoreAirBlocks(ignoreAir)
                .copyEntities(true)
                .build());
            after.accept((bx, by, bz, data) -> {
                try {
                    session.setBlock(BlockVector3.at(bx, by, bz), BukkitAdapter.adapt(data));
                } catch (final com.sk89q.worldedit.MaxChangedBlocksException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        if (weActor != null) {
            // also available as //undo for the admin
            WorldEdit.getInstance().getSessionManager().get(weActor).remember(session);
        }
        return new Undo() {
            @Override
            public int changed() {
                return session.getBlockChangeCount();
            }

            @Override
            public void undo() {
                try (EditSession revert = WorldEdit.getInstance().newEditSessionBuilder()
                    .world(weWorld).actor(weActor).maxBlocks(-1).build()) {
                    session.undo(revert);
                }
            }
        };
    }
}
