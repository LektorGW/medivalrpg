// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import java.util.*;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.freight.FreightRules.Slot;

/** Independent, bounded journal. Receipts prevent silently reusing replaced or rolled-back containers. */
public final class InfrastructureState {
    public int version=1;
    public Map<String,Fixture> fixtures=new LinkedHashMap<>();
    public Pending pending;
    public enum Phase { PREPARED, COMMITTING }
    public Phase phase=Phase.PREPARED;
    public record Fixture(String id,String station,String world,int x,int y,int z,int rotation,SupplyKind kind,String block,String receipt) {
        public String key(){return world+":"+x+":"+y+":"+z;}
        public Fixture receipt(String next){return new Fixture(id,station,world,x,y,z,rotation,kind,block,next);}
    }
    public record Pending(String id,String fixture,String train,String beforeReceipt,String afterReceipt,int quantity,long cost,
                          double coal,double water,List<Slot> before,List<Slot> after) {}
    public void validate() {
        if(version!=1 || fixtures==null || fixtures.size()>64)throw new IllegalStateException("Nieprawidłowy rejestr infrastruktury.");
        Set<String> keys=new HashSet<>();
        for(var e:fixtures.entrySet()) {
            Fixture f=e.getValue();
            if(f==null || !RailData.identifier(f.id()) || !e.getKey().equals(f.id()) || f.station()==null || f.station().isBlank()
                || f.rotation()<0 || f.rotation()>3 || f.kind()==null || !Set.of("CHEST","TRAPPED_CHEST","BARREL").contains(f.block())
                || Math.abs((long)f.x())>30_000_000 || Math.abs((long)f.z())>30_000_000 || f.y()<-2048 || f.y()>2048 || !keys.add(f.key()))
                throw new IllegalStateException("Nieprawidłowe stanowisko.");
            UUID.fromString(f.world());UUID.fromString(f.receipt());
        }
        if(pending!=null) {
            if(phase==null)throw new IllegalStateException("Brak fazy transakcji.");
            Pending p=pending;Fixture f=fixtures.get(p.fixture());
            if(f==null || p.train()==null || p.train().isBlank() || !f.receipt().equals(p.beforeReceipt()) || p.beforeReceipt().equals(p.afterReceipt())
                || !SupplyRules.valid(p.coal(),p.water()) || p.quantity()>SupplyRules.room(f.kind(),p.coal(),p.water()) || p.cost()<0
                || p.before()==null || p.after()==null || !SupplyRules.consume(p.before(),f.kind(),p.quantity()).equals(p.after()))
                throw new IllegalStateException("Nieprawidłowy zamiar tankowania.");
            UUID.fromString(p.id());UUID.fromString(p.afterReceipt());
        }
    }
}
