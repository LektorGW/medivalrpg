// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import pl.medivalrpg.zycie.logic.FireIncident;

/** Dedicated single writer; serialise on main thread, atomic files and recovery from .bak. */
public final class FireStore {
    public static final class Data { public List<FireIncident> incidents = new ArrayList<>(); }
    private final Path file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Zycie-pozary-zapis"); t.setDaemon(true); return t;
    });

    public FireStore(final Path directory, final Logger logger) {
        this.file = directory.resolve("pozary.json"); this.logger = logger;
    }

    public List<FireIncident> load() {
        for (final Path candidate : List.of(this.file, this.file.resolveSibling("pozary.json.bak"))) {
            if (!Files.exists(candidate)) continue;
            try {
                final Data data = this.gson.fromJson(Files.readString(candidate, StandardCharsets.UTF_8), Data.class);
                if (data != null && data.incidents != null) return data.incidents.stream()
                    .filter(i -> i != null && i.country != null && i.world != null).map(FireIncident::normalize).toList();
            } catch (final Exception e) {
                this.logger.log(Level.WARNING, "Nie można wczytać rejestru pożarów " + candidate.getFileName(), e);
            }
        }
        return List.of();
    }

    private String json(final Collection<FireIncident> incidents) {
        final Data data = new Data(); data.incidents = new ArrayList<>(incidents); return this.gson.toJson(data);
    }

    public void save(final Collection<FireIncident> incidents) {
        final String snapshot = this.json(incidents);
        this.writer.execute(() -> this.write(snapshot));
    }

    public void close(final Collection<FireIncident> incidents) {
        final String snapshot = this.json(incidents);
        this.writer.shutdown();
        try { if (!this.writer.awaitTermination(5, TimeUnit.SECONDS)) this.logger.warning("Zapis pożarów nadal trwa"); }
        catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
        this.write(snapshot);
    }

    private synchronized void write(final String snapshot) {
        try {
            Files.createDirectories(this.file.getParent());
            final Path tmp = this.file.resolveSibling("pozary.json.tmp");
            Files.writeString(tmp, snapshot, StandardCharsets.UTF_8);
            if (Files.exists(this.file)) Files.copy(this.file, this.file.resolveSibling("pozary.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(tmp, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (final java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (final Exception e) { this.logger.log(Level.SEVERE, "Nie można zapisać rejestru pożarów", e); }
    }
}
