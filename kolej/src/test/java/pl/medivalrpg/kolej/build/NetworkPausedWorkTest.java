// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NetworkPausedWorkTest {
    @TempDir Path folder;
    @Test void pauseDuringPartKeepsCursorAndLegacyFormatAndResumeRequiresPaymentProof() throws Exception {
        var entries=List.of(new RailwayWorks.Entry(BlockOp.place(0,64,0,BlockOp.Part.BED),"minecraft:dirt","minecraft:gravel"),
            new RailwayWorks.Entry(BlockOp.place(1,64,0,BlockOp.Part.BED),"minecraft:dirt","minecraft:gravel"));
        var job=new RailwayWorks.Job("part1",UUID.randomUUID(),UUID.randomUUID(),entries,1,2,"QUEUED","",List.of(),true);
        Predicate<String> paused=id->id.equals("part1");Set<String> paid=Set.of("part1");
        assertTrue(RailwayWorks.executable(job,paid,id->false));
        assertFalse(RailwayWorks.executable(job,paid,paused));
        var snapshot=new RailwayWorks.Snapshot(1,List.of(job));Path file=folder.resolve("jobs.json");
        var store=new WorksStore(file);
        try {store.read();store.save(snapshot).get(10,TimeUnit.SECONDS);}finally{store.shutdown();}
        var legacy=new LegacyWorksStore(file);
        RailwayWorks.Job restored;
        try {assertEquals(snapshot,legacy.read());restored=RailwayWorks.afterRestart(legacy.read().jobs().getFirst());}finally{legacy.shutdown();}
        assertEquals(1,restored.cursor());assertEquals("HELD",restored.status());
        assertFalse(RailwayWorks.executable(restored,Set.of(),paused));
        assertFalse(RailwayWorks.executable(restored,Set.of(),id->false),"resume cannot bypass paid recovery");
        assertFalse(RailwayWorks.executable(job,Set.of(),id->false));
        assertTrue(RailwayWorks.executable(job,paid,id->false));
        assertEquals(1,job.cursor());assertEquals("QUEUED",job.status());
    }
    @Test void networkPauseDoesNotBlockAnUnrelatedManualJob() {
        var job=new RailwayWorks.Job("manual",UUID.randomUUID(),UUID.randomUUID(),List.of(),0,0,"QUEUED","");
        assertTrue(RailwayWorks.executable(job,Set.of(),id->id.equals("part1")));
    }
}
