// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import pl.medivalrpg.kolej.data.RailData.Board;

/**
 * Departure boards: one non-persistent TextDisplay per registered board, shown only while a player is within 48 m.
 * No block is placed; the position must be free, supported, outside the track and on land with the carrier's consent.
 */
final class StationBoards implements Listener,AutoCloseable {
    static final int RANGE=48,ROWS=5;
    private final KolejPlugin plugin;
    private final NamespacedKey tag;
    private final Map<String,TextDisplay> shown=new HashMap<>();
    private final Map<String,String> lastText=new HashMap<>();
    private int ticks;
    StationBoards(KolejPlugin plugin){this.plugin=plugin;tag=new NamespacedKey(plugin,"departure-board");Bukkit.getPluginManager().registerEvents(this,plugin);}

    /** Null when a board may stand at x,y,z (the display floats over the block at y). Requires a loaded chunk. */
    static String siteProblem(KolejPlugin plugin,World w,int x,int y,int z){
        if(!w.isChunkLoaded(x>>4,z>>4))return "chunk niewczytany";
        if(!w.getBlockAt(x,y,z).isPassable() || !w.getBlockAt(x,y+1,z).isPassable())return "miejsce zajęte (potrzeba 2 bloków wolnej przestrzeni)";
        if(!w.getBlockAt(x,y-1,z).getType().isSolid())return "brak podłoża";
        if(!plugin.allowed(new Location(w,x,y,z)))return "brak zgody kraju na ten teren";
        for(var s:plugin.graph().segments())for(var c:s.cells())if(Math.abs(c.x()-x)<=1 && Math.abs(c.z()-z)<=1 && Math.abs(c.y()-y)<=3)return "zbyt blisko toru (skrajnia)";
        return null;
    }

    /** Board text: station name and next departures (origin times and planned timed returns). */
    static List<String> lines(Collection<pl.medivalrpg.kolej.data.RailData.Line> lines,String station,String stationName,LocalDateTime now,java.util.function.Function<String,String> names,java.util.function.LongFunction<String> money){
        List<String> out=new ArrayList<>();
        out.add(stationName);
        var rows=DepartureBoard.next(lines,station,now,ROWS);
        if(rows.isEmpty())out.add("Brak odjazdów");
        for(var r:rows)out.add(r.time().format(DateTimeFormatter.ofPattern("HH:mm"))+"  → "+names.apply(r.destination())+"  "+money.apply(r.fare()));
        return out;
    }

    void tick(){
        if(++ticks%20!=0)return;
        Set<String> live=new HashSet<>();
        for(Board b:plugin.data().boards.values()){
            World w=Bukkit.getWorld(b.world());if(w==null || !w.isChunkLoaded(b.x()>>4,b.z()>>4))continue;
            Location at=new Location(w,b.x()+.5,b.y()+1.4,b.z()+.5,b.rotation(),0);
            if(Bukkit.getOnlinePlayers().stream().noneMatch(p->p.getWorld().equals(w) && p.getLocation().distanceSquared(at)<RANGE*RANGE))continue;
            live.add(b.id());
            var node=plugin.graph().node(b.station());
            var rows=lines(plugin.data().lines.values(),b.station(),node==null?b.station():node.name(),LocalDateTime.now(plugin.zone()),id->DepartureBoard.name(plugin,id),plugin.economy()::format);
            String text=String.join("\n",rows);
            TextDisplay d=shown.get(b.id());
            if(d==null || !d.isValid()){
                d=w.spawn(at,TextDisplay.class,e->{e.setPersistent(false);e.setBillboard(Display.Billboard.FIXED);e.setSeeThrough(false);e.setShadowed(true);e.setLineWidth(220);e.setBackgroundColor(Color.fromARGB(200,24,18,10));e.getPersistentDataContainer().set(tag,PersistentDataType.STRING,b.id());});
                shown.put(b.id(),d);lastText.remove(b.id());
            }
            if(!text.equals(lastText.get(b.id()))){
                Component c=Component.text(rows.getFirst(),NamedTextColor.GOLD,TextDecoration.BOLD);
                for(int i=1;i<rows.size();i++)c=c.append(Component.newline()).append(Component.text(rows.get(i),NamedTextColor.WHITE));
                d.text(c);lastText.put(b.id(),text);
            }
        }
        for(var it=shown.entrySet().iterator();it.hasNext();){var e=it.next();if(!live.contains(e.getKey())){e.getValue().remove();lastText.remove(e.getKey());it.remove();}}
    }
    @EventHandler public void chunk(ChunkLoadEvent e){for(Entity en:e.getChunk().getEntities())if(en.getPersistentDataContainer().has(tag,PersistentDataType.STRING) && !shown.containsValue(en))en.remove();}
    @Override public void close(){shown.values().forEach(Entity::remove);shown.clear();}
}
