// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import com.google.gson.Gson;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pl.medivalrpg.armia.model.SoldierRecord;

final class ShipRecordTest {
    @Test void sunkInvalidAndOutOfWorldShipsCannotResurrectOnLoad() {
        final ShipRecord r=FleetStoreTest.ship(UUID.randomUUID());
        assertTrue(r.valid());r.health=0;assertFalse(r.valid());
        r.health=100;r.x=Double.NaN;assertFalse(r.valid());
        r.x=29_999_001;assertFalse(r.valid());
        r.x=0;r.type="steamship";assertFalse(r.valid());
    }

    @Test void legacySoldiersStayOnLandAndNewCargoReferencesRoundTrip() {
        final Gson gson=new Gson();
        final SoldierRecord old=gson.fromJson("{\"type\":\"piechota\"}",SoldierRecord.class);
        assertNull(old.transportShip);
        old.transportShip=UUID.randomUUID();
        assertEquals(old.transportShip,gson.fromJson(gson.toJson(old),SoldierRecord.class).transportShip);
    }
}
