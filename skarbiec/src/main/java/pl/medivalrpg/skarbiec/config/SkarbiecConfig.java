// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.config;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import pl.medivalrpg.skarbiec.merchant.MerchantMath;
import pl.medivalrpg.skarbiec.merchant.MerchantOffer;
import pl.medivalrpg.skarbiec.money.Denomination;
import pl.medivalrpg.skarbiec.money.Money;

/** Typed view of config.yml (everything except {@code messages}). Immutable; rebuilt on reload. */
public final class SkarbiecConfig {

    public enum PayMode { ANYWHERE, BANKER }

    public enum Territory { ANYWHERE, OWN_OR_WILD, OWN }

    // storage
    public final int snapshotIntervalSeconds;
    public final boolean fsync;
    public final int logKeepDays;
    public final ZoneId zone;

    // coins
    public final boolean coinGlint;
    public final Map<Denomination, String> coinNames = new EnumMap<>(Denomination.class);
    public final Map<Denomination, List<String>> coinLore = new EnumMap<>(Denomination.class);

    // bank
    public final List<Long> quickWithdraw;
    public final int chatInputTimeoutSeconds;

    // pay
    public final PayMode payMode;
    public final double bankerRadius;
    public final long payMin;

    // merchant
    public final boolean merchantToAccount;
    public final long merchantDailyLimit;
    public final MerchantMath.Rotation rotation;
    public final boolean supplyEnabled;
    public final int supplyStep;
    public final int supplyDropPercent;
    public final int supplyMinPercent;
    public final List<MerchantOffer> offers;
    /** Dynamic market (stock + budget); {@code enabled == false} keeps the old fixed prices with the supply drop. */
    public final MerchantMath.Market market;
    public final int marketHistoryHours;
    /** Target stock per good (items): {@code target-stock} of the offer, else {@code market.target-bundles} bundles. */
    public final Map<String, Long> marketTargets;
    /** Explicit restock (items/day) per good: these goods are CREATED by the merchant up to the target. */
    public final Map<String, Long> marketRestock;
    /** Delivery clock of the merchants ({@code merchant.assortment}); disabled = every type trades its whole catalog. */
    public final pl.medivalrpg.skarbiec.merchant.MerchantAssortment.Schedule assortment;
    /** Merchant types by id (config order); never empty. */
    public final Map<String, pl.medivalrpg.skarbiec.merchant.MerchantType> merchantTypes;
    /** Type of merchant NPCs without a stored type (all NPCs created before the types existed). */
    public final String defaultMerchantType;
    /** Labour share of construction payments for world:households (closed pool). */
    public final pl.medivalrpg.skarbiec.ledger.LaborShare laborShare;
    /** One-time deployment grants from the reserve: run automatically at start only when this is true. */
    public final boolean grantsAutoOnStart;

    // shops
    public final boolean shopsEnabled;
    public final int shopsMaxPerPlayer;
    public final long shopsMaxPrice;
    public final int shopsMaxAmount;
    public final Set<Material> shopContainers;
    public final Territory shopTerritory;
    public final boolean shopBuyPayoutToAccount;

    // trade
    public final double tradeRange;
    public final int tradeCountdownSeconds;
    public final int tradeRequestTimeoutSeconds;

    // npc
    public final boolean npcLookAtPlayers;
    public final double npcLookRadius;
    public final String bankerName;
    public final String merchantName;
    public final String bankerSkin;
    public final String merchantSkin;
    public final boolean debugNpcClicks;
    /** Market stalls (BetterModel) next to merchants. */
    public final boolean stallsEnabled;
    public final double stallViewDistance;

    public SkarbiecConfig(final FileConfiguration c, final Logger log) {
        this.snapshotIntervalSeconds = Math.max(5, c.getInt("storage.snapshot-interval-seconds", 60));
        this.fsync = c.getBoolean("storage.fsync", true);
        this.logKeepDays = Math.max(1, c.getInt("storage.log-keep-days", 90));
        this.zone = zone(c.getString("storage.timezone", ""), log);

        this.coinGlint = c.getBoolean("coins.glint", true);
        for (final Denomination d : Denomination.values()) {
            this.coinNames.put(d, c.getString("coins." + d.id() + ".name", d.symbol()));
            this.coinLore.put(d, List.copyOf(c.getStringList("coins." + d.id() + ".lore")));
        }

        final List<Long> quick = new ArrayList<>();
        for (final Object o : c.getList("bank.quick-withdraw", List.of(10, 100, 1000))) {
            final long v = amount(o, -1);
            if (v > 0) {
                quick.add(v);
            } else {
                log.warning("bank.quick-withdraw: nieprawidłowa kwota " + o);
            }
        }
        this.quickWithdraw = List.copyOf(quick.subList(0, Math.min(3, quick.size())));
        this.chatInputTimeoutSeconds = Math.max(5, c.getInt("bank.chat-input-timeout-seconds", 30));

        this.payMode = "banker".equalsIgnoreCase(c.getString("pay.mode", "anywhere")) ? PayMode.BANKER : PayMode.ANYWHERE;
        this.bankerRadius = Math.max(1, c.getDouble("pay.banker-radius", 8));
        this.payMin = Math.max(1, amount(c.get("pay.min-amount", 1), 1));

        this.merchantToAccount = "account".equalsIgnoreCase(c.getString("merchant.payout", "coins"));
        this.merchantDailyLimit = Math.max(0, amount(c.get("merchant.daily-limit", 500), 500));
        this.supplyEnabled = c.getBoolean("merchant.supply.enabled", true);
        this.supplyStep = Math.max(1, c.getInt("merchant.supply.step", 128));
        this.supplyDropPercent = clamp(c.getInt("merchant.supply.drop-percent", 5), 0, 100);
        this.supplyMinPercent = clamp(c.getInt("merchant.supply.min-percent", 40), 1, 100);
        this.rotation = new MerchantMath.Rotation(c.getBoolean("merchant.rotation.enabled", true),
            clamp(c.getInt("merchant.rotation.period-hours", 3), 1, 168),
            clamp(c.getInt("merchant.rotation.price-swing-percent", 15), 0, 50),
            clamp(c.getInt("merchant.rotation.featured", 6), 0, 45),
            clamp(c.getInt("merchant.rotation.featured-bonus-percent", 40), 0, 200),
            clamp(c.getInt("merchant.rotation.featured-limit-multiplier", 2), 1, 10));
        this.offers = List.copyOf(offers(c, log));
        this.market = new MerchantMath.Market(c.getBoolean("merchant.market.enabled", true),
            c.getInt("merchant.market.target-bundles", 32),
            c.getDouble("merchant.market.elasticity", 0.5),
            c.getInt("merchant.market.min-percent", 50),
            c.getInt("merchant.market.max-percent", 175),
            c.getInt("merchant.market.spread-percent", 25),
            c.getDouble("merchant.market.smoothing-hours", 6),
            c.getDouble("merchant.market.surplus-decay-percent-per-day", 10),
            Math.max(0, amount(c.get("merchant.market.budget.target", "500 zł"), 50_000)),
            c.getDouble("merchant.market.budget.elasticity", 0.5),
            c.getInt("merchant.market.budget.min-percent", 30),
            c.getInt("merchant.market.budget.max-percent", 125),
            c.getInt("merchant.market.low-stock-percent", 50),
            c.getInt("merchant.market.high-stock-percent", 150));
        this.marketHistoryHours = clamp(c.getInt("merchant.market.history-hours", 168), 24, 744);
        final Map<String, Long> targets = new LinkedHashMap<>();
        final Map<String, Long> restock = new LinkedHashMap<>();
        for (final Map<?, ?> raw : c.getMapList("merchant.offers")) {
            final Object itemName = raw.get("item");
            final Material material = itemName == null ? null : Material.matchMaterial(itemName.toString());
            if (material == null) {
                continue;
            }
            final MerchantOffer offer = this.offers.stream().filter(o -> o.material() == material).findFirst().orElse(null);
            if (offer == null || targets.containsKey(offer.key())) {
                continue;
            }
            final int target = intOf(raw.get("target-stock"), 0);
            targets.put(offer.key(), (long) (target > 0 ? target : this.market.targetBundles() * offer.amount()));
            final int rate = intOf(raw.get("restock-per-day"), 0);
            if (rate > 0) {
                restock.put(offer.key(), (long) rate);
            }
        }
        final List<String> prefixes = c.isList("world-economy.labor-share.prefixes")
            ? c.getStringList("world-economy.labor-share.prefixes") : pl.medivalrpg.skarbiec.ledger.LaborShare.DEFAULT_PREFIXES;
        final List<String> refunds = c.isList("world-economy.labor-share.refund-prefixes")
            ? c.getStringList("world-economy.labor-share.refund-prefixes") : pl.medivalrpg.skarbiec.ledger.LaborShare.DEFAULT_REFUND_PREFIXES;
        this.laborShare = new pl.medivalrpg.skarbiec.ledger.LaborShare(
            (int) Math.round(Math.max(0, c.getDouble("world-economy.labor-share.percent", 60.0)) * 100),
            prefixes, refunds, Math.max(0, amount(c.get("world-economy.labor-share.daily-cap", 3_000), 3_000)),
            Math.max(0, amount(c.get("world-economy.labor-share.target-balance", 400_000), 400_000)),
            c.getDouble("world-economy.labor-share.settle-after-hours", 24.0));
        this.grantsAutoOnStart = c.getBoolean("world-economy.grants.auto-on-start", false);
        this.marketTargets = java.util.Collections.unmodifiableMap(targets);
        this.assortment = new pl.medivalrpg.skarbiec.merchant.MerchantAssortment.Schedule(c.getBoolean("merchant.assortment.enabled", false),
            c.getInt("merchant.assortment.period-hours", 12), c.getInt("merchant.assortment.start-hour", 6),
            c.getInt("merchant.assortment.featured", this.rotation.featured()), c.getBoolean("merchant.assortment.preview", true));
        this.merchantTypes = merchantTypes(c, log, this.offers, c.getString("npc.default-name.merchant", "<green>Kupiec"));
        final String def = c.getString("merchant.default-type", "ogolny").toLowerCase(Locale.ROOT);
        if (this.merchantTypes.containsKey(def)) {
            this.defaultMerchantType = def;
        } else {
            this.defaultMerchantType = this.merchantTypes.keySet().iterator().next();
            log.warning("merchant.default-type: nieznany typ " + def + " — używam " + this.defaultMerchantType);
        }
        this.marketRestock = java.util.Collections.unmodifiableMap(restock);

        this.shopsEnabled = c.getBoolean("shops.enabled", true);
        this.shopsMaxPerPlayer = Math.max(0, c.getInt("shops.max-per-player", 10));
        this.shopsMaxPrice = Math.max(1, amount(c.get("shops.max-price", 100_000), 100_000));
        this.shopsMaxAmount = clamp(c.getInt("shops.max-amount", 576), 1, 2304);
        final Set<Material> containers = EnumSet.noneOf(Material.class);
        for (final String name : c.getStringList("shops.containers")) {
            final Material m = Material.matchMaterial(name);
            if (m == null) {
                log.warning("shops.containers: nieznany blok " + name);
            } else {
                containers.add(m);
            }
        }
        if (containers.isEmpty()) {
            containers.addAll(List.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL));
        }
        this.shopContainers = Set.copyOf(containers);
        this.shopTerritory = switch (c.getString("shops.territory", "own-or-wild").toLowerCase(Locale.ROOT)) {
            case "anywhere" -> Territory.ANYWHERE;
            case "own" -> Territory.OWN;
            default -> Territory.OWN_OR_WILD;
        };
        this.shopBuyPayoutToAccount = "account".equalsIgnoreCase(c.getString("shops.buy-payout", "coins"));

        this.tradeRange = Math.max(1, c.getDouble("trade.range", 10));
        this.tradeCountdownSeconds = clamp(c.getInt("trade.countdown-seconds", 3), 1, 30);
        this.tradeRequestTimeoutSeconds = Math.max(5, c.getInt("trade.request-timeout-seconds", 60));

        this.npcLookAtPlayers = c.getBoolean("npc.look-at-players", true);
        this.npcLookRadius = Math.max(1, c.getDouble("npc.look-radius", 6));
        this.bankerName = c.getString("npc.default-name.banker", "<gold>Bankier");
        this.merchantName = c.getString("npc.default-name.merchant", "<green>Kupiec");
        this.bankerSkin = c.getString("npc.default-skin.banker", "");
        this.merchantSkin = c.getString("npc.default-skin.merchant", "");
        this.debugNpcClicks = c.getBoolean("debug-npc-clicks", false);
        this.stallsEnabled = c.getBoolean("npc.stalls.enabled", true);
        this.stallViewDistance = Math.max(16, Math.min(128, c.getDouble("npc.stalls.view-distance", 48)));
    }

    private static List<MerchantOffer> offers(final FileConfiguration c, final Logger log) {
        final Map<Material, MerchantOffer> out = new LinkedHashMap<>();
        for (final Map<?, ?> raw : c.getMapList("merchant.offers")) {
            final Object itemName = raw.get("item");
            final Material material = itemName == null ? null : Material.matchMaterial(itemName.toString());
            if (material == null || material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
                log.warning("merchant.offers: nieznany przedmiot " + itemName);
                continue;
            }
            final int amount = clamp(intOf(raw.get("amount"), 1), 1, 64);
            final long buy = Math.max(0, amount(raw.get("buy"), 0));
            long sell = Math.max(0, amount(raw.get("sell"), 0));
            final int daily = Math.max(0, intOf(raw.get("daily-limit"), 0));
            if (buy == 0 && sell == 0) {
                log.warning("merchant.offers: " + material + " nie ma ani ceny skupu, ani sprzedaży — pomijam");
                continue;
            }
            if (sell > 0 && sell < buy) {
                // Selling cheaper than buying back would be a money printer.
                log.warning("merchant.offers: " + material + " — cena sprzedaży niższa od skupu, podnoszę do " + buy);
                sell = buy;
            }
            if (out.containsKey(material)) {
                log.warning("merchant.offers: " + material + " występuje dwa razy — używam pierwszego wpisu");
                continue;
            }
            final Object cat = raw.get("category");
            final Set<String> seasons = new java.util.LinkedHashSet<>();
            if (raw.get("seasons") instanceof List<?> list) {
                for (final Object season : list) {
                    final String id = String.valueOf(season).toLowerCase(Locale.ROOT).replace('ę', 'e');
                    if (pl.medivalrpg.skarbiec.merchant.MerchantAssortment.SEASONS.contains(id)) {
                        seasons.add(id);
                    } else {
                        log.warning("merchant.offers: " + material + " — nieznana pora roku " + season + " (wiosna|lato|jesien|zima)");
                    }
                }
            }
            out.put(material, new MerchantOffer(material, amount, buy, sell, daily,
                cat == null ? null : cat.toString().toLowerCase(Locale.ROOT), Boolean.TRUE.equals(raw.get("core"))
                    || "true".equalsIgnoreCase(String.valueOf(raw.get("core"))), seasons));
        }
        return new ArrayList<>(out.values());
    }

    /**
     * {@code merchant.types}: id → name, icon, categories, core, rotating, seasonal. Without the section there is one type
     * {@code ogolny} carrying everything (the merchant as before the types existed).
     */
    private static Map<String, pl.medivalrpg.skarbiec.merchant.MerchantType> merchantTypes(final FileConfiguration c, final Logger log,
            final List<MerchantOffer> offers, final String defaultName) {
        final Map<String, pl.medivalrpg.skarbiec.merchant.MerchantType> out = new LinkedHashMap<>();
        final var section = c.getConfigurationSection("merchant.types");
        final Set<String> known = new java.util.HashSet<>();
        offers.forEach(o -> known.add(o.category()));
        if (section != null) {
            for (final String rawId : section.getKeys(false)) {
                final String id = rawId.toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9_-]{1,24}")) {
                    log.warning("merchant.types: nieprawidłowy identyfikator " + rawId + " (a-z, 0-9, _ -)");
                    continue;
                }
                final var t = section.getConfigurationSection(rawId);
                if (t == null) {
                    continue;
                }
                final Material icon = Material.matchMaterial(t.getString("icon", "WHEAT"));
                final Set<String> categories = new java.util.LinkedHashSet<>();
                for (final String cat : t.getStringList("categories")) {
                    final String k = cat.toLowerCase(Locale.ROOT);
                    if (!k.equals(pl.medivalrpg.skarbiec.merchant.MerchantType.ALL) && !known.contains(k)) {
                        log.warning("merchant.types." + id + ": kategoria " + cat + " nie ma żadnego towaru w cenniku");
                    }
                    categories.add(k);
                }
                if (categories.isEmpty()) {
                    categories.add(pl.medivalrpg.skarbiec.merchant.MerchantType.ALL);
                }
                final Set<String> core = new java.util.LinkedHashSet<>();
                for (final String item : t.getStringList("core")) {
                    final Material m = Material.matchMaterial(item);
                    if (m == null || offers.stream().noneMatch(o -> o.material() == m)) {
                        log.warning("merchant.types." + id + ".core: " + item + " nie ma w cenniku — pomijam");
                    } else {
                        core.add(m.name());
                    }
                }
                out.put(id, new pl.medivalrpg.skarbiec.merchant.MerchantType(id, t.getString("name", defaultName),
                    icon == null || icon == Material.AIR ? Material.WHEAT : icon, categories, core, clamp(t.getInt("rotating", 16), 0, 200),
                    clamp(t.getInt("seasonal", 0), 0, 45)));
            }
        }
        if (out.isEmpty()) {
            out.put("ogolny", new pl.medivalrpg.skarbiec.merchant.MerchantType("ogolny", defaultName, Material.WHEAT,
                Set.of(pl.medivalrpg.skarbiec.merchant.MerchantType.ALL), Set.of(), 16, 2));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** Merchant type by id; unknown or {@code null} = the default type. */
    public pl.medivalrpg.skarbiec.merchant.MerchantType merchantType(final String id) {
        final var t = id == null ? null : this.merchantTypes.get(id.toLowerCase(Locale.ROOT));
        return t != null ? t : this.merchantTypes.get(this.defaultMerchantType);
    }

    /** Number (copper) or text such as {@code "1 zł 5 sr"}; {@code def} when missing/invalid. */
    public static long amount(final Object o, final long def) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return def;
        }
        return Money.parse(o.toString()).orElse(def);
    }

    private static int intOf(final Object o, final int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return def;
        }
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static ZoneId zone(final String id, final Logger log) {
        if (id == null || id.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(id);
        } catch (final DateTimeException e) {
            log.warning("storage.timezone: nieznana strefa " + id + " — używam strefy serwera");
            return ZoneId.systemDefault();
        }
    }
}
