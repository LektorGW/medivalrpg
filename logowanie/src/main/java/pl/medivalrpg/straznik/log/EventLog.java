// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Security log {@code zdarzenia.log}: one line per event (time, event, nick, address, details). Never contains a
 * password or a hash — callers pass only fixed words and nicks. Written by a background thread; rotated at 5 MiB.
 */
public final class EventLog implements AutoCloseable {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;
    private final Logger logger;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Straznik-Log");
        t.setDaemon(true);
        return t;
    });

    public EventLog(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Values are sanitised (no line breaks, no spaces in fields). */
    public void log(final String event, final String nick, final String ip, final String details) {
        final String line = TIME.format(LocalDateTime.now()) + " " + clean(event) + " gracz=" + clean(nick) + " ip="
            + clean(ip) + (details == null || details.isEmpty() ? "" : " " + details.replaceAll("[\r\n]", " ")) + "\n";
        try {
            this.io.execute(() -> this.append(line));
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            // shutting down
        }
    }

    /** One field: empty → "-", every whitespace character (also tab, line break) → "_". Visible for tests. */
    static String clean(final String s) {
        return s == null || s.isEmpty() ? "-" : s.replaceAll("\\s", "_");
    }

    private void append(final String line) {
        try {
            Files.createDirectories(this.file.toAbsolutePath().getParent());
            if (Files.exists(this.file) && Files.size(this.file) > MAX_BYTES) {
                Files.move(this.file, this.file.resolveSibling(this.file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(this.file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException e) {
            this.logger.warning("Nie można zapisać dziennika zdarzeń: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        this.io.shutdown();
        try {
            this.io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
