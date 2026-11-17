// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.merchant;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.skarbiec.config.Messages;
import pl.medivalrpg.skarbiec.config.SkarbiecConfig;
import pl.medivalrpg.skarbiec.shop.Items;

/**
 * NPC merchants: buy plain goods from players at configured prices (daily limits, supply-driven price drop)
 * and optionally sell them. Daily counters survive restarts ({@code merchant.json}). Main thread only.
 *
 * <p>Merchant types ({@link MerchantType}) share one warehouse, one budget and the per-good daily limits; each type has
 * its own delivery ({@link MerchantAssortment}): only goods on offer at the type can be traded there. A good can be
 * featured at one type and not at another, so the selling price always uses the HIGHEST buying price of all types:
 * selling to one merchant and buying back from another never earns money.
 */
public final class MerchantService {

    private static final class PlayerDay {
        long earned;
        final Map<String, Long> sold = new HashMap<>();
    }

    private final SkarbiecPlugin plugin;
    private final Path file;
    private LocalDate day;
    private final Map<UUID, PlayerDay> players = new HashMap<>();
    private final Map<String, Long> global = new HashMap<>();
    private BukkitTask saveTask;
    private long featuredPeriod = Long.MIN_VALUE;
    private SkarbiecConfig featuredConfig;
    private Set<String> featuredKeys = Set.of();
    private final Path marketFile;
    private MerchantMarket market;
    private BukkitTask sampleTask;

    public MerchantService(final SkarbiecPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("merchant.json");
        this.marketFile = plugin.getDataFolder().toPath().resolve("merchant-market.json");
        this.day = LocalDate.now(plugin.cfg().zone);
        this.market = new MerchantMarket(System.currentTimeMillis(), plugin.cfg().zone);
    }

    // ------------------------------------------------------------------ persistence

    public void load() throws IOException {
        try {
            this.market = MerchantMarket.fromJson(this.plugin.files().read(this.marketFile), System.currentTimeMillis(), this.plugin.cfg().zone);
        } catch (final IOException | RuntimeException e) {
            // The warehouse is not money: a damaged file must never stop the bank. Keep a copy and start empty.
            this.plugin.getLogger().log(java.util.logging.Level.SEVERE, "merchant-market.json jest uszkodzony — kopia zapasowa i pusty magazyn kupca", e);
            try {
                java.nio.file.Files.copy(this.marketFile, this.marketFile.resolveSibling("merchant-market.json.broken-" + System.currentTimeMillis()));
            } catch (final IOException copy) {
                this.plugin.getLogger().warning("Nie udało się skopiować uszkodzonego merchant-market.json: " + copy.getMessage());
            }
            this.market = new MerchantMarket(System.currentTimeMillis(), this.plugin.cfg().zone);
        }
        this.market.historyHours(this.plugin.cfg().marketHistoryHours);
        this.loadCounters();
    }

    /** Hourly price/stock sample and periodic time step of the market (main thread, cheap). */
    public void start() {
        this.sampleTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tickMarket, 200L, 6000L);
    }

    public void stop() {
        if (this.sampleTask != null) {
            this.sampleTask.cancel();
            this.sampleTask = null;
        }
    }

    private void tickMarket() {
        if (!this.marketOn()) {
            return;
        }
        this.advance();
        final Map<String, long[]> prices = new java.util.LinkedHashMap<>();
        final Set<String> active = this.activeKeys();
        for (final MerchantOffer o : this.plugin.cfg().offers) {
            final MerchantMarket.Good g = this.market.good(o.key());
            if (!active.contains(o.key()) && g.stock() <= 0) {
                continue; // keep the hourly history small: only goods on offer or in stock
            }
            prices.put(o.key(), new long[] {g.stock(), this.currentBuyPrice(o), this.sellPriceNow(o)});
        }
        if (this.market.sample(System.currentTimeMillis(), this.budget(), prices)) {
            this.scheduleSave();
        }
    }

    private void loadCounters() throws IOException {
        final JsonElement root = this.plugin.files().read(this.file);
        if (root == null || !root.isJsonObject()) {
            return;
        }
        final JsonObject o = root.getAsJsonObject();
        final LocalDate saved = LocalDate.parse(o.get("day").getAsString());
        if (!saved.equals(LocalDate.now(this.plugin.cfg().zone))) {
            return; // a new day: counters start from zero
        }
        this.day = saved;
        for (final var e : o.getAsJsonObject("global").entrySet()) {
            this.global.put(e.getKey(), e.getValue().getAsLong());
        }
        for (final var e : o.getAsJsonObject("players").entrySet()) {
            final JsonObject p = e.getValue().getAsJsonObject();
            final PlayerDay d = new PlayerDay();
            d.earned = p.get("earned").getAsLong();
            for (final var s : p.getAsJsonObject("sold").entrySet()) {
                d.sold.put(s.getKey(), s.getValue().getAsLong());
            }
            this.players.put(UUID.fromString(e.getKey()), d);
        }
    }

    private JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("day", this.day.toString());
        final JsonObject g = new JsonObject();
        this.global.forEach(g::addProperty);
        o.add("global", g);
        final JsonObject ps = new JsonObject();
        this.players.forEach((id, d) -> {
            final JsonObject p = new JsonObject();
            p.addProperty("earned", d.earned);
            final JsonObject sold = new JsonObject();
            d.sold.forEach(sold::addProperty);
            p.add("sold", sold);
            ps.add(id.toString(), p);
        });
        o.add("players", ps);
        return o;
    }

    private void scheduleSave() {
        if (this.saveTask != null || !this.plugin.isEnabled()) {
            return;
        }
        this.saveTask = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            this.saveTask = null;
            this.plugin.files().writeAsync(this.file, this.toJson());
            this.plugin.files().writeAsync(this.marketFile, this.market.toJson());
        }, 40L);
    }

    public void saveNow() {
        if (this.saveTask != null) {
            this.saveTask.cancel();
            this.saveTask = null;
        }
        this.plugin.files().writeAsync(this.file, this.toJson());
        this.plugin.files().writeAsync(this.marketFile, this.market.toJson());
    }

    // ------------------------------------------------------------------ dynamic market

    public boolean marketOn() {
        return this.plugin.cfg().market.enabled();
    }

    private void advance() {
        final SkarbiecConfig c = this.plugin.cfg();
        this.market.advance(System.currentTimeMillis(), c.zone, c.market, c.marketTargets, c.marketRestock, this.restingKeys());
    }

    // ------------------------------------------------------------------ merchant types and deliveries

    private SkarbiecConfig pickConfig;
    private final Map<String, MerchantAssortment.Pick> picks = new HashMap<>();

    public MerchantType type(final String id) {
        return this.plugin.cfg().merchantType(id);
    }

    public boolean assortmentOn() {
        return this.plugin.cfg().assortment.enabled();
    }

    /** Current delivery period number. */
    public long deliveryPeriod() {
        return this.plugin.cfg().assortment.period(System.currentTimeMillis(), this.plugin.cfg().zone);
    }

    public long millisToDelivery() {
        return this.plugin.cfg().assortment.millisToNext(System.currentTimeMillis(), this.plugin.cfg().zone);
    }

    /** Ludnosc season now (empty without Ludnosc / seasons). */
    public java.util.Optional<pl.medivalrpg.skarbiec.integration.Seasons.Season> season() {
        return this.plugin.seasons() == null ? java.util.Optional.empty() : this.plugin.seasons().current();
    }

    /** Season id at the start of a delivery period, {@code null} when unknown. */
    public String seasonOf(final long period) {
        final SkarbiecConfig c = this.plugin.cfg();
        final long start = c.assortment.start(period, c.zone);
        return this.season().map(s -> s.clock().idAt(start)).orElse(null);
    }

    /** What the type has in the current delivery. */
    public MerchantAssortment.Pick pick(final MerchantType type) {
        return this.pick(type, this.deliveryPeriod());
    }

    /** What the type has (or will have) in {@code period}. */
    public MerchantAssortment.Pick pick(final MerchantType type, final long period) {
        final SkarbiecConfig c = this.plugin.cfg();
        if (c != this.pickConfig || this.picks.size() > 64) {
            this.pickConfig = c;
            this.picks.clear();
        }
        if (!c.assortment.enabled()) {
            return this.picks.computeIfAbsent(type.id() + "@all", k -> MerchantAssortment.all(type, c.offers));
        }
        final String season = this.seasonOf(period);
        return this.picks.computeIfAbsent(type.id() + "@" + period + "@" + season,
            k -> MerchantAssortment.pick(type, c.offers, period, season, c.assortment.featured()));
    }

    /** Goods on offer now at this type (core first, then the delivery). */
    public java.util.List<MerchantOffer> offers(final MerchantType type) {
        return this.pick(type).offers();
    }

    public boolean onOffer(final MerchantType type, final MerchantOffer offer) {
        return this.pick(type).contains(offer);
    }

    /** Keys of goods on offer now at any merchant type. */
    public Set<String> activeKeys() {
        final Set<String> out = new java.util.HashSet<>();
        for (final MerchantType t : this.plugin.cfg().merchantTypes.values()) {
            for (final MerchantOffer o : this.pick(t).offers()) {
                out.add(o.key());
            }
        }
        return out;
    }

    /** Goods out of every current offer: their stock rests (no shipping away, no restock) until they come back. */
    private Set<String> restingKeys() {
        if (!this.assortmentOn()) {
            return Set.of();
        }
        final Set<String> active = this.activeKeys();
        final Set<String> out = new java.util.HashSet<>();
        for (final MerchantOffer o : this.plugin.cfg().offers) {
            if (!active.contains(o.key())) {
                out.add(o.key());
            }
        }
        return out;
    }

    /** Money the merchant can pay out: the world merchant budget in the closed pool, else a neutral budget. */
    public long budget() {
        if (this.plugin.ledger().closedSupply()) {
            return this.plugin.ledger().balance(pl.medivalrpg.skarbiec.ledger.WorldMoney.MERCHANT);
        }
        return Math.max(1, this.plugin.cfg().market.budgetTarget());
    }

    /** Whether the merchant buys at all now (false = empty budget). */
    public boolean buying() {
        return !this.marketOn() || this.plugin.cfg().market.budgetFactor(this.budget()) > 0;
    }

    public long stock(final MerchantOffer offer) {
        return this.market.good(offer.key()).stock();
    }

    public long target(final MerchantOffer offer) {
        return this.plugin.cfg().marketTargets.getOrDefault(offer.key(), (long) this.plugin.cfg().market.targetBundles() * offer.amount());
    }

    /** NONE / LOW / OK / HIGH. */
    public String stockLevel(final MerchantOffer offer) {
        return this.plugin.cfg().market.level(this.stock(offer), this.target(offer), offer.amount());
    }

    private int rotationPercentRaw(final MerchantType type, final MerchantOffer offer) {
        return this.plugin.cfg().rotation.percent(offer.key(), this.period(), this.featured(type, offer));
    }

    /** Highest rotation percent of the good at any merchant type (for the selling-price floor). */
    private int maxRotationPercent(final MerchantOffer offer) {
        return this.plugin.cfg().rotation.percent(offer.key(), this.period(), this.featuredAnywhere(offer));
    }

    private long marketBuyAt(final MerchantOffer offer, final long stock, final long budget, final int rotationPercent) {
        final MerchantMarket.Good g = this.market.good(offer.key());
        return this.plugin.cfg().market.buyPrice(offer.buy(), Math.max(stock, g.reference()), this.target(offer), budget, rotationPercent);
    }

    /** Current price the merchant charges for one bundle (0 = does not sell now: no stock or not on the list). */
    public long sellPriceNow(final MerchantOffer offer) {
        if (!offer.sells()) {
            return 0;
        }
        if (!this.marketOn()) {
            return offer.sell();
        }
        this.advance();
        final MerchantMarket.Good g = this.market.good(offer.key());
        if (g.stock() < offer.amount()) {
            return 0;
        }
        final long buyNow = offer.buys() ? this.marketBuyAt(offer, g.stock(), Math.max(1, this.budget()), this.maxRotationPercent(offer)) : 0;
        return this.plugin.cfg().market.sellPrice(offer.sell(), Math.min(g.stock(), g.reference()), this.target(offer), buyNow);
    }

    /** Buying price trend vs about a day ago: [direction -1/0/1, percent]. */
    public int[] trend(final MerchantOffer offer) {
        final long now = this.currentBuyPrice(offer);
        final long ago = this.market.buyPriceAgo(offer.key(), System.currentTimeMillis());
        if (ago <= 0 || now <= 0) {
            return new int[] {0, 0};
        }
        final int pct = (int) Math.round((now - ago) * 100.0 / ago);
        return new int[] {Integer.signum(pct), Math.abs(pct)};
    }

    /** Owner statistics ({@code world.stats.merchant}); main thread. */
    public Map<String, Object> statistics() {
        this.advance();
        final SkarbiecConfig c = this.plugin.cfg();
        final long now = System.currentTimeMillis();
        final long budget = this.budget();
        final Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", c.market.enabled());
        out.put("closed", this.plugin.ledger().closedSupply());
        out.put("budget", budget);
        out.put("budgetTarget", c.market.budgetTarget());
        out.put("budgetPercent", c.market.budgetTarget() <= 0 ? 100 : (int) Math.round(budget * 100.0 / c.market.budgetTarget()));
        out.put("budgetFactor", (int) Math.round(c.market.budgetFactor(budget) * 100));
        out.put("buying", this.buying());
        out.put("updatedAt", this.market.updatedAt());
        out.put("periodEndsAt", now + this.millisToRotation());
        final java.util.List<Map<String, Object>> goods = new java.util.ArrayList<>();
        long stockValue = 0;
        long paid = 0;
        long received = 0;
        final Map<String, String> levels = Map.of("NONE", "brak", "LOW", "mało", "OK", "w normie", "HIGH", "dużo");
        for (final MerchantOffer o : c.offers) {
            final MerchantMarket.Good g = this.market.good(o.key());
            final long buy = o.buys() ? this.currentBuyPrice(o) : 0;
            final long sell = this.sellPriceNow(o);
            final int[] trend = this.trend(o);
            final String level = this.stockLevel(o);
            final Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("key", o.key());
            m.put("bundle", o.amount());
            m.put("stock", g.stock());
            m.put("reference", Math.round(g.reference()));
            m.put("target", this.target(o));
            m.put("stockLevel", level);
            m.put("stockLabel", levels.getOrDefault(level, level));
            m.put("buyPrice", buy);
            m.put("sellPrice", sell);
            m.put("baseBuy", o.buy());
            m.put("baseSell", o.sell());
            m.put("buyPercent", o.buy() <= 0 ? null : (int) Math.round(buy * 100.0 / o.buy()));
            m.put("sellPercent", o.sell() <= 0 || sell <= 0 ? null : (int) Math.round(sell * 100.0 / o.sell()));
            m.put("trend", trend[0] > 0 ? "UP" : trend[0] < 0 ? "DOWN" : "FLAT");
            m.put("trendPercent", trend[1]);
            m.put("featured", o.buys() && this.featuredAnywhere(o));
            m.put("category", o.category());
            m.put("seasons", java.util.List.copyOf(o.seasons()));
            final java.util.List<String> at = new java.util.ArrayList<>();
            for (final MerchantType t : c.merchantTypes.values()) {
                if (this.onOffer(t, o)) {
                    at.add(t.id());
                }
            }
            m.put("onOffer", !at.isEmpty());
            m.put("merchants", at);
            m.put("soldToday", g.soldItems());
            m.put("boughtToday", g.boughtItems());
            m.put("paidToday", g.paid());
            m.put("receivedToday", g.received());
            m.put("restockPerDay", c.marketRestock.getOrDefault(o.key(), 0L));
            m.put("itemLimit", this.itemLimit(this.bestType(o), o));
            goods.add(m);
            if (o.buys() && g.stock() > 0) {
                stockValue += g.stock() / o.amount() * o.buy();
            }
            paid += g.paid();
            received += g.received();
        }
        out.put("goods", goods);
        final java.util.List<Map<String, Object>> history = new java.util.ArrayList<>();
        for (final MerchantMarket.Sample s : this.market.history()) {
            final Map<String, Object> gm = new java.util.LinkedHashMap<>();
            s.goods().forEach((k, v) -> gm.put(k, Map.of("stock", v.length > 0 ? v[0] : 0L, "buy", v.length > 1 ? v[1] : 0L, "sell", v.length > 2 ? v[2] : 0L)));
            final Map<String, Object> hm = new java.util.LinkedHashMap<>();
            hm.put("t", s.t());
            hm.put("budget", s.budget());
            hm.put("goods", gm);
            history.add(hm);
        }
        out.put("history", history);
        out.put("totals", Map.of("stockValue", stockValue, "paidToday", paid, "receivedToday", received));
        out.put("restockConfigured", !c.marketRestock.isEmpty());
        out.put("assortment", this.assortmentStatistics());
        return out;
    }

    /** Deliveries per merchant type (now and the next one) for the owner statistics. */
    private Map<String, Object> assortmentStatistics() {
        final SkarbiecConfig c = this.plugin.cfg();
        final long now = System.currentTimeMillis();
        final long period = this.deliveryPeriod();
        final Map<String, Object> a = new java.util.LinkedHashMap<>();
        a.put("enabled", c.assortment.enabled());
        a.put("periodHours", c.assortment.periodHours());
        a.put("period", period);
        a.put("nextDeliveryAt", now + this.millisToDelivery());
        a.put("season", this.season().map(s -> s.clock().id()).orElse(null));
        a.put("catalogSize", c.offers.size());
        a.put("defaultType", c.defaultMerchantType);
        final java.util.List<Map<String, Object>> types = new java.util.ArrayList<>();
        for (final MerchantType t : c.merchantTypes.values()) {
            final MerchantAssortment.Pick p = this.pick(t);
            final MerchantAssortment.Pick next = this.pick(t, period + 1);
            final Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", t.id());
            m.put("name", t.name());
            m.put("catalog", c.offers.stream().filter(t::carries).count());
            m.put("core", p.core().stream().map(MerchantOffer::key).toList());
            m.put("rotating", p.rotating().stream().map(MerchantOffer::key).toList());
            m.put("seasonal", p.seasonal().stream().map(MerchantOffer::key).toList());
            m.put("featured", java.util.List.copyOf(p.featured()));
            m.put("next", Map.of("delivery", next.delivery().stream().map(MerchantOffer::key).toList(),
                "featured", java.util.List.copyOf(next.featured())));
            types.add(m);
        }
        a.put("types", types);
        return a;
    }

    /** Type where the good has its best (featured) conditions now; the default type when nowhere special. */
    private MerchantType bestType(final MerchantOffer offer) {
        for (final MerchantType t : this.plugin.cfg().merchantTypes.values()) {
            if (this.onOffer(t, offer) && this.featured(t, offer)) {
                return t;
            }
        }
        return this.type(null);
    }


    // ------------------------------------------------------------------ counters

    private void rollover() {
        final LocalDate today = LocalDate.now(this.plugin.cfg().zone);
        if (!today.equals(this.day)) {
            this.day = today;
            this.players.clear();
            this.global.clear();
            this.scheduleSave();
        }
    }

    public long earnedToday(final UUID player) {
        this.rollover();
        final PlayerDay d = this.players.get(player);
        return d == null ? 0 : d.earned;
    }

    public long soldToday(final UUID player, final MerchantOffer offer) {
        this.rollover();
        final PlayerDay d = this.players.get(player);
        return d == null ? 0 : d.sold.getOrDefault(offer.key(), 0L);
    }

    public long globalSold(final MerchantOffer offer) {
        this.rollover();
        return this.global.getOrDefault(offer.key(), 0L);
    }

    public MerchantMath.Supply supply() {
        final SkarbiecConfig c = this.plugin.cfg();
        return new MerchantMath.Supply(c.supplyEnabled, c.supplyStep, c.supplyDropPercent, c.supplyMinPercent);
    }

    // ------------------------------------------------------------------ rotating offer

    private long period() {
        return this.plugin.cfg().rotation.period(System.currentTimeMillis());
    }

    /**
     * Goods this merchant type is looking for (better price, higher item limit): drawn per delivery from its offer, or
     * (deliveries off) the old 3-hour rotation over the whole price list, the same at every merchant.
     */
    public boolean featured(final MerchantType type, final MerchantOffer offer) {
        if (this.assortmentOn()) {
            return this.pick(type).featured().contains(offer.key());
        }
        return this.rotationFeatured(offer);
    }

    /** Whether the good is featured at any merchant type now. */
    public boolean featuredAnywhere(final MerchantOffer offer) {
        if (!this.assortmentOn()) {
            return this.rotationFeatured(offer);
        }
        for (final MerchantType t : this.plugin.cfg().merchantTypes.values()) {
            if (this.pick(t).featured().contains(offer.key())) {
                return true;
            }
        }
        return false;
    }

    private boolean rotationFeatured(final MerchantOffer offer) {
        final SkarbiecConfig cfg = this.plugin.cfg();
        final long period = this.period();
        if (period != this.featuredPeriod || cfg != this.featuredConfig) { // new rotation or /skarbiec reload
            this.featuredPeriod = period;
            this.featuredConfig = cfg;
            this.featuredKeys = cfg.rotation.featured(cfg.offers.stream()
                .filter(MerchantOffer::buys).map(MerchantOffer::key).toList(), period);
        }
        return this.featuredKeys.contains(offer.key());
    }

    /** Price of one bundle in this rotation, before the supply drop. */
    public long rotatedBuyPrice(final MerchantType type, final MerchantOffer offer) {
        return this.plugin.cfg().rotation.buyPrice(offer.buy(), offer.sell(), offer.key(), this.period(), this.featured(type, offer));
    }

    /** Items one player may sell today (0 = no item limit). */
    public int itemLimit(final MerchantType type, final MerchantOffer offer) {
        final int base = this.plugin.cfg().rotation.itemLimit(offer.dailyLimit(), this.featured(type, offer));
        return this.marketOn() ? this.plugin.cfg().market.scaledLimit(base, offer.amount(), this.budget()) : base;
    }

    public long millisToRotation() {
        return this.plugin.cfg().rotation.millisToNext(System.currentTimeMillis());
    }

    /** Current price this merchant type pays for one bundle. */
    public long currentBuyPrice(final MerchantType type, final MerchantOffer offer) {
        if (this.marketOn()) {
            if (!offer.buys()) {
                return 0;
            }
            this.advance();
            return this.marketBuyAt(offer, this.stock(offer), this.budget(), this.rotationPercentRaw(type, offer));
        }
        return this.supply().price(this.rotatedBuyPrice(type, offer), this.globalSold(offer));
    }

    /** Best current buying price of the good at any merchant type (history, trend, statistics). */
    public long currentBuyPrice(final MerchantOffer offer) {
        if (this.marketOn()) {
            if (!offer.buys()) {
                return 0;
            }
            this.advance();
            return this.marketBuyAt(offer, this.stock(offer), this.budget(), this.maxRotationPercent(offer));
        }
        return this.supply().price(this.plugin.cfg().rotation.buyPrice(offer.buy(), offer.sell(), offer.key(), this.period(),
            this.featuredAnywhere(offer)), this.globalSold(offer));
    }

    /** Current price relative to the configured base price (rotation and supply together). */
    public int pricePercent(final MerchantType type, final MerchantOffer offer) {
        return offer.buy() <= 0 ? 100 : (int) (this.currentBuyPrice(type, offer) * 100 / offer.buy());
    }

    /** Rotation price relative to the configured base price, without the supply drop. */
    public int rotationPercent(final MerchantType type, final MerchantOffer offer) {
        return offer.buy() <= 0 ? 100 : (int) (this.rotatedBuyPrice(type, offer) * 100 / offer.buy());
    }

    /** Drop caused by today's supply alone, in percent (0 = none). */
    public int supplyDrop(final MerchantOffer offer) {
        return this.marketOn() ? 0 : 100 - this.supply().percent(this.globalSold(offer));
    }

    // ------------------------------------------------------------------ trading

    /** Tells the player that the good is not on offer at this merchant now, with the time to the next delivery. */
    private void notOnOffer(final Player player, final MerchantOffer offer) {
        final long minutes = Math.max(1, this.millisToDelivery() / 60_000);
        this.plugin.messages().send(player, "merchant.not-in-offer", Placeholder.component("item", Component.translatable(offer.material())),
            Placeholder.unparsed("hours", Long.toString(minutes / 60)), Placeholder.unparsed("minutes", Long.toString(minutes % 60)));
    }

    /** Player sells up to {@code bundles} bundles of the offer to a merchant of {@code type}. Returns copper paid (0 = nothing sold). */
    public long sell(final Player player, final MerchantType type, final MerchantOffer offer, final int bundles, final boolean quiet) {
        final Messages m = this.plugin.messages();
        if (!offer.buys()) {
            return 0;
        }
        if (!this.onOffer(type, offer)) {
            if (!quiet) {
                this.notOnOffer(player, offer);
            }
            return 0;
        }
        final ItemStack plain = ItemStack.of(offer.material());
        final int available = Items.count(player.getInventory().getStorageContents(), plain);
        final SkarbiecConfig c = this.plugin.cfg();
        final MerchantMath.Quote q;
        if (this.marketOn()) {
            this.advance();
            final MerchantMarket.Good g = this.market.good(offer.key());
            q = MerchantMath.quoteMarketSale(c.market, offer.amount(), offer.buy(), g.stock(), g.reference(), this.target(offer), this.budget(),
                this.rotationPercentRaw(type, offer), this.soldToday(player.getUniqueId(), offer), this.itemLimit(type, offer),
                this.earnedToday(player.getUniqueId()), c.merchantDailyLimit, available, bundles);
        } else {
            q = MerchantMath.quoteSale(offer.amount(), this.rotatedBuyPrice(type, offer), this.globalSold(offer),
                this.soldToday(player.getUniqueId(), offer), this.itemLimit(type, offer), this.earnedToday(player.getUniqueId()),
                c.merchantDailyLimit, available, bundles, this.supply());
        }
        if (q.bundles() == 0) {
            if (!quiet) {
                switch (q.limitedBy()) {
                    case BUDGET -> m.send(player, "merchant.no-budget");
                    case ITEMS -> m.send(player, "merchant.not-enough-items", Placeholder.component("item", Component.translatable(offer.material())),
                        Placeholder.unparsed("count", Integer.toString(offer.amount())));
                    case ITEM_DAILY -> m.send(player, "merchant.item-limit", Placeholder.component("item", Component.translatable(offer.material())));
                    case MONEY_DAILY -> m.send(player, "merchant.daily-limit", SkarbiecPlugin.amount(c.merchantDailyLimit));
                    default -> { }
                }
            }
            return 0;
        }
        final int items = q.bundles() * offer.amount();
        final int removed = Items.remove(player.getInventory(), plain, items);
        if (removed != items) {
            // Cannot happen (counted on the same tick); put back whatever was taken and stop.
            Items.give(player, plain, removed);
            return 0;
        }
        final String reason = "kupiec: skup " + items + "x " + offer.key();
        if (c.merchantToAccount) {
            final java.util.function.Supplier<TxResult> pay = () -> this.plugin.ledger().closedSupply()?
                this.plugin.ledger().transfer(pl.medivalrpg.skarbiec.ledger.WorldMoney.MERCHANT,SkarbiecPlugin.account(player),q.payout(),reason):
                this.plugin.ledger().deposit(SkarbiecPlugin.account(player), q.payout(), reason);
            final TxResult r = this.marketOn() ? this.market.settleSale(offer.key(), items, q.payout(), pay) : pay.get();
            if (!r.ok()) {
                Items.give(player, plain, items);
                if (r == TxResult.INSUFFICIENT_FUNDS) {
                    m.send(player, "merchant.no-budget");
                } else {
                    m.send(player, "error.transaction", Placeholder.unparsed("reason", r.name()));
                }
                return 0;
            }
        } else {
            final java.util.function.Supplier<TxResult> pay = () -> this.plugin.coins().payFromAccount(player,
                this.plugin.ledger().closedSupply()?pl.medivalrpg.skarbiec.ledger.WorldMoney.MERCHANT:pl.medivalrpg.skarbiec.ledger.WorldMoney.PAYMENTS,q.payout(),reason);
            final TxResult payment = this.marketOn() ? this.market.settleSale(offer.key(), items, q.payout(), pay) : pay.get();
            if(!payment.ok()){
                Items.give(player,plain,items);
                m.send(player,"merchant.no-budget");
                return 0;
            }
            this.plugin.store().audit("MERCHANT_COINS_OUT\t" + player.getUniqueId() + "\t" + player.getName() + "\t+"
                + q.payout() + "\t" + reason);
        }
        this.rollover();
        final PlayerDay d = this.players.computeIfAbsent(player.getUniqueId(), k -> new PlayerDay());
        d.earned += q.payout();
        d.sold.merge(offer.key(), (long) items, Long::sum);
        this.global.merge(offer.key(), (long) items, Long::sum);
        this.scheduleSave();
        if (!quiet) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.1f);
            m.send(player, "merchant.sold", Placeholder.unparsed("count", Integer.toString(items)),
                Placeholder.component("item", Component.translatable(offer.material())), SkarbiecPlugin.amount(q.payout()),
                Placeholder.unparsed("target", m.raw(c.merchantToAccount ? "merchant.to-account" : "merchant.to-purse")));
            if (q.limitedBy() == MerchantMath.Limit.MONEY_DAILY) {
                m.send(player, "merchant.daily-limit", SkarbiecPlugin.amount(c.merchantDailyLimit));
            } else if (q.limitedBy() == MerchantMath.Limit.ITEM_DAILY) {
                m.send(player, "merchant.item-limit", Placeholder.component("item", Component.translatable(offer.material())));
            }
        }
        return q.payout();
    }

    /** Sells everything this merchant type takes now that the player carries. */
    public void sellAll(final Player player, final MerchantType type) {
        long total = 0;
        int kinds = 0;
        for (final MerchantOffer offer : this.offers(type)) {
            final long paid = this.sell(player, type, offer, Integer.MAX_VALUE, true);
            if (paid > 0) {
                total += paid;
                kinds++;
            }
        }
        final Messages m = this.plugin.messages();
        if (total == 0) {
            final long limit = this.plugin.cfg().merchantDailyLimit;
            if (!this.buying()) {
                m.send(player, "merchant.no-budget");
            } else if (limit > 0 && this.earnedToday(player.getUniqueId()) >= limit) {
                m.send(player, "merchant.daily-limit", SkarbiecPlugin.amount(limit));
            } else {
                m.send(player, "merchant.nothing-to-sell");
            }
            return;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.1f);
        m.send(player, "merchant.sold-all", Placeholder.unparsed("kinds", Integer.toString(kinds)), SkarbiecPlugin.amount(total),
            Placeholder.unparsed("target", m.raw(this.plugin.cfg().merchantToAccount ? "merchant.to-account" : "merchant.to-purse")));
    }

    /** Player buys {@code bundles} bundles from a merchant of {@code type}. */
    public void buy(final Player player, final MerchantType type, final MerchantOffer offer, final int bundles) {
        final Messages m = this.plugin.messages();
        if (!offer.sells() || bundles <= 0) {
            return;
        }
        if (!this.onOffer(type, offer)) {
            this.notOnOffer(player, offer);
            return;
        }
        final ItemStack plain = ItemStack.of(offer.material());
        int count = bundles;
        long total = offer.sell() * bundles;
        if (this.marketOn()) {
            this.advance();
            final MerchantMarket.Good g = this.market.good(offer.key());
            final long budgetNow = Math.max(1, this.budget());
            final MerchantMath.Quote q = MerchantMath.quoteMarketPurchase(this.plugin.cfg().market, offer.amount(), offer.sell(), g.stock(),
                g.reference(), this.target(offer), st -> offer.buys() ? this.marketBuyAt(offer, st, budgetNow, this.maxRotationPercent(offer)) : 0,
                bundles);
            if (q.bundles() == 0) {
                m.send(player, "merchant.out-of-stock", Placeholder.component("item", Component.translatable(offer.material())));
                return;
            }
            count = q.bundles();
            total = q.payout();
        }
        final int items = count * offer.amount();
        final long price = total;
        if (!Items.fits(player.getInventory().getStorageContents(), plain, items)) {
            m.send(player, "merchant.no-space");
            return;
        }
        final String reason = "kupiec: zakup " + items + "x " + offer.key();
        if (this.plugin.cfg().merchantToAccount) {
            final String account = SkarbiecPlugin.account(player);
            final java.util.function.Supplier<TxResult> charge = () -> this.plugin.ledger().closedSupply()?
                this.plugin.ledger().transfer(account,pl.medivalrpg.skarbiec.ledger.WorldMoney.MERCHANT,price,reason):
                this.plugin.ledger().withdraw(account, price, reason);
            final TxResult r = this.marketOn() ? this.market.settlePurchase(offer.key(), items, price, charge) : charge.get();
            if (r == TxResult.INSUFFICIENT_FUNDS) {
                m.send(player, "merchant.cannot-afford", SkarbiecPlugin.amount(price));
                return;
            }
            if (!r.ok()) {
                m.send(player, "error.transaction", Placeholder.unparsed("reason", r.name()));
                return;
            }
        } else {
            final java.util.function.Supplier<TxResult> charge = () -> (this.plugin.coins().closedSupply()
                ? this.plugin.coins().takeCoinsTo(player, price, pl.medivalrpg.skarbiec.ledger.WorldMoney.MERCHANT)
                : this.plugin.coins().takeCoins(player, price)) ? TxResult.OK : TxResult.INSUFFICIENT_FUNDS;
            final TxResult r = this.marketOn() ? this.market.settlePurchase(offer.key(), items, price, charge) : charge.get();
            if (!r.ok()) {
                m.send(player, "merchant.cannot-afford", SkarbiecPlugin.amount(price));
                return;
            }
            this.plugin.store().audit("MERCHANT_COINS_IN\t" + player.getUniqueId() + "\t" + player.getName() + "\t-"
                + price + "\t" + reason);
        }
        Items.give(player, plain, items);
        this.scheduleSave();
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.7f, 1.0f);
        m.send(player, "merchant.bought", Placeholder.unparsed("count", Integer.toString(items)),
            Placeholder.component("item", Component.translatable(offer.material())), SkarbiecPlugin.amount(price));
    }
}
