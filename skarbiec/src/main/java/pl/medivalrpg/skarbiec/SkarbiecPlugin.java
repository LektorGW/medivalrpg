// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.coin.CoinGuardListener;
import pl.medivalrpg.skarbiec.coin.CoinService;
import pl.medivalrpg.skarbiec.command.SkarbiecCommands;
import pl.medivalrpg.skarbiec.config.Messages;
import pl.medivalrpg.skarbiec.config.SkarbiecConfig;
import pl.medivalrpg.skarbiec.gui.ChatInput;
import pl.medivalrpg.skarbiec.gui.GuiListener;
import pl.medivalrpg.skarbiec.integration.Territory;
import pl.medivalrpg.skarbiec.ledger.Ledger;
import pl.medivalrpg.skarbiec.ledger.LedgerStore;
import pl.medivalrpg.skarbiec.merchant.MerchantService;
import pl.medivalrpg.skarbiec.money.Money;
import pl.medivalrpg.skarbiec.npc.NpcManager;
import pl.medivalrpg.skarbiec.shop.ShopListener;
import pl.medivalrpg.skarbiec.shop.ShopManager;
import pl.medivalrpg.skarbiec.storage.JsonFiles;
import pl.medivalrpg.skarbiec.trade.TradeManager;

/** Skarbiec — economy of MEDIVALRPG: bank accounts, physical coins, NPC banker/merchant, player shops, trading. */
public final class SkarbiecPlugin extends JavaPlugin {
    public boolean worldMoneyManager(UUID actor){return pl.medivalrpg.skarbiec.ledger.WorldMoney.MANAGER.equals(actor);}
    public boolean closedSupply(){return ledger!=null && ledger.closedSupply();}
    public Object worldTreasury(UUID actor,String method,java.util.Map<String,String> args){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Main thread required");
        return pl.medivalrpg.skarbiec.command.WorldTreasury.invoke(this,actor,method,args);
    }
    /**
     * Owner-only statistics bridge for the Panel ({@code world.stats.*}). The UUID is checked inside on every call;
     * returns a JSON-ready map or a CompletableFuture of one (completed on the main thread).
     */
    public Object worldStats(UUID actor,String method,java.util.Map<String,String> args){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Main thread required");
        if(this.stats==null)throw new IllegalArgumentException("Statystyki Skarbu Świata są niedostępne.");
        return this.stats.invoke(actor,method,args==null?java.util.Map.of():args);
    }
    private pl.medivalrpg.skarbiec.stats.WorldStats stats;
    private pl.medivalrpg.skarbiec.ledger.WorldGrants grants;
    private pl.medivalrpg.skarbiec.ledger.LaborLedger labor;
    private BukkitTask laborTask;
    public pl.medivalrpg.skarbiec.ledger.LaborLedger labor(){return this.labor;}
    /** Grants act on behalf of the personal manager (owner command, or the owner's own config switch at start). */
    public UUID grantActor(){return pl.medivalrpg.skarbiec.ledger.WorldMoney.MANAGER;}
    public pl.medivalrpg.skarbiec.ledger.WorldGrants grants(){return this.grants;}
    private pl.medivalrpg.skarbiec.ledger.NpcBulkTransfers npcBulk;
    /** Equal transfers to every NPC country (bank/npc-bulk.json); null when the file is unreadable. */
    public pl.medivalrpg.skarbiec.ledger.NpcBulkTransfers npcBulk(){return this.npcBulk;}

    private volatile SkarbiecConfig config;
    private volatile Messages messages;
    private Ledger ledger;
    private LedgerStore store;
    private CoinService coins;
    private SkarbiecApi api;
    private JsonFiles files;
    private ChatInput chatInput;
    private MerchantService merchant;
    private NpcManager npcs;
    private ShopManager shops;
    private TradeManager trades;
    private Territory territory;
    private pl.medivalrpg.skarbiec.integration.Seasons seasons;
    private BukkitTask snapshotTask;
    private pl.medivalrpg.skarbiec.npc.StallService stalls;

    /** Before any plugin is enabled: ship the stall models to BetterModel, which reads its models when it enables. */
    @Override
    public void onLoad() {
        this.saveDefaultConfig();
        pl.medivalrpg.skarbiec.npc.StallService.installModels(this);
    }

    /** Market stalls of merchants (BetterModel, optional). */
    public pl.medivalrpg.skarbiec.npc.StallService stalls() {
        return this.stalls;
    }

    @Override
    public void onEnable() {
        this.getLogger().info("Skarbiec by Maciej Kaczmarczyk — lektoro.pl");
        this.saveDefaultConfig();
        this.reloadConfig();
        this.config = new SkarbiecConfig(this.getConfig(), this.getLogger());
        this.messages = new Messages(this.getConfig(), this.getLogger());

        // ---- bank: refuse to run on unreadable data rather than start from zero and overwrite it
        this.store = new LedgerStore(this.getDataFolder().toPath().resolve("bank"), this.getLogger(), this.config.zone,
            this.config.fsync, this.config.logKeepDays);
        this.ledger = new Ledger(this.store);
        try {
            final LedgerStore.LoadResult r = this.store.load(this.ledger);
            this.getLogger().info("Bank: kont " + this.ledger.balances().size() + ", w obiegu bankowym "
                + Money.format(this.ledger.totalMoney()) + " (migawka #" + r.snapshotSeq() + ", odtworzono z dziennika "
                + r.replayed() + " operacji)");
            if (r.usedBackup()) {
                this.getLogger().warning("Bank wczytany z kopii accounts.json.bak + dziennika — sprawdź plik accounts.json.broken-*");
            }
            if (!r.gaps().isEmpty()) {
                this.getLogger().severe("Bank: luki w numeracji dziennika przy operacjach " + r.gaps()
                    + " — część operacji mogła zostać utracona (np. awaria dysku). Sprawdź plugins/Skarbiec/bank/logs.");
            }
            if(this.getConfig().getBoolean("world-economy.enabled",false)
                || this.ledger.exists(pl.medivalrpg.skarbiec.ledger.WorldMoney.RESERVE)
                || this.ledger.exists(pl.medivalrpg.skarbiec.ledger.WorldMoney.CASH)){
                this.ledger.enableClosedSupply(pl.medivalrpg.skarbiec.ledger.WorldMoney.CAP,
                    pl.medivalrpg.skarbiec.ledger.WorldMoney.MANAGER);
                this.getLogger().info("Skarb Świata: stała pula 1 000 000 zł; zarząd osobisty WorldSteward");
            }
            this.labor = new pl.medivalrpg.skarbiec.ledger.LaborLedger(this.config.laborShare, this.config.zone);
            this.ledger.setRefundPrefixes(this.config.laborShare.refundPrefixes());
            this.ledger.addObserver(this.labor::observe);
        } catch (final IOException | RuntimeException e) {
            this.getLogger().log(Level.SEVERE, "Nie udało się wczytać danych banku — plugin zostaje wyłączony, aby "
                + "niczego nie nadpisać. Sprawdź plugins/Skarbiec/bank/.", e);
            this.store = null;
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.store.start();
        this.ledger.requestSnapshot(true); // consolidate replayed operations right away

        this.files = new JsonFiles(this.getLogger());
        this.coins = new CoinService(this,this.ledger);
        this.coins.reload(this.config);
        this.api = new SkarbiecApiImpl(this.ledger, this.coins);
        this.chatInput = new ChatInput(this);
        this.territory = Territory.create(this);
        this.seasons = new pl.medivalrpg.skarbiec.integration.Seasons(this);
        this.merchant = new MerchantService(this);
        this.npcs = new NpcManager(this);
        this.shops = new ShopManager(this);
        this.trades = new TradeManager(this);
        try {
            this.merchant.load();
            this.npcs.load();
            this.shops.load();
        } catch (final IOException | RuntimeException e) {
            this.getLogger().log(Level.SEVERE, "Nie udało się wczytać danych (npc/sklepy/kupiec) — plugin zostaje wyłączony.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        final var pm = Bukkit.getPluginManager();
        pm.registerEvents(new GuiListener(), this);
        pm.registerEvents(this.chatInput, this);
        pm.registerEvents(new CoinGuardListener(this.coins), this);
        pm.registerEvents(this.npcs, this);
        pm.registerEvents(new ShopListener(this, this.shops), this);
        pm.registerEvents(this.trades, this);

        final SkarbiecCommands commands = new SkarbiecCommands(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> commands.register(event.registrar()));

        Bukkit.getServicesManager().register(SkarbiecApi.class, this.api, this, ServicePriority.Normal);

        final long period = this.config.snapshotIntervalSeconds * 20L;
        this.snapshotTask = Bukkit.getScheduler().runTaskTimer(this, () -> this.ledger.requestSnapshot(false), period, period);
        this.npcs.start();
        this.trades.start();
        this.merchant.start();
        this.stalls = new pl.medivalrpg.skarbiec.npc.StallService(this);
        this.stalls.start();
        final var laborFile = this.getDataFolder().toPath().resolve("bank").resolve("labor.json");
        try {
            this.labor.load(this.files.read(laborFile));
        } catch (final IOException | RuntimeException e) {
            this.getLogger().log(Level.SEVERE, "bank/labor.json nieczytelny — zaległa robocizna liczona od zera, bank działa dalej", e);
            try { // the next save replaces the file: keep the unreadable one for inspection
                java.nio.file.Files.copy(laborFile, laborFile.resolveSibling("labor.json.broken-" + System.currentTimeMillis()));
            } catch (final IOException | RuntimeException copy) {
                this.getLogger().warning("Nie udało się skopiować nieczytelnego labor.json: " + copy.getMessage());
            }
        }
        // Settlement runs also after an unreadable labor.json: new construction payments must still reach the households.
        this.laborTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            final long moved = this.labor.settle(this.ledger, System.currentTimeMillis());
            if (moved > 0) {
                this.getLogger().info("Robocizna: " + Money.format(moved) + " z world:payments do world:households.");
            }
            if (this.labor.dirty()) {
                this.files.writeAsync(laborFile, this.labor.toJson());
            }
        }, 1200L, 12_000L);
        try {
            this.grants = new pl.medivalrpg.skarbiec.ledger.WorldGrants(this.getDataFolder().toPath().resolve("bank"));
            if (this.config.grantsAutoOnStart && this.ledger.closedSupply()) {
                Bukkit.getScheduler().runTaskLater(this, () -> pl.medivalrpg.skarbiec.command.WorldTreasury.autoGrants(this), 100L);
            }
        } catch (final IOException | RuntimeException e) {
            this.getLogger().log(Level.SEVERE, "bank/grants.json nieczytelny — zasilenia wdrożeniowe wyłączone, bank działa dalej", e);
            this.grants = null;
        }
        try {
            this.npcBulk = new pl.medivalrpg.skarbiec.ledger.NpcBulkTransfers(this.getDataFolder().toPath().resolve("bank"));
        } catch (final IOException | RuntimeException e) {
            this.getLogger().log(Level.SEVERE, "bank/npc-bulk.json nieczytelny — przelewy do wszystkich państw NPC wyłączone, bank działa dalej", e);
            this.npcBulk = null;
        }
        try {
            // Statistics are read-only extras: a failure here must never take the bank down.
            this.stats = new pl.medivalrpg.skarbiec.stats.WorldStats(this);
            this.stats.start();
        } catch (final RuntimeException | LinkageError e) {
            this.getLogger().log(Level.SEVERE, "Statystyki Skarbu Świata niedostępne — bank działa dalej", e);
            this.stats = null;
        }
        for (final Player p : Bukkit.getOnlinePlayers()) {
            this.coins.normalize(p);
        }
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        if (this.snapshotTask != null) {
            this.snapshotTask.cancel();
        }
        if (this.trades != null) {
            this.trades.cancelAll(); // returns escrowed items to their owners
        }
        if (this.chatInput != null) {
            this.chatInput.clear();
        }
        if (this.stalls != null) {
            this.stalls.stop();
        }
        if (this.npcs != null) {
            this.npcs.stop();
        }
        if (this.stats != null) {
            this.stats.stop();
            this.stats = null;
        }
        if (this.laborTask != null) {
            this.laborTask.cancel();
        }
        if (this.labor != null && this.files != null) {
            this.files.writeAsync(this.getDataFolder().toPath().resolve("bank").resolve("labor.json"), this.labor.toJson());
        }
        if (this.merchant != null) {
            this.merchant.stop();
            this.merchant.saveNow();
        }
        if (this.files != null) {
            this.files.shutdown();
        }
        if (this.store != null) {
            this.ledger.requestSnapshot(true);
            this.store.close();
        }
    }

    /** {@code /skarbiec reload}: config, messages, coin look. Account data stays in memory. */
    public void reload() {
        this.reloadConfig();
        this.config = new SkarbiecConfig(this.getConfig(), this.getLogger());
        this.messages = new Messages(this.getConfig(), this.getLogger());
        this.coins.reload(this.config);
        this.labor.policy(this.config.laborShare, this.config.zone);
        this.ledger.setRefundPrefixes(this.config.laborShare.refundPrefixes());
        for (final Player p : Bukkit.getOnlinePlayers()) {
            this.coins.normalize(p);
        }
        this.npcs.refreshAll();
        this.shops.refreshSigns();
    }

    // ------------------------------------------------------------------ helpers

    public static TagResolver amount(final long copper) {
        return Placeholder.unparsed("amount", Money.format(copper));
    }

    public static TagResolver amount(final String key, final long copper) {
        return Placeholder.unparsed(key, Money.format(copper));
    }

    /** Best known name of a player (online, cached or the uuid). */
    public static String nameOf(final UUID id) {
        final Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        final OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        return off.getName() != null ? off.getName() : id.toString().substring(0, 8);
    }

    public static String account(final Player player) {
        return SkarbiecApi.playerAccount(player.getUniqueId());
    }

    // ------------------------------------------------------------------ accessors

    public SkarbiecConfig cfg() {
        return this.config;
    }

    public Messages messages() {
        return this.messages;
    }

    public Ledger ledger() {
        return this.ledger;
    }

    /** Optional consumer bridge; the shared SkarbiecApi remains unchanged. */
    public java.util.concurrent.CompletableFuture<Void> bankDurable() {return this.store.durable();}

    // ------------------------------------------------------------------ Firmy bridge (called by reflection, JDK types only)

    /**
     * Atomic batch of transfers (all legs or none), e.g. a dividend or a liquidation of a company. Each leg is a map
     * {@code from, to, amount, reason}. Returns the {@link pl.medivalrpg.skarbiec.api.TxResult} name. Thread-safe.
     */
    public String transferBatch(final java.util.List<java.util.Map<String, Object>> legs) {
        if (this.ledger == null || legs == null) {
            return pl.medivalrpg.skarbiec.api.TxResult.INVALID_ACCOUNT.name();
        }
        final java.util.List<Ledger.Leg> list = new java.util.ArrayList<>();
        for (final java.util.Map<String, Object> leg : legs) {
            final Object amount = leg.get("amount");
            if (!(amount instanceof Number n)) {
                return pl.medivalrpg.skarbiec.api.TxResult.INVALID_AMOUNT.name();
            }
            list.add(new Ledger.Leg(String.valueOf(leg.get("from")), String.valueOf(leg.get("to")), n.longValue(),
                String.valueOf(leg.getOrDefault("reason", "api: przelew zbiorczy"))));
        }
        return this.ledger.transferAll(list).name();
    }

    /** Firmy registers the policy of its company shops ({@code namespace} = {@code firma}); see ShopAccounts. */
    public boolean registerShopAccounts(final String namespace,
                                        final java.util.function.Function<java.util.Map<String, Object>, java.util.Map<String, Object>> policy) {
        if (this.shops == null || namespace == null || namespace.isBlank() || policy == null) {
            return false;
        }
        this.shops.accounts().register(namespace, policy);
        Bukkit.getScheduler().runTask(this, () -> this.shops.refreshSigns());
        return true;
    }

    public void unregisterShopAccounts(final String namespace) {
        if (this.shops != null) {
            this.shops.accounts().unregister(namespace);
        }
    }

    private static java.util.Map<String, Object> shopMap(final pl.medivalrpg.skarbiec.shop.Shop s, final Integer stock) {
        final java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", s.id());
        m.put("owner", s.owner().toString());
        m.put("ownerName", s.ownerName());
        m.put("type", s.type().name());
        m.put("amount", s.amount());
        m.put("price", s.price());
        m.put("item", s.item().getType().name());
        m.put("world", s.world());
        final String[] k = s.chestKey().split(":");
        m.put("x", Integer.parseInt(k[k.length - 3]));
        m.put("y", Integer.parseInt(k[k.length - 2]));
        m.put("z", Integer.parseInt(k[k.length - 1]));
        m.put("account", s.account());
        m.put("accountLabel", s.accountLabel());
        if (stock != null) {
            m.put("stock", stock);
        }
        return m;
    }

    /** Main thread. The shop whose sign or container is at the block, or null. */
    public java.util.Map<String, Object> shopInfoAt(final String world, final int x, final int y, final int z) {
        if (this.shops == null) {
            return null;
        }
        final org.bukkit.World w = Bukkit.getWorld(world);
        if (w == null) {
            return null;
        }
        final pl.medivalrpg.skarbiec.shop.Shop s = this.shops.at(w.getBlockAt(x, y, z));
        return s == null ? null : shopMap(s, s.chunkLoaded() ? this.shops.stock(s) : null);
    }

    /**
     * Main thread. Makes the shop at the block trade for {@code account} (label = company name), or back for its builder
     * when {@code account} is null. Assigning needs {@code actor} to be the builder; the caller (Firmy) checks its own roles.
     * Returns {ok, message, shop}.
     */
    public java.util.Map<String, Object> shopAssign(final UUID actor, final String world, final int x, final int y, final int z,
                                                    final String account, final String label) {
        final java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", false);
        final org.bukkit.World w = Bukkit.getWorld(world);
        final pl.medivalrpg.skarbiec.shop.Shop s = w == null || this.shops == null ? null : this.shops.at(w.getBlockAt(x, y, z));
        if (s == null) {
            out.put("message", "To nie jest sklep. Spójrz na tabliczkę [Sklep] albo jego skrzynię.");
            return out;
        }
        if (account != null) {
            if (!Ledger.validAccount(account)) {
                out.put("message", "Nieprawidłowe konto.");
                return out;
            }
            if (!s.owner().equals(actor)) {
                out.put("message", "Firmie przypisuje się własny sklep (zbudował go " + s.ownerName() + ").");
                return out;
            }
            if (s.account() != null && !s.account().equals(account)) {
                out.put("message", "Ten sklep należy już do innej firmy.");
                return out;
            }
        }
        this.shops.assign(s, account, label, actor == null ? "system" : nameOf(actor));
        out.put("ok", true);
        out.put("shop", shopMap(s, s.chunkLoaded() ? this.shops.stock(s) : null));
        return out;
    }

    /** Main thread. Company shops of {@code account}. */
    public java.util.List<java.util.Map<String, Object>> shopsOfAccount(final String account) {
        final java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (this.shops != null && account != null) {
            for (final pl.medivalrpg.skarbiec.shop.Shop s : this.shops.ofAccount(account)) {
                out.add(shopMap(s, s.chunkLoaded() ? this.shops.stock(s) : null));
            }
        }
        return out;
    }

    /** Main thread. Deletes every shop of {@code account} (company liquidated and the grace period over). */
    public int shopsRemoveAccount(final String account, final String by) {
        return this.shops == null || account == null ? 0 : this.shops.removeAccount(account, by);
    }

    public LedgerStore store() {
        return this.store;
    }

    public CoinService coins() {
        return this.coins;
    }

    public SkarbiecApi api() {
        return this.api;
    }

    public JsonFiles files() {
        return this.files;
    }

    public ChatInput chatInput() {
        return this.chatInput;
    }

    public MerchantService merchant() {
        return this.merchant;
    }

    public NpcManager npcs() {
        return this.npcs;
    }

    public ShopManager shops() {
        return this.shops;
    }

    public TradeManager trades() {
        return this.trades;
    }

    public Territory territory() {
        return this.territory;
    }

    /** Optional Ludnosc season (seasonal merchant goods). */
    public pl.medivalrpg.skarbiec.integration.Seasons seasons() {
        return this.seasons;
    }
}
