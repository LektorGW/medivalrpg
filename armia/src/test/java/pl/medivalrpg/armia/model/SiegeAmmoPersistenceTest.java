// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.model;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class SiegeAmmoPersistenceTest {
    private final Gson gson=new Gson();

    @Test void loadingOldSoldiersAndBadSelectionsPreservesIdentityAndUsesCompatibleAmmo() {
        final SoldierRecord cannon=this.gson.fromJson("{\"type\":\"armata\",\"morale\":75}",SoldierRecord.class);
        cannon.normalize();
        assertEquals("kula",cannon.siegeAmmo); assertEquals(75,cannon.morale);
        final SoldierRecord catapult=this.gson.fromJson("{\"type\":\"katapulta\",\"siegeAmmo\":\"kartacz\"}",SoldierRecord.class);
        catapult.normalize(); assertEquals("kamien",catapult.siegeAmmo);
        final SoldierRecord infantry=this.gson.fromJson("{\"type\":\"piechota\"}",SoldierRecord.class);
        infantry.normalize(); assertNull(infantry.siegeAmmo);
    }

    @Test void newAmmoRoundTripsThroughTheExistingSoldierJson() {
        final SoldierRecord soldier=new SoldierRecord(java.util.UUID.randomUUID(),"armata",null,8);
        soldier.siegeAmmo="kartacz";
        final SoldierRecord loaded=this.gson.fromJson(this.gson.toJson(soldier),SoldierRecord.class);
        loaded.normalize();
        assertEquals(soldier.id,loaded.id); assertEquals(soldier.country,loaded.country);
        assertEquals("kartacz",loaded.siegeAmmo); assertEquals("armata",loaded.type);
    }
}
