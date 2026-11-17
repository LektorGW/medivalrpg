// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An arena defined by an admin. Mutable, main thread only; persisted in {@code arenas.json}. Plain fields for Gson.
 */
public final class Arena {

    public String id;
    public ArenaType type;
    public Box box;
    /** Fighter spawn points; matches use the first two (melee: all, round robin). */
    public List<Pos> spawns = new ArrayList<>();
    /** Spectator stand, also where waiting participants stand; null = above the arena centre. */
    public Pos spectators;
    /** Owning country (may host {@code /turniej zorganizuj}); null = public arena. */
    public UUID country;
    /** Joust: horses are provided and the weapon is the configured lance. */
    public boolean mounted;
    /** Null = default from config. */
    public GearMode gear;

    public Arena() {
    }

    public Arena(final String id, final ArenaType type, final Box box) {
        this.id = id;
        this.type = type;
        this.box = box;
    }

    public GearMode gearOr(final GearMode fallback) {
        return this.gear == null ? fallback : this.gear;
    }

    /** Ready to host fights: at least two spawn points. */
    public boolean ready() {
        return this.spawns != null && this.spawns.size() >= 2;
    }

    /** Spectator stand, or a point above the centre of the box. */
    public Pos spectatorPos() {
        if (this.spectators != null) {
            return this.spectators;
        }
        return new Pos(this.box.world(), this.box.centerX(), this.box.maxY() + 1.0, this.box.centerZ(), 0f, 0f);
    }
}
