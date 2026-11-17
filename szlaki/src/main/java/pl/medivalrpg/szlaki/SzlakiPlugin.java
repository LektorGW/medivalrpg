// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.szlaki;

import static pl.medivalrpg.szlaki.Model.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/** Physical, bounded, manager-defined transport. Entities are views; sealed manifest is authoritative. */
public final class SzlakiPlugin extends JavaPlugin implements Listener {
    private KrajeApi countries;
    private SkarbiecApi economy;
    private State state;
    private StateStore store;
    private NamespacedKey tag;
    private boolean failed;
    private long ticks;
    private final Map<UUID,List<Entity>> visuals=new HashMap<>();
    private final Map<UUID,Point> previous=new HashMap<>();
    private final Map<UUID,Integer> stalled=new HashMap<>();
    private final Map<UUID,Long> repairs=new HashMap<>();
    private final Map<UUID,UUID> repairers=new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig(); tag=new NamespacedKey(this,"shipment");
        countries=getServer().getServicesManager().load(KrajeApi.class);
        economy=getServer().getServicesManager().load(SkarbiecApi.class);
        if(countries==null || economy==null) { getLogger().severe("Brak API Kraje lub Skarbiec."); getServer().getPluginManager().disablePlugin(this); return; }
        store=new StateStore(getDataFolder().toPath());
        try { state=store.load(); persist(); }
        catch(Exception e) { getLogger().log(java.util.logging.Level.SEVERE,"Odmowa uruchomienia: dziennik wymaga audytu.",e); getServer().getPluginManager().disablePlugin(this); return; }
        for(World w:Bukkit.getWorlds()) for(Chunk c:w.getLoadedChunks()) clean(c);
        getServer().getPluginManager().registerEvents(this,this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,event -> {
            var root=Commands.literal("szlaki").executes(ctx -> { help(ctx.getSource().getSender()); return 1; });
            for(String name:List.of("lista","trasa","info","nowa","punkt","przystanek","skrzynia","towar","rozklad","zatwierdz","start","stop","napad","przejmij","napraw","audyt")) {
                root.then(Commands.literal(name)
                    .executes(ctx -> execute(ctx.getSource().getSender(),name,""))
                    .then(Commands.argument("parametry",StringArgumentType.greedyString())
                        .suggests((ctx,builder) -> {
                            // Completion reads immutable strings only, no Bukkit access on the completion thread.
                            builder.suggest("pomoc",new com.mojang.brigadier.LiteralMessage("Składnia: /szlaki — interaktywna pomoc"));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> execute(ctx.getSource().getSender(),name,StringArgumentType.getString(ctx,"parametry")))));
            }
            event.registrar().register(root.build(),"Kupieckie karawany i konwoje");
        });
        Bukkit.getScheduler().runTaskTimer(this,this::tick,1,1);
        getLogger().info("Szlaki by Maciej Kaczmarczyk — lektoro.pl");
    }
    @Override public void onDisable() {
        if(state!=null && !failed) { try { checkpoint(); } catch(Exception e) { getLogger().severe(e.toString()); } }
        for(UUID id:new ArrayList<>(visuals.keySet())) removeVisual(id);
        if(store!=null) store.close();
    }
    private void persist() {
        try { store.save(state); }
        catch(RuntimeException e) { failed=true; for(List<Entity> list:visuals.values()) list.forEach(x->x.setVelocity(new Vector())); throw e; }
    }
    private Point point(Location l) { return new Point(l.getWorld().getName(),l.getX(),l.getY(),l.getZ()); }
    private Location location(Point p) { World w=Bukkit.getWorld(p.world()); return w==null?null:new Location(w,p.x(),p.y(),p.z()); }
    private boolean loaded(Point p) { World w=Bukkit.getWorld(p.world()); return w!=null && w.isChunkLoaded(p.cx(),p.cz()); }
    private boolean bounded(Point p) {
        return p.world().equals(getConfig().getString("terrain.world","world"))
            && Math.hypot(p.x()-getConfig().getDouble("terrain.center-x"),p.z()-getConfig().getDouble("terrain.center-z"))<=Math.min(10000,Math.max(16,getConfig().getDouble("terrain.radius",2000)))
            && Double.isFinite(p.y()) && p.y()>-64 && p.y()<320;
    }
    private void require(boolean ok,String message) { if(!ok) throw new IllegalArgumentException(message); }
    private boolean manager(Player p) { return p.hasPermission("szlaki.zarzadzanie") || getConfig().getStringList("managers").contains(p.getUniqueId().toString()); }
    private UUID country(Player p) { return countries.getCountryOf(p.getUniqueId()).map(c->c.id()).orElse(null); }
    private boolean owned(Chest c,UUID owner) { return countries.getCountryAt(c.world(),c.x()>>4,c.z()>>4).map(x->x.id().equals(owner)).orElse(false); }
    private boolean active(Route r) { return state.jobs.values().stream().anyMatch(j->j.route.equals(r.id) && j.active()); }
    private Route route(String id) { Route r=state.routes.get(id); require(r!=null,"Nieznana trasa."); return r; }
    private Route editable(Player p,String id) {
        Route r=route(id); require(manager(p) && (r.manager.equals(p.getUniqueId()) || p.hasPermission("szlaki.admin")),"Tylko zarządca tej firmy.");
        require(!active(r),"Trasa ma aktywny lub zatrzymany przewóz; nie można zmienić manifestu."); return r;
    }
    private Job job(String id) { Job j=state.jobs.get(UUID.fromString(id)); require(j!=null,"Nieznany przewóz."); return j; }
    private double distance(Player p,Job j) { return point(p.getLocation()).distance(j.position); }
    private int execute(org.bukkit.command.CommandSender sender,String action,String input) {
        try {
            require(!failed,"Operacje zablokowane po błędzie zapisu. Sprawdź log i dziennik.");
            String[] a=input.isBlank()?new String[0]:input.trim().split("\\s+");
            if(action.equals("lista")) {
                for(Route r:state.routes.values()) sender.sendMessage(r.id+" "+r.mode+" zatwierdzona="+r.approved+" aktywna="+r.enabled);
                for(Job j:state.jobs.values()) if(j.active()) sender.sendMessage(j.id+" "+j.route+" "+j.phase+" HP="+j.hp);
                return 1;
            }
            if(action.equals("info") || action.equals("audyt")) {
                if(action.equals("audyt")) require(sender.hasPermission("szlaki.admin"),"Brak uprawnienia administratora.");
                require(a.length==1,"Podaj UUID przewozu."); Job j=job(a[0]); Route r=route(j.route);
                sender.sendMessage(j.id+" / "+j.phase+" / "+j.position+" / punkt "+j.node+" / HP "+j.hp+" / "+j.cargoQuantity+" "+j.cargoMaterial+" / opłata "+j.contractFee+" / escrow "+economy.balance(j.escrow())+" md / "+j.note); return 1;
            }
            if(action.equals("trasa")) {
                require(a.length==1,"/szlaki trasa <id>"); Route r=route(a[0]);
                sender.sendMessage(r.id+" / "+r.mode+" / zarządca "+r.manager+" / kraj "+r.country+" → "+r.destinationCountry+" / "+r.quantity+" "+r.material+" / co "+r.intervalSeconds+" s / "+r.fee+" md");
                sender.sendMessage("Załadunek: "+r.source+" / rozładunek: "+r.destination);
                for(int i=0;i<r.points.size();i++) sender.sendMessage(i+": "+r.points.get(i)+(r.stops.contains(i)?" — przystanek 10 s":"")); return 1;
            }
            require(sender instanceof Player,"Ta komenda wymaga gracza."); Player p=(Player)sender;
            switch(action) {
                case "nowa" -> {
                    require(manager(p),"Brak uprawnienia zarządcy."); require(a.length==3,"/szlaki nowa <id> <drogowa|morska> <UUID kraju>");
                    require(a[0].matches("[a-z0-9_-]{1,32}") && !state.routes.containsKey(a[0]),"Niepoprawny lub zajęty identyfikator.");
                    require(state.routes.size()<Math.min(64,getConfig().getInt("limits.routes",64)),"Limit tras.");
                    UUID owner=UUID.fromString(a[2]); require(countries.getCountry(owner).isPresent(),"Nieznany kraj.");
                    require(owner.equals(country(p)) || p.hasPermission("szlaki.admin"),"Firma musi działać dla twojego kraju.");
                    Route r=new Route(); r.id=a[0]; r.manager=p.getUniqueId(); r.country=owner; r.destinationCountry=owner;
                    r.mode=switch(a[1]) { case "drogowa" -> Mode.ROAD; case "morska" -> Mode.SEA; default -> throw new IllegalArgumentException("Tryb drogowa albo morska."); };
                    r.material="WHEAT"; r.quantity=64; r.intervalSeconds=300; state.routes.put(r.id,r); persist();
                }
                case "punkt" -> {
                    require(a.length==1 || (a.length==2 && a[1].equals("wyczysc")),"/szlaki punkt <trasa> [wyczysc] — dodaj swoją pozycję lub wyczyść projekt"); Route r=editable(p,a[0]);
                    if(a.length==2) { r.points.clear(); r.stops.clear(); r.edited(); persist(); break; }
                    Point pos=point(p.getLocation());
                    require(bounded(pos) && r.points.size()<512,"Poza terenem lub limit punktów.");
                    require(r.points.isEmpty() || (pos.distance(r.points.getLast())<=8 && pos.distance(r.points.getLast())>=.1),"Punkty co 0,1–8 bloków, jeden świat.");
                    r.points.add(pos); r.edited(); persist();
                }
                case "przystanek" -> {
                    require(a.length==2,"/szlaki przystanek <trasa> <indeks od 0>"); Route r=editable(p,a[0]); int i=Integer.parseInt(a[1]);
                    require(i>=0 && i<r.points.size(),"Nieznany punkt."); if(!r.stops.add(i)) r.stops.remove(i); r.edited(); persist();
                }
                case "skrzynia" -> {
                    require(a.length==2,"/szlaki skrzynia <trasa> <poczatek|koniec> — patrz na pojedynczą skrzynię"); Route r=editable(p,a[0]);
                    Chest c=target(p);
                    if(a[1].equals("poczatek")) { require(owned(c,r.country),"Załadunek w kraju firmy."); r.source=c; }
                    else if(a[1].equals("koniec")) {
                        r.destinationCountry=countries.getCountryAt(c.world(),c.x()>>4,c.z()>>4).orElseThrow(()->new IllegalArgumentException("Rozładunek na terytorium kraju.")).id(); r.destination=c;
                    } else throw new IllegalArgumentException("poczatek albo koniec");
                    r.edited(); persist();
                }
                case "towar" -> {
                    require(a.length==3,"/szlaki towar <trasa> <MATERIAL> <ilość>"); Route r=editable(p,a[0]); Material m=Material.matchMaterial(a[1]);
                    require(m!=null && m.isItem() && m!=Material.AIR && m.getMaxStackSize()>1 && !m.name().contains("SHULKER"),"Tylko zwykły, stackowalny towar bez zawartości.");
                    int n=Integer.parseInt(a[2]); require(n>0 && n<=1728,"Ilość 1–1728."); r.material=m.name(); r.quantity=n; r.edited(); persist();
                }
                case "rozklad" -> {
                    require(a.length==3,"/szlaki rozklad <trasa> <sekundy> <opłata md>"); Route r=editable(p,a[0]); long seconds=Long.parseLong(a[1]),fee=Long.parseLong(a[2]);
                    require(seconds>=60 && seconds<=604800 && fee>=0 && fee<=1_000_000_000L,"Okres 60–604800 s, opłata 0–1 mld md.");
                    r.intervalSeconds=seconds; r.fee=fee; r.edited(); persist();
                }
                case "zatwierdz" -> {
                    require(p.hasPermission("szlaki.admin"),"Wymagane zatwierdzenie administratora."); require(a.length==1,"/szlaki zatwierdz <trasa>"); Route r=route(a[0]);
                    require(!active(r),"Aktywny przewóz."); r.validate(); require(owned(r.source,r.country) && owned(r.destination,r.destinationCountry),"Właściciele terenu skrzyń zmienili się.");
                    for(Point pos:r.points) require(bounded(pos) && loaded(pos) && terrain(pos,r.mode),"Punkt poza terenem, niezaładowany albo nieprzejezdny.");
                    require(container(r.source)!=null && container(r.destination)!=null,"Wymagane pojedyncze skrzynie bez widzów.");
                    r.approved=true; persist();
                }
                case "start", "stop" -> {
                    require(a.length==1,"/szlaki start|stop <trasa>"); Route r=route(a[0]);
                    require(manager(p) && (r.manager.equals(p.getUniqueId()) || p.hasPermission("szlaki.admin")),"Tylko zarządca firmy.");
                    if(action.equals("start")) { r.validate(); require(r.approved,"Trasa wymaga zatwierdzenia."); }
                    r.enabled=action.equals("start"); persist();
                }
                case "przejmij" -> {
                    require(a.length==1,"/szlaki przejmij <UUID przewozu> — cały ładunek do twojego ekwipunku");
                    capture(p,job(a[0]));
                }
                case "napad" -> {
                    require(a.length==1,"/szlaki napad <UUID przewozu> — wojenny atak z bliska");
                    Job j=job(a[0]); Route r=route(j.route); UUID enemy=country(p);
                    require((j.phase==Phase.MOVING || j.phase==Phase.DISABLED) && loaded(j.position) && visuals.containsKey(j.id)
                        && distance(p,j)<=4 && p.hasLineOfSight(visuals.get(j.id).getFirst()) && enemy!=null && !enemy.equals(r.country) && countries.isAtWar(enemy,r.country),"Napad wymaga widocznego konwoju wroga, wojny i dystansu do 4 bloków.");
                    j.hit(enemy,20,System.currentTimeMillis()); repairs.remove(j.id); repairers.remove(j.id); persist();
                }
                case "napraw" -> {
                    require(a.length==1,"/szlaki napraw <UUID przewozu> — obrona po 30 s bez ataku"); Job j=job(a[0]); Route r=route(j.route); long now=System.currentTimeMillis();
                    require(r.country.equals(country(p)) && distance(p,j)<=6 && j.phase==Phase.DISABLED && now-j.lastHit>=30000,"Naprawia obywatel kraju firmy, do 6 bloków, po 30 s bez ataku.");
                    // Two-step defence: remain nearby for ten seconds; taking another hit invalidates it.
                    Long begin=repairs.get(j.id);
                    if(begin==null || begin<j.lastHit || !p.getUniqueId().equals(repairers.get(j.id))) { repairs.put(j.id,now); repairers.put(j.id,p.getUniqueId()); p.sendMessage("Broń konwoju przez 10 s, następnie ponów /szlaki napraw."); return 1; }
                    require(now-begin>=10000,"Obrona musi trwać co najmniej 10 s.");
                    j.hp=100; j.phase=Phase.MOVING; repairs.remove(j.id); repairers.remove(j.id); persist();
                }
                default -> help(sender);
            }
            sender.sendMessage(MiniMessage.miniMessage().deserialize(getConfig().getString("messages.prefix","")+"<green>Zapisano operację.</green>"));
        } catch(IllegalArgumentException e) { sender.sendMessage(e.getMessage()==null?"Niepoprawne parametry.":e.getMessage()); }
        catch(Exception e) { getLogger().log(java.util.logging.Level.SEVERE,"Operacja Szlaków przerwana",e); sender.sendMessage("Operacja przerwana. Sprawdź dziennik; nie ponawiaj ręcznie przeładunku."); }
        return 1;
    }
    private void help(org.bukkit.command.CommandSender sender) {
        sender.sendMessage("Szlaki: zarządca projektuje trasę; administrator zatwierdza; start uruchamia rozkład. Stop kończy nowe odjazdy.");
        for(String s:List.of("lista","trasa <id>","nowa <id> <drogowa|morska> <UUID kraju>","punkt <trasa> [wyczysc]","przystanek <trasa> <indeks>","skrzynia <trasa> <poczatek|koniec>","towar <trasa> <MATERIAL> <ilość>","rozklad <trasa> <sekundy> <opłata md>","zatwierdz <trasa>","start <trasa>","stop <trasa>","info <UUID przewozu>","napad <UUID przewozu>","przejmij <UUID przewozu>","napraw <UUID przewozu>","audyt <UUID przewozu>"))
            sender.sendMessage(Component.text("/szlaki "+s).clickEvent(ClickEvent.suggestCommand("/szlaki "+s.split(" ")[0]+" ")).hoverEvent(HoverEvent.showText(Component.text("Kliknij, aby wpisać komendę"))));
    }

    private Chest target(Player p) {
        Block b=p.getTargetBlockExact(5); require(b!=null && b.getType()==Material.CHEST,"Patrz na pojedynczą zwykłą skrzynię do 5 bloków.");
        Chest c=new Chest(b.getWorld().getName(),b.getX(),b.getY(),b.getZ()); require(container(c)!=null,"Skrzynia musi być pojedyncza, bez widzów."); return c;
    }
    private Container container(Chest c) {
        if(c==null || !loaded(c.point())) return null;
        Block b=location(c.point()).getBlock();
        if(b.getType()!=Material.CHEST || !(b.getState() instanceof org.bukkit.block.Chest chest)) return null;
        if(((org.bukkit.block.data.type.Chest)b.getBlockData()).getType()!=org.bukkit.block.data.type.Chest.Type.SINGLE) return null;
        if(!chest.getInventory().getViewers().isEmpty()) return null;
        return chest;
    }
    private int[] inventoryPlan(Inventory inventory,Route r,boolean loading) {
        ItemStack expected=new ItemStack(Material.valueOf(r.material)); int[] slots=new int[inventory.getStorageContents().length];
        for(int i=0;i<slots.length;i++) {
            ItemStack item=inventory.getItem(i);
            slots[i]=item==null || item.getType().isAir()?0:item.isSimilar(expected)?item.getAmount():-1;
        }
        if(loading) { int[] only=slots.clone(); for(int i=0;i<only.length;i++) only[i]=Math.max(0,only[i]);
            int[] plan=Model.take(only,r.quantity); if(plan==null) return null;
            for(int i=0;i<plan.length;i++) if(slots[i]<0) plan[i]=-1; return plan;
        }
        return Model.put(slots,r.quantity,expected.getMaxStackSize());
    }
    private void apply(Inventory inv,Route r,int[] plan) {
        for(int i=0;i<plan.length;i++) if(plan[i]>=0) inv.setItem(i,plan[i]==0?null:new ItemStack(Material.valueOf(r.material),plan[i]));
    }
    private void dispatch(Route r,long now) {
        if(!r.enabled || !r.approved || active(r) || now<r.nextDeparture || state.jobs.size()>=10000 || state.jobs.values().stream().filter(Job::active).count()>=Math.min(16,getConfig().getInt("limits.active",16))) return;
        if(!owned(r.source,r.country) || !owned(r.destination,r.destinationCountry) || locked(r.source) || locked(r.destination) || !loaded(r.points.getFirst())) return;
        Container source=container(r.source); if(source==null || inventoryPlan(source.getInventory(),r,true)==null) return;
        Job j=new Job(); j.route=r.id; j.position=r.points.getFirst();
        j.cargoMaterial=r.material; j.cargoQuantity=r.quantity; j.contractFee=r.fee; j.payerCountry=r.country; j.payee=r.manager;
        state.jobs.put(j.id,j); r.nextDeparture=now+r.intervalSeconds*1000; persist();
        fund(j);
    }
    private void fund(Job j) {
        Route r=route(j.route);
        long balance=economy.balance(j.escrow());
        if(r.fee>0 && balance==0 && !economy.transfer(SkarbiecApi.countryAccount(r.country),j.escrow(),r.fee,"Szlaki escrow "+j.id).ok()) {
            j.phase=Phase.DONE; j.note="Brak środków; bez załadunku."; persist(); return;
        }
        if(economy.balance(j.escrow())!=r.fee) { quarantine(j,"Nieoczekiwane saldo escrow."); return; }
        Container source=container(r.source); int[] plan=source==null?null:inventoryPlan(source.getInventory(),r,true);
        if(plan==null || !owned(r.source,r.country)) { j.captured=true; j.phase=Phase.PAYING; persist(); settle(j); return; }
        j.phase=Phase.LOADING; persist(); // durable intent BEFORE removal; restart => quarantine
        apply(source.getInventory(),r,plan); source.getWorld().save();
        j.phase=Phase.MOVING; persist();
    }
    private void deliver(Job j,Chest destination,UUID recipient,boolean captured) {
        Route r=route(j.route); Container c=container(destination); require(c!=null,"Skrzynia niedostępna lub ma widzów.");
        int[] plan=inventoryPlan(c.getInventory(),r,false); require(plan!=null,"Brak miejsca na cały ładunek.");
        j.unloadingChest=destination; j.recipient=recipient; j.captured=captured; j.phase=Phase.UNLOADING; persist();
        apply(c.getInventory(),r,plan); c.getWorld().save();
        j.phase=Phase.PAYING; persist(); removeVisual(j.id); settle(j);
    }
    private void settle(Job j) {
        Route r=route(j.route); long amount=economy.balance(j.escrow());
        Settlement decision=Model.settlement(amount,r.fee);
        if(decision==Settlement.ALREADY_PAID) { j.phase=Phase.DONE; persist(); return; }
        if(decision==Settlement.QUARANTINE) { quarantine(j,"Nieoczekiwane saldo podczas rozliczenia."); return; }
        String to=j.captured?SkarbiecApi.countryAccount(r.country):SkarbiecApi.playerAccount(r.manager);
        if(economy.transfer(j.escrow(),to,amount,"Szlaki "+(j.captured?"zwrot ":"przewóz ")+j.id).ok()) { j.phase=Phase.DONE; persist(); }
    }
    private void capture(Player p,Job j) {
        Route r=route(j.route); UUID enemy=country(p);
        require(loaded(j.position) && visuals.containsKey(j.id) && visuals.get(j.id).getFirst().isValid()
            && p.hasLineOfSight(visuals.get(j.id).getFirst()),"Konwój musi być widoczny i fizycznie obecny w załadowanym chunku.");
        require(j.canCapture(enemy,r.country,enemy!=null && countries.isAtWar(enemy,r.country),distance(p,j)),"Przejęcie tylko unieruchomionego konwoju wroga w trwającej wojnie, do 6 bloków.");
        // Loot stays at the ambush site, in the verified raider's real inventory.
        require(p.getOpenInventory().getTopInventory().getType()==InventoryType.CRAFTING,"Zamknij otwarty pojemnik przed przejęciem.");
        int[] plan=inventoryPlan(p.getInventory(),r,false); require(plan!=null,"Potrzebujesz miejsca na cały ładunek.");
        j.unloadingPlayer=p.getUniqueId(); j.recipient=enemy; j.captured=true; j.phase=Phase.UNLOADING; persist();
        apply(p.getInventory(),r,plan); p.saveData();
        j.phase=Phase.PAYING; persist(); removeVisual(j.id); settle(j);
        p.sendMessage("Przejęto cały ładunek do ekwipunku na miejscu napadu. Opłata wraca do kraju zlecającego.");
    }
    private void quarantine(Job j,String note) { j.phase=Phase.QUARANTINED; j.note=note; removeVisual(j.id); persist(); }

    private boolean terrain(Point p,Mode mode) {
        if(!bounded(p) || !loaded(p)) return false;
        Location l=location(p); World w=l.getWorld();
        if(!w.getWorldBorder().isInside(l)) return false;
        // Full carrier envelope, no loading neighbouring chunks as a side effect.
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
            Point q=new Point(p.world(),p.x()+dx,p.y(),p.z()+dz); if(!loaded(q) || !bounded(q)) return false;
            Block feet=w.getBlockAt((int)Math.floor(q.x()),(int)Math.floor(q.y()),(int)Math.floor(q.z()));
            if(mode==Mode.SEA) {
                if(feet.getType()!=Material.WATER && feet.getRelative(0,-1,0).getType()!=Material.WATER) return false;
            } else if(!feet.getRelative(0,-1,0).getType().isSolid() || !feet.isPassable() || feet.isLiquid()) return false;
            if(!feet.getRelative(0,1,0).isPassable() || !feet.getRelative(0,2,0).isPassable()) return false;
        }
        return true;
    }
    private void spawn(Job j) {
        Route r=route(j.route); if(!terrain(j.position,r.mode)) return;
        Location l=location(j.position); List<Entity> entities=new ArrayList<>();
        try {
            Entity carrier=l.getWorld().spawnEntity(l,r.mode==Mode.ROAD?EntityType.DONKEY:EntityType.OAK_BOAT); entities.add(carrier);
            if(carrier instanceof Donkey donkey) { donkey.setAI(false); donkey.setTamed(true); donkey.setCarryingChest(true); donkey.setCollidable(false); }
            Mannequin driver=l.getWorld().spawn(l,Mannequin.class); entities.add(driver); driver.setImmovable(false); driver.setCollidable(false);
            driver.customName(Component.text(r.mode==Mode.ROAD?"Woźnica — "+r.id:"Szyper — "+r.id)); driver.setCustomNameVisible(true);
            driver.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET)); carrier.addPassenger(driver);
            // Vanilla barrel visibly marks the sealed cargo on a sea convoy, no resource pack required.
            if(r.mode==Mode.SEA) {
                ItemDisplay cargo=l.getWorld().spawn(l,ItemDisplay.class); entities.add(cargo); cargo.setItemStack(new ItemStack(Material.BARREL)); carrier.addPassenger(cargo);
            }
            for(Entity e:entities) { e.getPersistentDataContainer().set(tag,PersistentDataType.STRING,j.id.toString()); e.setPersistent(false); e.setGravity(false); }
            visuals.put(j.id,entities); previous.put(j.id,j.position);
        } catch(RuntimeException e) { entities.forEach(Entity::remove); throw e; }
    }
    private void removeVisual(UUID id) {
        List<Entity> list=visuals.remove(id); if(list!=null) list.forEach(Entity::remove); previous.remove(id); stalled.remove(id);
    }
    private Job tagged(Entity e) {
        String id=e.getPersistentDataContainer().get(tag,PersistentDataType.STRING);
        if(id==null) return null;
        try { return state.jobs.get(UUID.fromString(id)); } catch(IllegalArgumentException ex) { return null; }
    }
    private boolean marked(Entity e) { return e.getPersistentDataContainer().has(tag,PersistentDataType.STRING); }
    private void clean(Chunk c) { for(Entity e:c.getEntities()) if(marked(e)) e.remove(); }
    private void checkpoint() {
        for(var entry:visuals.entrySet()) {
            Job j=state.jobs.get(entry.getKey()); Entity lead=entry.getValue().getFirst();
            if(j!=null && lead.isValid()) j.position=point(lead.getLocation());
        }
        persist();
    }
    private void tick() {
        if(failed) return;
        try {
            long now=System.currentTimeMillis(); ticks++;
            for(UUID id:new ArrayList<>(repairs.keySet())) {
                Job defending=state.jobs.get(id); Player defender=Bukkit.getPlayer(repairers.get(id));
                if(defending==null || defender==null || defending.phase!=Phase.DISABLED || !loaded(defending.position)
                    || distance(defender,defending)>6 || !route(defending.route).country.equals(country(defender))) {
                    repairs.remove(id); repairers.remove(id);
                }
            }
            for(Job j:new ArrayList<>(state.jobs.values())) {
                if(j.phase==Phase.FUNDING) { fund(j); continue; }
                if(j.phase==Phase.PAYING) { if(ticks%20==0) settle(j); continue; }
                if(j.phase!=Phase.MOVING && j.phase!=Phase.DISABLED) continue;
                Route r=route(j.route);
                if(!loaded(j.position)) { removeVisual(j.id); continue; }
                if(!visuals.containsKey(j.id)) spawn(j);
                List<Entity> list=visuals.get(j.id); if(list==null) continue;
                Entity lead=list.getFirst();
                if(!lead.isValid()) { removeVisual(j.id); continue; }
                j.position=point(lead.getLocation()); lead.setVelocity(new Vector());
                if(j.phase==Phase.DISABLED || now<j.waitUntil) continue;
                if(j.node>=r.points.size()) {
                    if(owned(r.destination,r.destinationCountry) && !lockedExcept(r.destination,j)) {
                        Container c=container(r.destination);
                        if(c!=null && inventoryPlan(c.getInventory(),r,false)!=null) deliver(j,r.destination,r.destinationCountry,false);
                    }
                    continue;
                }
                Point target=r.points.get(j.node); double distance=j.position.distance(target);
                if(distance<.32) {
                    if(r.stops.contains(j.node)) j.waitUntil=now+10000;
                    j.node++; persist(); continue;
                }
                if(distance>12 || !bounded(j.position)) { j.note="Konwój poza korytarzem; zatrzymany do audytu."; j.phase=Phase.QUARANTINED; removeVisual(j.id); persist(); continue; }
                Vector delta=location(target).toVector().subtract(lead.getLocation().toVector());
                double speed=r.mode==Mode.SEA?.12:.10;
                Vector velocity=delta.normalize().multiply(Math.min(speed,distance));
                // Look ahead covers all crossed chunks before setting physical velocity.
                Point next=point(lead.getLocation().add(velocity.clone().multiply(4)));
                if(!terrain(next,r.mode)) continue;
                Point last=previous.put(j.id,j.position);
                if(last!=null && last.distance(j.position)<.005) stalled.merge(j.id,1,Integer::sum); else stalled.put(j.id,0);
                if(stalled.getOrDefault(j.id,0)>200) { j.note="Przeszkoda fizyczna. Usuń przeszkodę; konwój nie teleportuje się."; stalled.put(j.id,0); continue; }
                lead.setRotation((float)Math.toDegrees(Math.atan2(-velocity.getX(),velocity.getZ())),0);
                lead.setVelocity(velocity);
            }
            if(ticks%20==0) for(Route r:state.routes.values()) dispatch(r,now);
            if(ticks%100==0) { checkpoint(); prune(); }
        } catch(Exception e) { failed=true; for(List<Entity> list:visuals.values()) list.forEach(x->x.setVelocity(new Vector())); getLogger().log(java.util.logging.Level.SEVERE,"Wstrzymano Szlaki po błędzie",e); }
    }
    private void prune() {
        // Keep completed receipts for audit; hard cap refuses new work instead of deleting evidence.
        if(state.jobs.size()>10000) { for(Route r:state.routes.values()) r.enabled=false; persist(); }
    }

    private boolean locked(Chest c) { return lockedExcept(c,null); }
    private boolean lockedExcept(Chest c,Job except) {
        if(c==null) return false;
        if(failed && state.routes.values().stream().anyMatch(r->c.equals(r.source) || c.equals(r.destination))) return true;
        for(Job j:state.jobs.values()) if(j!=except && j.active()) {
            Route r=route(j.route);
            if(c.equals(r.source) || c.equals(r.destination) || c.equals(j.unloadingChest)) return true;
        }
        return false;
    }
    private boolean locked(Block b) { return locked(new Chest(b.getWorld().getName(),b.getX(),b.getY(),b.getZ())); }
    private boolean locked(Inventory inv) {
        return InventoryLocks.locked(inv,this::locked,this::marked);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void damage(EntityDamageEvent e) {
        if(!marked(e.getEntity())) return;
        boolean cancelled=e.isCancelled(); e.setCancelled(true); Job j=tagged(e.getEntity()); if(j==null || failed || cancelled) return;
        Player p=null;
        if(e instanceof EntityDamageByEntityEvent hit) {
            if(hit.getDamager() instanceof Player direct) p=direct;
            else if(hit.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) p=shooter;
        }
        if(p==null) return; UUID enemy=country(p); Route r=route(j.route);
        if(enemy==null || enemy.equals(r.country) || !countries.isAtWar(enemy,r.country) || distance(p,j)>32) return;
        j.hit(enemy,e.getDamage(),System.currentTimeMillis()); repairs.remove(j.id); persist();
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void vehicleDamage(VehicleDamageEvent e) {
        if(!marked(e.getVehicle())) return; boolean cancelled=e.isCancelled(); e.setCancelled(true); if(cancelled || failed) return;
        Job j=tagged(e.getVehicle()); if(j==null) return; Entity attacker=e.getAttacker(); Player p=attacker instanceof Player direct?direct:attacker instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter?shooter:null;
        if(p==null) return; UUID enemy=country(p); Route r=route(j.route);
        if(enemy!=null && !enemy.equals(r.country) && countries.isAtWar(enemy,r.country) && distance(p,j)<=32) { j.hit(enemy,e.getDamage(),System.currentTimeMillis()); repairs.remove(j.id); persist(); }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void destroy(VehicleDestroyEvent e) { if(marked(e.getVehicle())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void enter(VehicleEnterEvent e) { if(marked(e.getVehicle()) && !marked(e.getEntered())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void mount(EntityMountEvent e) { if(marked(e.getMount()) && e.getEntity() instanceof Player) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEntityEvent e) { if(marked(e.getRightClicked())) { e.setCancelled(true); Job j=tagged(e.getRightClicked()); if(j!=null) e.getPlayer().sendMessage("Przewóz "+j.id+" / "+j.phase+" / HP "+j.hp+". Przejęcie: /szlaki przejmij "+j.id); } }
    @EventHandler(priority=EventPriority.HIGHEST) public void interactAt(PlayerInteractAtEntityEvent e) { if(marked(e.getRightClicked())) { e.setCancelled(true); Job j=tagged(e.getRightClicked()); if(j!=null) e.getPlayer().sendMessage("Przewóz "+j.id+" / "+j.phase+" / HP "+j.hp); } }
    @EventHandler(priority=EventPriority.HIGHEST) public void portal(EntityPortalEvent e) { if(marked(e.getEntity())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void teleport(EntityTeleportEvent e) { if(marked(e.getEntity())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void collision(VehicleEntityCollisionEvent e) { if(marked(e.getVehicle())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void leash(PlayerLeashEntityEvent e) { if(marked(e.getEntity())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void open(InventoryOpenEvent e) { if(locked(e.getInventory())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e) { if(locked(e.getView().getTopInventory())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e) { if(locked(e.getView().getTopInventory())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void move(InventoryMoveItemEvent e) { if(locked(e.getSource()) || locked(e.getDestination())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e) { if(locked(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e) {
        for(BlockFaceOffset offset:BlockFaceOffset.values()) if(locked(e.getBlockPlaced().getRelative(offset.x,0,offset.z))) { e.setCancelled(true); return; }
    }
    private enum BlockFaceOffset { N(0,-1),S(0,1),E(1,0),W(-1,0); final int x,z; BlockFaceOffset(int x,int z){this.x=x;this.z=z;} }
    @EventHandler(priority=EventPriority.HIGHEST) public void explode(EntityExplodeEvent e) { e.blockList().removeIf(this::locked); }
    @EventHandler(priority=EventPriority.HIGHEST) public void explode(BlockExplodeEvent e) { e.blockList().removeIf(this::locked); }
    @EventHandler(priority=EventPriority.HIGHEST) public void piston(BlockPistonExtendEvent e) { if(e.getBlocks().stream().anyMatch(b->locked(b) || locked(b.getRelative(e.getDirection())))) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST) public void piston(BlockPistonRetractEvent e) { if(e.getBlocks().stream().anyMatch(this::locked)) e.setCancelled(true); }
    @EventHandler public void load(ChunkLoadEvent e) { clean(e.getChunk()); }
    @EventHandler public void unload(ChunkUnloadEvent e) {
        if(state==null || failed) return;
        boolean changed=false;
        for(var entry:new ArrayList<>(visuals.entrySet())) {
            Entity lead=entry.getValue().getFirst(); Point pos=point(lead.getLocation());
            if(pos.world().equals(e.getWorld().getName()) && pos.cx()==e.getChunk().getX() && pos.cz()==e.getChunk().getZ()) {
                state.jobs.get(entry.getKey()).position=pos; removeVisual(entry.getKey()); changed=true;
            }
        }
        if(changed) persist();
    }
}
