// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleStoreTest {

    @Test
    void knightTitlesSurviveARestartAndCanBeHidden(@TempDir File dir) {
        UUID knight = UUID.randomUUID();
        PlayerDataStore a = new PlayerDataStore(dir, Logger.getLogger("t"));
        a.load();
        assertNull(a.title(knight));
        a.setTitle(knight, new PlayerDataStore.Title("Pani", "Krolowa", 123L, false));
        a.close();

        PlayerDataStore b = new PlayerDataStore(dir, Logger.getLogger("t"));
        b.load();
        assertEquals("Pani", b.shownTitle(knight));
        assertEquals("Krolowa", b.title(knight).byName());
        b.setTitle(knight, new PlayerDataStore.Title("Pani", "Krolowa", 123L, true));
        assertNull(b.shownTitle(knight), "hidden titles are not shown");
        assertTrue(b.title(knight).hidden());
        b.close();

        PlayerDataStore c = new PlayerDataStore(dir, Logger.getLogger("t"));
        c.load();
        assertTrue(c.title(knight).hidden());
        c.close();
    }
}
