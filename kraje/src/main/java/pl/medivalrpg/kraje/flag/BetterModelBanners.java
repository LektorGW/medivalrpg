// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelPlatform;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.BetterModelBukkit;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.event.ModelEventListener;
import kr.toxicity.model.api.event.PluginEndReloadEvent;
import kr.toxicity.model.api.event.PluginStartReloadEvent;
import kr.toxicity.model.api.tracker.DummyTracker;
import kr.toxicity.model.api.tracker.ModelRotation;
import kr.toxicity.model.api.tracker.ModelScaler;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** The only class touching the BetterModel API (loaded only when BetterModel is enabled). */
final class BetterModelBanners implements BannerRenderer {

    private final Plugin plugin;
    private ModelEventListener listener = ModelEventListener.NONE;
    private ModelEventListener packListener = ModelEventListener.NONE;

    BetterModelBanners(final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean modelLoaded(final String model) {
        return BetterModel.modelOrNull(model) != null;
    }

    @Override
    public Banner create(final String model, final Location origin, final float yaw, final int tint, final boolean pole, final float scale) {
        final ModelRenderer renderer = BetterModel.modelOrNull(model);
        if (renderer == null) {
            return null;
        }
        final DummyTracker tracker = renderer.create(BukkitAdapter.adapt(origin));
        final AtomicReference<Float> rotation = new AtomicReference<>(yaw);
        tracker.rotation(() -> new ModelRotation(0, rotation.get()));
        if (scale > 0 && Math.abs(scale - 1f) > 1e-3) {
            tracker.scaler(ModelScaler.value(scale));
        }
        if (tint >= 0) {
            tracker.update(TrackerUpdateAction.tint(tint & 0xFFFFFF), bone -> bone.name().name().startsWith(FlagModel.CLOTH_PREFIX));
        }
        if (!pole) {
            tracker.update(TrackerUpdateAction.togglePart(false), bone -> {
                final String n = bone.name().name();
                return n.equals(FlagModel.POLE_LOW) || n.equals(FlagModel.POLE_HIGH) || n.equals(FlagModel.FINIAL);
            });
        }
        tracker.animate(FlagModel.ANIMATION, AnimationModifier.builder().type(AnimationIterator.Type.LOOP).build());
        return new Handle(tracker, rotation, origin.clone());
    }

    private static final class Handle implements Banner {
        private final DummyTracker tracker;
        private final AtomicReference<Float> rotation;
        private Location origin;

        Handle(final DummyTracker tracker, final AtomicReference<Float> rotation, final Location origin) {
            this.tracker = tracker;
            this.rotation = rotation;
            this.origin = origin;
        }

        @Override
        public boolean closed() {
            return this.tracker.isClosed();
        }

        @Override
        public void move(final Location origin, final float yaw) {
            this.origin = origin.clone();
            this.tracker.location(BukkitAdapter.adapt(origin));
            if (this.rotation.getAndSet(yaw) != yaw) {
                this.tracker.forceUpdate(true);
            }
        }

        @Override
        public void sync(final Collection<? extends Player> players, final double distance) {
            final Location at = this.origin;
            for (final Player p : players) {
                final Location l = p.getLocation();
                final boolean sameWorld = at.getWorld() != null && p.getWorld().equals(at.getWorld());
                final double dx = l.getX() - at.getX();
                final double dz = l.getZ() - at.getZ();
                final double d2 = dx * dx + dz * dz;
                if (sameWorld && d2 < distance * distance) {
                    if (!this.tracker.isSpawned(p.getUniqueId())) {
                        this.tracker.spawn(BukkitAdapter.adapt(p));
                    }
                } else if (!sameWorld || d2 > (distance + 16) * (distance + 16)) {
                    if (this.tracker.isSpawned(p.getUniqueId())) {
                        this.tracker.remove(BukkitAdapter.adapt(p));
                    }
                }
            }
        }

        @Override
        public int viewers() {
            return this.tracker.playerCount();
        }

        @Override
        public void close() {
            this.tracker.close();
        }
    }

    @Override
    public Path modelsDir() {
        return BetterModel.platform().dataFolder().toPath().resolve("models");
    }

    @Override
    public Reload reload() {
        try {
            final BetterModelPlatform.ReloadResult result = BetterModel.platform().reload(Bukkit.getConsoleSender());
            if (result instanceof BetterModelPlatform.ReloadResult.OnReload) {
                return Reload.BUSY;
            }
            if (result instanceof BetterModelPlatform.ReloadResult.Failure f) {
                this.plugin.getLogger().log(Level.WARNING, "BetterModel: przebudowa paczki z flagami nie powiodła się", f.throwable());
                return Reload.FAILED;
            }
            return Reload.OK;
        } catch (final RuntimeException | LinkageError e) {
            this.plugin.getLogger().log(Level.WARNING, "BetterModel: przebudowa paczki z flagami nie powiodła się", e);
            return Reload.FAILED;
        }
    }

    @Override
    public void onReloaded(final Runnable callback) {
        this.listener.unregister();
        this.listener = BetterModelBukkit.platform().eventBus().subscribe(this.plugin, PluginEndReloadEvent.class, e -> {
            if (e.result() instanceof BetterModelPlatform.ReloadResult.Success && this.plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(this.plugin, callback);
            }
        });
    }

    @Override
    public void onPackBuild(final java.util.function.Supplier<java.util.Map<String, byte[]>> files) {
        this.packListener.unregister();
        this.packListener = BetterModelBukkit.platform().eventBus().subscribe(this.plugin, PluginStartReloadEvent.class, e -> {
            if (!this.plugin.isEnabled()) {
                return;
            }
            try {
                final java.util.Map<String, byte[]> add = files.get();
                for (final java.util.Map.Entry<String, byte[]> f : add.entrySet()) {
                    final byte[] bytes = f.getValue();
                    e.zipper().assets().add(f.getKey().split("/"), () -> bytes);
                }
                this.plugin.getLogger().info("Sztandary: do paczki zasobów dodano " + add.size() + " plików przedmiotu „Sztandar kraju”.");
            } catch (final RuntimeException | LinkageError ex) {
                // never break BetterModel's reload because of the standards
                this.plugin.getLogger().log(Level.WARNING, "Sztandary: nie udało się dodać plików do paczki zasobów", ex);
            }
        });
    }

    @Override
    public void shutdown() {
        this.listener.unregister();
        this.listener = ModelEventListener.NONE;
        this.packListener.unregister();
        this.packListener = ModelEventListener.NONE;
    }
}
