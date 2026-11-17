// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import java.util.EnumSet;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;

/**
 * The only goal of a soldier's brain (besides vanilla FLOAT, kept so soldiers swim). It runs inside the mob's own
 * goal selector, so it only ticks while the mob ticks (loaded chunk, inside Paper's activation range) — Bukkit
 * schedules nothing per soldier. Owns MOVE and LOOK so nothing else steers the mob.
 */
final class SoldierGoal implements Goal<Mob> {

    private static GoalKey<Mob> key;

    private final Soldier soldier;
    private final SoldierController controller;

    SoldierGoal(final Soldier soldier, final SoldierController controller) {
        this.soldier = soldier;
        this.controller = controller;
    }

    static GoalKey<Mob> key(final org.bukkit.plugin.Plugin plugin) {
        if (key == null) {
            key = GoalKey.of(Mob.class, new NamespacedKey(plugin, "soldier"));
        }
        return key;
    }

    static GoalKey<Mob> key() {
        return key;
    }

    @Override
    public boolean shouldActivate() {
        return this.soldier.attached() && this.controller.owns(this.soldier);
    }

    @Override
    public boolean shouldStayActive() {
        return this.shouldActivate();
    }

    @Override
    public void tick() {
        this.controller.tickSoldier(this.soldier);
    }

    @Override
    public void stop() {
        if (this.soldier.brain != null && this.soldier.brain.isValid()) {
            this.soldier.brain.getPathfinder().stopPathfinding();
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
    }
}
