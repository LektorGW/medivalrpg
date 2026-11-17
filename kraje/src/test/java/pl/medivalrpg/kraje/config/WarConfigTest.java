// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Keys of the spoils / disband / vassal features. */
class WarConfigTest {

    @Test
    void landingCostCannotOverflowAndLimitsAreClampedBeforeIntegerConversion() {
        final YamlConfiguration config=new YamlConfiguration();
        config.set("siege.cost",Long.MAX_VALUE-50);
        config.set("siege.landing.extra-cost",Long.MAX_VALUE);
        config.set("siege.landing.max-bridgeheads",Long.MAX_VALUE);
        final KrajeConfig cfg=new KrajeConfig(config);
        assertEquals(50,cfg.landingExtraCost);
        assertEquals(Long.MAX_VALUE,Math.addExact(cfg.siegeCost,cfg.landingExtraCost));
        assertEquals(16,cfg.landingMaxBridgeheads);
    }

    private static YamlConfiguration jar() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            WarConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void defaults() {
        final KrajeConfig cfg = new KrajeConfig(jar());
        assertTrue(cfg.landingEnabled);
        assertEquals(1000,cfg.landingExtraCost);
        assertEquals(3,cfg.landingMaxBridgeheads);
        assertTrue(cfg.protection.playerTnt);
        assertFalse(cfg.protection.explosions);
        assertEquals(0.05, cfg.lootPerChunk);
        assertEquals(0.75, cfg.lootOnFall);
        assertEquals(KrajeConfig.FallRemainder.DESTROY, cfg.fallRemainder);
        assertEquals(KrajeConfig.DisbandTreasury.RULER, cfg.disbandTreasury);
        assertTrue(cfg.conquestInvites);
        assertEquals(24 * 3_600_000L, cfg.conquestInviteMillis);
        assertTrue(cfg.vassalEnabled);
        assertEquals(0.25, cfg.tributeShare);
        assertEquals(20, cfg.tributeMin);
        assertEquals(7, cfg.vassalDefaultDays);
        assertEquals(1, cfg.vassalMinDays);
        assertEquals(30, cfg.vassalMaxDays);
        assertEquals(1.5, cfg.buyoutMultiplier);
        assertTrue(cfg.vassalRules.forbidWarOnOverlord());
        assertTrue(cfg.vassalRules.forbidWarOnOverlordAllies());
        assertTrue(cfg.vassalRules.forbidAllianceWithOverlordEnemies());
        assertTrue(cfg.vassalRules.overlordWarOnlyForDebt());
        assertEquals(3, cfg.vassalRules.debtWarDays());
        assertTrue(cfg.vassalNotifyWars);
        assertFalse(cfg.vassalAutoJoinWars, "auto-join is off by default");
    }

    @Test
    void oldServerConfigGetsJarDefaultsAndBadValuesFallBack() {
        final YamlConfiguration server = new YamlConfiguration();
        server.set("conquest.fall-remainder", "Members");
        server.set("disband.treasury", "nonsense");
        server.set("conquest.loot-per-chunk", 7.0);
        server.set("vassal.default-days", 99);
        server.setDefaults(jar());
        final KrajeConfig cfg = new KrajeConfig(server);
        assertTrue(cfg.landingEnabled);
        assertEquals(1000,cfg.landingExtraCost);
        assertEquals(3,cfg.landingMaxBridgeheads);
        assertEquals(KrajeConfig.FallRemainder.MEMBERS, cfg.fallRemainder, "case-insensitive");
        assertEquals(KrajeConfig.DisbandTreasury.RULER, cfg.disbandTreasury, "unknown value: default");
        assertEquals(1.0, cfg.lootPerChunk, "clamped to 100 %");
        assertEquals(30, cfg.vassalDefaultDays, "clamped to max-days");
        assertEquals(0.75, cfg.lootOnFall, "missing key from the jar's config");
    }
}
