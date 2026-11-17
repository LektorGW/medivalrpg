// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.szlaki;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** A single IO worker, ordered writes, forced temporary file, atomic replacement, previous snapshot backup.
 * A main-thread barrier waits for durable intent before changing a Bukkit inventory. No Bukkit calls on IO worker. */
final class StateStore implements AutoCloseable {
    private final Path path;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final ExecutorService io=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("Szlaki-IO").factory());
    StateStore(Path directory) { path=directory.resolve("state.json"); }
    Model.State load() throws IOException {
        if(!Files.exists(path)) return new Model.State();
        try {
            String json=Files.readString(path);
            var root=JsonParser.parseString(json).getAsJsonObject();
            if(!root.has("schema") || !root.has("routes") || !root.has("jobs")) throw new IOException("Niepełny dziennik Szlaków.");
            Model.State s=gson.fromJson(json,Model.State.class);
            if(s==null || s.routes==null || s.jobs==null) throw new IOException("Puste dane Szlaków.");
            for(var entry:s.routes.entrySet()) {
                if(entry.getValue()==null || !entry.getKey().equals(entry.getValue().id)) throw new IOException("Niepoprawny indeks tras.");
            }
            for(var entry:s.jobs.entrySet()) {
                Model.Job j=entry.getValue();
                if(j==null || !entry.getKey().equals(j.id) || j.phase==null || !s.routes.containsKey(j.route)) throw new IOException("Niepoprawny indeks przewozów.");
                if(j.active()) {
                    s.routes.get(j.route).validate();
                    if(j.position==null || !Double.isFinite(j.position.x()+j.position.y()+j.position.z()) || j.node<1 || j.node>s.routes.get(j.route).points.size() || j.hp<0 || j.hp>100) throw new IOException("Niepoprawny stan konwoju.");
                }
            }
            s.recover(); return s;
        } catch(RuntimeException e) { throw new IOException("Uszkodzony state.json; nie przywracaj .bak bez audytu skrzyń!",e); }
    }
    void save(Model.State state) {
        String json=gson.toJson(state); // snapshot on main thread
        try { io.submit(() -> {
            try {
                Files.createDirectories(path.getParent());
                Path tmp=path.resolveSibling("state.json.tmp");
                Files.writeString(tmp,json,StandardCharsets.UTF_8);
                try(FileChannel channel=FileChannel.open(tmp,StandardOpenOption.WRITE)) { channel.force(true); }
                if(Files.exists(path)) Files.copy(path,path.resolveSibling("state.json.bak"),StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } catch(IOException e) { throw new UncheckedIOException(e); }
        }).get(); } catch(Exception e) { throw new IllegalStateException("Nie zapisano dziennika Szlaków; zatrzymano operacje.",e); }
    }
    @Override public void close() { io.shutdown(); }
}
