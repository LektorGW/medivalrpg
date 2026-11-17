// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.audit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import pl.medivalrpg.panel.util.Json;

/**
 * {@code plugins/Panel/audit.log}: one JSON object per line for every panel mutation and login (who, what, when,
 * IP reported by the Worker, result). Appended on a background thread, rotated to {@code audit.log.1} above 5 MB.
 * The newest entries are also kept in memory for {@code admin.audit}. Passwords are never logged.
 */
public final class AuditLog {

    /**
     * One audit line.
     *
     * @param time   epoch millis
     * @param uuid   acting player (may be null for failed logins)
     * @param name   acting player name
     * @param ip     IP reported by the Worker
     * @param action RPC method, or {@code login}/{@code link}/{@code revoke}
     * @param params parameters (JSON text, truncated)
     * @param ok     success
     * @param error  error message when failed (may be null)
     */
    public record Entry(long time, String uuid, String name, String ip, String action, String params, boolean ok, String error) {

        public Map<String, Object> toJson() {
            return Json.obj("time", this.time, "uuid", this.uuid, "name", this.name, "ip", this.ip, "action", this.action,
                "params", this.params, "ok", this.ok, "error", this.error);
        }
    }

    private static final long ROTATE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_PARAMS = 600;

    private final Path file;
    private final Logger logger;
    private final int memory;
    private final Deque<Entry> recent = new ArrayDeque<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Panel-Audit");
        t.setDaemon(true);
        return t;
    });

    public AuditLog(final Path dataFolder, final Logger logger, final int memory) {
        this.file = dataFolder.resolve("audit.log");
        this.logger = logger;
        this.memory = Math.max(10, memory);
    }

    /** Loads the newest lines of audit.log into memory (startup). */
    public void loadRecent() {
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            final List<String> lines = Files.readAllLines(this.file, StandardCharsets.UTF_8);
            final int from = Math.max(0, lines.size() - this.memory);
            synchronized (this.recent) {
                for (final String line : lines.subList(from, lines.size())) {
                    final Entry e = parse(line);
                    if (e != null) {
                        this.recent.addLast(e);
                    }
                }
            }
        } catch (final IOException | RuntimeException e) {
            this.logger.log(Level.WARNING, "Nie udało się wczytać audit.log", e);
        }
    }

    public static Entry parse(final String line) {
        try {
            final JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            return new Entry(o.get("time").getAsLong(), str(o, "uuid"), str(o, "name"), str(o, "ip"), str(o, "action"),
                str(o, "params"), o.has("ok") && o.get("ok").getAsBoolean(), str(o, "error"));
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String str(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    public static String truncate(final String params) {
        if (params == null) {
            return null;
        }
        return params.length() > MAX_PARAMS ? params.substring(0, MAX_PARAMS) + "…" : params;
    }

    /** Records an entry (any thread). */
    public void log(final Entry entry) {
        synchronized (this.recent) {
            this.recent.addLast(entry);
            while (this.recent.size() > this.memory) {
                this.recent.removeFirst();
            }
        }
        final String line = Json.GSON.toJson(entry.toJson()) + "\n";
        try {
            this.io.execute(() -> this.append(line));
        } catch (final RejectedExecutionException e) {
            this.append(line);
        }
    }

    private void append(final String line) {
        try {
            Files.createDirectories(this.file.getParent());
            if (Files.exists(this.file) && Files.size(this.file) > ROTATE_BYTES) {
                Files.move(this.file, this.file.resolveSibling("audit.log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(this.file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException e) {
            this.logger.log(Level.WARNING, "Nie udało się dopisać do audit.log", e);
        }
    }

    /** Newest first, at most {@code limit}. */
    public List<Entry> recent(final int limit) {
        final List<Entry> out = new ArrayList<>();
        synchronized (this.recent) {
            final var it = this.recent.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                out.add(it.next());
            }
        }
        return out;
    }

    public void close() {
        this.io.shutdown();
        try {
            this.io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
