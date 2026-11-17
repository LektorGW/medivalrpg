// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.service.LudnoscData;

class SeasonCodecTest {

    @Test
    void seasonAndPoorHarvestSurviveRestart() {
        final LudnoscData data = new LudnoscData();
        data.setSeasonAnchor(1_800_000_000_000L);
        data.setSeasonSerial(5);
        final UUID country = UUID.randomUUID();
        final CountryState st = data.state(country);
        st.blightUntil = 1_800_000_500_000L;
        st.workFood = 12;
        st.lastMineOre = 40;
        final LudnoscData back = new LudnoscData();
        DataCodec.apply(DataCodec.decode(DataCodec.encode(data)), back);
        assertEquals(1_800_000_000_000L, back.seasonAnchor());
        assertEquals(5, back.seasonSerial());
        assertEquals(1_800_000_500_000L, back.state(country).blightUntil);
        assertEquals(12, back.state(country).workFood);
        assertEquals(40, back.state(country).lastMineOre);
    }

    @Test
    void oldFilesHaveNoSeasonYet() {
        final LudnoscData back = new LudnoscData();
        DataCodec.apply(DataCodec.decode("{\"version\":2,\"lastDaily\":\"2026-10-25\"}"), back);
        assertEquals(0, back.seasonAnchor());
        assertEquals(Long.MIN_VALUE, back.seasonSerial());
        final LudnoscData fresh = new LudnoscData();
        DataCodec.apply(DataCodec.decode(DataCodec.encode(fresh)), back);
        assertEquals(0, back.seasonAnchor(), "unset stays unset");
        assertEquals(Long.MIN_VALUE, back.seasonSerial());
    }
}
