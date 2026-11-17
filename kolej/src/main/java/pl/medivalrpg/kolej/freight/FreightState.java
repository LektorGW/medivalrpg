// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.freight;

import java.util.*;

/** Authoritative freight ledger. Train.cargo is a main-thread projection of this state. */
public final class FreightState {
    public int version=1;
    public Map<String, Warehouse> warehouses=new LinkedHashMap<>();
    public Map<String, Job> jobs=new LinkedHashMap<>();
    public Journal pending;
    public static final class Warehouse {
        public String station, world, receipt;
        public int x,y,z;
        public String key() {return world+":"+x+":"+y+":"+z;}
    }
    public enum Stage { SOURCE, ABOARD, DONE }
    public static final class Job {
        public String train, material, source, destination;
        public int quantity, aboard;
        public long repeatMillis, nextAt;
        public Stage stage=Stage.SOURCE;
        public boolean travelProven;
    }
    public static final class Journal {
        public String id, train, station, direction;
        public int quantity;
        public long preparedAt;
        public List<FreightRules.Slot> before, after;
    }
    public void validate() {
        if(version!=1 || warehouses==null || jobs==null) throw new IllegalStateException("Nieprawidłowa wersja/dane freight.json");
        Set<String> locations=new HashSet<>();
        warehouses.forEach((id,w)->{
            if(w==null || !id.equals(w.station) || w.world==null || w.receipt==null || !locations.add(w.key())) throw new IllegalStateException("Nieprawidłowy/powielony magazyn");
            UUID.fromString(w.world);UUID.fromString(w.receipt);
        });
        jobs.forEach((id,j)->{
            if(j==null || !id.equals(j.train) || !FreightRules.supported(j.material) || j.quantity<=0 || j.aboard<0 || j.aboard>j.quantity || j.stage==null || j.repeatMillis<0 || j.repeatMillis>86_400_000L || j.nextAt<0 || Objects.equals(j.source,j.destination) || !warehouses.containsKey(j.source) || !warehouses.containsKey(j.destination)) throw new IllegalStateException("Nieprawidłowe zlecenie");
            if(j.stage==Stage.ABOARD ? j.aboard!=j.quantity : j.aboard!=0) throw new IllegalStateException("Nieprawidłowy stan ładunku");
        });
        if(pending!=null && (pending.id==null || pending.station==null || !warehouses.containsKey(pending.station) || !jobs.containsKey(pending.train) || pending.quantity<=0 || pending.before==null || pending.after==null)) throw new IllegalStateException("Uszkodzony dziennik");
    }
}
