// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.logic.SupplyLedger.Proof;

class SupplyDiskProofTest {
    static void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+2_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    @Test void delayedRegionIoStaysPendingUntilTheActualMarkerAppears() throws Exception {
        var now=new AtomicLong(); var written=new AtomicBoolean(); var calls=new AtomicInteger();
        BooleanSupplier probe=() -> { calls.incrementAndGet(); return written.get(); };
        try(var proof=new SupplyDiskProof(now::get)) {
            assertEquals(Proof.UNAVAILABLE,proof.poll("chest",30_000,probe)); await(() -> calls.get()==1);
            assertEquals(Proof.UNAVAILABLE,proof.poll("chest",30_000,probe));
            written.set(true); now.set(1001);
            await(() -> proof.poll("chest",30_000,probe)==Proof.AFTER);
            assertEquals(2,calls.get());
        }
    }
    @Test void swallowedPlayerSaveFailureTimesOutAndNeverReturnsSuccess() throws Exception {
        var now=new AtomicLong(); var called=new AtomicBoolean();
        try(var proof=new SupplyDiskProof(now::get)) {
            BooleanSupplier probe=() -> { called.set(true); return false; };
            assertEquals(Proof.UNAVAILABLE,proof.poll("player",5000,probe)); await(called::get);
            now.set(5001); await(() -> proof.poll("player",5000,probe)==Proof.MISMATCH);
        }
    }
    @Test void proofReadersRunOffTheCallingThreadAndExceptionsAreNotReceipts() throws Exception {
        var now=new AtomicLong(); Thread main=Thread.currentThread(); var async=new AtomicBoolean();
        try(var proof=new SupplyDiskProof(now::get)) {
            BooleanSupplier probe=() -> { async.set(Thread.currentThread()!=main); throw new IllegalStateException("IO"); };
            assertEquals(Proof.UNAVAILABLE,proof.poll("source",5000,probe)); await(async::get); now.set(5001);
            await(() -> proof.poll("source",5000,probe)==Proof.MISMATCH);
        }
    }
}
