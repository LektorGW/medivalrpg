// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.LudnoscApi.BuildOrderResult;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.planner.TerrainFootprint;
import pl.medivalrpg.ludnosc.planner.TerrainSelection;

/** Main-thread preview and execution of a rectangle drawn in the country plan editor. */
public final class TerrainLevelService {
    private final LudnoscPlugin plugin;
    private record Proposal(World world, TerrainSelection.Result result) { }
    public TerrainLevelService(final LudnoscPlugin plugin) { this.plugin=plugin; }

    private Proposal compute(final UUID country, final String worldName, final int minX, final int minZ,
                             final int maxX, final int maxZ, final Integer targetY) {
        if (country==null || this.plugin.kraje().getCountry(country).isEmpty()) throw new IllegalArgumentException("Nie ma takiego kraju.");
        if (!TerrainSelection.valid(minX,minZ,maxX,maxZ)) throw new IllegalArgumentException("Zaznaczenie może mieć najwyżej 4096 bloków.");
        final World world=worldName==null ? null : Bukkit.getWorld(worldName);
        if (world==null) throw new IllegalArgumentException("Świat nie jest dostępny.");
        if (targetY!=null && (targetY<world.getMinHeight()+1 || targetY>world.getMaxHeight()-4))
            throw new IllegalArgumentException("Wybrana wysokość wychodzi poza granice świata.");
        final int r=TerrainSelection.RAMP+1;
        for (int cx=(minX-r)>>4;cx<=(maxX+r)>>4;cx++) for (int cz=(minZ-r)>>4;cz<=(maxZ+r)>>4;cz++)
            this.plugin.terrain().request(new ChunkRef(worldName,cx,cz));
        final List<BlockBox> protectedBoxes=new ArrayList<>(this.plugin.construction().takenNear(worldName,
            (minX+maxX)/2,(minZ+maxZ)/2,Math.max(maxX-minX,maxZ-minZ)/2+r+4,Integer.MAX_VALUE));
        final var state=this.plugin.data().stateIfPresent(country);
        final var ownership=this.plugin.terrain().view(worldName,country);
        final var view=this.liveView(world,ownership);
        // Unknown aprons must not be replaced with the selected floor height by Terraform.
        for (int z=minZ-r;z<=maxZ+r;z++) for (int x=minX-r;x<=maxX+r;x++) {
            if (view.own(x,z) && view.height(x,z)==pl.medivalrpg.ludnosc.planner.TerrainView.UNKNOWN)
                throw new IllegalArgumentException("Teren jest jeszcze skanowany. Spróbuj za chwilę.");
        }
        final var result=TerrainSelection.plan(view,minX,minZ,maxX,maxZ,targetY,(x,z)->{
            if (this.plugin.design().untouchable(country,x,z)) return true;
            if (state!=null && state.roads().contains(pl.medivalrpg.ludnosc.planner.RoadPlanner.key(x,z))) return true;
            for (final BlockBox box:protectedBoxes) {
                if (x>=box.minX()-2 && x<=box.maxX()+2 && z>=box.minZ()-2 && z<=box.maxZ()+2) return true;
            }
            return false;
        });
        if (result.targetY()<world.getMinHeight()+1 || result.targetY()>world.getMaxHeight()-4)
            throw new IllegalArgumentException("Docelowy poziom jest zbyt blisko granicy świata. Wybierz inną wysokość.");
        return new Proposal(world,result);
    }

    private record Column(int height,boolean liquid,boolean obstacle) { }

    /** Preview and confirmation read the actual loaded terrain, never a 15-minute-old planning snapshot. */
    private pl.medivalrpg.ludnosc.planner.TerrainView liveView(final World world,
            final pl.medivalrpg.ludnosc.planner.TerrainView ownership) {
        final var columns=new HashMap<Long,Column>();
        final long deadline=System.nanoTime()+15_000_000L;
        return new pl.medivalrpg.ludnosc.planner.TerrainView() {
            private Column sample(int x,int z) {
                final long key=pl.medivalrpg.ludnosc.planner.RoadPlanner.key(x,z);
                final Column cached=columns.get(key); if(cached!=null)return cached;
                if (!world.isChunkLoaded(x>>4,z>>4)) return new Column(UNKNOWN,false,true);
                if ((columns.size()&63)==0 && System.nanoTime()>deadline)
                    throw new IllegalArgumentException("Obszar wymaga zbyt długiego skanu. Podziel zaznaczenie na mniejsze części.");
                int y=Math.min(world.getMaxHeight()-1,world.getHighestBlockYAt(x,z));
                boolean obstacle=false;
                Column result=new Column(UNKNOWN,false,true);
                for(int n=0;n<48 && y>=world.getMinHeight();n++,y--) {
                    final Material m=world.getBlockAt(x,y,z).getType();
                    if(m.isAir()||Tag.LEAVES.isTagged(m)||Tag.REPLACEABLE.isTagged(m)||Tag.FLOWERS.isTagged(m)||m==Material.SNOW)continue;
                    if(m==Material.WATER||m==Material.LAVA){result=new Column(y,true,obstacle);break;}
                    if(Tag.LOGS.isTagged(m)){obstacle=true;continue;}
                    result=new Column(y,false,obstacle||!TerrainCache.natural(m));break;
                }
                columns.put(key,result); return result;
            }
            public int height(int x,int z){return sample(x,z).height();}
            public boolean liquid(int x,int z){return sample(x,z).liquid();}
            public boolean obstacle(int x,int z){return sample(x,z).obstacle();}
            public boolean own(int x,int z){return ownership.own(x,z);}
        };
    }

    public String preview(final UUID country, final String world, final int minX, final int minZ,
                          final int maxX, final int maxZ, final Integer targetY) {
        final JsonObject out=new JsonObject();
        out.addProperty("world",world); out.addProperty("minX",minX); out.addProperty("minZ",minZ);
        out.addProperty("maxX",maxX); out.addProperty("maxZ",maxZ);
        final JsonArray warnings=new JsonArray(); out.add("warnings",warnings);
        try {
            final var result=this.compute(country,world,minX,minZ,maxX,maxZ,targetY).result();
            out.addProperty("targetY",result.targetY());
            out.addProperty("available",!result.columns().isEmpty());
            out.addProperty("columns",((long)maxX-minX+1)*((long)maxZ-minZ+1));
            out.addProperty("changedColumns",result.columns().size()); out.addProperty("blocks",result.blocks());
            out.addProperty("cost",result.columns().stream().filter(c->c[2]!=c[3]).count()*this.plugin.settings().redesignLevelCostPerColumn);
            if (result.protectedColumns()>0) warnings.add("Pomijane chronione kolumny, drogi, budynki, drzewa lub woda: "+result.protectedColumns());
            if (result.excessiveColumns()>0) warnings.add("Pomijane wykopy/nasypy większe niż 6 bloków: "+result.excessiveColumns());
            if (result.columns().isEmpty()) out.addProperty("reason","Teren jest już równy albo chroniony.");
        } catch (final IllegalArgumentException e) {
            out.addProperty("available",false); out.addProperty("reason",e.getMessage());
        }
        return out.toString();
    }

    public BuildOrderResult order(final UUID country, final String world, final int minX, final int minZ,
                                  final int maxX, final int maxZ, final int targetY, final boolean free) {
        final Proposal p;
        try { p=this.compute(country,world,minX,minZ,maxX,maxZ,targetY); }
        catch (final IllegalArgumentException e) { return BuildOrderResult.AREA_OBSTRUCTED; }
        if (p.result().columns().isEmpty()) return BuildOrderResult.AREA_OBSTRUCTED;
        // Include the apron in tickets, progress and cancellation rather than only the user's flat rectangle.
        int x1=minX,z1=minZ,x2=maxX,z2=maxZ;
        for (final int[] col:p.result().columns()) {x1=Math.min(x1,col[0]);z1=Math.min(z1,col[1]);x2=Math.max(x2,col[0]);z2=Math.max(z2,col[1]);}
        return this.plugin.construction().orderManualGrading(country,p.world(),x1,z1,x2,z2,targetY,p.result().columns(),free);
    }
}
