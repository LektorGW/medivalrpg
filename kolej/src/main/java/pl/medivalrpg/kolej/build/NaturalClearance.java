// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/** Per-plan, coordinate/fingerprint-bound exceptions. Never changes the general construction policy. */
public final class NaturalClearance {
    private NaturalClearance() { }
    public static final int RADIUS=8, MAX_FINGERPRINTS=8192, MAX_PLANTINGS=2048;
    private static final Set<String> LOGS=Set.of("oak_log","birch_log","acacia_log","dark_oak_log");
    private static final Set<String> LEAVES=Set.of("oak_leaves","birch_leaves","acacia_leaves","dark_oak_leaves");
    private static final Set<String> SPECIAL=Set.of("brown_mushroom_block","red_mushroom_block","mushroom_stem","pointed_dripstone","dripstone_block","sandstone","kelp","kelp_plant");
    private static final Set<String> SOIL=Set.of("grass_block","dirt","podzol","coarse_dirt","rooted_dirt");
    private static final Set<String> SAPLINGS=Set.of("oak_sapling","birch_sapling","acacia_sapling","dark_oak_sapling");
    /**
     * Own-structure rebuild, exactly two pairs: the carrier's own masonry track bed only into a realistic sleeper or
     * ballast, and the carrier's own classic oak bridge railing only into a stone-brick parapet wall.
     */
    private static final Set<String> FENCE_KEYS=Set.of("east","north","south","waterlogged","west");
    private static final Set<String> WALL_KEYS=Set.of("east","north","south","up","waterlogged","west");
    public static boolean ownStructureState(String data){
        State s=state(data);if(s==null)return false;
        if(s.type.equals("stone_bricks"))return s.properties.isEmpty();
        return ownFence(s);
    }
    private static boolean ownFence(State s){
        if(s==null || !s.type.equals("oak_fence") || !s.properties.keySet().equals(FENCE_KEYS) || !"false".equals(s.properties.get("waterlogged")))return false;
        for(String side:List.of("east","north","south","west"))if(!Set.of("true","false").contains(s.properties.get(side)))return false;
        return true;
    }
    /** Target allowed for the own block {@code before}; never anything else. */
    public static boolean ownTarget(BlockOp op,String before) {
        if(op.part()!=BlockOp.Part.RAW || op.mode()!=BlockOp.Mode.PLACE)return false;
        State s=state(op.data()),b=state(before);if(s==null || b==null)return false;
        if(b.type.equals("stone_bricks") && b.properties.isEmpty()) {
            if(s.type.equals("gravel"))return s.properties.isEmpty();
            return s.type.equals("stripped_spruce_log") && s.properties.keySet().equals(Set.of("axis")) && Set.of("x","z").contains(s.properties.get("axis"));
        }
        if(ownFence(b)) {
            if(!s.type.equals("stone_brick_wall") || !s.properties.keySet().equals(WALL_KEYS) || !"false".equals(s.properties.get("waterlogged")) || !Set.of("true","false").contains(s.properties.get("up")))return false;
            for(String side:List.of("east","north","south","west"))if(!Set.of("none","low").contains(s.properties.get(side)))return false;
            return true;
        }
        return false;
    }
    /** Fence connections are derived by vanilla from neighbours (like leaf distance); type and water may not drift. */
    static boolean ownFingerprintMatches(String expected,String live){
        if(expected!=null && expected.equals(live))return true;
        State a=state(expected),b=state(live);
        return ownFence(a) && ownFence(b);
    }
    public record Position(int x,int y,int z) {
        public String key(){return x+":"+y+":"+z;}
        public Position below(){return new Position(x,Math.subtractExact(y,1),z);}
        public static Position of(BlockOp op){return new Position(op.x(),op.y(),op.z());}
        public static Position parse(String key) {
            try {
                if(key==null || key.length()>64)throw new IllegalArgumentException();
                String[] parts=key.split(":",-1);if(parts.length!=3)throw new IllegalArgumentException();
                Position p=new Position(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));
                if(!p.key().equals(key) || Math.abs((long)p.x)>30000000 || Math.abs((long)p.z)>30000000 || p.y < -2048 || p.y>2048)throw new IllegalArgumentException();
                return p;
            }catch(RuntimeException ex){throw new IllegalArgumentException("Nieprawidłowa pozycja zezwolenia: "+key);}
        }
    }
    public record Sample(String before,boolean tileEntity,boolean ordinaryNatural,boolean permitted) { }
    public record Span(int x,int z,int minY,int maxY) { }
    private record State(String type,Map<String,String> properties) { }
    private static State state(String data) {
        if(data==null || data.length()>512 || !data.matches("minecraft:[a-z0-9_]+(?:\\[[a-z0-9_=,]+])?"))return null;
        int bracket=data.indexOf('[');String type=data.substring(10,bracket<0?data.length():bracket);
        Map<String,String> properties=new HashMap<>();
        if(bracket>=0)for(String item:data.substring(bracket+1,data.length()-1).split(",",-1)) {
            String[] pair=item.split("=",-1);if(pair.length!=2 || pair[1].isEmpty() || properties.putIfAbsent(pair[0],pair[1])!=null)return null;
        }
        return new State(type,properties);
    }
    /** Exactly the material classes in the approved 4044 fingerprints, with conservative tree states. */
    public static boolean authorizedNaturalState(String data) {
        State s=state(data);if(s==null)return false;
        if(LOGS.contains(s.type))return s.properties.keySet().equals(Set.of("axis")) && Set.of("x","y","z").contains(s.properties.get("axis"));
        if(LEAVES.contains(s.type))return s.properties.keySet().equals(Set.of("distance","persistent","waterlogged")) && "false".equals(s.properties.get("persistent")) && "false".equals(s.properties.get("waterlogged")) && Set.of("1","2","3","4","5","6","7").contains(s.properties.get("distance"));
        return SPECIAL.contains(s.type); // Bukkit must still parse/canonicalize every pinned state before comparison.
    }
    public static boolean allowedSoil(String data){State s=state(data);return s!=null && SOIL.contains(s.type);}
    /**
     * Natural self-change between survey and build (leaf distance, kelp growth, ...): the shared rule of
     * natural-drift.json, on top of the approved material classes. Nothing else may drift.
     */
    static boolean fingerprintMatches(String expected,String current) {
        if(expected!=null && expected.equals(current))return true;
        return authorizedNaturalState(expected) && authorizedNaturalState(current) && NaturalDrift.same(expected,current);
    }
    public static boolean sapling(BlockOp op) {
        State s=state(op.data());
        return op.part()==BlockOp.Part.RAW && op.mode()==BlockOp.Mode.PLACE && s!=null && SAPLINGS.contains(s.type)
            && s.properties.keySet().equals(Set.of("stage")) && "0".equals(s.properties.get("stage"));
    }
    static boolean clearOnly(BlockOp op){return op.part()==BlockOp.Part.AIR && op.mode()==BlockOp.Mode.CLEAR && op.data()==null;}
    public static final class Policy {
        private final Map<Position,String> natural,soil,own;
        private Policy(Map<Position,String> natural,Map<Position,String> soil,Map<Position,String> own){this.natural=Map.copyOf(natural);this.soil=Map.copyOf(soil);this.own=Map.copyOf(own);}
        static Policy none(){return new Policy(Map.of(),Map.of(),Map.of());}
        public String ownBefore(BlockOp op){return own.get(Position.of(op));}
        /** Exact fingerprint of a provenance-bound own block (provenance is checked by the trusted loader). */
        public String ownProblem(BlockOp op,String live,boolean tile,UnaryOperator<String> canonical) {
            String expected=ownBefore(op);if(expected==null)return null;
            if(!ownTarget(op,expected) || !ownStructureState(expected) || tile || !ownFingerprintMatches(canonical.apply(expected),live))return "Zmieniono fingerprint własnej konstrukcji przewoźnika";
            return null;
        }
        public boolean preserveOrder(){return !natural.isEmpty();}
        public String naturalBefore(BlockOp op){return natural.get(Position.of(op));}
        public String supportBefore(BlockOp op){return soil.get(Position.of(op));}
        public String naturalProblem(BlockOp op,Sample live,UnaryOperator<String> canonical) {
            String expected=naturalBefore(op);if(expected==null)return null;
            if(!clearOnly(op) || !authorizedNaturalState(expected) || !authorizedNaturalState(live.before))return "Blok nie ma zezwolenia na naturalny preclear";
            if(live.tileEntity || !live.permitted || !fingerprintMatches(canonical.apply(expected),live.before))return "Zmieniono fingerprint lub ochronę naturalnego bloku";
            return null;
        }
        public String soilProblem(BlockOp op,Sample support,UnaryOperator<String> canonical) {
            String expected=supportBefore(op);if(expected==null)return null;
            if(!sapling(op) || !allowedSoil(expected) || !allowedSoil(support.before) || support.tileEntity || !support.permitted || !NaturalDrift.same(canonical.apply(expected),support.before))return "Zmieniono podłoże lub zgodę na sadzenie";
            return null;
        }
        public String auditProblem(Position position,Sample live,UnaryOperator<String> canonical) {
            if(live.tileEntity || !live.permitted)return "Blok funkcyjny, niezaładowany lub chroniony teren w promieniu 8 m";
            String expected=natural.get(position);
            if(expected!=null && !fingerprintMatches(canonical.apply(expected),live.before))return "Zmieniono fingerprint naturalnego bloku przed wyceną";
            // Surroundings only: vanilla world-generation states from natural-surroundings.json (shared with the plan generator).
            if(!live.ordinaryNatural && !authorizedNaturalState(live.before) && !NaturalSurroundings.permitted(live.before,live.tileEntity))return "Zabudowa lub niezatwierdzony materiał w promieniu 8 m";
            return null;
        }
        /** Union of full 17x17x17 cubes, represented as merged vertical spans, without per-voxel duplication. */
        public List<Span> auditUnion(int minHeight,int maxHeight) {
            if(minHeight>=maxHeight)throw new IllegalArgumentException("Nieprawidłowa wysokość świata");
            Map<Long,TreeMap<Integer,Integer>> columns=new HashMap<>();
            for(Position p:natural.keySet()) {
                if(p.y<minHeight || p.y>=maxHeight)throw new IllegalArgumentException("Zezwolenie poza wysokością świata");
                int low=Math.max(minHeight,p.y-RADIUS),high=Math.min(maxHeight-1,p.y+RADIUS);
                for(int x=p.x-RADIUS;x<=p.x+RADIUS;x++)for(int z=p.z-RADIUS;z<=p.z+RADIUS;z++) {
                    long key=((long)x<<32)|(z&0xffffffffL);var intervals=columns.computeIfAbsent(key,k->new TreeMap<>());
                    int lo=low,hi=high;var floor=intervals.floorEntry(lo);
                    if(floor!=null && floor.getValue()>=lo-1){lo=floor.getKey();hi=Math.max(hi,floor.getValue());intervals.remove(floor.getKey());}
                    var next=intervals.ceilingEntry(lo);
                    while(next!=null && next.getKey()<=hi+1){hi=Math.max(hi,next.getValue());intervals.remove(next.getKey());next=intervals.ceilingEntry(lo);}
                    intervals.put(lo,hi);
                }
            }
            List<Span> spans=new ArrayList<>();long reads=0;
            for(var column:columns.entrySet())for(var interval:column.getValue().entrySet()) {
                reads+=interval.getValue()-interval.getKey()+1;
                if(reads>8000000)throw new IllegalArgumentException("Audyt preclearu przekracza 8 mln bloków. Podziel zlecenie.");
                spans.add(new Span((int)(column.getKey()>>32),(int)(long)column.getKey(),interval.getKey(),interval.getValue()));
            }
            spans.sort(Comparator.comparingInt(Span::x).thenComparingInt(Span::z).thenComparingInt(Span::minY));
            return List.copyOf(spans);
        }
    }
    /** CPU-only validation, run on the preparation executor before any world reads. Maps are local to this batch. */
    public static Policy forPlan(List<BlockOp> ops,Map<String,String> naturalBefore,Map<String,String> plantSoil) {
        return forPlan(ops,naturalBefore,plantSoil,Map.of());
    }
    /** {@code ownBefore}: exact fingerprints of the carrier's own completed masonry; never broadens the natural policy. */
    public static Policy forPlan(List<BlockOp> ops,Map<String,String> naturalBefore,Map<String,String> plantSoil,Map<String,String> ownBefore) {
        if(ownBefore.size()>MAX_FINGERPRINTS)throw new IllegalArgumentException("Za dużo zezwoleń własnej konstrukcji");
        Map<Position,String> own=new HashMap<>();
        ownBefore.forEach((key,value)->{if(!ownStructureState(value))throw new IllegalArgumentException("Niezatwierdzony materiał własnej konstrukcji");own.put(Position.parse(key),value);});
        Set<Position> foundOwn=new HashSet<>();
        for(BlockOp op:ops){Position p=Position.of(op);if(own.containsKey(p)){if(!ownTarget(op,own.get(p)))throw new IllegalArgumentException("Zezwolenie własnej konstrukcji tylko: cegła pod torem → podkład/tłuczeń, dębowa barierka → murek");foundOwn.add(p);}}
        if(!foundOwn.equals(own.keySet()))throw new IllegalArgumentException("Zezwolenie własnej konstrukcji nie należy do operacji tego zlecenia");
        for(String key:naturalBefore.keySet())if(own.containsKey(Position.parse(key)))throw new IllegalArgumentException("Pozycja ma dwa rodzaje zezwoleń");
        for(String key:plantSoil.keySet())if(own.containsKey(Position.parse(key)))throw new IllegalArgumentException("Pozycja ma dwa rodzaje zezwoleń");
        Policy natural=forNatural(ops,naturalBefore,plantSoil);
        return new Policy(natural.natural,natural.soil,own);
    }
    private static Policy forNatural(List<BlockOp> ops,Map<String,String> naturalBefore,Map<String,String> plantSoil) {
        if(naturalBefore.size()>MAX_FINGERPRINTS || plantSoil.size()>MAX_PLANTINGS)throw new IllegalArgumentException("Za dużo zezwoleń preclearu/sadzenia");
        Map<Position,String> natural=new HashMap<>(),soil=new HashMap<>();
        naturalBefore.forEach((key,value)->{if(!authorizedNaturalState(value))throw new IllegalArgumentException("Niezatwierdzony naturalny materiał");natural.put(Position.parse(key),value);});
        plantSoil.forEach((key,value)->{if(!allowedSoil(value))throw new IllegalArgumentException("Niezatwierdzone podłoże sadzonki");soil.put(Position.parse(key),value);});
        Set<Position> foundNatural=new HashSet<>(),foundSoil=new HashSet<>(),opPositions=new HashSet<>();
        for(BlockOp op:ops) {
            Position p=Position.of(op);opPositions.add(p);
            if(natural.containsKey(p)){if(!clearOnly(op))throw new IllegalArgumentException("Zezwolenie działa tylko dla AIR/CLEAR");foundNatural.add(p);}
            if(soil.containsKey(p)){if(!sapling(op))throw new IllegalArgumentException("Podłoże działa tylko dla RAW sadzonki");foundSoil.add(p);}
            if(sapling(op) && !soil.containsKey(p))throw new IllegalArgumentException("Brak dowodu podłoża sadzonki");
        }
        if(!foundNatural.equals(natural.keySet()) || !foundSoil.equals(soil.keySet()))throw new IllegalArgumentException("Zezwolenie nie należy do operacji tego zlecenia");
        for(Position p:soil.keySet())if(opPositions.contains(p.below()))throw new IllegalArgumentException("Zlecenie zmienia również podłoże sadzonki");
        return new Policy(natural,soil,Map.of());
    }
    static void order(List<RailwayWorks.Entry> entries,boolean preserve) {
        if(!preserve)entries.sort(Comparator.comparingInt((RailwayWorks.Entry e)->e.op().y()).thenComparingInt(e->e.target().contains(":rail") || e.target().contains(":lantern")?1:0));
    }
    /** Exact before/target checks, with only the dry nonpersistent leaf distance exception above. */
    static boolean workerMatches(RailwayWorks.Entry entry,String current,String support) {
        if(entry.supportBefore()!=null) {
            if(!sapling(entry.op()) || !allowedSoil(entry.supportBefore()) || !(allowedSoil(support) && NaturalDrift.same(entry.supportBefore(),support)))return false;
            return Objects.equals(current,entry.before()) || Objects.equals(current,entry.target());
        }
        if(authorizedNaturalState(entry.before()))return clearOnly(entry.op()) && "minecraft:air".equals(entry.target()) && (fingerprintMatches(entry.before(),current) || Objects.equals(current,entry.target()));
        // Own railing: only the derived fence connections may change before the parapet replaces it.
        if(ownFence(state(entry.before())) && ownTarget(entry.op(),entry.before()))return ownFingerprintMatches(entry.before(),current) || Objects.equals(current,entry.target());
        return NaturalWorkChange.matches(entry.before(),entry.target(),current);
    }
    /** Shared, testable read/time budget. Each live voxel consumes one slot, including support voxels. */
    static final class Budget {
        private final LongSupplier clock;private final long deadline;private int reads;
        Budget(LongSupplier clock){this.clock=clock;deadline=clock.getAsLong()+2000000L;}
        boolean take(){if(reads>=128 || clock.getAsLong()>=deadline)return false;reads++;return true;}
        int reads(){return reads;}
    }
}
