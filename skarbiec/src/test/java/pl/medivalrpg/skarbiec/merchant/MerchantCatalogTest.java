// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.config.SkarbiecConfig;

/**
 * The catalog for epochs 1–2, the merged production config (prices ×3.5 kept), the old production config (no deliveries:
 * the merchant behaves as before), no arbitrage between merchant types, an empty budget, and the warehouse of goods out of
 * the offer (kept, not shipped away) incl. the live merchant-market.json.
 */
class MerchantCatalogTest {

    private static final Path PROD_DIR = Path.of("config-merchant-catalog-20261029");
    private static final Pattern BANNED = Pattern.compile("ELYTRA|TOTEM|SHULKER|NETHERITE|BEACON|SPAWNER|ENCHANTED|DRAGON|NETHER_STAR"
        + "|REDSTONE|PISTON|HOPPER|RAIL|MINECART|TNT|FIREWORK|TRIDENT|MACE|GOLDEN_APPLE|_NUGGET$|SPAWN_EGG"
        + "|DIAMOND_(SWORD|PICKAXE|AXE|SHOVEL|HOE|HELMET|CHESTPLATE|LEGGINGS|BOOTS)");

    private static SkarbiecConfig load(final YamlConfiguration y) {
        return new SkarbiecConfig(y, Logger.getAnonymousLogger());
    }

    private static YamlConfiguration resource() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            MerchantCatalogTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    private static YamlConfiguration file(final String name) {
        return YamlConfiguration.loadConfiguration(PROD_DIR.resolve(name).toFile());
    }

    @Test
    void catalogStaysInEpochsOneAndTwoAndHasNoMoneyPrinter() {
        final SkarbiecConfig c = load(resource());
        for (final MerchantOffer o : c.offers) {
            assertFalse(BANNED.matcher(o.key()).find(), "not for epoch <= 2 / balance: " + o.key());
            if (o.buys() && o.sells()) {
                assertTrue(o.sell() * 4 >= o.buy() * 5, "sell >= 1.25 x buy: " + o.key());
            }
            assertFalse(o.category().equals(MerchantOffer.DEFAULT_CATEGORY), "category set: " + o.key());
        }
    }

    @Test
    void mergedProductionConfigKeepsEveryLivePriceAndSetting() {
        // The production config snapshot is not part of the public repository; skip when it is absent.
        Assumptions.assumeTrue(Files.isDirectory(PROD_DIR.resolve("production")), "production snapshot not available");
        final YamlConfiguration live = file("production/config.active.yml");
        final YamlConfiguration merged = file("config.yml");
        final SkarbiecConfig before = load(live);
        final SkarbiecConfig after = load(merged);
        assertFalse(before.assortment.enabled(), "live config: no deliveries (old behaviour of the jar)");
        assertEquals(1, before.merchantTypes.size());
        assertTrue(after.assortment.enabled());
        assertEquals(4, after.merchantTypes.size());
        assertEquals(load(resource()).offers.size(), after.offers.size());
        final Map<String, MerchantOffer> now = new HashMap<>();
        after.offers.forEach(o -> now.put(o.key(), o));
        for (final MerchantOffer o : before.offers) {
            final MerchantOffer n = now.get(o.key());
            assertNotNull(n, o.key());
            assertEquals(o.amount(), n.amount(), o.key());
            assertEquals(o.sell(), n.sell(), o.key());
            if (o.buys()) {
                assertEquals(o.buy(), n.buy(), "x3.5 price kept: " + o.key());
                assertEquals(o.dailyLimit(), n.dailyLimit(), o.key());
            } else {
                // sell-only goods could never be stocked (no restock): they get a buying price, below the selling one
                assertTrue(n.buys() && n.sell() * 4 >= n.buy() * 5 && n.dailyLimit() > 0, o.key());
            }
        }
        assertEquals(before.merchantDailyLimit, after.merchantDailyLimit, "500 zł per player");
        assertEquals(before.rotation, after.rotation);
        assertEquals(before.market, after.market);
        assertEquals(before.payMode, after.payMode);
        // everything outside merchant: untouched
        for (final String key : live.getKeys(false)) {
            if (!key.equals("merchant")) {
                assertEquals(live.get(key).toString(), merged.get(key).toString(), key);
            }
        }
        // new goods: x3.5 of the jar's base price (same scale as the existing goods)
        final Map<String, MerchantOffer> base = new HashMap<>();
        load(resource()).offers.forEach(o -> base.put(o.key(), o));
        final Set<String> old = new java.util.HashSet<>();
        before.offers.forEach(o -> old.add(o.key()));
        for (final MerchantOffer n : after.offers) {
            if (old.contains(n.key())) {
                continue;
            }
            final MerchantOffer b = base.get(n.key());
            assertEquals(Math.round(b.buy() * 3.5 + 1e-9), n.buy(), n.key());
            assertEquals(Math.round(b.sell() * 3.5 + 1e-9), n.sell(), n.key());
            if (n.buys() && n.sells()) {
                assertTrue(n.sell() * 4 >= n.buy() * 5, n.key());
            }
        }
    }

    /**
     * A good can be featured (+bonus) at one merchant type and not at another. The selling price is floored on the HIGHEST
     * buying price of all types, so selling to one merchant and buying back from another never earns money.
     */
    @Test
    void noArbitrageBetweenMerchantTypes() {
        final MerchantMath.Market m = load(resource()).market;
        final MerchantMath.Rotation rot = load(resource()).rotation;
        final Random rnd = new Random(29);
        for (final MerchantOffer o : load(file("config.yml")).offers) {
            if (!o.buys() || !o.sells()) {
                continue;
            }
            final long target = 32L * o.amount();
            for (int i = 0; i < 200; i++) {
                final long stock = rnd.nextInt((int) (6 * target) + 1);
                final long budget = 1 + rnd.nextInt(2_000_000);
                final long period = rnd.nextInt(10_000);
                final int plain = rot.percent(o.key(), period, false);
                final int featured = rot.percent(o.key(), period, true);
                final long buyFeatured = m.buyPrice(o.buy(), stock, target, budget, featured);
                final long buyPlain = m.buyPrice(o.buy(), stock, target, budget, plain);
                final long sell = m.sellPrice(o.sell(), stock, target, Math.max(buyFeatured, buyPlain));
                assertTrue(sell * 4 >= Math.max(buyFeatured, buyPlain) * 5, o.key() + " sell " + sell + " buy " + buyFeatured);
            }
        }
    }

    @Test
    void emptyBudgetStopsBuyingOfEveryGood() {
        final SkarbiecConfig c = load(file("config.yml"));
        for (final MerchantOffer o : c.offers) {
            if (o.buys()) {
                assertEquals(0, c.market.buyPrice(o.buy(), 0, 32L * o.amount(), 0, 140), o.key());
                assertEquals(0, MerchantMath.quoteMarketSale(c.market, o.amount(), o.buy(), 0, 0, 32L * o.amount(), 0, 100, 0,
                    o.dailyLimit(), 0, c.merchantDailyLimit, 64, 1).bundles(), o.key());
            }
        }
    }

    @Test
    void stockOfGoodsOutOfTheOfferWaitsInTheWarehouse() {
        final SkarbiecConfig c = load(resource());
        final ZoneId zone = ZoneId.of("Europe/Warsaw");
        final long t0 = 1_793_000_000_000L;
        final MerchantMarket market = new MerchantMarket(t0, zone);
        market.settleSale("GLASS", 5_000, 10, () -> pl.medivalrpg.skarbiec.api.TxResult.OK);
        market.settleSale("WHEAT", 5_000, 10, () -> pl.medivalrpg.skarbiec.api.TxResult.OK);
        market.advance(t0 + 3 * 86_400_000L, zone, c.market, c.marketTargets, Map.of("GLASS", 100L), Set.of("GLASS"));
        assertEquals(5_000, market.good("GLASS").stock(), "resting good: no surplus shipping, no restock");
        assertTrue(market.good("WHEAT").stock() < 5_000, "a good on offer ships its surplus away");
        // the same warehouse after a save/load (restart)
        final MerchantMarket reloaded = MerchantMarket.fromJson(market.toJson(), t0 + 3 * 86_400_000L, zone);
        assertEquals(5_000, reloaded.good("GLASS").stock());
    }

    @Test
    void liveMerchantMarketFileStillLoads() throws Exception {
        // The production config snapshot is not part of the public repository; skip when it is absent.
        Assumptions.assumeTrue(Files.isDirectory(PROD_DIR.resolve("production")), "production snapshot not available");
        final Path live = PROD_DIR.resolve("production/merchant-market.active.json");
        final MerchantMarket m = MerchantMarket.fromJson(JsonParser.parseString(Files.readString(live)), System.currentTimeMillis(),
            ZoneId.of("Europe/Warsaw"));
        assertTrue(m.goods().containsKey("IRON_INGOT"));
        final SkarbiecConfig c = load(file("config.yml"));
        m.advance(System.currentTimeMillis() + 3_600_000L, ZoneId.of("Europe/Warsaw"), c.market, c.marketTargets, c.marketRestock, Set.of());
        assertTrue(m.good("IRON_INGOT").stock() >= 0);
        assertEquals(List.of(), c.marketRestock.keySet().stream().toList(), "no item is created from thin air");
    }
}
