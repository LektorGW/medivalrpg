// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import java.util.UUID;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import pl.medivalrpg.armia.model.SoldierRecord;

/**
 * A soldier: persistent {@link SoldierRecord} + live entities while its chunk is loaded.
 *
 * <ul>
 *   <li><b>brain</b> — the {@link Mob} that pathfinds, holds the hit points and deals damage. In {@code visual: mannequin}
 *   it is invisible and hidden from all clients; in {@code visual: mob} it is the visible soldier; for mounted units it
 *   is the horse.</li>
 *   <li><b>body</b> — the visible human ({@code Mannequin}) following the brain, or the rider on the horse. Null for a
 *   foot soldier in {@code visual: mob}. Never saved to disk: re-created when the brain loads.</li>
 * </ul>
 */
public final class Soldier {

    final SoldierRecord rec;
    Mob brain;
    LivingEntity body;

    /** Current target (transient). */
    LivingEntity target;
    /** Target given by the commander (pursued without leash, until dead). */
    boolean ordered;
    long nextAttackTick;
    long nextThinkTick;
    long nextRepathTick;
    long lastCombatTick;
    long nextBodyRespawnTick;
    long nextRegenTick;
    int missingChecks;
    boolean aiming;
    /** Last entity that hurt this soldier and when. */
    UUID lastAttacker;
    long lastAttackedTick;
    /** Wander point inside the guard radius. */
    org.bukkit.Location wander;
    /** Work animation of the body accepted by Interakcje (null = none); transient, never saved. */
    String anim;
    /** Tick since which the body has not moved, and the earliest tick of the next try after a refusal. */
    long stillSinceTick;
    long animRetryTick;

    Soldier(final SoldierRecord rec) {
        this.rec = rec;
    }

    public SoldierRecord record() {
        return this.rec;
    }

    public UUID id() {
        return this.rec.id;
    }

    public UUID country() {
        return this.rec.country;
    }

    public Mob brain() {
        return this.brain;
    }

    public LivingEntity body() {
        return this.body;
    }

    public LivingEntity target() {
        return this.target;
    }

    /** Brain is spawned and valid. */
    public boolean attached() {
        return this.brain != null && this.brain.isValid();
    }

    void clearTarget() {
        this.target = null;
        this.ordered = false;
        this.aiming = false;
    }
}
