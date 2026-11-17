// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.api.NpcReply;

/** Pure parts of the v2 NPC methods: codes, treasury-history types, next tier, parameter validation. */
class NpcV2HelpersTest {

    private static Params params(final String json) {
        return Params.of(JsonParser.parseString(json));
    }

    @Test
    void codesMatchPanelMd() {
        assertEquals(List.of("AUTO", "FRIENDLY", "HOSTILE", "NEVER_WAR", "PREFERRED_TARGET"), NpcV2Methods.STANCES);
        assertEquals(List.of("DEFENSIVE", "BALANCED", "OFFENSIVE"), NpcV2Methods.POSTURES);
        assertEquals(List.of("WAR", "DIPLOMACY", "BUILD", "ARMY", "ECONOMY", "OTHER"), NpcV2Methods.CATEGORIES);
        assertEquals(List.of("npcTax", "aiIncome", "tribute", "other"), NpcV2Methods.INCOME_KEYS);
        assertEquals(List.of("wages", "land", "buildings", "recruit", "tribute", "war", "other"), NpcV2Methods.EXPENSE_KEYS);
        for (final String k : NpcV2Methods.EXPENSE_KEYS) {
            assertTrue(NpcV2Methods.LABELS.containsKey(k), k);
        }
    }

    @Test
    void ledgerKeysBecomeTreasuryTypes() {
        assertEquals("tax", NpcV2Methods.txType("npcTax", ""));
        assertEquals("income", NpcV2Methods.txType("aiIncome", ""));
        assertEquals("claim", NpcV2Methods.txType("land", "Zajęcie chunka"));
        assertEquals("upkeep", NpcV2Methods.txType("land", "Utrzymanie ziemi"));
        assertEquals("siege", NpcV2Methods.txType("war", "Oblężenie"));
        assertEquals("war", NpcV2Methods.txType("war", "Wypowiedzenie wojny"));
        assertEquals("build", NpcV2Methods.txType("buildings", ""));
        assertEquals("other", NpcV2Methods.txType("cokolwiek", null));
    }

    @Test
    void nextTierHint() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> next = NpcV2Methods.nextTier(2, 12, 3_000);
        assertEquals(3, next.get("tier"));
        assertEquals("Miasteczko", next.get("name"));
        assertEquals(List.of("20 mieszkańców (jest 12)", "Skarbiec 100 zł (jest 30 zł)"), next.get("missing"));
        assertNull(NpcV2Methods.nextTier(5, 100, 1_000_000), "highest tier");
        assertEquals(List.of(), NpcV2Methods.nextTier(1, 50, 50_000).get("missing"));
    }

    @Test
    void plannerOrdersAreAuto() {
        assertTrue(NpcV2Methods.autoOrder("AI"));
        assertTrue(NpcV2Methods.autoOrder(null));
        assertTrue(NpcV2Methods.autoOrder("planer"));
        assertFalse(NpcV2Methods.autoOrder("WorldSteward"));
    }

    @Test
    void orderErrorsArePolish() {
        assertTrue(NpcV2Methods.orderError("INSUFFICIENT_FUNDS").contains("pieniędzy"));
        assertTrue(NpcV2Methods.orderError("TOO_MANY_ORDERS").contains("Kolejka"));
        assertTrue(NpcV2Methods.orderError("XYZ").contains("XYZ"));
    }

    @Test
    void parameterValidationOfV2Shapes() {
        final Params p = params("{\"value\":-101,\"stance\":\"never_war\",\"posture\":\"OFFENSIVE\",\"days\":30,\"army\":45.5}");
        assertThrows(RpcException.class, () -> p.integer("value", -100, 100));
        assertEquals("NEVER_WAR", p.oneOf("stance", NpcV2Methods.STANCES));
        assertEquals("OFFENSIVE", p.oneOf("posture", NpcV2Methods.POSTURES));
        assertThrows(RpcException.class, () -> p.integer("army", 0, 100), "integers only");
        assertTrue(params("{\"target\":null}").absentOrEmpty("target"), "null = auto siege target");
        assertTrue(params("{\"target\":\"\"}").absentOrEmpty("target"));
    }

    @Test
    void nullOrBlankApiMessagesNeverBreakTheAnswer() {
        assertEquals("x", PanelMethods.text(null, "x"));
        assertEquals("x", PanelMethods.text("  ", "x"));
        assertEquals("Wojna", PanelMethods.text("Wojna", "x"));
        // a refusal without a message (NpcReply's constructor does not normalize null) keeps a Polish error instead of an NPE
        final RpcException refused = assertThrows(RpcException.class, () -> NpcV2Methods.reply(new NpcReply(false, null, null)));
        assertEquals("Operacja nie powiodła się.", refused.getMessage());
        assertEquals("Nie chcemy.", assertThrows(RpcException.class, () -> NpcV2Methods.reply(NpcReply.fail("Nie chcemy."))).getMessage());
        assertThrows(RpcException.class, () -> NpcV2Methods.reply(null));
        final Map<String, Object> ok = NpcV2Methods.reply(new NpcReply(true, null, Boolean.TRUE));
        assertEquals(Boolean.TRUE, ok.get("ok"));
        assertNull(ok.get("message"), "success shape unchanged");
        assertEquals(Boolean.TRUE, ok.get("accepted"));
    }
}
