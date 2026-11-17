// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class RailDataTest {
    @TempDir Path dir;
    @Test void paidCrewAndDriverAssignmentsSurviveRestart() throws Exception {
        RailData d=new RailData();UUID id=UUID.randomUUID();d.drivers.put(id,"p1");
        RailData.Crew c=new RailData.Crew();c.id="m1";c.name="Jan";c.paid=true;c.train="p1";d.crews.put(c.id,c);
        RailData.Train t=new RailData.Train();t.id="p1";t.node="s1";t.cars.add(RailData.Car.PASAZERSKI);t.steam.pressure=5;d.trains.put(t.id,t);
        try(RailStore store=new RailStore(dir,Logger.getAnonymousLogger())){store.save(d);}
        try(RailStore store=new RailStore(dir,Logger.getAnonymousLogger())){RailData loaded=store.load();assertEquals("p1",loaded.drivers.get(id));assertTrue(loaded.crews.get("m1").paid);assertEquals(8,loaded.trains.get("p1").seats());assertEquals(5,loaded.trains.get("p1").steam.pressure);}
    }
    @Test void malformedDataIsNeverSilentlyReplaced() throws Exception {
        Files.writeString(dir.resolve("kolej.json"),"{");
        try(RailStore store=new RailStore(dir,Logger.getAnonymousLogger())){assertThrows(java.io.IOException.class,store::load);}
        assertEquals("{",Files.readString(dir.resolve("kolej.json")));
    }
    @Test void customIdsCannotBePathsOrCommands(){assertFalse(RailData.identifier("../z"));assertFalse(RailData.identifier("a b"));assertTrue(RailData.identifier("Krolewski_1"));}
}
