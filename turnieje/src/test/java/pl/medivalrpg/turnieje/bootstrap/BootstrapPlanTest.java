// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static pl.medivalrpg.turnieje.bootstrap.BootstrapPlan.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pl.medivalrpg.turnieje.arena.*;

class BootstrapPlanTest {
    Request request() {
        Request r=new Request();r.enabled=true;r.id="krolewska";r.country=OWNER;r.world="world";r.expectedBlocks=new ArrayList<>();
        for(int y=89;y<=97;y++)for(int z=892;z<=910;z++)for(int x=4933;x<=4955;x++)
            r.expectedBlocks.add(new Expected(x,y,z,x==4936&&z==907&&y==92?"minecraft:stone_bricks":"minecraft:air"));
        return r;
    }
    Request replace(Request r,Cell cell,String state) {
        r.expectedBlocks.replaceAll(e->e.cell().equals(cell)?new Expected(e.x(),e.y(),e.z(),state):e);return r;
    }
    @Test void boundedPlanOnlyTouchesTwoPitchLayers() {
        BootstrapPlan p=new BootstrapPlan(request());assertEquals(234,p.changes().size());
        assertEquals(3933,p.baseline().size());
        assertEquals(117,p.changes().stream().filter(c->c.cell().y()==89&&c.after().equals("minecraft:stone_bricks")).count());
        assertEquals(117,p.changes().stream().filter(c->c.cell().y()==90&&c.after().equals("minecraft:sand")).count());
        assertTrue(p.changes().stream().allMatch(c->c.cell().x()>=4938&&c.cell().x()<=4950&&c.cell().z()>=897&&c.cell().z()<=905));
        assertTrue(p.changes().subList(0,117).stream().allMatch(c->c.cell().y()==89));
    }
    @Test void registryMatchesWeeklyPackage() {
        Arena a=new BootstrapPlan(request()).arena();assertEquals("krolewska",a.id);assertEquals(ArenaType.TURNIEJ,a.type);
        assertEquals(OWNER,a.country);assertFalse(a.mounted);assertEquals(GearMode.KIT,a.gear);assertTrue(a.ready());
        assertEquals(new Pos("world",4940.5,91,899.5,-90,0),a.spawns.get(0));
        assertEquals(new Pos("world",4948.5,91,899.5,90,0),a.spawns.get(1));
        assertEquals(new Pos("world",4936.5,93,907.5,-126.87f,12),a.spectators);
    }
    @Test void unownedOrForeignChunkDenied() {
        assertTrue(ownership(OWNER,List.of(OWNER,OWNER,OWNER,OWNER)));
        assertFalse(ownership(OWNER,Arrays.asList(OWNER,OWNER,null,OWNER)));
        assertFalse(ownership(OWNER,List.of(OWNER,OWNER,UUID.randomUUID(),OWNER)));
        assertFalse(ownership(OWNER,List.of(OWNER,OWNER,OWNER)));
        assertFalse(ownership(UUID.randomUUID(),List.of(OWNER,OWNER,OWNER,OWNER)));
    }
    @Test void oldAliasAndRelocationDenied() {
        Request r=request();r.id="royal-002-arena";assertThrows(IllegalArgumentException.class,()->new BootstrapPlan(r));
        Request w=request();w.world="world_nether";assertThrows(IllegalArgumentException.class,()->new BootstrapPlan(w));
    }
    @Test void incompleteAndDuplicateSnapshotDenied() {
        Request r=request();r.expectedBlocks.removeLast();assertThrows(IllegalArgumentException.class,()->new BootstrapPlan(r));
        Request d=request();d.expectedBlocks.add(d.expectedBlocks.getFirst());assertThrows(IllegalArgumentException.class,()->new BootstrapPlan(d));
    }
    @Test void workCannotRemoveWallsContainersOrHay() {
        for(String s:List.of("minecraft:chest","minecraft:barrel","minecraft:stone_bricks","minecraft:hay_block","minecraft:oak_log","minecraft:torch","minecraft:oak_planks")) {
            Request r=replace(request(),new Cell(4940,90,899),s);assertThrows(IllegalArgumentException.class,()->new BootstrapPlan(r),s);
        }
    }
    @Test void existingCorrectFoundationIsIdempotent() {
        Request r=replace(request(),new Cell(4940,89,899),"minecraft:stone_bricks");assertDoesNotThrow(()->new BootstrapPlan(r));
    }
    @Test void oreAndWaterAreNaturalFloorNotProduction() {
        assertTrue(replaceable("minecraft:coal_ore"));assertTrue(replaceable("minecraft:deepslate_iron_ore"));assertTrue(replaceable("minecraft:water[level=0]"));
        assertFalse(replaceable("minecraft:deepslate_bricks"));assertFalse(replaceable("minecraft:unknown_material"));
    }
    @Test void allPitchCellsHaveTwoAirBlocksBeforeWork() {
        Request r=replace(request(),new Cell(4950,92,905),"minecraft:stone");assertThrows(IllegalArgumentException.class,()->new BootstrapPlan(r));
    }
    @Test void unsafeSpectatorDenied() {
        Request r=replace(request(),new Cell(4936,92,907),"minecraft:air");assertThrows(IllegalArgumentException.class,()->new BootstrapPlan(r));
    }
    @Test void noTileStateAcceptedEvenMatchingSnapshot() {
        BootstrapPlan p=new BootstrapPlan(request());assertFalse(p.accepts(new Cell(4933,91,892),"minecraft:air",true,null));
    }
    @Test void newPlayerPropOutsidePitchAlsoInvalidatesWholeSnapshot() {
        BootstrapPlan p=new BootstrapPlan(request());assertFalse(p.accepts(new Cell(4934,94,893),"minecraft:torch",false,null));
        assertFalse(p.accepts(new Cell(4934,94,893),"minecraft:stone",false,null));
    }
    @Test void targetBlockWithoutPriorIntentIsNotAdopted() {
        BootstrapPlan p=new BootstrapPlan(request());Change c=p.changes().getFirst();
        assertFalse(p.accepts(c.cell(),c.after(),false,null));
        Progress q=new Progress();assertFalse(p.accepts(c.cell(),c.after(),false,q));
    }
    @Test void crashDuringBatchReplaysOnlyAuthorizedPrefix() {
        BootstrapPlan p=new BootstrapPlan(request());Progress q=new Progress();q.intentThrough=8;q.phase=Phase.APPLYING;
        for(int i=0;i<p.changes().size();i++) {
            Change c=p.changes().get(i);assertEquals(i<8,p.accepts(c.cell(),c.after(),false,q));
            assertTrue(p.accepts(c.cell(),c.before(),false,q));
        }
    }
    @ParameterizedTest @EnumSource(Phase.class) void progressPhaseAndFingerprintAreValidated(Phase phase) {
        Progress p=new Progress();p.fingerprint="known";p.phase=phase;p.intentThrough=234;
        assertTrue(progressValid(p,"known",234));assertFalse(progressValid(p,"changed",234));
        p.intentThrough=235;assertFalse(progressValid(p,"known",234));
    }
    @Test void cannotRegisterIncompleteStatus() {
        Progress p=new Progress();p.fingerprint="known";p.intentThrough=233;p.phase=Phase.REGISTERING;assertFalse(progressValid(p,"known",234));
        p.phase=Phase.DONE;assertFalse(progressValid(p,"known",234));
    }
    @Test void durableIntentSurvivesStorageRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var s=new pl.medivalrpg.turnieje.data.Storage(dir,java.util.logging.Logger.getAnonymousLogger());s.load();
        Progress p=new Progress();p.phase=Phase.APPLYING;p.fingerprint="snapshot";p.intentThrough=16;s.saveArenaBootstrap(p);s.shutdown();
        var restored=new pl.medivalrpg.turnieje.data.Storage(dir,java.util.logging.Logger.getAnonymousLogger());
        try {restored.load();Progress q=restored.loadArenaBootstrap();assertEquals(16,q.intentThrough);assertEquals(Phase.APPLYING,q.phase);assertEquals("snapshot",q.fingerprint);}
        finally {restored.shutdown();}
    }
    @Test void stagedHotbackupManifestPlansExactly231Changes() throws Exception {
        var file=java.nio.file.Path.of("../deploy-wave17/arena/bootstrap-arena.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(file));
        Request r=pl.medivalrpg.turnieje.data.JsonFiles.GSON.fromJson(java.nio.file.Files.readString(file),Request.class);
        BootstrapPlan p=new BootstrapPlan(r);assertEquals(3933,p.baseline().size());
        assertEquals(231,p.changes().stream().filter(c->!c.before().equals(c.after())).count());
        assertEquals("krolewska",p.arena().id);
    }
}
