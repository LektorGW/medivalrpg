// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Niezmienny odczyt config.yml. Nowa instancja przy każdym {@code /glos reload}. */
public final class GlosConfig {
    public enum IndicatorAttach { PASSENGER, FOLLOW }

    private final String workerUrl;
    private final String pluginSecret;
    private final Map<VoiceMode, Integer> ranges = new EnumMap<>(VoiceMode.class);
    private final boolean sneakWhisper;
    private final boolean actionbar;
    private final int offlineKickSeconds;
    private final boolean debug;

    private final boolean indicatorEnabled;
    private final IndicatorAttach indicatorAttach;
    private final float indicatorHeight;
    private final Color indicatorBackground;
    private final boolean indicatorHideWhenInvisible;
    private final Map<VoiceMode, String> indicatorText = new EnumMap<>(VoiceMode.class);

    public GlosConfig(FileConfiguration cfg, Logger log) {
        String url = cfg.getString("worker-url", "").trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.workerUrl = url;
        this.pluginSecret = cfg.getString("plugin-secret", "").trim();

        ranges.put(VoiceMode.WHISPER, Math.max(1, cfg.getInt("ranges.whisper", 6)));
        ranges.put(VoiceMode.NORMAL, Math.max(1, cfg.getInt("ranges.normal", 24)));
        ranges.put(VoiceMode.SHOUT, Math.max(1, cfg.getInt("ranges.shout", 48)));

        this.sneakWhisper = cfg.getBoolean("sneak-whisper", true);
        this.actionbar = cfg.getBoolean("actionbar", true);
        this.offlineKickSeconds = Math.max(0, cfg.getInt("offline-kick-seconds", 60));
        this.debug = cfg.getBoolean("debug", false);

        ConfigurationSection ind = cfg.getConfigurationSection("indicator");
        if (ind == null) {
            ind = cfg.createSection("indicator");
        }
        this.indicatorEnabled = ind.getBoolean("enabled", true);
        String attach = ind.getString("attach", "passenger").trim().toUpperCase(Locale.ROOT);
        IndicatorAttach a;
        try {
            a = IndicatorAttach.valueOf(attach);
        } catch (IllegalArgumentException e) {
            log.warning("indicator.attach: nieznana wartość '" + attach + "', używam 'passenger'.");
            a = IndicatorAttach.PASSENGER;
        }
        this.indicatorAttach = a;
        this.indicatorHeight = (float) ind.getDouble("height", 0.8);
        this.indicatorBackground = parseArgb(ind.getString("background", "#40000000"), log);
        this.indicatorHideWhenInvisible = ind.getBoolean("hide-when-invisible", true);
        indicatorText.put(VoiceMode.WHISPER, ind.getString("text.whisper", "<gray>♪ <i>szept</i></gray>"));
        indicatorText.put(VoiceMode.NORMAL, ind.getString("text.normal", "<green>♪ ♫ ♪</green>"));
        indicatorText.put(VoiceMode.SHOUT, ind.getString("text.shout", "<red><b>♫ KRZYK ♫</b></red>"));
    }

    private static Color parseArgb(String raw, Logger log) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        try {
            if (s.length() == 6) {
                s = "FF" + s;
            }
            if (s.length() != 8) {
                throw new NumberFormatException();
            }
            return Color.fromARGB((int) Long.parseLong(s, 16));
        } catch (NumberFormatException e) {
            log.warning("indicator.background: niepoprawny kolor '" + raw + "', używam #40000000.");
            return Color.fromARGB(0x40, 0, 0, 0);
        }
    }

    public String workerUrl() { return workerUrl; }
    public String pluginSecret() { return pluginSecret; }
    public int range(VoiceMode mode) { return ranges.get(mode); }
    /** Maksymalny zasięg (zwykle krzyk) – podstawa filtra odległości peerów. */
    public int maxRange() { return ranges.values().stream().mapToInt(Integer::intValue).max().orElse(48); }
    public boolean sneakWhisper() { return sneakWhisper; }
    public boolean actionbar() { return actionbar; }
    public int offlineKickSeconds() { return offlineKickSeconds; }
    public boolean debug() { return debug; }
    public boolean indicatorEnabled() { return indicatorEnabled; }
    public IndicatorAttach indicatorAttach() { return indicatorAttach; }
    public float indicatorHeight() { return indicatorHeight; }
    public Color indicatorBackground() { return indicatorBackground; }
    public boolean indicatorHideWhenInvisible() { return indicatorHideWhenInvisible; }
    public String indicatorText(VoiceMode mode) { return indicatorText.get(mode); }
}
