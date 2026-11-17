// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.nms;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import pl.medivalrpg.pory.logic.PaletteRemapper;
import pl.medivalrpg.pory.logic.SnowTracker;
import pl.medivalrpg.pory.logic.VisualSeason;

/**
 * Netty outbound handler of one connection, placed just before {@code packet_handler} (it sees packet objects before
 * the encoder). It
 * <ul>
 *   <li>rewrites biome ids in chunk packets and biome-only packets to the seasonal variants,</li>
 *   <li>keeps the client-only snow consistent: tracks what was shown and removes snow whose support block changed.</li>
 * </ul>
 * Any failure sends the original packet unchanged (the world just looks like summer for that chunk).
 */
public final class SeasonPacketHandler extends ChannelOutboundHandlerAdapter {

    public static final String NAME = "pory_seasons";

    private static final Constructor<ClientboundLevelChunkPacketData> DATA_CTOR;
    private static final Field BLOCK_ENTITIES;
    private static final Throwable REFLECTION_ERROR;

    static {
        Constructor<ClientboundLevelChunkPacketData> ctor = null;
        Field field = null;
        Throwable error = null;
        try {
            ctor = ClientboundLevelChunkPacketData.class.getDeclaredConstructor(Map.class, byte[].class, List.class);
            ctor.setAccessible(true);
            field = ClientboundLevelChunkPacketData.class.getDeclaredField("blockEntitiesData");
            field.setAccessible(true);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            error = e;
        }
        DATA_CTOR = ctor;
        BLOCK_ENTITIES = field;
        REFLECTION_ERROR = error;
    }

    private final PacketContext context;
    private final SnowTracker snow = new SnowTracker();
    private volatile VisualSeason override;

    public SeasonPacketHandler(final PacketContext context) {
        this.context = context;
    }

    /** Null when chunk packets can be rebuilt on this server build, else the reason. */
    public static Throwable reflectionError() {
        return REFLECTION_ERROR;
    }

    public SnowTracker snow() {
        return this.snow;
    }

    /** Season shown to this connection only (admin preview), null = server season. */
    public VisualSeason override() {
        return this.override;
    }

    public void override(final VisualSeason season) {
        this.override = season;
    }

    public VisualSeason season() {
        final VisualSeason o = this.override;
        return o != null ? o : this.context.globalSeason();
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        Object out = msg;
        List<Packet<?>> extra = null;
        if (msg instanceof Packet<?> packet) {
            try {
                final List<Packet<?>> corrections = new ArrayList<>(0);
                out = this.handle(packet, corrections);
                extra = corrections.isEmpty() ? null : corrections;
            } catch (final Throwable t) {
                this.context.error(t);
                out = msg;
                extra = null;
            }
        }
        super.write(ctx, out, promise);
        if (extra != null) {
            for (final Packet<?> p : extra) {
                ctx.write(p, ctx.voidPromise());
            }
            this.context.snowCorrections.addAndGet(extra.size());
        }
    }

    private Packet<?> handle(final Packet<?> packet, final List<Packet<?>> corrections) throws Exception {
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            this.snow.clearChunk(chunk.x(), chunk.z());
            this.context.chunkPackets.incrementAndGet();
            return this.rewriteChunk(chunk);
        }
        if (packet instanceof SnowSectionPacket own) {
            for (final long p : own.removed) {
                this.snow.remove(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
            }
            for (final long p : own.added) {
                this.snow.add(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
            }
            return packet;
        }
        if (packet instanceof ClientboundChunksBiomesPacket biomes) {
            return this.rewriteBiomes(biomes);
        }
        if (packet instanceof ClientboundBlockUpdatePacket update) {
            if (this.snow.columns() > 0) {
                final BlockPos pos = update.getPos();
                if (this.snow.onBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), this.supports(update.getBlockState()))) {
                    corrections.add(new ClientboundBlockUpdatePacket(pos.above(), Blocks.AIR.defaultBlockState()));
                }
            }
            return packet;
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            if (this.snow.columns() > 0) {
                final LongOpenHashSet touched = new LongOpenHashSet();
                final List<BlockPos> fix = new ArrayList<>(0);
                section.runUpdates((pos, state) -> {
                    touched.add(pos.asLong());
                    if (this.snow.onBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), this.supports(state))) {
                        fix.add(pos.above());
                    }
                });
                for (final BlockPos p : fix) {
                    // a real state for the snow position in the same packet wins
                    if (!touched.contains(p.asLong())) {
                        corrections.add(new ClientboundBlockUpdatePacket(p, Blocks.AIR.defaultBlockState()));
                    }
                }
            }
            return packet;
        }
        if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            this.snow.clearChunk(forget.pos().x(), forget.pos().z());
            return packet;
        }
        if (packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundLoginPacket
            || packet instanceof ClientboundStartConfigurationPacket) {
            this.snow.clearAll();
            return packet;
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            final List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> parts =
                new ArrayList<>();
            boolean changed = false;
            for (final Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> sub
                : bundle.subPackets()) {
                final Packet<?> replaced = this.handle(sub, corrections);
                changed |= replaced != sub;
                @SuppressWarnings("unchecked")
                final Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> cast =
                    (Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>) replaced;
                parts.add(cast);
            }
            return changed ? new ClientboundBundlePacket(parts) : packet;
        }
        return packet;
    }

    private boolean supports(final BlockState state) {
        final SnowRules rules = this.context.snowRules();
        return rules != null && rules.supports(state);
    }

    private Packet<?> rewriteChunk(final ClientboundLevelChunkWithLightPacket chunk) throws Exception {
        final int[] table = this.context.table(this.season());
        if (table == null || DATA_CTOR == null) {
            return chunk;
        }
        final long start = System.nanoTime();
        final ClientboundLevelChunkPacketData data = chunk.chunkData();
        final byte[] raw = bytes(data.getReadBuffer());
        final byte[] remapped = PaletteRemapper.remapChunkSections(raw, table);
        if (remapped == null) {
            return chunk;
        }
        final ClientboundLevelChunkPacketData copy =
            DATA_CTOR.newInstance(data.getHeightmaps(), remapped, BLOCK_ENTITIES.get(data));
        this.context.chunkPacketsRewritten.incrementAndGet();
        this.context.rewriteNanos.addAndGet(System.nanoTime() - start);
        return new ClientboundLevelChunkWithLightPacket(chunk.x(), chunk.z(), copy, chunk.lightData());
    }

    private Packet<?> rewriteBiomes(final ClientboundChunksBiomesPacket packet) throws Exception {
        final int[] table = this.context.table(this.season());
        if (table == null) {
            return packet;
        }
        final List<ClientboundChunksBiomesPacket.ChunkBiomeData> out = new ArrayList<>(packet.chunkBiomeData().size());
        boolean changed = false;
        for (final ClientboundChunksBiomesPacket.ChunkBiomeData d : packet.chunkBiomeData()) {
            final byte[] remapped = PaletteRemapper.remapBiomeSections(d.buffer(), table);
            if (remapped == null) {
                out.add(d);
            } else {
                out.add(new ClientboundChunksBiomesPacket.ChunkBiomeData(d.pos(), remapped));
                changed = true;
            }
        }
        if (!changed) {
            return packet;
        }
        this.context.biomePacketsRewritten.incrementAndGet();
        return new ClientboundChunksBiomesPacket(out);
    }

    private static byte[] bytes(final ByteBuf buf) {
        if (buf.hasArray() && buf.arrayOffset() == 0 && buf.readerIndex() == 0
            && buf.writerIndex() == buf.array().length) {
            return buf.array();
        }
        final byte[] copy = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), copy);
        return copy;
    }
}
