// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.visual;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * Vanilla particles and sounds for rail models. BetterModel 3.6.0-SNAPSHOT-535 parses "sound"/"particle"
 * keyframe channels but never plays them, so steam, sparks and whistles are sent here.
 * Everything goes per player, only to players in the same world within range (no world broadcast),
 * and each emitter is throttled by game tick so a train costs at most a few packets per second per viewer.
 */
public final class RailEffects {
    /** Particles beyond ~64 blocks are sub-pixel; models themselves use the 96/112 block window. */
    public static final double PARTICLE_RANGE = 64, SOUND_RANGE = 64, WHISTLE_RANGE = 96;
    private static final BlockData COAL = Material.COAL_BLOCK.createBlockData();
    private RailEffects() { }

    public static List<Player> viewers(Location at, double range) {
        List<Player> result = new ArrayList<>();
        if (at == null || at.getWorld() == null) return result;
        double r2 = range * range;
        for (Player p : at.getWorld().getPlayers()) if (p.getLocation().distanceSquared(at) < r2) result.add(p);
        return result;
    }
    /** Model-local blocks (x right, y up, z forward) → world, same yaw convention as the model trackers. */
    public static Location local(Location origin, double x, double y, double z) {
        double a = Math.toRadians(origin.getYaw()), c = Math.cos(a), s = Math.sin(a);
        return origin.clone().add(x * c - z * s, y, x * s + z * c);
    }
    private static void particle(List<Player> viewers, Particle type, Location at, int count, double spread, double spreadY, double speed) {
        for (Player p : viewers) p.spawnParticle(type, at, count, spread, spreadY, spread, speed, null, true);
    }
    private static <T> void particle(List<Player> viewers, Particle type, Location at, int count, double spread, double speed, T data) {
        for (Player p : viewers) p.spawnParticle(type, at, count, spread, spread, spread, speed, data, true);
    }
    private static void sound(Location at, double range, Sound sound, float volume, float pitch) {
        for (Player p : viewers(at, range)) p.playSound(at, sound, SoundCategory.NEUTRAL, volume, pitch);
    }

    /** Locomotive at model origin {@code front}; model units are /16 (chimney top 60, whistle 53, drain cocks 6). */
    public static void locomotive(Location front, LocomotiveAnimator.Frame f, boolean fire, long tick) {
        if (front == null) return;
        boolean smokeTick = every(tick, 10), effectTick = every(tick, 4);
        if (!smokeTick && !effectTick && !f.start() && !f.whistle()) return;
        List<Player> near = viewers(front, PARTICLE_RANGE);
        if (near.isEmpty()) return;
        Location chimney = local(front, 0, 60 / 16.0, 34 / 16.0), whistle = local(front, 3 / 16.0, 54 / 16.0, -20.5 / 16.0);
        if (fire && smokeTick) {
            // Coal smoke: denser when working. Idle = thin wisp plus a little steam weeping from the cocks.
            particle(near, Particle.CAMPFIRE_COSY_SMOKE, chimney, f.moving() ? 3 : 1, .12, .2, .01);
            if (f.loop() == LocomotiveAnimator.Loop.IDLE && every(tick, 40))
                for (double side : new double[]{-17 / 16.0, 17 / 16.0}) particle(near, Particle.CLOUD, local(front, side, 6 / 16.0, 44 / 16.0), 1, .05, .02, .01);
        }
        if (f.start()) {
            // Cylinder drain cocks open: low, wide steam clouds on both sides, chimney blast.
            for (double side : new double[]{-17 / 16.0, 17 / 16.0}) particle(near, Particle.CLOUD, local(front, side * 1.2, 6 / 16.0, 44 / 16.0), 10, .35, .1, .04);
            particle(near, Particle.CAMPFIRE_COSY_SMOKE, chimney, 6, .2, .3, .02);
            sound(front, SOUND_RANGE, Sound.BLOCK_FIRE_EXTINGUISH, .7f, .6f);
        }
        if (f.whistle()) {
            particle(near, Particle.CLOUD, whistle, 8, .06, .25, .03);
            for (Player p : viewers(front, WHISTLE_RANGE)) {
                // Two-tone chime from vanilla note-block flutes plus a steam hiss.
                p.playSound(whistle, Sound.BLOCK_NOTE_BLOCK_FLUTE, SoundCategory.NEUTRAL, 2.5f, .7f);
                p.playSound(whistle, Sound.BLOCK_NOTE_BLOCK_FLUTE, SoundCategory.NEUTRAL, 2.5f, .89f);
                p.playSound(whistle, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.NEUTRAL, .4f, 1.6f);
            }
        }
        if (f.braking() && effectTick) {
            // Brake shoe sparks at one axle per burst, alternating sides.
            double[] axles = {-34, -10, 14, 36};
            double z = axles[(int) ((tick / 4) % axles.length)] / 16.0, x = ((tick / 4) % 2 == 0 ? -19 : 19) / 16.0;
            Location wheel = local(front, x, 1 / 16.0, z);
            particle(near, Particle.CRIT, wheel, 3, .08, .05, .25);
            particle(near, Particle.SMALL_FLAME, wheel, 1, .05, .02, .01);
            if (every(tick, 20)) sound(front, 32, Sound.BLOCK_GRINDSTONE_USE, .25f, 1.6f);
        }
        if (f.moving() && every(tick, 50)) sound(front, SOUND_RANGE, Sound.BLOCK_PISTON_EXTEND, .3f, .65f);
    }
    public static void doors(Location at) { sound(at, 32, Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, .6f, .8f); }
    /** Tender service finished at a fixture (model origin = above the container). */
    public static void service(Location at, boolean coal) {
        if (at == null) return;
        List<Player> near = viewers(at, PARTICLE_RANGE);
        if (coal) {
            Location chute = local(at, 0, 2.2, 3.0);
            particle(near, Particle.BLOCK, chute, 25, .3, .05, COAL);
            sound(at, SOUND_RANGE, Sound.BLOCK_GRAVEL_BREAK, .9f, .7f);
        } else {
            particle(near, Particle.FALLING_WATER, at.clone().add(0, 2.5, 0), 25, .6, .4, .1);
            particle(near, Particle.SPLASH, at.clone().add(0, 1.2, 0), 12, .5, .1, .1);
            sound(at, SOUND_RANGE, Sound.ITEM_BUCKET_EMPTY, .8f, .9f);
        }
    }
    public static void semaphore(Location at) { sound(at, 48, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, .7f, .7f); }
    public static void lever(Location at) { sound(at, 48, Sound.BLOCK_PISTON_CONTRACT, .5f, 1.4f); }
    public static void turntable(Location at, long tick) { if (every(tick, 20)) sound(at, 48, Sound.BLOCK_GRINDSTONE_USE, .35f, .5f); }
    /** Callers run every game tick or every second tick; true once per period for either parity. */
    static boolean every(long tick, int period) { return Math.floorMod(tick, period) < 2; }
    /** Current game tick (Paper); used for throttling. */
    public static long now() { return Bukkit.getCurrentTick(); }
}
