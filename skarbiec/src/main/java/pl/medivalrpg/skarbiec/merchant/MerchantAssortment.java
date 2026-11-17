// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.merchant;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deliveries of the merchants (pure Java, unit tested). The catalog is large; in one delivery period a merchant type
 * trades only its core goods (always) plus a rotating part drawn from the rest of its categories, plus a few goods of
 * the current season. Featured goods ("★ Kupiec tego szuka") are drawn from what is on offer.
 *
 * <p>Everything is a pure function of (type id, period number, season id, catalog): nothing is stored, so the offer is
 * the same after a restart and for every player. Goods are ranked by a stable 64-bit hash of (type, period, good), so
 * adding a good to the catalog changes only the places it wins. Stock of a good that left the offer stays in the shared
 * warehouse and is traded again when the good comes back.
 */
public final class MerchantAssortment {

    /** Ludnosc season ids in cycle order (wiosna → lato → jesień → zima). */
    public static final List<String> SEASONS = List.of("wiosna", "lato", "jesien", "zima");

    private static final long SALT_ROTATING = 0x524F544154494E47L;
    private static final long SALT_SEASONAL = 0x5345415349454E41L;
    private static final long SALT_FEATURED = 0x4645415455524544L;
    private static final long HOUR = 3_600_000L;

    private MerchantAssortment() {
    }

    /**
     * Delivery clock: periods of {@code periodHours} counted in local time of the zone, the first delivery of a day
     * at {@code startHour} (e.g. 12 h from 6:00 = deliveries at 6:00 and 18:00).
     */
    public record Schedule(boolean enabled, int periodHours, int startHour, int featured, boolean preview) {

        public static final Schedule DISABLED = new Schedule(false, 12, 6, 0, false);

        public Schedule {
            periodHours = Math.max(1, Math.min(168, periodHours));
            startHour = Math.max(0, Math.min(23, startHour));
            featured = Math.max(0, Math.min(45, featured));
        }

        private long periodMillis() {
            return this.periodHours * HOUR;
        }

        /** Number of the delivery period that contains {@code epochMillis}. */
        public long period(final long epochMillis, final ZoneId zone) {
            final long offset = zone.getRules().getOffset(Instant.ofEpochMilli(epochMillis)).getTotalSeconds() * 1000L;
            return Math.floorDiv(epochMillis + offset - this.startHour * HOUR, this.periodMillis());
        }

        /** Epoch millis when {@code period} starts (local wall time; DST gaps/overlaps resolved by java.time). */
        public long start(final long period, final ZoneId zone) {
            final long local = period * this.periodMillis() + this.startHour * HOUR;
            final LocalDateTime wall = LocalDateTime.ofEpochSecond(Math.floorDiv(local, 1000L), (int) Math.floorMod(local, 1000L) * 1_000_000,
                ZoneOffset.UTC);
            return wall.atZone(zone).toInstant().toEpochMilli();
        }

        public long millisToNext(final long epochMillis, final ZoneId zone) {
            return Math.max(1, this.start(this.period(epochMillis, zone) + 1, zone) - epochMillis);
        }
    }

    /** Ludnosc season as seen now; {@link #idAt} also answers for the neighbouring seasons. */
    public record SeasonClock(String id, int index, long startedAt, long endsAt) {

        /** Season id at {@code t}: this one, the next one after {@code endsAt}, the previous one before {@code startedAt}. */
        public String idAt(final long t) {
            final int i = Math.floorMod(this.index, SEASONS.size());
            if (t >= this.endsAt) {
                return SEASONS.get((i + 1) % SEASONS.size());
            }
            if (t < this.startedAt) {
                return SEASONS.get((i + SEASONS.size() - 1) % SEASONS.size());
            }
            return this.id;
        }
    }

    /** What one merchant type has in one period. Lists keep the catalog order. */
    public record Pick(List<MerchantOffer> core, List<MerchantOffer> rotating, List<MerchantOffer> seasonal, Set<String> featured) {

        public Pick {
            core = List.copyOf(core);
            rotating = List.copyOf(rotating);
            seasonal = List.copyOf(seasonal);
            featured = Set.copyOf(featured);
        }

        /** Everything on offer: core first, then the delivery (rotating and seasonal) in catalog order. */
        public List<MerchantOffer> offers() {
            final List<MerchantOffer> out = new ArrayList<>(this.core);
            out.addAll(this.rotating);
            out.addAll(this.seasonal);
            return out;
        }

        public boolean contains(final MerchantOffer offer) {
            return this.core.contains(offer) || this.rotating.contains(offer) || this.seasonal.contains(offer);
        }

        public boolean isCore(final MerchantOffer offer) {
            return this.core.contains(offer);
        }

        public boolean isSeasonal(final MerchantOffer offer) {
            return this.seasonal.contains(offer);
        }

        /** Goods of the delivery (not core). */
        public List<MerchantOffer> delivery() {
            final List<MerchantOffer> out = new ArrayList<>(this.rotating);
            out.addAll(this.seasonal);
            return out;
        }
    }

    /** Assortment switched off: the type trades everything it carries, nothing featured here. */
    public static Pick all(final MerchantType type, final List<MerchantOffer> catalog) {
        return new Pick(catalog.stream().filter(type::carries).toList(), List.of(), List.of(), Set.of());
    }

    /**
     * @param season season id at the start of the period, {@code null} when unknown (then no seasonal goods)
     */
    public static Pick pick(final MerchantType type, final List<MerchantOffer> catalog, final long period, final String season,
                            final int featuredCount) {
        final List<MerchantOffer> core = new ArrayList<>();
        final List<MerchantOffer> regular = new ArrayList<>();
        final List<MerchantOffer> inSeason = new ArrayList<>();
        for (final MerchantOffer o : catalog) {
            if (!type.carries(o)) {
                continue;
            }
            if (type.isCore(o)) {
                core.add(o);
            } else if (o.seasonal()) {
                if (season != null && o.seasons().contains(season)) {
                    inSeason.add(o);
                }
            } else {
                regular.add(o);
            }
        }
        final Set<MerchantOffer> rotating = top(regular, type.rotating(), SALT_ROTATING, type.id(), period);
        final Set<MerchantOffer> seasonal = top(inSeason, type.seasonal(), SALT_SEASONAL, type.id(), period);
        final List<MerchantOffer> buyable = new ArrayList<>();
        for (final MerchantOffer o : catalog) {
            if ((core.contains(o) || rotating.contains(o) || seasonal.contains(o)) && o.buys()) {
                buyable.add(o);
            }
        }
        final Set<String> featured = new LinkedHashSet<>();
        for (final MerchantOffer o : top(buyable, featuredCount, SALT_FEATURED, type.id(), period)) {
            featured.add(o.key());
        }
        return new Pick(core, inOrder(catalog, rotating), inOrder(catalog, seasonal), featured);
    }

    private static List<MerchantOffer> inOrder(final List<MerchantOffer> catalog, final Set<MerchantOffer> chosen) {
        return catalog.stream().filter(chosen::contains).toList();
    }

    private static Set<MerchantOffer> top(final List<MerchantOffer> from, final int count, final long salt, final String typeId,
                                          final long period) {
        if (count <= 0 || from.isEmpty()) {
            return Set.of();
        }
        final List<MerchantOffer> ranked = new ArrayList<>(from);
        ranked.sort(Comparator.comparingLong((MerchantOffer o) -> score(salt, typeId, period, o.key())).thenComparing(MerchantOffer::key));
        return new LinkedHashSet<>(ranked.subList(0, Math.min(count, ranked.size())));
    }

    /** Stable rank of a good in a period (independent of JVM, catalog order and other goods). */
    static long score(final long salt, final String typeId, final long period, final String key) {
        final long base = mix(fnv(typeId) ^ salt);
        return mix(mix(base + period * 0x9E3779B97F4A7C15L) ^ fnv(key));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long fnv(final String s) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001B3L;
        }
        return h;
    }
}
