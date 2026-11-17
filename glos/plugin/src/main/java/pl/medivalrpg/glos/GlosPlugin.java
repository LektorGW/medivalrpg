// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Główna klasa pluginu Głos (serwerowa część proximity voice chatu). */
public final class GlosPlugin extends JavaPlugin {
    private static final String SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private GlosConfig config;
    private Messages messages;
    private MuteStore mutes;
    private PlayerPrefs prefs;
    private YamlConfiguration jarDefaults;
    private SpeakingIndicator indicator;
    private VoiceManager voice;
    private WorkerConnection connection;
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public void onEnable() {
        getLogger().info("Glos by Maciej Kaczmarczyk — lektoro.pl");
        saveDefaultConfig();
        ensureSecret();
        jarDefaults = loadJarDefaults();
        config = new GlosConfig(getConfig(), getLogger());
        messages = new Messages(getConfig(), jarDefaults);

        mutes = new MuteStore(getDataFolder(), getLogger());
        mutes.load();
        prefs = new PlayerPrefs(getDataFolder().toPath().resolve("prefs.json"), getLogger());
        prefs.load();

        indicator = new SpeakingIndicator(this, config, prefs);
        int orphans = indicator.cleanupOrphans();
        if (orphans > 0) {
            getLogger().info("Usunięto osierocone wskaźniki mówienia: " + orphans);
        }

        voice = new VoiceManager(this, config, messages, mutes, prefs, indicator);
        connection = new WorkerConnection(getLogger(), mainThreadExecutor(), voice);
        connection.setDebug(config.debug());
        voice.setConnection(connection);
        for (Player p : Bukkit.getOnlinePlayers()) {
            voice.playerJoined(p); // np. po przeładowaniu pluginu
        }

        getServer().getPluginManager().registerEvents(new GlosListener(this), this);

        GlosCommand command = new GlosCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command.build(), "Czat głosowy (proximity voice chat)",
                        List.of("vc", "voice", "audio")));

        tasks.add(Bukkit.getScheduler().runTaskTimer(this, voice::tickState, 4L, 4L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, voice::tickIndicators, 1L, 1L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, voice::tickSecond, 20L, 20L));

        startConnection();
    }

    @Override
    public void onDisable() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (indicator != null) {
            indicator.hideAll();
        }
        if (mutes != null) {
            mutes.save();
        }
        if (prefs != null) {
            prefs.close(); // dokończ zaległe zapisy prefs.json
        }
        if (connection != null) {
            connection.stop(); // maks. ~3 s, nie wiesza wyłączania serwera
        }
    }

    /** {@code /glos reload}: config, komunikaty, wyciszenia; połączenie restartuje się przy zmianie adresu/sekretu. */
    public void reloadAll() {
        String oldUrl = config.workerUrl();
        String oldSecret = config.pluginSecret();
        reloadConfig();
        ensureSecret();
        config = new GlosConfig(getConfig(), getLogger());
        messages = new Messages(getConfig(), jarDefaults);
        mutes.load();
        indicator.setConfig(config);
        voice.reload(config, messages);
        connection.setDebug(config.debug());
        if (!oldUrl.equals(config.workerUrl()) || !oldSecret.equals(config.pluginSecret())) {
            startConnection();
        }
    }

    private void startConnection() {
        if (config.workerUrl().isEmpty()) {
            getLogger().warning("Nie ustawiono worker-url w config.yml – czat głosowy jest wyłączony.");
            connection.disconnect();
            return;
        }
        try {
            connection.start(config.workerUrl(), config.pluginSecret());
        } catch (IllegalArgumentException e) {
            getLogger().severe("Niepoprawny worker-url: " + config.workerUrl() + " (" + e.getMessage() + ")");
        }
    }

    private void ensureSecret() {
        String secret = getConfig().getString("plugin-secret", "");
        if (secret == null || secret.isBlank()) {
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder(48);
            for (int i = 0; i < 48; i++) {
                sb.append(SECRET_ALPHABET.charAt(r.nextInt(SECRET_ALPHABET.length())));
            }
            getConfig().set("plugin-secret", sb.toString());
            saveConfig();
            getLogger().warning("Wygenerowano nowy plugin-secret i zapisano go w config.yml. "
                    + "Ustaw tę samą wartość w Workerze: wrangler secret put PLUGIN_SECRET");
        }
    }

    /** Domyślny config.yml z jara – źródło brakujących komunikatów w starszych configach serwera. */
    private YamlConfiguration loadJarDefaults() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Przerzuca zadanie na główny wątek; po wyłączeniu pluginu po cichu je porzuca. */
    private Executor mainThreadExecutor() {
        return task -> {
            if (!isEnabled()) {
                return;
            }
            try {
                Bukkit.getScheduler().runTask(this, task);
            } catch (IllegalPluginAccessException ignored) {
                // plugin właśnie się wyłącza
            }
        };
    }

    public String kickReason(String key, String def) {
        return getConfig().getString("kick-reasons." + key, def);
    }

    public VoiceManager voice() { return voice; }
    public Messages messages() { return messages; }
    public MuteStore mutes() { return mutes; }
    public PlayerPrefs prefs() { return prefs; }
    public SpeakingIndicator indicator() { return indicator; }
    public GlosConfig glosConfig() { return config; }
}
