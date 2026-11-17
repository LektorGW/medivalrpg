// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DeckRulesTest {
    private static final List<NavalRules.Type> TYPES=List.of(NavalRules.type("transportowiec"),NavalRules.type("karawela"));
    private static void same(final DeckRules.Point expected,final DeckRules.Point actual) {
        assertEquals(expected.x(),actual.x(),1e-8);assertEquals(expected.y(),actual.y(),1e-8);assertEquals(expected.z(),actual.z(),1e-8);
    }

    @Test void localWorldTransformIsInvertibleAcrossHeadingsAndNegativeCoordinates() {
        for(int yaw=-720;yaw<=720;yaw+=5) {
            final var frame=new DeckRules.Frame(-123.25,64.125,987.75,yaw);
            for(final var point:List.of(new DeckRules.Point(1.25,3.5,-4.75),new DeckRules.Point(-2,-1,3),new DeckRules.Point(0,0,0))) {
                same(point,DeckRules.toLocal(frame,DeckRules.toWorld(frame,point)));
                same(point,DeckRules.toWorld(frame,DeckRules.toLocal(frame,point)));
            }
        }
    }

    @Test void positiveNinetyDegreesMatchesTheShipVisualAxes() {
        final var frame=new DeckRules.Frame(10,20,30,90);
        same(new DeckRules.Point(10,20,31),DeckRules.toWorld(frame,new DeckRules.Point(1,0,0)));
        same(new DeckRules.Point(9,20,30),DeckRules.toWorld(frame,new DeckRules.Point(0,0,1)));
    }

    @Test void carryPreservesRelativeFootPositionAcrossYawWrapAndVerticalMovement() {
        final var before=new DeckRules.Frame(10,63,-20,179);
        final var after=new DeckRules.Frame(10.4,63.5,-19.5,-179);
        final var local=new DeckRules.Point(.7,3,-2);
        same(DeckRules.toWorld(after,local),DeckRules.carry(before,after,DeckRules.toWorld(before,local)));
        assertEquals(2,DeckRules.yawDelta(179,-179));assertEquals(-2,DeckRules.yawDelta(-179,179));
        assertEquals(0,DeckRules.yawDelta(0,720));assertEquals(180,DeckRules.yawDelta(0,180));
        assertEquals(-180,DeckRules.yawDelta(0,-180));
    }

    @Test void exactlyTwelveBoundedTilesFitBothVisibleHulls() {
        for(final var type:TYPES) {
            final var tiles=DeckRules.tiles(type);
            assertEquals(12,tiles.size());assertTrue(tiles.size()<=16);
            assertEquals(1+type.halfWidth(),DeckRules.deckHeight(type));
            for(final var tile:tiles) {
                assertTrue(Math.abs(tile.x())+tile.scale()/2<=type.halfWidth());
                assertTrue(Math.abs(tile.z())+tile.scale()/2<=type.halfLength()-.7+1e-9);
                assertEquals(type.halfWidth(),tile.scale());
            }
        }
    }

    @Test void playerBoundingBoxHasPhysicalSupportEverywhereOnDeckAtEveryFiveDegreeHeading() {
        // Shulker collision boxes remain world-axis-aligned when their centres rotate with the ship.
        // Minecraft standing support needs horizontal overlap with the player's 0.6-wide AABB.
        for(final var type:TYPES) for(int yaw=0;yaw<360;yaw+=5) {
            final var frame=new DeckRules.Frame(-40,63,85,yaw);
            final double maxX=type.halfWidth()-.12,maxZ=DeckRules.walkableHalfLength(type);
            final var tiles=DeckRules.tiles(type);
            for(int ix=0;ix<=40;ix++) for(int iz=0;iz<=120;iz++) {
                final var player=DeckRules.toWorld(frame,new DeckRules.Point(-maxX+2*maxX*ix/40,DeckRules.deckHeight(type),-maxZ+2*maxZ*iz/120));
                boolean supported=false;
                for(final var tile:tiles) {
                    final var centre=DeckRules.toWorld(frame,new DeckRules.Point(tile.x(),0,tile.z()));
                    if(Math.abs(player.x()-centre.x())<tile.scale()/2+.3 && Math.abs(player.z()-centre.z())<tile.scale()/2+.3) {supported=true;break;}
                }
                if(!supported) fail("Unsupported deck: "+type.id()+" yaw="+yaw+" ix="+ix+" iz="+iz);
            }
        }
    }

    @Test void deckMembershipDoesNotAttractPeopleFromWaterShoreOrAnotherHeight() {
        for(final var type:TYPES) {
            final var frame=new DeckRules.Frame(120,63,-90,35);
            final double height=DeckRules.deckHeight(type);
            assertTrue(DeckRules.onDeck(type,frame,DeckRules.toWorld(frame,new DeckRules.Point(0,height,0))));
            for(final var local:List.of(new DeckRules.Point(0,0,0),new DeckRules.Point(type.halfWidth(),height,0),
                new DeckRules.Point(0,height,type.halfLength()),new DeckRules.Point(0,height-.201,0),
                new DeckRules.Point(0,height+1.651,0),new DeckRules.Point(0,300,0)))
                assertFalse(DeckRules.onDeck(type,frame,DeckRules.toWorld(frame,local)));
            assertEquals(DeckRules.onDeck(type,frame,new DeckRules.Point(120,63+height,-90)),
                DeckRules.walkableOnDeck(type,frame,new DeckRules.Point(120,63+height,-90)));
        }
    }

    @Test void nonFiniteInputCannotProduceDeckMembershipOrCorruptTransforms() {
        final var type=TYPES.getFirst();final var frame=new DeckRules.Frame(0,63,0,0);final var point=new DeckRules.Point(0,65.5,0);
        for(final double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertFalse(DeckRules.onDeck(type,frame,new DeckRules.Point(invalid,65.5,0)));
            assertFalse(DeckRules.onDeck(type,frame,new DeckRules.Point(0,invalid,0)));
            assertFalse(DeckRules.onDeck(type,new DeckRules.Frame(0,invalid,0,0),point));
            assertThrows(IllegalArgumentException.class,()->DeckRules.toWorld(frame,new DeckRules.Point(0,0,invalid)));
        }
        assertFalse(DeckRules.onDeck(null,frame,point));assertFalse(DeckRules.onDeck(type,null,point));
        assertFalse(DeckRules.onDeck(type,frame,null));assertFalse(DeckRules.onDeck(type,new DeckRules.Frame(0,0,0,Float.NaN),point));
        assertThrows(IllegalArgumentException.class,()->DeckRules.yawDelta(Float.NaN,0));
    }
}
