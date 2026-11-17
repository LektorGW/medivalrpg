// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;
import pl.medivalrpg.zycie.logic.Texts;

/**
 * Buildings of every country from {@link LudnoscApi#info} (types, functions, positions), cached and refreshed every
 * {@code wydajnosc.odswiez-liste-sekund}. No world scanning: positions come from Ludnosc. Main thread only.
 */
public final class CityCache {

    /** A building: position (Ludnosc spawn point or base centre), normalised tokens (type + function) and name. */
    public record Spot(String world, int x, int y, int z, Set<String> tokens, String name) {

        double distanceSquared(final Location l) {
            final double dx = l.getX() - this.x;
            final double dy = l.getY() - this.y;
            final double dz = l.getZ() - this.z;
            return dx * dx + dy * dy + dz * dz;
        }

        String key() {
            return String.join("/", this.tokens) + "@" + this.world + ":" + this.x + "," + this.y + "," + this.z;
        }
    }

    /** Snapshot of one country. {@code info} may be null when Ludnosc reports nothing. */
    public record City(UUID country, LudnoscInfo info, List<Spot> spots, Set<String> tokens, long refreshedAt) {

        public boolean has(final String token) {
            return this.tokens.contains(token);
        }

        public boolean hasAny(final List<String> wanted) {
            for (final String t : wanted) {
                if (this.tokens.contains(t)) {
                    return true;
                }
            }
            return false;
        }

        public int count(final List<String> wanted) {
            int n = 0;
            for (final Spot s : this.spots) {
                if (wanted.stream().anyMatch(s.tokens()::contains)) {
                    n++;
                }
            }
            return n;
        }

        /** Nearest building of any of the tokens in the same world as {@code from}. */
        public Optional<Spot> nearest(final List<String> wanted, final Location from) {
            Spot best = null;
            double bestD = Double.MAX_VALUE;
            final String world = from.getWorld() == null ? "" : from.getWorld().getName();
            for (final Spot s : this.spots) {
                if (!s.world().equals(world) || wanted.stream().noneMatch(s.tokens()::contains)) {
                    continue;
                }
                final double d = s.distanceSquared(from);
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    /** Callback for buildings that appeared since the previous refresh of a country (not on the first refresh). */
    public interface NewBuildings {
        void added(UUID country, List<Spot> spots);
    }

    private final LudnoscApi ludnosc;
    private final Map<UUID, City> cities = new HashMap<>();
    private long maxAgeMs = 30_000L;
    private NewBuildings listener = (c, s) -> {
    };

    public CityCache(final LudnoscApi ludnosc) {
        this.ludnosc = ludnosc;
    }

    public void maxAgeSeconds(final int seconds) {
        this.maxAgeMs = Math.max(1, seconds) * 1000L;
    }

    public void onNewBuildings(final NewBuildings callback) {
        this.listener = callback;
    }

    /** Cached city, refreshed when older than the configured age. */
    public City get(final UUID country) {
        final City c = this.cities.get(country);
        if (c != null && System.currentTimeMillis() - c.refreshedAt() < this.maxAgeMs) {
            return c;
        }
        return this.refresh(country);
    }

    public City refresh(final UUID country) {
        LudnoscInfo info = null;
        try {
            info = this.ludnosc.info(country).orElse(null);
        } catch (final RuntimeException ignored) {
            // older Ludnosc / transient problem: keep working without buildings
        }
        final List<Spot> spots = new ArrayList<>();
        final Set<String> tokens = new HashSet<>();
        if (info != null && info.buildings() != null) {
            for (final LudnoscInfo.Building b : info.buildings()) {
                final Set<String> t = new HashSet<>();
                t.add(Texts.normalize(b.type()));
                if (b.function() != null && !b.function().isBlank()) {
                    final String fn = b.function();
                    final int dot = fn.indexOf('•');
                    t.add(Texts.normalize((dot >= 0 ? fn.substring(0, dot) : fn).trim()));
                }
                t.remove("");
                tokens.addAll(t);
                spots.add(new Spot(b.world(), b.x(), b.y(), b.z(), Set.copyOf(t), b.name() == null ? b.type() : b.name()));
            }
        }
        final City previous = this.cities.get(country);
        final City city = new City(country, info, List.copyOf(spots), Set.copyOf(tokens), System.currentTimeMillis());
        this.cities.put(country, city);
        if (previous != null && previous.info() != null && info != null) {
            final Set<String> before = new HashSet<>();
            for (final Spot s : previous.spots()) {
                before.add(s.key());
            }
            final List<Spot> added = new ArrayList<>();
            for (final Spot s : spots) {
                if (!before.contains(s.key())) {
                    added.add(s);
                }
            }
            if (!added.isEmpty()) {
                this.listener.added(country, added);
            }
        }
        return city;
    }

    public void forget(final UUID country) {
        this.cities.remove(country);
    }

    public void clear() {
        this.cities.clear();
    }

    /** Centre of the capital chunk at ground level, or null when the world/chunk is not loaded. */
    public static Location capitalCenter(final KrajInfo k) {
        if (k == null || k.capitalWorld() == null) {
            return null;
        }
        final World w = Bukkit.getWorld(k.capitalWorld());
        if (w == null || !w.isChunkLoaded(k.capitalX(), k.capitalZ())) {
            return null;
        }
        final int x = (k.capitalX() << 4) + 8;
        final int z = (k.capitalZ() << 4) + 8;
        return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1.0, z + 0.5);
    }

    /**
     * Square spot of a country (festival, protest): the square-type building nearest to the capital centre, else the
     * capital centre. Null when nothing is loaded.
     */
    public Location squareSpot(final KrajInfo k, final List<String> squareTokens) {
        if (k == null) {
            return null;
        }
        final City city = this.get(k.id());
        final World capitalWorld = k.capitalWorld() == null ? null : Bukkit.getWorld(k.capitalWorld());
        if (capitalWorld != null) {
            final Location probe = new Location(capitalWorld, (k.capitalX() << 4) + 8, 64, (k.capitalZ() << 4) + 8);
            final Optional<Spot> s = city.nearest(squareTokens, probe);
            if (s.isPresent()) {
                final Spot spot = s.get();
                return new Location(capitalWorld, spot.x() + 0.5, spot.y(), spot.z() + 0.5);
            }
        }
        return capitalCenter(k);
    }
}
