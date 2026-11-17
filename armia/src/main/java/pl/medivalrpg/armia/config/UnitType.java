// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.config;

import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;

/**
 * One unit type from {@code units.<id>} in config.yml.
 *
 * @param id               config key (e.g. {@code piechota})
 * @param name             display name (MiniMessage)
 * @param description      one line shown in suggestions / info (MiniMessage)
 * @param cost             recruitment cost in copper
 * @param wage             daily wage in copper
 * @param health           hit points
 * @param damage           damage per hit (melee) or per arrow
 * @param range            melee reach or shooting range in blocks
 * @param cooldownTicks    ticks between attacks
 * @param ranged           shoots arrows instead of melee
 * @param projectileSpeed  arrow speed (vanilla skeleton 1.6)
 * @param spread           random aim error
 * @param speed            movement speed attribute (zombie 0.23, player ~0.1 walking but mobs differ)
 * @param siegeStrength    contribution to sieges (1.0 = one player)
 * @param skin             Mojang player name for the skin ("" = default)
 * @param mobType          entity type of the visible mob in {@code visual: mob}
 * @param mounted          rides a horse (the horse carries the AI)
 * @param horseHealth      horse hit points
 * @param horseSpeed       horse movement speed attribute
 * @param horseArmor       horse armour item (null = none)
 * @param equipment        slot → item
 * @param dyeLeather       dye leather armour in the country colour
 * @param colorShield      colour the shield with the closest dye of the country colour
 */
public record UnitType(
    String id,
    String name,
    String description,
    long cost,
    long wage,
    double health,
    double damage,
    double range,
    int cooldownTicks,
    boolean ranged,
    double projectileSpeed,
    double spread,
    double speed,
    double siegeStrength,
    String skin,
    EntityType mobType,
    boolean mounted,
    double horseHealth,
    double horseSpeed,
    Material horseArmor,
    Map<EquipmentSlot, Material> equipment,
    boolean dyeLeather,
    boolean colorShield
) {

    /** Hit points of the entity that carries the soldier (the horse for mounted units). */
    public double brainHealth() {
        return this.mounted ? this.horseHealth : this.health;
    }
}
