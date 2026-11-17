// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.util.BoundingBox;
import pl.medivalrpg.armia.soldier.Keys;
import pl.medivalrpg.armia.soldier.Soldier;

/**
 * Animated siege engine models (trebusz, katapulta, taran, armata) following their crew soldier, plus an optional
 * small collider for a deployed engine. The collider is a scaled, invisible shulker on a zero-size carrier (like the
 * ship deck); it appears only after the engine stood still for a while with nobody inside its box, and disappears the
 * moment the engine moves or turns or a player ends up inside it — players walk round and stand next to it, never
 * trapped. All entities carry the Armia {@code siege_model} tag, so orphans are removed on start and chunk load.
 */
public final class SiegeVisuals {
    private static final class Entry {
        final String model;
        final ModelHandle handle;
        final SiegeAnimator animator;
        Interaction carrier;
        Shulker box;
        long stillSince, lastViewers = Long.MIN_VALUE;
        double lastX, lastZ;
        float lastYaw;
        String blocker = "-";

        Entry(final String model, final ModelHandle handle, final SiegeAnimator animator, final Location at, final long tick) {
            this.model = model; this.handle = handle; this.animator = animator;
            this.lastX = at.getX(); this.lastZ = at.getZ(); this.lastYaw = at.getYaw(); this.stillSince = tick;
        }
    }

    private final Keys keys;
    private final ArmiaModels models;
    private final Map<UUID, Entry> entries = new HashMap<>();

    public SiegeVisuals(final Keys keys) {
        this.keys = keys;
        this.models = ArmiaModels.get();
    }

    /** The engine model for this soldier (stable variant), or null when the unit has no model. */
    public String modelFor(final Soldier soldier) {
        return ModelCatalog.pick(this.models.settings().engineModels(soldier.record().type), soldier.id());
    }

    /**
     * Returns true when the model layer handles the engine (model shown, or nothing because nobody is near / the
     * budget is used up); false means "draw the vanilla block model" (no BetterModel or no such model loaded).
     */
    public boolean sync(final Soldier soldier, final long tick) {
        final String model = this.modelFor(soldier);
        if (model == null || ModelCatalog.engine(model) == null || !this.models.ready(model)) {
            this.remove(soldier.id());
            return false;
        }
        final Location base = soldier.brain().getLocation();
        base.setPitch(0);
        final VisualSettings settings = this.models.settings();
        Entry entry = this.entries.get(soldier.id());
        double nearest = Double.MAX_VALUE;
        for (final Player p : base.getWorld().getPlayers()) nearest = Math.min(nearest, p.getLocation().distanceSquared(base));
        if (settings.enginesNearPlayersOnly() && !VisualRules.visible(entry != null, nearest, settings.engineShow(), settings.engineHide())) {
            this.remove(soldier.id());
            return true;
        }
        if (entry == null || !entry.model.equals(model) || entry.handle.closed()) {
            this.remove(soldier.id());
            final ModelHandle handle = this.models.create(model, base, base.getYaw(), this.entries.size(), settings.engineLimit());
            if (handle == null) return !VisualRules.admit(this.entries.size(), settings.engineLimit());
            entry = new Entry(model, handle, new SiegeAnimator(ModelCatalog.engine(model)), base, tick);
            this.entries.put(soldier.id(), entry);
        }
        entry.handle.move(base, base.getYaw());
        final Entry current = entry;
        for (final ModelAction action : entry.animator.update(tick, base.getX(), base.getZ()))
            entry.handle.play(action, current.animator::rollSpeed);
        if (tick - entry.lastViewers >= 10 || tick < entry.lastViewers) {
            entry.lastViewers = tick;
            entry.handle.viewers(settings.engineShow(), settings.engineHide());
        }
        this.collider(soldier, entry, base, tick, settings);
        return true;
    }

    /** The logic launched a projectile (or the ram struck) on this tick. */
    public void shot(final Soldier soldier, final long tick, final long reloadTicks) {
        final Entry entry = this.entries.get(soldier.id());
        if (entry == null || !soldier.attached()) return;
        final Location base = soldier.brain().getLocation();
        base.setPitch(0);
        entry.handle.move(base, base.getYaw()); // the crew was just turned towards the target
        for (final ModelAction action : entry.animator.shot(tick, reloadTicks)) entry.handle.play(action, entry.animator::rollSpeed);
        this.dropCollider(entry);
        entry.stillSince = tick;
    }

    private void collider(final Soldier soldier, final Entry entry, final Location base, final long tick, final VisualSettings settings) {
        final double moved = Math.hypot(base.getX() - entry.lastX, base.getZ() - entry.lastZ);
        final boolean still = VisualRules.still(moved, base.getYaw() - entry.lastYaw);
        entry.lastX = base.getX(); entry.lastZ = base.getZ(); entry.lastYaw = base.getYaw();
        if (!still) {
            entry.stillSince = tick;
            this.dropCollider(entry);
            return;
        }
        final ModelCatalog.Engine engine = ModelCatalog.engine(entry.model);
        final double[] box = VisualRules.colliderBox(base.getX(), base.getY(), base.getZ(), base.getYaw(), engine);
        final World world = base.getWorld();
        boolean player = false, anyone = false;
        final BoundingBox area = new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]).expand(0.3);
        for (final Entity e : world.getNearbyEntities(area)) {
            if (!(e instanceof LivingEntity) || e == entry.box) continue;
            anyone = true;
            entry.blocker = e.getType().name();
            if (e instanceof Player) player = true;
        }
        if (entry.box != null) {
            if (player || !settings.engineColliders() || !entry.box.isValid() || !entry.carrier.isValid()) this.dropCollider(entry);
            return;
        }
        if (!anyone) entry.blocker = "-";
        if (!VisualRules.colliderWanted(settings.engineColliders(), true, tick - entry.stillSince, settings.colliderAfterTicks(), anyone)) return;
        final Location at = new Location(world, (box[0] + box[3]) / 2, box[1], (box[2] + box[5]) / 2);
        try {
            entry.carrier = world.spawn(at, Interaction.class, e -> {
                e.setInteractionWidth(0); e.setInteractionHeight(0); e.setResponsive(false); e.setGravity(false);
                e.setInvulnerable(true); e.setPersistent(false);
                this.keys.tag(e, soldier.id(), soldier.country(), Keys.PART_SIEGE_MODEL);
            });
            entry.box = world.spawn(at, Shulker.class, e -> {
                e.setAI(false); e.setGravity(false); e.setInvisible(true); e.setInvulnerable(true); e.setSilent(true);
                e.setPersistent(false); e.setPeek(0); e.setAttachedFace(BlockFace.DOWN); e.setCollidable(true);
                final var scale = e.getAttribute(Attribute.SCALE);
                if (scale != null) scale.setBaseValue(engine.colliderSize());
            });
            if (!entry.carrier.isValid() || !entry.box.isValid()) {
                entry.blocker = "spawn cancelled";
                this.dropCollider(entry);
            } else if (!entry.carrier.addPassenger(entry.box)) {
                entry.blocker = "mount refused";
                this.dropCollider(entry);
            } else {
                // tagged once mounted (SoldierListener refuses mounts of tagged Armia parts); same tick, so the
                // orphan cleanup on start/chunk load still recognises it
                this.keys.tag(entry.box, soldier.id(), soldier.country(), Keys.PART_SIEGE_MODEL);
            }
        } catch (final RuntimeException e) {
            entry.blocker = "error " + e;
            this.dropCollider(entry);
        }
    }

    private void dropCollider(final Entry entry) {
        if (entry.box != null) entry.box.remove();
        if (entry.carrier != null) entry.carrier.remove();
        entry.box = null;
        entry.carrier = null;
    }

    public void remove(final UUID id) {
        final Entry entry = this.entries.remove(id);
        if (entry == null) return;
        this.dropCollider(entry);
        this.models.release(entry.handle);
    }

    /** Drops models of soldiers that left the registry (death, dismissal). */
    public void retain(final Predicate<UUID> owned) {
        final List<UUID> gone = new ArrayList<>();
        for (final UUID id : this.entries.keySet()) if (!owned.test(id)) gone.add(id);
        gone.forEach(this::remove);
    }

    public void stop() {
        new ArrayList<>(this.entries.keySet()).forEach(this::remove);
        ArmiaModels.shutdown();
    }

    // ---- diagnostics / tests
    public int liveModels() { return this.entries.size(); }

    public ModelHandle handle(final UUID soldier) {
        final Entry e = this.entries.get(soldier);
        return e == null ? null : e.handle;
    }

    public SiegeAnimator animator(final UUID soldier) {
        final Entry e = this.entries.get(soldier);
        return e == null ? null : e.animator;
    }

    /** Diagnostics: still time, collider state and who blocks it. */
    public String debug(final UUID soldier, final long tick) {
        final Entry e = this.entries.get(soldier);
        if (e == null) return "no model";
        return "still=" + (tick - e.stillSince) + " box=" + (e.box == null ? "none" : e.box.isValid() ? "valid" : "invalid")
            + " blocker=" + e.blocker;
    }

    public Shulker collider(final UUID soldier) {
        final Entry e = this.entries.get(soldier);
        return e == null ? null : e.box;
    }
}
