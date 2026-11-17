// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.*;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.logic.StoragePlan;
import pl.medivalrpg.ludnosc.logic.WorkAlerts.Reason;
import pl.medivalrpg.ludnosc.model.*;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/** Physical workplace storage. All Bukkit access and commits run on the main thread. */
public final class ProductionStorageService {
    public record Change(BlockPos pos, String before, String after) {
        public static Change of(Block b, String after) { return new Change(GranaryService.pos(b), b.getBlockData().getAsString(), after); }
    }
    private record Store(ProductionBinding binding, Inventory inventory) { }
    private static final class Scan { long cursor; long retry; }
    private static final class Job {
        Building building; Store store; ItemStack[] before, after; List<Change> changes; Runnable done; List<ItemStack> outputs;
        CompletableFuture<Void> saved; boolean finishing, committed;
    }
    private final LudnoscPlugin plugin;
    private final NamespacedKey tokenKey, countryKey;
    private final Map<UUID, Scan> scans = new HashMap<>();
    private final Map<UUID, String> statuses = new HashMap<>();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> snapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private int scanBudget;
    private long ticks;
    /** Warehouses: every chest and barrel of the building is its store (bbox scan, a separate budget). */
    private final Map<UUID, Scan> warehouseScans = new HashMap<>();
    private final ArrayDeque<UUID> warehouseQueue = new ArrayDeque<>();
    private int warehouseTurn;
    private static final long WAREHOUSE_RESCAN_MILLIS = 5*60_000L;
    /** Bindings of one country looked at by the supply / food projections (was 64 before warehouses had many). */
    private static final int BINDING_BUDGET = 256;
    public ProductionStorageService(LudnoscPlugin plugin) {
        this.plugin = plugin; tokenKey = new NamespacedKey(plugin,"production_store"); countryKey = new NamespacedKey(plugin,"production_country");
    }
    private static void requireMain() { if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Production storage requires main thread"); }
    public static boolean productive(Building b) {
        if(b == null || b.function() == null) return false;
        return switch(b.function()) { case MINE, QUARRY, SAWMILL, FARM, HARBOR, GLASSWORKS, BRICKWORKS, SHEEPFOLD, SMITHY -> true; default -> false; };
    }
    private boolean own(UUID country, BlockPos p) {
        return plugin.kraje().getCountryAt(p.world(), p.x() >> 4, p.z() >> 4).map(k -> country.equals(k.id())).orElse(false);
    }
    private Block block(BlockPos p) {
        World w = Bukkit.getWorld(p.world());
        return w == null || !w.isChunkLoaded(p.x() >> 4,p.z() >> 4) ? null : w.getBlockAt(p.x(),p.y(),p.z());
    }
    private List<BlockPos> parts(Block b) {
        if (!(b.getState(false) instanceof Container c) || !(c instanceof Chest || c instanceof Barrel)) return List.of();
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Chest chest && chest.getType() != Type.SINGLE) {
            var f = chest.getFacing();
            for(int sign:new int[]{-1,1}) {
                BlockPos other = new BlockPos(b.getWorld().getName(),b.getX()-sign*f.getModZ(),b.getY(),b.getZ()+sign*f.getModX());
                if (block(other) == null) return List.of();
            }
        }
        Inventory inv = c.getInventory();
        if (inv.getHolder() instanceof DoubleChest d) {
            final var out = new ArrayList<BlockPos>();
            for (var h : List.of(d.getLeftSide(),d.getRightSide())) {
                if (!(h instanceof Chest half)) return List.of();
                out.add(GranaryService.pos(half.getBlock()));
            }
            return pl.medivalrpg.ludnosc.logic.StorageDiscovery.canonical(out);
        }
        return List.of(GranaryService.pos(b));
    }
    private ProductionBinding register(UUID country, Block b) {
        List<BlockPos> positions = parts(b);
        if (positions.isEmpty() || positions.stream().anyMatch(p -> !own(country,p))) throw new IllegalArgumentException("Kontener musi być załadowany i w całości w kraju.");
        UUID token = null;
        for (var p : positions) {
            if(plugin.supply()!=null && plugin.supply().locked(p)) throw new IllegalArgumentException("Magazyn ma nierozstrzygniętą dostawę.");
            Container c = (Container) block(p).getState();
            if(!namespaceAllowed(c)) throw new IllegalArgumentException("Kontener ma rejestrację/receipt innego pluginu — wybierz osobny magazyn.");
            String owner = c.getPersistentDataContainer().get(countryKey,PersistentDataType.STRING);
            String id = c.getPersistentDataContainer().get(tokenKey,PersistentDataType.STRING);
            if (owner != null && !owner.equals(country.toString())) throw new IllegalArgumentException("Kontener ma rejestrację innego kraju — wymagany audyt.");
            if (id != null) {
                UUID found = UUID.fromString(id);
                if (token != null && !token.equals(found)) throw new IllegalArgumentException("Niezgodne rejestracje połówek skrzyni.");
                token = found;
            }
        }
        if (token == null) token = UUID.randomUUID();
        for (var p : positions) {
            Container c = (Container) block(p).getState();
            c.getPersistentDataContainer().set(tokenKey,PersistentDataType.STRING,token.toString());
            c.getPersistentDataContainer().set(countryKey,PersistentDataType.STRING,country.toString());
            if (!c.update(false,false)) throw new IllegalArgumentException("Kontener zmienił się podczas rejestracji.");
        }
        return new ProductionBinding(token,positions);
    }
    public void bind(UUID country, Building workplace, Block target) {
        requireMain();
        if (workplace != null && (!country.equals(workplace.countryId()) || !productive(workplace)
            && workplace.function()!=BuildingFunction.WAREHOUSE && workplace.function()!=BuildingFunction.GRANARY)) throw new IllegalArgumentException("To nie jest miejsce pracy ani magazyn tego kraju.");
        if (!jobs.isEmpty()) throw new IllegalArgumentException("Trwa zapis produkcji — spróbuj za chwilę.");
        if (workplace != null && !workplace.box().world().equals(target.getWorld().getName())) throw new IllegalArgumentException("Magazyn musi być w świecie miejsca pracy.");
        var stores = plugin.data().state(country).productionStores();
        if (workplace != null && workplace.function()==BuildingFunction.WAREHOUSE) {
            // every container of a warehouse is its store: one more binding, never a replacement
            List<BlockPos> wanted = parts(target);
            if (!wanted.isEmpty()) for (var e : stores.entrySet()) {
                if (e.getValue()!=null && wanted.equals(e.getValue().parts())
                    && workplace.id().toString().equals(pl.medivalrpg.ludnosc.logic.StorageDiscovery.ownerKey(e.getKey())))
                    throw new IllegalArgumentException("Ta skrzynia już należy do tego magazynu.");
            }
            if (pl.medivalrpg.ludnosc.logic.StorageDiscovery.keysOf(stores.keySet(),workplace.id().toString()).size()>=warehouseCap())
                throw new IllegalArgumentException("Magazyn ma już " + warehouseCap() + " powiązanych skrzyń i beczek.");
            var binding = register(country,target);
            stores.put(pl.medivalrpg.ludnosc.logic.StorageDiscovery.freeKey(stores.keySet(),workplace.id().toString()),binding);
        } else {
            var binding = register(country,target);
            stores.put(workplace == null ? "default" : workplace.id().toString(),binding);
        }
        plugin.data().markDirty(); plugin.saveRoyalCheckpoint();
    }
    private int warehouseCap() { return Math.max(1,Math.min(64,plugin.getConfig().getInt("production.warehouse-containers",40))); }
    /** Bindings of a building (its id and, for a warehouse, id#n), the main one first. */
    private List<ProductionBinding> bindingsOf(UUID country, Building b) {
        var state=plugin.data().stateIfPresent(country); if(state==null) return List.of();
        final List<ProductionBinding> out=new ArrayList<>();
        for(String k:pl.medivalrpg.ludnosc.logic.StorageDiscovery.keysOf(state.productionStores().keySet(),b.id().toString())) {
            var binding=state.productionStores().get(k); if(binding!=null) out.add(binding);
        }
        return out;
    }
    private static boolean room(Store store) {
        return Arrays.stream(store.inventory().getStorageContents()).anyMatch(i -> i==null || i.getType().isAir()
            || i.getAmount()<Math.min(i.getMaxStackSize(),store.inventory().getMaxStackSize()));
    }
    /**
     * The container of a warehouse (or granary) a workplace without its own store works with: the first loaded one
     * with a free slot, else the first loaded one, else its main binding (reported as unavailable by the caller).
     */
    private ProductionBinding pick(UUID country, Building warehouse) {
        ProductionBinding firstValid=null, first=null;
        for(var binding:bindingsOf(country,warehouse)) {
            if(first==null) first=binding;
            Store s=validate(country,binding); if(s==null) continue;
            if(room(s)) return binding;
            if(firstValid==null) firstValid=s.binding();
        }
        return firstValid!=null ? firstValid : first;
    }
    /** The scan of this warehouse's containers starts with the next tick (after it was built, registered or asked about). */
    public void noteWarehouse(Building b) {
        if(b==null || b.function()!=BuildingFunction.WAREHOUSE) return;
        var scan=warehouseScans.computeIfAbsent(b.id(),k -> new Scan());
        if(scan.retry>System.currentTimeMillis() && !bindingsOf(b.countryId(),b).isEmpty()) return; // scanned lately
        if(!warehouseQueue.contains(b.id())) warehouseQueue.addLast(b.id());
    }
    /** One row of {@code /ludnosc magazyn}: the containers of a warehouse that are loaded and valid now. */
    public record WarehouseView(Building building, int containers, int loaded, int usedSlots, int slots) { }
    public List<WarehouseView> warehouses(UUID country) {
        requireMain();
        final List<WarehouseView> out=new ArrayList<>();
        for(var b:plugin.data().buildingsOf(country)) {
            if(b.function()!=BuildingFunction.WAREHOUSE) continue;
            noteWarehouse(b);
            int loaded=0, used=0, slots=0; var all=bindingsOf(country,b);
            for(var binding:all) {
                Store s=validate(country,binding); if(s==null) continue;
                loaded++;
                for(var item:s.inventory().getStorageContents()) { slots++; if(item!=null && !item.getType().isAir()) used++; }
            }
            out.add(new WarehouseView(b,all.size(),loaded,used,slots));
        }
        return out;
    }
    /** The workplace, warehouse or granary of the country whose outline holds this block (smallest first), or null. */
    public Building holder(UUID country, Block at) {
        Building best=null;
        for(var b:plugin.data().buildingsOf(country)) {
            if(!b.box().contains(at.getWorld().getName(),at.getX(),at.getY(),at.getZ())) continue;
            if(!productive(b) && b.function()!=BuildingFunction.WAREHOUSE && b.function()!=BuildingFunction.GRANARY) continue;
            if(best==null || pl.medivalrpg.ludnosc.logic.StorageDiscovery.volume(b.box())<pl.medivalrpg.ludnosc.logic.StorageDiscovery.volume(best.box())) best=b;
        }
        return best;
    }
    /**
     * Registers every chest and barrel inside the queued warehouses (template-built and hand-built), a few blocks a
     * tick, loaded chunks only. Containers already bound (by hand, as the default store, by a workplace) stay as they
     * are; a container carrying another plugin's data (a shop chest) is left alone.
     */
    private void scanWarehouses() {
        if(warehouseQueue.isEmpty() && ticks%20==0 && !plugin.data().states().isEmpty()) {
            var countries=new ArrayList<>(plugin.data().states().keySet());
            UUID country=countries.get(Math.floorMod(warehouseTurn++,countries.size()));
            long now=System.currentTimeMillis();
            for(var b:plugin.data().buildingsOf(country)) if(b.function()==BuildingFunction.WAREHOUSE) {
                var scan=warehouseScans.computeIfAbsent(b.id(),k -> new Scan());
                if(scan.retry<=now && !warehouseQueue.contains(b.id())) warehouseQueue.addLast(b.id());
            }
        }
        int budget=Math.max(16,Math.min(1024,plugin.getConfig().getInt("production.scan-blocks-per-tick",128)));
        while(budget>0 && !warehouseQueue.isEmpty()) {
            UUID id=warehouseQueue.peekFirst(); Building b=plugin.data().building(id);
            if(b==null || b.function()!=BuildingFunction.WAREHOUSE) { warehouseQueue.pollFirst(); warehouseScans.remove(id); continue; }
            Scan scan=warehouseScans.computeIfAbsent(id,k -> new Scan());
            long volume=pl.medivalrpg.ludnosc.logic.StorageDiscovery.volume(b.box());
            boolean unloaded=false;
            while(budget>0 && scan.cursor<volume) {
                BlockPos pos=pl.medivalrpg.ludnosc.logic.StorageDiscovery.cell(b.box(),scan.cursor); Block at=block(pos);
                if(at==null) { unloaded=true; break; }
                budget--; scan.cursor++;
                if((at.getType()==Material.CHEST || at.getType()==Material.BARREL) && own(b.countryId(),pos)) adopt(b,at);
            }
            if(unloaded) { scan.cursor=0; scan.retry=System.currentTimeMillis()+30_000; warehouseQueue.pollFirst(); continue; }
            if(scan.cursor>=volume) {
                scan.cursor=0; scan.retry=System.currentTimeMillis()+WAREHOUSE_RESCAN_MILLIS; warehouseQueue.pollFirst();
                prune(b);
            }
        }
    }
    private void adopt(Building b, Block at) {
        List<BlockPos> wanted=parts(at); if(wanted.isEmpty()) return;
        var stores=plugin.data().state(b.countryId()).productionStores();
        for(var existing:stores.values()) if(existing!=null && wanted.equals(existing.parts())) return;
        String id=b.id().toString();
        if(pl.medivalrpg.ludnosc.logic.StorageDiscovery.keysOf(stores.keySet(),id).size()>=warehouseCap()) return;
        try {
            var binding=register(b.countryId(),at);
            stores.put(pl.medivalrpg.ludnosc.logic.StorageDiscovery.freeKey(stores.keySet(),id),binding);
            plugin.data().markDirty();
        } catch(IllegalArgumentException ignored) { }
    }
    /** Further containers of a warehouse that are gone (loaded, no chest or barrel there any more) are forgotten. */
    private void prune(Building b) {
        var stores=plugin.data().state(b.countryId()).productionStores();
        boolean changed=false;
        for(String k:pl.medivalrpg.ludnosc.logic.StorageDiscovery.keysOf(stores.keySet(),b.id().toString())) {
            if(!pl.medivalrpg.ludnosc.logic.StorageDiscovery.extraKey(k)) continue;
            var binding=stores.get(k);
            boolean gone=binding==null || binding.parts()==null || binding.parts().isEmpty();
            if(!gone) for(var p:binding.parts()) {
                Block at=block(p);
                if(at==null) { gone=false; break; }
                if(at.getType()!=Material.CHEST && at.getType()!=Material.BARREL) gone=true;
            }
            if(gone) { stores.remove(k); changed=true; }
        }
        if(changed) plugin.data().markDirty();
    }
    public void linkWarehouse(UUID country, Building workplace, Building warehouse) {
        requireMain();
        if(!country.equals(workplace.countryId()) || !country.equals(warehouse.countryId()) || warehouse.function()!=BuildingFunction.WAREHOUSE && warehouse.function()!=BuildingFunction.GRANARY
            || !productive(workplace) || !workplace.box().world().equals(warehouse.box().world())) throw new IllegalArgumentException("Nieprawidłowy magazyn lub kraj.");
        if(!jobs.isEmpty()) throw new IllegalArgumentException("Trwa zapis produkcji.");
        var binding=pick(country,warehouse);
        if(binding==null) binding=discover(warehouse);
        noteWarehouse(warehouse);
        Store store=validate(country,binding); if(store==null) throw new IllegalArgumentException("Magazyn nie ma dostępnego zarejestrowanego kontenera.");
        plugin.data().state(country).productionStores().put(workplace.id().toString(),store.binding()); plugin.data().markDirty(); plugin.saveRoyalCheckpoint();
    }
    private Store validate(UUID country, ProductionBinding binding) {
        return validate(country,binding,false);
    }
    private Store validate(UUID country, ProductionBinding binding, boolean supplyRead) {
        if (binding == null || binding.token()==null || binding.parts() == null || binding.parts().isEmpty()) return null;
        for (var p : binding.parts()) {
            if(!supplyRead && plugin.supply()!=null && plugin.supply().locked(p)) return null;
            Block b = block(p);
            if (b == null || !own(country,p) || !(b.getState(false) instanceof Container c) || !namespaceAllowed(c)
                || !binding.token().toString().equals(c.getPersistentDataContainer().get(tokenKey,PersistentDataType.STRING))
                || !country.toString().equals(c.getPersistentDataContainer().get(countryKey,PersistentDataType.STRING))) return null;
        }
        Block first = block(binding.parts().getFirst());
        if (!parts(first).equals(binding.parts())) return null;
        return new Store(binding,((Container)first.getState(false)).getInventory());
    }
    record SupplyTarget(UUID warehouse, ProductionBinding binding, Inventory inventory) { }
    /** Read existing warehouse registrations only; never discovers, registers or loads containers. */
    SupplyTarget supplyTarget(UUID country, Location player, double range) {
        requireMain();
        var state=plugin.data().stateIfPresent(country); if(state==null || state.productionStores().size()>BINDING_BUDGET) return null;
        var warehouses=plugin.data().buildingsOf(country).stream()
            .filter(b -> b.function()==BuildingFunction.WAREHOUSE && b.box().world().equals(player.getWorld().getName()))
            .sorted(Comparator.comparingDouble(b -> Math.pow(b.box().centerX()+0.5-player.getX(),2)+Math.pow(b.box().centerZ()+0.5-player.getZ(),2)))
            .limit(64).toList();
        for(var b:warehouses) for(var binding:state.productionStores().values()) {
            if(binding==null || binding.parts()==null || binding.parts().isEmpty() || binding.parts().size()>2) continue;
            if(binding.parts().stream().anyMatch(p -> !b.box().contains(p.world(),p.x(),p.y(),p.z())
                || player.distanceSquared(new Location(player.getWorld(),p.x()+0.5,p.y()+0.5,p.z()+0.5))>range*range)) continue;
            Store store=validate(country,binding);
            if(store==null || !store.inventory().getViewers().isEmpty()
                || jobs.values().stream().anyMatch(j -> j.store.binding().parts().equals(binding.parts()))) continue;
            return new SupplyTarget(b.id(),binding,store.inventory());
        }
        return null;
    }
    /** Internal receipt reconciliation may READ a locked inventory, but normal production cannot use it. */
    Inventory supplyInventory(UUID country, UUID warehouse, ProductionBinding binding) {
        requireMain();
        var state=plugin.data().stateIfPresent(country);
        if(state==null || !state.productionStores().containsValue(binding)) return null;
        var b=plugin.data().building(warehouse);
        if(b==null || !country.equals(b.countryId()) || b.function()!=BuildingFunction.WAREHOUSE
            || binding.parts().stream().anyMatch(p -> !b.box().contains(p.world(),p.x(),p.y(),p.z()))) return null;
        Store store=validate(country,binding,true);
        return store==null ? null : store.inventory();
    }
    private boolean namespaceAllowed(Container container) {
        return pl.medivalrpg.ludnosc.logic.StorageDiscovery.exclusiveNamespace(
            container.getPersistentDataContainer().getKeys().stream().map(NamespacedKey::getNamespace).toList(),tokenKey.getNamespace());
    }
    /** Bounded, lazy deterministic scan of known native building boxes only. No chunk loads or radius scans. */
    private ProductionBinding discover(Building b) {
        if (b.registered() || scanBudget <= 0) return null;
        Scan scan = scans.computeIfAbsent(b.id(),k -> new Scan());
        if (scan.retry > System.currentTimeMillis()) return null;
        var box = b.box();
        long volume = pl.medivalrpg.ludnosc.logic.StorageDiscovery.volume(box);
        while (scanBudget-- > 0 && scan.cursor < volume) {
            BlockPos pos = pl.medivalrpg.ludnosc.logic.StorageDiscovery.cell(box,scan.cursor); Block at = block(pos);
            if (at == null) return null;
            scan.cursor++;
            if ((at.getType() == Material.CHEST || at.getType() == Material.BARREL) && own(b.countryId(),pos)) {
                try {
                    var binding = register(b.countryId(),at);
                    plugin.data().state(b.countryId()).productionStores().put(b.id().toString(),binding);
                    plugin.data().markDirty(); return binding;
                } catch (IllegalArgumentException ignored) { }
            }
        }
        if (scan.cursor >= volume) { scan.cursor = 0; scan.retry = System.currentTimeMillis()+10_000; }
        return null;
    }
    /**
     * Where the store of a workplace lies, from the existing bindings only (own, then the country default, then a
     * warehouse), never discovering, validating or loading anything; the building itself when nothing is bound yet
     * (its own chest is found by the bbox scan). Lets a ticketed workplace keep its store chunk loaded.
     */
    public BlockPos storeAnchor(Building b) {
        requireMain();
        var state = plugin.data().stateIfPresent(b.countryId());
        if (state != null) {
            var binding = state.productionStores().get(b.id().toString());
            if (binding == null) binding = state.productionStores().get("default");
            if (binding == null) for (var warehouse : plugin.data().buildingsOf(b.countryId()).stream()
                .filter(x -> x.function() == BuildingFunction.WAREHOUSE && x.box().world().equals(b.box().world()))
                .sorted(Comparator.comparing(x -> x.id().toString())).toList()) {
                binding = state.productionStores().get(warehouse.id().toString());
                if (binding != null) break;
            }
            if (binding != null && binding.parts() != null && !binding.parts().isEmpty() && binding.parts().getFirst() != null)
                return binding.parts().getFirst();
        }
        return new BlockPos(b.box().world(), b.box().centerX(), b.box().minY(), b.box().centerZ());
    }
    private Store resolve(Building b) { return resolve(b,false); }
    private Store resolve(Building b, boolean report) {
        var state = plugin.data().state(b.countryId());
        var binding = state.productionStores().get(b.id().toString());
        if (binding == null) binding = discover(b);
        if (binding == null) binding = state.productionStores().get("default");
        if (binding == null) for (var warehouse : plugin.data().buildingsOf(b.countryId()).stream()
            .filter(x -> (x.function() == BuildingFunction.WAREHOUSE
                || (b.function()==BuildingFunction.FARM || b.function()==BuildingFunction.HARBOR) && x.function()==BuildingFunction.GRANARY)
                && x.box().world().equals(b.box().world()))
            .sorted(Comparator.comparing(x -> x.id().toString())).toList()) {
            binding = pick(b.countryId(),warehouse);
            if (binding == null) binding = discover(warehouse);
            if (binding == null) noteWarehouse(warehouse);
            if (binding != null) break;
        }
        if (binding == null) {
            statuses.put(b.id(),"Praca wstrzymana — przypisz magazyn");
            if(report) plugin.alerts().blocked(b,Reason.MISSING_STORE);
            return null;
        }
        Store store = validate(b.countryId(),binding);
        if (store == null || !binding.parts().getFirst().world().equals(b.box().world())) {
            if(report) plugin.alerts().blocked(b,Reason.UNAVAILABLE_STORE);
            statuses.put(b.id(),"Praca wstrzymana — magazyn niezaładowany, zniszczony lub zmienił właściciela"); return null;
        }
        return store;
    }
    public String status(Building b) {
        requireMain();
        if (plugin.data().state(b.countryId()).productionIntents().contains(b.id().toString()) && !jobs.containsKey(b.id()))
            return "Praca wstrzymana — wymagany audyt nieukończonego zapisu";
        Store store=resolve(b);
        if(store!=null && !jobs.containsKey(b.id())) {
            boolean room=Arrays.stream(store.inventory().getStorageContents()).anyMatch(i -> i==null || i.getType().isAir()
                || i.getAmount()<Math.min(i.getMaxStackSize(),store.inventory().getMaxStackSize()));
            if(!room) statuses.put(b.id(),"Magazyn pełny — praca wstrzymana");
            else {
                String prior=statuses.getOrDefault(b.id(),"");
                if(prior.contains("przypisz magazyn") || prior.contains("niezaładowany") || prior.startsWith("Magazyn pełny")) statuses.remove(b.id());
            }
        }
        return statuses.getOrDefault(b.id(),"Magazyn gotowy");
    }
    public void blocked(Building b,String message) { statuses.put(b.id(),message); plugin.alerts().forget(b); }
    /** A prepared production commit of this workplace is still running (not a refusal). */
    public boolean busy(Building b) { requireMain(); return jobs.containsKey(b.id()); }
    public boolean ready(Building b) {
        requireMain();
        if(plugin.royalRebuild()!=null && plugin.royalRebuild().blocks(b.countryId())) return false;
        if (jobs.containsKey(b.id())) return false;
        if (plugin.data().state(b.countryId()).productionIntents().contains(b.id().toString())) {
            plugin.alerts().forget(b);
            statuses.put(b.id(),"Praca wstrzymana — wymagany audyt nieukończonego zapisu"); return false;
        }
        Store store=resolve(b,true);
        if(store==null) return false;
        boolean room=Arrays.stream(store.inventory().getStorageContents()).anyMatch(i -> i==null || i.getType().isAir() || i.getAmount()<Math.min(i.getMaxStackSize(),store.inventory().getMaxStackSize()));
        if(!room) { statuses.put(b.id(),"Magazyn pełny — praca wstrzymana"); plugin.alerts().blocked(b,Reason.FULL); }
        return room;
    }
    private static ItemStack[] cloneItems(ItemStack[] items) { return Arrays.stream(items).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new); }
    /** Capacity calculation preserves exact ItemStack identity/meta using a local key dictionary. */
    static ItemStack[] plan(ItemStack[] before, List<ItemStack> outputs, List<ItemStack> costs, int inventoryMax) {
        final List<ItemStack> kinds = new ArrayList<>();
        java.util.function.Function<ItemStack,StoragePlan.Stack> encode = item -> {
            if (item == null || item.getType().isAir() || item.getAmount() == 0) return null;
            int key = 0; while (key < kinds.size() && !kinds.get(key).isSimilar(item)) key++;
            if (key == kinds.size()) kinds.add(item.clone());
            return new StoragePlan.Stack(Integer.toString(key),item.getAmount(),Math.min(inventoryMax,item.getMaxStackSize()));
        };
        var result = StoragePlan.plan(Arrays.stream(before).map(encode).toList(),outputs.stream().map(encode).toList(),costs.stream().map(encode).toList());
        return result.map(list -> list.stream().map(s -> { if (s == null) return null; var item = kinds.get(Integer.parseInt(s.key())).clone(); item.setAmount(s.amount()); return item; }).toArray(ItemStack[]::new)).orElse(null);
    }
    /** Prepare exact output before any world/cargo mutation; commit is polled by tick after durable intent. */
    public boolean produce(Building b, List<Change> changes, List<ItemStack> drops, List<ItemStack> costs, Runnable done) {
        requireMain();
        if (!ready(b) || jobs.size() >= 16) return false;
        Store store = resolve(b); if (store == null) return false;
        var before = cloneItems(store.inventory().getStorageContents());
        final ItemStack[] after;
        try { after=plan(before,drops,costs,store.inventory().getMaxStackSize()); }
        catch(IllegalArgumentException ex) { statuses.put(b.id(),"Praca wstrzymana — nieprawidłowy stos w magazynie"); plugin.alerts().forget(b); return false; }
        if (after == null) {
            boolean missing=!costs.isEmpty() && plan(before,List.of(),costs,store.inventory().getMaxStackSize())==null;
            boolean torches=costs.stream().allMatch(i -> i.getType()==Material.TORCH || i.getType()==Material.SOUL_TORCH);
            if(missing && !torches) { plugin.alerts().forget(b); statuses.put(b.id(),"Brak surowca w magazynie — praca wstrzymana"); return false; }
            plugin.alerts().blocked(b,missing ? Reason.TORCHES : Reason.FULL);
            statuses.put(b.id(),missing ? "Brak oświetlenia — brak pochodni w magazynie, praca wstrzymana" : "Magazyn pełny — praca wstrzymana"); return false;
        }
        Job job = new Job(); job.building=b; job.store=store; job.before=before; job.after=after;
        job.changes=List.copyOf(changes); job.done=done;
        job.outputs=drops.stream().map(ItemStack::clone).toList();
        plugin.data().state(b.countryId()).productionIntents().add(b.id().toString()); plugin.data().markDirty();
        job.saved=plugin.saveRoyalCheckpoint(); jobs.put(b.id(),job); statuses.put(b.id(),"Zapis produkcji — praca wstrzymana"); return true;
    }
    /** One commit per tick. A crash between intent and final checkpoint is fail-closed, never replayed. */
    public void tick() {
        requireMain();
        scanBudget = Math.max(16,Math.min(1024,plugin.getConfig().getInt("production.scan-blocks-per-tick",128)));
        scanWarehouses();
        if(++ticks%20==0 && !plugin.data().states().isEmpty()) {
            var countries=new ArrayList<>(plugin.data().states().keySet());
            stock(countries.get((int)((ticks/20)%countries.size())));
        }
        for (var it=jobs.entrySet().iterator();it.hasNext();) {
            var entry=it.next(); Job j=entry.getValue(); if(!j.saved.isDone()) continue;
            if(j.saved.isCompletedExceptionally()) { plugin.alerts().forget(j.building); plugin.data().state(j.building.countryId()).productionIntents().add(entry.getKey().toString()); statuses.put(entry.getKey(),"Praca wstrzymana — błąd zapisu, wymagany audyt"); it.remove(); continue; }
            if(j.finishing) { if(j.committed) plugin.alerts().recovered(j.building); statuses.put(entry.getKey(),"Magazyn gotowy"); it.remove(); continue; }
            if(ticks%Math.max(1,plugin.getConfig().getInt("production.commit-interval-ticks",20))!=0) continue;
            var current=plugin.data().building(entry.getKey());
            Store store=current == null || !current.equals(j.building) ? null : validate(j.building.countryId(),j.store.binding());
            boolean valid=store!=null && Arrays.equals(j.before,store.inventory().getStorageContents())
                && (plugin.royalRebuild()==null || !plugin.royalRebuild().blocks(j.building.countryId()));
            for(var c:j.changes) { Block b=block(c.pos()); if(b==null || !own(j.building.countryId(),c.pos()) || !b.getBlockData().getAsString().equals(c.before())) valid=false; }
            if(valid) {
                try {
                    // No Bukkit event, player action or async task interleaves this exact main-thread section.
                    for(var c:j.changes) block(c.pos()).setBlockData(Bukkit.createBlockData(c.after()),false);
                    store.inventory().setStorageContents(cloneItems(j.after));
                    j.done.run();
                    recordOutput(j.building,j.outputs);
                    stock(j.building.countryId());
                    // Persist region/inventory changes BEFORE clearing the intent. Ambiguous crash -> manual audit.
                    final Set<World> worlds=new HashSet<>(); j.store.binding().parts().forEach(p -> worlds.add(Bukkit.getWorld(p.world())));
                    j.changes.forEach(c -> worlds.add(Bukkit.getWorld(c.pos().world()))); worlds.forEach(World::save);
                    j.committed=true;
                } catch(RuntimeException ex) {
                    plugin.alerts().forget(j.building);
                    statuses.put(entry.getKey(),"Praca wstrzymana — wymagany audyt operacji");
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,"Nieukończona produkcja " + entry.getKey(),ex); it.remove(); break;
                }
            }
            plugin.data().state(j.building.countryId()).productionIntents().remove(entry.getKey().toString()); plugin.data().markDirty();
            j.finishing=true; j.saved=plugin.saveRoyalCheckpoint(); break;
        }
    }
    public void audit(UUID country, UUID building) {
        requireMain();
        if(jobs.containsKey(building)) throw new IllegalArgumentException("Trwa zapis.");
        plugin.data().state(country).productionIntents().remove(building.toString()); plugin.data().markDirty(); plugin.saveRoyalCheckpoint();
    }
    /** A clean disable can discard unexecuted preparations; ambiguous failed commits were removed from jobs already. */
    public void shutdown() {
        for(var j:jobs.values()) if(!j.finishing && !j.saved.isCompletedExceptionally())
            plugin.data().state(j.building.countryId()).productionIntents().remove(j.building.id().toString());
        jobs.clear(); plugin.data().markDirty();
    }
    private List<Store> stores(UUID country) {
        var state=plugin.data().stateIfPresent(country); if(state==null) return List.of();
        final Map<List<BlockPos>,Store> unique=new LinkedHashMap<>();
        for(var binding:state.productionStores().values()) { var s=validate(country,binding); if(s!=null) unique.putIfAbsent(binding.parts(),s); }
        return List.copyOf(unique.values());
    }
    /** A registered, validated container of a country: binding key (building id or "default"), its parts and inventory. */
    public record StoreView(String key, ProductionBinding binding, Inventory inventory, BlockPos at) { }
    /**
     * Loaded, valid and currently free stores of a country (no supply lock, no pending production commit), one view
     * per physical container even when several workplaces share it. Main thread; never loads chunks or discovers.
     */
    public List<StoreView> views(UUID country) {
        requireMain();
        var state=plugin.data().stateIfPresent(country); if(state==null) return List.of();
        final Map<List<BlockPos>,StoreView> unique=new LinkedHashMap<>();
        for(var e:state.productionStores().entrySet()) {
            var binding=e.getValue();
            if(binding==null || binding.parts()==null || binding.parts().isEmpty() || unique.containsKey(binding.parts()) || busy(binding)) continue;
            var store=validate(country,binding); if(store==null) continue;
            unique.put(binding.parts(),new StoreView(e.getKey(),binding,store.inventory(),binding.parts().getFirst()));
        }
        // a store shared by a warehouse/granary and a workplace is named after the warehouse/granary
        for(var e:state.productionStores().entrySet()) {
            var v=e.getValue()==null ? null : unique.get(e.getValue().parts()); if(v==null) continue;
            var b=key(e.getKey()); if(b!=null && (b.function()==BuildingFunction.WAREHOUSE || b.function()==BuildingFunction.GRANARY))
                unique.put(v.binding().parts(),new StoreView(e.getKey(),v.binding(),v.inventory(),v.at()));
        }
        return List.copyOf(unique.values());
    }
    /**
     * The validated store a workplace / workshop works with now (its own container, else the default store or a
     * warehouse), or null when unavailable or a production commit is pending on it. Main thread.
     */
    public StoreView storeOf(Building b) {
        requireMain();
        Store store=resolve(b); if(store==null || busy(store.binding())) return null;
        return new StoreView(b.id().toString(),store.binding(),store.inventory(),store.binding().parts().getFirst());
    }
    /** Committed output into the rolling 24 h ledger of the workplace (kept in the country data, survives restarts). */
    private void recordOutput(Building b, List<ItemStack> outputs) {
        if(outputs==null || outputs.isEmpty()) return;
        var buckets=plugin.data().state(b.countryId()).output24h().computeIfAbsent(b.id().toString(),k -> new LinkedHashMap<>());
        long now=System.currentTimeMillis();
        for(var item:outputs) if(item!=null && !item.getType().isAir())
            pl.medivalrpg.ludnosc.logic.OutputLedger.record(buckets,now,item.getType().getKey().toString(),item.getAmount());
        plugin.data().markDirty();
    }
    /** Items per material made by the workplace in the last 24 hours (committed production only). */
    public Map<String,Integer> lastDay(Building b) {
        var st=plugin.data().stateIfPresent(b.countryId()); if(st==null) return Map.of();
        return pl.medivalrpg.ludnosc.logic.OutputLedger.lastDay(st.output24h().get(b.id().toString()),System.currentTimeMillis());
    }
    /** The building a binding key belongs to (null for "default" or an unknown id). */
    public Building key(String key) {
        try { return key==null || key.equals("default") ? null : plugin.data().building(UUID.fromString(pl.medivalrpg.ludnosc.logic.StorageDiscovery.ownerKey(key))); }
        catch(IllegalArgumentException ex) { return null; }
    }
    /** Production commits waiting for their turn (one per {@code production.commit-interval-ticks}). */
    public int pending() { return jobs.size(); }
    /** The store of this binding is shared by several workplaces / a warehouse (not the building's own container). */
    public boolean shared(UUID country, ProductionBinding binding) {
        var state=plugin.data().stateIfPresent(country); if(state==null || binding==null) return false;
        int n=0; for(var b:state.productionStores().values()) if(b!=null && binding.parts().equals(b.parts())) n++;
        return n!=1;
    }
    /** A production commit is prepared on this container: haulers must not touch it now. */
    public boolean busy(ProductionBinding binding) {
        return jobs.values().stream().anyMatch(j -> j.store.binding().parts().equals(binding.parts()));
    }
    /** A production commit is prepared on this inventory (compared by container location). */
    public boolean busy(Inventory inventory) {
        var at=inventory.getLocation(); if(at==null) return false;
        return jobs.values().stream().anyMatch(j -> at.equals(j.store.inventory().getLocation()));
    }
    /** Re-publishes the stock snapshot after a hauler changed a store. */
    public void touched(UUID country) { if(Bukkit.isPrimaryThread()) stock(country); }
    /** Read-only meal projection, using the same acceptance/rounding as feed. No discovery or chunk loads.
     * Null means the binding budget was exceeded; no food alert is inferred from a partial scan. */
    public pl.medivalrpg.ludnosc.logic.FoodReserve foodReserve(UUID country) {
        requireMain();
        var reserve = new pl.medivalrpg.ludnosc.logic.FoodReserve(plugin.granary().food(country),
            plugin.granary().capacity(country), (long)plugin.data().population(country)*plugin.settings().rules.foodPerCitizen());
        if(reserve.sufficient()) return reserve;
        var state=plugin.data().stateIfPresent(country); if(state==null) return reserve;
        Set<List<BlockPos>> seen=new HashSet<>(); int inspected=0;
        for(var binding:state.productionStores().values()) {
            if(++inspected>BINDING_BUDGET) return null;
            if(binding==null || binding.parts()==null || binding.parts().isEmpty()) continue;
            if(binding.parts().size()>2) return null;
            if(seen.contains(binding.parts())) continue;
            Store store=validate(country,binding); if(store==null) continue;
            seen.add(binding.parts());
            for(var item:store.inventory().getStorageContents()) {
                if(item!=null && !item.hasItemMeta()) reserve.include(item.getAmount(),plugin.settings().foodValue(item.getType()));
            }
        }
        return reserve;
    }
    public Map<String,Long> stock(UUID country) {
        if(!Bukkit.isPrimaryThread()) return snapshots.getOrDefault(country,Map.of());
        final Map<String,Long> result=new HashMap<>();
        for(var s:stores(country)) for(var item:s.inventory().getStorageContents()) if(item!=null && !item.hasItemMeta()) {
            String key=kind(item.getType()); if(key!=null) result.merge(key,(long)item.getAmount(),Long::sum);
        }
        var out=Map.copyOf(result); snapshots.put(country,out); plugin.resources().publishStock(country,out); return out;
    }
    public static String kind(Material m) {
        if(Tag.LOGS.isTagged(m)) return ResourceService.WOOD;
        if(Tag.BASE_STONE_OVERWORLD.isTagged(m) || m==Material.COBBLESTONE || m==Material.COBBLED_DEEPSLATE) return ResourceService.STONE;
        if(m==Material.RAW_GOLD || m==Material.GOLD_NUGGET || m==Material.DIAMOND || m==Material.EMERALD) return ResourceService.PRECIOUS;
        if(m==Material.RAW_IRON || m==Material.RAW_COPPER || m==Material.COAL || m==Material.REDSTONE || m==Material.LAPIS_LAZULI) return ResourceService.ORE;
        if(m==Material.COD || m==Material.SALMON) return ResourceService.FISH;
        return "minecraft:"+m.name().toLowerCase(java.util.Locale.ROOT);
    }
    /** Real debit for building materials; pending production observes the changed exact snapshot and retries. */
    public boolean take(UUID country, Material need, String group) {
        if(!Bukkit.isPrimaryThread()) return false;
        for(var store:stores(country)) {
            var items=store.inventory().getStorageContents();
            for(int pass=0;pass<2;pass++) for(int i=0;i<items.length;i++) {
                var item=items[i]; if(item==null || item.hasItemMeta()) continue;
                if(pass==0 ? item.getType()!=need : group==null || !group.equals(kind(item.getType()))) continue;
                item=item.clone(); item.setAmount(item.getAmount()-1); items[i]=item.getAmount()==0 ? null : item;
                store.inventory().setStorageContents(items); stock(country); return true;
            }
        }
        return false;
    }
    /** Haulers disabled: one plain item of a material group taken from any store; returns the units it gives (0 = none). */
    public int takeSupply(UUID country, String group) {
        if(!Bukkit.isPrimaryThread()) return 0;
        for(var store:stores(country)) {
            if(busy(store.binding())) continue;
            var items=store.inventory().getStorageContents();
            for(int i=0;i<items.length;i++) {
                var item=items[i]; if(item==null || item.hasItemMeta()) continue;
                int units=pl.medivalrpg.ludnosc.logic.MaterialNeeds.supply(item.getType().name(),group); if(units<=0) continue;
                item=item.clone(); item.setAmount(item.getAmount()-1); items[i]=item.getAmount()==0 ? null : item;
                store.inventory().setStorageContents(items); stock(country); return units;
            }
        }
        return 0;
    }
    /** Food is debited from physical storage only as needed for the current daily meal. */
    public void feed(UUID country) {
        requireMain();
        final var state=plugin.data().state(country);
        long need=Math.max(0,Math.min(plugin.granary().capacity(country)-state.food,
            (long)plugin.data().population(country)*plugin.settings().rules.foodPerCitizen()-state.food));
        for(var store:stores(country)) {
            var items=store.inventory().getStorageContents(); boolean changed=false;
            for(int i=0;i<items.length && need>0;i++) {
                var item=items[i]; if(item==null || item.hasItemMeta()) continue;
                int value=plugin.settings().foodValue(item.getType()); if(value<=0) continue;
                long room=Math.max(0,plugin.granary().capacity(country)-state.food);
                int count=(int)Math.min(item.getAmount(),Math.min((need+value-1)/value,room/value)); if(count==0) continue;
                var reduced=item.clone(); reduced.setAmount(item.getAmount()-count); items[i]=reduced.getAmount()==0 ? null : reduced;
                long food=(long)count*value; state.food+=food; need-=food; changed=true;
            }
            if(changed) { store.inventory().setStorageContents(items); plugin.data().markDirty(); snapshots.remove(country); }
        }
        stock(country);
    }
    public List<ItemStack> drops(Block b, ItemStack tool, boolean replant) {
        var out=new ArrayList<ItemStack>(); for(var item:b.getDrops(tool)) out.add(item.clone());
        if(replant) {
            Material seed=switch(b.getType()) { case WHEAT -> Material.WHEAT_SEEDS; case CARROTS -> Material.CARROT; case POTATOES -> Material.POTATO; case BEETROOTS -> Material.BEETROOT_SEEDS; default -> null; };
            if(seed==null) throw new IllegalArgumentException("Unsupported crop for automatic replanting");
            for(var item:out) if(item.getType()==seed && item.getAmount()>0) { item.setAmount(item.getAmount()-1); out.removeIf(i -> i.getAmount()==0); return out; }
            throw new IllegalArgumentException("Harvest has no seed for replanting");
        }
        return out;
    }
}
