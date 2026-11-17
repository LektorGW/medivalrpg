// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.straz.util.DailyClock;

/** Immutable view of config.yml; invalid values fall back to defaults with a warning. */
public record StrazConfig(
    ZoneId zone,
    String webhookUrl, String username, boolean allowLoopback,
    LocalTime reportAt, boolean catchUp, int reportFileDays,
    long expectedPool, int poolEverySeconds, double tpsThreshold, int tpsMinutes, int lagSpikeMs, int newErrorDays,
    List<Pattern> ignoreErrors, int railStallHours, int alertsPerHour,
    boolean backupEnabled, LocalTime backupAt, String backupDir, int backupKeepDays, List<String> backupDirs,
    List<String> backupExcludes, double backupMbPerSecond, long backupMinBytes, int backupSpaceFactor
) {

    public static StrazConfig from(final ConfigurationSection c, final Logger log) {
        ZoneId zone;
        try {
            zone = ZoneId.of(c.getString("strefa", "Europe/Warsaw"));
        } catch (final RuntimeException e) {
            log.warning("strefa: nieznana strefa — używam Europe/Warsaw");
            zone = ZoneId.of("Europe/Warsaw");
        }
        final List<Pattern> ignore = new ArrayList<>();
        for (final String r : c.getStringList("alerty.ignoruj-bledy")) {
            try {
                ignore.add(Pattern.compile(r));
            } catch (final PatternSyntaxException e) {
                log.warning("alerty.ignoruj-bledy: niepoprawne wyrażenie „" + r + "” — pomijam");
            }
        }
        final LocalTime reportAt = DailyClock.parse(c.getString("raport.godzina"), LocalTime.of(9, 0));
        final LocalTime backupAt = DailyClock.parse(c.getString("kopia.godzina"), LocalTime.of(4, 30));
        List<String> dirs = c.getStringList("kopia.katalogi");
        dirs = dirs.stream().filter(d -> !d.isBlank() && !d.contains("..") && !d.contains("/") && !d.contains("\\")).toList();
        return new StrazConfig(zone,
            c.getString("discord.webhook-url", "").strip(), c.getString("discord.nazwa", "Straż MEDIVALRPG"),
            c.getBoolean("discord.zezwol-loopback-test", false),
            reportAt, c.getBoolean("raport.nadrabiaj", true), Math.max(1, c.getInt("raport.pliki-dni", 60)),
            c.getLong("alerty.pula-oczekiwana", 100_000_000L), Math.max(30, c.getInt("alerty.pula-co-sekund", 300)),
            c.getDouble("alerty.tps-prog", 15.0), Math.max(1, c.getInt("alerty.tps-minut", 5)),
            Math.max(100, c.getInt("alerty.lag-spike-ms", 500)), Math.max(1, c.getInt("alerty.nowy-blad-dni", 7)),
            List.copyOf(ignore), Math.max(0, c.getInt("alerty.kolej-bez-postepu-godzin", 12)),
            Math.max(1, c.getInt("alerty.max-na-godzine", 12)),
            c.getBoolean("kopia.wlaczona", true), backupAt, c.getString("kopia.katalog", "backups"),
            Math.max(1, c.getInt("kopia.zachowaj-dni", 14)), List.copyOf(dirs), List.copyOf(c.getStringList("kopia.pomijaj")),
            Math.max(0, c.getDouble("kopia.limit-mb-s", 20)), Math.max(0, c.getLong("kopia.min-bajtow", 1_000_000L)),
            Math.max(1, c.getInt("kopia.zapas-miejsca", 3)));
    }

    public boolean ignored(final String message) {
        for (final Pattern p : this.ignoreErrors) {
            if (p.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }
}
