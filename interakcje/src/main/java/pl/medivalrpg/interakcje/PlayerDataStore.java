// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Player preferences in {@code players.yml}: who disabled interactions and personal block lists.
 * Collections are concurrent (command suggestions may read them off the main thread); the YAML snapshot
 * is built on the caller's thread and written to disk on a single background thread, in order.
 */
public final class PlayerDataStore {
    private final File file;
    private final Logger log;
    private final Set<UUID> disabled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<UUID>> blocks = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** Knight titles: player -> title (Sir / Pani); {@link #titleHidden} = the player hides it. */
    private final Map<UUID, Title> titles = new ConcurrentHashMap<>();

    /**
     * A knight title granted at a knighting.
     *
     * @param title    the word shown before the nick
     * @param byName   who knighted the player (a ruler)
     * @param since    epoch millis
     * @param hidden   the player chose not to show it
     */
    public record Title(String title, String byName, long since, boolean hidden) {
    }
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Interakcje-save");
        t.setDaemon(true);
        return t;
    });

    public PlayerDataStore(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "players.yml");
        this.log = log;
    }

    public void load() {
        disabled.clear();
        blocks.clear();
        titles.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            log.log(Level.SEVERE, "Nie udało się wczytać players.yml – zaczynam z pustymi danymi.", e);
            return;
        }
        for (String s : yaml.getStringList("disabled")) {
            UUID id = parse(s);
            if (id != null) {
                disabled.add(id);
            }
        }
        ConfigurationSection b = yaml.getConfigurationSection("blocks");
        if (b != null) {
            for (String owner : b.getKeys(false)) {
                UUID ownerId = parse(owner);
                if (ownerId == null) {
                    continue;
                }
                Set<UUID> set = ConcurrentHashMap.newKeySet();
                for (String s : b.getStringList(owner)) {
                    UUID id = parse(s);
                    if (id != null) {
                        set.add(id);
                    }
                }
                if (!set.isEmpty()) {
                    blocks.put(ownerId, set);
                }
            }
        }
        ConfigurationSection t = yaml.getConfigurationSection("titles");
        if (t != null) {
            for (String key : t.getKeys(false)) {
                UUID id = parse(key);
                ConfigurationSection e = t.getConfigurationSection(key);
                if (id != null && e != null && e.getString("title") != null) {
                    titles.put(id, new Title(e.getString("title"), e.getString("by", ""), e.getLong("since", 0L),
                            e.getBoolean("hidden", false)));
                }
            }
        }
        ConfigurationSection n = yaml.getConfigurationSection("names");
        if (n != null) {
            for (String key : n.getKeys(false)) {
                UUID id = parse(key);
                String name = n.getString(key);
                if (id != null && name != null) {
                    names.put(id, name);
                }
            }
        }
    }

    private UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            log.warning("players.yml: pomijam niepoprawny UUID '" + s + "'");
            return null;
        }
    }

    // ------------------------------------------------------------------ queries

    public boolean isDisabled(UUID player) {
        return disabled.contains(player);
    }

    /** Does {@code owner} block {@code other}? */
    public boolean isBlocked(UUID owner, UUID other) {
        Set<UUID> set = blocks.get(owner);
        return set != null && set.contains(other);
    }

    public Set<UUID> blockedBy(UUID owner) {
        Set<UUID> set = blocks.get(owner);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    public String name(UUID id) {
        return names.get(id);
    }

    /** Known names of players blocked by {@code owner} (for suggestions / listing). */
    public List<String> blockedNames(UUID owner) {
        List<String> out = new ArrayList<>();
        for (UUID id : blockedBy(owner)) {
            out.add(names.getOrDefault(id, id.toString()));
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Blocked player of {@code owner} with this name (case-insensitive), or {@code null}. */
    public UUID findBlockedByName(UUID owner, String name) {
        for (UUID id : blockedBy(owner)) {
            String n = names.get(id);
            if ((n != null && n.equalsIgnoreCase(name)) || id.toString().equalsIgnoreCase(name)) {
                return id;
            }
        }
        return null;
    }

    public Title title(UUID player) {
        return titles.get(player);
    }

    /** Title to show before the nick, or {@code null} (none or hidden). */
    public String shownTitle(UUID player) {
        Title t = titles.get(player);
        return t == null || t.hidden() ? null : t.title();
    }

    public void setTitle(UUID player, Title title) {
        if (title == null) {
            titles.remove(player);
        } else {
            titles.put(player, title);
        }
        saveAsync();
    }

    // ------------------------------------------------------------------ changes (each one saves)

    /** @return {@code true} when the value changed. */
    public boolean setDisabled(UUID player, boolean value) {
        boolean changed = value ? disabled.add(player) : disabled.remove(player);
        if (changed) {
            saveAsync();
        }
        return changed;
    }

    /** @return {@code true} when {@code other} was not blocked before. */
    public boolean block(UUID owner, UUID other, String otherName) {
        if (otherName != null) {
            names.put(other, otherName);
        }
        boolean changed = blocks.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(other);
        if (changed) {
            saveAsync();
        }
        return changed;
    }

    /** @return {@code true} when {@code other} was blocked. */
    public boolean unblock(UUID owner, UUID other) {
        Set<UUID> set = blocks.get(owner);
        boolean changed = set != null && set.remove(other);
        if (set != null && set.isEmpty()) {
            blocks.remove(owner);
        }
        if (changed) {
            saveAsync();
        }
        return changed;
    }

    /** Keeps the name cache fresh (blocked players may change nick only via UUID, so this is cosmetic). */
    public void rememberName(UUID id, String name) {
        if (!name.equals(names.get(id)) && isReferenced(id)) {
            names.put(id, name);
            saveAsync();
        }
    }

    private boolean isReferenced(UUID id) {
        for (Set<UUID> set : blocks.values()) {
            if (set.contains(id)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ persistence

    private String snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Interakcje – dane graczy (zapisywane automatycznie).",
                "disabled: gracze z wyłączonymi interakcjami; blocks: <uuid> -> zablokowani przez niego;",
                "names: ostatnio znane nicki zablokowanych graczy; titles: tytuły rycerskie z pasowania."));
        yaml.set("disabled", disabled.stream().map(UUID::toString).sorted().toList());
        ConfigurationSection b = yaml.createSection("blocks");
        blocks.forEach((owner, set) -> {
            if (!set.isEmpty()) {
                b.set(owner.toString(), set.stream().map(UUID::toString).sorted().toList());
            }
        });
        ConfigurationSection t = yaml.createSection("titles");
        titles.forEach((id, title) -> {
            ConfigurationSection e = t.createSection(id.toString());
            e.set("title", title.title());
            e.set("by", title.byName());
            e.set("since", title.since());
            if (title.hidden()) {
                e.set("hidden", true);
            }
        });
        ConfigurationSection n = yaml.createSection("names");
        names.forEach((id, name) -> {
            if (isReferenced(id)) {
                n.set(id.toString(), name);
            }
        });
        return yaml.saveToString();
    }

    public void saveAsync() {
        String data = snapshot();
        try {
            writer.execute(() -> write(data));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            write(data); // shutting down
        }
    }

    /** Waits for pending writes and writes the final state synchronously (onDisable). */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warning("Zapis players.yml trwa zbyt długo – kontynuuję wyłączanie.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        write(snapshot());
    }

    private synchronized void write(String data) {
        try {
            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, data, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Nie udało się zapisać players.yml", e);
        }
    }
}
