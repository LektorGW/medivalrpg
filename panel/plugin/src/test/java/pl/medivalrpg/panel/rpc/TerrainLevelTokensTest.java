// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TerrainLevelTokensTest {
    private static Params p(final String json) { return Params.of(JsonParser.parseString(json)); }
    private static TerrainLevelTokens.Request request() {
        return TerrainLevelTokens.Request.parse(p("{minX:0,minZ:0,maxX:15,maxZ:15}"), "world", false);
    }

    @Test void selectionNormalizesCoordinatesAndAutomaticHeight() {
        final var r = TerrainLevelTokens.Request.parse(p("{minX:15,minZ:8,maxX:0,maxZ:-1,targetY:null}"), "world", false);
        assertEquals(0, r.minX()); assertEquals(-1, r.minZ()); assertEquals(15, r.maxX()); assertEquals(8, r.maxZ());
        assertNull(r.targetY()); assertFalse(r.free());
        assertEquals(64, r.resolved(64).targetY());
    }

    @Test void hugeFractionalOrForeignWorldSelectionIsRejected() {
        assertThrows(RpcException.class, () -> TerrainLevelTokens.Request.parse(p("{minX:0,minZ:0,maxX:64,maxZ:64}"), "world", true));
        assertThrows(RpcException.class, () -> TerrainLevelTokens.Request.parse(p("{minX:0.5,minZ:0,maxX:1,maxZ:1}"), "world", true));
        assertThrows(RpcException.class, () -> TerrainLevelTokens.Request.parse(p("{world:'nether',minX:0,minZ:0,maxX:1,maxZ:1}"), "world", true));
        assertThrows(RpcException.class, () -> TerrainLevelTokens.Request.parse(p("{minX:0,minZ:0,maxX:1,maxZ:1,targetY:1.5}"), "world", true));
        assertThrows(RpcException.class, () -> TerrainLevelTokens.Request.parse(p("{minX:-29999000,minZ:-29999000,maxX:29999000,maxZ:29999000}"), "world", true));
    }

    @Test void freeWorkRequiresAdminAndFixedHeightCannotBeSilentlyChanged() {
        final String json = "{minX:0,minZ:0,maxX:1,maxZ:1,targetY:64,free:true}";
        assertThrows(RpcException.class, () -> TerrainLevelTokens.Request.parse(p(json), "world", false));
        final var r = TerrainLevelTokens.Request.parse(p(json), "world", true);
        assertTrue(r.free()); assertThrows(RpcException.class, () -> r.resolved(65));
    }

    @Test void confirmationIsBoundToActorCountryAndUsableOnce() {
        final var tokens = new TerrainLevelTokens();
        final UUID actor = UUID.randomUUID(), country = UUID.randomUUID();
        final var issued = tokens.issue(actor, country, request().resolved(64), "preview", 100);
        assertThrows(RpcException.class, () -> tokens.consume(issued.token(), UUID.randomUUID(), country, 101));
        assertThrows(RpcException.class, () -> tokens.consume(issued.token(), actor, UUID.randomUUID(), 101));
        assertEquals(issued, tokens.consume(issued.token(), actor, country, 101));
        assertThrows(RpcException.class, () -> tokens.consume(issued.token(), actor, country, 102));
    }

    @Test void expiredOrSupersededPreviewCannotBeConfirmed() {
        final var tokens = new TerrainLevelTokens();
        final UUID actor = UUID.randomUUID(), country = UUID.randomUUID();
        final var old = tokens.issue(actor, country, request().resolved(64), "old", 100);
        final var latest = tokens.issue(actor, country, request().resolved(65), "new", 101);
        assertThrows(RpcException.class, () -> tokens.consume(old.token(), actor, country, 102));
        assertThrows(RpcException.class, () -> tokens.consume(latest.token(), actor, country, latest.expiresAt()));
    }

    @Test void changedCostOrEarthworkRequiresAnotherVisiblePreview() {
        final JsonObject preview = JsonParser.parseString("{world:'world',minX:0,minZ:0,maxX:1,maxZ:1,targetY:64,columns:4,changedColumns:3,blocks:6,cost:12}").getAsJsonObject();
        final String original = TerrainLevelTokens.signature(preview);
        preview.addProperty("cost", 13);
        assertNotEquals(original, TerrainLevelTokens.signature(preview));
        preview.addProperty("cost", 12); preview.addProperty("blocks", 7);
        assertNotEquals(original, TerrainLevelTokens.signature(preview));
        preview.remove("targetY");
        assertThrows(RpcException.class, () -> TerrainLevelTokens.signature(preview));
    }
}
