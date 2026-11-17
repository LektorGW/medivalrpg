// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.panel.access.Access;
import pl.medivalrpg.panel.access.Actor;

/** {@code world.stats.*}: only the personal manager's UUID passes; panel admins, NPC managers and rulers are refused. */
class WorldStatsMethodsTest {

    static final UUID OWNER = UUID.fromString("ff923cf7-f43c-37b8-85a3-77cbac357c9c");

    /** Stand-in for the Skarbiec plugin: the same two public methods the Panel calls by reflection. */
    public static class FakeSkarbiec {
        final List<String> calls = new ArrayList<>();
        Object answer = Map.of("ok", true);

        public boolean worldMoneyManager(final UUID actor) {
            return OWNER.equals(actor);
        }

        public Object worldTreasury(final UUID actor, final String method, final Map<String, String> args) {
            if (!OWNER.equals(actor)) {
                throw new IllegalArgumentException("Tylko WorldSteward zarządza Skarbem Świata.");
            }
            this.calls.add(method + " " + new java.util.TreeMap<>(args));
            return this.answer;
        }

        public Object worldStats(final UUID actor, final String method, final Map<String, String> args) {
            if (!OWNER.equals(actor)) {
                throw new IllegalArgumentException("Tylko WorldSteward ma dostęp do statystyk Skarbu Świata.");
            }
            this.calls.add(method + " " + new java.util.TreeMap<>(args));
            return this.answer;
        }
    }

    /** Simulates a broken first gate in the Panel: Skarbiec must still refuse. */
    public static final class BrokenGate extends FakeSkarbiec {
        @Override
        public boolean worldMoneyManager(final UUID actor) {
            return true;
        }
    }

    /** Skarbiec before the statistics release: manager check only. */
    public static final class OldSkarbiec {
        public boolean worldMoneyManager(final UUID actor) {
            return OWNER.equals(actor);
        }
    }

    private static Call call(final Actor actor, final String method, final JsonObject params) {
        return new Call(method, actor, new Params(params), null, "127.0.0.1");
    }

    private static List<Actor> others() {
        final UUID country = UUID.randomUUID();
        return List.of(
            new Actor(UUID.randomUUID(), "Admin", true, false, Set.of(), null, null),
            new Actor(UUID.randomUUID(), "WorldSteward", true, true, Set.of(), country, Rank.RULER), // same nick, other UUID
            new Actor(UUID.randomUUID(), "Zarzadca", false, true, Set.of(), null, null),
            new Actor(UUID.randomUUID(), "Wladca", false, false, Set.of(), country, Rank.RULER),
            new Actor(UUID.randomUUID(), "Gracz", false, false, Set.of(), null, null));
    }

    @Test
    void everyStatsMethodIsRegisteredAndOnlyTheCensusIsAudited() {
        for (final String m : MethodSpec.WORLD_STATS_READS) {
            assertFalse(MethodSpec.of(m).mutation(), m);
            assertFalse(MethodSpec.of(m).targetsId(), m);
        }
        assertTrue(MethodSpec.of("world.stats.census").mutation());
    }

    @Test
    void otherAdminsAndPlayersAreDeniedBeforeAnyDataIsRead() {
        final FakeSkarbiec skarbiec = new FakeSkarbiec();
        for (final Actor a : others()) {
            for (final String m : List.of("world.stats.summary", "world.stats.accounts", "world.stats.flows", "world.stats.account",
                "world.stats.coins", "world.stats.trail", "world.stats.merchant", "world.stats.census", "world.stats.households")) {
                // The generic gate lets any logged-in user reach the method; the UUID check below is what refuses.
                assertTrue(Access.allowed(a, MethodSpec.of(m).requirement(), null, false));
                final RpcException e = assertThrows(RpcException.class, () -> WorldMethods.stats(call(a, m, new JsonObject()), skarbiec), a.name() + " " + m);
                assertEquals(WorldMethods.STATS_DENIED, e.getMessage());
            }
        }
        assertTrue(skarbiec.calls.isEmpty(), "Skarbiec statistics never reached for a non-owner");
    }

    @Test
    void skarbiecRefusesOnItsOwnEvenIfThePanelCheckWereBypassed() {
        final FakeSkarbiec skarbiec = new BrokenGate();
        final Actor admin = others().get(0);
        final RpcException e = assertThrows(RpcException.class, () -> WorldMethods.stats(call(admin, "world.stats.flows", new JsonObject()), skarbiec));
        assertEquals("Tylko WorldSteward ma dostęp do statystyk Skarbu Świata.", e.getMessage());
    }

    @Test
    void ownerGetsDataWithWhitelistedParameters() {
        final FakeSkarbiec skarbiec = new FakeSkarbiec();
        final Actor owner = new Actor(OWNER, "WorldSteward", false, false, Set.of(), null, null);
        final JsonObject p = new JsonObject();
        p.addProperty("from", 1);
        p.addProperty("query", "żołd");
        p.addProperty("limit", 50);
        p.addProperty("actor", "spoofed"); // not a statistics parameter: never forwarded
        assertSame(skarbiec.answer, WorldMethods.stats(call(owner, "world.stats.flows", p), skarbiec));
        assertEquals(List.of("world.stats.flows {from=1, limit=50, query=żołd}"), skarbiec.calls);
    }

    @Test
    void invalidParametersAreRejected() {
        final Actor owner = new Actor(OWNER, "WorldSteward", false, false, Set.of(), null, null);
        final JsonObject p = new JsonObject();
        p.add("account", new JsonArray());
        assertThrows(RpcException.class, () -> WorldMethods.stats(call(owner, "world.stats.account", p), new FakeSkarbiec()));
        final JsonObject long1 = new JsonObject();
        long1.addProperty("query", "x".repeat(200));
        assertThrows(RpcException.class, () -> WorldMethods.stats(call(owner, "world.stats.flows", long1), new FakeSkarbiec()));
    }

    @Test
    void asynchronousFailuresBecomePolishErrors() {
        final FakeSkarbiec skarbiec = new FakeSkarbiec();
        final CompletableFuture<Object> pending = new CompletableFuture<>();
        skarbiec.answer = pending;
        final Actor owner = new Actor(OWNER, "WorldSteward", false, false, Set.of(), null, null);
        final Object r = WorldMethods.stats(call(owner, "world.stats.accounts", new JsonObject()), skarbiec);
        assertTrue(r instanceof CompletableFuture<?>);
        pending.completeExceptionally(new IllegalArgumentException("Nie udało się odczytać dzienników Skarbca: dysk"));
        final CompletionException e = assertThrows(CompletionException.class, () -> ((CompletableFuture<?>) r).join());
        assertTrue(e.getCause() instanceof RpcException);
        assertEquals("Nie udało się odczytać dzienników Skarbca: dysk", e.getCause().getMessage());
    }

    @Test
    void missingStatisticsInAnOlderSkarbiecIsReported() {
        final Object old = new OldSkarbiec();
        final Actor owner = new Actor(OWNER, "WorldSteward", false, false, Set.of(), null, null);
        final RpcException e = assertThrows(RpcException.class, () -> WorldMethods.stats(call(owner, "world.stats.summary", new JsonObject()), old));
        assertTrue(e.getMessage().contains("zaktualizuj Skarbiec"));
    }

    @Test
    void npcTreasuryTransfersAreOwnerOnlyAndForwardOnlyKnownParameters() {
        final FakeSkarbiec skarbiec = new FakeSkarbiec();
        for (final Actor a : others()) {
            for (final String m : List.of("world.npcTreasuries", "world.npcTransfer", "world.grants", "world.grantsApply")) {
                final RpcException e = assertThrows(RpcException.class, () -> WorldMethods.invoke(call(a, m, new JsonObject()), skarbiec));
                assertEquals("Tylko WorldSteward zarządza Skarbem Świata.", e.getMessage());
            }
        }
        assertTrue(skarbiec.calls.isEmpty());
        assertTrue(MethodSpec.of("world.npcTransfer").mutation(), "audited");
        assertFalse(MethodSpec.of("world.npcTreasuries").mutation());
        final Actor owner = new Actor(OWNER, "WorldSteward", false, false, Set.of(), null, null);
        final JsonObject p = new JsonObject();
        p.addProperty("country", "9d1fe36e-4852-4d18-9b2a-a89146f43e66");
        p.addProperty("amount", "100 zł");
        p.addProperty("confirm", 10_000);
        p.addProperty("reason", "odbudowa");
        p.addProperty("direction", "toCountry");
        p.addProperty("actor", "spoofed");
        WorldMethods.invoke(call(owner, "world.npcTransfer", p), skarbiec);
        assertEquals(List.of("world.npcTransfer {amount=100 zł, confirm=10000, country=9d1fe36e-4852-4d18-9b2a-a89146f43e66, direction=toCountry, reason=odbudowa}"),
            skarbiec.calls);
        // a refusal decided after the durable write keeps its Polish message
        final CompletableFuture<Object> pending = new CompletableFuture<>();
        skarbiec.answer = pending;
        final Object r = WorldMethods.invoke(call(owner, "world.npcTransfer", p), skarbiec);
        pending.completeExceptionally(new IllegalArgumentException("Za mało środków w rezerwie Skarbu Świata."));
        final CompletionException e = assertThrows(CompletionException.class, () -> ((CompletableFuture<?>) r).join());
        assertEquals("Za mało środków w rezerwie Skarbu Świata.", e.getCause().getMessage());
    }

    @Test
    void bulkTransferToAllNpcCountriesIsOwnerOnlyAndForwardsCountAndKey() {
        final FakeSkarbiec skarbiec = new FakeSkarbiec();
        final JsonObject p = new JsonObject();
        p.addProperty("amount", "10 zł");
        p.addProperty("confirm", 17_000);
        p.addProperty("count", 17);
        p.addProperty("reason", "wsparcie zimowe");
        p.addProperty("direction", "toAllNpc");
        p.addProperty("key", "npcbulk-0123456789abcdef");
        p.addProperty("country", "ignored-by-skarbiec");
        for (final Actor a : others()) {
            final RpcException e = assertThrows(RpcException.class, () -> WorldMethods.invoke(call(a, "world.npcTransfer", p), skarbiec));
            assertEquals("Tylko WorldSteward zarządza Skarbem Świata.", e.getMessage());
        }
        assertTrue(skarbiec.calls.isEmpty(), "no call reaches Skarbiec for others");
        final Actor owner = new Actor(OWNER, "WorldSteward", false, false, Set.of(), null, null);
        WorldMethods.invoke(call(owner, "world.npcTransfer", p), skarbiec);
        assertEquals(List.of("world.npcTransfer {amount=10 zł, confirm=17000, count=17, country=ignored-by-skarbiec, direction=toAllNpc, "
            + "key=npcbulk-0123456789abcdef, reason=wsparcie zimowe}"), skarbiec.calls);
        final JsonObject bad = p.deepCopy();
        bad.add("count", new JsonArray());
        assertThrows(RpcException.class, () -> WorldMethods.invoke(call(owner, "world.npcTransfer", bad), skarbiec));
    }

    /** Skarbiec refusing with an IllegalArgumentException that has no message. */
    public static final class SilentSkarbiec extends FakeSkarbiec {
        @Override
        public Object worldTreasury(final UUID actor, final String method, final Map<String, String> args) {
            throw new IllegalArgumentException();
        }

        @Override
        public Object worldStats(final UUID actor, final String method, final Map<String, String> args) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(" "));
        }
    }

    @Test
    void refusalWithoutMessageGetsAPolishFallbackNeverNull() {
        final Actor owner = new Actor(OWNER, "WorldSteward", false, false, Set.of(), null, null);
        final RpcException e = assertThrows(RpcException.class,
            () -> WorldMethods.invoke(call(owner, "world.get", new JsonObject()), new SilentSkarbiec()));
        assertEquals("Nie udało się wykonać operacji Skarbu Świata.", e.getMessage());
        final Object r = WorldMethods.stats(call(owner, "world.stats.summary", new JsonObject()), new SilentSkarbiec());
        final CompletionException ce = assertThrows(CompletionException.class, () -> ((CompletableFuture<?>) r).join());
        assertEquals("Nie udało się odczytać statystyk Skarbu Świata.", ce.getCause().getMessage());
        assertEquals("Powód", WorldMethods.refusal(new IllegalArgumentException("Powód"), "x").getMessage());
        assertEquals("x", WorldMethods.refusal(new IllegalStateException("wewnętrzny"), "x").getMessage(), "only IAE texts reach the browser");
        assertEquals("x", WorldMethods.refusal(null, "x").getMessage());
    }
}
