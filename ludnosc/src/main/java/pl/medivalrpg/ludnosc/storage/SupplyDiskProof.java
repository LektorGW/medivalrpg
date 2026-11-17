// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import pl.medivalrpg.ludnosc.logic.SupplyLedger.Proof;

/** Coalesced async read-only polling. A successful vanilla save CALL is not considered durable proof. */
public final class SupplyDiskProof implements AutoCloseable {
    private static final class Check { long started, next; boolean verified,expired; CompletableFuture<Boolean> read; }
    private final Map<String,Check> checks=new LinkedHashMap<>();
    private final ExecutorService reader=Executors.newSingleThreadExecutor(r -> { var t=new Thread(r,"Ludnosc-supply-disk-proof"); t.setDaemon(true); return t; });
    private final LongSupplier clock;
    public SupplyDiskProof() { this(System::currentTimeMillis); }
    public SupplyDiskProof(LongSupplier clock) { this.clock=clock; }
    public Proof poll(String key,long timeout,BooleanSupplier probe) {
        long now=clock.getAsLong();
        Check c=checks.get(key);
        if(c==null) {
            if(checks.size()>=3072) return Proof.MISMATCH;
            c=new Check(); c.started=now; checks.put(key,c);
        }
        if(c.verified) return Proof.AFTER;
        if(c.expired || now-c.started>=timeout) { c.expired=true; return Proof.MISMATCH; }
        if(c.read!=null && c.read.isDone()) {
            if(!c.read.isCompletedExceptionally() && c.read.join()) { c.verified=true; return Proof.AFTER; }
            if(now<c.next) return Proof.UNAVAILABLE;
        }
        if(c.read==null || c.read.isDone()) {
            c.next=now+1000;
            c.read=CompletableFuture.supplyAsync(() -> { try { return probe.getAsBoolean(); } catch(RuntimeException ex) { return false; } },reader);
        }
        return Proof.UNAVAILABLE;
    }
    public static boolean player(Path file,String receipt) {
        try { return receipt.equals(SupplyNbtReader.player(file)); } catch(Exception ex) { return false; }
    }
    public static boolean block(Path region,int x,int y,int z,String receipt) {
        try { return receipt.equals(SupplyNbtReader.block(region,x,y,z)); } catch(Exception ex) { return false; }
    }
    @Override public void close() { reader.shutdown(); }
}
