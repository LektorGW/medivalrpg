// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import java.nio.file.Path;
import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Model backend of the banners. The only implementation that touches BetterModel is {@link BetterModelBanners}, which is
 * loaded only when BetterModel is enabled, so Kraje runs without it (no banners in game, flags still work in the panel
 * and on the map).
 */
public interface BannerRenderer {

    /** Result of a resource-pack rebuild request. */
    enum Reload { OK, BUSY, FAILED, UNAVAILABLE }

    /** One animated banner (packet-only display entities, nothing is saved into the world). */
    interface Banner {
        boolean closed();

        /** Moves the model origin (main thread). */
        void move(Location origin, float yaw);

        /** Spawns for players within {@code distance}, removes for the others. */
        void sync(Collection<? extends Player> players, double distance);

        int viewers();

        void close();
    }

    boolean modelLoaded(String model);

    /**
     * @param tint  RGB multiplied into the cloth bones, or -1 for none
     * @param pole  false hides the pole and the finial (ship masts, carried standards)
     * @param scale model scale (1 = 4¼ blocks high)
     * @return null when the model is not loaded
     */
    Banner create(String model, Location origin, float yaw, int tint, boolean pole, float scale);

    /** {@code plugins/BetterModel/models} — where the generated {@code .bbmodel} files go. */
    Path modelsDir();

    /** Rebuilds models and the resource pack. Blocking: call it from an async task, never from the main thread. */
    Reload reload();

    /** Runs on the main thread after every successful BetterModel reload (also reloads started by others). */
    void onReloaded(Runnable callback);

    /**
     * Extra resource-pack files (path in the zip → bytes) added at the start of every BetterModel reload — the textures
     * and models of the item "Sztandar kraju". The supplier runs on BetterModel's reload thread: files only, no Bukkit.
     */
    default void onPackBuild(final java.util.function.Supplier<java.util.Map<String, byte[]>> files) {
    }

    void shutdown();

    BannerRenderer NONE = new BannerRenderer() {
        @Override
        public boolean modelLoaded(final String model) {
            return false;
        }

        @Override
        public Banner create(final String model, final Location origin, final float yaw, final int tint, final boolean pole, final float scale) {
            return null;
        }

        @Override
        public Path modelsDir() {
            return null;
        }

        @Override
        public Reload reload() {
            return Reload.UNAVAILABLE;
        }

        @Override
        public void onReloaded(final Runnable callback) {
        }

        @Override
        public void shutdown() {
        }
    };
}
