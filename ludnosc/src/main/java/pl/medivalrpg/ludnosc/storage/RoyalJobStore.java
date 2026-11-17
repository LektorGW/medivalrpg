// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** A single ordered journal writer. Destructive work waits for the initial/phase checkpoint to finish. */
public final class RoyalJobStore implements AutoCloseable {
    private final Path file;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Ludnosc-royal-journal");t.setDaemon(true);return t;});
    public RoyalJobStore(Path directory){this.file=directory.resolve("state.json");}
    public String load() throws IOException {
        if(!Files.exists(this.file))return Files.exists(this.file.resolveSibling("state.json.bak"))?this.backup():null;
        try{return Files.readString(this.file,StandardCharsets.UTF_8);}
        catch(IOException ex){return Files.readString(this.file.resolveSibling("state.json.bak"),StandardCharsets.UTF_8);}
    }
    public String backup() throws IOException{return Files.readString(this.file.resolveSibling("state.json.bak"),StandardCharsets.UTF_8);}
    public CompletableFuture<Void> save(String json) {
        return CompletableFuture.runAsync(()->{
            try {
                Files.createDirectories(this.file.getParent());
                final Path tmp=this.file.resolveSibling("state.json.tmp");Files.writeString(tmp,json,StandardCharsets.UTF_8);
                try(final var channel=java.nio.channels.FileChannel.open(tmp,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
                if(Files.exists(this.file))Files.copy(this.file,this.file.resolveSibling("state.json.bak"),StandardCopyOption.REPLACE_EXISTING);
                try{Files.move(tmp,this.file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(java.nio.file.AtomicMoveNotSupportedException ex){Files.move(tmp,this.file,StandardCopyOption.REPLACE_EXISTING);}
                if(!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win"))try(final var channel=java.nio.channels.FileChannel.open(this.file.getParent(),java.nio.file.StandardOpenOption.READ)){channel.force(true);}
            } catch(IOException ex){throw new java.io.UncheckedIOException(ex);}
        },this.writer);
    }
    public void close(){this.writer.shutdown();try{this.writer.awaitTermination(10,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}}
}
