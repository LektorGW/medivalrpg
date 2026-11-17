// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.nms;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which blocks may carry a client-only snow layer: the same rule as vanilla snow (full top face of the collision
 * shape — full blocks, leaves, upside-down slabs/stairs), minus ice, snow itself, blocks with block entities (chests,
 * furnaces, signs…) and the configured deny list. Results are cached per block state id; safe from any thread.
 */
public final class SnowRules {

    private static final byte UNKNOWN = 0;
    private static final byte YES = 1;
    private static final byte NO = 2;

    private final Set<Block> deny;
    private volatile byte[] cache = new byte[0];

    public SnowRules(final Set<String> denyIds) {
        final java.util.HashSet<Block> blocks = new java.util.HashSet<>();
        for (final String id : denyIds) {
            final Identifier key = Identifier.tryParse(id);
            if (key != null) {
                BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
            }
        }
        blocks.add(Blocks.ICE);
        blocks.add(Blocks.PACKED_ICE);
        blocks.add(Blocks.BLUE_ICE);
        blocks.add(Blocks.FROSTED_ICE);
        blocks.add(Blocks.SNOW);
        blocks.add(Blocks.SNOW_BLOCK);
        blocks.add(Blocks.POWDER_SNOW);
        blocks.add(Blocks.BARRIER);
        this.deny = Set.copyOf(blocks);
    }

    public boolean supports(final BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        final int id = Block.getId(state);
        if (id < 0) {
            return false;
        }
        byte[] c = this.cache;
        if (id >= c.length) {
            synchronized (this) {
                c = this.cache;
                if (id >= c.length) {
                    c = java.util.Arrays.copyOf(c, Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size()));
                    this.cache = c;
                }
            }
        }
        final byte known = c[id];
        if (known != UNKNOWN) {
            return known == YES;
        }
        final boolean ok = this.compute(state);
        c[id] = ok ? YES : NO;
        return ok;
    }

    private boolean compute(final BlockState state) {
        if (state.hasBlockEntity() || this.deny.contains(state.getBlock()) || !state.getFluidState().isEmpty()) {
            return false;
        }
        try {
            return Block.isFaceFull(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), Direction.UP);
        } catch (final RuntimeException e) {
            // shapes that need a real world: be conservative
            return false;
        }
    }
}
