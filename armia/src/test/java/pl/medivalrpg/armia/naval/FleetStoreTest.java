// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class FleetStoreTest {
    @TempDir Path dir;
    private final Logger log=Logger.getAnonymousLogger();
    private final Gson gson=new Gson();

    static ShipRecord ship(final UUID country) {
        final ShipRecord r=new ShipRecord();
        r.id=UUID.randomUUID();r.country=country;r.type="karawela";r.world="world";
        r.x=45;r.y=63;r.z=-80;r.yaw=90;r.health=120;r.sailed=48;r.lastVolleyMillis=100_000;
        return r;
    }

    @Test void recordsAndCargoReferencesSurviveAnOrderedAsyncSave() {
        final ShipRecord r=ship(UUID.randomUUID());
        final FleetStore store=new FleetStore(dir,log);
        assertTrue(store.load().isEmpty());
        store.saveAsync(List.of(r));r.x=99;store.saveAsync(List.of(r));store.close();
        final FleetStore reloaded=new FleetStore(dir,log);
        try {
            final var loaded=reloaded.load();
            assertEquals(1,loaded.size());assertEquals(r.id,loaded.getFirst().id);
            assertEquals(99,loaded.getFirst().x);assertEquals(48,loaded.getFirst().sailed);
            assertEquals(100_000,loaded.getFirst().lastVolleyMillis);
            assertEquals(45,gson.fromJson(read("fleet.json.bak"),ShipRecord[].class)[0].x);
        } finally {reloaded.close();}
    }

    private String read(String file) {
        try{return Files.readString(dir.resolve(file));}catch(Exception e){throw new AssertionError(e);}
    }

    @Test void recoveringFromBackupPreservesBothTheBackupAndBrokenPrimary() throws Exception {
        final String good=gson.toJson(List.of(ship(UUID.randomUUID())));
        Files.writeString(dir.resolve("fleet.json.bak"),good);
        Files.writeString(dir.resolve("fleet.json"),"{broken");
        final FleetStore store=new FleetStore(dir,log);
        final var recovered=store.load();
        store.saveAsync(recovered);store.close();
        assertEquals(good,read("fleet.json.bak"));
        try(final var paths=Files.list(dir)) {
            final Path broken=paths.filter(p->p.getFileName().toString().startsWith("fleet.json.broken-")).findFirst().orElseThrow();
            assertEquals("{broken",Files.readString(broken));
        }
        assertEquals(recovered.getFirst().id,gson.fromJson(read("fleet.json"),ShipRecord[].class)[0].id);
    }

    @Test void invalidNullDuplicateAndOversizedRecordsFailAsAWholeInsteadOfDroppingShips() throws Exception {
        final ShipRecord good=ship(UUID.randomUUID());
        for(final String invalid:List.of("[null]",gson.toJson(List.of(good,good)),
                "[{\"type\":\"karawela\"}]","null")) {
            Files.writeString(dir.resolve("fleet.json"),invalid);
            final FleetStore store=new FleetStore(dir,log);
            try{assertThrows(IllegalStateException.class,store::load);}finally{store.close();}
        }
        final List<ShipRecord> tooMany=new ArrayList<>();
        for(int n=0;n<25;n++)tooMany.add(ship(UUID.randomUUID()));
        Files.writeString(dir.resolve("fleet.json"),gson.toJson(tooMany));
        final FleetStore oversized=new FleetStore(dir,log);
        try{assertThrows(IllegalStateException.class,oversized::load);}finally{oversized.close();}
        final var sameCountry=List.of(ship(good.country),ship(good.country),ship(good.country),ship(good.country));
        Files.writeString(dir.resolve("fleet.json"),gson.toJson(sameCountry));
        final FleetStore excessCountry=new FleetStore(dir,log);
        try{assertThrows(IllegalStateException.class,excessCountry::load);}finally{excessCountry.close();}
    }

    @Test void invalidRecordAlsoFallsBackWithoutLosingTheHealthyFleet() throws Exception {
        final ShipRecord good=ship(UUID.randomUUID());
        Files.writeString(dir.resolve("fleet.json.bak"),gson.toJson(List.of(good)));
        Files.writeString(dir.resolve("fleet.json"),"[null]");
        final FleetStore store=new FleetStore(dir,log);
        try{assertEquals(good.id,store.load().getFirst().id);}finally{store.close();}
    }
}
