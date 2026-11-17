// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What barracks ("koszary") give a country: army capacity per bunk, a muster point for recruits and garrison healing.
 * Barracks come from Ludnosc's building list (type, Polish function text with the state appended, beds, position).
 * Pure.
 */
public final class BarracksRules {

    private static final Pattern DAMAGED = Pattern.compile("uszkodzony\\s+(\\d+)\\s*%");

    private BarracksRules() {
    }

    /**
     * One barracks building of a country.
     *
     * @param beds    bunks that count towards the army limit (already capped per building)
     * @param working false when the building is out of order (self-built without its key blocks, or badly damaged)
     */
    public record Barracks(String type, String world, int x, int y, int z, int beds, boolean working) {
    }

    /**
     * {@code barracks.*} of config.yml.
     *
     * @param limitPerBed           soldiers added to the country limit per bunk in a working barracks (0 = off)
     * @param limitBonusMax         most soldiers a country can gain from bunks
     * @param maxBedsPerBuilding    bunks counted per building (a self-built hall full of beds is not an army camp)
     * @param maxDamagePercent      a barracks damaged above this stops working (100 = damage never matters)
     * @param recruitSpawn          recruits appear at the nearest working barracks
     * @param recruitFollowDistance barracks within this distance of the recruiter: recruits walk to him; farther: they
     *                              stay at the barracks as its garrison (negative = always follow the recruiter)
     * @param healRadius            garrison healing radius around a working barracks, blocks (0 = off)
     * @param healPerMinute         extra hit points per minute for soldiers resting there (0 = off)
     * @param healIntervalSeconds   how often the healing pass runs
     * @param healParticles         heart particle on a healed soldier
     */
    public record Settings(int limitPerBed, int limitBonusMax, int maxBedsPerBuilding, int maxDamagePercent,
                           boolean recruitSpawn, double recruitFollowDistance, double healRadius, double healPerMinute,
                           int healIntervalSeconds, boolean healParticles) {

        public Settings {
            limitPerBed = Math.max(0, Math.min(10, limitPerBed));
            limitBonusMax = Math.max(0, Math.min(1000, limitBonusMax));
            maxBedsPerBuilding = Math.max(1, Math.min(1000, maxBedsPerBuilding));
            maxDamagePercent = Math.max(0, Math.min(100, maxDamagePercent));
            recruitFollowDistance = Double.isNaN(recruitFollowDistance) ? 40 : Math.min(512, recruitFollowDistance);
            healRadius = Double.isNaN(healRadius) ? 0 : Math.max(0, Math.min(128, healRadius));
            healPerMinute = Double.isNaN(healPerMinute) ? 0 : Math.max(0, Math.min(200, healPerMinute));
            healIntervalSeconds = Math.max(2, Math.min(60, healIntervalSeconds));
        }

        /** Values used when the production config has no {@code barracks} section. */
        public static Settings defaults() {
            return new Settings(1, 60, 12, 50, true, 40, 24, 4.0, 10, true);
        }

        public boolean limitEnabled() {
            return this.limitPerBed > 0 && this.limitBonusMax > 0;
        }

        public boolean healEnabled() {
            return this.healPerMinute > 0 && this.healRadius > 0;
        }

        /** Hit points one healing pass gives. */
        public double healPerPass() {
            return this.healPerMinute * this.healIntervalSeconds / 60.0;
        }
    }

    /**
     * Army limit of a country, split for the player.
     *
     * @param base    limit from land ({@code limits.country-*})
     * @param bonus   limit from bunks
     * @param beds    counted bunks in working barracks
     * @param working working barracks
     * @param idle    barracks that are out of order (they give nothing)
     * @param atMax   the bonus hit {@code barracks.limit-bonus-max}
     */
    public record Limit(int base, int bonus, int beds, int working, int idle, boolean atMax) {

        public int total() {
            return this.base + this.bonus;
        }
    }

    /**
     * Is this building a barracks? By function text ("koszary", "koszary • uszkodzony 12%") or by type id: the
     * configured building, its upgrades ({@code koszary2}) and the self-built kind ({@code wlasny_koszary}).
     */
    public static boolean isBarracks(final String type, final String function, final String building) {
        final String f = function == null ? "" : function.toLowerCase(Locale.ROOT).trim();
        if (f.startsWith("koszary")) {
            return true;
        }
        final String b = building == null || building.isBlank() ? "koszary" : building.toLowerCase(Locale.ROOT).trim();
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT).trim();
        if (t.startsWith("wlasny_")) {
            t = t.substring("wlasny_".length());
        }
        return t.startsWith(b);
    }

    /** Out of order: "nieczynny" (self-built without its key blocks) or damaged above {@code maxDamagePercent}. */
    public static boolean working(final String function, final int maxDamagePercent) {
        final String f = function == null ? "" : function.toLowerCase(Locale.ROOT);
        if (f.contains("nieczynn")) {
            return false;
        }
        final Matcher m = DAMAGED.matcher(f);
        if (m.find() && maxDamagePercent < 100) {
            try {
                return Integer.parseInt(m.group(1)) <= maxDamagePercent;
            } catch (final NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /** Builds the barracks entry of a Ludnosc building, or empty when it is not a barracks. */
    public static Optional<Barracks> classify(final String type, final String function, final int beds, final String world,
                                              final int x, final int y, final int z, final String building, final Settings s) {
        if (world == null || !isBarracks(type, function, building)) {
            return Optional.empty();
        }
        return Optional.of(new Barracks(type == null ? "" : type, world, x, y, z,
            Math.max(0, Math.min(s.maxBedsPerBuilding(), beds)), working(function, s.maxDamagePercent())));
    }

    /** The limit of a country with land limit {@code base} and these barracks. */
    public static Limit limit(final int base, final Settings s, final List<Barracks> barracks) {
        int beds = 0;
        int working = 0;
        int idle = 0;
        for (final Barracks b : barracks) {
            if (b.working()) {
                working++;
                beds += b.beds();
            } else {
                idle++;
            }
        }
        if (!s.limitEnabled()) {
            return new Limit(Math.max(0, base), 0, beds, working, idle, false);
        }
        final long raw = (long) beds * s.limitPerBed();
        final int bonus = (int) Math.min(raw, s.limitBonusMax());
        return new Limit(Math.max(0, base), bonus, beds, working, idle, raw >= s.limitBonusMax());
    }

    /** The global cap ({@code limits.global-max}) is never exceeded by a country limit. */
    public static Limit capped(final Limit l, final int globalMax) {
        if (l.total() <= globalMax) {
            return l;
        }
        final int base = Math.min(l.base(), Math.max(0, globalMax));
        return new Limit(base, Math.max(0, globalMax - base), l.beds(), l.working(), l.idle(), true);
    }

    /**
     * Message for "no more soldiers": the server is full, the old plain text (barracks give no limit or Ludnosc is
     * missing), the country's bonus is at its maximum, or — the usual case — "build barracks".
     */
    public static String limitKey(final Settings s, final Limit l, final boolean ludnosc, final int globalCount, final int globalMax) {
        if (globalCount >= globalMax) {
            return "recruit-limit-global";
        }
        if (!s.limitEnabled() || !ludnosc) {
            return "recruit-limit";
        }
        return l.atMax() ? "recruit-limit-barracks-max" : "recruit-limit-barracks";
    }

    /** Nearest working barracks in {@code world} to a point (horizontal distance). */
    public static Optional<Barracks> nearest(final List<Barracks> barracks, final String world, final double x, final double z) {
        Barracks best = null;
        double bestSq = Double.MAX_VALUE;
        for (final Barracks b : barracks) {
            if (!b.working() || !b.world().equals(world)) {
                continue;
            }
            final double d = distanceSq(b, x, z);
            if (d < bestSq) {
                bestSq = d;
                best = b;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Horizontal distance squared from the barracks' block centre. */
    public static double distanceSq(final Barracks b, final double x, final double z) {
        final double dx = b.x() + 0.5 - x;
        final double dz = b.z() + 0.5 - z;
        return dx * dx + dz * dz;
    }

    /** Recruits of a recruiter this far from the barracks walk to him (otherwise they stay as its garrison). */
    public static boolean recruitsFollow(final Settings s, final double distanceSq) {
        return s.recruitFollowDistance() < 0 || distanceSq <= s.recruitFollowDistance() * s.recruitFollowDistance();
    }

    /** Is a soldier at this position resting at one of its country's working barracks? */
    public static boolean garrisoned(final List<Barracks> barracks, final String world, final double x, final double y,
                                     final double z, final double radius) {
        if (radius <= 0) {
            return false;
        }
        for (final Barracks b : barracks) {
            if (b.working() && b.world().equals(world) && Math.abs(b.y() - y) <= radius
                && distanceSq(b, x, z) <= radius * radius) {
                return true;
            }
        }
        return false;
    }
}
