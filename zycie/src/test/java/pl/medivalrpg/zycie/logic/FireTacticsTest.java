// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.zycie.logic.FireIncident.Cell;

class FireTacticsTest {
    @Test void standardWellOpeningIsFoundBeforeItsHiddenLowerWaterLayers() {
        // Live KRÓLESTWO well: base75, water76..79, stone rim80. The former base-3..base+2 scan
        // saw only76/77 behind stone and never the reachable surface79.
        final int base=75;
        Integer exposed=null;
        for(int cursor=0;cursor<FireTactics.wellProbeCount();cursor++) {
            var probe=FireTactics.wellProbe(cursor);
            int y=base+probe.y();
            boolean water=Math.abs(probe.x())<=1&&Math.abs(probe.z())<=1&&y>=76&&y<=79;
            boolean waterAbove=water&&y<79;
            if(water&&!waterAbove) {exposed=y;break;}
        }
        assertEquals(79,exposed);assertTrue(exposed>base+2);
        assertEquals(new FireTactics.Probe(0,4,0),FireTactics.wellProbe(4));
    }
    @Test void wellSearchCoversACompleteBoundedVolumeAndWrapsWithoutDuplicateProbes() {
        var probes=new HashSet<FireTactics.Probe>();
        for(int n=0;n<FireTactics.wellProbeCount();n++) {
            var p=FireTactics.wellProbe(n);assertTrue(probes.add(p));
            assertTrue(Math.abs(p.x())<=4&&Math.abs(p.z())<=4&&p.y()>=-4&&p.y()<=8);
        }
        assertEquals(1053,probes.size());assertEquals(FireTactics.wellProbe(0),FireTactics.wellProbe(1053));
    }
    @Test void aDutyFromAnExtinguishedNeighbouringIncidentDoesNotBlockImmediateFirefighting() {
        assertTrue(FireTactics.reassign(false,true,0,100));
        assertTrue(FireTactics.reassign(false,false,0,100));
        assertFalse(FireTactics.reassign(true,true,100,1),"an active water trip must not alternate between neighbouring incidents");
    }
    @Test void evacuationFollowsCloserDangerWithoutThrashingForAlmostEqualFires() {
        assertTrue(FireTactics.reassign(true,false,100,4));
        assertFalse(FireTactics.reassign(true,false,25,20));
    }
    @Test void aBucketTreatsOnlyNearbyVisibleOwnedFlamesEvenDuringAHundredCellFire() {
        final Cell centre=new Cell(4972,83,979);final List<Cell> large=new ArrayList<>();
        for(int x=0;x<13;x++)for(int z=0;z<9;z++)large.add(new Cell(4972+x,83,979+z));
        assertEquals(117,large.size());
        final Cell behindWall=new Cell(4973,83,979);
        var splash=FireTactics.splash(centre,large,5,c->!c.equals(behindWall));
        assertTrue(splash.contains(centre));assertFalse(splash.contains(behindWall));assertTrue(splash.size()<=5);
        assertFalse(splash.contains(new Cell(4984,83,987)));
        assertTrue(FireTactics.splash(centre,large,5,c->false).isEmpty());
    }
    @Test void fullBucketHasABoundedUsefulSplashAndDoesNotDrainEveryTrackedFlame() {
        var flames=new ArrayList<Cell>();
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)flames.add(new Cell(x,64,z));
        var result=FireTactics.splash(new Cell(0,64,0),flames,5,c->true);
        assertEquals(5,result.size());assertEquals(new Cell(0,64,0),result.getFirst());
        assertEquals(25,flames.size(),"candidate selection does not mutate the incident while checking visibility");
    }
}
