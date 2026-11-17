// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.panel.access.Access;
import pl.medivalrpg.panel.access.Actor;
import pl.medivalrpg.panel.access.Requirement;
import pl.medivalrpg.panel.audit.AuditLog;
import pl.medivalrpg.panel.util.Json;

/** {@code country.flag.*} / {@code admin.flag.*}: registration, parameter whitelist and limits, the Kraje bridge. */
class FlagMethodsTest {

    static final UUID COUNTRY = UUID.fromString("0f2d6a51-5c3e-4c1a-9d0e-7b8a9c0d1e2f");
    static final UUID MODERATOR = UUID.fromString("ff923cf7-f43c-37b8-85a3-77cbac357c9c");

    /** Stand-in for KrajePlugin: the two public methods the Panel calls by reflection. */
    public static class FakeKraje {
        final List<String> calls = new ArrayList<>();
        final Map<String, Object> answer = Map.of("ok", true, "message", "Zapisano.");

        public boolean flagModerator(final UUID actor, final boolean panelAdmin) {
            return panelAdmin || MODERATOR.equals(actor);
        }

        public Map<String, Object> flagPanel(final UUID actor, final String actorName, final boolean panelAdmin, final String method,
                                             final Map<String, String> args) {
            if (method.startsWith("admin.flag.") && !this.flagModerator(actor, panelAdmin)) {
                throw new IllegalArgumentException("Tylko moderator chorągwi może to zrobić.");
            }
            if ("country.flag.withdraw".equals(method)) {
                throw new IllegalStateException("awaria");
            }
            this.calls.add(method + " " + actorName + " " + panelAdmin + " " + new TreeMap<>(args));
            return this.answer;
        }
    }

    /** Kraje before the banner release. */
    public static final class OldKraje {
        public boolean railwayManager(final UUID actor) {
            return false;
        }
    }

    private static final Actor ADVISOR = new Actor(UUID.randomUUID(), "Doradca", false, false, Set.of(), COUNTRY, Rank.ADVISOR);
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
            } else {
                o.addProperty((String) kv[i], String.valueOf(v));
            }
        }
        return o;
    }

    @Test
    void methodsAreRegisteredWithTheirRequirements() {
        assertEquals(9, FlagMethods.METHODS.size());
        final var get = MethodSpec.of("country.flag.get");
        assertEquals(Requirement.LOGGED_IN, get.requirement());
        assertFalse(get.mutation());
        assertTrue(get.targetsId());
        for (final String m : FlagMethods.COUNTRY_WRITES) {
            final var s = MethodSpec.of(m);
            assertEquals(Requirement.ADVISOR, s.requirement(), m);
            assertTrue(s.mutation(), m);
            assertTrue(s.targetsId(), m);
            assertFalse(s.npcOnly(), m);
        }
        final var queue = MethodSpec.of("admin.flag.queue");
        assertEquals(Requirement.LOGGED_IN, queue.requirement());
        assertFalse(queue.mutation());
        assertFalse(queue.targetsId());
        for (final String m : List.of("admin.flag.approve", "admin.flag.reject")) {
            final var s = MethodSpec.of(m);
            assertEquals(Requirement.LOGGED_IN, s.requirement(), m);
            assertTrue(s.mutation(), m);
            assertTrue(s.targetsId(), m);
            assertFalse(s.npcOnly(), m);
        }
        final var rebuild = MethodSpec.of("admin.flag.rebuild");
        assertEquals(Requirement.LOGGED_IN, rebuild.requirement());
        assertTrue(rebuild.mutation());
        assertFalse(rebuild.targetsId());
    }

    @Test
    void uploadIsForAdvisorsOfTheirOwnCountryAndPanelAdmins() {
        final Requirement r = MethodSpec.of("country.flag.upload").requirement();
        assertTrue(Access.allowed(ADVISOR, r, COUNTRY, false));
        assertFalse(Access.allowed(new Actor(UUID.randomUUID(), "Obywatel", false, false, Set.of(), COUNTRY, Rank.CITIZEN), r, COUNTRY, false));
        assertFalse(Access.allowed(ADVISOR, r, UUID.randomUUID(), false), "advisor of another country");
        assertTrue(Access.allowed(ADMIN, r, COUNTRY, false), "panel admin passes the Panel gate; Kraje decides");
    }

    @Test
    void whitelistedArgumentsArePassedWithThePanelAdminFlag() {
        final FakeKraje kraje = new FakeKraje();
        final JsonObject p = params("id", COUNTRY.toString(), "data", "data:image/png;base64,iVBORw0KGgo=", "filename", "flaga.png",
            "actor", "spoofed", "panelAdmin", "true");
        assertSame(kraje.answer, FlagMethods.invoke(call(ADVISOR, "country.flag.upload", p), kraje));
        assertEquals(List.of("country.flag.upload Doradca false {data=data:image/png;base64,iVBORw0KGgo=, filename=flaga.png, id=" + COUNTRY + "}"),
            kraje.calls);
        FlagMethods.invoke(call(ADMIN, "admin.flag.approve", params("id", COUNTRY.toString(), "imageId", "0123abcd")), kraje);
        assertEquals("admin.flag.approve Admin true {id=" + COUNTRY + ", imageId=0123abcd}", kraje.calls.get(1));
    }

    @Test
    void badOrOversizedArgumentsAreRefused() {
        final FakeKraje kraje = new FakeKraje();
        final String ok = "A".repeat(FlagMethods.MAX_DATA);
        FlagMethods.invoke(call(ADVISOR, "country.flag.upload", params("id", COUNTRY.toString(), "data", ok)), kraje);
        final List<JsonObject> bad = List.of(
            params("id", COUNTRY.toString(), "data", "A".repeat(FlagMethods.MAX_DATA + 1)),
            params("id", COUNTRY.toString(), "data", "data:image/png;base64," + "A".repeat(FlagMethods.MAX_DATA)),
            params("id", COUNTRY.toString(), "data", "data:image/gif;base64,R0lGOD"),
            params("id", COUNTRY.toString(), "data", "<script>"),
            params("id", COUNTRY.toString(), "data", "data:image/png;base64,"),
            params("id", COUNTRY.toString(), "data", new JsonArray()),
            params("id", COUNTRY.toString(), "data", new JsonObject()),
            params("id", "nie-uuid"),
            params("id", COUNTRY.toString(), "imageId", "ABCDEF"),
            params("id", COUNTRY.toString(), "imageId", "a".repeat(33)),
            params("id", COUNTRY.toString(), "imageId", "../x"),
            params("id", COUNTRY.toString(), "reason", "x".repeat(FlagMethods.MAX_REASON + 1)),
            params("id", COUNTRY.toString(), "filename", "x".repeat(FlagMethods.MAX_FILENAME + 1)));
        for (final JsonObject p : bad) {
            final RpcException e = assertThrows(RpcException.class, () -> FlagMethods.invoke(call(ADVISOR, "country.flag.upload", p), kraje), p.toString().substring(0, Math.min(80, p.toString().length())));
            assertEquals(FlagMethods.BAD_PARAM, e.getMessage());
        }
        assertEquals(1, kraje.calls.size(), "Kraje never reached with a bad parameter");
        // numbers are JSON primitives too
        FlagMethods.invoke(call(ADVISOR, "country.flag.restore", params("id", COUNTRY.toString(), "imageId", 1234)), kraje);
        assertTrue(kraje.calls.get(1).contains("imageId=1234"));
    }

    @Test
    void krajeErrorsBecomePolishMessages() {
        final FakeKraje kraje = new FakeKraje();
        final Actor player = new Actor(UUID.randomUUID(), "Gracz", false, false, Set.of(), null, null);
        RpcException e = assertThrows(RpcException.class, () -> FlagMethods.invoke(call(player, "admin.flag.queue", new JsonObject()), kraje));
        assertEquals("Tylko moderator chorągwi może to zrobić.", e.getMessage());
        e = assertThrows(RpcException.class, () -> FlagMethods.invoke(call(ADVISOR, "country.flag.withdraw", params("id", COUNTRY.toString())), kraje));
        assertEquals(FlagMethods.FAILED, e.getMessage());
        e = assertThrows(RpcException.class, () -> FlagMethods.invoke(call(ADVISOR, "country.flag.get", params("id", COUNTRY.toString())), new OldKraje()));
        assertEquals(FlagMethods.OUTDATED, e.getMessage());
        e = assertThrows(RpcException.class, () -> FlagMethods.invoke(call(ADVISOR, "country.flag.get", params("id", COUNTRY.toString())), null));
        assertEquals(FlagMethods.UNAVAILABLE, e.getMessage());
        assertEquals("Kraje są obecnie niedostępne.", FlagMethods.UNAVAILABLE);
    }

    /** Kraje whose upload answers with a future completed later (off the main thread). */
    public static final class AsyncKraje {
        CompletableFuture<Map<String, Object>> pending = new CompletableFuture<>();

        public Object flagPanel(final UUID actor, final String actorName, final boolean panelAdmin, final String method,
                                final Map<String, String> args) {
            return "country.flag.upload".equals(method) ? this.pending : Map.of("ok", true);
        }
    }

    private static Throwable failureOf(final Object result) {
        final CompletableFuture<?> f = assertInstanceOf(CompletableFuture.class, result);
        final CompletionException e = assertThrows(CompletionException.class, f::join);
        return e.getCause();
    }

    @Test
    void asyncUploadFailuresArePolishMessages() {
        final JsonObject p = params("id", COUNTRY.toString(), "data", "iVBORw0KGgo=");
        // success: a NEW future with the same value
        AsyncKraje kraje = new AsyncKraje();
        Object result = FlagMethods.invoke(call(ADVISOR, "country.flag.upload", p), kraje);
        final CompletableFuture<?> ok = assertInstanceOf(CompletableFuture.class, result);
        assertNotSame(kraje.pending, ok);
        final Map<String, Object> value = Map.of("ok", true, "message", "Obrazek czeka na zatwierdzenie.");
        kraje.pending.complete(value);
        assertSame(value, ok.join());
        // rule violation keeps its message (also when wrapped in CompletionException)
        kraje = new AsyncKraje();
        result = FlagMethods.invoke(call(ADVISOR, "country.flag.upload", p), kraje);
        kraje.pending.completeExceptionally(new IllegalArgumentException("Obrazek jest za mały (min. 32×20 px)."));
        Throwable cause = failureOf(result);
        assertInstanceOf(RpcException.class, cause);
        assertEquals("Obrazek jest za mały (min. 32×20 px).", cause.getMessage());
        kraje = new AsyncKraje();
        result = FlagMethods.invoke(call(ADVISOR, "country.flag.upload", p), kraje);
        kraje.pending.completeExceptionally(new CompletionException(new IllegalArgumentException("Nieobsługiwany format.")));
        assertEquals("Nieobsługiwany format.", failureOf(result).getMessage());
        // any other failure → generic message, no internals
        kraje = new AsyncKraje();
        result = FlagMethods.invoke(call(ADVISOR, "country.flag.upload", p), kraje);
        kraje.pending.completeExceptionally(new java.io.UncheckedIOException(new java.io.IOException("dysk pełny")));
        cause = failureOf(result);
        assertInstanceOf(RpcException.class, cause);
        assertEquals(FlagMethods.FAILED, cause.getMessage());
        // other methods stay synchronous maps
        assertInstanceOf(Map.class, FlagMethods.invoke(call(ADVISOR, "country.flag.get", params("id", COUNTRY.toString())), new AsyncKraje()));
    }

    @Test
    void moderatorRoleComesFromKrajeOnly() {
        final FakeKraje kraje = new FakeKraje();
        assertTrue(FlagMethods.moderator(kraje, MODERATOR, false));
        assertTrue(FlagMethods.moderator(kraje, UUID.randomUUID(), true));
        assertFalse(FlagMethods.moderator(kraje, UUID.randomUUID(), false));
        assertFalse(FlagMethods.moderator(new OldKraje(), MODERATOR, true), "old Kraje");
        assertFalse(FlagMethods.moderator(null, MODERATOR, true), "no Kraje");
    }

    @Test
    void auditNeverStoresTheImage() {
        final String image = "data:image/png;base64," + "Q".repeat(200_000);
        final JsonObject p = params("id", COUNTRY.toString(), "data", image, "filename", "flaga.png");
        final String audited = AuditLog.truncate(Json.GSON.toJson(FlagMethods.auditParams("country.flag.upload", p)));
        assertFalse(audited.contains("QQQQ"), audited);
        assertTrue(audited.contains("<obraz " + image.length() + " znaków>"), audited);
        assertTrue(audited.contains("flaga.png"));
        assertTrue(p.get("data").getAsString().startsWith("data:image/png"), "the call itself keeps the image");
        final JsonObject other = params("id", COUNTRY.toString(), "data", "x");
        assertSame(other, FlagMethods.auditParams("country.plan.set", other));
    }
}
