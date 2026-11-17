// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.turnieje.config.TurniejeConfig;

/**
 * Joust horses: spawned for a fight, invulnerable, saddled, PDC-marked, never persistent, removed after the fight.
 * Orphans (crash) are removed at start and whenever their chunk loads. Main thread only.
 */
public final class Horses {

    private final Keys keys;
    private final Supplier<TurniejeConfig> config;
    private final Set<UUID> active = new HashSet<>();
    /** Riders we are dismounting ourselves (the dismount guard lets them go). */
    private final Set<UUID> releasing = new HashSet<>();

    public Horses(final Keys keys, final Supplier<TurniejeConfig> config) {
        this.keys = keys;
        this.config = config;
    }

    public Horse spawn(final Location at, final Player rider) {
        final Horse horse = at.getWorld().spawn(at, Horse.class, h -> {
            h.getPersistentDataContainer().set(this.keys.horse, PersistentDataType.BYTE, (byte) 1);
            h.setPersistent(false);
            h.setRemoveWhenFarAway(false);
            h.setAdult();
            h.setTamed(true);
            h.setOwner(rider);
            h.setInvulnerable(true);
            h.setAgeLock(true);
            h.setBreed(false);
            h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            final AttributeInstance speed = h.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                speed.setBaseValue(this.config.get().horseSpeed);
            }
        });
        this.active.add(horse.getUniqueId());
        return horse;
    }

    public boolean isOurs(final Entity e) {
        return e instanceof AbstractHorse && e.getPersistentDataContainer().has(this.keys.horse, PersistentDataType.BYTE);
    }

    public boolean isReleasing(final UUID rider) {
        return this.releasing.contains(rider);
    }

    /** Removes the given horses (riders are let off first). */
    public void remove(final Set<UUID> horses) {
        for (final UUID id : horses) {
            final Entity e = Bukkit.getEntity(id);
            if (e != null) {
                for (final Entity passenger : e.getPassengers()) {
                    this.releasing.add(passenger.getUniqueId());
                    try {
                        e.removePassenger(passenger);
                    } finally {
                        this.releasing.remove(passenger.getUniqueId());
                    }
                }
                e.remove();
            }
            this.active.remove(id);
        }
    }

    /** Lets a rider off our horse (dismount guard bypassed). */
    public void dismount(final Player rider) {
        if (rider.isInsideVehicle()) {
            this.releasing.add(rider.getUniqueId());
            try {
                rider.leaveVehicle();
            } finally {
                this.releasing.remove(rider.getUniqueId());
            }
        }
    }

    /** True when the entity is one of our horses not belonging to a running fight. */
    public boolean isOrphan(final Entity e) {
        return this.isOurs(e) && !this.active.contains(e.getUniqueId());
    }

    /** Removes orphaned horses from all loaded worlds; returns how many. */
    public int cleanupLoaded() {
        int n = 0;
        for (final World w : Bukkit.getWorlds()) {
            for (final AbstractHorse h : w.getEntitiesByClass(AbstractHorse.class)) {
                if (this.isOrphan(h)) {
                    h.remove();
                    n++;
                }
            }
        }
        return n;
    }
}
