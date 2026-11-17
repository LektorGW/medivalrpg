// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.config.LifeSettings;
import pl.medivalrpg.ludnosc.logic.Workplaces;
import pl.medivalrpg.ludnosc.model.CitizenLife;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.service.LudnoscData;

/** Persistence of the citizens' life and migration of old saves (no life file, entries without the new fields). */
class LifeStoreTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void missingOrEmptyFileIsAnEmptyLife() {
        assertTrue(LifeStore.decode(null).citizens.isEmpty());
        assertTrue(LifeStore.decode("").citizens.isEmpty());
        assertTrue(LifeStore.decode("{}").countries.isEmpty());
    }

    @Test
    void entriesWithoutNewFieldsGetDefaults() {
        final String json = "{\"citizens\":{\"" + A + "\":{\"savings\":12},\"" + B + "\":{}," + "\"" + C
            + "\":{\"bed\":-7,\"savings\":-5}},\"countries\":{\"" + A + "\":{\"huts\":false}}}";
        final LifeStore.Snapshot s = LifeStore.decode(json);
        assertEquals(12, s.citizens.get(A).savings);
        assertEquals(-1, s.citizens.get(B).bed, "no bed field = no bed");
        assertNull(s.citizens.get(B).home);
        assertEquals(-1, s.citizens.get(C).bed);
        assertEquals(0, s.citizens.get(C).savings, "a broken negative share is never kept");
        assertEquals(Boolean.FALSE, s.countries.get(A).huts);
        assertNull(s.countries.get(A).hutLimit, "unset limit = config default");
    }

    @Test
    void roundTripKeepsEverything() {
        final LifeStore.Snapshot s = new LifeStore.Snapshot();
        final CitizenLife l = CitizenLife.of(C);
        l.home = A;
        l.bed = 1;
        l.workplace = B;
        l.work = Workplaces.Kind.KOPALNIA.key();
        l.savings = 77;
        l.partner = B;
        l.mother = A;
        l.born = 123;
        s.citizens.put(A, l);
        final LifeStore.CountryLife c = new LifeStore.CountryLife();
        c.hutLimit = 2;
        s.countries.put(C, c);
        final LifeStore.Snapshot back = LifeStore.decode(LifeStore.encode(s));
        final CitizenLife r = back.citizens.get(A);
        assertEquals(A, r.home);
        assertEquals(1, r.bed);
        assertEquals("kopalnia", r.work);
        assertEquals(77, r.savings);
        assertEquals(B, r.partner);
        assertEquals(123, r.born);
        assertEquals(2, back.countries.get(C).hutLimit);
    }

    @Test
    void rekeyFollowsAPendingCitizenToItsRealEntity() {
        final CitizenLife child = new CitizenLife();
        child.mother = A;
        child.father = B;
        child.rekey(A, C);
        assertEquals(C, child.mother);
        assertEquals(B, child.father);
    }

    @Test
    void oldDataJsonCitizensStillLoad() {
        // data.json written before the citizens' life: the registry record format is unchanged
        final String old = "{\"version\":2,\"citizens\":[{\"entityId\":\"" + A + "\",\"countryId\":\"" + B
            + "\",\"name\":\"Bolesław\",\"profession\":\"minecraft:farmer\",\"adult\":true,\"world\":\"world\",\"x\":1,\"y\":64,"
            + "\"z\":2,\"since\":5,\"missed\":0}]}";
        final LudnoscData data = new LudnoscData();
        DataCodec.apply(DataCodec.decode(old), data);
        final CitizenRecord r = data.citizen(A);
        assertEquals("Bolesław", r.name());
        assertFalse(r.pending());
        assertEquals(1, data.population(B));
    }

    @Test
    void settingsWorkWithoutTheLifeSection() {
        final LifeSettings d = LifeSettings.from(new YamlConfiguration());
        assertTrue(d.enabled());
        assertTrue(d.routine().valid());
        assertEquals(2, d.wageOf(Workplaces.Kind.POLE));
        assertEquals(1, d.mealPrice());
        assertTrue(d.oldAge(), "old age is on (decision 2026-10-29), with a long life");
        assertEquals(35, d.lifespanMinDays());
        assertEquals(50, d.lifespanMaxDays());
        assertTrue(d.taxFromCitizens());
        assertTrue(d.winterIndoors());
        assertTrue(d.huts() && d.felling());
        final YamlConfiguration y = new YamlConfiguration();
        y.set("life.wages.per-day.kopalnia", 7);
        y.set("life.huts.enabled", false);
        y.set("life.routine.work-start", 9000); // invalid order → defaults
        final LifeSettings s = LifeSettings.from(y);
        assertEquals(7, s.wageOf(Workplaces.Kind.KOPALNIA));
        assertFalse(s.huts());
        assertEquals(2000, s.routine().workStart());
    }
}
