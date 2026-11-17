// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.limbo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * What a waiting player had before Straznik hid it: real position, inventory (Paper item bytes, Base64) and flight
 * flags. One JSON file per player in {@code limbo/}, written synchronously and atomically BEFORE anything is
 * hidden, deleted only after the player's data with the restored state has been saved. A file left behind by a crash
 * is used on the next join, so nothing is lost.
 */
public final class LimboStore {

    /** Saved state. {@code items} = {@code ItemStack.serializeItemsAsBytes(getContents())} in Base64, or null. */
    public record Snapshot(String world, double x, double y, double z, float yaw, float pitch, String items,
                           boolean allowFlight, boolean flying, long created) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dir;
    private final Logger logger;

    public LimboStore(final Path dir, final Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    private Path file(final UUID player) {
        return this.dir.resolve(player + ".json");
    }

    public boolean exists(final UUID player) {
        return Files.isRegularFile(this.file(player));
    }

    public Optional<Snapshot> load(final UUID player) {
        final Path f = this.file(player);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Snapshot.class));
        } catch (final IOException | RuntimeException e) {
            this.logger.severe("Nie można odczytać kopii limbo " + f.getFileName() + ": " + e.getMessage()
                + " — plik zostaje bez zmian, ekwipunek gracza nie jest czyszczony.");
            return Optional.empty();
        }
    }

    /** Atomic, fsynced write; throws when the state could not be secured (the caller must then hide nothing). */
    public void save(final UUID player, final Snapshot snapshot) throws IOException {
        Files.createDirectories(this.dir);
        final Path target = this.file(player);
        final Path tmp = this.dir.resolve(player + ".json.tmp");
        Files.writeString(tmp, GSON.toJson(snapshot), StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Keeps an old snapshot under another name instead of overwriting it (never lose items). */
    public void archive(final UUID player) {
        final Path f = this.file(player);
        try {
            if (Files.isRegularFile(f)) {
                Files.move(f, this.dir.resolve(player + ".old-" + System.currentTimeMillis() + ".json"),
                    StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (final IOException e) {
            this.logger.warning("Nie można zarchiwizować kopii limbo " + player + ": " + e.getMessage());
        }
    }

    public void delete(final UUID player) {
        try {
            Files.deleteIfExists(this.file(player));
        } catch (final IOException e) {
            this.logger.warning("Nie można usunąć kopii limbo " + player + ": " + e.getMessage());
        }
    }

    /** Number of snapshot files (players who still have something to get back). */
    public int count() {
        if (!Files.isDirectory(this.dir)) {
            return 0;
        }
        try (var s = Files.list(this.dir)) {
            return (int) s.filter(p -> {
                final String n = p.getFileName().toString();
                return n.endsWith(".json") && !n.contains(".old-");
            }).count();
        } catch (final IOException e) {
            return -1;
        }
    }
}
