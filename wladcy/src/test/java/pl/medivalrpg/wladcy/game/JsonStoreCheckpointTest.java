// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonStoreCheckpointTest {
    @TempDir Path dir;

    @Test void checkpointAndShutdownCannotBeOverwrittenByPreviouslyQueuedSnapshots() throws Exception {
        var store = new JsonStore(this.dir, Logger.getAnonymousLogger());
        try {
            for (int i = 0; i < 20; i++) store.saveAsync("supply.json", Map.of("phase", "old" + i));
            var mutable = new HashMap<String, String>(); mutable.put("phase", "DELIVERED");
            var saved = store.checkpointAsync("supply.json", mutable);
            mutable.put("phase", "uncheckpointed-change"); saved.join();
            assertTrue(Files.readString(this.dir.resolve("supply.json")).contains("DELIVERED"));
            assertFalse(Files.readString(this.dir.resolve("supply.json")).contains("uncheckpointed-change"));
            store.saveAsync("supply.json", Map.of("phase", "queued"));
            store.saveNow("supply.json", Map.of("phase", "PAID"));
        } finally { store.close(); }
        assertTrue(Files.readString(this.dir.resolve("supply.json")).contains("PAID"));
        assertTrue(Files.exists(this.dir.resolve("supply.json.bak")));
    }

    @Test void failedCheckpointPropagatesInsteadOfPretendingDeliveryIsDurable() throws Exception {
        Path file = this.dir.resolve("not-a-directory"); Files.writeString(file, "x");
        var store = new JsonStore(file, Logger.getAnonymousLogger());
        try {
            assertThrows(CompletionException.class, () -> store.checkpointAsync("supply.json", Map.of("phase", "DELIVERED")).join());
        } finally { store.close(); }
    }
}
