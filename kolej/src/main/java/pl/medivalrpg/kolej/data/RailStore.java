// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.data;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** One ordered writer; snapshots are serialized by the caller on the server thread. */
public final class RailStore implements AutoCloseable {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Logger log;
    private volatile Throwable failure;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Kolej-IO");t.setDaemon(true);return t;});
    public RailStore(Path directory, Logger log) {this.file=directory.resolve("kolej.json");this.log=log;}
    public RailData load() throws java.io.IOException {
        if(!Files.exists(file)) {
            if(Files.exists(file.resolveSibling("kolej.json.bak")) || Files.exists(file.resolveSibling("kolej.json.tmp")))throw new java.io.IOException("Brak kolej.json, istnieje kopia lub plik tymczasowy — wymagana kontrola, bez zerowania danych.");
            return new RailData();
        }
        try {RailData d=gson.fromJson(Files.readString(file),RailData.class);if(d==null)throw new IllegalStateException("Puste dane");d.graph();return d;}
        catch(RuntimeException e){throw new java.io.IOException("Nieprawidłowe kolej.json — zachowano plik i kopię",e);}
    }
    public CompletableFuture<Void> save(RailData data) {
        String json=gson.toJson(data);
        return CompletableFuture.runAsync(()->{try {
            if(failure!=null)throw new java.io.IOException("Poprzedni zapis kolei nie powiódł się",failure);
            Files.createDirectories(file.getParent());Path tmp=file.resolveSibling("kolej.json.tmp");
            try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                var bytes=StandardCharsets.UTF_8.encode(json);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
            }
            if(Files.exists(file))Files.copy(file,file.resolveSibling("kolej.json.bak"),StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception e){failure=e;log.severe("Zapis kolei: "+e.getMessage());throw new CompletionException(e);}},io);
    }
    public void close() {io.shutdown();try {if(!io.awaitTermination(10,TimeUnit.SECONDS))log.warning("Zapis kolei nadal trwa");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
