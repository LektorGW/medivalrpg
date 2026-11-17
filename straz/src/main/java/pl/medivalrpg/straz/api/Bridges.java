// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.api;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Null-safe access to the other plugins (main thread). API classes of a plugin exist only when its jar is
 * loaded, so each call first checks that the plugin is enabled and then touches the API through a separate
 * holder class; a missing plugin or service gives an empty answer, never an exception.
 */
public final class Bridges {

    /** A country: id and name. */
    public record Country(UUID id, String name) {
    }

    private Method kolejStatus;

    public static boolean enabled(final String plugin) {
        final Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
        return p != null && p.isEnabled();
    }

    /** Countries from KrajeApi; empty when Kraje is missing. */
    public List<Country> countries() {
        if (!enabled("Kraje")) {
            return List.of();
        }
        try {
            return KrajeHolder.countries();
        } catch (final LinkageError | RuntimeException e) {
            return List.of();
        }
    }

    /** Sum of LudnoscApi.population over the countries; -1 when Ludnosc is missing. */
    public int population(final List<Country> countries) {
        if (!enabled("Ludnosc")) {
            return -1;
        }
        try {
            return LudnoscHolder.population(countries);
        } catch (final LinkageError | RuntimeException e) {
            return -1;
        }
    }

    /** True for a living citizen entity of Ludnosc. */
    public boolean isCitizen(final Entity entity) {
        if (!enabled("Ludnosc")) {
            return false;
        }
        try {
            return LudnoscHolder.isCitizen(entity);
        } catch (final LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** Soldiers per country (only countries with soldiers); null when Armia is missing. */
    public Map<Country, Integer> soldiers(final List<Country> countries) {
        if (!enabled("Armia")) {
            return null;
        }
        try {
            return ArmiaHolder.soldiers(countries);
        } catch (final LinkageError | RuntimeException e) {
            return null;
        }
    }

    /** Live status line of Kolej's network construction ({@code KolejPlugin#networkCommissionStatus}); null if unknown. */
    public String kolejStatus() {
        final Plugin p = Bukkit.getPluginManager().getPlugin("Kolej");
        if (p == null || !p.isEnabled()) {
            return null;
        }
        try {
            if (this.kolejStatus == null || this.kolejStatus.getDeclaringClass() != p.getClass()) {
                this.kolejStatus = p.getClass().getMethod("networkCommissionStatus");
            }
            final Object o = this.kolejStatus.invoke(p);
            return o == null ? null : o.toString();
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ holders (loaded only when the plugin is there)

    private static final class KrajeHolder {
        static List<Country> countries() {
            final var api = Bukkit.getServicesManager().load(pl.medivalrpg.kraje.api.KrajeApi.class);
            if (api == null) {
                return List.of();
            }
            final List<Country> out = new ArrayList<>();
            for (final var k : api.getCountries()) {
                out.add(new Country(k.id(), k.name()));
            }
            return out;
        }
    }

    private static final class LudnoscHolder {
        static int population(final List<Country> countries) {
            final var api = Bukkit.getServicesManager().load(pl.medivalrpg.ludnosc.api.LudnoscApi.class);
            if (api == null) {
                return -1;
            }
            int sum = 0;
            for (final Country c : countries) {
                sum += Math.max(0, api.population(c.id()));
            }
            return sum;
        }

        static boolean isCitizen(final Entity entity) {
            final var api = Bukkit.getServicesManager().load(pl.medivalrpg.ludnosc.api.LudnoscApi.class);
            return api != null && api.countryOf(entity).isPresent();
        }
    }

    private static final class ArmiaHolder {
        static Map<Country, Integer> soldiers(final List<Country> countries) {
            final var api = Bukkit.getServicesManager().load(pl.medivalrpg.armia.api.ArmiaApi.class);
            if (api == null) {
                return null;
            }
            final Map<Country, Integer> out = new LinkedHashMap<>();
            for (final Country c : countries) {
                final int n = api.soldierCount(c.id());
                if (n > 0) {
                    out.put(c, n);
                }
            }
            return out;
        }
    }
}
