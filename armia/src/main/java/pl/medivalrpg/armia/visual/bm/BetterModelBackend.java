// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual.bm;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelPlatform;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.DummyTracker;
import kr.toxicity.model.api.tracker.ModelRotation;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import kr.toxicity.model.api.util.TransformedItemStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import pl.medivalrpg.armia.visual.ModelAction;
import pl.medivalrpg.armia.visual.ModelBackend;
import pl.medivalrpg.armia.visual.ModelCatalog;
import pl.medivalrpg.armia.visual.ModelHandle;
import pl.medivalrpg.armia.visual.VisualRules;

/**
 * The only class touching the BetterModel API (loaded only when BetterModel is enabled). Every model is a
 * {@link DummyTracker}: display packets for nearby players, no server entity, nothing to clean after a crash.
 */
public final class BetterModelBackend implements ModelBackend {
    /**
     * BetterModel turns a Blockbench model so that its -Z side faces the yaw (players, Interakcje). Armia models are
     * drawn bow/front towards +Z, so the tracker gets yaw + 180. Verified on Paper by the orientation probe.
     */
    public static final float YAW_OFFSET = 180f;

    public BetterModelBackend() {
        BetterModel.platform(); // NoClassDefFoundError / IllegalStateException here = API unusable
    }

    @Override public String name() {
        final var plugin = Bukkit.getPluginManager().getPlugin("BetterModel");
        return "BetterModel " + (plugin == null ? "?" : plugin.getPluginMeta().getVersion());
    }

    @Override public boolean ready(final String model) { return BetterModel.modelOrNull(model) != null; }

    @Override public ModelHandle create(final String model, final Location at, final float yaw) {
        final var renderer = BetterModel.modelOrNull(model);
        if (renderer == null) return null;
        final Location spot = at.clone();
        spot.setYaw(yaw + YAW_OFFSET);
        spot.setPitch(0);
        return new Handle(renderer.create(BukkitAdapter.adapt(spot)), spot, yaw);
    }

    @Override public Path modelFolder() { return BetterModel.platform().dataFolder().toPath().resolve("models"); }

    @Override public boolean reload() {
        final BetterModelPlatform.ReloadResult result = BetterModel.platform().reload(Bukkit.getConsoleSender());
        return !(result instanceof BetterModelPlatform.ReloadResult.OnReload)
            && !(result instanceof BetterModelPlatform.ReloadResult.Failure);
    }

    private static final class Handle implements ModelHandle {
        private final DummyTracker tracker;
        private volatile float yaw;
        private Location at;

        Handle(final DummyTracker tracker, final Location at, final float yaw) {
            this.tracker = tracker;
            this.at = at;
            this.yaw = yaw + YAW_OFFSET;
            tracker.rotation(() -> new ModelRotation(0, this.yaw));
            tracker.update(TrackerUpdateAction.moveDuration(2));
        }

        @Override public void move(final Location at, final float yaw) {
            final Location spot = at.clone();
            spot.setYaw(yaw + YAW_OFFSET);
            spot.setPitch(0);
            this.at = spot;
            this.yaw = yaw + YAW_OFFSET;
            this.tracker.location(BukkitAdapter.adapt(spot));
        }

        @Override public boolean play(final ModelAction action, final Supplier<Float> liveSpeed) {
            if (this.tracker.isClosed()) return false;
            if (!action.start()) return this.tracker.stopAnimation(action.animation());
            final var builder = AnimationModifier.builder().priority(action.priority()).type(switch (action.kind()) {
                case LOOP -> AnimationIterator.Type.LOOP;
                case ONCE -> AnimationIterator.Type.PLAY_ONCE;
                case HOLD -> AnimationIterator.Type.HOLD_ON_LAST;
            });
            if (action.speed() == ModelAction.LIVE_SPEED && liveSpeed != null) builder.speed(() -> liveSpeed.get());
            else builder.speed(Math.max(0.01f, action.speed()));
            return this.tracker.animate(action.animation(), builder.build());
        }

        @Override public void tint(final int rgb) {
            this.tracker.update(TrackerUpdateAction.tint(rgb & 0xFFFFFF), (java.util.function.Predicate<RenderedBone>) bone -> ModelCatalog.tinted(bone.name().name()));
        }

        @Override public boolean item(final String bonePrefix, final ItemStack item) {
            if (item == null || item.getType().isAir()) return false;
            this.tracker.update(TrackerUpdateAction.itemStack(TransformedItemStack.of(BukkitAdapter.adapt(item))),
                (java.util.function.Predicate<RenderedBone>) bone -> bone.name().name().startsWith(bonePrefix));
            return true;
        }

        @Override public void visible(final String bonePrefix, final boolean visible) {
            if (this.tracker.isClosed()) return;
            this.tracker.update(visible ? TrackerUpdateAction.TogglePart.TRUE : TrackerUpdateAction.TogglePart.FALSE,
                (java.util.function.Predicate<RenderedBone>) bone -> bone.name().name().startsWith(bonePrefix));
        }

        @Override public boolean steer(final String name, final Supplier<Float> degrees) {
            final RenderedBone bone = this.tracker.bone(name);
            if (bone == null) return false;
            return bone.addLocalRotModifier(b -> b == bone, q -> q.rotateY((float) Math.toRadians(degrees.get())));
        }

        @Override public void refresh() { if (!this.tracker.isClosed()) this.tracker.forceUpdate(true); }

        @Override public int viewers(final double show, final double hide) {
            if (this.tracker.isClosed() || this.at == null || this.at.getWorld() == null) return 0;
            for (final Player p : Bukkit.getOnlinePlayers()) {
                final boolean shown = this.tracker.isSpawned(p.getUniqueId());
                final boolean visible = p.getWorld() == this.at.getWorld()
                    && VisualRules.visible(shown, p.getLocation().distanceSquared(this.at), show, hide);
                if (visible && !shown) this.tracker.spawn(BukkitAdapter.adapt(p));
                else if (!visible && shown) this.tracker.remove(BukkitAdapter.adapt(p));
            }
            return this.tracker.playerCount();
        }

        @Override public int viewerCount() { return this.tracker.isClosed() ? 0 : this.tracker.playerCount(); }

        @Override public boolean closed() { return this.tracker.isClosed(); }

        @Override public void close() { if (!this.tracker.isClosed()) this.tracker.close(); }

        @Override public Optional<Vector> bone(final String name) {
            final RenderedBone bone = this.tracker.bone(name);
            if (bone == null) return Optional.empty();
            final var p = bone.hitBoxPosition(); // cube centre with animation and model rotation, relative to the model origin
            return Optional.of(new Vector(p.x(), p.y(), p.z()));
        }

        @Override public Optional<String> running(final String name) {
            final RenderedBone bone = this.tracker.bone(name);
            final var running = bone == null ? null : bone.runningAnimation();
            return running == null ? Optional.empty() : Optional.of(running.name());
        }
    }
}
