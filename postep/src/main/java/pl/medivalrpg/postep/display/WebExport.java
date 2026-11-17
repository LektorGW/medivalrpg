// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.display;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.postep.PostepPlugin;
import pl.medivalrpg.postep.model.Discovery;
import pl.medivalrpg.postep.model.EraDef;
import pl.medivalrpg.postep.service.ProgressService;
import pl.medivalrpg.postep.service.Snapshot;
import pl.medivalrpg.postep.storage.DataStore;

/**
 * Writes {@code postep.json} (into squaremap's web directory by default) for the map side panel and the web panel.
 * Collected on the main thread from the snapshots, written in the background (tmp file + atomic move). Format in the
 * README ("Eksport postep.json").
 */
public final class WebExport {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final PostepPlugin plugin;
    private final DataStore store;

    public WebExport(final PostepPlugin plugin, final DataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** Target file; null when disabled. */
    public Path target() {
        final ProgressService s = this.plugin.service();
        if (!s.cfg().exportJson) {
            return null;
        }
        if (s.cfg().exportPath != null && !s.cfg().exportPath.isBlank()) {
            return Path.of(s.cfg().exportPath.trim());
        }
        final Plugin squaremap = Bukkit.getPluginManager().getPlugin("squaremap");
        if (squaremap == null) {
            return this.plugin.getDataFolder().toPath().resolve("postep.json");
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
        return dir.resolve("postep.json");
    }

    /** Main thread. */
    public void export() {
        final Path target = this.target();
        if (target == null) {
            return;
        }
        this.store.writeExternalAsync(target, GSON.toJson(this.build()));
    }

    JsonObject build() {
        final ProgressService s = this.plugin.service();
        final JsonObject root = new JsonObject();
        root.addProperty("updated", System.currentTimeMillis());
        root.addProperty("nextTick", s.nextTickAt());
        final JsonArray eras = new JsonArray();
        for (final EraDef e : s.cfg().eras) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("name", e.name());
            o.addProperty("years", e.years());
            o.addProperty("color", e.color());
            o.addProperty("icon", e.icon());
            o.addProperty("description", e.description());
            o.addProperty("minDays", e.minDays());
            o.addProperty("minPopulation", e.minPopulation());
            o.addProperty("minTier", e.minTier());
            eras.add(o);
        }
        root.add("eras", eras);
        final JsonArray discoveries = new JsonArray();
        for (final Discovery d : s.tree().all()) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", d.id());
            o.addProperty("name", d.name());
            o.addProperty("era", d.era());
            o.addProperty("cost", d.cost());
            final JsonArray req = new JsonArray();
            d.requires().forEach(req::add);
            o.add("requires", req);
            o.addProperty("requiredForEra", d.requiredForEra());
            o.addProperty("category", d.category());
            o.addProperty("description", d.description());
            o.addProperty("effect", d.effectText());
            discoveries.add(o);
        }
        root.add("discoveries", discoveries);

        final JsonArray countries = new JsonArray();
        final List<Snapshot> ranking = s.ranking();
        for (int i = 0; i < ranking.size(); i++) {
            final Snapshot c = ranking.get(i);
            final EraDef era = s.era(c.era());
            final JsonObject o = new JsonObject();
            o.addProperty("id", c.id().toString());
            o.addProperty("name", c.name());
            o.addProperty("color", c.color());
            o.addProperty("npc", c.npc());
            o.addProperty("rank", i + 1);
            o.addProperty("era", c.era());
            o.addProperty("eraName", era.name());
            o.addProperty("eraIcon", era.icon());
            o.addProperty("eraColor", era.color());
            o.addProperty("eraSince", c.eraSince());
            o.addProperty("maxEra", c.check().atMaxEra());
            o.addProperty("progress", Math.round(c.check().progress() * 1000) / 1000.0);
            o.addProperty("ready", c.check().ready());
            o.addProperty("pendingAdvance", c.pendingAdvance());
            o.addProperty("points", Math.floor(c.points()));
            o.addProperty("totalPoints", Math.floor(c.totalPoints()));
            o.addProperty("dailyPoints", Math.round(c.daily().total() * 10) / 10.0);
            o.addProperty("investedToday", (long) c.investedToday());
            final JsonArray owned = new JsonArray();
            c.discoveries().forEach(owned::add);
            o.add("discoveries", owned);
            o.addProperty("discoveryCount", c.discoveries().size());
            if (c.target() != null) {
                o.addProperty("target", c.target());
                o.addProperty("targetName", s.tree().get(c.target()).map(Discovery::name).orElse(c.target()));
            } else {
                o.add("target", JsonNull.INSTANCE);
                o.add("targetName", JsonNull.INSTANCE);
            }
            final JsonArray missing = new JsonArray();
            c.check().missing().forEach(missing::add);
            o.add("missing", missing);
            o.addProperty("population", c.population());
            o.addProperty("tier", c.tier());
            final Optional<KrajInfo> info = s.kraje().getCountry(c.id());
            if (info.isPresent() && info.get().capitalWorld() != null) {
                final JsonObject cap = new JsonObject();
                cap.addProperty("world", worldId(info.get().capitalWorld()));
                cap.addProperty("x", info.get().capitalX() * 16 + 8);
                cap.addProperty("z", info.get().capitalZ() * 16 + 8);
                o.add("capital", cap);
            } else {
                o.add("capital", JsonNull.INSTANCE);
            }
            countries.add(o);
        }
        root.add("countries", countries);
        return root;
    }

    /** squaremap world id ({@code minecraft_overworld}). */
    static String worldId(final String worldName) {
        final World world = Bukkit.getWorld(worldName);
        return world == null ? worldName : world.getKey().toString().replace(':', '_');
    }
}
