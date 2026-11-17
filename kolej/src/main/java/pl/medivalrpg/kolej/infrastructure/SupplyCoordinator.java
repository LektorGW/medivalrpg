// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import pl.medivalrpg.kolej.freight.FreightRules.Slot;
import static pl.medivalrpg.kolej.infrastructure.InfrastructureState.*;

/** Main-thread transaction state machine. PREPARED must reach disk before account/inventory mutations. */
public final class SupplyCoordinator implements AutoCloseable {
    public record TrainView(String id,String station,boolean eligible,double coal,double water) {}
    public record Snapshot(Fixture fixture,String receipt,List<Slot> slots,Object token) {}
    public interface Port {
        TrainView train(String id,Fixture fixture);
        Snapshot inventory(Fixture fixture); // null = unloaded, never load chunks
        boolean apply(Snapshot before,List<Slot> after,String receipt); // false = no changes; throw = ambiguous
        boolean debit(long cost,String transaction);
        boolean refund(long cost,String transaction);
        boolean credit(TrainView expected,double coal,double water); // false = no changes
        CompletableFuture<Void> confirmEconomy(String transaction);
        CompletableFuture<Void> persistTender(String train,String transaction);
        void audit(Pending transaction,String outcome);
    }
    private final Port port;
    private final CompletableFuture<InfrastructureState> loading;
    private final Function<InfrastructureState,CompletableFuture<Void>> persist;
    private InfrastructureState state;
    private CompletableFuture<Void> writing;
    private Runnable acknowledged;
    private CompletableFuture<Void> boundary;
    private Runnable boundaryAcknowledged;
    private Snapshot source;
    private TrainView target;
    private String heldTrain,failure;
    private boolean closed;
    public SupplyCoordinator(Port port,CompletableFuture<InfrastructureState> loading,Function<InfrastructureState,CompletableFuture<Void>> persist) {
        this.port=port;this.loading=loading;this.persist=persist;
    }
    public InfrastructureState state(){return state;}
    public boolean available(){return !closed && failure==null && state!=null && writing==null && boundary==null && state.pending==null;}
    public String status(){return failure!=null?"BLOKADA: "+failure:closed?"Moduł zamknięty":state==null?"Wczytywanie infrastruktury":writing!=null || boundary!=null?"Trwa zapis infrastruktury/tendera/ekonomii":"Gotowy";}
    public boolean holds(String train){return Objects.equals(heldTrain,train) || state!=null && state.pending!=null && state.pending.train().equals(train);}
    public void requireReady(){if(!available())throw new IllegalArgumentException(status());}
    private void save(Runnable ack){writing=persist.apply(state);acknowledged=ack;}
    private void await(CompletableFuture<Void> durable,Runnable ack){boundary=Objects.requireNonNull(durable);boundaryAcknowledged=ack;}
    private void fail(Throwable e){failure=e.toString();}
    public void register(Fixture f) {
        requireReady();
        if(state.fixtures.size()>=64 || state.fixtures.containsKey(f.id()) || state.fixtures.values().stream().anyMatch(v->v.key().equals(f.key())))
            throw new IllegalArgumentException("Id/kontener zajęte albo limit 64 stanowisk.");
        Snapshot before=port.inventory(f);
        if(before==null || before.receipt()!=null)throw new IllegalArgumentException("Kontener niewczytany lub ma już znacznik infrastruktury.");
        state.fixtures.put(f.id(),f);
        try {
            state.validate();
            if(!port.apply(before,before.slots(),f.receipt())){state.fixtures.remove(f.id());throw new IllegalArgumentException("Zmieniono kontener podczas rejestracji.");}
            save(()->{});
        }catch(RuntimeException e){state.fixtures.remove(f.id());fail(e);throw e;}
    }
    public void remove(String id) {
        requireReady();Fixture f=fixture(id);Snapshot before=checked(f);
        if(before==null)throw new IllegalArgumentException("Wczytaj kontener przed usunięciem stanowiska.");
        // Remove the marker first. A crash before registry save leaves a receipt mismatch and denies all reuse.
        try {
            if(!port.apply(before,before.slots(),null))throw new IllegalArgumentException("Kontener zmienił się podczas usuwania.");
            state.fixtures.remove(id);save(()->{});
        }catch(RuntimeException e){fail(e);throw e;}
    }
    public Fixture fixture(String id) {
        if(state==null)throw new IllegalArgumentException(status());
        Fixture f=state.fixtures.get(id);if(f==null)throw new IllegalArgumentException("Nie ma stanowiska "+id);return f;
    }
    private Snapshot checked(Fixture f) {
        Snapshot s=port.inventory(f);
        if(s!=null && !f.receipt().equals(s.receipt()))throw new IllegalArgumentException("Niezgodny znacznik kontenera "+f.id()+" — wymagana kontrola, bez ponawiania transferu.");
        return s;
    }
    public int stock(Fixture f){Snapshot s=checked(f);return s==null?-1:SupplyRules.available(s.slots(),f.kind());}
    public void request(String fixture,String train,int quantity,long coalPrice,long waterPrice) {
        requireReady();Fixture f=fixture(fixture);TrainView t=port.train(train,f);
        if(t==null || !t.eligible() || !f.station().equals(t.station()))throw new IllegalArgumentException("Skład musi stać przy stanowisku swojej stacji, poza manewrami.");
        if(quantity<1 || quantity>64 || quantity>SupplyRules.room(f.kind(),t.coal(),t.water()))throw new IllegalArgumentException("Partia 1–64 musi w całości mieścić się w tendrze.");
        Snapshot s=checked(f);if(s==null)throw new IllegalArgumentException("Magazyn nie jest wczytany.");
        long cost=SupplyRules.cost(f.kind(),quantity,coalPrice,waterPrice);
        Pending p=new Pending(UUID.randomUUID().toString(),f.id(),train,f.receipt(),UUID.randomUUID().toString(),quantity,cost,t.coal(),t.water(),s.slots(),SupplyRules.consume(s.slots(),f.kind(),quantity));
        state.pending=p;state.phase=Phase.PREPARED;heldTrain=train;source=s;target=t;
        try {save(this::apply);}catch(RuntimeException e){fail(e);throw e;}
    }
    private void finish(Pending p,String outcome) {
        port.audit(p,outcome);state.pending=null;
        save(()->{heldTrain=null;source=null;target=null;});
    }
    private void apply() {
        Pending p=state.pending;Fixture f=fixture(p.fixture());
        TrainView current=port.train(p.train(),f);
        Snapshot now=checked(f);
        if(!Objects.equals(target,current) || now==null || !source.slots().equals(now.slots()) || !Objects.equals(source.receipt(),now.receipt())){finish(p,"ANULOWANO: zmiana pociągu lub magazynu przed obciążeniem");return;}
        if(p.cost()>0 && !port.debit(p.cost(),p.id())){finish(p,"ANULOWANO: brak środków firmy");return;}
        if(p.cost()>0)await(port.confirmEconomy(p.id()),this::transfer);else transfer();
    }
    private void refund(Pending p,String outcome) {
        if(p.cost()==0){finish(p,outcome);return;}
        if(!port.refund(p.cost(),p.id()))throw new IllegalStateException("Nie udało się zwrócić opłaty "+p.id());
        await(port.confirmEconomy(p.id()),()->finish(p,outcome));
    }
    private void transfer() {
        Pending p=state.pending;Fixture f=fixture(p.fixture());
        if(!Objects.equals(target,port.train(p.train(),f))){refund(p,"COFNIĘTO: pociąg zmienił się podczas potwierdzania opłaty");return;}
        // A definite false means no inventory mutation. Exceptions keep PREPARED, with no blind refund/replay.
        if(!port.apply(source,p.after(),p.afterReceipt())) {
            refund(p,"COFNIĘTO: zmiana kontenera, opłata zwrócona");return;
        }
        double coal=p.coal()+(f.kind().coal?p.quantity()*f.kind().units():0);
        double water=p.water()+(f.kind().coal?0:p.quantity()*f.kind().units());
        if(!port.credit(target,coal,water)) {
            Snapshot changed=port.inventory(f);
            if(changed==null || !Objects.equals(changed.receipt(),p.afterReceipt()) || !changed.slots().equals(p.after()) || !port.apply(changed,p.before(),p.beforeReceipt()))
                throw new IllegalStateException("Nie można bezpiecznie cofnąć zapasów "+p.id());
            refund(p,"COFNIĘTO: zmiana tendera, zapasy i opłata zwrócone");return;
        }
        state.phase=Phase.COMMITTING;
        // Keep the durable intent until the train's exact credit AND receipt have reached kolej.json.
        save(()->await(port.persistTender(p.train(),p.id()),()->{
            state.fixtures.put(f.id(),f.receipt(p.afterReceipt()));finish(p,"WYKONANO");
        }));
    }
    public void tick() {
        if(closed || failure!=null)return;
        try {
            if(state==null) {
                if(!loading.isDone())return;
                state=loading.join();state.validate();
                if(state.pending!=null){heldTrain=state.pending.train();throw new IllegalStateException("Niedokończona transakcja "+state.pending.id()+" — sprawdź konto, kontener i tender; automatyczne ponowienie wyłączone.");}
            }
            for(int step=0;step<8;step++) {
                if(writing!=null) {
                    if(!writing.isDone())break;
                    writing.join();writing=null;Runnable ack=acknowledged;acknowledged=null;ack.run();
                } else if(boundary!=null) {
                    if(!boundary.isDone())break;
                    boundary.join();boundary=null;Runnable ack=boundaryAcknowledged;boundaryAcknowledged=null;ack.run();
                } else break;
            }
        }catch(RuntimeException e){fail(e);}
    }
    @Override public void close(){closed=true;}
}
