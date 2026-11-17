// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bootstrap;

import java.util.*;
import pl.medivalrpg.turnieje.arena.*;

/** Pure, deliberately bounded maintenance of ONE existing royal arena. Not a world editor. */
public final class BootstrapPlan {
    public static final UUID OWNER=UUID.fromString("9d1fe36e-4852-4d18-9b2a-a89146f43e66");
    public static final Box NATIVE=new Box("world",4933,90,892,4955,97,910);
    public record Cell(int x,int y,int z) {}
    public record Expected(int x,int y,int z,String state) { public Cell cell(){return new Cell(x,y,z);} }
    public record Change(Cell cell,String before,String after) {}
    public static final class Request {
        public boolean enabled;
        public String id;
        public UUID country;
        public String world;
        public int blocksPerTick=8;
        public List<Expected> expectedBlocks;
    }
    public enum Phase { PREPARED, APPLYING, REGISTERING, DONE, BLOCKED }
    public static final class Progress {
        public String fingerprint;
        public Phase phase=Phase.PREPARED;
        public int intentThrough;
        public String reason="";
    }
    private final Request request;
    private final Map<Cell,String> baseline;
    private final List<Change> changes;
    public BootstrapPlan(Request r) {
        if(r==null || !"krolewska".equals(r.id) || !OWNER.equals(r.country) || !"world".equals(r.world)
            || r.blocksPerTick<1 || r.blocksPerTick>16 || r.expectedBlocks==null) throw new IllegalArgumentException("Niepoprawny bootstrap areny królewskiej.");
        request=r; baseline=new LinkedHashMap<>();
        for(Expected e:r.expectedBlocks) {
            if(e==null || e.state==null || e.state.isBlank() || e.x<4933 || e.x>4955 || e.z<892 || e.z>910 || e.y<89 || e.y>97
                || baseline.putIfAbsent(e.cell(),e.state)!=null) throw new IllegalArgumentException("Niepoprawny snapshot areny.");
        }
        if(baseline.size()!=23*19*9) throw new IllegalArgumentException("Wymagany pełny snapshot 3933 bloków, Y89..97.");
        changes=new ArrayList<>();
        for(int y=89;y<=90;y++) for(int z=897;z<=905;z++) for(int x=4938;x<=4950;x++) {
            Cell c=new Cell(x,y,z); String before=baseline.get(c),after=y==89?"minecraft:stone_bricks":"minecraft:sand";
            if(!before.equals(after) && !replaceable(before)) throw new IllegalArgumentException("Chroniony blok w podłodze: "+c+" "+before);
            changes.add(new Change(c,before,after));
        }
        for(int z=897;z<=905;z++) for(int x=4938;x<=4950;x++) for(int y=91;y<=92;y++)
            if(!air(baseline.get(new Cell(x,y,z)))) throw new IllegalArgumentException("Brak dwóch bloków powietrza nad boiskiem.");
        if(!"minecraft:stone_bricks".equals(baseline.get(new Cell(4936,92,907)))
            || !air(baseline.get(new Cell(4936,93,907))) || !air(baseline.get(new Cell(4936,94,907))))
            throw new IllegalArgumentException("Widownia nie jest bezpieczną istniejącą platformą.");
    }
    public Request request(){return request;}
    public Map<Cell,String> baseline(){return Collections.unmodifiableMap(baseline);}
    public List<Change> changes(){return Collections.unmodifiableList(changes);}
    public static boolean air(String state){return Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air").contains(state);}
    public static boolean replaceable(String state) {
        if(state==null)return false;
        String n=state.split("\\[",2)[0];
        return air(n) || n.endsWith("_ore") || Set.of("minecraft:water","minecraft:stone","minecraft:dirt","minecraft:grass_block",
            "minecraft:coarse_dirt","minecraft:rooted_dirt","minecraft:sand","minecraft:red_sand","minecraft:gravel",
            "minecraft:andesite","minecraft:granite","minecraft:diorite","minecraft:deepslate","minecraft:tuff","minecraft:calcite").contains(n);
    }
    public boolean accepts(Cell cell,String actual,boolean tile,Progress progress) {
        if(tile)return false;
        String original=baseline.get(cell);
        if(original==null)return false;
        if(original.equals(actual))return true;
        if(progress==null)return false;
        for(int i=0;i<Math.min(progress.intentThrough,changes.size());i++) {
            Change c=changes.get(i);
            if(c.cell.equals(cell) && c.after.equals(actual))return true;
        }
        return false;
    }
    public static boolean ownership(UUID country,Collection<UUID> owners) {
        return OWNER.equals(country) && owners.size()==4 && owners.stream().allMatch(OWNER::equals);
    }
    public static boolean progressValid(Progress p,String fingerprint,int size) {
        return p!=null && p.phase!=null && Objects.equals(p.fingerprint,fingerprint) && p.intentThrough>=0 && p.intentThrough<=size
            && ((p.phase!=Phase.DONE && p.phase!=Phase.REGISTERING) || p.intentThrough==size);
    }
    public Arena arena() {
        Arena a=new Arena(request.id,ArenaType.TURNIEJ,NATIVE);
        a.country=OWNER;a.mounted=false;a.gear=GearMode.KIT;
        a.spawns=List.of(new Pos("world",4940.5,91,899.5,-90,0),new Pos("world",4948.5,91,899.5,90,0));
        a.spectators=new Pos("world",4936.5,93,907.5,-126.87f,12);return a;
    }
}
