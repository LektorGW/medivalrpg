// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.nms;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import pl.medivalrpg.pory.logic.BiomeMapping;

/** Every direct access to server internals outside the packet classes. */
public final class Nms {

    private static final Key LISTENER_KEY = Key.key("pory", "seasons");

    private Nms() {
    }

    public static ServerPlayer handle(final Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    public static ServerLevel level(final World world) {
        return ((CraftWorld) world).getHandle();
    }

    public static Channel channel(final Player player) {
        final ServerPlayer sp = handle(player);
        return sp.connection == null ? null : sp.connection.connection.channel;
    }

    public static void send(final Player player, final Packet<?> packet) {
        final ServerPlayer sp = handle(player);
        if (sp.connection != null) {
            sp.connection.send(packet);
        }
    }

    /** The loaded chunk, or null (never loads or generates). */
    public static LevelChunk loadedChunk(final World world, final int cx, final int cz) {
        return level(world).getChunkSource().getChunkNow(cx, cz);
    }

    public static Registry<Biome> biomes() {
        return MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME);
    }

    /** Network id of the biome stored at a block (server-side, unchanged world). */
    public static int biomeId(final Registry<Biome> registry, final LevelChunk chunk, final int x, final int y,
                              final int z) {
        final Holder<Biome> holder = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
            QuartPos.fromBlock(z));
        return registry.getId(holder.value());
    }

    /** Snapshot of the biome registry for {@link BiomeMapping}. */
    public static List<BiomeMapping.Entry> biomeEntries() {
        final Registry<Biome> registry = biomes();
        final List<BiomeMapping.Entry> out = new ArrayList<>();
        for (final Biome biome : registry) {
            final var key = registry.getKey(biome);
            if (key != null) {
                out.add(new BiomeMapping.Entry(registry.getId(biome), key.toString(), biome.hasPrecipitation(),
                    biome.getBaseTemperature()));
            }
        }
        return out;
    }

    // ---- netty injection ---------------------------------------------------------------------------------------

    /** Adds the handler to every new connection and to the given online players. */
    public static void install(final PacketContext context, final Iterable<? extends Player> online,
                               final Consumer<String> warn) {
        ChannelInitializeListenerHolder.addListener(LISTENER_KEY, channel -> inject(channel, context, warn));
        for (final Player p : online) {
            final Channel ch = channel(p);
            if (ch != null) {
                ch.eventLoop().execute(() -> inject(ch, context, warn));
            }
        }
    }

    /** Fallback for connections that were created before {@link #install} (idempotent). */
    public static void ensure(final Player player, final PacketContext context, final Consumer<String> warn) {
        final Channel ch = channel(player);
        if (ch != null && ch.pipeline().get(SeasonPacketHandler.NAME) == null) {
            ch.eventLoop().execute(() -> inject(ch, context, warn));
        }
    }

    private static void inject(final Channel channel, final PacketContext context, final Consumer<String> warn) {
        try {
            final ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(SeasonPacketHandler.NAME) != null) {
                return;
            }
            if (pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", SeasonPacketHandler.NAME, new SeasonPacketHandler(context));
            } else {
                warn.accept("Brak packet_handler w potoku połączenia " + channel.remoteAddress());
            }
        } catch (final RuntimeException e) {
            warn.accept("Nie udało się podłączyć obsługi pakietów: " + e);
        }
    }

    public static void uninstall(final Iterable<? extends Player> online) {
        ChannelInitializeListenerHolder.removeListener(LISTENER_KEY);
        for (final Player p : online) {
            final Channel ch = channel(p);
            if (ch != null) {
                ch.eventLoop().execute(() -> {
                    if (ch.pipeline().get(SeasonPacketHandler.NAME) != null) {
                        ch.pipeline().remove(SeasonPacketHandler.NAME);
                    }
                });
            }
        }
    }

    /** The handler of a player's connection, or null. */
    public static SeasonPacketHandler handler(final Player player) {
        final Channel ch = channel(player);
        return ch == null ? null : (SeasonPacketHandler) ch.pipeline().get(SeasonPacketHandler.NAME);
    }
}
