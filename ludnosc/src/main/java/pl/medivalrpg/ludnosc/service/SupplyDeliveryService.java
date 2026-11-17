// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.logic.SupplyLedger;
import pl.medivalrpg.ludnosc.logic.SupplyLedger.*;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.storage.SupplyJournal;
import pl.medivalrpg.ludnosc.storage.SupplyDiskProof;

/** Bukkit adapter for the optional reflection bridge. Both inventories belong to the same durable transfer. */
public final class SupplyDeliveryService implements SupplyLedger.Port, AutoCloseable {
    private final LudnoscPlugin plugin;
    private final SupplyJournal journal;
    private final SupplyLedger ledger;
    private final SupplyDiskProof disk=new SupplyDiskProof();
    private java.nio.file.Path playerData;
    private final NamespacedKey receiptKey;
    private final Map<UUID,Integer> players = new HashMap<>();
    private final Map<BlockPos,Integer> chests = new HashMap<>();
    private int ticks;

    public SupplyDeliveryService(LudnoscPlugin plugin) {
        this.plugin=plugin; receiptKey=new NamespacedKey(plugin,"supply_receipt");
        journal=new SupplyJournal(plugin.getDataFolder().toPath().resolve("supply-receipts"));
        SupplyLedger loaded;
        try {
            Object server=Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            Object list=server.getClass().getMethod("getPlayerList").invoke(server);
            Object storage=list.getClass().getField("playerIo").get(list);
            playerData=((java.io.File)storage.getClass().getMethod("getPlayerDir").invoke(storage)).toPath().toAbsolutePath().normalize();
            loaded=new SupplyLedger(journal,this,journal.load());
        }
        catch (Exception ex) {
            loaded=new SupplyLedger(journal,this,List.of());
            loaded.failClosed("Nie można odczytać dziennika dostaw — wymagany audyt administratora.");
            plugin.getLogger().log(Level.SEVERE,"Dziennik dostaw niedostępny; nowe transfery i chronione inventory zablokowane.",ex);
        }
        ledger=loaded;
    }
    public Map<String,Object> receive(UUID player, UUID country, String id, String material, int amount) {
        if(!Bukkit.isPrimaryThread()) return SupplyLedger.result("UNAVAILABLE","","Odbiór dostawy wymaga głównego wątku serwera.");
        try {
            Material type=Material.matchMaterial(material==null ? "" : material);
            if(type==null || !type.isItem() || type.isAir()) return ledger.reject(id,"Nieprawidłowy materiał dostawy.");
            Payload payload=new Payload(player,country,id,type.name(),amount);
            // Known receipts must remain pollable even after configuration/range/player availability changes.
            return ledger.receivePrepared(payload);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return ledger.quarantine(id,"Nieprawidłowy identyfikator, gracz, kraj, materiał, ilość lub stan dostawy — wymagany audyt, jeśli zapisano receipt.");
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE,"Nie można zarejestrować dostawy",ex);
            return ledger.quarantine(id,"Błąd odczytu lub dziennika — zapisane receipt pozostaje przypisane do kontraktu; wymagany audyt.");
        }
    }
    public void tick() {
        try { ledger.tick(++ticks%20==0); }
        catch (RuntimeException ex) {
            ledger.failClosed("Błąd transferu — inventory zablokowane do audytu.");
            plugin.getLogger().log(Level.SEVERE,"Nierozstrzygnięta dostawa",ex);
        }
    }
    public boolean locked(UUID player) { return ledger!=null && (ledger.faulted() || players.containsKey(player)); }
    public boolean locked(BlockPos position) { return ledger!=null && (ledger.faulted() || chests.containsKey(position)); }
    public boolean locked(Inventory inventory) {
        InventoryHolder h=inventory.getHolder(false);
        if(h instanceof Player p) return locked(p.getUniqueId());
        if(h instanceof DoubleChest d) return holderLocked(d.getLeftSide(false)) || holderLocked(d.getRightSide(false));
        return holderLocked(h);
    }
    private boolean holderLocked(InventoryHolder holder) {
        return holder instanceof Container c && locked(GranaryService.pos(c.getBlock()));
    }
    @Override public void lock(Entry entry) {
        players.merge(entry.payload().player(),1,Integer::sum);
        entry.plan().binding().parts().forEach(p -> chests.merge(p,1,Integer::sum));
    }
    @Override public void unlock(Entry entry) {
        players.computeIfPresent(entry.payload().player(),(p,n) -> n==1 ? null : n-1);
        entry.plan().binding().parts().forEach(p -> chests.computeIfPresent(p,(key,n) -> n==1 ? null : n-1));
    }
    private double range() {
        double configured=plugin.getConfig().getDouble("supply.warehouse-range",16);
        return Double.isFinite(configured) ? Math.max(4,Math.min(32,configured)) : 16;
    }
    private boolean allowed(String material) {
        return plugin.getConfig().getStringList("supply.allowed-materials").stream().anyMatch(s -> material.equals(s.toUpperCase(Locale.ROOT)));
    }
    private boolean near(Player player, Plan plan) {
        for(BlockPos p:plan.binding().parts()) if(!player.getWorld().getName().equals(p.world())
            || player.getLocation().distanceSquared(new Location(player.getWorld(),p.x()+0.5,p.y()+0.5,p.z()+0.5))>range()*range()) return false;
        return true;
    }
    private boolean playerReady(Player p) {
        if(p==null || !p.isOnline() || p.isDead() || p.getGameMode()!=GameMode.SURVIVAL && p.getGameMode()!=GameMode.ADVENTURE) return false;
        if(p.getOpenInventory().getType()!=InventoryType.CRAFTING || !empty(p.getItemOnCursor())) return false;
        return Arrays.stream(p.getOpenInventory().getTopInventory().getContents()).allMatch(SupplyDeliveryService::empty);
    }
    private static boolean empty(ItemStack item) { return item==null || item.getType().isAir() || item.getAmount()==0; }
    private String playerMarker(Player p) { return p.getPersistentDataContainer().getOrDefault(receiptKey,PersistentDataType.STRING,""); }
    private Container container(BlockPos p) {
        World w=Bukkit.getWorld(p.world());
        if(w==null || !w.isChunkLoaded(p.x()>>4,p.z()>>4)) return null;
        return w.getBlockAt(p.x(),p.y(),p.z()).getState() instanceof Container c ? c : null;
    }
    private List<String> markers(Plan plan) {
        var out=new ArrayList<String>();
        for(var pos:plan.binding().parts()) {
            Container c=container(pos); if(c==null) return null;
            out.add(c.getPersistentDataContainer().getOrDefault(receiptKey,PersistentDataType.STRING,""));
        }
        return List.copyOf(out);
    }
    private boolean paused(UUID country) {
        return plugin.royalRebuild()!=null && plugin.royalRebuild().blocks(country);
    }
    @Override public Attempt prepare(Payload payload) {
        if(!plugin.getConfig().getBoolean("supply.enabled",true)) return new Attempt(null,"Odbiór dostaw jest wyłączony.");
        if(!allowed(payload.material()))
            return new Attempt(null,"Materiał nie znajduje się na liście zasobów przyjmowanych przez magazyn.");
        if(plugin.kraje().getCountry(payload.country()).isEmpty() || paused(payload.country())) return new Attempt(null,"Kraj nie istnieje lub trwa przebudowa.");
        Player player=Bukkit.getPlayer(payload.player());
        if(!playerReady(player) || locked(payload.player())) return new Attempt(null,"Gracz musi być online w trybie przetrwania/przygody, zamknąć GUI i opróżnić siatkę wytwarzania.");
        var target=plugin.production().supplyTarget(payload.country(),player.getLocation(),range());
        if(target==null) return new Attempt(null,"Brak dostępnej, załadowanej, wolnej skrzyni w istniejącym magazynie tego kraju w zasięgu dostawy.");
        Material material=Material.valueOf(payload.material());
        var stacks=new ArrayList<ItemStack>();
        for(int left=payload.amount();left>0;) { int n=Math.min(left,material.getMaxStackSize()); stacks.add(new ItemStack(material,n)); left-=n; }
        ItemStack[] source=player.getInventory().getContents();
        // Only the 36 ordinary storage slots supply goods; armor/offhand are retained in the full snapshot.
        int[] amounts=new int[36]; boolean[] eligible=new boolean[36];
        for(int i=0;i<36;i++) if(!empty(source[i])) {
            amounts[i]=source[i].getAmount(); eligible[i]=source[i].getType()==material && !source[i].hasItemMeta();
        }
        var remaining=pl.medivalrpg.ludnosc.logic.SupplyCargo.debit(amounts,eligible,payload.amount());
        if(remaining==null) return new Attempt(null,"Brak wymaganej liczby zwykłych przedmiotów w głównym ekwipunku.");
        ItemStack[] after=source.clone();
        for(int i=0;i<36;i++) if(remaining[i]!=amounts[i]) {
            after[i]=remaining[i]==0 ? null : source[i].clone(); if(after[i]!=null) after[i].setAmount(remaining[i]);
        }
        ItemStack[] destination=target.inventory().getStorageContents();
        var destAfter=ProductionStorageService.plan(destination,stacks,List.of(),target.inventory().getMaxStackSize());
        if(destAfter==null) return new Attempt(null,"Magazyn nie mieści całej dostawy; niczego nie pobrano.");
        var markers=new ArrayList<String>();
        for(var pos:target.binding().parts()) markers.add(container(pos).getPersistentDataContainer().getOrDefault(receiptKey,PersistentDataType.STRING,""));
        Plan plan=new Plan(target.warehouse(),target.binding(),encode(source),encode(after),encode(destination),encode(destAfter),playerMarker(player),markers);
        return new Attempt(plan,"");
    }
    @Override public boolean before(Entry e) {
        Player player=Bukkit.getPlayer(e.payload().player());
        Inventory inventory=plugin.production().supplyInventory(e.payload().country(),e.plan().warehouse(),e.plan().binding());
        return playerReady(player) && near(player,e.plan()) && !paused(e.payload().country()) && inventory!=null && inventory.getViewers().isEmpty()
            && encode(player.getInventory().getContents()).equals(e.plan().sourceBefore())
            && encode(inventory.getStorageContents()).equals(e.plan().targetBefore())
            && playerMarker(player).equals(e.plan().playerMarker()) && e.plan().chestMarkers().equals(markers(e.plan()));
    }
    @Override public void apply(Entry e) {
        if(!before(e)) throw new IllegalStateException("Źródło lub magazyn zmienił się po zapisie zamiaru.");
        Player player=Objects.requireNonNull(Bukkit.getPlayer(e.payload().player()));
        Inventory inventory=Objects.requireNonNull(plugin.production().supplyInventory(e.payload().country(),e.plan().warehouse(),e.plan().binding()));
        player.getInventory().setContents(decode(e.plan().sourceAfter()));
        player.getPersistentDataContainer().set(receiptKey,PersistentDataType.STRING,e.receipt());
        player.saveData(); // A crash here is partial debit, never permission to debit/replay again.
        // saveData may call third-party hooks. Never overwrite their intervening target/source changes.
        inventory=Objects.requireNonNull(plugin.production().supplyInventory(e.payload().country(),e.plan().warehouse(),e.plan().binding()));
        if(!encode(player.getInventory().getContents()).equals(e.plan().sourceAfter()) || !playerMarker(player).equals(e.receipt())
            || !encode(inventory.getStorageContents()).equals(e.plan().targetBefore()) || !e.plan().chestMarkers().equals(markers(e.plan())))
            throw new IllegalStateException("Inventory zmieniło się podczas zapisu gracza; wymagany audyt bez nadpisywania zmian.");
        inventory.setStorageContents(decode(e.plan().targetAfter()));
        for(var pos:e.plan().binding().parts()) {
            Container c=Objects.requireNonNull(container(pos));
            c.getPersistentDataContainer().set(receiptKey,PersistentDataType.STRING,e.receipt());
            if(!c.update(false,false)) throw new IllegalStateException("Nie można zapisać pokwitowania skrzyni.");
        }
        if(proof(e)!=Proof.AFTER) throw new IllegalStateException("Niezgodny snapshot po zapisie obu inventory.");
        Objects.requireNonNull(Bukkit.getWorld(e.plan().binding().parts().getFirst().world())).save();
        player.saveData();
    }
    @Override public Proof proof(Entry e) {
        Player player=Bukkit.getPlayer(e.payload().player());
        if(player==null || !player.isOnline()) return Proof.UNAVAILABLE;
        for(var pos:e.plan().binding().parts()) {
            World world=Bukkit.getWorld(pos.world());
            if(world==null || !world.isChunkLoaded(pos.x()>>4,pos.z()>>4)) return Proof.UNAVAILABLE;
        }
        Inventory inventory=plugin.production().supplyInventory(e.payload().country(),e.plan().warehouse(),e.plan().binding());
        if(inventory==null) return Proof.MISMATCH;
        List<String> markers=markers(e.plan()); if(markers==null) return Proof.UNAVAILABLE;
        var source=encode(player.getInventory().getContents()); var target=encode(inventory.getStorageContents());
        if(source.equals(e.plan().sourceAfter()) && target.equals(e.plan().targetAfter()) && playerMarker(player).equals(e.receipt())
            && markers.stream().allMatch(e.receipt()::equals)) return Proof.AFTER;
        if(source.equals(e.plan().sourceBefore()) && target.equals(e.plan().targetBefore()) && playerMarker(player).equals(e.plan().playerMarker())
            && markers.equals(e.plan().chestMarkers())) return Proof.BEFORE;
        return Proof.MISMATCH;
    }
    @Override public Proof sourceProof(Entry e) {
        Player player=Bukkit.getPlayer(e.payload().player());
        if(player!=null && player.isOnline() && !playerMarker(player).equals(e.receipt())) return Proof.MISMATCH;
        return diskSource(e);
    }
    @Override public Proof targetProof(Entry e) {
        for(BlockPos pos:e.plan().binding().parts()) {
            World world=Bukkit.getWorld(pos.world());
            if(world==null) return Proof.UNAVAILABLE;
            if(world.isChunkLoaded(pos.x()>>4,pos.z()>>4)) {
                Container c=container(pos);
                if(c==null || !e.receipt().equals(c.getPersistentDataContainer().get(receiptKey,PersistentDataType.STRING))) return Proof.MISMATCH;
            }
        }
        return diskTarget(e);
    }
    private long diskTimeout() { return Math.max(5,Math.min(120,plugin.getConfig().getLong("supply.disk-proof-timeout-seconds",30)))*1000; }
    private Proof diskSource(Entry e) {
        java.nio.file.Path file=playerData.resolve(e.payload().player()+".dat");
        return disk.poll("player:"+e.receipt(),diskTimeout(),() -> SupplyDiskProof.player(file,e.receipt()));
    }
    private Proof diskTarget(Entry e) {
        record Target(java.nio.file.Path region, BlockPos pos) { }
        List<Target> targets=new ArrayList<>();
        for(BlockPos pos:e.plan().binding().parts()) {
            World world=Bukkit.getWorld(pos.world()); if(world==null) return Proof.UNAVAILABLE;
            targets.add(new Target(world.getWorldPath().resolve("region"),pos));
        }
        return disk.poll("chest:"+e.receipt(),diskTimeout(),() -> targets.stream().allMatch(t ->
            SupplyDiskProof.block(t.region(),t.pos().x(),t.pos().y(),t.pos().z(),e.receipt())));
    }
    @Override public Proof durableProof(Entry e) {
        Proof source=diskSource(e), target=diskTarget(e);
        if(source==Proof.MISMATCH || target==Proof.MISMATCH) return Proof.MISMATCH;
        return source==Proof.AFTER && target==Proof.AFTER ? Proof.AFTER : Proof.UNAVAILABLE;
    }
    static List<String> encode(ItemStack[] items) {
        var out=new ArrayList<String>();
        for(ItemStack item:items) out.add(empty(item) ? null : Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        return Collections.unmodifiableList(out);
    }
    private static ItemStack[] decode(List<String> items) {
        return items.stream().map(s -> s==null ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(s))).toArray(ItemStack[]::new);
    }
    @Override public void close() { disk.close(); journal.close(); } // Never refund/replay on disable.
}
