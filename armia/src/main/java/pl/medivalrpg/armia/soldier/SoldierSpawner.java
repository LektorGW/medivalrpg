// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Raider;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.ShieldMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.config.UnitType;
import pl.medivalrpg.kraje.api.KrajInfo;

/**
 * Creates and (re)configures soldier entities. Everything that vanilla would do on its own is switched off:
 * no despawn, no drops/XP (drop chances 0 + death listener), no picking up items, no burning in the sun, no
 * conversions (listener), no natural targets (all goals except FLOAT removed), no raids/patrols, no trading.
 */
public final class SoldierSpawner {

    static final String TEAM = "armia_npc";

    private final Plugin plugin;
    private final Keys keys;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;
    private SoldierController controller;

    /** HP multiplier of a country (era bonus + Postep sila-armii); 1 without Postep. */
    private java.util.function.ToDoubleFunction<java.util.UUID> hpMultiplier = c -> 1.0;
    /** Camouflage dye of a unit type (Postep modern units). */
    private java.util.function.Predicate<String> camo = t -> false;

    public void setEraHooks(final java.util.function.ToDoubleFunction<java.util.UUID> hp, final java.util.function.Predicate<String> camoUnit) {
        this.hpMultiplier = hp;
        this.camo = camoUnit;
    }

    public SoldierSpawner(final Plugin plugin, final Keys keys, final Supplier<ArmiaConfig> config, final Supplier<Messages> messages) {
        this.plugin = plugin;
        this.keys = keys;
        this.config = config;
        this.messages = messages;
        SoldierGoal.key(plugin);
    }

    void controller(final SoldierController controller) {
        this.controller = controller;
    }

    private boolean mannequinMode() {
        return this.config.get().visual == ArmiaConfig.Visual.MANNEQUIN;
    }

    /** True when the brain is invisible and hidden from clients (mannequin mode, foot soldier). */
    boolean hiddenBrain(final UnitType u) {
        return this.mannequinMode() && !u.mounted();
    }

    // ------------------------------------------------------------------ brain

    Mob spawnBrain(final Soldier s, final UnitType u, final Location loc, final KrajInfo country) {
        final Class<? extends Mob> type = this.brainClass(u);
        final boolean hidden = this.hiddenBrain(u);
        final Mob mob = loc.getWorld().spawn(loc, type, false, m -> {
            this.keys.tag(m, s.id(), s.country(), Keys.PART_BRAIN);
            if (hidden) {
                m.setInvisible(true);
                m.setVisibleByDefault(false);
                m.setSilent(true);
            }
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
        });
        this.configureBrain(s, u, mob, country, true);
        return mob;
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Mob> brainClass(final UnitType u) {
        if (u.mounted()) {
            return Horse.class;
        }
        final ArmiaConfig cfg = this.config.get();
        final Class<? extends Entity> cls = (this.mannequinMode() ? cfg.brainType : u.mobType()).getEntityClass();
        return (Class<? extends Mob>) cls;
    }

    /** Idempotent: called after spawning and every time the brain is loaded from disk again. */
    void configureBrain(final Soldier s, final UnitType u, final Mob mob, final KrajInfo country, final boolean fresh) {
        final ArmiaConfig cfg = this.config.get();
        this.keys.tag(mob, s.id(), s.country(), Keys.PART_BRAIN);
        final boolean hidden = this.hiddenBrain(u);
        mob.setPersistent(true);
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        mob.setDespawnInPeacefulOverride(TriState.FALSE);
        mob.setLootTable(null);
        mob.customName(this.name(u, country));
        mob.setCustomNameVisible(!hidden && !u.mounted() && cfg.showNames);
        if (hidden) {
            mob.setInvisible(true);
            mob.setVisibleByDefault(false);
            mob.setSilent(true);
            // Saved with the entity, so the brain stays invisible even in the moment between chunk load and re-attach.
            mob.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY,
                org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
        mob.setCollidable(!hidden);

        final double maxHp = u.brainHealth() * this.hpMultiplier.applyAsDouble(s.country());
        setBase(mob, Attribute.MAX_HEALTH, maxHp);
        setBase(mob, Attribute.MOVEMENT_SPEED, u.mounted() ? u.horseSpeed() : u.speed());
        setBase(mob, Attribute.FOLLOW_RANGE, 48);
        setBase(mob, Attribute.SPAWN_REINFORCEMENTS, 0);
        final double max = maxHp;
        final double hp = s.rec.health > 0 ? Math.min(max, s.rec.health) : max;
        mob.setHealth(Math.max(0.5, hp));

        final EntityEquipment eq = mob.getEquipment();
        for (final EquipmentSlot slot : EquipmentSlot.values()) {
            try {
                eq.setDropChance(slot, 0f);
            } catch (final IllegalArgumentException ignored) {
                // slot not supported by this mob
            }
        }

        if (mob instanceof Ageable a) {
            a.setAdult();
        }
        if (mob instanceof Breedable b) {
            b.setAgeLock(true);
            b.setBreed(false);
        }
        if (mob instanceof Zombie z) {
            z.setShouldBurnInDay(false);
            z.setCanBreakDoors(false);
            z.setConversionTime(-1);
        }
        if (mob instanceof AbstractSkeleton sk) {
            sk.setShouldBurnInDay(false);
        }
        if (mob instanceof Raider r) {
            r.setCanJoinRaid(false);
            r.setPatrolLeader(false);
        }
        if (mob instanceof WanderingTrader w) {
            w.setDespawnDelay(0);
            w.setCanDrinkPotion(false);
            w.setCanDrinkMilk(false);
        }
        if (mob instanceof AbstractHorse h) {
            h.setTamed(true);
            h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            if (h instanceof Horse horse) {
                if (fresh) {
                    final Horse.Color[] colors = {Horse.Color.BROWN, Horse.Color.DARK_BROWN, Horse.Color.BLACK, Horse.Color.CHESTNUT, Horse.Color.GRAY, Horse.Color.WHITE};
                    horse.setColor(colors[Math.floorMod(s.id().hashCode(), colors.length)]);
                    horse.setStyle(Horse.Style.NONE);
                }
                if (u.horseArmor() != null) {
                    horse.getInventory().setArmor(new ItemStack(u.horseArmor()));
                }
            }
        }
        if (!hidden && !u.mounted()) {
            this.equip(eq, u, country);
        }

        // AI: keep vanilla FLOAT (swimming), drop everything else, add ours.
        final MobGoals goals = Bukkit.getMobGoals();
        goals.removeAllGoals(mob, GoalType.TARGET);
        for (final Goal<Mob> g : new ArrayList<>(goals.getAllGoals(mob))) {
            if (!g.getKey().equals(VanillaGoal.FLOAT) && !g.getKey().equals(SoldierGoal.key())) {
                goals.removeGoal(mob, g);
            }
        }
        if (!goals.hasGoal(mob, SoldierGoal.key())) {
            goals.addGoal(mob, 1, new SoldierGoal(s, this.controller));
        }
        mob.getPathfinder().setCanFloat(true);
        mob.getPathfinder().setCanOpenDoors(false);
        mob.getPathfinder().setCanPassDoors(true);
        mob.setTarget(null);
        mob.setAggressive(false);
        this.joinTeam(mob);
    }

    private static void setBase(final LivingEntity e, final Attribute attribute, final double value) {
        final AttributeInstance a = e.getAttribute(attribute);
        if (a != null) {
            a.setBaseValue(value);
        }
    }

    // ------------------------------------------------------------------ body

    /** Visible human for mannequin mode / the rider of a horse. Null when the brain itself is the visible soldier. */
    LivingEntity spawnBody(final Soldier s, final UnitType u, final Location loc, final KrajInfo country) {
        final ArmiaConfig cfg = this.config.get();
        final LivingEntity body;
        if (this.mannequinMode()) {
            body = loc.getWorld().spawn(loc, Mannequin.class, m -> {
                this.keys.tag(m, s.id(), s.country(), Keys.PART_BODY);
                m.setPersistent(false);
                m.setImmovable(true);
                m.setGravity(false);
                m.setSilent(true);
                m.setCollidable(false);
                m.setProfile(SoldierSpawner.profile(u.skin(), s.id()));
                m.customName(this.name(u, country));
                m.setCustomNameVisible(cfg.showNames);
                m.setDescription(this.countryLabel(country));
                this.equip(m.getEquipment(), u, country);
            });
        } else if (u.mounted()) {
            @SuppressWarnings("unchecked")
            final Class<? extends Mob> riderType = (Class<? extends Mob>) u.mobType().getEntityClass();
            body = loc.getWorld().spawn(loc, riderType, false, r -> {
                this.keys.tag(r, s.id(), s.country(), Keys.PART_BODY);
                r.setPersistent(false);
                r.setRemoveWhenFarAway(false);
                r.setAI(false);
                r.setSilent(true);
                r.setCanPickupItems(false);
                r.setDespawnInPeacefulOverride(TriState.FALSE);
                r.setLootTable(null);
                r.customName(this.name(u, country));
                r.setCustomNameVisible(cfg.showNames);
                if (r instanceof Ageable a) {
                    a.setAdult();
                }
                if (r instanceof Zombie z) {
                    z.setShouldBurnInDay(false);
                    z.setConversionTime(-1);
                }
                if (r instanceof AbstractSkeleton sk) {
                    sk.setShouldBurnInDay(false);
                }
                if (r instanceof Raider raider) {
                    raider.setCanJoinRaid(false);
                }
                if (r instanceof WanderingTrader w) {
                    w.setDespawnDelay(0);
                }
                for (final EquipmentSlot slot : EquipmentSlot.values()) {
                    try {
                        r.getEquipment().setDropChance(slot, 0f);
                    } catch (final IllegalArgumentException ignored) {
                        // unsupported slot
                    }
                }
                this.equip(r.getEquipment(), u, country);
            });
            Bukkit.getMobGoals().removeAllGoals((Mob) body);
        } else {
            return null;
        }
        this.joinTeam(body);
        return body;
    }

    // ------------------------------------------------------------------ looks

    Component name(final UnitType u, final KrajInfo country) {
        final Messages m = this.messages.get();
        final String countryName = country == null ? "?" : country.name();
        final TextColor color = country == null ? TextColor.color(0xAAAAAA) : country.color();
        return m.get("soldier-name",
            Placeholder.parsed("unit", u.name()),
            Placeholder.component("country", Component.text(countryName, color)));
    }

    private Component countryLabel(final KrajInfo country) {
        if (country == null) {
            return Component.empty();
        }
        return this.messages.get().get("soldier-description", Placeholder.component("country", Component.text(country.name(), country.color())));
    }

    /**
     * Body profile: a Mojang nick from the unit's skin list (stable per soldier), or a profile with only a stable UUID
     * (the soldier id) and no textures, so the client shows one of its default skins.
     */
    public static ResolvableProfile profile(final String skinSpec, final java.util.UUID stableId) {
        final java.util.Optional<String> nick = pl.medivalrpg.armia.logic.SkinChoice.pick(skinSpec, stableId);
        return nick.isPresent()
            ? ResolvableProfile.resolvableProfile().name(nick.get()).build()
            : ResolvableProfile.resolvableProfile().uuid(stableId).build();
    }

    void equip(final EntityEquipment eq, final UnitType u, final KrajInfo country) {
        final int rgb = country == null ? 0x888888 : country.color().value();
        for (final Map.Entry<EquipmentSlot, Material> e : u.equipment().entrySet()) {
            eq.setItem(e.getKey(), this.item(e.getValue(), u, rgb), true);
        }
        if (!this.mannequinMode() && !pl.medivalrpg.armia.logic.SkinChoice.isRandom(u.skin()) && this.plugin.getConfig().getBoolean("mob-skin-head", true)) {
            final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            head.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile().name(pl.medivalrpg.armia.logic.SkinChoice.names(u.skin()).get(0)).build());
            eq.setItem(EquipmentSlot.HEAD, head, true);
        }
    }

    private ItemStack item(final Material material, final UnitType u, final int rgb) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (meta instanceof LeatherArmorMeta leather && u.dyeLeather()) {
            leather.setColor(Color.fromRGB((this.camo.test(u.id()) ? pl.medivalrpg.armia.logic.Eras.camo(rgb) : rgb) & 0xFFFFFF));
        }
        if (meta instanceof ShieldMeta shield && u.colorShield()) {
            shield.setBaseColor(nearestDye(rgb));
        }
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

    static DyeColor nearestDye(final int rgb) {
        DyeColor best = DyeColor.WHITE;
        long bestDist = Long.MAX_VALUE;
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;
        for (final DyeColor d : DyeColor.values()) {
            final Color c = d.getColor();
            final long dr = c.getRed() - r;
            final long dg = c.getGreen() - g;
            final long db = c.getBlue() - b;
            final long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ collisions

    private Team team() {
        if (!this.config.get().noCollision) {
            return null;
        }
        final Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(TEAM);
        if (team == null) {
            team = board.registerNewTeam(TEAM);
        }
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        return team;
    }

    void joinTeam(final Entity e) {
        final Team team = this.team();
        if (team != null && !team.hasEntity(e)) {
            team.addEntity(e);
        }
    }

    void leaveTeam(final Entity e) {
        final Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        final Team team = board.getTeam(TEAM);
        if (team != null) {
            try {
                team.removeEntity(e);
            } catch (final IllegalStateException | IllegalArgumentException ignored) {
                // not a member
            }
        }
    }

    /** Startup: forget team entries of entities from the previous run (they re-join when attached). */
    public void resetTeam() {
        final Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        final Team team = board.getTeam(TEAM);
        if (team != null) {
            for (final String entry : new ArrayList<>(team.getEntries())) {
                team.removeEntry(entry);
            }
        }
    }
}
