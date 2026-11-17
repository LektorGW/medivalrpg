// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import pl.medivalrpg.pory.logic.VisualSeason;

/**
 * Autumn: a few falling leaves under tree crowns near each player, visible to that player only. Bounded by
 * {@code samples-per-player} block reads every {@code interval-ticks}; reads only loaded chunks. Main thread only.
 */
public final class LeafService {

    private static final Color[] AUTUMN = {
        Color.fromRGB(0xD9822B), Color.fromRGB(0xC8501E), Color.fromRGB(0xE3B33C), Color.fromRGB(0xA8461F)
    };

    private final List<Particle> leafParticles = new ArrayList<>();
    private final boolean tinted;
    public long spawned;

    public LeafService() {
        for (final String name : List.of("ORANGE_POPLAR_LEAVES", "RED_POPLAR_LEAVES", "YELLOW_POPLAR_LEAVES")) {
            try {
                final Particle p = Particle.valueOf(name);
                if (p.getDataType() == Void.class) {
                    this.leafParticles.add(p);
                }
            } catch (final IllegalArgumentException ignored) {
                // older/newer API without this particle
            }
        }
        this.tinted = Particle.TINTED_LEAVES.getDataType() == Color.class;
    }

    public void tick(final Function<Player, VisualSeason> season, final Predicate<World> worldAllowed,
                     final int samples, final int radius) {
        if (samples <= 0 || (this.leafParticles.isEmpty() && !this.tinted)) {
            return;
        }
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (season.apply(player) != VisualSeason.JESIEN || !worldAllowed.test(player.getWorld())) {
                continue;
            }
            final Location at = player.getLocation();
            final World world = at.getWorld();
            for (int i = 0; i < samples; i++) {
                final int x = at.getBlockX() + rnd.nextInt(-radius, radius + 1);
                final int y = at.getBlockY() + rnd.nextInt(-3, 13);
                final int z = at.getBlockZ() + rnd.nextInt(-radius, radius + 1);
                if (y <= world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                final Block block = world.getBlockAt(x, y, z);
                if (!Tag.LEAVES.isTagged(block.getType()) || block.getRelative(0, -1, 0).getType() != Material.AIR) {
                    continue;
                }
                final Location spot = new Location(world, x + rnd.nextDouble(), y - 0.1, z + rnd.nextDouble());
                if (!this.leafParticles.isEmpty() && (rnd.nextInt(3) != 0 || !this.tinted)) {
                    player.spawnParticle(this.leafParticles.get(rnd.nextInt(this.leafParticles.size())), spot, 1, 0, 0,
                        0, 0);
                } else {
                    player.spawnParticle(Particle.TINTED_LEAVES, spot, 1, 0, 0, 0, 0,
                        AUTUMN[rnd.nextInt(AUTUMN.length)]);
                }
                this.spawned++;
            }
        }
    }
}
