// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.freight;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** Serialized worker. No Bukkit calls; snapshots are serialized on the main thread. */
public final class FreightStore implements AutoCloseable {
    static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Kolej-Freight-IO");t.setDaemon(true);return t;});
    public FreightStore(Path directory) {file=directory.resolve("freight.json");}
    public CompletableFuture<FreightState> load() {return CompletableFuture.supplyAsync(()->{
        try {
            if(!Files.exists(file)) {
                if(Files.exists(file.resolveSibling("freight.json.bak")) || Files.exists(file.resolveSibling("freight.json.tmp"))) throw new IOException("Brak ledgeru, istnieją ślady starszego zapisu — ręczna kontrola.");
                return new FreightState();
            }
            JsonObject json=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!json.has("version") || !json.has("warehouses") || !json.has("jobs"))throw new IOException("Brak obowiązkowych pól ledgeru");
            FreightState state=GSON.fromJson(json,FreightState.class);
            if(state==null) throw new IOException("Pusty freight.json");state.validate();return state;
        }catch(Exception e){throw new CompletionException(e);}
    },writer);}
    public CompletableFuture<Void> save(FreightState state) {
        state.validate();String json=GSON.toJson(state);
        return CompletableFuture.runAsync(()->{try{writeAtomic(file,json);}catch(IOException e){throw new CompletionException(e);}},writer);
    }
    static void writeAtomic(Path file,String json) throws IOException {
        Files.createDirectories(file.getParent());Path tmp=file.resolveSibling("freight.json.tmp");
        try(FileChannel channel=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer bytes=StandardCharsets.UTF_8.encode(json);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
        if(Files.exists(file))Files.copy(file,file.resolveSibling("freight.json.bak"),StandardCopyOption.REPLACE_EXISTING);
        // No non-atomic fallback: unsupported filesystem must stop freight.
        Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    @Override public void close() {writer.shutdown();} // no blocking wait on Bukkit main
}
