// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import pl.medivalrpg.armia.model.NpcState;
import pl.medivalrpg.armia.model.RulerRecord;
import pl.medivalrpg.armia.model.SoldierRecord;

/**
 * JSON files in the plugin folder. Snapshots are serialised on the main thread and written by one background
 * thread: {@code <file>.tmp} → current file becomes {@code <file>.bak} → atomic move. Loading falls back to the
 * {@code .bak} copy when the main file is broken.
 */
public final class DataStore {

    /** Small global state. */
    public static final class Meta {
        public long lastUpkeepAt;
        public boolean aiPaused;
    }

    private static final Type SOLDIERS = new TypeToken<List<SoldierRecord>>() { }.getType();
    private static final Type NPCS = new TypeToken<Map<UUID, NpcState>>() { }.getType();
    private static final Type RULERS = new TypeToken<Map<UUID, RulerRecord>>() { }.getType();

    private final Path dir;
    private final Logger logger;
    /** Files whose main copy was unreadable at load: their .bak is kept on the first write (read on main, used by the writer). */
    private final java.util.Set<String> keepBackup = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Armia-zapis");
        t.setDaemon(true);
        return t;
    });

    public DataStore(final Path dir, final Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    public List<SoldierRecord> loadSoldiers() {
        final List<SoldierRecord> list = this.read("soldiers.json", SOLDIERS);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    public Map<UUID, NpcState> loadNpcs() {
        final Map<UUID, NpcState> map = this.read("npc.json", NPCS);
        return map == null ? new HashMap<>() : new HashMap<>(map);
    }

    public Map<UUID, RulerRecord> loadRulers() {
        final Map<UUID, RulerRecord> map = this.read("rulers.json", RULERS);
        return map == null ? new HashMap<>() : new HashMap<>(map);
    }

    public Meta loadMeta() {
        final Meta meta = this.read("meta.json", Meta.class);
        return meta == null ? new Meta() : meta;
    }

    /** Main thread: serialise now, write in the background. */
    public void saveAsync(final List<SoldierRecord> soldiers, final Map<UUID, NpcState> npcs, final Map<UUID, RulerRecord> rulers, final Meta meta) {
        final Map<String, String> files = this.serialise(soldiers, npcs, rulers, meta);
        this.writer.execute(() -> files.forEach(this::write));
    }

    /** Rare live administrative barrier: keep the writer alive and fail closed until the soldier snapshot is durable. */
    public void checkpointSoldiers(final List<SoldierRecord> soldiers) {
        final String snapshot=this.gson.toJson(soldiers,SOLDIERS);
        final java.util.concurrent.Future<?> checkpoint=this.writer.submit(()->{
            try {
                Files.createDirectories(this.dir);
                final Path file=this.dir.resolve("soldiers.json"),tmp=this.dir.resolve("soldiers.json.tmp");
                final java.nio.ByteBuffer bytes=StandardCharsets.UTF_8.encode(snapshot);
                try(final java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.WRITE,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                    while(bytes.hasRemaining()) channel.write(bytes);
                    channel.force(true);
                }
                if(Files.exists(file) && !this.keepBackup.remove("soldiers.json")) Files.copy(file,this.dir.resolve("soldiers.json.bak"),StandardCopyOption.REPLACE_EXISTING);
                try {Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(final AtomicMoveNotSupportedException e) {Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
                // Linux production: persist the rename as well as the contents. Windows does not expose directory handles here.
                if(!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win"))
                    try(final java.nio.channels.FileChannel directory=java.nio.channels.FileChannel.open(this.dir,java.nio.file.StandardOpenOption.READ)) {directory.force(true);}
            } catch(final IOException error) {throw new java.io.UncheckedIOException(error);}
        });
        try {checkpoint.get(10,TimeUnit.SECONDS);}
        catch(final InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("Przerwano trwały zapis ewakuacji armii.",error);}
        catch(final java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new IllegalStateException("Nie potwierdzono trwałego zapisu ewakuacji armii; przebudowa nie może przejść dalej.",error);
        }
    }

    /** Main thread, on disable: serialise and write synchronously (after pending background writes). */
    public void saveNow(final List<SoldierRecord> soldiers, final Map<UUID, NpcState> npcs, final Map<UUID, RulerRecord> rulers, final Meta meta) {
        final Map<String, String> files = this.serialise(soldiers, npcs, rulers, meta);
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.warning("Zapis w tle nie skończył się w 10 s");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        files.forEach(this::write);
    }

    private Map<String, String> serialise(final List<SoldierRecord> soldiers, final Map<UUID, NpcState> npcs, final Map<UUID, RulerRecord> rulers, final Meta meta) {
        final Map<String, String> files = new HashMap<>();
        files.put("soldiers.json", this.gson.toJson(soldiers, SOLDIERS));
        files.put("npc.json", this.gson.toJson(npcs, NPCS));
        files.put("rulers.json", this.gson.toJson(rulers, RULERS));
        files.put("meta.json", this.gson.toJson(meta));
        return files;
    }

    private <T> T read(final String name, final Type type) {
        final Path file = this.dir.resolve(name);
        final Path bak = this.dir.resolve(name + ".bak");
        if (Files.exists(file)) {
            Exception problem;
            try {
                final T value = this.gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
                if (value != null) {
                    return value;
                }
                // An empty (0-byte, e.g. after a crash or a full disk) or "null" file parses to null without an error:
                // it must not be read as "no soldiers" — that would erase the army at the next save.
                problem = new JsonParseException("pusty plik");
            } catch (final IOException | JsonParseException e) {
                problem = e;
            }
            this.logger.log(Level.SEVERE, "Nie można wczytać " + name + ", próbuję kopii .bak", problem);
            // The next write must not copy the broken file over the (possibly only) good .bak copy.
            this.keepBackup.add(name);
            try {
                Files.copy(file, this.dir.resolve(name + ".broken-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException ignored) {
                // best effort
            }
        }
        if (Files.exists(bak)) {
            try {
                return this.gson.fromJson(Files.readString(bak, StandardCharsets.UTF_8), type);
            } catch (final IOException | JsonParseException e) {
                this.logger.log(Level.SEVERE, "Kopia " + name + ".bak też jest uszkodzona", e);
            }
        }
        return null;
    }

    private void write(final String name, final String json) {
        try {
            Files.createDirectories(this.dir);
            final Path file = this.dir.resolve(name);
            final Path tmp = this.dir.resolve(name + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            if (Files.exists(file) && !this.keepBackup.remove(name)) {
                Files.copy(file, this.dir.resolve(name + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Nie można zapisać " + name, e);
        }
    }

    /** Appends a line to {@code logs/<name>} in the background. */
    public void appendLog(final String name, final String line) {
        if (this.writer.isShutdown()) {
            return;
        }
        this.writer.execute(() -> {
            try {
                final Path logs = this.dir.resolve("logs");
                Files.createDirectories(logs);
                final Path file = logs.resolve(name);
                if (Files.exists(file) && Files.size(file) > 5L * 1024 * 1024) {
                    Files.move(file, logs.resolve(name + ".1"), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (final IOException e) {
                this.logger.log(Level.WARNING, "Nie można dopisać do logu " + name, e);
            }
        });
    }
}
