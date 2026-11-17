// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/** Immutable JSON snapshots are captured on the server thread, then atomically written by one ordered writer. */
public final class FleetStore {
    private final Path dir;
    private final Logger log;
    private final Gson gson=new Gson();
    /** When loading a backup, the damaged primary must never replace that healthy backup. */
    private boolean recoveredBackup;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{final Thread t=new Thread(r,"Armia-flota-zapis");t.setDaemon(true);return t;});
    public FleetStore(final Path dir,final Logger log) {this.dir=dir;this.log=log;}
    public List<ShipRecord> load() {
        final java.lang.reflect.Type type=new TypeToken<List<ShipRecord>>(){}.getType();
        for (final String name:List.of("fleet.json","fleet.json.bak")) {
            final Path path=this.dir.resolve(name);
            if (!Files.exists(path)) continue;
            try {
                final List<ShipRecord> records=this.gson.fromJson(Files.readString(path,StandardCharsets.UTF_8),type);
                if (records==null) throw new IllegalArgumentException("Pusty zapis floty");
                if (records.size()>NavalRules.GLOBAL_MAX) throw new IllegalArgumentException("Przekroczony limit floty");
                final Set<UUID> ids=new HashSet<>();
                final Map<UUID,Integer> countryCounts=new HashMap<>();
                for (final ShipRecord record:records) {
                    if (record==null || !record.valid() || !ids.add(record.id)
                        || countryCounts.merge(record.country,1,Integer::sum)>NavalRules.COUNTRY_MAX)
                        throw new IllegalArgumentException("Nieprawidłowy lub powielony statek w zapisie floty");
                }
                this.recoveredBackup=name.endsWith(".bak");
                return List.copyOf(records);
            } catch (final Exception error) {this.log.log(Level.SEVERE,"Błąd wczytywania "+name+", próbuję kopii",error);}
        }
        if (Files.exists(this.dir.resolve("fleet.json")) || Files.exists(this.dir.resolve("fleet.json.bak")))
            throw new IllegalStateException("Nie można wczytać floty; zachowano pliki do odzyskania.");
        return List.of();
    }
    public void saveAsync(final Collection<ShipRecord> records) {
        final String snapshot=this.gson.toJson(records);
        if (!this.writer.isShutdown()) this.writer.execute(()->this.write(snapshot));
    }
    private void write(final String json) {
        try {
            Files.createDirectories(this.dir);
            final Path file=this.dir.resolve("fleet.json"),tmp=this.dir.resolve("fleet.json.tmp");
            Files.writeString(tmp,json,StandardCharsets.UTF_8);
            if(Files.exists(file)) {
                if(this.recoveredBackup) Files.move(file,this.dir.resolve("fleet.json.broken-"+UUID.randomUUID()));
                else Files.copy(file,this.dir.resolve("fleet.json.bak"),StandardCopyOption.REPLACE_EXISTING);
            }
            try {Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(final AtomicMoveNotSupportedException e) {Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
            this.recoveredBackup=false;
        } catch(final Exception e) {this.log.log(Level.SEVERE,"Nie można zapisać floty",e);}
    }
    public void close() {
        this.writer.shutdown();
        try {if(!this.writer.awaitTermination(10,TimeUnit.SECONDS)) this.log.severe("Zapis floty nadal trwa.");}
        catch(final InterruptedException e) {Thread.currentThread().interrupt();}
    }
}
