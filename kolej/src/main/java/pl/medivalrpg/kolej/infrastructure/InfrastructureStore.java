// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** Async ordered snapshots. Never falls back to an older ledger or overwrites corrupt input. */
public final class InfrastructureStore implements AutoCloseable {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private volatile boolean failed;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Kolej-Infrastructure-IO");t.setDaemon(true);return t;});
    public InfrastructureStore(Path directory){file=directory.resolve("infrastructure.json");}
    public CompletableFuture<InfrastructureState> load(){return CompletableFuture.supplyAsync(()->{
        try {
            if(!Files.exists(file)) {
                if(Files.exists(sibling(".bak")) || Files.exists(sibling(".tmp")))throw new IOException("Brak rejestru, istnieje kopia lub plik tymczasowy — wymagana kontrola.");
                return new InfrastructureState();
            }
            var json=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!json.has("version") || !json.has("fixtures"))throw new IOException("Brak wymaganych pól infrastruktury.");
            var state=GSON.fromJson(json,InfrastructureState.class);state.validate();return state;
        }catch(Exception e){failed=true;throw new CompletionException(e);}
    },writer);}
    private Path sibling(String suffix){return file.resolveSibling(file.getFileName()+suffix);}
    public CompletableFuture<Void> save(InfrastructureState state) {
        state.validate();String snapshot=GSON.toJson(state);
        return CompletableFuture.runAsync(()->{
            if(failed)throw new CompletionException(new IOException("Zapis infrastruktury zablokowany po błędzie."));
            try {
                Files.createDirectories(file.getParent());
                try(var channel=FileChannel.open(sibling(".tmp"),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                    var bytes=StandardCharsets.UTF_8.encode(snapshot);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
                }
                if(Files.exists(file))Files.copy(file,sibling(".bak"),StandardCopyOption.REPLACE_EXISTING);
                Files.move(sibling(".tmp"),file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }catch(Exception e){failed=true;throw new CompletionException(e);}
        },writer);
    }
    @Override public void close(){writer.shutdown();}
}
