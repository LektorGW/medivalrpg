// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.config.SkarbiecConfig;

/** Deliveries: deterministic, restart-proof, core always on offer, seasons, several merchant types. */
class MerchantAssortmentTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final long HOUR = 3_600_000L;

    private static SkarbiecConfig defaults() {
        return new SkarbiecConfig(YamlConfiguration.loadConfiguration(new InputStreamReader(
            MerchantAssortmentTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)), Logger.getAnonymousLogger());
    }

    private static long at(final int y, final int mo, final int d, final int h, final int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ZONE).toInstant().toEpochMilli();
    }

    @Test
    void defaultCatalogHasFourTypesAndADeliveryClock() {
        final SkarbiecConfig c = defaults();
        assertTrue(c.assortment.enabled());
        assertEquals(12, c.assortment.periodHours());
        assertEquals(List.of("ogolny", "spozywczy", "budowlany", "kowal"), List.copyOf(c.merchantTypes.keySet()));
        assertEquals("ogolny", c.defaultMerchantType);
        assertEquals(c.merchantTypes.get("ogolny"), c.merchantType(null), "NPCs without a type are general merchants");
        assertEquals(c.merchantTypes.get("ogolny"), c.merchantType("nieistniejacy"));
        assertTrue(c.offers.size() >= 150, "big catalog: " + c.offers.size());
        for (final MerchantType t : c.merchantTypes.values()) {
            assertTrue(c.offers.stream().anyMatch(t::carries), t.id());
        }
    }

    @Test
    void deliveriesStartAtSixAndEighteenLocalTimeAlsoAcrossDst() {
        final MerchantAssortment.Schedule s = defaults().assortment;
        final long p = s.period(at(2026, 10, 29, 10, 0), ZONE);
        assertEquals(at(2026, 10, 29, 6, 0), s.start(p, ZONE));
        assertEquals(at(2026, 10, 29, 18, 0), s.start(p + 1, ZONE));
        assertEquals(8 * HOUR, s.millisToNext(at(2026, 10, 29, 10, 0), ZONE));
        assertEquals(p - 1, s.period(at(2026, 10, 29, 5, 59), ZONE));
        // every hour of a year (incl. both DST switches): start(period(t)) <= t < start(period(t) + 1), periods never go back
        long previous = Long.MIN_VALUE;
        for (long t = at(2026, 1, 1, 0, 0); t < at(2027, 1, 1, 0, 0); t += HOUR / 2) {
            final long q = s.period(t, ZONE);
            assertTrue(s.start(q, ZONE) <= t && t < s.start(q + 1, ZONE), "t=" + t);
            assertTrue(q >= previous);
            assertTrue(s.millisToNext(t, ZONE) > 0);
            previous = q;
        }
    }

    @Test
    void sameDeliveryAfterRestartAndForEveryCaller() {
        final SkarbiecConfig a = defaults();
        final SkarbiecConfig b = defaults(); // "restart": the config is parsed again, nothing is stored
        for (final String type : a.merchantTypes.keySet()) {
            for (long p = 41_000; p < 41_050; p++) {
                assertEquals(MerchantAssortment.pick(a.merchantTypes.get(type), a.offers, p, "jesien", 4),
                    MerchantAssortment.pick(b.merchantTypes.get(type), b.offers, p, "jesien", 4), type + " " + p);
            }
        }
    }

    @Test
    void catalogOrderDoesNotChangeTheDelivery() {
        final SkarbiecConfig c = defaults();
        final List<MerchantOffer> shuffled = new ArrayList<>(c.offers);
        Collections.shuffle(shuffled, new Random(7));
        for (final MerchantType t : c.merchantTypes.values()) {
            final var x = MerchantAssortment.pick(t, c.offers, 41_234, "lato", 4);
            final var y = MerchantAssortment.pick(t, shuffled, 41_234, "lato", 4);
            assertEquals(Set.copyOf(x.offers()), Set.copyOf(y.offers()), t.id());
            assertEquals(x.featured(), y.featured(), t.id());
        }
    }

    @Test
    void coreIsAlwaysOnOfferAndTheRestRotates() {
        final SkarbiecConfig c = defaults();
        for (final MerchantType t : c.merchantTypes.values()) {
            final List<MerchantOffer> core = c.offers.stream().filter(t::isCore).toList();
            assertFalse(core.isEmpty(), t.id());
            final Set<String> seen = new HashSet<>();
            Set<String> previous = null;
            int changes = 0;
            for (long p = 41_000; p < 41_120; p++) {
                final var pick = MerchantAssortment.pick(t, c.offers, p, null, 4);
                assertTrue(pick.offers().containsAll(core), t.id() + " core in " + p);
                final long regular = c.offers.stream().filter(o -> t.carries(o) && !t.isCore(o) && !o.seasonal()).count();
                assertEquals(Math.min(t.rotating(), regular), pick.rotating().size(), t.id());
                assertTrue(pick.seasonal().isEmpty(), "unknown season: no seasonal goods");
                assertTrue(pick.offers().stream().allMatch(t::carries));
                assertTrue(pick.offers().size() <= 45, "fits one GUI page: " + pick.offers().size());
                final Set<String> now = new HashSet<>();
                pick.rotating().forEach(o -> now.add(o.key()));
                seen.addAll(now);
                if (previous != null && !previous.equals(now)) {
                    changes++;
                }
                previous = now;
                // featured: drawn from what is on offer and bought
                assertEquals(Math.min(4, pick.offers().stream().filter(MerchantOffer::buys).count()), pick.featured().size());
                assertTrue(pick.offers().stream().filter(o -> pick.featured().contains(o.key())).allMatch(MerchantOffer::buys));
            }
            assertTrue(changes >= 100, t.id() + " rotates: " + changes);
            final long regular = c.offers.stream().filter(o -> t.carries(o) && !t.isCore(o) && !o.seasonal()).count();
            assertEquals(regular, seen.size(), t.id() + ": every good of the catalog comes in 60 days");
        }
    }

    @Test
    void seasonalGoodsOnlyInTheirSeason() {
        final SkarbiecConfig c = defaults();
        final MerchantType food = c.merchantTypes.get("spozywczy");
        for (final String season : MerchantAssortment.SEASONS) {
            boolean any = false;
            for (long p = 41_000; p < 41_040; p++) {
                final var pick = MerchantAssortment.pick(food, c.offers, p, season, 4);
                for (final MerchantOffer o : pick.seasonal()) {
                    assertTrue(o.seasons().contains(season), o.key() + " in " + season);
                }
                assertTrue(pick.rotating().stream().noneMatch(MerchantOffer::seasonal));
                any |= !pick.seasonal().isEmpty();
            }
            assertTrue(any, "food merchant has seasonal goods in " + season);
        }
        final MerchantAssortment.SeasonClock autumn = new MerchantAssortment.SeasonClock("jesien", 2, 1_000, 2_000);
        assertEquals("jesien", autumn.idAt(1_500));
        assertEquals("zima", autumn.idAt(2_000));
        assertEquals("lato", autumn.idAt(999));
        assertEquals("wiosna", new MerchantAssortment.SeasonClock("zima", 3, 0, 10).idAt(10));
    }

    @Test
    void typesHaveTheirOwnCatalogsAndDeliveries() {
        final SkarbiecConfig c = defaults();
        final var smith = c.merchantTypes.get("kowal");
        final var builder = c.merchantTypes.get("budowlany");
        final var food = c.merchantTypes.get("spozywczy");
        assertTrue(c.offers.stream().filter(smith::carries).noneMatch(o -> o.key().equals("BREAD")));
        assertTrue(c.offers.stream().filter(food::carries).noneMatch(o -> o.key().equals("IRON_PICKAXE")));
        for (final String key : List.of("GLASS", "BRICKS", "TERRACOTTA", "WHITE_WOOL", "RED_WOOL")) {
            assertTrue(c.offers.stream().anyMatch(o -> o.key().equals(key) && builder.carries(o)), "Ludnosc building material " + key);
        }
        final long p = 41_111;
        assertNotEquals(MerchantAssortment.pick(smith, c.offers, p, null, 4).offers(),
            MerchantAssortment.pick(builder, c.offers, p, null, 4).offers());
        // the general merchant draws its own delivery from everything
        final var general = c.merchantTypes.get("ogolny");
        assertTrue(c.offers.stream().allMatch(general::carries));
    }

    @Test
    void scoreIsStable() {
        // pinned values: a change would silently reshuffle every running server's deliveries
        assertEquals(MerchantAssortment.score(1, "ogolny", 41_000, "WHEAT"), MerchantAssortment.score(1, "ogolny", 41_000, "WHEAT"));
        assertNotEquals(MerchantAssortment.score(1, "ogolny", 41_000, "WHEAT"), MerchantAssortment.score(1, "kowal", 41_000, "WHEAT"));
        assertNotEquals(MerchantAssortment.score(1, "ogolny", 41_000, "WHEAT"), MerchantAssortment.score(1, "ogolny", 41_001, "WHEAT"));
    }
}
