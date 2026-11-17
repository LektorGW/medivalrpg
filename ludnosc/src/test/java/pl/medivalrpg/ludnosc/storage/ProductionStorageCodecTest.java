// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.*;
import pl.medivalrpg.ludnosc.service.LudnoscData;

class ProductionStorageCodecTest {
    @Test void bindingDoubleChestIdentityAndUnfinishedIntentSurviveRestartWithoutReplayingItems() {
        UUID country=UUID.randomUUID(), mine=UUID.randomUUID(); var data=new LudnoscData();
        var binding=new ProductionBinding(UUID.randomUUID(),List.of(new BlockPos("world",1,60,1),new BlockPos("world",2,60,1)));
        data.state(country).productionStores().put(mine.toString(),binding); data.state(country).productionStores().put("default",binding);
        data.state(country).productionIntents().add(mine.toString()); data.state(country).resources().put("rudy",123L);
        var restored=new LudnoscData(); DataCodec.apply(DataCodec.decode(DataCodec.encode(data)),restored);
        assertEquals(binding,restored.state(country).productionStores().get(mine.toString()));
        assertTrue(restored.state(country).productionIntents().contains(mine.toString()));
        assertEquals(123L,restored.state(country).resources().get("rudy"));
        assertEquals(1,restored.state(country).productionStores().values().stream().map(ProductionBinding::parts).distinct().count());
    }
    @Test void legacySaveWithoutBindingsStaysUnmigratedAndHasNoInventedChest() {
        var data=new LudnoscData(); UUID country=UUID.randomUUID(); data.state(country).resources().put("kamien",777L);
        String json=DataCodec.encode(data).replaceAll("\\\"productionStores\\\": \\{\\},?", "").replaceAll("\\\"productionIntents\\\": \\[\\],?", "");
        var restored=new LudnoscData(); DataCodec.apply(DataCodec.decode(json),restored);
        assertTrue(restored.state(country).productionStores().isEmpty());
        assertTrue(restored.state(country).productionIntents().isEmpty());
        assertEquals(777L,restored.state(country).resources().get("kamien"));
    }
}
