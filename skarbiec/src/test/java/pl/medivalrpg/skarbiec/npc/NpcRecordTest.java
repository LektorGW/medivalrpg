// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** npcs.json stays compatible: old merchants (no type, no stall) load; type and stall survive a restart. */
class NpcRecordTest {

    private static final String PRODUCTION_MERCHANT = """
        {"id":"8747b8f4-fdbc-4da4-84ea-bfa2cfdcff54","type":"kupiec","name":"<gold>Kupiec Bartłomiej","skin":"","world":"world",
         "x":52.5,"y":64.0,"z":-111.5,"yaw":0.0,"pitch":0.0,"entity":"13c743b5-7cd8-4c82-9fe5-1902ddde41f5",
         "appearance":"c4f223f3-d421-4a22-bca5-4ad81c50a637"}""";

    @Test
    void oldMerchantLoadsWithDefaultTypeAndNoStall() {
        final NpcRecord r = NpcRecord.fromJson(JsonParser.parseString(PRODUCTION_MERCHANT).getAsJsonObject());
        assertEquals(NpcType.MERCHANT, r.type());
        assertNull(r.merchantType(), "default type from config (ogolny)");
        assertNull(r.stall());
        final JsonObject back = r.toJson();
        assertFalse(back.has("merchantType"));
        assertFalse(back.has("stall"));
        assertEquals(JsonParser.parseString(PRODUCTION_MERCHANT), back, "unchanged file for an untouched merchant");
    }

    @Test
    void typeAndStallSurviveARestart() {
        final NpcRecord r = NpcRecord.fromJson(JsonParser.parseString(PRODUCTION_MERCHANT).getAsJsonObject());
        r.merchantType = "kowal";
        r.stall = new NpcRecord.Stall(52.5, 64.0, -112.25, 180f);
        final NpcRecord again = NpcRecord.fromJson(JsonParser.parseString(r.toJson().toString()).getAsJsonObject());
        assertEquals("kowal", again.merchantType());
        assertEquals(new NpcRecord.Stall(52.5, 64.0, -112.25, 180f), again.stall());
    }
}
