// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import pl.medivalrpg.armia.api.NpcPosture;

/**
 * Should an NPC country start a naval landing (wave 13 bridgehead + wave 16 fleet)? Pure decision and autopilot math.
 *
 * <p>A landing is the answer to an enemy that cannot be reached by land: there must be no land front with that
 * enemy, an own coast and an enemy coast within {@code maxRange}, enough idle soldiers and money for the ship (unless
 * one is free), the landing surcharge and the siege. Defensive countries and countries in crisis only sail to take
 * back their own lost land; otherwise the usual aggression/advantage gates apply. One operation per country, a
 * cooldown between them and a global cap keep NPC countries from spamming landings.</p>
 */
public final class NavalPlanner {

    public record Settings(boolean enabled, int minSoldiers, int maxSoldiers, int maxRange, long cooldownMinutes,
                           long timeoutMinutes, int minAggression, double minAdvantage, int maxOperations) {
        public static Settings defaults() {
            return new Settings(true, 4, 12, 256, 90, 15, 40, 0.9, 3);
        }
    }

    public enum Reason {
        OK, DISABLED, NOT_AT_WAR, BUSY, GLOBAL_LIMIT, COOLDOWN, LAND_FRONT, DEFENSIVE, CRISIS, PASSIVE, WEAK,
        NO_COAST, NO_TARGET, TOO_FAR, TOO_CLOSE, FEW_SOLDIERS, NO_SHIP, NO_MONEY, NPC_IN_BACKGROUND
    }

    /**
     * @param landFront       we share a land front with this enemy (a normal siege is possible)
     * @param reclaim         the target chunk is our own lost land (always worth sailing for)
     * @param distance        blocks from our launch water to the enemy shore, NaN = unknown
     * @param shipFree        we already have an idle ship in range (no build cost)
     * @param canBuildShip    era and fleet limits allow a new ship
     * @param spendable       treasury minus the wage reserve
     */
    public record Input(boolean atWar, boolean busy, int runningOperations, long minutesSinceLast, boolean landFront,
                        boolean reclaim, NpcPosture posture, boolean crisis, int aggression, double strengthRatio,
                        boolean ownCoast, boolean targetCoast, double distance, int idleSoldiers, boolean shipFree,
                        boolean canBuildShip, long shipCost, long landingCost, long spendable) {
    }

    private NavalPlanner() {
    }

    public static Reason decide(final Settings s, final Input in) {
        if (!s.enabled()) {
            return Reason.DISABLED;
        }
        if (!in.atWar()) {
            return Reason.NOT_AT_WAR;
        }
        if (in.busy()) {
            return Reason.BUSY;
        }
        if (in.runningOperations() >= s.maxOperations()) {
            return Reason.GLOBAL_LIMIT;
        }
        if (in.minutesSinceLast() < s.cooldownMinutes()) {
            return Reason.COOLDOWN;
        }
        if (in.landFront() && !in.reclaim()) {
            return Reason.LAND_FRONT;
        }
        if (!in.reclaim()) {
            if (in.posture() == NpcPosture.DEFENSIVE) {
                return Reason.DEFENSIVE;
            }
            if (in.crisis()) {
                return Reason.CRISIS;
            }
            if (in.aggression() < s.minAggression()) {
                return Reason.PASSIVE;
            }
            if (!(in.strengthRatio() >= s.minAdvantage())) {
                return Reason.WEAK;
            }
        }
        if (!in.ownCoast()) {
            return Reason.NO_COAST;
        }
        if (!in.targetCoast()) {
            return Reason.NO_TARGET;
        }
        if (!Double.isFinite(in.distance()) || in.distance() > s.maxRange()) {
            return Reason.TOO_FAR;
        }
        if (in.distance() < 24) {
            return Reason.TOO_CLOSE; // Kraje's landing proof needs at least 24 sailed blocks
        }
        if (in.idleSoldiers() < s.minSoldiers()) {
            return Reason.FEW_SOLDIERS;
        }
        if (!in.shipFree() && !in.canBuildShip()) {
            return Reason.NO_SHIP;
        }
        final long need = (in.shipFree() ? 0 : Math.max(0, in.shipCost())) + Math.max(0, in.landingCost());
        if (in.spendable() < need) {
            return Reason.NO_MONEY;
        }
        return Reason.OK;
    }

    // ------------------------------------------------------------------ long-range expeditions (2026-10-31)

    /**
     * Long-range landing (ai.naval.long-range): beyond real sailing range the voyage is computed in the background.
     *
     * @param minutesPer1000  voyage minutes per 1000 blocks (capital → target)
     * @param minMinutes      shortest voyage
     * @param maxMinutes      longest voyage (cap)
     * @param waitMinutes     how long the fleet waits off the enemy coast for players before sailing home
     * @param maxSoldiers     soldiers per expedition (also limited by the ship's capacity)
     * @param homeGuardShare  share of the army that always stays at home
     * @param vsNpc           the AI also sails on its own against NPC enemies (they are normally fought in the background)
     * @param approachMinutes defenders get a second warning this many minutes before arrival
     */
    public record LongRange(boolean enabled, double minutesPer1000, int minMinutes, int maxMinutes, int waitMinutes,
                            int maxSoldiers, double homeGuardShare, boolean vsNpc, int approachMinutes) {
        public static LongRange defaults() {
            return new LongRange(true, 6.0, 3, 30, 240, 16, 0.35, false, 3);
        }
    }

    /**
     * Inputs of a long-range decision (no chunk is loaded to compute them).
     *
     * @param admin          ordered by an administrator: personality, posture and cooldown gates are skipped
     * @param enemyNpc       the enemy is an NPC country
     * @param freeSoldiers   soldiers in the registry free for an expedition (after the home guard)
     * @param shipSlot       the fleet limits allow one more (expedition) ship
     * @param enemyLand      the enemy has siegeable land in our world
     */
    public record LongInput(boolean admin, boolean atWar, boolean busy, boolean siegeRunning, int runningOperations,
                            long minutesSinceLast, boolean landFront, boolean reclaim, NpcPosture posture, boolean crisis,
                            int aggression, double strengthRatio, boolean enemyNpc, boolean enemyLand, int freeSoldiers,
                            boolean shipSlot, long shipCost, long landingCost, long spendable) {
    }

    /** Should a long-range expedition start? Same gates as a normal landing, without the physical coast/range checks. */
    public static Reason decideLong(final Settings s, final LongRange l, final LongInput in) {
        if (!s.enabled() || !l.enabled()) {
            return Reason.DISABLED;
        }
        if (!in.atWar()) {
            return Reason.NOT_AT_WAR;
        }
        if (in.busy() || in.siegeRunning()) {
            return Reason.BUSY;
        }
        if (in.runningOperations() >= s.maxOperations()) {
            return Reason.GLOBAL_LIMIT;
        }
        if (!in.admin()) {
            if (in.minutesSinceLast() < s.cooldownMinutes()) {
                return Reason.COOLDOWN;
            }
            if (in.landFront() && !in.reclaim()) {
                return Reason.LAND_FRONT;
            }
            if (in.enemyNpc() && !l.vsNpc() && !in.reclaim()) {
                return Reason.NPC_IN_BACKGROUND;
            }
            if (!in.reclaim()) {
                if (in.posture() == NpcPosture.DEFENSIVE) {
                    return Reason.DEFENSIVE;
                }
                if (in.crisis()) {
                    return Reason.CRISIS;
                }
                if (in.aggression() < s.minAggression()) {
                    return Reason.PASSIVE;
                }
                if (!(in.strengthRatio() >= s.minAdvantage())) {
                    return Reason.WEAK;
                }
            }
        }
        if (!in.enemyLand()) {
            return Reason.NO_TARGET;
        }
        if (in.freeSoldiers() < s.minSoldiers()) {
            return Reason.FEW_SOLDIERS;
        }
        if (!in.shipSlot()) {
            return Reason.NO_SHIP;
        }
        if (in.spendable() < Math.max(0, in.shipCost()) + Math.max(0, in.landingCost())) {
            return Reason.NO_MONEY;
        }
        return Reason.OK;
    }

    /** Voyage time in minutes for {@code blocks} of distance: proportional, between min and max. */
    public static long voyageMinutes(final LongRange l, final double blocks) {
        final double raw = Math.max(0, Double.isFinite(blocks) ? blocks : 0) / 1000.0 * Math.max(0, l.minutesPer1000());
        final long m = (long) Math.ceil(raw);
        return Math.max(l.minMinutes(), Math.min(l.maxMinutes(), m));
    }

    /** Soldiers that may leave: the free ones minus the home guard share of the whole army. */
    public static int expeditionPool(final LongRange l, final int army, final int free) {
        final int stay = (int) Math.ceil(Math.max(0, army) * Math.max(0, Math.min(1, l.homeGuardShare())));
        return Math.max(0, Math.min(free, army - stay));
    }

    /** Expedition squad: the pool up to the configured maximum and the ship's capacity. */
    public static int longSquad(final LongRange l, final int pool, final int capacity) {
        return Math.max(0, Math.min(Math.min(pool, capacity), l.maxSoldiers()));
    }

    /** Polish compass direction of the sea approach seen from the defender ({@code dx},{@code dz}: attacker − defender). */
    public static String direction(final double dx, final double dz) {
        if (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9) {
            return "z morza";
        }
        final double deg = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360; // 0 = north (−z), 90 = east (+x)
        final String[] names = {"z północy", "z północnego wschodu", "ze wschodu", "z południowego wschodu", "z południa",
            "z południowego zachodu", "z zachodu", "z północnego zachodu"};
        return names[(int) Math.round(deg / 45.0) % 8];
    }

    /** Ship to build: a caravel in the renaissance when it is affordable with a margin, else a transport. */
    public static String shipType(final int era, final long spendable, final long caravelCost, final long landingCost) {
        return era >= 2 && spendable >= 2 * (caravelCost + landingCost) ? "karawela" : "transportowiec";
    }

    /** Soldiers to embark: all idle up to the ship's capacity and the configured maximum. */
    public static int squad(final Settings s, final int idle, final int capacity) {
        return Math.max(0, Math.min(Math.min(idle, capacity), s.maxSoldiers()));
    }

    /** Polish text of a refusal reason (decision log). */
    public static String polish(final Reason r) {
        return switch (r) {
            case OK -> "desant możliwy";
            case DISABLED -> "desanty AI wyłączone";
            case NOT_AT_WAR -> "brak wojny";
            case BUSY -> "trwa inna operacja";
            case GLOBAL_LIMIT -> "limit jednoczesnych desantów na serwerze";
            case COOLDOWN -> "przerwa między desantami";
            case LAND_FRONT -> "wróg jest osiągalny lądem";
            case DEFENSIVE -> "postawa obronna";
            case CRISIS -> "tryb kryzysowy — obrona ojczyzny";
            case PASSIVE -> "zbyt pokojowy władca";
            case WEAK -> "za mała przewaga sił";
            case NO_COAST -> "brak własnego wybrzeża w zasięgu";
            case NO_TARGET -> "brak nadbrzeżnego celu przy graczach";
            case TOO_FAR -> "cel za daleko";
            case TOO_CLOSE -> "cel za blisko na rejs";
            case FEW_SOLDIERS -> "za mało wolnych żołnierzy";
            case NO_SHIP -> "limit floty albo epoka";
            case NO_MONEY -> "brak pieniędzy na statek i desant";
            case NPC_IN_BACKGROUND -> "wojnę z krajem NPC prowadzi się w tle";
        };
    }

    // ------------------------------------------------------------------ autopilot

    /** Minecraft yaw (degrees, −180..180) looking from (x,z) towards (tx,tz): motion x = −sin(yaw), z = cos(yaw). */
    public static float headingTo(final double x, final double z, final double tx, final double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
    }

    /** Signed shortest turn from {@code yaw} to {@code target}, −180..180. */
    public static float turn(final float yaw, final float target) {
        float d = (target - yaw) % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /** Helm keys for this tick: {left, right, forward}. Slow down (no forward) while turning more than 60°. */
    public static boolean[] helm(final float yaw, final double x, final double z, final double tx, final double tz, final float detour) {
        final float want = headingTo(x, z, tx, tz) + detour;
        final float d = turn(yaw, want);
        final boolean right = d > 3f;
        final boolean left = d < -3f;
        return new boolean[] {left, right, Math.abs(d) <= 60f};
    }

    /** Arrived when within {@code radius} blocks of the waypoint. */
    public static boolean arrived(final double x, final double z, final double tx, final double tz, final double radius) {
        final double dx = tx - x;
        final double dz = tz - z;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** Detour when blocked: alternate ±50°, ±90° by attempt. */
    public static float detour(final int attempt) {
        final float[] d = {50f, -50f, 90f, -90f};
        return attempt <= 0 ? 0f : d[(attempt - 1) % d.length];
    }
}
