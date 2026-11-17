// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.RelationQuality;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;

/** Defaults of union.* / embassy.*, fallback for old server configs and every new message key. */
class DiplomacyConfigTest {

    private static YamlConfiguration jar() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            DiplomacyConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void defaults() {
        final KrajeConfig cfg = new KrajeConfig(jar());
        assertTrue(cfg.unionRules.enabled());
        assertTrue(cfg.unionRules.allowNpc());
        assertFalse(cfg.unionNpcAutoRefuse, "decision 2026-10-29: the NPC AI (Armia) answers union proposals");
        assertEquals(72 * 3_600_000L, cfg.unionRules.minDurationMillis());
        assertEquals(48 * 3_600_000L, cfg.unionRules.reformCooldownMillis());
        assertTrue(cfg.unionRules.defendPartner());
        assertFalse(cfg.unionRules.joinOffensiveWars());
        assertEquals(2000, cfg.unionFormCost);
        assertEquals(1000, cfg.unionBreakCost);
        assertTrue(cfg.unionKeepAlliance);
        assertTrue(cfg.unionRegentCanSecede);

        assertTrue(cfg.embassyRules.enabled());
        assertEquals(Set.of(Relation.NEUTRAL, Relation.ALLIANCE), cfg.embassyRules.allowedRelations());
        assertEquals(Set.of(Relation.ALLIANCE), cfg.embassyRules.npcAcceptRelations());
        assertEquals(3, cfg.embassyRules.maxPerCountry());
        assertEquals(5, cfg.embassyRules.maxHosted());
        assertFalse(cfg.embassyRules.allowCapital());
        assertEquals(24 * 3_600_000L, cfg.embassyRules.expelNoticeMillis());
        assertEquals(0.05, cfg.embassyRules.tradeBonus());
        assertEquals(0.10, cfg.embassyRules.unionTradeBonus());
        assertEquals(RelationQuality.NEUTRAL, cfg.embassyRules.jointMinQuality());
        assertFalse(cfg.embassyRules.jointNeedsOwnEmbassy());
        assertEquals(300, cfg.embassyOpenCost);
        assertEquals(Rank.RULER, cfg.embassyMinRank);
        assertEquals(Rank.CITIZEN, cfg.embassyTpMinRank);
        assertFalse(cfg.embassyNpcAutoDecide, "decision 2026-10-29: the NPC AI (Armia) answers embassy requests");
        assertTrue(cfg.mapPopupUnion.contains("{union}"));
        assertTrue(cfg.mapPopupEmbassies.contains("{embassies}"));
    }

    @Test
    void oldServerConfigFallsBackToJarDefaults() {
        final YamlConfiguration server = new YamlConfiguration();
        server.set("embassy.allowed-relations", List.of("WAR", "bzdura"));
        server.setDefaults(jar());
        final KrajeConfig cfg = new KrajeConfig(server);
        // WAR is never allowed; nothing valid left → built-in default
        assertEquals(Set.of(Relation.NEUTRAL, Relation.ALLIANCE), cfg.embassyRules.allowedRelations());
        assertEquals(2000, cfg.unionFormCost);
        assertEquals(Set.of(Relation.ALLIANCE), KrajeConfig.relations(List.of("alliance", "war"), Set.of()));
    }

    @Test
    void everyNewMessageExists() {
        final Messages m = new Messages(jar(), Logger.getAnonymousLogger());
        for (final String key : List.of("usage-unia", "usage-ambasada", "union-proposal-received", "union-formed-broadcast",
            "union-dissolved-broken", "union-dissolved-seceded", "union-dissolved-ruler", "union-dissolved-war",
            "union-dissolved-vassal", "union-dissolved-gone", "union-dissolved-admin", "union-partner-defends",
            "union-partner-attacks", "union-info", "union-acting-partner", "union-acting-home", "union-last-member",
            "union-war-partner", "union-break-alliance", "union-too-early", "union-cooldown", "union-vassal",
            "embassy-request-received", "embassy-opened-guest", "embassy-opened-host", "embassy-expel-notice",
            "embassy-closed-closed", "embassy-closed-expelled", "embassy-closed-war", "embassy-closed-land",
            "embassy-closed-gone", "embassy-closed-admin", "embassy-list-entry", "embassy-teleported", "unclaim-embassy",
            "info-union", "info-embassies-abroad", "info-embassies-hosted", "border-relation-union",
            "treasury-op.union", "treasury-op.embassy")) {
            assertFalse(m.raw(key).equals(key), key);
            m.get(key); // parses as MiniMessage
        }
    }
}
