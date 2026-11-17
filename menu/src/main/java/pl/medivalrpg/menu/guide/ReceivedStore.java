// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.guide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Players who already got the guide compass automatically (one UUID per line). Loaded on the main thread at
 * start; saved asynchronously on a single writer thread (tmp file + atomic replace, previous file kept as .bak).
 */
public final class ReceivedStore {

    private final Path file;
    private final Logger logger;
    private final Set<UUID> received = new HashSet<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Menu-ReceivedStore");
        t.setDaemon(true);
        return t;
    });

    public ReceivedStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load() {
        this.received.clear();
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            for (final String line : Files.readAllLines(this.file, StandardCharsets.UTF_8)) {
                final String s = line.strip();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    try {
                        this.received.add(UUID.fromString(s));
                    } catch (final IllegalArgumentException ignored) {
                        // skip garbage
                    }
                }
            }
        } catch (final IOException e) {
            this.logger.warning("Nie można wczytać " + this.file + ": " + e);
        }
    }

    public boolean has(final UUID id) {
        return this.received.contains(id);
    }

    public void add(final UUID id) {
        if (this.received.add(id)) {
            this.saveAsync();
        }
    }

    private void saveAsync() {
        final List<String> lines = new ArrayList<>();
        lines.add("# Gracze, którzy dostali już kompas Przewodnik automatycznie (Menu by Maciej Kaczmarczyk — lektoro.pl)");
        this.received.forEach(id -> lines.add(id.toString()));
        this.writer.execute(() -> this.write(lines));
    }

    private void write(final List<String> lines) {
        try {
            Files.createDirectories(this.file.getParent());
            final Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            if (Files.exists(this.file)) {
                Files.copy(this.file, this.file.resolveSibling(this.file.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException atomicFailed) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.logger.warning("Nie można zapisać " + this.file + ": " + e);
        }
    }

    /** Waits for pending writes (plugin disable). */
    public void close() {
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(5, TimeUnit.SECONDS)) {
                this.logger.warning("Zapis listy Przewodnika nie zakończył się w 5 s.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
