// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.siege.SiegeManager;

/**
 * Writes {@code kraje.json} into squaremap's web directory for the web side panel ({@code map.export-json}).
 * Data is collected on the main thread, the file is written on a single background thread (tmp file + atomic
 * rename). Format: {@code {"updated": millis, "countries": [ {id, name, color, npc, description, ruler, members,
 * population, soldiers, chunks, areaBlocks, founded, capital: {world, x, z}, allies: [names], wars: [names],
 * sieges, overlord (name or null), vassalUntil (millis or null), vassals: [names]} ]}}, countries sorted by chunks
 * (descending).
 */
final class MapExport {

    // serializeNulls: "overlord": null must be written (the panels test for it).
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final KrajePlugin plugin;
    private final Path target;
    private final ExecutorService io;

    private MapExport(final KrajePlugin plugin, final Path target) {
        this.plugin = plugin;
        this.target = target;
        this.io = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "Kraje-MapExport");
            t.setDaemon(true);
            return t;
        });
    }

    /** Null when disabled or squaremap is missing. */
    static MapExport create(final KrajePlugin plugin) {
        if (!plugin.cfg().mapExportJson) {
            return null;
        }
        final Plugin squaremap = Bukkit.getPluginManager().getPlugin("squaremap");
        if (squaremap == null) {
            return null;
        }
        final File dataFolder = squaremap.getDataFolder();
        String web = "web";
        final File config = new File(dataFolder, "config.yml");
        if (config.isFile()) {
            web = YamlConfiguration.loadConfiguration(config).getString("settings.web-directory.path", "web");
        }
        Path dir = Path.of(web);
        if (!dir.isAbsolute()) {
            dir = dataFolder.toPath().resolve(web);
        }
        return new MapExport(plugin, dir.resolve("kraje.json"));
    }

    /** squaremap world id: the world key with {@code :} replaced by {@code _} (e.g. {@code minecraft_overworld}). */
    static String worldId(final String worldName) {
        final World world = Bukkit.getWorld(worldName);
        return world == null ? worldName : world.getKey().toString().replace(':', '_');
    }

    /** Main thread: snapshot now, write in the background. */
    void export() {
        final JsonObject root = new JsonObject();
        root.addProperty("updated", System.currentTimeMillis());
        final JsonArray array = new JsonArray();
        final List<Country> sorted = this.plugin.manager().countries().stream()
            .sorted(Comparator.comparingInt(Country::claimCount).reversed()
                .thenComparing(c -> c.name().toLowerCase(java.util.Locale.ROOT)))
            .toList();
        final List<SiegeManager.Siege> sieges = this.plugin.sieges() == null ? List.of() : List.copyOf(this.plugin.sieges().sieges());
        for (final Country c : sorted) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", c.id().toString());
            o.addProperty("name", c.name());
            o.addProperty("displayName", this.plugin.displayName(c));
            o.addProperty("color", c.hexColor());
            o.addProperty("npc", c.npc());
            o.addProperty("description", c.description());
            o.addProperty("ruler", c.rulerName());
            o.addProperty("members", c.memberCount());
            o.addProperty("population", this.plugin.integrations().population(c.id()).orElse(0));
            o.addProperty("soldiers", this.plugin.integrations().soldiers(c.id()).orElse(0));
            o.addProperty("chunks", c.claimCount());
            o.addProperty("areaBlocks", c.claimCount() * 256L);
            o.addProperty("founded", c.foundedAt());
            // Postep (eras): null without the plugin
            final java.util.Optional<pl.medivalrpg.kraje.integration.Integrations.Era> era = this.plugin.integrations().era(c.id());
            if (era.isPresent()) {
                o.addProperty("era", era.get().id());
                o.addProperty("eraName", era.get().name());
                o.addProperty("progress", Math.round(era.get().progress() * 1000.0) / 1000.0);
            } else {
                o.add("era", JsonNull.INSTANCE);
                o.add("eraName", JsonNull.INSTANCE);
                o.add("progress", JsonNull.INSTANCE);
            }
            final ChunkPos capital = c.capital();
            final SpawnPoint spawn = c.spawn();
            final boolean spawnInCapital = spawn.world().equals(capital.world())
                && ((int) Math.floor(spawn.x())) >> 4 == capital.x() && ((int) Math.floor(spawn.z())) >> 4 == capital.z();
            final JsonObject cap = new JsonObject();
            cap.addProperty("world", worldId(capital.world()));
            cap.addProperty("x", spawnInCapital ? (int) Math.floor(spawn.x()) : capital.centerBlockX());
            cap.addProperty("z", spawnInCapital ? (int) Math.floor(spawn.z()) : capital.centerBlockZ());
            o.add("capital", cap);
            final JsonArray allies = new JsonArray();
            final JsonArray wars = new JsonArray();
            for (final Map.Entry<UUID, Relation> e : c.relationsMap().entrySet()) {
                final Country other = this.plugin.manager().get(e.getKey());
                if (other == null) {
                    continue;
                }
                if (e.getValue() == Relation.ALLIANCE) {
                    allies.add(other.name());
                } else if (e.getValue() == Relation.WAR) {
                    wars.add(other.name());
                }
            }
            o.add("allies", allies);
            o.add("wars", wars);
            o.addProperty("sieges", sieges.stream().filter(s -> s.attacker().equals(c.id()) || s.defender().equals(c.id())).count());
            final Country overlord = this.plugin.manager().get(c.overlord());
            if (overlord != null) {
                o.addProperty("overlord", overlord.name());
                o.addProperty("vassalUntil", c.vassalage().until());
            } else {
                o.add("overlord", JsonNull.INSTANCE);
                o.add("vassalUntil", JsonNull.INSTANCE);
            }
            final JsonArray vassals = new JsonArray();
            for (final Country v : this.plugin.manager().vassalsOf(c.id())) {
                vassals.add(v.name());
            }
            o.add("vassals", vassals);
            final pl.medivalrpg.kraje.api.UnionInfo union = this.plugin.manager().unionInfo(c.id());
            final Country unionPartner = union == null ? null : this.plugin.manager().get(union.other(c.id()));
            if (unionPartner != null) {
                final JsonObject uo = new JsonObject();
                uo.addProperty("partner", unionPartner.name());
                uo.addProperty("monarch", union.monarchName());
                uo.addProperty("since", union.since());
                o.add("union", uo);
            } else {
                o.add("union", JsonNull.INSTANCE);
            }
            final JsonArray embassiesAbroad = new JsonArray();
            for (final pl.medivalrpg.kraje.model.Embassy e : c.embassiesMap().values()) {
                final Country host = this.plugin.manager().get(e.host());
                if (host != null) {
                    embassiesAbroad.add(host.name());
                }
            }
            o.add("embassies", embassiesAbroad);
            final JsonArray embassiesHosted = new JsonArray();
            for (final Country guest : this.plugin.manager().embassyGuestsIn(c.id())) {
                embassiesHosted.add(guest.name());
            }
            o.add("hostedEmbassies", embassiesHosted);
            array.add(o);
        }
        root.add("countries", array);
        final String json = GSON.toJson(root);
        try {
            this.io.execute(() -> this.write(json));
        } catch (final RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void write(final String json) {
        final Path temp = this.target.resolveSibling("kraje.json.tmp");
        try {
            Files.createDirectories(this.target.getParent());
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, this.target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temp, this.target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie udało się zapisać " + this.target, e);
        }
    }

    void shutdown() {
        this.io.shutdown();
        try {
            this.io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
