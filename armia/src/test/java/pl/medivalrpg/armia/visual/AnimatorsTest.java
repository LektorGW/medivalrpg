// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.logic.SiegeRules;

/** Animation choice of ship and siege models (pure state machines, no server). */
class AnimatorsTest {
    private static List<String> started(final List<ModelAction> actions) {
        return actions.stream().filter(ModelAction::start).map(ModelAction::animation).toList();
    }

    private static List<String> stopped(final List<ModelAction> actions) {
        return actions.stream().filter(a -> !a.start()).map(ModelAction::animation).toList();
    }

    @Test void shipStartsMooredWithWavesFlagsFurledSailsAndAnchor() {
        final ShipAnimator a = new ShipAnimator(ShipAnimator.Settings.DEFAULT);
        final var first = a.update(100, 0, 0, 0, ShipAnimator.Steer.NONE);
        assertEquals(List.of(ModelCatalog.WAVES, ModelCatalog.FLAGS, ModelCatalog.SAILS_FURLED, ModelCatalog.ANCHOR_DOWN), started(first));
        assertTrue(a.anchorDown());
        assertFalse(a.sailsSet());
        assertEquals(ModelAction.Kind.LOOP, first.getFirst().kind());
        // still: nothing changes, no packets
        assertTrue(a.update(120, 0, 0, 0, ShipAnimator.Steer.NONE).isEmpty());
        assertTrue(new ShipAnimator(new ShipAnimator.Settings(5, 100, 200, false)).update(1, 0, 0, 0, ShipAnimator.Steer.NONE)
            .stream().noneMatch(x -> x.animation().equals(ModelCatalog.WAVES)), "swell can be disabled");
    }

    @Test void departureRaisesAnchorThenSetsSailsAndFlutters() {
        final ShipAnimator a = new ShipAnimator(ShipAnimator.Settings.DEFAULT);
        a.update(0, 0, 0, 0, ShipAnimator.Steer.NONE);
        final List<ModelAction> all = new ArrayList<>();
        double z = 0;
        for (int t = 1; t <= 10; t++) {
            z += 0.24; // HelmRules.SPEED
            all.addAll(a.update(t, 0, z, 0, ShipAnimator.Steer.NONE));
        }
        final List<String> s = started(all);
        assertTrue(s.indexOf(ModelCatalog.ANCHOR_RAISE) >= 0 && s.indexOf(ModelCatalog.SAILS_SET) > s.indexOf(ModelCatalog.ANCHOR_RAISE));
        assertTrue(s.contains(ModelCatalog.FLUTTER));
        assertTrue(stopped(all).containsAll(List.of(ModelCatalog.ANCHOR_DOWN, ModelCatalog.SAILS_FURLED, ModelCatalog.SAILS_FURL)));
        assertEquals(1, s.stream().filter(ModelCatalog.SAILS_SET::equals).count(), "sails set once, not every tick");
        assertTrue(a.sailsSet() && a.flutter() && !a.anchorDown());
        assertEquals(0.24, a.speed(), 1e-9);
    }

    @Test void sailsWaitForRealMovementAndFurlAfterCalm() {
        final ShipAnimator a = new ShipAnimator(new ShipAnimator.Settings(5, 100, 200, true));
        a.update(0, 0, 0, 0, ShipAnimator.Steer.NONE);
        // a bump (rollback) of one tick does not unfurl
        assertFalse(started(a.update(1, 0, 0.2, 0, ShipAnimator.Steer.NONE)).contains(ModelCatalog.SAILS_SET));
        assertTrue(a.update(2, 0, 0.2, 0, ShipAnimator.Steer.NONE).stream().noneMatch(x -> x.animation().equals(ModelCatalog.SAILS_SET)));
        double z = 0.2;
        for (int t = 3; t <= 12; t++) a.update(t, 0, z += 0.2, 0, ShipAnimator.Steer.NONE);
        assertTrue(a.sailsSet());
        final List<ModelAction> calm = new ArrayList<>();
        for (int t = 13; t <= 400; t += 20) calm.addAll(a.update(t, 0, z, 0, ShipAnimator.Steer.NONE)); // fleet syncs a still ship every 20 ticks
        assertTrue(stopped(calm).contains(ModelCatalog.FLUTTER));
        assertTrue(started(calm).contains(ModelCatalog.SAILS_FURL));
        assertTrue(started(calm).contains(ModelCatalog.ANCHOR_DROP));
        assertTrue(started(calm).indexOf(ModelCatalog.SAILS_FURL) < started(calm).indexOf(ModelCatalog.ANCHOR_DROP));
        assertFalse(a.sailsSet());
        assertTrue(a.anchorDown());
    }

    @Test void turningInPlaceCountsAsUnderWayAndRudderFollowsHelm() {
        final ShipAnimator a = new ShipAnimator(ShipAnimator.Settings.DEFAULT);
        a.update(0, 0, 0, 0, ShipAnimator.Steer.NONE);
        float yaw = 0;
        for (int t = 1; t <= 20; t++) a.update(t, 0, 0, yaw += 2.4f, ShipAnimator.Steer.RIGHT);
        assertFalse(a.anchorDown(), "turning at the helm weighs anchor");
        assertEquals(ShipAnimator.MAX_RUDDER, a.rudder(), 0.05);
        for (int t = 21; t <= 40; t++) a.update(t, 0, 0, yaw -= 2.4f, ShipAnimator.Steer.LEFT);
        assertEquals(-ShipAnimator.MAX_RUDDER, a.rudder(), 0.05);
        for (int t = 41; t <= 60; t++) a.update(t, 0, 0, yaw, ShipAnimator.Steer.NONE);
        assertEquals(0, a.rudder(), 0.01, "rudder centres when the helm is released");
        // autopilot (no captain): derived from the real turn rate, sign of the turn, smoothed
        for (int t = 61; t <= 90; t++) a.update(t, 0, 0, yaw += 1.2f, ShipAnimator.Steer.UNKNOWN);
        assertEquals(ShipAnimator.MAX_RUDDER / 2, a.rudder(), 0.2);
        // wrap-around 179 -> -179 is a small right turn, not a 358 degree left turn
        final ShipAnimator w = new ShipAnimator(ShipAnimator.Settings.DEFAULT);
        w.update(0, 0, 0, 179, ShipAnimator.Steer.UNKNOWN);
        for (int t = 1; t < 10; t++) w.update(t, 0, 0, 179 + 2 * t, ShipAnimator.Steer.UNKNOWN);
        assertTrue(w.rudder() > 0);
        assertEquals(2f, ShipAnimator.wrap(-358), 1e-4);
    }

    @Test void outOfOrderTicksAreIgnored() {
        final ShipAnimator a = new ShipAnimator(ShipAnimator.Settings.DEFAULT);
        a.update(10, 0, 0, 0, ShipAnimator.Steer.NONE);
        assertTrue(a.update(10, 5, 5, 0, ShipAnimator.Steer.NONE).isEmpty());
        assertTrue(a.update(3, 5, 5, 0, ShipAnimator.Steer.NONE).isEmpty());
    }

    @Test void siegeShotPlaysAtOnceAndReloadEndsWithTheLogicCooldown() {
        for (final String model : List.of(ModelCatalog.TREBUSZ, ModelCatalog.KATAPULTA, ModelCatalog.ARMATA, ModelCatalog.TARAN)) {
            final var engine = ModelCatalog.engine(model);
            final SiegeAnimator a = new SiegeAnimator(engine);
            a.update(0, 0, 0);
            final long reload = model.equals(ModelCatalog.ARMATA) ? SiegeRules.engine("armata").reloadTicks() : SiegeRules.engine("katapulta").reloadTicks();
            final var shot = a.shot(50, reload);
            assertEquals(List.of(ModelCatalog.RELOAD, engine.shotAnimation()), stopped(shot), "previous clips cleared first");
            assertEquals(List.of(engine.shotAnimation()), started(shot));
            assertEquals(ModelAction.Kind.HOLD, shot.getLast().kind());
            assertTrue(engine.release() <= 0.15, "release/impact within 3 ticks of the logic shot: " + model);
            final long shotTicks = SiegeAnimator.shotTicks(engine);
            assertTrue(a.update(50 + shotTicks - 1, 0, 0).isEmpty());
            final var reloadActions = a.update(50 + shotTicks, 0, 0);
            assertEquals(List.of(ModelCatalog.RELOAD), started(reloadActions));
            final float speed = reloadActions.stream().filter(ModelAction::start).findFirst().orElseThrow().speed();
            final double ends = 50 + shotTicks + engine.reloadLength() * 20 / speed;
            if (speed > SiegeAnimator.MIN_RELOAD_SPEED && speed < SiegeAnimator.MAX_RELOAD_SPEED)
                assertEquals(50 + reload, ends, 1.0, "reload clip ends when the next shot is allowed: " + model);
            assertFalse(a.shotRunning());
        }
        assertEquals(ModelCatalog.STRIKE, ModelCatalog.engine(ModelCatalog.TARAN).shotAnimation());
    }

    @Test void reloadSpeedIsClamped() {
        final var trebusz = ModelCatalog.engine(ModelCatalog.TREBUSZ);
        assertEquals(SiegeAnimator.MAX_RELOAD_SPEED, SiegeAnimator.reloadSpeed(trebusz, 5));
        assertEquals(SiegeAnimator.MIN_RELOAD_SPEED, SiegeAnimator.reloadSpeed(trebusz, 100_000));
        assertEquals(1.0f, SiegeAnimator.reloadSpeed(trebusz, 20 + 80), 1e-5f);
    }

    @Test void wheelsRollOnlyWhileTheEngineMovesAtGroundSpeed() {
        final var armata = ModelCatalog.engine(ModelCatalog.ARMATA);
        final SiegeAnimator a = new SiegeAnimator(armata);
        assertTrue(a.update(0, 0, 0).isEmpty());
        final var go = a.update(3, 0, 0.6);
        assertEquals(List.of(ModelCatalog.ROLL), started(go));
        assertEquals(ModelAction.LIVE_SPEED, go.getFirst().speed());
        assertEquals(0.2 * 20 / (2 * Math.PI * armata.wheelRadius()), a.rollSpeed(), 1e-5);
        assertTrue(a.update(6, 0, 1.2).isEmpty(), "keeps rolling without restarting the clip");
        assertEquals(List.of(ModelCatalog.ROLL), stopped(a.update(9, 0, 1.2)));
        assertFalse(a.rolling());
        assertThrows(IllegalArgumentException.class, () -> new SiegeAnimator(null));
    }

    @Test void priorityKeepsFlutterAndWheelsAboveParentClips() {
        assertTrue(ModelAction.priority(ModelCatalog.WAVES) < ModelAction.priority(ModelCatalog.SAILS_SET));
        assertTrue(ModelAction.priority(ModelCatalog.SAILS_SET) < ModelAction.priority(ModelCatalog.FLUTTER));
        assertTrue(ModelAction.priority(ModelCatalog.SHOT) < ModelAction.priority(ModelCatalog.ROLL));
        assertTrue(ModelAction.priority(ModelCatalog.RELOAD) < ModelAction.priority(ModelCatalog.ROLL));
    }

    @Test void catalogMapsUnitsAndShipsStably() {
        assertEquals(ModelCatalog.KOGA, ModelCatalog.shipModel("transportowiec"));
        assertEquals(ModelCatalog.KARAWELA, ModelCatalog.shipModel("karawela"));
        assertNull(ModelCatalog.shipModel("galeon"));
        assertEquals(List.of(ModelCatalog.ARMATA), ModelCatalog.defaultEngineModels("artyleria"));
        assertTrue(ModelCatalog.defaultEngineModels("piechota").isEmpty());
        final var variants = ModelCatalog.defaultEngineModels("katapulta");
        int trebuchets = 0;
        for (int i = 0; i < 400; i++) {
            final UUID id = new UUID(i * 0x9E3779B97F4A7C15L, ~i * 31L);
            final String pick = ModelCatalog.pick(variants, id);
            assertEquals(pick, ModelCatalog.pick(variants, id), "same soldier, same look");
            if (pick.equals(ModelCatalog.TREBUSZ)) trebuchets++;
        }
        assertTrue(trebuchets > 120 && trebuchets < 280, "both variants appear: " + trebuchets);
        assertNull(ModelCatalog.pick(List.of(), UUID.randomUUID()));
        assertTrue(ModelCatalog.tinted("bandera_koniec") && ModelCatalog.tinted("herb_gl") && !ModelCatalog.tinted("kadlub"));
    }
}
