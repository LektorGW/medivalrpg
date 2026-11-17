// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.npc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;

/**
 * Market stalls of merchant NPCs: a BetterModel model per merchant type ({@code skarbiec_kram_<typ>}) placed by an admin
 * next to a merchant ({@code /skarbiec kram …}). The placement lives in the NPC registry ({@code npcs.json}), so stalls
 * come back after a restart; the models themselves are packet-only (see {@link StallModels}) and are never saved in the
 * world, so there is nothing orphaned to clean up. Without BetterModel (softdepend) merchants work exactly as before.
 * Main thread only.
 */
public final class StallService {

    /** Merchant types with a shipped model; other types use the general merchant's stall. */
    public static final List<String> SHIPPED = List.of("ogolny", "kowal", "spozywczy", "budowlany");
    public static final String PREFIX = "skarbiec_kram_";
    private static final String FALLBACK = PREFIX + "ogolny";

    private final SkarbiecPlugin plugin;
    private StallModels models;
    private BukkitTask task;
    private boolean broken;
    private long lastDelivery = Long.MIN_VALUE;

    public StallService(final SkarbiecPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Copies the shipped stall models into {@code plugins/BetterModel/models/} (called from onLoad, before BetterModel
     * reads its models). Our own {@code skarbiec_kram_*} files are replaced when the shipped version differs.
     */
    public static void installModels(final SkarbiecPlugin plugin) {
        if (!plugin.getConfig().getBoolean("npc.stalls.install-models", true)) {
            return;
        }
        final Path target = plugin.getDataFolder().toPath().resolveSibling("BetterModel").resolve("models");
        for (final String type : SHIPPED) {
            final String name = PREFIX + type + ".bbmodel";
            try (InputStream in = plugin.getResource("models/" + name)) {
                if (in == null) {
                    continue;
                }
                final byte[] data = in.readAllBytes();
                final Path file = target.resolve(name);
                if (Files.exists(file) && java.util.Arrays.equals(Files.readAllBytes(file), data)) {
                    continue;
                }
                Files.createDirectories(target);
                final Path tmp = target.resolve(name + ".tmp");
                Files.write(tmp, data);
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException e) {
                plugin.getLogger().warning("Instalacja modelu kramu " + name + ": " + e.getMessage());
            }
        }
    }

    public void start() {
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 40L, 20L);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        if (this.models != null) {
            try {
                this.models.close();
            } catch (final LinkageError | RuntimeException e) {
                // BetterModel already gone: its trackers die with it
            }
            this.models = null;
        }
    }

    /** BetterModel is enabled and usable. */
    public boolean available() {
        return !this.broken && Bukkit.getPluginManager().isPluginEnabled("BetterModel");
    }

    /** Model name for a merchant type (falls back to the general stall when the type has no own model loaded). */
    public String modelFor(final String typeId) {
        final String own = PREFIX + typeId;
        if (!this.available()) {
            return own;
        }
        try {
            return StallModels.ready(own) ? own : FALLBACK;
        } catch (final LinkageError | RuntimeException e) {
            return own;
        }
    }

    /** Whether the model of this merchant type is loaded in BetterModel. */
    public boolean modelLoaded(final String typeId) {
        if (!this.available()) {
            return false;
        }
        try {
            return StallModels.ready(this.modelFor(typeId));
        } catch (final LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** Number of stalls shown now. */
    public int shown() {
        return this.models == null ? 0 : this.models.count();
    }

    private void tick() {
        if (!this.plugin.cfg().stallsEnabled || !this.available()) {
            if (this.models != null) {
                this.stop();
                this.start();
            }
            return;
        }
        try {
            if (this.models == null) {
                this.models = new StallModels();
            }
            final long period = this.plugin.merchant().deliveryPeriod();
            final boolean newDelivery = this.lastDelivery != Long.MIN_VALUE && period != this.lastDelivery;
            this.lastDelivery = period;
            final Set<UUID> keep = new HashSet<>();
            final double radius = this.plugin.cfg().stallViewDistance;
            for (final NpcRecord r : this.plugin.npcs().all()) {
                final NpcRecord.Stall stall = r.stall();
                if (stall == null || r.type() != NpcType.MERCHANT) {
                    continue;
                }
                final World w = Bukkit.getWorld(r.world());
                if (w == null || !w.isChunkLoaded((int) Math.floor(stall.x()) >> 4, (int) Math.floor(stall.z()) >> 4)) {
                    continue;
                }
                final Location at = new Location(w, stall.x(), stall.y(), stall.z(), stall.yaw(), 0);
                if (!this.models.show(r.id(), this.modelFor(this.plugin.npcs().merchantType(r).id()), at)) {
                    continue;
                }
                keep.add(r.id());
                this.models.viewers(r.id(), at, Bukkit.getOnlinePlayers(), radius);
                if (newDelivery) {
                    this.models.delivery(r.id());
                }
            }
            this.models.retain(keep);
        } catch (final LinkageError | RuntimeException e) {
            this.broken = true;
            this.plugin.getLogger().log(Level.WARNING, "Kramy kupców (BetterModel) wyłączone — kupcy działają bez kramów", e);
            this.models = null;
        }
    }

    /** Re-creates the stall of one NPC at once (after placing, moving, rotating or a type change). */
    public void refresh(final NpcRecord r) {
        if (this.models != null) {
            try {
                this.models.remove(r.id());
            } catch (final LinkageError | RuntimeException e) {
                // next tick decides
            }
        }
    }
}
