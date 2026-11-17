// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/** Loads/saves {@link StrazState} atomically (tmp + move, previous kept as .bak). */
public final class StateStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final Logger logger;

    public StateStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public StrazState load() {
        for (final Path p : new Path[] {this.file, this.file.resolveSibling(this.file.getFileName() + ".bak")}) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                final StrazState s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), StrazState.class);
                if (s != null) {
                    normalize(s);
                    return s;
                }
            } catch (final IOException | RuntimeException e) {
                this.logger.warning(p.getFileName() + ": nie można odczytać (" + e.getMessage() + ")");
            }
        }
        return new StrazState();
    }

    /** Gson leaves fields missing from an older file null. */
    static void normalize(final StrazState s) {
        final StrazState d = new StrazState();
        if (s.lastBackup == null) s.lastBackup = d.lastBackup;
        if (s.log == null) s.log = d.log;
        if (s.errors == null) s.errors = d.errors;
        if (s.errors.sigs == null) s.errors.sigs = new java.util.LinkedHashMap<>();
        if (s.metrics == null) s.metrics = d.metrics;
        if (s.metrics.hours == null) s.metrics.hours = new java.util.TreeMap<>();
        if (s.alerts == null) s.alerts = d.alerts;
        if (s.alerts.lastSent == null) s.alerts.lastSent = new java.util.LinkedHashMap<>();
        if (s.alerts.sentTimes == null) s.alerts.sentTimes = new java.util.ArrayList<>();
        if (s.flowHistory == null) s.flowHistory = d.flowHistory;
        if (s.recentAlerts == null) s.recentAlerts = d.recentAlerts;
        if (s.lastReportDay == null) s.lastReportDay = "";
        if (s.lastBackupDay == null) s.lastBackupDay = "";
        if (s.lastReportFile == null) s.lastReportFile = "";
        if (s.railStopKey == null) s.railStopKey = "";
        if (s.poolAlertTotal == null) s.poolAlertTotal = "";
        if (s.log.id == null) s.log.id = "";
        for (final var sig : s.errors.sigs.values()) {
            if (sig.hours == null) sig.hours = new java.util.TreeMap<>();
        }
    }

    /**
     * Serializes under the state's monitor, writes outside of it. Synchronized: the I/O thread, the backup thread and
     * the plugin shutdown may save at the same moment and would otherwise share {@code stan.json.tmp}.
     */
    public synchronized void save(final StrazState state) throws IOException {
        final String json;
        synchronized (state) {
            json = GSON.toJson(state);
        }
        Files.createDirectories(this.file.getParent());
        final Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        if (Files.isRegularFile(this.file)) {
            Files.copy(this.file, this.file.resolveSibling(this.file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
