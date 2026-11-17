// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SupplyDeliveryBridgeTest {
    static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000011");
    static final UUID COUNTRY = UUID.fromString("00000000-0000-0000-0000-000000000012");

    public static final class Provider {
        Map<String, Object> response = Map.of("status", "PENDING", "receipt", "", "reason", "zapis");
        boolean throwsError;
        int calls;
        UUID player, country;
        String id, material;
        int amount;
        public Map<String, Object> supplyDelivery(UUID player, UUID country, String id, String material, int amount) {
            this.calls++;
            this.player = player; this.country = country; this.id = id; this.material = material; this.amount = amount;
            if (this.throwsError) throw new IllegalStateException("provider failed");
            return this.response;
        }
    }

    public static final class Incompatible {
        public String supplyDelivery(UUID player, UUID country, String id, String material, int amount) { return "DELIVERED"; }
    }

    private static SupplyDeliveryBridge.Result call(SupplyDeliveryBridge bridge) {
        return bridge.deliver(PLAYER, COUNTRY, "wladcy:abc-123", "OAK_LOG", 32);
    }

    @Test void absenceOldPluginAndWrongReturnTypeFailClosed() {
        for (Object target : new Object[]{null, new Object(), new Incompatible()}) {
            var bridge = new SupplyDeliveryBridge(() -> target);
            assertFalse(bridge.available());
            assertEquals(SupplyDeliveryBridge.Status.UNAVAILABLE, call(bridge).status());
        }
    }

    @Test void discoveryIsReadOnlyAndInvocationUsesTheExactAgreedSignature() {
        var provider = new Provider();
        var bridge = new SupplyDeliveryBridge(() -> provider);
        assertTrue(bridge.available());
        assertEquals(0, provider.calls);
        assertEquals(SupplyDeliveryBridge.Status.PENDING, call(bridge).status());
        assertEquals(PLAYER, provider.player); assertEquals(COUNTRY, provider.country);
        assertEquals("wladcy:abc-123", provider.id); assertEquals("OAK_LOG", provider.material); assertEquals(32, provider.amount);
        for (String status : new String[]{"PENDING", "DELIVERED", "BLOCKED", "UNAVAILABLE"}) {
            provider.response = Map.of("status", status, "receipt", "durable-receipt", "reason", "msg\n§cred");
            var result = call(bridge);
            assertEquals(status, result.status().name());
            assertEquals("msg red", result.reason());
        }
    }

    @Test void malformedAndUnknownRepliesNeverAuthorizePayment() {
        var provider = new Provider(); var bridge = new SupplyDeliveryBridge(() -> provider);
        for (var reply : java.util.List.<Map<String,Object>>of(Map.of(), Map.of("status", "OK"),
            Map.of("status", 1), Map.of("status", "DELIVERED"), Map.of("status", "DELIVERED", "receipt", 1),
            Map.of("status", "DELIVERED", "receipt", " "), Map.of("status", "DELIVERED", "receipt", "x".repeat(257)))) {
            provider.response = reply;
            assertEquals(SupplyDeliveryBridge.Status.UNAVAILABLE, call(bridge).status());
        }
        provider.response = null;
        assertEquals(SupplyDeliveryBridge.Status.UNAVAILABLE, call(bridge).status());
    }

    @Test void failureAndPluginReplacementDoNotCacheAStaleProvider() {
        var provider = new Provider(); var target = new AtomicReference<Object>(provider);
        var bridge = new SupplyDeliveryBridge(target::get);
        provider.throwsError = true;
        assertEquals(SupplyDeliveryBridge.Status.UNAVAILABLE, call(bridge).status());
        target.set(null); assertFalse(bridge.available());
        var replacement = new Provider(); target.set(replacement);
        assertEquals(SupplyDeliveryBridge.Status.PENDING, call(bridge).status());
        assertEquals(1, replacement.calls);
    }

    @Test void invalidRequestsAreRejectedBeforeCallingProvider() {
        var provider = new Provider(); var bridge = new SupplyDeliveryBridge(() -> provider);
        assertEquals(SupplyDeliveryBridge.Status.UNAVAILABLE, bridge.deliver(PLAYER, COUNTRY, "other:id", "OAK_LOG", 32).status());
        assertEquals(SupplyDeliveryBridge.Status.UNAVAILABLE, bridge.deliver(PLAYER, COUNTRY, "wladcy:id", "not a material", 32).status());
        assertEquals(SupplyDeliveryBridge.Status.UNAVAILABLE, bridge.deliver(PLAYER, COUNTRY, "wladcy:id", "OAK_LOG", 0).status());
        assertEquals(SupplyDeliveryBridge.Status.UNAVAILABLE, bridge.deliver(PLAYER, COUNTRY, "wladcy:id", "OAK_LOG", 2305).status());
        assertEquals(0, provider.calls);
    }
}
