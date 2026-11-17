// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Families of citizens (pure, unit tested): households, marriages, births and growing up.
 *
 * <p>A <b>household</b> is a married couple with their unmarried children (adult children stay until they marry).
 * Its key is the smaller id of the couple; a single parent's household key is the parent's own id. Households live
 * together ({@link Housing}).</p>
 */
public final class Family {

    private Family() {
    }

    /** Family links of one living citizen. */
    public record Member(UUID id, String name, boolean adult, UUID partner, UUID mother, UUID father, String world,
                         int x, int z) {
    }

    /** Female first name (Polish convention: ending in "a", with the usual male exceptions). */
    public static boolean female(final String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String first = name.trim();
        final int space = first.indexOf(' ');
        if (space > 0) {
            first = first.substring(0, space);
        }
        final int comma = first.indexOf(',');
        if (comma > 0) {
            first = first.substring(0, comma);
        }
        final String n = first.toLowerCase(Locale.ROOT);
        return n.endsWith("a") && !MALE_A.contains(n);
    }

    private static final Set<String> MALE_A = Set.of("kuba", "barnaba", "bonawentura", "kosma", "jarema", "zawisza",
        "boryna", "sawa", "bonifacja");

    /** Household key of every member (members whose links point to the dead/absent are treated as alone). */
    public static Map<UUID, UUID> households(final List<Member> members) {
        final Map<UUID, Member> byId = new HashMap<>();
        for (final Member m : members) {
            byId.put(m.id(), m);
        }
        final Map<UUID, UUID> out = new HashMap<>();
        for (final Member m : members) {
            out.put(m.id(), key(m, byId, 0));
        }
        return out;
    }

    private static UUID key(final Member m, final Map<UUID, Member> byId, final int depth) {
        if (m.partner() != null) {
            final Member p = byId.get(m.partner());
            if (p != null && m.id().equals(p.partner())) {
                return m.id().toString().compareTo(p.id().toString()) <= 0 ? m.id() : p.id();
            }
        }
        if (depth < 4) {
            for (final UUID parentId : new UUID[] {m.mother(), m.father()}) {
                final Member parent = parentId == null ? null : byId.get(parentId);
                if (parent != null && !parent.id().equals(m.id())) {
                    return key(parent, byId, depth + 1);
                }
            }
        }
        return m.id();
    }

    /** Siblings or parent/child: never married to each other. */
    public static boolean related(final Member a, final Member b) {
        if (a.id().equals(b.mother()) || a.id().equals(b.father()) || b.id().equals(a.mother()) || b.id().equals(a.father())) {
            return true;
        }
        return a.mother() != null && Objects.equals(a.mother(), b.mother()) || a.father() != null && Objects.equals(a.father(), b.father());
    }

    /**
     * New couples today: every unmarried adult woman, in id order, is matched with the nearest unmarried adult man of
     * the same world (not related), and marries him with probability {@code chance}. Each person marries once.
     */
    public static List<UUID[]> marriages(final List<Member> members, final double chance, final RandomGenerator random) {
        final List<Member> women = new ArrayList<>();
        final List<Member> men = new ArrayList<>();
        final Map<UUID, Member> byId = new HashMap<>();
        for (final Member m : members) {
            byId.put(m.id(), m);
        }
        for (final Member m : members) {
            if (!m.adult() || married(m, byId)) {
                continue;
            }
            (female(m.name()) ? women : men).add(m);
        }
        women.sort(Comparator.comparing(m -> m.id().toString()));
        men.sort(Comparator.comparing(m -> m.id().toString()));
        final Set<UUID> taken = new HashSet<>();
        final List<UUID[]> out = new ArrayList<>();
        for (final Member w : women) {
            Member best = null;
            double bestD = Double.MAX_VALUE;
            for (final Member m : men) {
                if (taken.contains(m.id()) || !Objects.equals(m.world(), w.world()) || related(w, m)) {
                    continue;
                }
                final double d = Math.pow(m.x() - w.x(), 2) + Math.pow(m.z() - w.z(), 2);
                if (d < bestD) {
                    bestD = d;
                    best = m;
                }
            }
            if (best != null && random.nextDouble() < chance) {
                taken.add(best.id());
                out.add(new UUID[] {w.id(), best.id()});
            }
        }
        return out;
    }

    /** Married to a living partner who is married back. */
    public static boolean married(final Member m, final Map<UUID, Member> byId) {
        if (m.partner() == null) {
            return false;
        }
        final Member p = byId.get(m.partner());
        return p != null && m.id().equals(p.partner());
    }

    /** Couples (woman first when known) that may have a child: both adults, married to each other. */
    public static List<UUID[]> couples(final List<Member> members) {
        final Map<UUID, Member> byId = new HashMap<>();
        for (final Member m : members) {
            byId.put(m.id(), m);
        }
        final List<UUID[]> out = new ArrayList<>();
        for (final Member m : members) {
            if (!m.adult() || !married(m, byId)) {
                continue;
            }
            final Member p = byId.get(m.partner());
            if (!p.adult() || m.id().toString().compareTo(p.id().toString()) > 0) {
                continue; // each couple once
            }
            final boolean mFemale = female(m.name());
            out.add(mFemale || !female(p.name()) ? new UUID[] {m.id(), p.id()} : new UUID[] {p.id(), m.id()});
        }
        out.sort(Comparator.comparing(c -> c[0].toString()));
        return out;
    }

    /** A child is grown up after {@code childhoodMillis} since its birth (birth 0 = unknown → grown up now). */
    public static boolean grownUp(final long born, final long now, final long childhoodMillis) {
        return born <= 0 || now - born >= childhoodMillis;
    }

    /** Profession a grown-up child takes over: the father's, else the mother's, else none. */
    public static String inheritedProfession(final String father, final String mother) {
        for (final String p : new String[] {father, mother}) {
            final String key = Economy.professionKey(p);
            if (!key.equals("none") && !key.equals("nitwit")) {
                return p.contains(":") ? p : "minecraft:" + key;
            }
        }
        return "minecraft:none";
    }

    /** Length of a life in real days, drawn once per citizen in {@code [min, max]}. */
    public static long lifespanDays(final long min, final long max, final RandomGenerator random) {
        final long lo = Math.max(1, Math.min(min, max));
        final long hi = Math.max(lo, max);
        return lo + (hi > lo ? random.nextLong(hi - lo + 1) : 0);
    }

    /**
     * Start of the life (epoch millis) of a citizen without a known birth — existing citizens when the feature is
     * first enabled and adult settlers: a random age between 0 and {@code maxShare} of the lifespan, so nobody is old
     * enough to die right after the migration.
     */
    public static long initialLifeStart(final long now, final long lifespanDays, final double maxShare, final RandomGenerator random) {
        final double share = Math.max(0, Math.min(0.9, maxShare)) * random.nextDouble();
        return now - (long) (share * lifespanDays * 86_400_000L);
    }

    /** Old age (optional): the yearly-like chance to die grows after {@code lifespanDays}. */
    public static boolean diesOfOldAge(final long ageDays, final long lifespanDays, final double chancePerDay,
                                       final RandomGenerator random) {
        return lifespanDays > 0 && ageDays >= lifespanDays && random.nextDouble() < chancePerDay;
    }
}
