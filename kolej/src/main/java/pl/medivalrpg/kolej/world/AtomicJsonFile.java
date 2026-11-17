// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.world;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;

/** Small serial single-writer store for the turntable's write-ahead intents. No Bukkit access. */
public final class AtomicJsonFile<T> implements AutoCloseable {
    private final Path path;
    private final Class<T> type;
    private final Gson gson = new Gson();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r,"kolej-turntable-save"); t.setDaemon(true); return t; });
    private volatile IOException failure;
    public AtomicJsonFile(Path path, Class<T> type) { this.path = path; this.type = type; }
    private T decode(Path p) throws IOException {
        try {
            T value = gson.fromJson(Files.readString(p),type);
            if (value == null) throw new IOException("Empty JSON");
            return value;
        } catch (RuntimeException ex) { throw new IOException("Invalid JSON",ex); }
    }
    public T read(T empty) throws IOException {
        if (!Files.exists(path)) return empty;
        try { return decode(path); }
        catch (IOException ex) { return decode(path.resolveSibling(path.getFileName()+".bak")); }
    }
    public Future<?> write(T value) {
        return writer.submit(() -> {
            if (failure != null) throw new java.io.UncheckedIOException(failure);
            try {
                Files.createDirectories(path.getParent());
                Path tmp = path.resolveSibling(path.getFileName()+".tmp");
                byte[] bytes = gson.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (var channel = java.nio.channels.FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                    var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
                }
                boolean valid = false;
                if (Files.exists(path)) try { decode(path); valid = true; } catch (IOException ignored) { }
                if (valid) {
                    Path backup = path.resolveSibling(path.getFileName()+".bak.tmp");
                    Files.copy(path,backup,StandardCopyOption.REPLACE_EXISTING);
                    Files.move(backup,path.resolveSibling(path.getFileName()+".bak"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) { failure = ex; throw new java.io.UncheckedIOException(ex); }
        });
    }
    @Override public void close() { writer.shutdown(); }
}
