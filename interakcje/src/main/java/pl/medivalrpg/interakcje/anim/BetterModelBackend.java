// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.anim;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelConfig;
import kr.toxicity.model.api.BetterModelPlatform;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.BetterModelBukkit;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.event.ModelEventListener;
import kr.toxicity.model.api.event.PluginEndReloadEvent;
import kr.toxicity.model.api.platform.PlatformPlayer;
import kr.toxicity.model.api.profile.ModelProfile;
import kr.toxicity.model.api.profile.ModelProfileInfo;
import kr.toxicity.model.api.profile.ModelProfileSkin;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import kr.toxicity.model.api.tracker.ModelRotation;
import kr.toxicity.model.api.tracker.TrackerModifier;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Animations through BetterModel's player limb models. This is the only class that touches the
 * BetterModel API, so the plugin loads fine without BetterModel installed.
 *
 * <p>How a player is animated: {@link ModelRenderer#create(kr.toxicity.model.api.platform.PlatformEntity,
 * TrackerModifier, java.util.function.Consumer)} on the limb model ({@code BetterModel.limb(name)},
 * a {@code .bbmodel} in {@code plugins/BetterModel/players/}) creates a {@code PlayerTracker}: item
 * display entities with the player's skin are spawned for every viewer and the real player entity
 * is hidden from them (after {@code player-hide-delay}). {@link EntityTracker#animate(String,
 * AnimationModifier, Runnable)} plays a clip; {@link EntityTracker#close()} removes the model and
 * shows the real player again.
 */
public final class BetterModelBackend implements AnimationBackend {
    private final Plugin plugin;
    private final List<String> models;
    private final Map<UUID, EntityTracker> active = new HashMap<>();
    private ModelEventListener reloadListener = ModelEventListener.NONE;

    public BetterModelBackend(Plugin plugin, List<String> models) {
        this.plugin = plugin;
        this.models = List.copyOf(models);
        // touch the API now: a NoClassDefFoundError here means BetterModel is not usable
        BetterModel.platform();
    }

    @Override
    public String name() {
        return "BetterModel " + plugin.getServer().getPluginManager().getPlugin("BetterModel").getPluginMeta().getVersion();
    }

    @Override
    public boolean ready() {
        for (String m : models) {
            if (BetterModel.limbOrNull(m) == null) {
                return false;
            }
        }
        return !models.isEmpty();
    }

    @Override
    public boolean hasClip(String model, String clip) {
        ModelRenderer r = BetterModel.limbOrNull(model);
        return r != null && r.animation(clip).isPresent();
    }

    @Override
    public Handle play(Player player, String model, String clip, PlayOptions options, Runnable onEnd) {
        ModelRenderer renderer = BetterModel.limbOrNull(model);
        if (renderer == null || renderer.animation(clip).isEmpty()) {
            return null;
        }
        closeOurs(player);
        PlatformPlayer platform = BukkitAdapter.adapt(player);
        EntityTracker tracker;
        try {
            tracker = renderer.create(platform, TrackerModifier.DEFAULT,
                    t -> t.spawnCondition(viewer -> options.viewer().test(viewer.uuid())));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "BetterModel: nie udało się utworzyć modelu gracza " + player.getName(), e);
            return null;
        }
        if (options.fixedYaw() != null) {
            float yaw = options.fixedYaw();
            ModelRotation rotation = new ModelRotation(0f, yaw);
            tracker.rotation(() -> rotation);
        }
        AnimationModifier modifier = AnimationModifier.builder()
                .start(Math.max(0, options.blendInTicks()))
                .end(0)
                .type(options.loop() ? AnimationIterator.Type.LOOP : AnimationIterator.Type.PLAY_ONCE)
                .build();
        AtomicBoolean ended = new AtomicBoolean();
        boolean started = tracker.animate(clip, modifier, () -> {
            if (!options.loop() && ended.compareAndSet(false, true)) {
                onEnd.run();
            }
        });
        if (!started) {
            tracker.close();
            return null;
        }
        UUID id = player.getUniqueId();
        active.put(id, tracker);
        return () -> {
            if (active.get(id) == tracker) {
                active.remove(id);
            }
            if (!tracker.isClosed()) {
                tracker.close();
            }
        };
    }

    // ------------------------------------------------------------------ NPCs (any living entity)

    private final Map<UUID, EntityTracker> entityActive = new HashMap<>();

    /**
     * A limb (player) model on a non-player entity: {@code RenderSource$ProfiledEntity} through
     * {@link ModelRenderer#create(kr.toxicity.model.api.platform.PlatformEntity, ModelProfile.Uncompleted,
     * TrackerModifier, java.util.function.Consumer)} with an explicit profile, so the skin does not
     * depend on a player lookup. The tracker hides the base entity from the viewers it is spawned for
     * (the entity stays, so clicks on the NPC still reach it) and shows it again on close.
     */
    @Override
    public Handle playEntity(LivingEntity entity, String model, String clip, String rawTextures, String profileName,
                             boolean loop, Predicate<UUID> viewer, Runnable onEnd) {
        ModelRenderer renderer = BetterModel.limbOrNull(model);
        if (renderer == null || renderer.animation(clip).isEmpty() || !entity.isValid()) {
            return null;
        }
        UUID id = entity.getUniqueId();
        EntityTracker previous = entityActive.remove(id);
        if (previous != null && !previous.isClosed()) {
            previous.close();
        }
        ModelProfileSkin skin = ModelProfileSkin.EMPTY;
        if (rawTextures != null && !rawTextures.isBlank()) {
            try {
                skin = BetterModel.platform().manager(kr.toxicity.model.api.manager.ProfileManager.class).skin(rawTextures);
            } catch (RuntimeException e) {
                plugin.getLogger().fine("BetterModel: nieczytelny skin NPC " + id + ": " + e);
            }
        }
        String name = profileName == null || profileName.isBlank() ? "NPC" : profileName;
        ModelProfile profile = ModelProfile.of(new ModelProfileInfo(id, name), skin);
        EntityTracker tracker;
        try {
            tracker = renderer.create(BukkitAdapter.adapt(entity), profile.asUncompleted(), TrackerModifier.DEFAULT,
                    t -> t.spawnCondition(v -> viewer.test(v.uuid())));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "BetterModel: nie udało się utworzyć modelu NPC " + id, e);
            return null;
        }
        AnimationModifier modifier = AnimationModifier.builder()
                .start(3)
                .end(0)
                .type(loop ? AnimationIterator.Type.LOOP : AnimationIterator.Type.PLAY_ONCE)
                .build();
        AtomicBoolean ended = new AtomicBoolean();
        boolean started = tracker.animate(clip, modifier, () -> {
            if (!loop && ended.compareAndSet(false, true)) {
                onEnd.run();
            }
        });
        if (!started) {
            tracker.close();
            return null;
        }
        entityActive.put(id, tracker);
        return () -> {
            if (entityActive.get(id) == tracker) {
                entityActive.remove(id);
            }
            if (!tracker.isClosed()) {
                tracker.close();
            }
        };
    }

    /** Number of NPC trackers alive (for status). */
    public int entityTrackers() {
        entityActive.values().removeIf(EntityTracker::isClosed);
        return entityActive.size();
    }

    /** Closes every tracker of our limb models on the player (also ones left by an earlier session). */
    private void closeOurs(Player player) {
        EntityTracker previous = active.remove(player.getUniqueId());
        if (previous != null && !previous.isClosed()) {
            previous.close();
        }
        EntityTrackerRegistry registry = BetterModel.registryOrNull(player.getUniqueId());
        if (registry != null) {
            for (String m : models) {
                EntityTracker t = registry.tracker(m);
                if (t != null && !t.isClosed()) {
                    t.close();
                }
            }
        }
    }

    @Override
    public void shutdown() {
        reloadListener.unregister();
        reloadListener = ModelEventListener.NONE;
        for (EntityTracker t : active.values()) {
            if (!t.isClosed()) {
                t.close();
            }
        }
        active.clear();
        for (EntityTracker t : entityActive.values()) {
            if (!t.isClosed()) {
                t.close();
            }
        }
        entityActive.clear();
    }

    // ------------------------------------------------------------------ reload & files

    /** Runs {@code onReload} on the main thread after every successful BetterModel reload (pack rebuilt). */
    public void onReload(Runnable onReload) {
        reloadListener.unregister();
        reloadListener = BetterModelBukkit.platform().eventBus().subscribe(plugin, PluginEndReloadEvent.class, e -> {
            if (e.result() instanceof BetterModelPlatform.ReloadResult.Success && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, onReload);
            }
        });
    }

    /** {@code plugins/BetterModel}. */
    public static File dataFolder() {
        return BetterModel.platform().dataFolder();
    }

    /**
     * Copies the bundled limb models to {@code plugins/BetterModel/players/} when they are missing or
     * differ from the version in the jar.
     *
     * @return {@code true} when at least one file was written (BetterModel must reload to see it)
     */
    public boolean installModels(String resourceDir) throws IOException {
        Path players = dataFolder().toPath().resolve("players");
        Files.createDirectories(players);
        boolean changed = false;
        for (String m : models) {
            String resource = resourceDir + "/" + m + ".bbmodel";
            byte[] bundled;
            try (InputStream in = plugin.getResource(resource)) {
                if (in == null) {
                    plugin.getLogger().warning("Brak " + resource + " w jarze.");
                    continue;
                }
                bundled = in.readAllBytes();
            }
            Path target = players.resolve(m + ".bbmodel");
            if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), bundled)) {
                continue;
            }
            Path tmp = players.resolve(m + ".bbmodel.tmp");
            Files.write(tmp, bundled);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            changed = true;
        }
        return changed;
    }

    /**
     * Asks BetterModel to reload (rebuilds models and the resource pack).
     *
     * @return {@code false} when a reload is already running (try again later)
     */
    public boolean reload() {
        BetterModelPlatform.ReloadResult result = BetterModel.platform().reload(Bukkit.getConsoleSender());
        if (result instanceof BetterModelPlatform.ReloadResult.Failure f) {
            plugin.getLogger().log(Level.WARNING, "BetterModel: przeładowanie nie powiodło się", f.throwable());
        }
        return !(result instanceof BetterModelPlatform.ReloadResult.OnReload);
    }

    /**
     * Where BetterModel writes its resource pack: {@code plugins/<build-folder-location>[.zip]}.
     *
     * @return the path and whether it is a folder (pack-type: folder), or {@code null} for pack-type: none
     */
    public static PackOutput packOutput() {
        BetterModelConfig config = BetterModel.config();
        File plugins = dataFolder().getParentFile();
        return switch (config.packType()) {
            case ZIP -> new PackOutput(new File(plugins, config.buildFolderLocation() + ".zip").toPath(), false);
            case FOLDER -> new PackOutput(new File(plugins, config.buildFolderLocation()).toPath(), true);
            case NONE -> null;
        };
    }

    /** BetterModel's pack location. */
    public record PackOutput(Path path, boolean folder) {
    }
}
