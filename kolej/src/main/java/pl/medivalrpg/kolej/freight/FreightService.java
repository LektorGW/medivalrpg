// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.freight;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.kolej.KolejPlugin;
import pl.medivalrpg.kolej.data.RailData.Train;
import pl.medivalrpg.kolej.network.TrackNode;

/** Integration entry point. Constructor, command, tick and close must run on Bukkit main. */
public final class FreightService implements AutoCloseable {
    private final KolejPlugin plugin;
    private final NamespacedKey receipt;
    private final FreightStore store;
    private final FreightCoordinator coordinator;
    private final BukkitPort port=new BukkitPort();
    private boolean reported;
    public FreightService(KolejPlugin plugin) {
        main();this.plugin=Objects.requireNonNull(plugin);receipt=new NamespacedKey(plugin,"freight_receipt");
        store=new FreightStore(plugin.getDataFolder().toPath());
        coordinator=new FreightCoordinator(port,store.load(),store::save);
    }
    private static void main() {if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Freight wymaga głównego wątku Bukkit.");}
    public void tick() {
        main();coordinator.tick(System.currentTimeMillis());
        if(coordinator.blocked()&&!reported){reported=true;plugin.getLogger().severe("Ładunki: "+coordinator.status());}
    }
    public String status() {main();return coordinator.status();}
    /** Accepts either [ladunek, action, ...] from main or [action, ...]. Never registers a command itself. */
    public void command(Player player,String[] arguments) {
        main();
        if(!plugin.isManager(player))throw new IllegalArgumentException("Tylko zarządca przewoźnika (kolej.admin).");
        String[] a=arguments.length>0&&arguments[0].equalsIgnoreCase("ladunek")?Arrays.copyOfRange(arguments,1,arguments.length):arguments;
        if(a.length==0)throw new IllegalArgumentException("/kolej ladunek magazyn|zlec|regularnosc|status");
        switch(a[0].toLowerCase(Locale.ROOT)) {
            case "magazyn" -> {
                count(a,2,"magazyn <stacja>");coordinator.requireReady();
                TrackNode station=plugin.node(a[1]);
                if(!station.isWaitingPoint())throw new IllegalArgumentException("Magazyn wymaga stacji lub bazy.");
                Block block=player.getTargetBlockExact(6);
                if(block==null)throw new IllegalArgumentException("Wyceluj w pojedynczą skrzynię lub beczkę (do 6 bloków).");
                container(block);
                if(container(block).getPersistentDataContainer().has(new NamespacedKey(plugin,"infrastructure_receipt"),PersistentDataType.STRING))throw new IllegalArgumentException("Stanowisko tankowania nie może być magazynem ładunkowym.");
                if(!block.getWorld().getName().equals(station.world()) || block.getLocation().distanceSquared(new Location(block.getWorld(),station.x(),station.y(),station.z()))>32*32)throw new IllegalArgumentException("Magazyn musi leżeć do 32 bloków od swojej stacji, w tym samym świecie.");
                if(!plugin.allowed(block.getLocation()))throw new IllegalArgumentException("Firma nie ma zgody na używanie magazynu w tym kraju.");
                FreightState state=coordinator.state();
                if(state.warehouses.containsKey(station.id()))throw new IllegalArgumentException("Stacja ma już magazyn.");
                FreightState.Warehouse w=new FreightState.Warehouse();w.station=station.id();w.world=block.getWorld().getUID().toString();w.x=block.getX();w.y=block.getY();w.z=block.getZ();w.receipt=UUID.randomUUID().toString();
                if(state.warehouses.values().stream().anyMatch(existing->existing.key().equals(w.key())))throw new IllegalArgumentException("Kontener jest już zarejestrowany.");
                FreightCoordinator.Snapshot before=port.inventory(w);
                if(before==null)throw new IllegalArgumentException("Magazyn nie jest wczytany.");
                if(before.receipt()!=null)throw new IllegalArgumentException("Kontener ma już kwit freight — nie usuwaj go; sprawdź ledger.");
                try {
                    if(!port.apply(before,before.slots(),w.receipt))throw new IllegalStateException("Magazyn zmienił się podczas rejestracji.");
                    coordinator.register(w);
                }catch(RuntimeException e){coordinator.quarantine(e);throw e;}
                plugin.message(player,"Przypisano magazyn do "+station.name()+"; trwa zapis. /kolej ladunek status");
            }
            case "zlec" -> {
                count(a,6,"zlec <pociag> <material> <ilosc> <od> <do>");
                String material=a[2].toUpperCase(Locale.ROOT);
                if(!FreightRules.supported(material) || Material.matchMaterial(material)==null)throw new IllegalArgumentException("Nieobsługiwany standardowy surowiec.");
                coordinator.order(plugin.train(a[1]).id,material,integer(a[3]),plugin.node(a[4]).id(),plugin.node(a[5]).id());
                plugin.message(player,"Zapisano zlecenie do dziennika; zapis w toku. Jednorazowo; cykl: /kolej ladunek regularnosc "+a[1]+" <minuty>.");
            }
            case "regularnosc" -> {
                count(a,3,"regularnosc <pociag> <minuty|0>");coordinator.regularity(a[1],integer(a[2]),System.currentTimeMillis());
                plugin.message(player,"Zapis regularności zlecenia w toku (0 = jednorazowo).");
            }
            case "status" -> {
                count(a,1,"status");plugin.message(player,coordinator.status());
                FreightState s=coordinator.state();
                if(s!=null)s.jobs.values().forEach(j->plugin.message(player,j.train+": "+j.stage+" "+j.quantity+" "+j.material+" "+j.source+" → "+j.destination+", na pokładzie "+j.aboard+", cykl "+j.repeatMillis/60_000+" min, przejazd "+j.travelProven));
            }
            default -> throw new IllegalArgumentException("/kolej ladunek magazyn|zlec|regularnosc|status");
        }
    }
    private static int integer(String value) {try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException("Oczekiwano liczby całkowitej.");}}
    private static void count(String[] a,int count,String usage) {if(a.length!=count)throw new IllegalArgumentException("Użycie: /kolej ladunek "+usage);}
    @Override public void close() {main();coordinator.close();store.close();}
    private static Container container(Block block) {
        BlockState state=block.getState();
        if(state instanceof Chest && block.getBlockData() instanceof org.bukkit.block.data.type.Chest chest && chest.getType()==Type.SINGLE)return (Container)state;
        if(state instanceof Barrel)return (Container)state;
        throw new IllegalArgumentException("Magazyn: pojedyncza skrzynia, skrzynia-pułapka albo beczka; bez podwójnych i przenośnych kontenerów.");
    }
    private static JourneyProof.Position position(Location l) {return new JourneyProof.Position(l.getWorld().getUID().toString(),l.getX(),l.getY(),l.getZ());}
    private record Token(Material blockType,ItemStack[] items) {}
    private static ItemStack[] clones(ItemStack[] items) {return Arrays.stream(items).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);}
    private static List<FreightRules.Slot> slots(ItemStack[] items) {
        List<FreightRules.Slot> result=new ArrayList<>();
        for(ItemStack item:items) {
            if(item==null || item.getType().isAir() || item.getAmount()==0){result.add(FreightRules.Slot.empty());continue;}
            boolean plain=FreightRules.supported(item.getType().name()) && !item.hasItemMeta() && item.isSimilar(new ItemStack(item.getType()));
            result.add(new FreightRules.Slot(item.getType().name(),item.getAmount(),plain,item.getMaxStackSize()));
        }
        return List.copyOf(result);
    }
    private final class BukkitPort implements FreightCoordinator.Port {
        public FreightCoordinator.TrainView train(String id) {
            main();Train t=plugin.data().trains.get(id);if(t==null)return null;
            Location location=plugin.runtime().location(t,0);if(location==null)return null;
            boolean stopped=Math.abs(t.steam.speed)<=.05 && (!t.running || t.dwellUntil>System.currentTimeMillis());
            return new FreightCoordinator.TrainView(t.id,t.node,stopped,t.running && Math.abs(t.steam.speed)>.001,t.routeNodes.toString()+"/"+t.routeSegments.toString(),t.distance,position(location),List.copyOf(t.cars),Map.copyOf(t.cargo),t.cargoDestination);
        }
        public JourneyProof.Position station(String id) {
            main();TrackNode n=plugin.graph().node(id);if(n==null)return null;World world=Bukkit.getWorld(n.world());if(world==null)return null;
            return new JourneyProof.Position(world.getUID().toString(),n.point().x(),n.point().y(),n.point().z());
        }
        public FreightCoordinator.Snapshot inventory(FreightState.Warehouse warehouse) {
            main();World world=Bukkit.getWorld(UUID.fromString(warehouse.world));
            if(world==null || !world.isChunkLoaded(warehouse.x>>4,warehouse.z>>4))return null;
            Block block=world.getBlockAt(warehouse.x,warehouse.y,warehouse.z);
            if(!plugin.allowed(block.getLocation()))throw new IllegalStateException("Cofnięto zgodę na magazyn "+warehouse.station);
            Container c=container(block);ItemStack[] items=clones(c.getInventory().getStorageContents());
            return new FreightCoordinator.Snapshot(warehouse,c.getPersistentDataContainer().get(receipt,PersistentDataType.STRING),slots(items),new Token(block.getType(),items));
        }
        public boolean apply(FreightCoordinator.Snapshot before,List<FreightRules.Slot> after,String newReceipt) {
            main();FreightCoordinator.Snapshot current=inventory(before.warehouse());if(current==null)return false;
            Token expected=(Token)before.token(),actual=(Token)current.token();
            if(!Objects.equals(before.receipt(),current.receipt()))throw new IllegalStateException("Zmieniony kwit magazynu podczas transferu.");
            if(expected.blockType!=actual.blockType || !Arrays.equals(expected.items,actual.items))return false;
            if(after.size()!=actual.items.length)throw new IllegalStateException("Zmieniony rozmiar kontenera.");
            World world=Bukkit.getWorld(UUID.fromString(before.warehouse().world));Block block=world.getBlockAt(before.warehouse().x,before.warehouse().y,before.warehouse().z);
            ItemStack[] replacement=clones(actual.items);
            for(int i=0;i<after.size();i++)if(!before.slots().get(i).equals(after.get(i))) {
                FreightRules.Slot s=after.get(i);replacement[i]=s.amount()==0?null:new ItemStack(Material.valueOf(s.material()),s.amount());
            }
            container(block).getInventory().setStorageContents(replacement);
            // Fresh state AFTER changing inventory: don't restore a stale inventory snapshot via update().
            Container updated=container(block);updated.getPersistentDataContainer().set(receipt,PersistentDataType.STRING,newReceipt);
            if(!updated.update(false,false))throw new IllegalStateException("Nie zapisano kwitu PDC; pozostawiono PREPARED.");
            return true;
        }
        public void project(String id,Map<String,Integer> cargo,String destination) {
            main();Train t=plugin.data().trains.get(id);if(t==null)throw new IllegalStateException("Zniknął pociąg "+id);
            t.cargo=new LinkedHashMap<>(cargo);t.cargoDestination=destination;
            // Visual only (no BetterModel types here): covered wagons open their doors after the transfer.
            pl.medivalrpg.kolej.visual.AnimationRequests.freightDoors(id);
        }
    }
}
