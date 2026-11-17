// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wyciszenia adminów w {@code mutes.yml}: {@code mutes.<uuid>: <wygasa_ms | -1>}.
 * Mapy są współbieżne (odczyt np. z podpowiedzi komend), zapis pliku na głównym wątku.
 */
public final class MuteStore {
    public static final long PERMANENT = -1L;

    private final File file;
    private final Logger log;
    private final Map<UUID, Long> mutes = new ConcurrentHashMap<>();
    /** Pamięć podręczna nicków (do podpowiedzi przy /glos unmute dla graczy offline). */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public MuteStore(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "mutes.yml");
        this.log = log;
    }

    public void load() {
        mutes.clear();
        YamlConfiguration yaml = readYaml(file.toPath(), log);
        if (yaml == null) {
            return;
        }
        ConfigurationSection sec = yaml.getConfigurationSection("mutes");
        if (sec == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String key : sec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                log.warning("mutes.yml: pomijam niepoprawny UUID '" + key + "'");
                continue;
            }
            long expires = sec.getLong(key, PERMANENT);
            if (expires != PERMANENT && expires <= now) {
                continue;
            }
            mutes.put(id, expires);
            String name = Bukkit.getOfflinePlayer(id).getName();
            if (name != null) {
                names.put(id, name);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Wyciszenia czatu głosowego: uuid -> czas wygaśnięcia (ms od epoki) lub -1 (na stałe)."));
        ConfigurationSection sec = yaml.createSection("mutes");
        mutes.forEach((id, exp) -> sec.set(id.toString(), exp));
        try {
            writeAtomic(file.toPath(), yaml.saveToString());
        } catch (IOException e) {
            log.log(Level.SEVERE, "Nie udało się zapisać mutes.yml", e);
        }
    }

    /**
     * Zapis przez plik tymczasowy z atomową podmianą; poprzednia wersja zostaje w {@code <plik>.bak}.
     * Przerwany zapis (awaria, brak miejsca) nie niszczy już istniejących wyciszeń.
     */
    static void writeAtomic(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        if (Files.exists(target)) {
            Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Wczytuje YAML; gdy plik jest uszkodzony, próbuje {@code <plik>.bak}.
     * @return konfiguracja albo {@code null}, gdy nie ma czego wczytać
     */
    static YamlConfiguration readYaml(Path file, Logger log) {
        Path bak = file.resolveSibling(file.getFileName() + ".bak");
        for (Path p : new Path[] {file, bak}) {
            if (!Files.exists(p)) {
                continue;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(p.toFile());
                if (p == bak) {
                    log.warning(file.getFileName() + " jest uszkodzony – wczytano kopię " + bak.getFileName());
                }
                return yaml;
            } catch (IOException | InvalidConfigurationException e) {
                log.log(Level.WARNING, "Nie udało się wczytać " + p.getFileName(), e);
            }
        }
        return null;
    }

    public boolean isMuted(UUID id) {
        Long exp = mutes.get(id);
        return exp != null && (exp == PERMANENT || exp > System.currentTimeMillis());
    }

    /** @return czas wygaśnięcia (ms), {@link #PERMANENT} albo {@code null}, gdy niewyciszony. */
    public Long expiry(UUID id) {
        return isMuted(id) ? mutes.get(id) : null;
    }

    public void mute(UUID id, String name, long expiresAtOrPermanent) {
        mutes.put(id, expiresAtOrPermanent);
        if (name != null) {
            names.put(id, name);
        }
        save();
    }

    /** @return {@code true}, gdy gracz był wyciszony. */
    public boolean unmute(UUID id) {
        boolean had = mutes.remove(id) != null;
        if (had) {
            save();
        }
        return had;
    }

    /** Usuwa wygasłe wyciszenia; zwraca listę graczy, którym właśnie wygasło. */
    public List<UUID> purgeExpired() {
        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();
        mutes.forEach((id, exp) -> {
            if (exp != PERMANENT && exp <= now) {
                expired.add(id);
            }
        });
        if (!expired.isEmpty()) {
            expired.forEach(mutes::remove);
            save();
        }
        return expired;
    }

    /** Nicki wyciszonych graczy (dla podpowiedzi). */
    public List<String> mutedNames() {
        List<String> out = new ArrayList<>();
        for (UUID id : mutes.keySet()) {
            String n = names.get(id);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    /** Szuka wyciszonego gracza po nicku (bez rozróżniania wielkości liter). */
    public UUID findMutedByName(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name) && mutes.containsKey(e.getKey())) {
                return e.getKey();
            }
        }
        return null;
    }

    public void rememberName(UUID id, String name) {
        names.put(id, name);
    }
}
