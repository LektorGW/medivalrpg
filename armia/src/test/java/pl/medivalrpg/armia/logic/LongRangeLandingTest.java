// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.api.NpcPosture;
import pl.medivalrpg.armia.model.NpcState;

/** Long-range landings (ai.naval.long-range, 2026-10-31): decision gates, voyage time, squad size, persistence. */
class LongRangeLandingTest {

    private static final NavalPlanner.Settings S = NavalPlanner.Settings.defaults();
    private static final NavalPlanner.LongRange L = NavalPlanner.LongRange.defaults();

    /** Lithuania vs Vadstena on 31.10: at war, no land front, player enemy, huge advantage, 113 soldiers, money ok. */
    private static NavalPlanner.LongInput lithuania() {
        return new NavalPlanner.LongInput(false, true, false, false, 0, 10_000, false, false, NpcPosture.OFFENSIVE, false,
            60, 86.7 / 0.6, false, true, 60, true, 3000, 1500, 200_000);
    }

    private static NavalPlanner.LongInput with(final java.util.function.UnaryOperator<Object[]> f) {
        final NavalPlanner.LongInput b = lithuania();
        final Object[] v = {b.admin(), b.atWar(), b.busy(), b.siegeRunning(), b.runningOperations(), b.minutesSinceLast(), b.landFront(),
            b.reclaim(), b.posture(), b.crisis(), b.aggression(), b.strengthRatio(), b.enemyNpc(), b.enemyLand(), b.freeSoldiers(),
            b.shipSlot(), b.shipCost(), b.landingCost(), b.spendable()};
        final Object[] n = f.apply(v);
        return new NavalPlanner.LongInput((boolean) n[0], (boolean) n[1], (boolean) n[2], (boolean) n[3], (int) n[4], (long) n[5],
            (boolean) n[6], (boolean) n[7], (NpcPosture) n[8], (boolean) n[9], (int) n[10], (double) n[11], (boolean) n[12],
            (boolean) n[13], (int) n[14], (boolean) n[15], (long) n[16], (long) n[17], (long) n[18]);
    }

    private static NavalPlanner.LongInput set(final int index, final Object value) {
        return with(v -> {
            v[index] = value;
            return v;
        });
    }

    @Test
    void lithuaniaSailsOnVadstena() {
        assertEquals(NavalPlanner.Reason.OK, NavalPlanner.decideLong(S, L, lithuania()));
    }

    @Test
    void gatesInOrder() {
        assertEquals(NavalPlanner.Reason.NOT_AT_WAR, NavalPlanner.decideLong(S, L, set(1, false)));
        assertEquals(NavalPlanner.Reason.BUSY, NavalPlanner.decideLong(S, L, set(2, true)));
        assertEquals(NavalPlanner.Reason.BUSY, NavalPlanner.decideLong(S, L, set(3, true)), "a running siege blocks a second front");
        assertEquals(NavalPlanner.Reason.GLOBAL_LIMIT, NavalPlanner.decideLong(S, L, set(4, S.maxOperations())));
        assertEquals(NavalPlanner.Reason.COOLDOWN, NavalPlanner.decideLong(S, L, set(5, 10L)));
        assertEquals(NavalPlanner.Reason.LAND_FRONT, NavalPlanner.decideLong(S, L, set(6, true)));
        assertEquals(NavalPlanner.Reason.DEFENSIVE, NavalPlanner.decideLong(S, L, set(8, NpcPosture.DEFENSIVE)));
        assertEquals(NavalPlanner.Reason.CRISIS, NavalPlanner.decideLong(S, L, set(9, true)));
        assertEquals(NavalPlanner.Reason.PASSIVE, NavalPlanner.decideLong(S, L, set(10, 10)));
        assertEquals(NavalPlanner.Reason.WEAK, NavalPlanner.decideLong(S, L, set(11, 0.5)));
        assertEquals(NavalPlanner.Reason.WEAK, NavalPlanner.decideLong(S, L, set(11, Double.NaN)));
        assertEquals(NavalPlanner.Reason.NPC_IN_BACKGROUND, NavalPlanner.decideLong(S, L, set(12, true)));
        assertEquals(NavalPlanner.Reason.NO_TARGET, NavalPlanner.decideLong(S, L, set(13, false)));
        assertEquals(NavalPlanner.Reason.FEW_SOLDIERS, NavalPlanner.decideLong(S, L, set(14, 3)));
        assertEquals(NavalPlanner.Reason.NO_SHIP, NavalPlanner.decideLong(S, L, set(15, false)));
        assertEquals(NavalPlanner.Reason.NO_MONEY, NavalPlanner.decideLong(S, L, set(18, 4499L)));
        assertEquals(NavalPlanner.Reason.OK, NavalPlanner.decideLong(S, L, set(18, 4500L)));
    }

    @Test
    void reclaimIgnoresPersonalityAndNpcRule() {
        final NavalPlanner.LongInput in = with(v -> {
            v[7] = true; // reclaim
            v[8] = NpcPosture.DEFENSIVE;
            v[10] = 0;
            v[11] = 0.1;
            v[12] = true;
            v[6] = true; // even with a land front (the lost chunk is cut off)
            return v;
        });
        assertEquals(NavalPlanner.Reason.OK, NavalPlanner.decideLong(S, L, in));
    }

    @Test
    void adminSkipsPersonalityButNotRules() {
        final NavalPlanner.LongInput weak = with(v -> {
            v[0] = true;
            v[5] = 0L; // cooldown ignored
            v[8] = NpcPosture.DEFENSIVE;
            v[10] = 0;
            v[11] = 0.01;
            v[12] = true; // NPC enemy allowed
            v[6] = true; // land front allowed
            return v;
        });
        assertEquals(NavalPlanner.Reason.OK, NavalPlanner.decideLong(S, L, weak));
        assertEquals(NavalPlanner.Reason.NOT_AT_WAR, NavalPlanner.decideLong(S, L, with(v -> {
            v[0] = true;
            v[1] = false;
            return v;
        })));
        assertEquals(NavalPlanner.Reason.NO_MONEY, NavalPlanner.decideLong(S, L, with(v -> {
            v[0] = true;
            v[18] = 0L;
            return v;
        })), "the admin cannot create money");
        assertEquals(NavalPlanner.Reason.FEW_SOLDIERS, NavalPlanner.decideLong(S, L, with(v -> {
            v[0] = true;
            v[14] = 0;
            return v;
        })));
        assertEquals(NavalPlanner.Reason.NO_SHIP, NavalPlanner.decideLong(S, L, with(v -> {
            v[0] = true;
            v[15] = false;
            return v;
        })));
    }

    @Test
    void disabledSwitches() {
        final NavalPlanner.LongRange off = new NavalPlanner.LongRange(false, 6, 3, 30, 240, 16, 0.35, false, 3);
        assertEquals(NavalPlanner.Reason.DISABLED, NavalPlanner.decideLong(S, off, lithuania()));
        final NavalPlanner.Settings navalOff = new NavalPlanner.Settings(false, 4, 12, 256, 90, 15, 40, 0.9, 3);
        assertEquals(NavalPlanner.Reason.DISABLED, NavalPlanner.decideLong(navalOff, L, lithuania()));
        final NavalPlanner.LongRange npc = new NavalPlanner.LongRange(true, 6, 3, 30, 240, 16, 0.35, true, 3);
        assertEquals(NavalPlanner.Reason.OK, NavalPlanner.decideLong(S, npc, set(12, true)));
    }

    @Test
    void voyageTimeIsProportionalAndCapped() {
        // Lithuania (112,-100) → Vadstena (-24,78): ~3.5k blocks → 22 minutes at 6 min / 1000 blocks.
        final double d = Math.hypot((112 - (-24)) * 16.0, (-100 - 78) * 16.0);
        assertEquals(3584, Math.round(d), 1);
        assertEquals(22, NavalPlanner.voyageMinutes(L, d));
        assertEquals(3, NavalPlanner.voyageMinutes(L, 100), "minimum");
        assertEquals(30, NavalPlanner.voyageMinutes(L, 50_000), "cap");
        assertEquals(3, NavalPlanner.voyageMinutes(L, Double.NaN));
        assertEquals(3, NavalPlanner.voyageMinutes(L, -5));
    }

    @Test
    void homeGuardAndSquad() {
        assertEquals(73, NavalPlanner.expeditionPool(L, 113, 113), "35% of 113 stays home");
        assertEquals(20, NavalPlanner.expeditionPool(L, 113, 20), "only free soldiers may go");
        assertEquals(0, NavalPlanner.expeditionPool(L, 0, 0));
        assertEquals(16, NavalPlanner.longSquad(L, 73, 16));
        assertEquals(16, NavalPlanner.longSquad(L, 73, 32), "configured maximum");
        assertEquals(5, NavalPlanner.longSquad(L, 5, 16));
        assertEquals(12, NavalPlanner.longSquad(L, 73, 12), "ship capacity");
    }

    @Test
    void approachDirectionFromDefender() {
        // Lithuania lies north-east of Vadstena (x larger, z smaller).
        assertEquals("z północnego wschodu", NavalPlanner.direction((112 + 24) * 16.0, (-100 - 78) * 16.0));
        assertEquals("z północy", NavalPlanner.direction(0, -100));
        assertEquals("z południa", NavalPlanner.direction(0, 100));
        assertEquals("ze wschodu", NavalPlanner.direction(100, 0));
        assertEquals("z zachodu", NavalPlanner.direction(-100, 0));
        assertEquals("z morza", NavalPlanner.direction(0, 0));
    }

    @Test
    void polishReasonsAreComplete() {
        for (final NavalPlanner.Reason r : NavalPlanner.Reason.values()) {
            assertNotEquals("", NavalPlanner.polish(r));
        }
    }

    @Test
    void expeditionSurvivesSaveAndLoadAndOldSavesLoad() {
        final com.google.gson.Gson gson = new com.google.gson.GsonBuilder().create();
        final NpcState st = new NpcState();
        st.country = java.util.UUID.randomUUID();
        st.naval = new NpcState.NavalOp();
        st.naval.longRange = true;
        st.naval.id = "abc";
        st.naval.phase = "VOYAGE";
        st.naval.arriveAt = 123;
        final java.util.UUID soldier = java.util.UUID.randomUUID();
        st.naval.soldiers.add(soldier);
        st.naval.homes.put(soldier, new pl.medivalrpg.armia.model.Pos("world", 1.5, 64, -2.5, 0f));
        final NpcState back = gson.fromJson(gson.toJson(st), NpcState.class);
        back.normalize();
        assertTrue(back.naval.longRange);
        assertEquals("desant:abc", back.naval.campaign());
        assertEquals(123, back.naval.arriveAt);
        assertEquals(1.5, back.naval.homes.get(soldier).x());
        // An older save (short-range op without the new fields) loads with empty collections.
        final NpcState old = gson.fromJson("{\"country\":\"" + st.country + "\",\"naval\":{\"phase\":\"EMBARK\",\"soldiers\":[]}}", NpcState.class);
        old.normalize();
        assertTrue(!old.naval.longRange && old.naval.landed.isEmpty() && old.naval.homes.isEmpty());
    }

    @Test
    void configDefaultsAndClamps() {
        assertEquals(L, pl.medivalrpg.armia.config.ArmiaConfig.longRange(null));
        final YamlConfiguration y = new YamlConfiguration();
        y.set("r.max-minutes", 1);
        y.set("r.min-minutes", 5);
        y.set("r.home-guard-share", 5.0);
        final NavalPlanner.LongRange c = pl.medivalrpg.armia.config.ArmiaConfig.longRange(y.getConfigurationSection("r"));
        assertTrue(c.enabled());
        assertEquals(5, c.minMinutes());
        assertEquals(5, c.maxMinutes(), "max never below min");
        assertEquals(0.9, c.homeGuardShare());
        assertEquals(240, c.waitMinutes());
    }

    @Test
    void shippedConfigHasSectionMessagesAndHelp() throws java.io.IOException {
        final YamlConfiguration y;
        try (var in = LongRangeLandingTest.class.getResourceAsStream("/config.yml")) {
            y = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(L, pl.medivalrpg.armia.config.ArmiaConfig.longRange(y.getConfigurationSection("ai.naval.long-range")),
            "shipped values = code defaults (production config has no ai.naval section)");
        for (final String k : new String[] {"naval-expedition-departed", "naval-expedition-approach", "naval-expedition-offshore",
            "naval-expedition-retreat", "nk-desant-started", "nk-desant-refused", "nk-desant-no-target", "nk-desant-result",
            "help-nk.entries.desant.short", "help-nk.entries.desant.description"}) {
            assertTrue(y.isString("messages." + k), k);
        }
        for (final String k : new String[] {"naval-expedition-departed", "naval-expedition-approach"}) {
            assertTrue(y.getString("messages." + k).contains("<eta>"), k);
        }
        assertTrue(y.getString("messages.naval-expedition-departed").contains("<from>"));
    }
}
