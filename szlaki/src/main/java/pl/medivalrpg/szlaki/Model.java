// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.szlaki;

import java.util.*;

/** Pure domain: no Bukkit classes, one authoritative sealed manifest per shipment. */
final class Model {
    enum Mode { ROAD, SEA }
    enum Phase { FUNDING, LOADING, MOVING, DISABLED, UNLOADING, PAYING, DONE, QUARANTINED }
    record Point(String world, double x, double y, double z) {
        double distance(Point b) {
            if (!world.equals(b.world)) return Double.POSITIVE_INFINITY;
            return Math.sqrt(Math.pow(x-b.x,2)+Math.pow(y-b.y,2)+Math.pow(z-b.z,2));
        }
        int cx() { return ((int)Math.floor(x)) >> 4; }
        int cz() { return ((int)Math.floor(z)) >> 4; }
    }
    record Chest(String world, int x, int y, int z) {
        Point point() { return new Point(world,x+.5,y+.5,z+.5); }
    }
    static final class Route {
        String id;
        UUID manager, country, destinationCountry;
        Mode mode;
        String material;
        int quantity;
        long fee, intervalSeconds, nextDeparture;
        boolean approved, enabled;
        List<Point> points = new ArrayList<>();
        // Each stop is a dwell point. Cargo is loaded at the first and unloaded at the last.
        Set<Integer> stops = new HashSet<>();
        Chest source, destination;
        void validate() {
            if (id == null || !id.matches("[a-z0-9_-]{1,32}") || manager == null || country == null || destinationCountry == null || mode == null)
                throw new IllegalArgumentException("Niepoprawna firma lub identyfikator trasy.");
            if (quantity < 1 || quantity > 1728 || fee < 0 || fee > 1_000_000_000L || intervalSeconds < 60 || intervalSeconds > 604800)
                throw new IllegalArgumentException("Ilość 1–1728, opłata 0–1 mld md, okres 60–604800 s.");
            if (points.size()<2 || points.size()>512 || source==null || destination==null || source.equals(destination))
                throw new IllegalArgumentException("Wymagane 2–512 punktów i dwie różne skrzynie.");
            for (int i=0;i<points.size();i++) {
                Point p=points.get(i);
                if (!Double.isFinite(p.x+p.y+p.z) || (i>0 && (p.distance(points.get(i-1))>8 || p.distance(points.get(i-1))<.1)))
                    throw new IllegalArgumentException("Kolejne punkty muszą dzielić 0,1–8 bloków w jednym świecie.");
            }
            if (source.point().distance(points.getFirst())>5 || destination.point().distance(points.getLast())>5)
                throw new IllegalArgumentException("Skrzynie muszą być do 5 bloków od końców trasy.");
            if (stops.stream().anyMatch(i -> i<0 || i>=points.size())) throw new IllegalArgumentException("Nieznany przystanek.");
        }
        void edited() { approved=false; enabled=false; }
    }
    static final class Job {
        UUID id=UUID.randomUUID();
        String route;
        Phase phase=Phase.FUNDING;
        Point position;
        int node=1, hp=100;
        long waitUntil, lastHit;
        UUID attacker, recipient;
        Chest unloadingChest;
        boolean captured;
        String note="";
        UUID unloadingPlayer;
        String cargoMaterial;
        int cargoQuantity;
        long contractFee;
        UUID payerCountry, payee;
        String escrow() { return "szlaki:"+id; }
        boolean active() { return phase!=Phase.DONE; }
        boolean canCapture(UUID playerCountry, UUID owner, boolean war, double distance) {
            return phase==Phase.DISABLED && hp==0 && playerCountry!=null && !playerCountry.equals(owner)
                && war && distance<=6 && Double.isFinite(distance);
        }
        void hit(UUID enemy, double damage, long now) {
            if ((phase!=Phase.MOVING && phase!=Phase.DISABLED) || enemy==null || !Double.isFinite(damage) || damage<=0 || now-lastHit<750) return;
            hp=Math.max(0,hp-(int)Math.ceil(Math.min(20,damage))); lastHit=now; attacker=enemy;
            if(hp==0) phase=Phase.DISABLED;
        }
    }
    static final class State {
        int schema=1;
        Map<String,Route> routes=new LinkedHashMap<>();
        Map<UUID,Job> jobs=new LinkedHashMap<>();
        void recover() {
            if(schema!=1) throw new IllegalStateException("Nieznany format danych.");
            for(Job j:jobs.values()) if(j.phase==Phase.LOADING || j.phase==Phase.UNLOADING) {
                j.note="Przerwana operacja skrzyni. Wymagany audyt; brak automatycznej wypłaty cargo.";
                j.phase=Phase.QUARANTINED;
            }
        }
    }
    /** Inventory plan is calculated before any mutation; overflow never partially inserts. */
    static int[] take(int[] slots, int amount) {
        if(amount<=0 || Arrays.stream(slots).asLongStream().sum()<amount) return null;
        int[] result=slots.clone();
        for(int i=0;i<result.length && amount>0;i++) { int n=Math.min(result[i],amount); result[i]-=n; amount-=n; }
        return result;
    }
    static int[] put(int[] slots, int amount, int max) {
        if(amount<=0 || max<1) return null;
        int[] result=slots.clone();
        for(int i=0;i<result.length && amount>0;i++) {
            if(result[i]<0) continue; // different item, occupied slot
            int n=Math.min(max-result[i],amount); result[i]+=n; amount-=n;
        }
        return amount==0?result:null;
    }
    enum Settlement { ALREADY_PAID, TRANSFER, QUARANTINE }
    static Settlement settlement(long balance,long fee) {
        if(balance==0) return Settlement.ALREADY_PAID;
        return balance>0 && balance==fee?Settlement.TRANSFER:Settlement.QUARANTINE;
    }
}
