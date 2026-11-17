// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Debug log of messages from the Worker: a password never reaches the server log, whatever the case of {@code t}. */
class WorkerConnectionDebugTest {

    @Test
    void authIsHiddenInAnyLetterCase() {
        for (final String t : new String[] {"auth", "AUTH", "Auth", " auth "}) {
            final String text = "{\"t\":\"" + t + "\",\"id\":\"1\",\"name\":\"Gracz\",\"password\":\"tajne-haslo\",\"ip\":\"1.2.3.4\"}";
            final String logged = WorkerConnection.debugIncoming(t, text);
            assertFalse(logged.contains("tajne-haslo"), t);
            assertEquals("auth (hasło ukryte)", logged, t);
        }
    }

    @Test
    void otherMessagesAreShortened() {
        final String rpc = "{\"t\":\"rpc\",\"id\":\"7\",\"method\":\"me\"}";
        assertEquals(rpc, WorkerConnection.debugIncoming("rpc", rpc));
        final String big = "x".repeat(1000);
        final String logged = WorkerConnection.debugIncoming("rpc", big);
        assertEquals(303, logged.length());
        assertTrue(logged.endsWith("..."));
    }

    @Test
    void pluginUriUsesTheWebSocketScheme() {
        assertEquals("wss://panel.example/plugin", WorkerConnection.pluginUri("https://panel.example/").toString());
        assertEquals("ws://localhost:8787/plugin", WorkerConnection.pluginUri("http://localhost:8787").toString());
        assertEquals("wss://panel.example/plugin", WorkerConnection.pluginUri("panel.example").toString());
    }
}
