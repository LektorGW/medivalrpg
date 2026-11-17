// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import pl.medivalrpg.menu.gui.CountryList;

/**
 * Reads the player's country from {@code pl.medivalrpg.kraje.api.KrajeApi} (ServicesManager) <b>by reflection</b>.
 *
 * <p>Menu does not compile against the shared API on purpose: the server may still run an older Kraje whose
 * {@code KrajInfo} has a different shape, or no Kraje at all. Only {@code getCountryOf(UUID)},
 * {@code getRank(UUID)} and the accessors {@code name()}, {@code memberCount()}, {@code chunkCount()} and
 * {@code displayName()} (plus {@code getSiege(String, int, int)}, {@code getCountryAt(String, int, int)} and
 * {@code id()} for teleports and homes; {@code getCountries()} with {@code color()}, {@code npc()} and
 * {@code rulerName()} for the "Państwa" menu) are looked up by name; every failure (missing class, changed signature,
 * {@link LinkageError}) just means "unknown" and is logged once.</p>
 */
public final class KrajeHook {

    static final String API_CLASS = "pl.medivalrpg.kraje.api.KrajeApi";

    /** What Menu shows about a country. Numbers are -1 when unknown. */
    public record CountryView(String name, String rank, int members, int chunks) {
    }

    private final Logger logger;
    private boolean warned;
    private final Set<String> warnedFor = new HashSet<>();

    public KrajeHook(final Logger logger) {
        this.logger = logger;
    }

    /** Country of the player, empty when Kraje is missing or the player has none. Main thread only. */
    public Optional<CountryView> countryOf(final UUID player) {
        try {
            final RegisteredServiceProvider<?> rsp = this.registration();
            if (rsp == null) {
                return Optional.empty();
            }
            final Object api = rsp.getProvider();
            final Class<?> service = rsp.getService();
            final Object info = unwrap(service.getMethod("getCountryOf", UUID.class).invoke(api, player));
            if (info == null) {
                return Optional.empty();
            }
            Object rawName = call(info, "name");
            if (rawName == null) {
                rawName = call(info, "getName");
            }
            final String name = rawName == null ? "?" : String.valueOf(rawName);
            String rank = null;
            try {
                final Object r = unwrap(service.getMethod("getRank", UUID.class).invoke(api, player));
                if (r != null) {
                    final Object display = call(r, "displayName");
                    rank = display != null ? String.valueOf(display) : String.valueOf(r);
                }
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // older API without ranks
            }
            return Optional.of(new CountryView(name, rank, intOf(call(info, "memberCount")), intOf(call(info, "chunkCount"))));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!this.warned) {
                this.warned = true;
                this.logger.warning("Nie udało się odczytać kraju z KrajeApi (inna wersja Kraje?): " + e);
            }
            return Optional.empty();
        }
    }

    /**
     * {@code getCountries()}: every country (unsorted). Empty when Kraje is missing or its API cannot be read;
     * a country whose name cannot be read is skipped. Main thread.
     */
    public Optional<List<CountryList.Entry>> countries() {
        try {
            final RegisteredServiceProvider<?> rsp = this.registration();
            if (rsp == null) {
                return Optional.empty();
            }
            final Object raw = rsp.getService().getMethod("getCountries").invoke(rsp.getProvider());
            if (!(raw instanceof Collection<?> all)) {
                throw new NoSuchMethodException("getCountries() nie zwraca kolekcji");
            }
            return Optional.of(entries(all));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warnOnce("getCountries", e);
            return Optional.empty();
        }
    }

    /**
     * {@code KrajInfo} snapshots (read by method name: {@code name()}, {@code color()}, {@code npc()},
     * {@code rulerName()}, {@code chunkCount()}, {@code memberCount()}) → menu entries. Objects without a name are
     * skipped; other missing values become defaults (white, player country, "?", -1).
     */
    public static List<CountryList.Entry> entries(final Collection<?> infos) {
        final List<CountryList.Entry> out = new ArrayList<>(infos.size());
        for (final Object info : infos) {
            final Object name = info == null ? null : call(info, "name");
            if (name == null) {
                continue;
            }
            final Object ruler = call(info, "rulerName");
            out.add(new CountryList.Entry(String.valueOf(name), rgbOf(call(info, "color")),
                Boolean.TRUE.equals(call(info, "npc")), ruler == null || String.valueOf(ruler).isBlank() ? "?" : String.valueOf(ruler),
                intOf(call(info, "chunkCount")), intOf(call(info, "memberCount"))));
        }
        return out;
    }

    /** Adventure {@code TextColor} (or anything with {@code value()}) → {@code 0xRRGGBB}; white when unknown. */
    private static int rgbOf(final Object color) {
        if (color instanceof net.kyori.adventure.text.format.TextColor tc) {
            return tc.value();
        }
        final Object value = color == null ? null : call(color, "value");
        return value instanceof Number n ? n.intValue() & 0xFFFFFF : 0xFFFFFF;
    }

    public boolean hasCountry(final UUID player) {
        return this.countryOf(player).isPresent();
    }

    /**
     * Who owns a chunk and which country the player is in ({@code null} = wild / no country).
     * Empty when Kraje is missing or its API cannot be read – callers then skip land rules.
     */
    public record Territory(UUID landOwner, UUID playerCountry) {
    }

    /** {@code getSiege(world, cx, cz)}: is a siege running on that chunk? {@code false} without Kraje. Main thread. */
    public boolean siegeAt(final String world, final int cx, final int cz) {
        try {
            final RegisteredServiceProvider<?> rsp = this.registration();
            if (rsp == null) {
                return false;
            }
            final Method m = rsp.getService().getMethod("getSiege", String.class, int.class, int.class);
            return unwrap(m.invoke(rsp.getProvider(), world, cx, cz)) != null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warnOnce("getSiege", e);
            return false;
        }
    }

    /** {@code getCountryAt(world, cx, cz)} and {@code getCountryOf(player)} as country ids. Main thread. */
    public Optional<Territory> territory(final UUID player, final String world, final int cx, final int cz) {
        try {
            final RegisteredServiceProvider<?> rsp = this.registration();
            if (rsp == null) {
                return Optional.empty();
            }
            final Object api = rsp.getProvider();
            final Class<?> service = rsp.getService();
            final Object land = unwrap(service.getMethod("getCountryAt", String.class, int.class, int.class)
                .invoke(api, world, cx, cz));
            final Object own = unwrap(service.getMethod("getCountryOf", UUID.class).invoke(api, player));
            final UUID landId = land == null ? null : idOf(land);
            final UUID ownId = own == null ? null : idOf(own);
            if ((land != null && landId == null) || (own != null && ownId == null)) {
                throw new NoSuchMethodException("KrajInfo#id()");
            }
            return Optional.of(new Territory(landId, ownId));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warnOnce("getCountryAt", e);
            return Optional.empty();
        }
    }

    /**
     * {@code addProtectedArea(id, world, minX, minZ, maxX, maxZ, bufferChunks)}: land nobody may claim (spawn).
     *
     * @return {@code null} on success, otherwise a short reason (no Kraje, old API, refused)
     */
    public String addProtectedArea(final String id, final String world, final int minX, final int minZ,
                                   final int maxX, final int maxZ, final int bufferChunks) {
        return this.mutate("addProtectedArea", new Class<?>[] {String.class, String.class, int.class, int.class,
            int.class, int.class, int.class}, id, world, minX, minZ, maxX, maxZ, bufferChunks);
    }

    /** {@code removeProtectedArea(id)}; {@code null} on success. */
    public String removeProtectedArea(final String id) {
        return this.mutate("removeProtectedArea", new Class<?>[] {String.class}, id);
    }

    private String mutate(final String method, final Class<?>[] types, final Object... args) {
        try {
            final RegisteredServiceProvider<?> rsp = this.registration();
            if (rsp == null) {
                return "brak pluginu Kraje";
            }
            final Object result = rsp.getService().getMethod(method, types).invoke(rsp.getProvider(), args);
            if (result == null) {
                return "brak wyniku";
            }
            if (Boolean.TRUE.equals(result.getClass().getMethod("ok").invoke(result))) {
                return null;
            }
            final Object message = call(result, "message");
            final Object status = call(result, "status");
            return status + (message == null || String.valueOf(message).isEmpty() ? "" : ": " + message);
        } catch (final NoSuchMethodException e) {
            return "Kraje bez " + method + " (stara wersja)";
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warnOnce(method, e);
            return "błąd: " + e;
        }
    }

    private static UUID idOf(final Object info) {
        Object id = call(info, "id");
        if (id == null) {
            id = call(info, "getId");
        }
        return id instanceof UUID u ? u : null;
    }

    private void warnOnce(final String what, final Throwable e) {
        if (this.warnedFor.add(what)) {
            this.logger.warning("KrajeApi#" + what + " niedostępne (inna wersja Kraje?): " + e);
        }
    }

    private RegisteredServiceProvider<?> registration() {
        for (final Class<?> known : Bukkit.getServicesManager().getKnownServices()) {
            if (API_CLASS.equals(known.getName())) {
                return Bukkit.getServicesManager().getRegistration(known);
            }
        }
        return null;
    }

    private static Object unwrap(final Object o) {
        return o instanceof Optional<?> opt ? opt.orElse(null) : o;
    }

    /** Calls a public no-arg method; {@code null} when it does not exist. */
    private static Object call(final Object target, final String method) {
        try {
            final Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static int intOf(final Object o) {
        return o instanceof Number n ? n.intValue() : -1;
    }
}
