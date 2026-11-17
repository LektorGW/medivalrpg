// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.kolej.KolejPlugin;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.data.RailData.Train;
import pl.medivalrpg.kolej.freight.FreightRules.Slot;
import pl.medivalrpg.kolej.network.TrackNode;
import pl.medivalrpg.kolej.visual.TrainModels;
import pl.medivalrpg.kolej.world.WorksProtection;
import pl.medivalrpg.skarbiec.api.TxResult;
import static pl.medivalrpg.kolej.infrastructure.InfrastructureState.*;
import static pl.medivalrpg.kolej.infrastructure.SupplyCoordinator.*;

/** Five persistent service models over real containers. No block replacement, permanent entity or chunk tickets. */
public final class InfrastructureService implements Listener,AutoCloseable {
    private final KolejPlugin plugin;
    private final InfrastructureStore store;
    private final SupplyCoordinator coordinator;
    private final NamespacedKey receipt,freightReceipt;
    private final WorksProtection protection;
    private final Port port=new BukkitPort();
    private final Map<String,Long> retry=new HashMap<>();
    private final Map<String,Long> alerts=new HashMap<>();
    private TrainModels models;
    private int ticks;
    private boolean reported;
    public InfrastructureService(KolejPlugin plugin) {
        this.plugin=plugin;receipt=new NamespacedKey(plugin,"infrastructure_receipt");freightReceipt=new NamespacedKey(plugin,"freight_receipt");
        protection=new WorksProtection(plugin.countries());protection.setClaimValidator((at,country)->plugin.allowed(at));
        store=new InfrastructureStore(plugin.getDataFolder().toPath());coordinator=new SupplyCoordinator(port,store.load(),store::save);
        if(Bukkit.getPluginManager().isPluginEnabled("BetterModel"))models=new TrainModels();
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Infrastruktura wymaga głównego wątku Bukkit.");}
    public boolean holds(Train t){main();return coordinator.state()==null || coordinator.holds(t.id);}
    public String status(){return coordinator.status();}
    public void tick() {
        main();coordinator.tick();
        if(coordinator.status().startsWith("BLOKADA") && !reported){reported=true;plugin.getLogger().severe("Infrastruktura: "+coordinator.status());}
        if(++ticks%10!=0 || models==null || coordinator.state()==null)return;
        for(Fixture f:coordinator.state().fixtures.values()) {
            String key="infrastructure/"+f.id()+"/";World w=world(f);
            Location at=w==null?null:new Location(w,f.x()+.5,f.y()+1,f.z()+.5,f.rotation()*90,0);
            boolean near=at!=null && w.isChunkLoaded(f.x()>>4,f.z()>>4) && w.getPlayers().stream().anyMatch(p->p.getLocation().distanceSquared(at)<96*96);
            if(!near){models.remove(key);continue;}
            try {
                // Visual refresh only checks the source. Full geometry checks run before each transfer.
                validateSource(f);Container c=container(w.getBlockAt(f.x(),f.y(),f.z()));
                if(!f.receipt().equals(c.getPersistentDataContainer().get(receipt,PersistentDataType.STRING)))throw new IllegalArgumentException("Niezgodny znacznik stanowiska.");
                models.move(key,f.kind().model,at,0);
            }catch(RuntimeException e){models.remove(key);}
        }
    }
    /** Runtime hook: only low tenders trigger automatic transfers. Payment alone can never create resources. */
    public boolean refuel(Train t,boolean automatic) {
        main();
        if(holds(t))return false;
        if(!SupplyRules.valid(t.steam.coal,t.steam.water)){alert(t.id,"Nieprawidłowy zapis zapasów tendera.");return false;}
        boolean topUp=terminalTopUp(t);
        boolean needed=SupplyRules.needs(t.steam.coal,t.steam.water) || topUp && SupplyRules.topUp(t.steam.coal,t.steam.water);
        if(automatic && !needed)return true;
        if(!coordinator.available()){if(needed && coordinator.state()!=null)alert(t.id,coordinator.status());return automatic && !needed;}
        if(!plugin.runtime().canService(t))return !needed;
        long now=System.currentTimeMillis();
        if(automatic && now<retry.getOrDefault(t.id,0L))return !needed;
        retry.put(t.id,now+60_000);
        String reason="Brak przypisanego stanowiska dla brakującego surowca przy stacji "+t.node+".";
        for(Fixture f:coordinator.state().fixtures.values()) {
            if(!f.station().equals(t.node))continue;
            // Top up the scarce resource first. Each transaction handles exactly one physical stock.
            if(automatic && !SupplyRules.wants(f.kind(),t.steam.coal,t.steam.water,topUp))continue;
            try {
                int stock=coordinator.stock(f);
                int quantity=Math.min(64,Math.min(SupplyRules.room(f.kind(),t.steam.coal,t.steam.water),stock));
                if(quantity<1){reason=f.id()+": "+(stock<0?"chunk magazynu niewczytany":"brak zwykłego "+f.kind().material()+" w kontenerze");continue;}
                request(f,t,quantity);return false;
            }catch(IllegalArgumentException e){reason=f.id()+": "+e.getMessage();}
        }
        if(needed)alert(t.id,reason);
        return !needed;
    }
    /**
     * At the first/last stop of its active line a train fills the tender before leaving (config
     * {@code zapasy.do-pelna-na-koncowkach}, default true): a long uphill trip may use more than the 75 % above the
     * low-supply mark. Elsewhere only the low-supply rule applies.
     */
    private boolean terminalTopUp(Train t){
        if(!plugin.getConfig().getBoolean("zapasy.do-pelna-na-koncowkach",true) || t.line==null)return false;
        var line=plugin.data().lines.get(t.line);
        return line!=null && line.active && line.stops.size()>=2 && (t.node.equals(line.stops.getFirst()) || t.node.equals(line.stops.getLast()));
    }
    private void alert(String train,String reason) {
        long now=System.currentTimeMillis();if(now<alerts.getOrDefault(train,0L))return;
        alerts.put(train,now+120_000);
        for(Player manager:Bukkit.getOnlinePlayers())if(plugin.isManager(manager))plugin.message(manager,"Niskie zapasy — "+train+": "+reason);
    }
    private void request(Fixture f,Train t,int quantity) {
        long coalPrice=plugin.getConfig().getLong("koszty.wegiel-kg",1),waterPrice=plugin.getConfig().getLong("koszty.woda-100l",1);
        long cost=SupplyRules.cost(f.kind(),quantity,coalPrice,waterPrice);
        if(cost>0 && plugin.economy().balance(RailData.ACCOUNT)<cost)throw new IllegalArgumentException("Brak środków firmy na obsługę: "+cost+" md.");
        coordinator.request(f.id(),t.id,quantity,coalPrice,waterPrice);
        retry.put(t.id,System.currentTimeMillis()+60_000);
    }
    public void command(Player player,String[] a) {
        main();if(!plugin.isManager(player))throw new IllegalArgumentException("Tylko wyznaczony zarządca przewoźnika.");
        if(a.length<2)throw new IllegalArgumentException("/kolej infrastruktura dodaj|usun|lista|obsluz|status");
        switch(a[1]) {
            case "status" -> {count(a,2,"status");plugin.message(player,coordinator.status());}
            case "lista" -> {
                count(a,2,"lista");plugin.message(player,coordinator.status());
                if(coordinator.state()!=null)for(Fixture f:coordinator.state().fixtures.values()) {
                    String stock;try{int n=coordinator.stock(f);stock=n<0?"chunk niewczytany":n+" szt. "+f.kind().material();}catch(RuntimeException e){stock="niedostępne: "+e.getMessage();}
                    plugin.message(player,f.id()+" — "+f.kind().name().toLowerCase(Locale.ROOT)+", stacja "+f.station()+", "+stock);
                }
            }
            case "dodaj" -> {
                count(a,6,"dodaj <id> <zuraw|wieza|pompa|zbiornik|wegiel> <stacja> <0|90|180|270>");coordinator.requireReady();
                if(!RailData.identifier(a[2]))throw new IllegalArgumentException("Id: 1–24 liter, cyfr, _ lub -.");
                SupplyKind kind=SupplyKind.parse(a[3]);TrackNode station=plugin.node(a[4]);
                int degrees=integer(a[5]);if(!Set.of(0,90,180,270).contains(degrees))throw new IllegalArgumentException("Obrót: 0|90|180|270.");
                Block block=player.getTargetBlockExact(6);if(block==null)throw new IllegalArgumentException("Wyceluj w pojedynczą skrzynię lub beczkę (6 bloków).");
                container(block);
                Fixture f=new Fixture(a[2],station.id(),block.getWorld().getUID().toString(),block.getX(),block.getY(),block.getZ(),degrees/90,kind,block.getType().name(),UUID.randomUUID().toString());
                if(models==null || !models.ready(kind.model))throw new IllegalArgumentException("Brak wczytanego modelu BetterModel: "+kind.model);
                validateSite(f,true);coordinator.register(f);
                plugin.message(player,"Zapis stanowiska w toku. Model stoi nad kontenerem; jego zawartość jest jedynym źródłem zapasów.");
            }
            case "usun" -> {
                count(a,3,"usun <id>");Fixture f=coordinator.fixture(a[2]);near(player,f);coordinator.remove(f.id());
                if(models!=null)models.remove("infrastructure/"+f.id()+"/");plugin.message(player,"Usunięto przypisanie; zawartość kontenera pozostaje na miejscu. Trwa zapis.");
            }
            case "obsluz","tankuj" -> {
                count(a,5,"obsluz <stanowisko> <pociag> <1..64>");Fixture f=coordinator.fixture(a[2]);near(player,f);request(f,plugin.train(a[3]),integer(a[4]));
                plugin.message(player,"Zlecono tankowanie z magazynu; wynik: /kolej infrastruktura status. Węgiel 10 kg/szt., woda 250 l/wiadro.");
            }
            default -> throw new IllegalArgumentException("/kolej infrastruktura dodaj|usun|lista|obsluz|status");
        }
    }
    /** Launch support: is the registry idle (no pending transfer or write)? */
    public boolean idle(){main();return coordinator.available();}
    public Optional<Fixture> registered(String id){main();return coordinator.state()==null?Optional.empty():Optional.ofNullable(coordinator.state().fixtures.get(id));}
    private Fixture planned(String id,SupplyKind kind,String station,World world,int x,int y,int z,int degrees,String receipt) {
        if(!RailData.identifier(id))throw new IllegalArgumentException("Id: 1–24 liter, cyfr, _ lub -.");
        if(!Set.of(0,90,180,270).contains(degrees))throw new IllegalArgumentException("Obrót: 0|90|180|270.");
        TrackNode node=plugin.node(station);
        return new Fixture(id,node.id(),world.getUID().toString(),x,y,z,degrees/90,kind,world.getBlockAt(x,y,z).getType().name(),receipt);
    }
    /**
     * Dry check of a planned station fixture at exact coordinates (same rules as {@code dodaj}): loaded chunk, a plain
     * single chest/barrel without another receipt, model loaded, free model volume, land consent and track clearance.
     * Returns null when it could be registered now, else the reason. Never changes the world.
     */
    public String siteProblem(String id,SupplyKind kind,String station,World world,int x,int y,int z,int degrees) {
        main();
        try {
            if(!world.isChunkLoaded(x>>4,z>>4))return "chunk niewczytany";
            container(world.getBlockAt(x,y,z));
            if(models==null || !models.ready(kind.model))return "brak wczytanego modelu BetterModel "+kind.model;
            Fixture f=planned(id,kind,station,world,x,y,z,degrees,"check");
            var existing=registered(id);
            if(existing.isPresent())return existing.get().key().equals(f.key()) && existing.get().kind()==kind?null:"id zajęte przez inne stanowisko";
            validateSite(f,true);return null;
        }catch(IllegalArgumentException e){return e.getMessage();}
    }
    /** Registers the planned fixture; idempotent when the same id already points at the same container. */
    public boolean registerAt(String id,SupplyKind kind,String station,World world,int x,int y,int z,int degrees) {
        main();coordinator.requireReady();
        var existing=registered(id);
        if(existing.isPresent()){if(existing.get().x()==x && existing.get().y()==y && existing.get().z()==z && existing.get().kind()==kind)return false;throw new IllegalArgumentException("Id "+id+" zajęte przez inne stanowisko.");}
        container(world.getBlockAt(x,y,z));
        if(models==null || !models.ready(kind.model))throw new IllegalArgumentException("Brak wczytanego modelu BetterModel: "+kind.model);
        Fixture f=planned(id,kind,station,world,x,y,z,degrees,UUID.randomUUID().toString());
        validateSite(f,true);coordinator.register(f);return true;
    }
    public int stockAt(String id){main();var f=registered(id);if(f.isEmpty())return -1;try{return coordinator.stock(f.get());}catch(RuntimeException e){return -1;}}
    /** Plain items in a not-yet-registered container (coal or water buckets) for the launch report. */
    public int plainItems(World world,int x,int y,int z,SupplyKind kind){
        main();if(!world.isChunkLoaded(x>>4,z>>4))return -1;
        try{Container c=container(world.getBlockAt(x,y,z));int n=0;Material m=Material.valueOf(kind.material());for(ItemStack i:c.getInventory().getStorageContents())if(i!=null && i.getType()==m && !i.hasItemMeta())n+=i.getAmount();return n;}
        catch(RuntimeException e){return -1;}
    }
    /** Sneak-right-click inspects the real source without consuming items or opening another inventory. */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void inspect(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND || event.getAction()!=Action.RIGHT_CLICK_BLOCK || !event.getPlayer().isSneaking() || coordinator.state()==null)return;
        Block b=event.getClickedBlock();if(b==null)return;
        for(Fixture f:coordinator.state().fixtures.values())if(f.world().equals(b.getWorld().getUID().toString()) && f.x()==b.getX() && f.y()==b.getY() && f.z()==b.getZ()) {
            if(!plugin.isManager(event.getPlayer()))return;
            event.setCancelled(true);
            try{plugin.message(event.getPlayer(),f.id()+": "+coordinator.stock(f)+" szt. "+f.kind().material()+"; /kolej infrastruktura obsluz "+f.id()+" <pociag> <ilosc>");}
            catch(IllegalArgumentException e){plugin.message(event.getPlayer(),e.getMessage());}return;
        }
    }
    private static int integer(String s){try{return Integer.parseInt(s);}catch(NumberFormatException e){throw new IllegalArgumentException("Oczekiwano liczby całkowitej.");}}
    private static void count(String[] a,int n,String usage){if(a.length!=n)throw new IllegalArgumentException("/kolej infrastruktura "+usage);}
    private World world(Fixture f){return Bukkit.getWorld(UUID.fromString(f.world()));}
    private void near(Player p,Fixture f){World w=world(f);if(w==null || !w.equals(p.getWorld()) || p.getLocation().distanceSquared(new Location(w,f.x()+.5,f.y()+.5,f.z()+.5))>36)throw new IllegalArgumentException("Podejdź do stanowiska (6 bloków).");}
    private static Container container(Block block) {
        var state=block.getState();
        if(state instanceof Barrel)return (Container)state;
        if(state instanceof Chest && block.getBlockData() instanceof org.bukkit.block.data.type.Chest chest && chest.getType()==org.bukkit.block.data.type.Chest.Type.SINGLE)return (Container)state;
        throw new IllegalArgumentException("Stanowisko wymaga pojedynczej skrzyni lub beczki; bez podwójnych skrzyń i shulkerów.");
    }
    private void territory(World w,int x,int y,int z) {
        String denial=protection.territory(w,x,y,z,plugin.data().ownerCountry);
        if(denial!=null)throw new IllegalArgumentException(denial);
    }
    private void validateSource(Fixture f) {
        World w=world(f);if(w==null || !w.isChunkLoaded(f.x()>>4,f.z()>>4))throw new IllegalArgumentException("Świat/chunk stanowiska niewczytany.");
        TrackNode n=plugin.graph().node(f.station());
        if(n==null || !n.isWaitingPoint() || !n.world().equals(w.getName()) || n.point().sub(new pl.medivalrpg.kolej.geom.Vec3(f.x()+.5,f.y()+1,f.z()+.5)).length()>32)
            throw new IllegalArgumentException("Stanowisko musi znajdować się do 32 bloków od stacji lub bazy.");
        territory(w,f.x(),f.y(),f.z());Block source=w.getBlockAt(f.x(),f.y(),f.z());container(source);
        if(!source.getType().name().equals(f.block()))throw new IllegalArgumentException("Zmieniono typ kontenera stanowiska.");
        if(container(source).getPersistentDataContainer().has(freightReceipt,PersistentDataType.STRING))throw new IllegalArgumentException("Magazyn ładunkowy nie może być stanowiskiem tankowania.");
    }
    private void validateSite(Fixture f,boolean placement) {
        validateSource(f);World w=world(f);
        var b=f.kind().bounds(f.rotation());
        for(int x=(int)Math.floor(f.x()+.5+b.minX());x<=Math.floor(f.x()+.5+b.maxX());x++)
            for(int z=(int)Math.floor(f.z()+.5+b.minZ());z<=Math.floor(f.z()+.5+b.maxZ());z++)
                for(int y=f.y()+1;y<f.y()+1+b.height();y++) {
                    territory(w,x,y,z);
                    if(!w.getBlockAt(x,y,z).getType().isAir())throw new IllegalArgumentException("Bryła modelu wymaga wolnego miejsca nad kontenerem.");
                }
        if(InfrastructureClearance.conflicts(f,w.getName(),plugin.graph(),plugin.data().depots))throw new IllegalArgumentException("Model wchodzi w skrajnię toru, stacji lub manewrów bazy.");
        if(placement)for(Fixture other:coordinator.state().fixtures.values()) {
            if(!other.world().equals(f.world()))continue;
            var ob=other.kind().bounds(other.rotation());
            var translated=new SupplyKind.Bounds(ob.minX()+other.x()-f.x(),ob.minZ()+other.z()-f.z(),ob.maxX()+other.x()-f.x(),ob.maxZ()+other.z()-f.z(),ob.height());
            if(b.intersects(translated) && f.y()+1<other.y()+1+ob.height() && other.y()+1<f.y()+1+b.height())throw new IllegalArgumentException("Model koliduje z innym stanowiskiem.");
        }
    }
    private record Token(Material material,ItemStack[] items) {}
    private static ItemStack[] clones(ItemStack[] items){return Arrays.stream(items).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);}
    private static List<Slot> slots(ItemStack[] items) {
        var slots=new ArrayList<Slot>();
        for(ItemStack i:items) {
            if(i==null || i.getType().isAir() || i.getAmount()==0){slots.add(Slot.empty());continue;}
            boolean plain=Set.of(Material.COAL,Material.WATER_BUCKET,Material.BUCKET).contains(i.getType()) && !i.hasItemMeta() && i.isSimilar(new ItemStack(i.getType()));
            slots.add(new Slot(i.getType().name(),i.getAmount(),plain,i.getMaxStackSize()));
        }
        return List.copyOf(slots);
    }
    private final class BukkitPort implements Port {
        public TrainView train(String id,Fixture f) {
            Train t=plugin.data().trains.get(id);if(t==null)return null;
            Location loc=plugin.runtime().location(t,0);World w=world(f);
            boolean eligible=plugin.runtime().canService(t) && loc!=null && w!=null && w.equals(loc.getWorld()) && loc.distanceSquared(new Location(w,f.x()+.5,f.y()+1,f.z()+.5))<=16*16;
            return new TrainView(t.id,t.node,eligible,t.steam.coal,t.steam.water);
        }
        public Snapshot inventory(Fixture f) {
            World w=world(f);if(w==null || !w.isChunkLoaded(f.x()>>4,f.z()>>4))return null;
            validateSite(f,false);Block block=w.getBlockAt(f.x(),f.y(),f.z());Container c=container(block);
            ItemStack[] items=clones(c.getInventory().getStorageContents());
            return new Snapshot(f,c.getPersistentDataContainer().get(receipt,PersistentDataType.STRING),slots(items),new Token(block.getType(),items));
        }
        public boolean apply(Snapshot before,List<Slot> after,String nextReceipt) {
            Snapshot current=inventory(before.fixture());if(current==null)return false;
            Token a=(Token)before.token(),b=(Token)current.token();
            if(!Objects.equals(before.receipt(),current.receipt()) || a.material()!=b.material() || !Arrays.equals(a.items(),b.items()))return false;
            if(after.size()!=b.items().length)throw new IllegalStateException("Zmieniono wielkość kontenera.");
            ItemStack[] replacement=clones(b.items());
            for(int i=0;i<after.size();i++)if(!after.get(i).equals(before.slots().get(i))) {
                Slot s=after.get(i);replacement[i]=s.amount()==0?null:new ItemStack(Material.valueOf(s.material()),s.amount());
            }
            Fixture f=before.fixture();Block block=world(f).getBlockAt(f.x(),f.y(),f.z());
            container(block).getInventory().setStorageContents(replacement);
            Container fresh=container(block);
            if(nextReceipt==null)fresh.getPersistentDataContainer().remove(receipt);else fresh.getPersistentDataContainer().set(receipt,PersistentDataType.STRING,nextReceipt);
            if(!fresh.update(false,false))throw new IllegalStateException("Nie zapisano znacznika kontenera — pozostawiono zamiar tankowania.");
            return true;
        }
        public boolean debit(long cost,String tx){return plugin.economy().transfer(RailData.ACCOUNT,"kolej:obsluga",cost,"Obsługa kolei "+tx)==TxResult.OK;}
        public boolean refund(long cost,String tx){return plugin.economy().transfer("kolej:obsluga",RailData.ACCOUNT,cost,"Zwrot obsługi kolei "+tx)==TxResult.OK;}
        public boolean credit(TrainView before,double coal,double water) {
            Train t=plugin.data().trains.get(before.id());
            if(t==null || !plugin.runtime().canService(t) || !Objects.equals(t.node,before.station()) || Double.compare(t.steam.coal,before.coal())!=0 || Double.compare(t.steam.water,before.water())!=0)return false;
            t.steam.coal=coal;t.steam.water=water;return true;
        }
        public java.util.concurrent.CompletableFuture<Void> persistTender(String train,String transaction) {
            Train t=plugin.data().trains.get(train);
            if(t==null)return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Zniknął pociąg podczas zapisu tendera."));
            t.supplyReceipt=transaction;return plugin.saveDurable();
        }
        public java.util.concurrent.CompletableFuture<Void> confirmEconomy(String transaction) {
            try {
                var provider=Bukkit.getPluginManager().getPlugin("Skarbiec");
                var registration=Bukkit.getServicesManager().getRegistration(pl.medivalrpg.skarbiec.api.SkarbiecApi.class);
                if(provider==null || !provider.isEnabled() || registration==null || registration.getPlugin()!=provider || registration.getProvider()!=plugin.economy())throw new IllegalStateException("Brak zgodnego providera trwałego zapisu ekonomii.");
                Object result=provider.getClass().getMethod("bankDurable").invoke(provider);
                if(!(result instanceof java.util.concurrent.CompletableFuture<?> durable))throw new IllegalStateException("Provider nie zwrócił potwierdzenia bankDurable.");
                // Mapping only the result type is safe on the writer thread. State transitions/Bukkit stay in tick().
                return durable.thenApply(ignored->(Void)null).orTimeout(10,java.util.concurrent.TimeUnit.SECONDS);
            }catch(Exception e){return java.util.concurrent.CompletableFuture.failedFuture(e);}
        }
        public void audit(Pending p,String outcome){if(outcome.equals("WYKONANO"))retry.remove(p.train());else alert(p.train(),p.fixture()+": "+outcome);plugin.getLogger().info("Tankowanie "+p.id()+" "+outcome+": "+p.fixture()+" → "+p.train()+", szt. "+p.quantity()+", koszt "+p.cost()+" md");
            // Visual only, after the durable transfer: fixture animation (crane swings towards the tender) and effects.
            if(outcome.equals("WYKONANO") && models!=null && coordinator.state()!=null)try {
                Fixture f=coordinator.state().fixtures.get(p.fixture());World w=f==null?null:world(f);Train t=plugin.data().trains.get(p.train());
                if(w!=null)models.service("infrastructure/"+f.id()+"/",f.kind().model,new Location(w,f.x()+.5,f.y()+1,f.z()+.5,f.rotation()*90,0),t==null?null:plugin.runtime().location(t,8),f.kind().coal);
            }catch(RuntimeException | LinkageError ignored){}
        }
    }
    @Override public void close(){main();coordinator.close();store.close();if(models!=null)models.close();}
}
