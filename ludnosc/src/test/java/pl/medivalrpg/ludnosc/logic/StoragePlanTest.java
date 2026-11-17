// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StoragePlanTest {
    private static StoragePlan.Stack s(String key,int n) { return new StoragePlan.Stack(key,n,64); }
    private static long count(List<StoragePlan.Stack> items,String key) { return items.stream().filter(Objects::nonNull).filter(s -> s.key().equals(key)).mapToLong(StoragePlan.Stack::amount).sum(); }
    @Test void fullChestDoesNotAcceptOrDestroyAnyPartOfAnOreBatch() {
        var before=List.of(s("stone",64),s("diamond",64));
        assertTrue(StoragePlan.plan(before,List.of(s("diamond",1),s("raw_iron",3)),List.of()).isEmpty());
        assertEquals(64,count(before,"diamond")); assertEquals(0,count(before,"raw_iron"));
    }
    @Test void freeSpaceResumesWithoutAnyOverflowBuffer() {
        var blocked=List.of(s("raw_iron",64));
        assertTrue(StoragePlan.plan(blocked,List.of(s("raw_iron",3)),List.of()).isEmpty());
        var resumed=StoragePlan.plan(List.of(s("raw_iron",61)),List.of(s("raw_iron",3)),List.of()).orElseThrow();
        assertEquals(64,count(resumed,"raw_iron"));
    }
    @Test void outputsUsePartialStacksAndThenEmptySlotsWithoutLosingMetadata() {
        var before=Arrays.asList(s("raw_iron:plain",62),s("raw_iron:named",60),null);
        var after=StoragePlan.plan(before,List.of(s("raw_iron:plain",6)),List.of()).orElseThrow();
        assertEquals(68,count(after,"raw_iron:plain")); assertEquals(60,count(after,"raw_iron:named"));
        assertEquals(62,before.getFirst().amount()); assertNull(before.getLast());
    }
    @Test void entireBatchIsRefusedWhenOnlyTheFirstOreWouldFit() {
        var before=Arrays.asList(s("coal",63),s("stone",64));
        assertTrue(StoragePlan.plan(before,List.of(s("coal",1),s("diamond",1)),List.of()).isEmpty());
        assertEquals(63,count(before,"coal"));
    }
    @Test void exactlyOneTorchIsConsumedOnlyByASuccessfulPlan() {
        var before=Arrays.asList(s("torch",3),s("cobblestone",63));
        var after=StoragePlan.plan(before,List.of(s("cobblestone",1)),List.of(s("torch",1))).orElseThrow();
        assertEquals(2,count(after,"torch")); assertEquals(3,count(before,"torch"));
        assertTrue(StoragePlan.plan(before,List.of(s("diamond",1)),List.of(s("torch",1))).isEmpty());
        assertEquals(3,count(before,"torch"));
    }
    @Test void noTorchNeverAwardsDropsOrConsumesTheSourcePlan() {
        var before=Arrays.<StoragePlan.Stack>asList(null,null);
        assertTrue(StoragePlan.plan(before,List.of(s("diamond",1)),List.of(s("torch",1))).isEmpty());
        assertNull(before.getFirst());
    }
    @Test void maxStackSizeIsRespectedForEveryMaterial() {
        var sixteen=new StoragePlan.Stack("special",15,16);
        var after=StoragePlan.plan(Arrays.asList(sixteen,null),List.of(new StoragePlan.Stack("special",3,16)),List.of()).orElseThrow();
        assertEquals(16,after.getFirst().amount()); assertEquals(2,after.getLast().amount());
    }
    @Test void randomizedPlansConserveEveryKindOrLeaveInputUntouched() {
        var random=new Random(20261028);
        for(int t=0;t<5000;t++) {
            var before=new ArrayList<StoragePlan.Stack>();
            for(int i=0;i<27;i++) before.add(random.nextBoolean()?null:s("kind"+random.nextInt(4),1+random.nextInt(64)));
            var original=new ArrayList<>(before); String key="kind"+random.nextInt(4); int amount=1+random.nextInt(64);
            var out=StoragePlan.plan(before,List.of(s(key,amount)),List.of());
            assertEquals(original,before);
            if(out.isPresent()) for(int k=0;k<4;k++) assertEquals(count(before,"kind"+k)+(key.equals("kind"+k)?amount:0),count(out.get(),"kind"+k));
        }
    }
    @Test void twoProducersSharingOneDoubleChestMustUseTheUpdatedSnapshot() {
        var before=new ArrayList<StoragePlan.Stack>(Collections.nCopies(54,null)); before.set(0,s("iron",63));
        var first=StoragePlan.plan(before,List.of(s("iron",3)),List.of()).orElseThrow();
        var second=StoragePlan.plan(first,List.of(s("iron",4)),List.of()).orElseThrow();
        assertEquals(70,count(second,"iron")); assertNotEquals(before,first);
    }
}
