// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.service.LudnoscData;
import pl.medivalrpg.ludnosc.storage.DataCodec;
import static org.junit.jupiter.api.Assertions.*;

final class DesignOrderRunnerTest {
    @Test void clearingAnObstructionAfterReloadResumesTheSamePlanWithoutSavingItAgain() {
        final CountryState original=new CountryState();
        original.designJson="{\"buildings\":[{\"type\":\"skarbiec\"}]}";
        final AtomicBoolean obstructed=new AtomicBoolean(true);
        final AtomicInteger checks=new AtomicInteger();
        final Function<String,DesignOrderRunner.Attempt> placement=id->{
            checks.incrementAndGet();
            return obstructed.get()?DesignOrderRunner.Attempt.retry("AREA_OBSTRUCTED","minecraft:oak_planks w 10 64 20")
                :DesignOrderRunner.Attempt.ordered();
        };
        assertFalse(DesignOrderRunner.run(original,List.of("skarbiec"),Function.identity(),placement,1_000).ordered());
        final UUID country=UUID.randomUUID();
        final LudnoscData beforeRestart=new LudnoscData();
        beforeRestart.states().put(country,original);
        final String saved=DataCodec.encode(beforeRestart);
        final LudnoscData afterRestart=new LudnoscData();
        DataCodec.apply(DataCodec.decode(saved),afterRestart);
        final CountryState reloaded=afterRestart.state(country);
        assertEquals("minecraft:oak_planks w 10 64 20",reloaded.designFailures.get("skarbiec").reason);
        obstructed.set(false);
        assertFalse(DesignOrderRunner.run(reloaded,List.of("skarbiec"),Function.identity(),placement,30_999).ordered());
        assertEquals(1,checks.get(),"Do not rescan the obstacle on every scheduler turn");
        assertTrue(DesignOrderRunner.run(reloaded,List.of("skarbiec"),Function.identity(),placement,31_000).ordered());
        assertEquals(List.of("skarbiec"),reloaded.designDone);
        assertTrue(reloaded.designFailures.isEmpty());
        assertEquals(original.designJson,reloaded.designJson,"Never require or rewrite the player's plan to retry");
        DesignOrderRunner.run(reloaded,List.of("skarbiec"),Function.identity(),placement,99_000);
        assertEquals(2,checks.get(),"An accepted construction is never ordered a second time");
    }

    @Test void unavailableModernTowerDoesNotBlockTheFollowingMedievalMarket() {
        final CountryState state=new CountryState();
        final List<String> checked=new ArrayList<>();
        final var result=DesignOrderRunner.run(state,List.of("wiezowiec","targ"),Function.identity(),id->{
            checked.add(id);
            final boolean modern=id.equals("wiezowiec");
            final var gate=DesignOrderRunner.gate(modern?4:1,2,true,modern?200_000:5_000,5_000,
                "Wymagana epoka: Nowoczesność","Brak odkrycia","Brak pieniędzy");
            return gate==null?DesignOrderRunner.Attempt.ordered():gate;
        },1_000);
        assertTrue(result.ordered());assertFalse(result.blocked());
        assertEquals(List.of("wiezowiec","targ"),checked);
        assertEquals(List.of("targ"),state.designDone);
        assertFalse(state.designFailures.get("wiezowiec").terminal);
    }

    @Test void unavailableDiscoveryDoesNotReserveTheTreasuryButAnAvailableExpensiveBuildingDoes() {
        final var discovery=DesignOrderRunner.gate(2,2,false,200_000,5_000,"Epoka","Brak odkrycia","Brak pieniędzy");
        assertEquals(DesignOrderRunner.Disposition.RETRY,discovery.disposition());
        assertEquals("Brak odkrycia",discovery.reason());
        final var funds=DesignOrderRunner.gate(2,2,true,200_000,5_000,"Epoka","Brak odkrycia","Brak pieniędzy");
        assertEquals(DesignOrderRunner.Disposition.WAIT_GLOBAL,funds.disposition());
        assertNull(DesignOrderRunner.gate(1,2,true,5_000,5_000,"Epoka","Brak odkrycia","Brak pieniędzy"));
    }

    @Test void legacyPermanentObstructionsMigrateToBoundedRetries() {
        final CountryState state=new Gson().fromJson("{\"designFailed\":[\"dom\"]}",CountryState.class);
        final AtomicInteger checks=new AtomicInteger();
        final Function<String,DesignOrderRunner.Attempt> placement=id->{checks.incrementAndGet();return DesignOrderRunner.Attempt.ordered();};
        DesignOrderRunner.run(state,List.of("dom"),Function.identity(),placement,1_000);
        assertEquals(0,checks.get());assertTrue(state.designFailed.isEmpty());
        assertTrue(DesignOrderRunner.run(state,List.of("dom"),Function.identity(),placement,31_000).ordered());
        assertEquals(List.of("dom"),state.designDone);
    }

    @Test void terminalInvalidOrdersStayRejectedWhileValidOrdersCanProceed() {
        final CountryState state=new CountryState();
        final AtomicInteger invalidChecks=new AtomicInteger();
        final Function<String,DesignOrderRunner.Attempt> placement=id->{
            if(id.equals("unknown")){invalidChecks.incrementAndGet();return DesignOrderRunner.Attempt.rejected("INVALID","Nie ma takiego szablonu");}
            return DesignOrderRunner.Attempt.ordered();
        };
        assertTrue(DesignOrderRunner.run(state,List.of("unknown","dom"),Function.identity(),placement,1_000).ordered());
        final CountryState reloaded=new Gson().fromJson(new Gson().toJson(state),CountryState.class);
        DesignOrderRunner.run(reloaded,List.of("unknown","dom"),Function.identity(),placement,10_000_000);
        assertEquals(1,invalidChecks.get());assertTrue(reloaded.designFailures.get("unknown").terminal);
    }

    @Test void onePassHasABoundedNumberOfAdmissionsAndBackoffCannotGrowPastTenMinutes() {
        final CountryState state=new CountryState();
        final AtomicInteger checks=new AtomicInteger();
        final List<String> orders=IntStream.range(0,100).mapToObj(Integer::toString).toList();
        final Function<String,DesignOrderRunner.Attempt> blocked=id->{checks.incrementAndGet();return DesignOrderRunner.Attempt.retry("AREA_OBSTRUCTED","Przeszkoda");};
        DesignOrderRunner.run(state,orders,Function.identity(),blocked,1_000);
        assertEquals(8,checks.get());
        DesignOrderRunner.run(state,orders,Function.identity(),blocked,1_001);
        assertEquals(16,checks.get(),"Backoff on early positions does not starve later ones");
        long now=2_000;
        final CountryState repeated=new CountryState();
        for(int i=0;i<40;i++) {
            DesignOrderRunner.run(repeated,List.of("dom"),Function.identity(),blocked,now);
            final long next=repeated.designFailures.get("dom").retryAt;
            assertTrue(next-now>=30_000 && next-now<=600_000);
            now=next;
        }
    }

    @Test void globalQueueBackpressureStillStopsThePassWithoutChargingOrPlacingLaterOrders() {
        final CountryState state=new CountryState();
        final List<String> checked=new ArrayList<>();
        final Function<String,DesignOrderRunner.Attempt> queue=id->{checked.add(id);return DesignOrderRunner.Attempt.waiting("TOO_MANY_ORDERS","Pełna kolejka budowy");};
        assertTrue(DesignOrderRunner.run(state,List.of("dom","targ"),Function.identity(),queue,1_000).blocked());
        assertEquals(List.of("dom"),checked);
        assertTrue(DesignOrderRunner.run(state,List.of("dom","targ"),Function.identity(),queue,1_001).blocked());
        assertEquals(List.of("dom"),checked);
        assertTrue(state.designDone.isEmpty());
    }
}
