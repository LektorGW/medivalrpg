// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.panel.access.Access;
import pl.medivalrpg.panel.access.Actor;
import pl.medivalrpg.panel.access.Requirement;

/** {@code firmy.*}: registration, parameter whitelist and the Firmy bridge (Firmy decides access itself). */
class FirmyMethodsTest {

    static final UUID OWNER = UUID.fromString("ff923cf7-f43c-37b8-85a3-77cbac357c9c");
    static final List<String> ALL = List.of("firmy.list", "firmy.get", "firmy.admin", "firmy.contracts", "firmy.disputes", "firmy.loans",
        "firmy.world.loans", "firmy.admin.freeze", "firmy.dispute.resolve", "firmy.world.loanDecide", "firmy.world.loanSweep");

    /** Stand-in for FirmyPlugin: the public method the Panel calls by reflection. */
    public static class FakeFirmy {
        final List<String> calls = new ArrayList<>();
        final Map<String, Object> answer = Map.of("ok", true);

        public Object firmyPanel(final UUID actor, final String method, final Map<String, String> args) {
            if (method.equals("firmy.admin") && !OWNER.equals(actor)) {
                throw new IllegalArgumentException("Brak dostępu.");
            }
            if (method.equals("firmy.loans")) {
                throw new IllegalArgumentException("Nieznana metoda firm: firmy.loans");
            }
            if (method.equals("firmy.disputes")) {
                throw new IllegalStateException("awaria");
            }
            this.calls.add(actor + " " + method + " " + new TreeMap<>(args));
            return this.answer;
        }
    }

    /** Firmy without the web panel. */
    public static final class OldFirmy {
        public boolean railwayManager(final UUID actor) {
            return false;
        }
    }

    private static final Actor OWNER_ACTOR = new Actor(OWNER, "WorldSteward", false, false, Set.of(), null, null);
    private static final Actor PLAYER = new Actor(UUID.randomUUID(), "Gracz", false, false, Set.of(), null, null);
    private static final Actor ADMIN = new Actor(UUID.randomUUID(), "Admin", true, false, Set.of(), null, null);

    private static Call call(final Actor actor, final String method, final JsonObject params) {
        return new Call(method, actor, new Params(params), null, "127.0.0.1");
    }

    private static JsonObject params(final Object... kv) {
        final JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            final Object v = kv[i + 1];
            if (v instanceof JsonElement e) {
                o.add((String) kv[i], e);
            } else if (v instanceof Number n) {
                o.addProperty((String) kv[i], n);
            } else if (v instanceof Boolean b) {
                o.addProperty((String) kv[i], b);
            } else {
                o.addProperty((String) kv[i], String.valueOf(v));
            }
        }
        return o;
    }

    @Test
    void everyMethodIsRegisteredForLoggedInUsersWithoutCountryTarget() {
        assertEquals(ALL.size(), MethodSpec.FIRMY_READS.size() + MethodSpec.FIRMY_WRITES.size());
        for (final String m : ALL) {
            final MethodSpec s = MethodSpec.of(m);
            assertNotNull(s, m);
            assertEquals(Requirement.LOGGED_IN, s.requirement(), m);
            assertFalse(s.targetsId(), m);
            assertFalse(s.npcOnly(), m);
            assertEquals(MethodSpec.FIRMY_WRITES.contains(m), s.mutation(), m);
            assertTrue(Access.allowed(PLAYER, s.requirement(), null, false), "Firmy decide, not the Panel gate: " + m);
        }
        for (final String w : List.of("firmy.admin.freeze", "firmy.dispute.resolve", "firmy.world.loanDecide", "firmy.world.loanSweep")) {
            assertTrue(MethodSpec.of(w).mutation(), w + " is audited");
        }
    }

    @Test
    void panelMethodsDispatchesEveryNameToFirmy() throws Exception {
        final String src = Files.readString(Path.of("src/main/java/pl/medivalrpg/panel/rpc/PanelMethods.java"));
        final int arrow = src.indexOf("-> FirmyMethods.invoke(c)");
        assertTrue(arrow > 0, "case → FirmyMethods.invoke");
        final String caseText = src.substring(src.lastIndexOf("case ", src.lastIndexOf("\"firmy.list\"", arrow)), arrow);
        for (final String m : ALL) {
            assertTrue(caseText.contains("\"" + m + "\""), m);
        }
    }

    @Test
    void onlyWhitelistedPrimitiveArgumentsArePassedWithTheLoggedInUuid() {
        final FakeFirmy firmy = new FakeFirmy();
        final JsonObject p = params("id", "kuznia-1", "frozen", true, "reason", "kontrola", "page", 2, "actor", OWNER.toString(),
            "owner", "true", "uuid", OWNER.toString(), "sort", JsonNull.INSTANCE);
        assertSame(firmy.answer, FirmyMethods.invoke(call(PLAYER, "firmy.admin.freeze", p), firmy));
        assertEquals(List.of(PLAYER.uuid() + " firmy.admin.freeze {frozen=true, id=kuznia-1, page=2, reason=kontrola}"), firmy.calls);
        FirmyMethods.invoke(call(OWNER_ACTOR, "firmy.list", params("sort", "obrot")), firmy);
        assertEquals(OWNER + " firmy.list {sort=obrot}", firmy.calls.get(1));
        final StringBuilder all = new StringBuilder();
        for (final String k : FirmyMethods.KEYS) {
            all.append(k).append(',');
        }
        assertEquals("id,sort,frozen,reason,approve,note,winner,page,query,kind,", all.toString());
    }

    @Test
    void objectsArraysAndOversizedValuesAreRefusedBeforeFirmy() {
        final FakeFirmy firmy = new FakeFirmy();
        FirmyMethods.invoke(call(PLAYER, "firmy.get", params("id", "x".repeat(FirmyMethods.MAX_VALUE))), firmy);
        final List<JsonObject> bad = List.of(
            params("id", new JsonObject()),
            params("id", new JsonArray()),
            params("note", "x".repeat(FirmyMethods.MAX_VALUE + 1)),
            params("winner", params("a", "b")),
            params("kind", new JsonArray()));
        for (final JsonObject p : bad) {
            final RpcException e = assertThrows(RpcException.class, () -> FirmyMethods.invoke(call(PLAYER, "firmy.dispute.resolve", p), firmy));
            assertEquals(FirmyMethods.BAD_PARAM, e.getMessage());
        }
        // an unknown key with an object value is simply dropped
        FirmyMethods.invoke(call(PLAYER, "firmy.get", params("id", "a", "extra", new JsonObject())), firmy);
        assertEquals(2, firmy.calls.size(), "Firmy never reached with a bad parameter");
    }

    @Test
    void firmyErrorsBecomePolishMessages() {
        final FakeFirmy firmy = new FakeFirmy();
        RpcException e = assertThrows(RpcException.class, () -> FirmyMethods.invoke(call(ADMIN, "firmy.admin", new JsonObject()), firmy));
        assertEquals("Brak dostępu.", e.getMessage(), "panel admin is not the owner; Firmy refuses");
        e = assertThrows(RpcException.class, () -> FirmyMethods.invoke(call(PLAYER, "firmy.loans", params("id", "a")), firmy));
        assertEquals("Nieznana metoda firm: firmy.loans", e.getMessage(), "stage 2 not installed yet");
        e = assertThrows(RpcException.class, () -> FirmyMethods.invoke(call(PLAYER, "firmy.disputes", new JsonObject()), firmy));
        assertEquals(FirmyMethods.FAILED, e.getMessage());
        e = assertThrows(RpcException.class, () -> FirmyMethods.invoke(call(PLAYER, "firmy.list", new JsonObject()), new OldFirmy()));
        assertEquals("Zainstalowane Firmy nie obsługują jeszcze panelu WWW.", e.getMessage());
        e = assertThrows(RpcException.class, () -> FirmyMethods.invoke(call(PLAYER, "firmy.list", new JsonObject()), null));
        assertEquals("Firmy są obecnie niedostępne.", e.getMessage());
        assertTrue(FirmyMethods.invoke(call(OWNER_ACTOR, "firmy.admin", new JsonObject()), firmy) instanceof Map<?, ?>);
    }
}
