// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.nms;

import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A normal multi-block update (same packet type and wire format) carrying client-only snow changes of one section.
 * The packet handler recognises it by class and updates the connection's {@code SnowTracker} exactly when the packet
 * reaches the network, so the tracker always matches what the client was told.
 */
public final class SnowSectionPacket extends ClientboundSectionBlocksUpdatePacket {

    /** Packed {@code BlockPos#asLong} of snow layers this packet shows. */
    final long[] added;
    /** Packed positions of snow layers this packet replaces with the real blocks. */
    final long[] removed;

    /**
     * @param changes position in section ({@code SectionPos#sectionRelativePos}) → state shown to the client
     */
    public SnowSectionPacket(final SectionPos section, final Short2ObjectMap<BlockState> changes, final long[] added,
                             final long[] removed) {
        super(section, changes);
        this.added = added;
        this.removed = removed;
    }
}
