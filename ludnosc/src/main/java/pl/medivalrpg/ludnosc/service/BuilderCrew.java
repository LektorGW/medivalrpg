// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.config.LudnoscConfig;
import pl.medivalrpg.ludnosc.util.Keys;

/**
 * The builders of one construction site. Bodies are non-persistent Mannequins (no AI, so no pathfinding fights with
 * GrimAC or mob goals) moved by short smooth teleports every two ticks along the ground (step up/down one block,
 * fallback jump when blocked). Each builder walks to the spot next to its next block, faces it, swings and places.
 */
final class BuilderCrew {

    static final class Body {
        final UUID id;
        Location pos;
        Location target;
        Location look;
        /** Block item carried to the next placement and when it was last changed (throttled). */
        Material held;
        long heldAt;
        /** Service tick of the last placement (the builder who waited longest takes the next block in reach). */
        long lastWork;
        /** Consecutive moves without walkable ground (climbing first, a hop only after a while). */
        int blocked;
        /** Service ticks spent walking to the current work target without reaching it. */
        int stuck;
        /** Blocks still carried from the material pile (0 = must fetch a new load first). */
        int carry;
        /** Walking to the material pile for a new load. */
        boolean fetching;
        /** Work animation accepted by Interakcje (null = none yet); changed only on a state change. */
        String anim;
        /** Earliest time (millis) of the next try when Interakcje refused or is missing. */
        long animRetryAt;
        /** Consecutive moves without moving (0 = walking). */
        int still;

        Body(final UUID id, final Location pos) {
            this.id = id;
            this.pos = pos.clone();
            this.target = pos.clone();
        }

        Entity entity() {
            return Bukkit.getEntity(this.id);
        }
    }

    private static final Color[] CLOTHES = {Color.fromRGB(110, 80, 50), Color.fromRGB(90, 70, 55), Color.fromRGB(130, 100, 60),
        Color.fromRGB(80, 90, 60)};
    private static final Material[] TOOLS = {Material.IRON_AXE, Material.IRON_SHOVEL, Material.STONE_PICKAXE,
        Material.WOODEN_AXE, Material.IRON_PICKAXE};

    final List<Body> bodies = new ArrayList<>();
    UUID hologram;
    String lastHologram;
    int next;
    int waited;
    /** Body chosen by the last successful reach check (places the block). */
    Body active;
    /** Role of the crew ({@code ensure} description: building type, "Tragarz — …", "Górnik", "Rolnik"…). */
    private String role = "";
    private final Random random = new Random();

    /** Removes bodies whose entity is gone (chunk unloaded, killed...). */
    void prune() {
        this.bodies.removeIf(b -> {
            final Entity e = b.entity();
            return e == null || !e.isValid();
        });
    }

    boolean contains(final UUID id) {
        for (final Body b : this.bodies) {
            if (b.id.equals(id)) {
                return true;
            }
        }
        return id.equals(this.hologram);
    }

    void ensure(final LudnoscPlugin plugin, final UUID site, final Location spawn, final int wanted, final String description) {
        this.prune();
        this.role = description == null ? "" : description;
        while (this.bodies.size() < wanted) {
            final Location at = spawn.clone().add((this.bodies.size() % 3 - 1) * 1.2, 0, (this.bodies.size() / 3) * 1.2);
            this.bodies.add(new Body(this.spawn(plugin, site, at, description).getUniqueId(), at));
        }
    }

    private Entity spawn(final LudnoscPlugin plugin, final UUID site, final Location loc, final String description) {
        final LudnoscConfig cfg = plugin.settings();
        final Component name = plugin.messages().get("builder-name", Placeholder.unparsed("imie", plugin.citizens().nextName()));
        final Material tool = this.bodies.size() < TOOLS.length ? TOOLS[this.bodies.size()]
            : cfg.builderTools.get(this.random.nextInt(cfg.builderTools.size()));
        if (cfg.builderType == LudnoscConfig.BuilderType.VILLAGER) {
            return loc.getWorld().spawn(loc, Villager.class, v -> {
                v.setAI(false);
                v.setProfession(Villager.Profession.MASON);
                v.setInvulnerable(true);
                v.setPersistent(false);
                v.setSilent(true);
                v.setCollidable(false);
                v.customName(name);
                v.setCustomNameVisible(true);
                Keys.setUuid(v, plugin.keys().builder, site);
            });
        }
        return loc.getWorld().spawn(loc, Mannequin.class, m -> {
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setPersistent(false);
            m.setCollidable(false);
            m.setSilent(true);
            m.setGravity(false);
            if (!cfg.builderSkins.isEmpty()) {
                m.setProfile(ResolvableProfile.resolvableProfile().name(cfg.builderSkins.get(this.random.nextInt(cfg.builderSkins.size()))).build());
            }
            final EntityEquipment eq = m.getEquipment();
            eq.setItemInMainHand(new ItemStack(tool));
            final Color c = CLOTHES[this.random.nextInt(CLOTHES.length)];
            eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, c));
            eq.setLeggings(leather(Material.LEATHER_LEGGINGS, c.mixColors(Color.fromRGB(60, 50, 40))));
            eq.setBoots(leather(Material.LEATHER_BOOTS, Color.fromRGB(60, 40, 25)));
            m.customName(name);
            m.setCustomNameVisible(true);
            m.setDescription(Component.text(description, NamedTextColor.GRAY));
            Keys.setUuid(m, plugin.keys().builder, site);
        });
    }

    private static ItemStack leather(final Material type, final Color color) {
        final ItemStack item = new ItemStack(type);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Next builder in turn (round robin). */
    Body nextBody() {
        if (this.bodies.isEmpty()) {
            return null;
        }
        return this.bodies.get(Math.floorMod(this.next++, this.bodies.size()));
    }

    /** Smooth movement: every call (2 ticks) at most {@code step} blocks towards the target along walkable ground. */
    void move(final double step) {
        for (final Body b : this.bodies) {
            final Entity e = b.entity();
            if (e == null || b.target == null || !b.target.getWorld().equals(b.pos.getWorld())) {
                if (e != null) {
                    this.animate(b, e, false);
                }
                continue;
            }
            final Location previous = b.pos;
            final World w = b.pos.getWorld();
            final Vector delta = b.target.toVector().subtract(b.pos.toVector());
            final double horizontal = Math.hypot(delta.getX(), delta.getZ());
            Location next;
            if (horizontal < 0.05 && Math.abs(delta.getY()) < 0.05) {
                next = b.pos;
            } else if (horizontal <= step && Math.abs(delta.getY()) <= Math.max(1.0, step * 2)) {
                next = b.target.clone();
                b.blocked = 0;
            } else if (horizontal <= step) {
                // right under / over the work spot (a scaffold, the top of a wall): climb there step by step
                final Location up = b.target.clone();
                up.setY(b.pos.getY() + Math.copySign(Math.min(step, Math.abs(delta.getY())), delta.getY()));
                final int feet = (int) Math.floor(up.getY());
                next = climbable(w.getBlockAt(up.getBlockX(), feet, up.getBlockZ()))
                    && climbable(w.getBlockAt(up.getBlockX(), feet + 1, up.getBlockZ())) ? up : this.climb(b, w, step);
            } else {
                next = b.pos.clone().add(delta.getX() / horizontal * step, 0, delta.getZ() / horizontal * step);
                final int bx = next.getBlockX();
                final int bz = next.getBlockZ();
                final int by = b.pos.getBlockY();
                Integer found = null;
                for (final int dy : new int[] {0, 1, -1, -2}) {
                    final Block feet = w.getBlockAt(bx, by + dy, bz);
                    if (WorldOps.standable(feet)) {
                        found = by + dy;
                        break;
                    }
                }
                if (found == null) {
                    next = this.climb(b, w, step);
                } else {
                    b.blocked = 0;
                    next.setY(found);
                }
            }
            final Location face = b.look != null ? b.look : b.target;
            final Vector dir = face.toVector().subtract(next.toVector().add(new Vector(0, 1.5, 0)));
            if (dir.lengthSquared() > 0.01) {
                next.setDirection(dir);
            } else {
                next.setYaw(b.pos.getYaw());
                next.setPitch(b.pos.getPitch());
            }
            b.pos = next;
            e.teleport(next);
            this.animate(b, e, previous.getWorld() != next.getWorld() || previous.distanceSquared(next) > 1.0E-4);
        }
    }

    /**
     * Work animation of the role (Interakcje {@code WorkAnimations}, optional): set only when the state changes
     * (walking / working); a refused call or a missing Interakcje is retried at most every 5 s. Nothing to clean up on
     * removal — Interakcje forget removed entities themselves.
     */
    private void animate(final Body b, final Entity e, final boolean walking) {
        // a short pause between two steps is still walking: the work pose only after half a second on the spot
        b.still = walking ? 0 : Math.min(b.still + 1, 1000);
        final String want = workAnim(this.role, walking || b.still < 5 && b.anim != null && !b.anim.equals(workAnim(this.role, false)));
        if (want.equals(b.anim)) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now < b.animRetryAt) {
            return;
        }
        if (pl.medivalrpg.ludnosc.hook.WorkAnim.play(e, want, true)) {
            b.anim = want;
        } else {
            b.animRetryAt = now + 5000L;
        }
    }

    /** Work of a role while standing at the work spot / while walking (names of Interakcje WorkAnimations). */
    static String workAnim(final String role, final boolean walking) {
        final String r = role == null ? "" : role.toLowerCase(java.util.Locale.ROOT);
        if (r.startsWith("tragarz")) {
            return walking ? "noszenie_plecy" : "odpoczynek";
        }
        if (walking) {
            return "noszenie_rece";
        }
        if (r.startsWith("górnik") || r.startsWith("kamieniarz") || r.startsWith("hutnik") || r.startsWith("ceglarz")) {
            return "kilof";
        }
        if (r.startsWith("drwal")) {
            return "siekiera";
        }
        if (r.startsWith("rolnik")) {
            return "motyka";
        }
        if (r.startsWith("kowal")) {
            return "kowadlo";
        }
        if (r.startsWith("rybak") || r.startsWith("owczarz")) {
            return "odpoczynek";
        }
        return "budowa"; // builders: the description is the building type
    }

    /**
     * No walkable ground ahead (a wall, a ledge): the builder climbs up or down the scaffold/wall face on the spot
     * (no gravity, so this reads as climbing) while the column above or below is free; only after about two seconds
     * of being completely stuck does he hop to his work spot.
     */
    private Location climb(final Body b, final World w, final double step) {
        b.blocked++;
        final double dy = b.target.getY() - b.pos.getY();
        final int x = b.pos.getBlockX();
        final int z = b.pos.getBlockZ();
        if (Math.abs(dy) > 0.2 && b.blocked <= 40) {
            final double ny = b.pos.getY() + Math.copySign(Math.min(step, Math.abs(dy)), dy);
            final int feet = (int) Math.floor(ny);
            if (climbable(w.getBlockAt(x, feet, z)) && climbable(w.getBlockAt(x, feet + 1, z))) {
                final Location up = b.pos.clone();
                up.setY(ny);
                return up;
            }
        }
        if (b.blocked < 20) {
            return b.pos.clone(); // a short pause, the next step may find another way
        }
        b.blocked = 0;
        return b.target.clone(); // really stuck: hop to the work spot
    }

    /** Air, plants, a ladder or the site's own scaffolding: a builder can climb through it. */
    static boolean climbable(final Block b) {
        final Material m = b.getType();
        return !b.isLiquid() && (b.isPassable() || m == Material.SCAFFOLDING || m == Material.LADDER || m == Material.VINE);
    }

    /** The body nearest to a point (or null). */
    Body nearest(final Location to) {
        Body best = null;
        double bestD = Double.MAX_VALUE;
        for (final Body b : this.bodies) {
            if (!b.pos.getWorld().equals(to.getWorld())) {
                continue;
            }
            final double d = b.pos.distanceSquared(to);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    /** Among the bodies within {@code reach} of a point (eyes), the one that waited longest; null when none. */
    Body ready(final Location center, final double reach, final boolean needsLoad) {
        Body best = null;
        for (final Body b : this.bodies) {
            if (!b.pos.getWorld().equals(center.getWorld()) || b.fetching || needsLoad && b.carry <= 0) {
                continue;
            }
            if (b.pos.clone().add(0, 1.6, 0).distanceSquared(center) <= reach * reach
                && (best == null || b.lastWork < best.lastWork)) {
                best = b;
            }
        }
        return best;
    }

    /**
     * The builder carries the block he is placing (at most one equipment change per 1.5 s per builder, so a busy site
     * does not flood the clients with equipment packets).
     */
    void hold(final Body b, final Material m) {
        if (b == null || m == null || !m.isItem() || m.isAir() || m == b.held) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - b.heldAt < 1500L) {
            return;
        }
        if (b.entity() instanceof LivingEntity le && le.getEquipment() != null) {
            le.getEquipment().setItemInMainHand(new ItemStack(m));
            b.held = m;
            b.heldAt = now;
        }
    }

    void swing(final Body b) {
        if (b != null && b.entity() instanceof LivingEntity le) {
            le.swingMainHand();
        }
    }

    void remove() {
        for (final Body b : this.bodies) {
            final Entity e = b.entity();
            if (e != null) {
                e.remove();
            }
        }
        this.bodies.clear();
        if (this.hologram != null) {
            final Entity e = Bukkit.getEntity(this.hologram);
            if (e != null) {
                e.remove();
            }
            this.hologram = null;
        }
    }
}
