// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NetworkPauseTest {
    @TempDir Path folder;
    @Test void pauseSurvivesRestartAndResumeAuthorizesOnlyTheExactRequest() throws Exception {
        String sha="a".repeat(64);
        var control=new NetworkPause(folder);
        assertFalse(control.paused());
        assertFalse(NetworkCommission.startReady(folder.resolve("start.ready"),sha));
        control.pause();control.pause();
        var restarted=new NetworkPause(folder);
        assertTrue(restarted.paused());
        restarted.resume(sha);
        assertFalse(restarted.paused());assertFalse(new NetworkPause(folder).paused());
        assertTrue(NetworkCommission.startReady(folder.resolve("start.ready"),sha));
        assertFalse(NetworkCommission.startReady(folder.resolve("start.ready"),"b".repeat(64)));
        restarted.pause();assertTrue(NetworkCommission.startReady(folder.resolve("start.ready"),sha));
        assertTrue(new NetworkPause(folder).paused(),"pause wins over a valid start marker");
    }
    @Test void interruptedPauseWriteAndInvalidResumeFailClosed() throws Exception {
        Files.writeString(folder.resolve("paused.tmp"),"");
        var control=new NetworkPause(folder);assertTrue(control.paused());
        assertThrows(java.io.IOException.class,()->control.resume(null));assertTrue(control.paused());
        control.resume("a".repeat(64));assertFalse(new NetworkPause(folder).paused());
    }
    @Test void failedPauseWriteStopsCurrentProcessAndNeverClaimsDurability() throws Exception {
        Path blocked=folder.resolve("file");Files.writeString(blocked,"not a directory");
        var control=new NetworkPause(blocked);
        assertThrows(java.io.IOException.class,control::pause);assertTrue(control.paused());
    }
    @Test void resumeDoesNotNeedToMoveHistoricalReadyMarker() throws Exception {
        Files.writeString(folder.resolve("start.ready.before-batch2"),"old");
        var control=new NetworkPause(folder);control.resume("c".repeat(64));
        assertEquals("old",Files.readString(folder.resolve("start.ready.before-batch2")));
        assertTrue(NetworkCommission.startReady(folder.resolve("start.ready"),"c".repeat(64)));
    }
}
