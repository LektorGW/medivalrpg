// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.panel.audit.AuditLog;
import pl.medivalrpg.panel.net.Protocol;
import pl.medivalrpg.panel.util.Json;

/** Messages to the Durable Object and RPC envelopes (PANEL.md protocol). */
class SerializationTest {

    private static JsonObject json(final java.util.Map<String, Object> m) {
        return JsonParser.parseString(Protocol.toJson(m)).getAsJsonObject();
    }

    @Test
    void rpcResultOkKeepsNullsAndOrder() {
        final String text = Protocol.toJson(Protocol.rpcOk("r1", Json.obj("uuid", "u", "admin", false, "balance", 1234L, "country", null,
            "roles", Json.obj("admin", false, "npcManager", true, "managedNpcCountries", List.of("a", "b")))));
        assertEquals("{\"t\":\"rpc-result\",\"id\":\"r1\",\"ok\":true,\"data\":{\"uuid\":\"u\",\"admin\":false,\"balance\":1234,"
            + "\"country\":null,\"roles\":{\"admin\":false,\"npcManager\":true,\"managedNpcCountries\":[\"a\",\"b\"]}}}", text);
    }

    @Test
    void rpcResultFailure() {
        final JsonObject o = json(Protocol.rpcFail("r2", "Brak uprawnień do tej operacji."));
        assertEquals("rpc-result", o.get("t").getAsString());
        assertEquals("r2", o.get("id").getAsString());
        assertFalse(o.get("ok").getAsBoolean());
        assertEquals("Brak uprawnień do tej operacji.", o.get("error").getAsString());
        assertFalse(o.has("data"));
    }

    @Test
    void noHtmlEscapingAndPolishCharacters() {
        final String text = Protocol.toJson(Protocol.rpcOk("x", Json.obj("description", "<b>Żółć</b> & \"cytat\"")));
        assertTrue(text.contains("\"<b>Żółć</b> & \\\"cytat\\\"\""), text);
    }

    @Test
    void authAndLinkMessages() {
        final UUID u = UUID.randomUUID();
        final JsonObject ok = json(Protocol.authOk("a1", u, "Gracz"));
        assertEquals("auth-result", ok.get("t").getAsString());
        assertTrue(ok.get("ok").getAsBoolean());
        assertEquals(u.toString(), ok.get("uuid").getAsString());
        assertEquals("Gracz", ok.get("name").getAsString());
        final JsonObject fail = json(Protocol.authFail("a2", "Nieprawidłowy nick lub hasło."));
        assertFalse(fail.get("ok").getAsBoolean());
        assertFalse(fail.has("uuid"));
        final JsonObject link = json(Protocol.linkToken("T".repeat(32), u, "Gracz"));
        assertEquals("link-token", link.get("t").getAsString());
        assertEquals(32, link.get("token").getAsString().length());
        assertEquals(u.toString(), link.get("uuid").getAsString());
        assertEquals("revoke", json(Protocol.revoke(u)).get("t").getAsString());
        assertEquals(1, json(Protocol.hello()).get("v").getAsInt());
    }

    @Test
    void rpcEnvelopeParsing() {
        final UUID u = UUID.randomUUID();
        final RpcService.Request r = RpcService.parse(JsonParser.parseString(
            "{\"t\":\"rpc\",\"id\":\"9\",\"user\":{\"uuid\":\"" + u + "\",\"name\":\"Gracz\",\"ip\":\"1.2.3.4\"},\"method\":\"me\",\"params\":{}}").getAsJsonObject());
        assertEquals("9", r.id());
        assertEquals(u, r.uuid());
        assertEquals("Gracz", r.name());
        assertEquals("1.2.3.4", r.ip());
        assertEquals("me", r.method());
        // ip at top level, weird name
        final RpcService.Request r2 = RpcService.parse(JsonParser.parseString(
            "{\"id\":\"9\",\"ip\":\"5.6.7.8\",\"user\":{\"uuid\":\"" + u + "\",\"name\":\"<script>\"},\"method\":\"me\"}").getAsJsonObject());
        assertEquals("5.6.7.8", r2.ip());
        assertEquals("?", r2.name());
        assertThrows(RpcException.class, () -> RpcService.parse(JsonParser.parseString("{\"id\":\"1\",\"method\":\"me\"}").getAsJsonObject()));
        assertThrows(RpcException.class, () -> RpcService.parse(JsonParser.parseString(
            "{\"id\":\"1\",\"user\":{\"uuid\":\"zly\"},\"method\":\"me\"}").getAsJsonObject()));
        assertThrows(RpcException.class, () -> RpcService.parse(JsonParser.parseString(
            "{\"user\":{\"uuid\":\"" + u + "\"},\"method\":\"me\"}").getAsJsonObject()));
    }

    @Test
    void apiFailuresBecomePolishErrors() {
        final RpcException e = assertThrows(RpcException.class,
            () -> PanelMethods.check(KrajeResult.fail(KrajeResult.Status.INSUFFICIENT_FUNDS, "")));
        assertEquals("Za mało pieniędzy.", e.getMessage());
        final RpcException e2 = assertThrows(RpcException.class,
            () -> PanelMethods.check(KrajeResult.fail(KrajeResult.Status.DENIED, "Nie możesz wyrzucić władcy.")));
        assertEquals("Nie możesz wyrzucić władcy.", e2.getMessage());
        assertTrue(PanelMethods.check(KrajeResult.ok(UUID.randomUUID())).ok());
    }

    @Test
    void auditLineRoundTrip() {
        final AuditLog.Entry e = new AuditLog.Entry(123L, "u", "Gracz", "1.2.3.4", "country.setTax", "{\"amount\":5}", true, null);
        final String line = Json.GSON.toJson(e.toJson());
        assertTrue(line.contains("\"error\":null"));
        final AuditLog.Entry back = AuditLog.parse(line);
        assertEquals(e, back);
        assertEquals(null, AuditLog.parse("nie json"));
        assertTrue(AuditLog.truncate("x".repeat(1000)).length() < 700);
    }
}
