// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.util.*;
import java.util.function.IntConsumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.Plugin;

/** Invalidation sidecar for an incremental physical audit. Never probes, cancels events or changes blocks.
 * The main-thread callback must only enqueue an index; its owner filters index < cursor and rechecks later,
 * after the event has applied and before publishing. Synchronous enqueue avoids a one-tick publication gap.
 * Covers Bukkit events and instrumented Kolej writes; other plugins can bypass both. This is not an atomic
 * world snapshot: the runtime PhysicalRailGuard must keep checking after publication. */
public final class AuditMutationWatch implements Listener,AutoCloseable {
    static final int MAX_VOLUMES=200_000;
    private static final Set<AuditMutationWatch> ACTIVE=new LinkedHashSet<>();
    /** Inclusive vertical interval is [y-1,y+height]; horizontal footprint is [x-1,x+1] × [z-1,z+1]. */
    public record Volume(int x,int y,int z,int height,int index) {
        public Volume {
            if(height<0 || height>64 || index<0 || x==Integer.MIN_VALUE || x==Integer.MAX_VALUE
                || z==Integer.MIN_VALUE || z==Integer.MAX_VALUE || y==Integer.MIN_VALUE || (long)y+height>Integer.MAX_VALUE)
                throw new IllegalArgumentException("Nieprawidłowy obszar nadzoru odbioru toru");
        }
    }
    /** Pure, detached lookup; no World, Block, plugin, scheduler or other Bukkit access. */
    static final class SpatialIndex {
        private record Band(int bottom,int top,int index) {}
        private final UUID world;
        private final Map<Long,List<Band>> columns;
        private final Map<Long,Set<Integer>> chunks;
        SpatialIndex(UUID world,List<Volume> volumes) {
            this.world=Objects.requireNonNull(world,"world");Objects.requireNonNull(volumes,"volumes");
            if(volumes.size()>MAX_VOLUMES)throw new IllegalArgumentException("Limit obszarów nadzoru odbioru toru: "+MAX_VOLUMES);
            Map<Long,List<Band>> byColumn=new HashMap<>();Map<Long,Set<Integer>> byChunk=new HashMap<>();
            for(Volume v:new LinkedHashSet<>(List.copyOf(volumes))) {
                Band band=new Band(v.y()-1,v.y()+v.height(),v.index());
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                    int x=v.x()+dx,z=v.z()+dz;
                    byColumn.computeIfAbsent(key(x,z),ignored->new ArrayList<>()).add(band);
                    byChunk.computeIfAbsent(key(x>>4,z>>4),ignored->new LinkedHashSet<>()).add(v.index());
                }
            }
            byColumn.replaceAll((key,value)->List.copyOf(value));byChunk.replaceAll((key,value)->Collections.unmodifiableSet(value));
            columns=Map.copyOf(byColumn);chunks=Map.copyOf(byChunk);
        }
        Set<Integer> block(UUID world,int x,int y,int z) {
            if(!this.world.equals(world))return Set.of();List<Band> bands=columns.get(key(x,z));if(bands==null)return Set.of();
            Set<Integer> result=new LinkedHashSet<>();for(Band b:bands)if(y>=b.bottom() && y<=b.top())result.add(b.index());
            return Collections.unmodifiableSet(result);
        }
        Set<Integer> chunk(UUID world,int x,int z){return this.world.equals(world)?chunks.getOrDefault(key(x,z),Set.of()):Set.of();}
        private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    }
    private SpatialIndex spatial;
    private final IntConsumer invalidatedIndex;
    private boolean closed;
    public AuditMutationWatch(Plugin plugin,World world,List<Volume> volumes,IntConsumer invalidatedIndex) {
        main();Objects.requireNonNull(plugin,"plugin");Objects.requireNonNull(world,"world");
        this.invalidatedIndex=Objects.requireNonNull(invalidatedIndex,"invalidatedIndex");
        spatial=new SpatialIndex(world.getUID(),volumes);plugin.getServer().getPluginManager().registerEvents(this,plugin);
        ACTIVE.add(this);
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Nadzór odbioru toru wymaga głównego wątku");}
    /** Own Kolej writes bypass Bukkit events with physics disabled. Call synchronously after each real write.
     * Only enqueues affected checks; never reads blocks, schedules work or loads chunks. */
    public static void afterMutation(Block block) {
        main();if(block==null || ACTIVE.isEmpty())return;
        afterMutation(block.getWorld(),block.getX(),block.getY(),block.getZ());
    }
    public static void afterMutation(World world,int x,int y,int z) {
        main();if(world==null || ACTIVE.isEmpty())return;
        UUID id=world.getUID();
        for(var watcher:List.copyOf(ACTIVE))if(!watcher.closed)watcher.emit(watcher.spatial.block(id,x,y,z));
    }
    static void closeAll(){main();for(var watcher:List.copyOf(ACTIVE))watcher.close();}
    private Set<Integer> changes(){main();return new LinkedHashSet<>();}
    private void mark(Set<Integer> changes,Block block) {
        if(closed || block==null)return;
        changes.addAll(spatial.block(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ()));
    }
    private void mark(Set<Integer> changes,BlockState state) {
        if(closed || state==null)return;
        changes.addAll(spatial.block(state.getWorld().getUID(),state.getX(),state.getY(),state.getZ()));
    }
    private void shifted(Set<Integer> changes,Block block,int dx,int dy,int dz) {
        if(closed || block==null)return;
        long x=(long)block.getX()+dx,y=(long)block.getY()+dy,z=(long)block.getZ()+dz;
        if(x<Integer.MIN_VALUE || x>Integer.MAX_VALUE || y<Integer.MIN_VALUE || y>Integer.MAX_VALUE || z<Integer.MIN_VALUE || z>Integer.MAX_VALUE)return;
        changes.addAll(spatial.block(block.getWorld().getUID(),(int)x,(int)y,(int)z));
    }
    private void emit(Set<Integer> changes){if(!closed)changes.forEach(invalidatedIndex::accept);}
    private void changed(Block block){var changes=changes();mark(changes,block);emit(changes);}

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void broken(BlockBreakEvent e){changed(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void placed(BlockPlaceEvent e) {
        var changes=changes();mark(changes,e.getBlock());
        // MultiPlace shares BlockPlace's handler list. Include every replaced state, not only getBlock().
        if(e instanceof BlockMultiPlaceEvent multi)for(var state:multi.getReplacedBlockStates())mark(changes,state);
        emit(changes);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void exploded(BlockExplodeEvent e) {
        var changes=changes();mark(changes,e.getBlock());for(var block:e.blockList())mark(changes,block);emit(changes);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void entityExploded(EntityExplodeEvent e) {
        var changes=changes();for(var block:e.blockList())mark(changes,block);emit(changes);
    }
    private void piston(Block base,BlockFace direction,List<Block> moved) {
        var changes=changes();int dx=direction.getModX(),dy=direction.getModY(),dz=direction.getModZ();
        mark(changes,base);shifted(changes,base,dx,dy,dz);shifted(changes,base,-dx,-dy,-dz);
        // Cover source/destination and head in either API direction convention for sticky retraction.
        // Coordinate arithmetic avoids getRelative()/getBlockAt() and never loads a neighbouring chunk.
        for(var block:moved){mark(changes,block);shifted(changes,block,dx,dy,dz);shifted(changes,block,-dx,-dy,-dz);}
        emit(changes);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void extended(BlockPistonExtendEvent e){piston(e.getBlock(),e.getDirection(),e.getBlocks());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void retracted(BlockPistonRetractEvent e){piston(e.getBlock(),e.getDirection(),e.getBlocks());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void flowed(BlockFromToEvent e) {
        var changes=changes();mark(changes,e.getBlock());mark(changes,e.getToBlock());emit(changes);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void grown(BlockGrowEvent e){changed(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void spread(BlockSpreadEvent e) {
        var changes=changes();mark(changes,e.getBlock());mark(changes,e.getSource());emit(changes);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void formed(BlockFormEvent e){changed(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void faded(BlockFadeEvent e){changed(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void burned(BlockBurnEvent e) {
        var changes=changes();mark(changes,e.getBlock());mark(changes,e.getIgnitingBlock());emit(changes);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void decayed(LeavesDecayEvent e){changed(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void physics(BlockPhysicsEvent e) {
        var changes=changes();mark(changes,e.getBlock());mark(changes,e.getSourceBlock());
        // Bukkit can report only the physics root. Also invalidate its immediate neighbours conservatively.
        for(BlockFace face:List.of(BlockFace.UP,BlockFace.DOWN,BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST)) {
            shifted(changes,e.getBlock(),face.getModX(),face.getModY(),face.getModZ());
            shifted(changes,e.getSourceBlock(),face.getModX(),face.getModY(),face.getModZ());
        }
        emit(changes);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void entityChanged(EntityChangeBlockEvent e){changed(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void structureGrown(StructureGrowEvent e) {
        var changes=changes();for(var state:e.getBlocks())mark(changes,state);emit(changes);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void chunkLoaded(ChunkLoadEvent e) {
        main();if(closed)return;var chunk=e.getChunk();emit(spatial.chunk(e.getWorld().getUID(),chunk.getX(),chunk.getZ()));
    }
    @Override public void close(){main();if(closed)return;closed=true;ACTIVE.remove(this);HandlerList.unregisterAll(this);spatial=null;}
}
