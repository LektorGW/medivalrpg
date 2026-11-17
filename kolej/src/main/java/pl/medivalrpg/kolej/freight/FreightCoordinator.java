// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.freight;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import pl.medivalrpg.kolej.data.RailData.Car;
import static pl.medivalrpg.kolej.freight.FreightState.*;

/** Single main-thread state machine; futures are polled, never waited on synchronously. */
public final class FreightCoordinator {
    public record TrainView(String id,String station,boolean stopped,boolean moving,String route,double distance,
                            JourneyProof.Position position,List<Car> cars,Map<String,Integer> cargo,String cargoDestination) {}
    public record Snapshot(Warehouse warehouse,String receipt,List<FreightRules.Slot> slots,Object token) {}
    public interface Port {
        TrainView train(String id);
        JourneyProof.Position station(String id);
        Snapshot inventory(Warehouse warehouse); // null = unloaded; must not load chunks
        boolean apply(Snapshot before,List<FreightRules.Slot> after,String receipt);
        void project(String train,Map<String,Integer> cargo,String destination);
    }
    private final Port port;
    private final Function<FreightState,CompletableFuture<Void>> persist;
    private final CompletableFuture<FreightState> loading;
    private FreightState state;
    private CompletableFuture<Void> writing;
    private Runnable acknowledged;
    private final Map<String,JourneyProof> journeys=new HashMap<>();
    private final Set<String> projected=new HashSet<>();
    private final Map<String,String> verifiedReceipts=new HashMap<>();
    private boolean dirtyProof,closed;
    private long clockNow;
    private String failure;
    public FreightCoordinator(Port port,CompletableFuture<FreightState> loading,Function<FreightState,CompletableFuture<Void>> persist) {this.port=port;this.loading=loading;this.persist=persist;}
    public String status() {return failure!=null?"BLOKADA: "+failure:closed?"Moduł zamknięty":state==null?"Wczytywanie magazynów":writing!=null?"Trwa zapis dziennika":"Gotowy";}
    public boolean blocked() {return failure!=null;}
    public FreightState state() {return state;}
    private void fail(Throwable e) {failure=e.toString();}
    private void ready() {
        if(closed || failure!=null || state==null || writing!=null) throw new IllegalArgumentException(status());
    }
    void requireReady() {ready();}
    void quarantine(Throwable cause) {fail(cause);}
    private void save(Runnable ack) {writing=persist.apply(state);acknowledged=ack;}
    public void close() {closed=true;} // unfinished PREPARED remains on disk and is quarantined at restart
    public void register(Warehouse warehouse) {
        ready();
        if(state.warehouses.containsKey(warehouse.station)) throw new IllegalArgumentException("Stacja ma już magazyn. Nie przepinaj magazynu z ładunkiem.");
        if(state.warehouses.values().stream().anyMatch(w->w.key().equals(warehouse.key())))throw new IllegalArgumentException("Kontener należy już do innej stacji.");
        state.warehouses.put(warehouse.station,warehouse);save(()->{});
    }
    public void order(String train,String material,int quantity,String source,String destination) {
        ready();
        Job old=state.jobs.get(train);
        if(old!=null && old.stage!=Stage.DONE) throw new IllegalArgumentException("Pociąg ma aktywne zlecenie.");
        if(!state.warehouses.containsKey(source)||!state.warehouses.containsKey(destination)||source.equals(destination)) throw new IllegalArgumentException("Zarejestruj dwa różne magazyny.");
        TrainView t=port.train(train);
        if(t==null || !t.cargo.isEmpty())throw new IllegalArgumentException("Pociąg nie istnieje lub ma już ładunek.");
        if(quantity<=0 || quantity>FreightRules.capacity(t.cars,material))throw new IllegalArgumentException("Partia przekracza sloty odpowiednich wagonów albo ilość jest niedodatnia.");
        JourneyProof.Position from=port.station(source),to=port.station(destination);
        if(from==null||to==null||!Double.isFinite(from.distance(to))||from.distance(to)<=.1)throw new IllegalArgumentException("Magazyny muszą mieć różne stacje w jednym świecie.");
        Job j=new Job();j.train=train;j.material=material;j.quantity=quantity;j.source=source;j.destination=destination;
        state.jobs.put(train,j);projected.remove(train);save(()->{});
    }
    public void regularity(String train,int minutes,long now) {
        ready();if(minutes<0 || minutes>1440)throw new IllegalArgumentException("Regularność: 0 (jednorazowo) albo 1–1440 minut.");
        Job j=state.jobs.get(train);if(j==null)throw new IllegalArgumentException("Nie ma zlecenia.");
        j.repeatMillis=minutes*60_000L;
        if(j.stage==Stage.DONE && minutes>0){j.stage=Stage.SOURCE;j.nextAt=now+j.repeatMillis;j.travelProven=false;}
        save(()->{});
    }
    private Snapshot checked(String station) {
        Warehouse w=state.warehouses.get(station);
        if(w==null)throw new IllegalStateException("Brak magazynu "+station);
        Snapshot s=port.inventory(w);
        if(s!=null && !Objects.equals(w.receipt,s.receipt))throw new IllegalStateException("Niezgodny kwit PDC magazynu "+station+"; nie ponawiaj transferu, wymagana kontrola.");
        if(s!=null)verifiedReceipts.put(station,w.receipt);
        return s;
    }
    private boolean at(TrainView t,String station) {
        JourneyProof.Position p=port.station(station);
        return t!=null && p!=null && t.stopped && station.equals(t.station) && t.position!=null && t.position.distance(p)<=2.5;
    }
    private Map<String,Integer> cargo(Job j) {return j.aboard==0?Map.of():Map.of(j.material,j.aboard);}
    private void project(Job j,TrainView t) {
        Map<String,Integer> expected=cargo(j);
        if(!projected.contains(j.train)) {
            // kolej.json can lag behind freight.json; only our exact previous batch may be stale.
            if(!t.cargo.isEmpty() && !t.cargo.equals(Map.of(j.material,j.quantity))) throw new IllegalStateException("Obcy/niezgodny ładunek pociągu "+j.train);
            port.project(j.train,expected,j.aboard==0?null:j.destination);projected.add(j.train);
        } else if(!t.cargo.equals(expected) || !Objects.equals(t.cargoDestination,j.aboard==0?null:j.destination)) throw new IllegalStateException("Ładunek został zmieniony poza modułem freight: "+j.train);
    }
    private void observe() {
        for(Job j:state.jobs.values())if(j.stage==Stage.ABOARD && !j.travelProven) {
            TrainView t=port.train(j.train);if(t==null || t.position==null)continue;
            JourneyProof proof=journeys.computeIfAbsent(j.train,k->new JourneyProof(t.route,t.distance,t.position));
            JourneyProof.Position from=port.station(j.source),to=port.station(j.destination);
            if(from!=null && to!=null && proof.observe(t.moving,t.route,t.distance,t.position,from,to)){j.travelProven=true;dirtyProof=true;}
        }
    }
    public void tick(long now) {
        clockNow=now;
        if(closed || failure!=null)return;
        try {
            if(state==null) {
                if(!loading.isDone())return;
                state=loading.join();state.validate();
                if(state.pending!=null)throw new IllegalStateException("Niedokończona transakcja "+state.pending.id+" — ręczna kontrola inwentarza i ledgeru, brak automatycznego replay.");
            }
            observe();
            if(writing!=null) {
                if(!writing.isDone())return;
                writing.join();writing=null;Runnable ack=acknowledged;acknowledged=null;ack.run();return;
            }
            if(dirtyProof) {dirtyProof=false;save(()->{});return;}
            for(Job j:state.jobs.values()) {
                TrainView t=port.train(j.train);
                if(t==null){if(j.aboard>0)throw new IllegalStateException("Usunięto pociąg z ładunkiem "+j.train);continue;}
                // Projection is bookkeeping only; it must restore mass even while remote chunks
                // are unavailable. Inventory release still requires receipt verification below.
                project(j,t);t=port.train(j.train);
                boolean load=j.stage==Stage.SOURCE;
                Snapshot inv=checked(load?j.source:j.destination);
                if(inv==null)continue;
                // A remote destination need not be loaded for loading. A source receipt verified
                // this boot remains usable after its chunk unloads; restart resets this cache.
                if(j.stage==Stage.ABOARD) {
                    checked(j.source);
                    if(!Objects.equals(verifiedReceipts.get(j.source),state.warehouses.get(j.source).receipt))continue;
                }
                if(j.stage==Stage.DONE || now<j.nextAt || FreightRules.capacity(t.cars,j.material)<j.quantity)continue;
                if(!at(t,load?j.source:j.destination) || !load && !j.travelProven)continue;
                List<FreightRules.Slot> after;
                try{after=FreightRules.plan(inv.slots,j.material,j.quantity,load);}catch(IllegalArgumentException waitForStockOrSpace){continue;}
                Journal journal=new Journal();journal.id=UUID.randomUUID().toString();journal.train=j.train;journal.station=inv.warehouse.station;journal.direction=load?"LOAD":"UNLOAD";journal.quantity=j.quantity;journal.preparedAt=now;journal.before=inv.slots;journal.after=after;
                state.pending=journal;
                save(()->apply(j,inv,after,journal,load));return;
            }
        }catch(Throwable e){fail(e);}
    }
    private void apply(Job j,Snapshot inv,List<FreightRules.Slot> after,Journal journal,boolean load) {
        TrainView t=port.train(j.train);
        // train/inventory may have changed while the durable PREPARED write was in flight
        if(!at(t,journal.station) || t==null || !t.cargo.equals(cargo(j)) || !Objects.equals(t.cargoDestination,j.aboard==0?null:j.destination) || FreightRules.capacity(t.cars,j.material)<j.quantity) {
            state.pending=null;save(()->{});return;
        }
        if(checked(journal.station)==null) {state.pending=null;save(()->{});return;}
        if(!load) {
            checked(j.source);
            if(!Objects.equals(verifiedReceipts.get(j.source),state.warehouses.get(j.source).receipt))throw new IllegalStateException("Brak potwierdzonego kwitu źródła przed wydaniem.");
        }
        if(!port.apply(inv,after,journal.id)) {state.pending=null;save(()->{});return;}
        inv.warehouse.receipt=journal.id;
        verifiedReceipts.put(journal.station,journal.id);
        j.aboard=load?j.quantity:0;
        j.stage=load?Stage.ABOARD:j.repeatMillis>0?Stage.SOURCE:Stage.DONE;
        j.travelProven=false;journeys.remove(j.train);
        if(load)journeys.put(j.train,new JourneyProof(t.route,t.distance,t.position));
        else j.nextAt=j.repeatMillis==0?0:clockNow+j.repeatMillis;
        port.project(j.train,cargo(j),load?j.destination:null);
        state.pending=null;
        save(()->{});
    }
}
