// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.npc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.DummyTracker;
import kr.toxicity.model.api.tracker.ModelRotation;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The only class that touches BetterModel (softdepend, compile-only). Stalls are packet-only {@link DummyTracker}s:
 * no entity is ever saved in the world (nothing to clean up after a crash), they have no hitbox (players walk up to the
 * merchant and click him through the model) and they exist only while the plugin runs.
 */
final class StallModels {

    static final String WIND = "wiatr";
    static final String DELIVERY = "dostawa";

    private final Map<UUID, DummyTracker> trackers = new HashMap<>();
    private final Map<UUID, String> models = new HashMap<>();

    static boolean ready(final String model) {
        return BetterModel.modelOrNull(model) != null;
    }

    /** Creates or updates the stall of NPC {@code id}; returns false when the model is not loaded. */
    boolean show(final UUID id, final String model, final Location at) {
        final var renderer = BetterModel.modelOrNull(model);
        if (renderer == null) {
            this.remove(id);
            return false;
        }
        DummyTracker t = this.trackers.get(id);
        if (t != null && (t.isClosed() || !model.equals(this.models.get(id)))) {
            t.close();
            t = null;
        }
        if (t == null) {
            t = renderer.create(BukkitAdapter.adapt(at));
            this.trackers.put(id, t);
            this.models.put(id, model);
            if (renderer.animation(WIND).isPresent()) {
                t.animate(WIND, AnimationModifier.builder().type(AnimationIterator.Type.LOOP).build());
            }
        }
        t.location(BukkitAdapter.adapt(at));
        final float yaw = at.getYaw();
        t.rotation(() -> new ModelRotation(0, yaw));
        return true;
    }

    /** Shows the stall to players within {@code radius} blocks, hides it from those beyond {@code radius + 16}. */
    void viewers(final UUID id, final Location at, final Iterable<? extends Player> players, final double radius) {
        final DummyTracker t = this.trackers.get(id);
        if (t == null || t.isClosed()) {
            return;
        }
        final double in = radius * radius;
        final double out = (radius + 16) * (radius + 16);
        for (final Player p : players) {
            final boolean sameWorld = p.getWorld().equals(at.getWorld());
            final double d = sameWorld ? p.getLocation().distanceSquared(at) : Double.MAX_VALUE;
            if (d < in && !t.isSpawned(p.getUniqueId())) {
                t.spawn(BukkitAdapter.adapt(p));
            } else if (d >= out && t.isSpawned(p.getUniqueId())) {
                t.remove(BukkitAdapter.adapt(p));
            }
        }
    }

    /** One-shot "new goods on the counter" animation (new delivery). */
    void delivery(final UUID id) {
        final DummyTracker t = this.trackers.get(id);
        final String model = this.models.get(id);
        if (t == null || t.isClosed() || model == null) {
            return;
        }
        final var renderer = BetterModel.modelOrNull(model);
        if (renderer != null && renderer.animation(DELIVERY).isPresent()) {
            t.animate(DELIVERY, AnimationModifier.builder().type(AnimationIterator.Type.PLAY_ONCE).build());
        }
    }

    boolean shown(final UUID id) {
        final DummyTracker t = this.trackers.get(id);
        return t != null && !t.isClosed();
    }

    void remove(final UUID id) {
        final DummyTracker t = this.trackers.remove(id);
        this.models.remove(id);
        if (t != null) {
            t.close();
        }
    }

    /** Closes stalls of NPCs that are gone, unplaced, out of loaded chunks or without a model. */
    void retain(final java.util.Set<UUID> keep) {
        for (final UUID id : java.util.List.copyOf(this.trackers.keySet())) {
            if (!keep.contains(id)) {
                this.remove(id);
            }
        }
    }

    void close() {
        this.trackers.values().forEach(DummyTracker::close);
        this.trackers.clear();
        this.models.clear();
    }

    int count() {
        return this.trackers.size();
    }
}
