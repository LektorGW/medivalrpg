// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameRules;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.menu.MenuPlugin;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.config.Messages;
import pl.medivalrpg.menu.integration.KrajeHook;
import pl.medivalrpg.menu.integration.PluginHooks;
import pl.medivalrpg.menu.teleport.StoredLocation;

/**
 * {@code /ustawspawn zbuduj [noair]} → particle preview + warning → {@code /ustawspawn zbuduj potwierdz}:
 *
 * <ol>
 *   <li>pastes {@code plugins/Menu/spawn/spawn.schem} (or {@code spawn_noair.schem} without air) through the
 *       WorldEdit API with the admin's feet as the paste point, in one edit session together with the edge slope
 *       ({@link EdgeSmoother}) and the removal of the NPC marker carpets – {@code /ustawspawn cofnij} reverts it;</li>
 *   <li>sets {@code spawn.location} and the world spawn on the lodestone, gamerule {@code respawn_radius 0};</li>
 *   <li>creates the Skarbiec NPCs by teleporting the admin onto each marker (with the right facing), running
 *       {@code /skarbiec npc stworz ...} as the admin and putting the admin back – Skarbiec has no API for it;</li>
 *   <li>creates the WorldGuard region (schematic + margin, whole height) and the Kraje protected area.</li>
 * </ol>
 * Main thread only. The undo record lives until restart.
 */
public final class SpawnBuilder {

    public static final String DIR = "spawn";
    public static final String SCHEM = "spawn.schem";
    public static final String SCHEM_NOAIR = "spawn_noair.schem";
    public static final String MARKERS = "markers.json";
    public static final String AREA_ID = "spawn";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record Pending(UUID world, SpawnLayout.Pos paste, boolean noair, long expiresAt, BukkitTask preview) {
    }

    /** Everything needed by {@code /ustawspawn cofnij}. */
    private record LastBuild(UUID world, SchematicPaster.Undo blocks, Optional<StoredLocation> previousSpawn,
                             Location previousWorldSpawn, Integer previousRespawnRadius, String regionId,
                             boolean regionCreated, boolean areaAdded, int npcs) {
    }

    private final MenuPlugin plugin;
    private final KrajeHook kraje;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private LastBuild last;

    public SpawnBuilder(final MenuPlugin plugin, final KrajeHook kraje) {
        this.plugin = plugin;
        this.kraje = kraje;
    }

    private MenuSettings s() {
        return this.plugin.settings();
    }

    private Messages m() {
        return this.plugin.messages();
    }

    private Path dir() {
        return this.plugin.getDataFolder().toPath().resolve(DIR);
    }

    /** First start: unpack both schematics and markers.json into {@code plugins/Menu/spawn/}. */
    public void extractResources() {
        for (final String name : new String[] {SCHEM, SCHEM_NOAIR, MARKERS}) {
            if (!Files.exists(this.dir().resolve(name))) {
                this.plugin.saveResource(DIR + "/" + name, false);
            }
        }
    }

    private SpawnLayout layout() throws IOException {
        final Path file = this.dir().resolve(MARKERS);
        if (Files.exists(file)) {
            return SpawnLayout.parse(Files.readString(file, StandardCharsets.UTF_8));
        }
        try (InputStream in = this.plugin.getResource(DIR + "/" + MARKERS)) {
            if (in == null) {
                throw new IOException("brak " + MARKERS);
            }
            return SpawnLayout.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ step 1: preview

    public void prepare(final Player admin, final boolean noair) {
        if (!PluginHooks.isEnabled("WorldEdit")) {
            admin.sendMessage(this.m().get("build-no-worldedit"));
            return;
        }
        final SpawnLayout layout;
        try {
            layout = this.layout();
        } catch (final IOException | RuntimeException e) {
            admin.sendMessage(this.m().get("build-error", Placeholder.unparsed("error", String.valueOf(e.getMessage()))));
            return;
        }
        this.cancelPending(admin.getUniqueId());
        final Location feet = admin.getLocation();
        final SpawnLayout.Pos paste = new SpawnLayout.Pos(feet.getBlockX(), feet.getBlockY(), feet.getBlockZ());
        final Box box = layout.box(paste);
        final World world = admin.getWorld();
        if (box.minY() < world.getMinHeight() || box.maxY() >= world.getMaxHeight()) {
            admin.sendMessage(this.m().get("build-out-of-world"));
            return;
        }
        final MenuSettings s = this.s();
        final long until = System.currentTimeMillis() + s.buildPreviewSeconds * 1000L;
        final Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(230, 40, 40), 1.6f);
        final Particle.DustOptions gold = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.6f);
        final int smooth = EdgeSmoother.clampWidth(s.buildSmoothWidth);
        final BukkitTask task = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (!admin.isOnline() || System.currentTimeMillis() > until) {
                return;
            }
            outline(admin, box, box.minY() + 5, red);
            outline(admin, box.expandHorizontally(smooth, box.minY(), box.maxY()), box.minY() + 5, gold);
            for (final int[] c : new int[][] {{box.minX(), box.minZ()}, {box.maxX(), box.minZ()},
                {box.minX(), box.maxZ()}, {box.maxX(), box.maxZ()}}) {
                for (int y = box.minY(); y <= box.maxY(); y += 2) {
                    admin.spawnParticle(Particle.DUST, c[0] + 0.5, y + 0.5, c[1] + 0.5, 1, 0, 0, 0, 0, red, true);
                }
            }
            admin.spawnParticle(Particle.HAPPY_VILLAGER, paste.x() + 0.5, paste.y() + 0.5, paste.z() + 0.5, 8,
                0.3, 0.5, 0.3, 0, null, true);
        }, 0L, 10L);
        this.pending.put(admin.getUniqueId(), new Pending(world.getUID(), paste, noair,
            System.currentTimeMillis() + s.buildConfirmSeconds * 1000L, task));
        Bukkit.getScheduler().runTaskLater(this.plugin, task::cancel, s.buildPreviewSeconds * 20L);
        admin.sendMessage(this.m().get("build-preview",
            Placeholder.unparsed("box", box.describe()),
            Placeholder.unparsed("variant", noair ? SCHEM_NOAIR + " (bez powietrza, jak //paste -a)" : SCHEM),
            Placeholder.unparsed("smooth", Integer.toString(smooth)),
            Placeholder.unparsed("seconds", Integer.toString(s.buildConfirmSeconds))));
    }

    private static void outline(final Player p, final Box b, final int y, final Particle.DustOptions dust) {
        for (int x = b.minX(); x <= b.maxX() + 1; x++) {
            p.spawnParticle(Particle.DUST, x, y + 0.2, b.minZ(), 1, 0, 0, 0, 0, dust, true);
            p.spawnParticle(Particle.DUST, x, y + 0.2, b.maxZ() + 1, 1, 0, 0, 0, 0, dust, true);
        }
        for (int z = b.minZ(); z <= b.maxZ() + 1; z++) {
            p.spawnParticle(Particle.DUST, b.minX(), y + 0.2, z, 1, 0, 0, 0, 0, dust, true);
            p.spawnParticle(Particle.DUST, b.maxX() + 1, y + 0.2, z, 1, 0, 0, 0, 0, dust, true);
        }
    }

    private void cancelPending(final UUID admin) {
        final Pending p = this.pending.remove(admin);
        if (p != null) {
            p.preview().cancel();
        }
    }

    // ------------------------------------------------------------------ step 2: build

    public void confirm(final Player admin) {
        final Pending p = this.pending.remove(admin.getUniqueId());
        if (p == null || System.currentTimeMillis() > p.expiresAt()) {
            if (p != null) {
                p.preview().cancel();
            }
            admin.sendMessage(this.m().get("build-nothing-pending"));
            return;
        }
        p.preview().cancel();
        final World world = Bukkit.getWorld(p.world());
        if (world == null || !PluginHooks.isEnabled("WorldEdit")) {
            admin.sendMessage(this.m().get("build-no-worldedit"));
            return;
        }
        final SpawnLayout layout;
        try {
            layout = this.layout();
        } catch (final IOException | RuntimeException e) {
            admin.sendMessage(this.m().get("build-error", Placeholder.unparsed("error", String.valueOf(e.getMessage()))));
            return;
        }
        final Box box = layout.box(p.paste());
        final int smooth = EdgeSmoother.clampWidth(this.s().buildSmoothWidth);
        final Box loadArea = box.expandHorizontally(Math.max(smooth, this.s().buildRegionMargin) + 1, box.minY(), box.maxY());
        admin.sendMessage(this.m().get("build-loading"));
        final List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = loadArea.minChunkX(); cx <= loadArea.maxChunkX(); cx++) {
            for (int cz = loadArea.minChunkZ(); cz <= loadArea.maxChunkZ(); cz++) {
                loads.add(world.getChunkAtAsync(cx, cz));
            }
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((v, err) ->
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (err != null) {
                    admin.sendMessage(this.m().get("build-error", Placeholder.unparsed("error", String.valueOf(err))));
                    return;
                }
                if (admin.isOnline()) {
                    this.build(admin, world, layout, p.paste(), p.noair());
                }
            }));
    }

    private void build(final Player admin, final World world, final SpawnLayout layout, final SpawnLayout.Pos paste,
                       final boolean noair) {
        final MenuSettings s = this.s();
        final Box box = layout.box(paste);
        final int edge = layout.groundY(paste);
        final List<String> summary = new ArrayList<>();

        // ---- blocks (one WorldEdit session: paste + slope + marker carpets)
        final SchematicPaster paster;
        try {
            paster = (SchematicPaster) Class.forName("pl.medivalrpg.menu.build.WorldEditPaster")
                .getDeclaredConstructor().newInstance();
        } catch (final ReflectiveOperationException | LinkageError e) {
            admin.sendMessage(this.m().get("build-no-worldedit"));
            return;
        }
        final int width = s.buildSmoothWidth <= 0 ? 0 : EdgeSmoother.clampWidth(s.buildSmoothWidth);
        final List<EdgeSmoother.Column> slope = width == 0 ? List.of()
            : EdgeSmoother.plan(box, edge, width, (x, z) -> surface(world, x, z, Math.min(world.getMaxHeight() - 1, edge + 40)));
        final List<SpawnLayout.Pos> carpets = new ArrayList<>();
        layout.marker("bankier", paste).ifPresent(carpets::add);
        layout.marker("kupiec", paste).ifPresent(carpets::add);
        final File file = this.dir().resolve(noair ? SCHEM_NOAIR : SCHEM).toFile();
        final SchematicPaster.Undo undo;
        try {
            undo = paster.paste(world, file, paste.x(), paste.y(), paste.z(), noair, admin, sink -> {
                applySlope(world, sink, slope, Math.min(world.getMaxHeight() - 1, edge + 40));
                final BlockData air = Material.AIR.createBlockData();
                for (final SpawnLayout.Pos c : carpets) {
                    sink.set(c.x(), c.y(), c.z(), air); // marker carpet; the block below stays
                }
            });
        } catch (final Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Wklejanie spawnu nie powiodło się", e);
            admin.sendMessage(this.m().get("build-error", Placeholder.unparsed("error", String.valueOf(e.getMessage()))));
            return;
        }
        summary.add(this.line("build-sum-blocks", "count", Integer.toString(undo.changed()),
            "slope", Integer.toString(slope.size())));

        // ---- spawn point, world spawn, respawn radius
        final Optional<StoredLocation> previousSpawn = s.spawnLocation;
        final Location previousWorldSpawn = world.getSpawnLocation();
        final Integer previousRadius = world.getGameRuleValue(GameRules.RESPAWN_RADIUS);
        final Location spawn = new Location(world, paste.x() + 0.5, paste.y(), paste.z() + 0.5, s.buildSpawnYaw, 0f);
        final boolean worldSpawn = this.plugin.setSpawn(spawn);
        world.setGameRule(GameRules.RESPAWN_RADIUS, 0);
        summary.add(this.line("build-sum-spawn", "location", StoredLocation.of(spawn).describe(),
            "world", worldSpawn ? "tak" : "nie"));

        // ---- NPCs (Skarbiec has no API: teleport the admin onto the marker and run the command as them)
        int npcs = 0;
        if (s.buildNpcEnabled && PluginHooks.isEnabled("Skarbiec")) {
            final Location back = admin.getLocation();
            npcs += this.npc(admin, world, layout.marker("kupiec", paste), "kupiec", s.buildNpcMerchant,
                s.buildNpcMerchantYaw) ? 1 : 0;
            npcs += this.npc(admin, world, layout.marker("bankier", paste), "bankier", s.buildNpcBanker,
                s.buildNpcBankerYaw) ? 1 : 0;
            admin.teleport(back);
            summary.add(this.line("build-sum-npc", "count", Integer.toString(npcs)));
        } else {
            summary.add(this.line("build-sum-npc-skipped"));
        }

        // ---- WorldGuard region
        boolean regionCreated = false;
        if (s.buildRegionEnabled && PluginHooks.isEnabled("WorldGuard")) {
            final Box region = layout.region(paste, s.buildRegionMargin, world.getMinHeight(), world.getMaxHeight() - 1);
            try {
                final RegionProtector protector = (RegionProtector) Class.forName(
                    "pl.medivalrpg.menu.build.WorldGuardRegion").getDeclaredConstructor().newInstance();
                final int flags = protector.apply(world, region, new RegionProtector.Spec(s.buildRegionId,
                    s.buildRegionPriority, s.buildRegionInvincible, legacy(s.buildRegionGreeting),
                    legacy(s.buildRegionFarewell), s.buildRegionDenySpawn));
                regionCreated = true;
                summary.add(this.line("build-sum-region", "id", s.buildRegionId, "box", region.describe(),
                    "flags", Integer.toString(flags)));
            } catch (final Exception | LinkageError e) {
                this.plugin.getLogger().log(Level.WARNING, "Region WorldGuard nie powstał", e);
                summary.add(this.line("build-sum-region-failed", "error", String.valueOf(e.getMessage())));
            }
        } else {
            summary.add(this.line("build-sum-region-skipped"));
        }

        // ---- Kraje: neutral land
        boolean areaAdded = false;
        if (s.buildKrajeEnabled && PluginHooks.isEnabled("Kraje")) {
            final String error = this.kraje.addProtectedArea(AREA_ID, world.getName(), box.minX(), box.minZ(),
                box.maxX(), box.maxZ(), s.buildKrajeBufferChunks);
            areaAdded = error == null;
            summary.add(areaAdded
                ? this.line("build-sum-kraje", "buffer", Integer.toString(s.buildKrajeBufferChunks))
                : this.line("build-sum-kraje-failed", "error", error));
        } else {
            summary.add(this.line("build-sum-kraje-skipped"));
        }

        this.last = new LastBuild(world.getUID(), undo, previousSpawn, previousWorldSpawn, previousRadius,
            s.buildRegionId, regionCreated, areaAdded, npcs);
        this.plugin.getLogger().info(admin.getName() + " zbudował spawn w " + world.getName() + " " + box.describe());
        admin.sendMessage(this.m().get("build-done-header"));
        for (final String l : summary) {
            admin.sendMessage(this.m().parse(l));
        }
        admin.sendMessage(this.m().get("build-done-footer"));
    }

    private String line(final String key, final String... pairs) {
        String raw = this.m().raw(key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            raw = raw.replace("<" + pairs[i] + ">", MM.escapeTags(pairs[i + 1]));
        }
        return raw;
    }

    private static String legacy(final String miniMessage) {
        return miniMessage == null || miniMessage.isBlank() ? ""
            : LegacyComponentSerializer.legacyAmpersand().serialize(MM.deserialize(miniMessage));
    }

    private boolean npc(final Player admin, final World world, final Optional<SpawnLayout.Pos> marker,
                        final String type, final String name, final float yaw) {
        if (marker.isEmpty()) {
            return false;
        }
        final SpawnLayout.Pos c = marker.get();
        admin.teleport(new Location(world, c.x() + 0.5, c.y(), c.z() + 0.5, yaw, 0f));
        final String quoted = "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return admin.performCommand("skarbiec npc stworz " + type + " " + quoted);
    }

    // ------------------------------------------------------------------ slope

    /** Top ground block of a column: highest motion-blocking block below {@code cap}, skipping logs and leaves. */
    static int surface(final World world, final int x, final int z, final int cap) {
        int y = Math.min(cap, world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
        while (y > world.getMinHeight()) {
            final Material m = world.getBlockAt(x, y, z).getType();
            if (m.isSolid() && !Tag.LOGS.isTagged(m) && !Tag.LEAVES.isTagged(m)) {
                return y;
            }
            y--;
        }
        return y;
    }

    private static void applySlope(final World world, final SchematicPaster.BlockSink sink,
                                   final List<EdgeSmoother.Column> columns, final int clearTop) {
        final BlockData dirt = Material.DIRT.createBlockData();
        final BlockData grass = Material.GRASS_BLOCK.createBlockData();
        final BlockData air = Material.AIR.createBlockData();
        for (final EdgeSmoother.Column c : columns) {
            if (c.raise()) {
                sink.set(c.x(), c.from(), c.z(), dirt); // old grass now covered
                for (int y = c.from() + 1; y < c.to(); y++) {
                    sink.set(c.x(), y, c.z(), dirt);
                }
                sink.set(c.x(), c.to(), c.z(), grass);
            } else {
                for (int y = c.to() + 1; y <= clearTop; y++) {
                    final Block b = world.getBlockAt(c.x(), y, c.z());
                    if (!b.getType().isAir()) {
                        sink.set(c.x(), y, c.z(), air);
                    }
                }
                sink.set(c.x(), c.to(), c.z(), grass);
            }
        }
    }

    // ------------------------------------------------------------------ undo

    public void undo(final Player admin) {
        final LastBuild b = this.last;
        if (b == null) {
            admin.sendMessage(this.m().get("build-undo-nothing"));
            return;
        }
        this.last = null;
        final World world = Bukkit.getWorld(b.world());
        try {
            b.blocks().undo();
        } catch (final RuntimeException | LinkageError e) {
            this.plugin.getLogger().log(Level.SEVERE, "Cofanie spawnu nie powiodło się", e);
            admin.sendMessage(this.m().get("build-error", Placeholder.unparsed("error", String.valueOf(e.getMessage()))));
            return;
        }
        if (world != null) {
            this.plugin.restoreSpawn(b.previousSpawn().orElse(null));
            world.setSpawnLocation(b.previousWorldSpawn());
            if (b.previousRespawnRadius() != null) {
                world.setGameRule(GameRules.RESPAWN_RADIUS, b.previousRespawnRadius());
            }
            if (b.regionCreated() && PluginHooks.isEnabled("WorldGuard")) {
                try {
                    ((RegionProtector) Class.forName("pl.medivalrpg.menu.build.WorldGuardRegion")
                        .getDeclaredConstructor().newInstance()).remove(world, b.regionId());
                } catch (final Exception | LinkageError e) {
                    this.plugin.getLogger().log(Level.WARNING, "Nie udało się usunąć regionu WorldGuard", e);
                }
            }
        }
        if (b.areaAdded()) {
            this.kraje.removeProtectedArea(AREA_ID);
        }
        admin.sendMessage(this.m().get("build-undone", Placeholder.unparsed("npcs", Integer.toString(b.npcs()))));
    }
}
