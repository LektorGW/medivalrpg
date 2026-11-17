// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorksStoreTest {
    @TempDir Path dir;
    @Test void heldEntriesAreDurableBeforeFinancialStateMayAdvance() throws Exception {
        var intent=ConstructionTransactionTest.intent();var plan=intent.plan();
        var job=new RailwayWorks.Job(intent.id(),plan.world(),plan.country(),plan.entries(),0,plan.preview().cost(),"HELD","bank pending",plan.reused(),true);
        WorksStore store=new WorksStore(dir.resolve("jobs.json"));
        try {
            store.save(new RailwayWorks.Snapshot(1,List.of(job))).get(5,TimeUnit.SECONDS);
            assertEquals(job,store.read().jobs().getFirst());
            assertTrue(ConstructionTransaction.canRecoverPaid(intent.phase("PAID",""),Optional.of(store.read().jobs().getFirst())));
            assertFalse(ConstructionTransaction.canRecoverPaid(intent.phase("DEBITTING",""),Optional.of(job)));
        }finally{store.shutdown();}
    }
    @Test void staleBackupMissingPaidJobCannotAuthorizeRecoveryOrAnotherDebit() throws Exception {
        var intent=ConstructionTransactionTest.intent();var plan=intent.plan();Path file=dir.resolve("jobs.json");
        var job=new RailwayWorks.Job(intent.id(),plan.world(),plan.country(),plan.entries(),0,plan.preview().cost(),"HELD","",List.of(),true);
        WorksStore store=new WorksStore(file);
        try {
            store.save(new RailwayWorks.Snapshot(1,List.of())).get(5,TimeUnit.SECONDS);
            store.save(new RailwayWorks.Snapshot(1,List.of(job))).get(5,TimeUnit.SECONDS);
            Files.writeString(file,"broken-current");
            assertTrue(store.read().jobs().isEmpty());
            assertFalse(ConstructionTransaction.canRecoverPaid(intent.phase("PAID",""),store.read().jobs().stream().findFirst()));
        }finally{store.shutdown();}
    }
    @Test void completingBlockEntriesStillRequiresPhysicalVerificationOfTheEntireRoute() {
        var intent=ConstructionTransactionTest.intent();var plan=intent.plan();
        var cell=new pl.medivalrpg.kolej.network.TrackCell(12,71,15,pl.medivalrpg.kolej.network.CellKind.values()[0]);
        var completedEntries=new RailwayWorks.Job(intent.id(),plan.world(),plan.country(),plan.entries(),plan.entries().size(),plan.preview().cost(),"QUEUED","",List.of(),true,List.of(cell));
        assertEquals("VERIFYING",RailwayWorks.completedStatus(completedEntries));
        assertEquals(List.of(cell),RailwayWorks.afterRestart(completedEntries).routeCells());
    }
    @Test void optionalSaplingSupportSurvivesJobPersistenceAndLegacyEntriesKeepNull() throws Exception {
        var op=BlockOp.raw(10,71,20,"minecraft:oak_sapling[stage=0]",BlockOp.Mode.PLACE);
        var plant=new RailwayWorks.Entry(op,"minecraft:air",op.data(),"minecraft:grass_block[snowy=false]");
        var ordinary=new RailwayWorks.Entry(BlockOp.clear(1,70,1),"minecraft:dirt","minecraft:air");assertNull(ordinary.supportBefore());
        var job=new RailwayWorks.Job("replant",UUID.randomUUID(),UUID.randomUUID(),List.of(plant,ordinary),0,2,"HELD","",List.of(),true);
        WorksStore store=new WorksStore(dir.resolve("jobs.json"));
        try{store.save(new RailwayWorks.Snapshot(1,List.of(job))).get(5,TimeUnit.SECONDS);assertEquals(job,store.read().jobs().getFirst());}
        finally{store.shutdown();}
        String legacy="{\"op\":{\"x\":0,\"y\":70,\"z\":0,\"part\":\"AIR\",\"mode\":\"CLEAR\"},\"before\":\"minecraft:dirt\",\"target\":\"minecraft:air\"}";
        assertNull(new com.google.gson.Gson().fromJson(legacy,RailwayWorks.Entry.class).supportBefore());
    }
}
