// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.network.*;

/** Write-ahead financial evidence. Never fall back to an older financial state or replay a debit. */
public final class ConstructionJournal implements AutoCloseable {
    public record GraphIntent(TrackNode node,String from,String to,List<TrackCell> cells,RailData.Depot depot) {
        public GraphIntent { cells=List.copyOf(cells); }
    }
    public record Intent(String id,UUID actor,long price,RailwayWorks.PreparedPlan plan,GraphIntent graph,String phase,String reason,String debitAccount) {
        public Intent { Objects.requireNonNull(id);Objects.requireNonNull(actor);Objects.requireNonNull(plan);Objects.requireNonNull(graph);Objects.requireNonNull(phase);Objects.requireNonNull(reason);if(price<0)throw new IllegalArgumentException("Negative price");debitAccount=debitAccount==null?RailData.ACCOUNT:debitAccount;if(!debitAccount.equals(RailData.ACCOUNT) && !debitAccount.matches("kolej:budzet_[a-f0-9]{16}"))throw new IllegalArgumentException("Invalid funding account"); }
        public Intent(String id,UUID actor,long price,RailwayWorks.PreparedPlan plan,GraphIntent graph,String phase,String reason){this(id,actor,price,plan,graph,phase,reason,RailData.ACCOUNT);}
        public Intent phase(String phase,String reason){return new Intent(id,actor,price,plan,graph,phase,reason,debitAccount);}
    }
    public record Snapshot(int version,List<Intent> intents) { public Snapshot {intents=List.copyOf(intents);} }
    private final Path file;
    private final Map<String,Intent> intents=new LinkedHashMap<>();
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Kolej-Transakcje-IO");t.setDaemon(true);return t;});
    private volatile IOException failure;
    public ConstructionJournal(Path file) throws IOException {
        this.file=file;
        try {
            if(!Files.exists(file)) {
                if(Files.exists(sibling(".bak")) || Files.exists(sibling(".tmp")))throw new IOException("Brak głównego dziennika transakcji; wymagana kontrola kopii.");
                return;
            }
            Snapshot saved=new Gson().fromJson(Files.readString(file),Snapshot.class);
            if(saved==null || saved.version!=1)throw new IOException("Nieprawidłowa wersja transakcji");
            for(Intent intent:saved.intents) {
                if(intent==null || intent.id.isBlank() || intents.putIfAbsent(intent.id,intent)!=null)throw new IOException("Nieprawidłowe id transakcji");
                if(intent.plan.world()==null || intent.plan.country()==null || !intent.plan.preview().allowed())throw new IOException("Nieprawidłowy plan transakcji");
            }
        }catch(Exception ex){io.shutdown();if(ex instanceof IOException e)throw e;throw new IOException("Uszkodzony dziennik transakcji; zachowano pliki.",ex);}
    }
    private Path sibling(String suffix){return file.resolveSibling(file.getFileName()+suffix);}
    public Collection<Intent> intents(){return List.copyOf(intents.values());}
    public boolean contains(String id){return intents.containsKey(id);}
    public Future<?> checkpoint(Intent intent) {
        return checkpoint(intent,false);
    }
    /** Only the live state machine, before calling debit, may retire its own DEBITTING marker. */
    public Future<?> checkpointUnpaidPause(Intent intent) {
        Intent previous=intents.get(intent.id);
        if(previous==null || !intent.phase.equals("INTENT") || !Set.of("INTENT","DEBITTING").contains(previous.phase))throw new IllegalArgumentException("Brak dowodu transakcji przed płatnością.");
        return checkpoint(intent,true);
    }
    private Future<?> checkpoint(Intent intent,boolean unpaidPause) {
        Intent previous=intents.get(intent.id);
        if(previous!=null && (previous.price!=intent.price || !previous.debitAccount.equals(intent.debitAccount) || !previous.actor.equals(intent.actor) || !previous.plan.equals(intent.plan) || !previous.graph.equals(intent.graph)))throw new IllegalArgumentException("Nie można zmienić zapisanego zamiaru transakcji.");
        if(previous!=null && Set.of("RECONCILE","DECLINED").contains(previous.phase) && !previous.phase.equals(intent.phase))throw new IllegalArgumentException("Zamknięta transakcja wymaga kontroli.");
        if(previous!=null && !previous.phase.equals("INTENT") && intent.phase.equals("INTENT") && !unpaidPause)throw new IllegalArgumentException("Nie wolno ponowić transakcji.");
        intents.put(intent.id,intent);
        Snapshot snapshot=new Snapshot(1,List.copyOf(intents.values()));
        return io.submit(()->{
            if(failure!=null)throw new java.io.UncheckedIOException(failure);
            try {
                Files.createDirectories(file.getParent());
                byte[] bytes=new Gson().toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try(FileChannel channel=FileChannel.open(sibling(".tmp"),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                    ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
                }
                if(Files.exists(file))Files.copy(file,sibling(".bak"),StandardCopyOption.REPLACE_EXISTING);
                Files.move(sibling(".tmp"),file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                // Directory fsync makes the rename durable on the Linux production filesystem.
                if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))
                    try(FileChannel directory=FileChannel.open(file.getParent(),StandardOpenOption.READ)){directory.force(true);}
            }catch(IOException ex){failure=ex;throw new java.io.UncheckedIOException(ex);}
        });
    }
    @Override public void close(){io.shutdown();}
}
