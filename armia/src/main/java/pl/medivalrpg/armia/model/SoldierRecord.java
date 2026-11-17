// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent state of one soldier ({@code soldiers.json}). Main thread only. {@code brain == null} means the soldier
 * exists only in the registry and is spawned when the chunk of {@link #spawnPos()} loads (AI recruits in unloaded
 * chunks, recovered soldiers).
 */
public final class SoldierRecord {

    public UUID id;
    public UUID country;
    public String type;
    public SoldierMode mode = SoldierMode.GUARD;
    /** Guard post / home (also where "wróć" sends the soldier). */
    public Pos post;
    public double radius = 8;
    public List<Pos> patrol = new ArrayList<>();
    public int patrolIndex;
    /** Player commanding the soldier (FOLLOW / HOLD), null otherwise. */
    public UUID commander;
    /** Position to hold (HOLD). */
    public Pos hold;
    /** Health; ≤ 0 = full health of the unit type. */
    public double health;
    public int morale = 100;
    /** Entity UUID of the mob carrying the soldier's AI and hit points. */
    public UUID brain;
    /** Last known position of the brain. */
    public Pos last;
    /** Villager profession of the conscripted citizen ({@code minecraft:none} if unknown). */
    public String profession = "minecraft:none";
    public long recruitedAt;
    /** Mercenary contract end (epoch millis); 0 = a regular soldier. */
    public long contractUntil;
    /** Soldier sent to a siege by the NPC AI. */
    public boolean siege;
    /** Siege ammunition id; null in older saves means the unit's historical default. */
    public String siegeAmmo;
    /** Embarked soldiers remain unique registry entries and never follow/spawn/teleport until landed. */
    public UUID transportShip;
    /** Stable royal rebuild post id; absent for ordinary soldiers and historical saves. */
    public String royalPost;
    /** Royal construction evacuation, distinct from naval cargo; persists without changing the original post/HP/type. */
    public boolean royalEvacuated;
    /** Bounded tombstones for brains retired by royal rebuild/replays; old unloaded chunks must not resurrect them. */
    public List<UUID> royalRetiredBrains;
    /**
     * Background campaign of the NPC AI ({@code ai.conquest}): the soldier marches abstractly with the offensive of this id
     * (no live entity; it still counts towards limits, wages and the global cap). Null = at home. Older saves: absent.
     */
    public String campaign;

    public SoldierRecord() {
    }

    /** Naval transport and royal construction shelter share spawn/movement suspension, but remain distinct causes. */
    public boolean suspended() {return this.transportShip!=null || this.royalEvacuated || this.campaign!=null;}
    public void retireRoyalBrain(final UUID id) {
        if(id==null) return;
        if(this.royalRetiredBrains==null) this.royalRetiredBrains=new ArrayList<>();
        if(!this.royalRetiredBrains.contains(id)) this.royalRetiredBrains.add(id);
        while(this.royalRetiredBrains.size()>16) this.royalRetiredBrains.removeFirst();
    }
    public boolean retiredRoyalBrain(final UUID id) {return id!=null && this.royalRetiredBrains!=null && this.royalRetiredBrains.contains(id);}

    public SoldierRecord(final UUID country, final String type, final Pos post, final double radius) {
        this.id = UUID.randomUUID();
        this.country = country;
        this.type = type;
        this.post = post;
        this.radius = radius;
        this.recruitedAt = System.currentTimeMillis();
    }

    /** Where to spawn the soldier when it is not in the world. */
    public Pos spawnPos() {
        if (this.last != null) {
            return this.last;
        }
        return this.post;
    }

    /** Fixes nulls after loading an old/hand-edited file. */
    public void normalize() {
        if (this.mode == null) {
            this.mode = SoldierMode.GUARD;
        }
        if (this.patrol == null) {
            this.patrol = new ArrayList<>();
        }
        if (this.profession == null) {
            this.profession = "minecraft:none";
        }
        if (this.post == null) {
            this.post = this.last;
        }
        this.morale = Math.max(0, Math.min(100, this.morale));
        final var ammo=pl.medivalrpg.armia.logic.SiegeRules.ammunition(this.type,this.siegeAmmo);
        this.siegeAmmo=ammo==null?null:ammo.id();
    }
}
