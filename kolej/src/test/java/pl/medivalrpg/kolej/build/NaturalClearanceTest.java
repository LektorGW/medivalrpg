// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NaturalClearanceTest {
    private static final String LOG="minecraft:oak_log[axis=y]";
    private static final String LEAF="minecraft:oak_leaves[distance=6,persistent=false,waterlogged=false]";
    private static final String GRASS="minecraft:grass_block[snowy=false]";
    private static final UnaryOperator<String> EXACT=UnaryOperator.identity();
    private static BlockOp clear(int x,int y,int z){return BlockOp.clear(x,y,z);}
    private static NaturalClearance.Sample sample(String data){return new NaturalClearance.Sample(data,false,false,true);}
    private static BlockOp plant(int x,int y,int z){return BlockOp.raw(x,y,z,"minecraft:oak_sapling[stage=0]",BlockOp.Mode.PLACE);}
    @Test void permitIsLocalToThisBatchCoordinateAndClearOperationOnly() {
        var op=clear(10,70,-5);var other=clear(11,70,-5);
        var policy=NaturalClearance.forPlan(List.of(op),Map.of("10:70:-5",LOG),Map.of());
        assertEquals(LOG,policy.naturalBefore(op));assertNull(policy.naturalBefore(other));
        assertNull(NaturalClearance.Policy.none().naturalBefore(op));
        assertThrows(IllegalArgumentException.class,()->NaturalClearance.forPlan(List.of(other),Map.of("10:70:-5",LOG),Map.of()));
        for(var wrong:List.of(BlockOp.place(10,70,-5,BlockOp.Part.BED),BlockOp.raw(10,70,-5,"minecraft:air",BlockOp.Mode.CLEAR),BlockOp.fill(10,70,-5,BlockOp.Part.AIR)))
            assertThrows(IllegalArgumentException.class,()->NaturalClearance.forPlan(List.of(wrong),Map.of("10:70:-5",LOG),Map.of()));
    }
    @Test void changedCanonicalFingerprintOrPermissionFailsBeforePreparation() {
        var op=clear(0,70,0);var policy=NaturalClearance.forPlan(List.of(op),Map.of("0:70:0",LOG),Map.of());
        assertNull(policy.naturalProblem(op,sample(LOG),EXACT));
        for(String changed:List.of("minecraft:oak_log[axis=x]","minecraft:birch_log[axis=y]","minecraft:air"))
            assertNotNull(policy.naturalProblem(op,sample(changed),EXACT));
        assertNotNull(policy.naturalProblem(op,new NaturalClearance.Sample(LOG,false,false,false),EXACT));
        assertNotNull(policy.auditProblem(new NaturalClearance.Position(0,70,0),new NaturalClearance.Sample(LOG,false,false,false),EXACT));
    }
    @Test void onlyApprovedNaturalClassesAndUnstrippedNonpersistentTreeStatesAreEligible() {
        assertTrue(NaturalClearance.authorizedNaturalState(LOG));assertTrue(NaturalClearance.authorizedNaturalState(LEAF));
        for(String bad:List.of("minecraft:oak_planks","minecraft:stripped_oak_log[axis=y]","minecraft:oak_wood[axis=y]","minecraft:spruce_log[axis=y]","minecraft:chest[facing=north]","minecraft:oak_log[axis=none]","minecraft:oak_leaves[distance=6,persistent=true,waterlogged=false]","minecraft:oak_leaves[distance=6,persistent=false,waterlogged=true]","minecraft:oak_leaves","minecraft:oak_log[axis=y,axis=x]")) {
            assertFalse(NaturalClearance.authorizedNaturalState(bad),bad);
            assertThrows(IllegalArgumentException.class,()->NaturalClearance.forPlan(List.of(clear(0,70,0)),Map.of("0:70:0",bad),Map.of()));
        }
        for(String special:List.of("sandstone","dripstone_block","pointed_dripstone","mushroom_stem","brown_mushroom_block","red_mushroom_block","kelp","kelp_plant"))
            assertTrue(NaturalClearance.authorizedNaturalState("minecraft:"+special));
    }
    @Test void blockEntitiesAndAdjacentBuiltOrUnknownBlocksVetoTheWholeSite() {
        var op=clear(0,70,0);var policy=NaturalClearance.forPlan(List.of(op),Map.of("0:70:0",LOG),Map.of());
        var tile=new NaturalClearance.Sample(LOG,true,true,true);
        assertNotNull(policy.naturalProblem(op,tile,EXACT));assertNotNull(policy.auditProblem(new NaturalClearance.Position(0,70,0),tile,EXACT));
        var adjacent=new NaturalClearance.Position(8,78,8);
        for(String built:List.of("minecraft:stone_bricks","minecraft:oak_planks","minecraft:stripped_oak_log[axis=y]","minecraft:unknown_material"))assertNotNull(policy.auditProblem(adjacent,sample(built),EXACT));
        assertNull(policy.auditProblem(adjacent,new NaturalClearance.Sample("minecraft:stone",false,true,true),EXACT));
        assertNotNull(policy.auditProblem(adjacent,new NaturalClearance.Sample("minecraft:stone",true,true,true),EXACT));
    }
    @Test void neighborUnionContainsEachVoxelOnceIncludingCubeCornersAndNegativeHeights() {
        var ops=List.of(clear(0,-60,0),clear(1,-60,0));
        var policy=NaturalClearance.forPlan(ops,Map.of("0:-60:0",LOG,"1:-60:0",LOG),Map.of());
        Set<NaturalClearance.Position> seen=new HashSet<>();
        for(var span:policy.auditUnion(-64,320))for(int y=span.minY();y<=span.maxY();y++)assertTrue(seen.add(new NaturalClearance.Position(span.x(),y,span.z())),"duplicate read");
        assertEquals(18*17*13,seen.size());assertTrue(seen.contains(new NaturalClearance.Position(-8,-64,-8)));assertTrue(seen.contains(new NaturalClearance.Position(9,-52,8)));
    }
    @Test void suppliedPreclearOrderSurvivesWhileOrdinaryPlansKeepFoundationSorting() {
        var ops=List.of(clear(0,100,0),clear(0,98,1),clear(0,110,2),clear(0,90,3));
        var natural=Map.of("0:100:0",LEAF,"0:98:1",LEAF,"0:110:2",LOG,"0:90:3",LOG);
        var policy=NaturalClearance.forPlan(ops,natural,Map.of());assertTrue(policy.preserveOrder());
        List<RailwayWorks.Entry> entries=new ArrayList<>();for(var op:ops)entries.add(new RailwayWorks.Entry(op,natural.get(NaturalClearance.Position.of(op).key()),"minecraft:air"));
        NaturalClearance.order(entries,policy.preserveOrder());assertEquals(ops,entries.stream().map(RailwayWorks.Entry::op).toList());
        NaturalClearance.order(entries,false);assertEquals(List.of(90,98,100,110),entries.stream().map(e->e.op().y()).toList());
        assertFalse(NaturalClearance.Policy.none().preserveOrder());
    }
    @Test void soilProofBelongsToExactRawSaplingAndMayNotAuthorizeTimberOrSoilReplacement() {
        var op=plant(10,71,20);var soil=Map.of("10:71:20",GRASS);
        var policy=NaturalClearance.forPlan(List.of(op),Map.of(),soil);assertEquals(GRASS,policy.supportBefore(op));
        assertTrue(policy.auditUnion(-64,320).isEmpty(),"replanting is not a forest-clearance permission");
        assertThrows(IllegalArgumentException.class,()->NaturalClearance.forPlan(List.of(op),Map.of(),Map.of()));
        assertThrows(IllegalArgumentException.class,()->NaturalClearance.forPlan(List.of(clear(10,71,20)),Map.of(),soil));
        assertThrows(IllegalArgumentException.class,()->NaturalClearance.forPlan(List.of(BlockOp.raw(10,71,20,LOG,BlockOp.Mode.PLACE)),Map.of(),soil));
        assertThrows(IllegalArgumentException.class,()->NaturalClearance.forPlan(List.of(op,clear(10,70,20)),Map.of(),soil));
        assertThrows(IllegalArgumentException.class,()->NaturalClearance.forPlan(List.of(op),Map.of(),Map.of("10:71:20","minecraft:farmland[moisture=7]")));
    }
    @Test void soilIsCanonicalizedThenPinnedAndRecheckedEvenForAnAlreadyPlacedTarget() {
        var op=plant(10,71,20);var policy=NaturalClearance.forPlan(List.of(op),Map.of(),Map.of("10:71:20","minecraft:grass_block"));
        UnaryOperator<String> canonical=data->data.equals("minecraft:grass_block")?GRASS:data;
        assertNull(policy.soilProblem(op,sample(GRASS),canonical));
        for(var support:List.of(sample("minecraft:dirt"),new NaturalClearance.Sample(GRASS,true,true,true),new NaturalClearance.Sample(GRASS,false,true,false)))assertNotNull(policy.soilProblem(op,support,canonical));
        var entry=new RailwayWorks.Entry(op,"minecraft:air",op.data(),GRASS);
        assertTrue(NaturalClearance.workerMatches(entry,"minecraft:air",GRASS));assertTrue(NaturalClearance.workerMatches(entry,op.data(),GRASS));
        assertFalse(NaturalClearance.workerMatches(entry,op.data(),"minecraft:dirt"));assertFalse(NaturalClearance.workerMatches(entry,"minecraft:oak_log[axis=y]",GRASS));
    }
    @Test void workerDoesNotBroadenLogOrSaplingFingerprintsThroughFragilePlantDrift() {
        var log=new RailwayWorks.Entry(clear(0,70,0),LOG,"minecraft:air");
        assertTrue(NaturalClearance.workerMatches(log,LOG,null));assertTrue(NaturalClearance.workerMatches(log,"minecraft:air",null));
        assertFalse(NaturalClearance.workerMatches(log,"minecraft:oak_log[axis=x]",null));
        var leaf=new RailwayWorks.Entry(clear(0,70,0),LEAF,"minecraft:air");
        assertTrue(NaturalClearance.workerMatches(leaf,LEAF.replace("distance=6","distance=7"),null));
        assertFalse(NaturalWorkChange.accepts(LOG,"minecraft:air"));assertFalse(NaturalWorkChange.accepts(LEAF,"minecraft:air"));
        var sapling=new RailwayWorks.Entry(plant(0,71,0),"minecraft:short_grass","minecraft:oak_sapling[stage=0]",GRASS);
        assertFalse(NaturalClearance.workerMatches(sapling,"minecraft:air",GRASS));
        var ordinary=new RailwayWorks.Entry(clear(0,71,0),"minecraft:short_grass","minecraft:air");
        assertTrue(NaturalClearance.workerMatches(ordinary,"minecraft:air",null));
    }
    @Test void onlyDistanceMayRecalculateAcrossAuditQuoteAndWorkerForEveryApprovedLeaf() {
        var op=clear(3365,70,775);var position=NaturalClearance.Position.of(op);
        for(String type:List.of("oak","birch","acacia","dark_oak"))for(int pinned=1;pinned<=7;pinned++) {
            String expected="minecraft:"+type+"_leaves[distance="+pinned+",persistent=false,waterlogged=false]";
            var source=Map.of(position.key(),expected);
            var policy=NaturalClearance.forPlan(List.of(op),source,Map.of());
            for(int distance=1;distance<=7;distance++) {
                String live=expected.replace("distance="+pinned,"distance="+distance);
                assertNull(policy.auditProblem(position,sample(live),EXACT),live);
                assertNull(policy.naturalProblem(op,sample(live),EXACT),live);
                // A persisted worker entry uses its own live before, and may see another distance later.
                var entry=new RailwayWorks.Entry(op,live,"minecraft:air");
                assertTrue(NaturalClearance.workerMatches(entry,expected,null),live);
                assertTrue(NaturalClearance.workerMatches(entry,"minecraft:air",null));
            }
            assertEquals(expected,policy.naturalBefore(op));assertEquals(expected,source.get(position.key()));
        }
    }
    @Test void distanceExceptionCannotAuthorizeOtherPropertiesTypesActionsOrFunctionalBlocks() {
        var op=clear(3365,70,775);var position=NaturalClearance.Position.of(op);
        String expected=LEAF.replace("oak_leaves","dark_oak_leaves");
        var policy=NaturalClearance.forPlan(List.of(op),Map.of(position.key(),expected),Map.of());
        var entry=new RailwayWorks.Entry(op,expected,"minecraft:air");
        for(String changed:List.of(expected.replace("dark_oak","oak"),expected.replace("dark_oak","spruce"),
            expected.replace("persistent=false","persistent=true"),expected.replace("waterlogged=false","waterlogged=true"),
            expected.replace("distance=6","distance=0"),expected.replace("distance=6","distance=8"),
            expected.replace("distance=6","distance=06"),expected.replace("distance=6,",""),
            expected.replace("]",",extra=false]"),expected.replace("distance=6","distance=6,distance=7"),
            "minecraft:dark_oak_leaves","minecraft:dark_oak_log[axis=y]","minecraft:chest[facing=north]","minecraft:water")) {
            assertNotNull(policy.auditProblem(position,sample(changed),EXACT),changed);
            assertNotNull(policy.naturalProblem(op,sample(changed),EXACT),changed);
            assertFalse(NaturalClearance.workerMatches(entry,changed,null),changed);
        }
        String drift=expected.replace("distance=6","distance=7");
        for(var blocked:List.of(new NaturalClearance.Sample(drift,true,true,true),new NaturalClearance.Sample(drift,false,true,false))) {
            assertNotNull(policy.auditProblem(position,blocked,EXACT));
            assertNotNull(policy.naturalProblem(op,blocked,EXACT));
        }
        assertNotNull(policy.naturalProblem(op,sample("minecraft:air"),EXACT),"vanished leaves do not become a free quote");
        assertNotNull(policy.auditProblem(position,sample("minecraft:air"),EXACT));
        assertFalse(NaturalClearance.workerMatches(new RailwayWorks.Entry(BlockOp.place(op.x(),op.y(),op.z(),BlockOp.Part.BED),expected,"minecraft:air"),drift,null));
        assertFalse(NaturalClearance.workerMatches(new RailwayWorks.Entry(op,expected,"minecraft:stone"),drift,null));
        assertFalse(NaturalWorkChange.accepts(expected,drift),"ordinary work policy stays unchanged");
    }
    @Test void logsAndSpecialMaterialsRemainExactAcrossAuditQuoteAndWorker() {
        var op=clear(0,70,0);var pos=NaturalClearance.Position.of(op);
        for(var states:List.of(List.of(LOG,"minecraft:oak_log[axis=x]"),List.of(LOG,"minecraft:birch_log[axis=y]"),
            List.of("minecraft:sandstone","minecraft:smooth_sandstone"),List.of("minecraft:brown_mushroom_block[up=true]","minecraft:red_mushroom_block[up=true]"))) {
            var policy=NaturalClearance.forPlan(List.of(op),Map.of(pos.key(),states.getFirst()),Map.of());
            assertNotNull(policy.auditProblem(pos,sample(states.getLast()),EXACT));
            assertNotNull(policy.naturalProblem(op,sample(states.getLast()),EXACT));
            assertFalse(NaturalClearance.workerMatches(new RailwayWorks.Entry(op,states.getFirst(),"minecraft:air"),states.getLast(),null));
        }
    }
    /** railfix3: neighbour-derived mushroom faces are natural drift (natural-drift.json); the block type is not. */
    @Test void mushroomFacesDriftButTypeStaysExact() {
        var op=clear(0,70,0);var pos=NaturalClearance.Position.of(op);
        var policy=NaturalClearance.forPlan(List.of(op),Map.of(pos.key(),"minecraft:brown_mushroom_block[up=true]"),Map.of());
        assertNull(policy.naturalProblem(op,sample("minecraft:brown_mushroom_block[up=false]"),EXACT));
        assertNull(policy.auditProblem(pos,sample("minecraft:brown_mushroom_block[up=false]"),EXACT));
    }
    @Test void readBudgetCaps128SamplesAndTwoMillisecondsIncludingPlantSupportReads() {
        AtomicLong clock=new AtomicLong();var budget=new NaturalClearance.Budget(clock::get);
        for(int plant=0;plant<64;plant++){assertTrue(budget.take());assertTrue(budget.take());}
        assertEquals(128,budget.reads());assertFalse(budget.take());
        budget=new NaturalClearance.Budget(clock::get);assertTrue(budget.take());clock.set(2000000);assertFalse(budget.take());
    }
    @Test void aliasesAndUnrelatedPermitKeysCannotExpandTheGrantedCoordinates() {
        for(String bad:List.of("+1:70:0","01:70:0","-0:70:0","1:70:0 ","1:70:0:1","2147483648:70:0","30000001:70:0"))assertThrows(IllegalArgumentException.class,()->NaturalClearance.Position.parse(bad));
        assertEquals("-1:-64:0",NaturalClearance.Position.parse("-1:-64:0").key());
    }
}
