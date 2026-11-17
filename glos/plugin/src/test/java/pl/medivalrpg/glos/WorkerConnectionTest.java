// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerConnectionTest {

    @Test
    void debugTextMasksLinkTokens() {
        String token = "AbCdEfGhIjKlMnOpQrStUvWxYz012345";
        String json = new Gson().toJson(new VoiceManager.TokenMsg("token", token,
                "123e4567-e89b-12d3-a456-426614174000", "Gracz"));
        String out = WorkerConnection.debugText(json);
        assertFalse(out.contains(token), out);
        assertTrue(out.contains("\"token\":\"***\""), out);
        assertTrue(out.contains("\"n\":\"Gracz\""), "reszta wiadomości zostaje");
        assertEquals("{\"token\" : \"***\"}", WorkerConnection.debugText("{\"token\" : \"x\"}"));
    }

    @Test
    void debugTextLeavesOtherMessagesAndTruncates() {
        assertEquals("{\"t\":\"hello\",\"v\":1}", WorkerConnection.debugText("{\"t\":\"hello\",\"v\":1}"));
        String longMsg = "x".repeat(400);
        assertEquals("x".repeat(300) + "...", WorkerConnection.debugText(longMsg));
    }

    @Test
    void pluginUriFromWorkerUrl() {
        assertEquals("wss://glos.example.dev/plugin", WorkerConnection.pluginUri("https://glos.example.dev/").toString());
        assertEquals("ws://127.0.0.1:8787/plugin", WorkerConnection.pluginUri("http://127.0.0.1:8787").toString());
        assertEquals("wss://glos.example.dev/plugin", WorkerConnection.pluginUri("glos.example.dev").toString());
    }
}
