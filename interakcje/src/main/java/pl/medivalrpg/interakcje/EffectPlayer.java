// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import io.papermc.paper.entity.LookAnchor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Plays the visual / audio side of interactions. Main thread only. */
public final class EffectPlayer {
    /** How far the head tilts down when bowing, and for how long. */
    private static final float NOD_PITCH = 50f;
    private static final long NOD_TICKS = 12L;

    private final Plugin plugin;
    private final Logger log;
    private final Map<String, Optional<Particle>> particles = new HashMap<>();
    private final Map<String, Optional<Sound>> sounds = new HashMap<>();
    private final Set<UUID> nodding = new HashSet<>();

    public EffectPlayer(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /** Forgets resolved names (after a config reload) so new warnings are logged once again. */
    public void clearCache() {
        particles.clear();
        sounds.clear();
    }

    /** Particles / sounds of dances and emotes (and other {@link InteractionDef.Effects} lists). */
    public void validateEffects(Iterable<InteractionDef.Effects> list) {
        for (InteractionDef.Effects fx : list) {
            fx.particles().forEach(p -> particle(p.type()));
            fx.sounds().forEach(s -> sound(s.sound()));
        }
    }

    /** Resolves every particle / sound now, so typos are reported at startup instead of on first use. */
    public void validate(Iterable<InteractionDef> defs) {
        for (InteractionDef d : defs) {
            d.effects().particles().forEach(p -> particle(p.type()));
            d.effects().sounds().forEach(s -> sound(s.sound()));
            if (d.hold() != null) {
                d.hold().particles().forEach(p -> particle(p.type()));
            }
        }
    }

    /** Full one-shot effect of an interaction. {@code target} may be {@code null} (wave / bow to everyone). */
    public void play(InteractionDef.Effects fx, Player sender, Player target, boolean allowKnockback) {
        play(fx, sender, target, allowKnockback, false);
    }

    /**
     * @param animated a character animation is playing: skip the vanilla stand-ins (head turn, arm
     *                 swing, nod) and play only particles, sounds and the knockback
     */
    public void play(InteractionDef.Effects fx, Player sender, Player target, boolean allowKnockback, boolean animated) {
        if (!animated) {
            if (target != null && fx.faceEachOther() && sender.getWorld().equals(target.getWorld())) {
                sender.lookAt(target, LookAnchor.EYES, LookAnchor.EYES);
                target.lookAt(sender, LookAnchor.EYES, LookAnchor.EYES);
            }
            if (fx.swing().sender()) {
                sender.swingMainHand();
            }
            if (target != null && fx.swing().target()) {
                target.swingMainHand();
            }
            if (fx.nod()) {
                nod(sender);
            }
        }
        if (target != null && allowKnockback && (fx.knockback() > 0 || fx.knockbackUp() != 0)) {
            knockback(sender, target, fx.knockback(), fx.knockbackUp());
        }
        spawnParticles(fx.particles(), sender, target);
        playSounds(fx.sounds(), sender, target);
    }

    public void spawnParticles(List<InteractionDef.ParticleSpec> specs, Player sender, Player target) {
        for (InteractionDef.ParticleSpec spec : specs) {
            Particle particle = particle(spec.type()).orElse(null);
            if (particle == null) {
                continue;
            }
            for (Location at : anchors(spec, sender, target)) {
                at.getWorld().spawnParticle(particle, at, spec.count(), spec.spread(), spec.spread(), spec.spread(), 0.0);
            }
        }
    }

    private void playSounds(List<InteractionDef.SoundSpec> specs, Player sender, Player target) {
        if (specs.isEmpty()) {
            return;
        }
        Location at = target != null && sameWorld(sender, target)
                ? midpoint(sender.getLocation(), target.getLocation(), 1.0)
                : sender.getLocation().add(0, 1.0, 0);
        World world = at.getWorld();
        for (InteractionDef.SoundSpec spec : specs) {
            sound(spec.sound()).ifPresent(s -> world.playSound(at, s, SoundCategory.PLAYERS, spec.volume(), spec.pitch()));
        }
    }

    private List<Location> anchors(InteractionDef.ParticleSpec spec, Player sender, Player target) {
        Location s = sender.getLocation().add(0, spec.height(), 0);
        if (target == null || !sameWorld(sender, target)) {
            return List.of(s);
        }
        Location t = target.getLocation().add(0, spec.height(), 0);
        return switch (spec.at()) {
            case SENDER -> List.of(s);
            case TARGET -> List.of(t);
            case BOTH -> List.of(s, t);
            case BETWEEN -> List.of(midpoint(sender.getLocation(), target.getLocation(), spec.height()));
        };
    }

    private static boolean sameWorld(Player a, Player b) {
        return a.getWorld().equals(b.getWorld());
    }

    private static Location midpoint(Location a, Location b, double height) {
        return new Location(a.getWorld(),
                (a.getX() + b.getX()) / 2.0,
                (a.getY() + b.getY()) / 2.0 + height,
                (a.getZ() + b.getZ()) / 2.0);
    }

    /** Horizontal push away from the sender; no damage is dealt. */
    private static void knockback(Player sender, Player target, double strength, double up) {
        Vector dir = target.getLocation().toVector().subtract(sender.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = sender.getLocation().getDirection().setY(0);
        }
        if (dir.lengthSquared() < 1.0E-4) {
            dir = new Vector(1, 0, 0);
        }
        dir.normalize().multiply(strength).setY(up);
        target.setVelocity(dir);
    }

    /** Bow: tilt the head down for a moment, then restore the previous pitch (yaw follows the player). */
    private void nod(Player p) {
        if (!nodding.add(p.getUniqueId())) {
            return;
        }
        float originalPitch = p.getLocation().getPitch();
        look(p, Math.max(originalPitch, NOD_PITCH));
        UUID id = p.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            nodding.remove(id);
            Player now = plugin.getServer().getPlayer(id);
            if (now != null && now.isOnline()) {
                look(now, originalPitch);
            }
        }, NOD_TICKS);
    }

    private static void look(Player p, float pitch) {
        Location eye = p.getEyeLocation();
        Location dirLoc = eye.clone();
        dirLoc.setPitch(pitch);
        Vector d = dirLoc.getDirection().multiply(2.0);
        p.lookAt(eye.getX() + d.getX(), eye.getY() + d.getY(), eye.getZ() + d.getZ(), LookAnchor.EYES);
    }

    public void forget(UUID player) {
        nodding.remove(player);
    }

    // ------------------------------------------------------------------ name resolution

    /** Particle by enum name ({@code HEART}) or key ({@code minecraft:heart}); only particles without extra data. */
    Optional<Particle> particle(String name) {
        return particles.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> {
            Particle p = null;
            try {
                p = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                NamespacedKey key = NamespacedKey.fromString(k);
                if (key != null) {
                    p = Registry.PARTICLE_TYPE.get(key);
                }
            }
            if (p == null) {
                log.warning("Nieznana cząsteczka '" + name + "' w config.yml – pomijam.");
                return Optional.empty();
            }
            if (p.getDataType() != Void.class) {
                log.warning("Cząsteczka '" + name + "' wymaga dodatkowych danych (" + p.getDataType().getSimpleName()
                        + ") – użyj cząsteczki bez danych, np. HEART, CRIT, HAPPY_VILLAGER.");
                return Optional.empty();
            }
            return Optional.of(p);
        });
    }

    /** Sound by registry key ({@code entity.chicken.egg}, {@code minecraft:...}) or constant name ({@code ENTITY_CHICKEN_EGG}). */
    Optional<Sound> sound(String name) {
        return sounds.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> {
            Sound s = null;
            NamespacedKey key = NamespacedKey.fromString(k);
            if (key != null) {
                s = Registry.SOUNDS.get(key);
            }
            if (s == null) {
                s = soundConstant(name.trim().toUpperCase(Locale.ROOT));
            }
            if (s == null) {
                log.warning("Nieznany dźwięk '" + name + "' w config.yml – pomijam.");
            }
            return Optional.ofNullable(s);
        });
    }

    /** {@code Sound} is a registry-backed interface in 26.x; read its public constant without the deprecated valueOf. */
    private static Sound soundConstant(String constant) {
        try {
            Field f = Sound.class.getField(constant);
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == Sound.class) {
                return (Sound) f.get(null);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // unknown constant
        }
        return null;
    }
}
