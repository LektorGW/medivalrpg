// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.hook;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Epochs from the optional Postep plugin. Postep lists Ludnosc in its {@code softdepend}, so Ludnosc must not list
 * Postep (that would be a load-order cycle) and cannot link against its classes: {@code PostepApi} is found lazily
 * through {@code ServicesManager#getKnownServices()} and called reflectively, and {@code EraAdvanceEvent} is
 * registered with {@code PluginManager#registerEvent} using the event class from Postep's class loader (once Postep is
 * enabled). Without Postep every country is in epoch 1 (Middle Ages), has no discoveries and no effects.
 * Answers are cached for a few seconds (Postep's reads are cheap snapshots, reflection is not free).
 */
public final class PostepHook {

    public static final int MEDIEVAL = 1;
    public static final int RENAISSANCE = 2;
    public static final int INDUSTRIAL = 3;
    public static final int MODERN = 4;

    private static final String API = "pl.medivalrpg.postep.api.PostepApi";
    private static final String EVENT = "pl.medivalrpg.postep.api.event.EraAdvanceEvent";
    private static final long CACHE_MILLIS = 5_000;

    /** Polish epoch names. */
    public static String eraName(final int era) {
        return switch (era) {
            case 2 -> "Renesans";
            case 3 -> "Epoka przemysłowa";
            case 4 -> "Nowoczesność";
            default -> "Średniowiecze";
        };
    }

    /** Readable name of a discovery id ({@code beton_zbrojony} → "beton zbrojony"). */
    public static String discoveryName(final String id) {
        return switch (id) {
            case "elektrycznosc" -> "elektryczność";
            case "samochod" -> "samochód";
            case "bankowosc" -> "bankowość";
            case "nawigacja_oceaniczna" -> "nawigacja oceaniczna";
            default -> id.replace('_', ' ');
        };
    }

    private record Cached(Object value, long time) {
    }

    private final Logger logger;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private Class<?> type;
    private final Map<String, Method> methods = new ConcurrentHashMap<>();
    private boolean eventRegistered;
    private boolean warned;
    /** Test override: fixed answers without Postep. */
    private java.util.function.ToIntFunction<UUID> fixedEra;

    public PostepHook(final Logger logger) {
        this.logger = logger;
    }

    /** For tests / admin: fixed epoch per country instead of Postep. */
    public void fixEra(final java.util.function.ToIntFunction<UUID> f) {
        this.fixedEra = f;
        this.cache.clear();
    }

    private synchronized Class<?> type() {
        if (this.type != null && Bukkit.getServicesManager().isProvidedFor(this.type)) {
            return this.type;
        }
        this.type = null;
        this.methods.clear();
        for (final Class<?> known : Bukkit.getServicesManager().getKnownServices()) {
            if (known.getName().equals(API)) {
                this.type = known;
                break;
            }
        }
        return this.type;
    }

    public boolean present() {
        return this.type() != null;
    }

    private Object call(final String name, final Class<?>[] params, final Object... args) {
        try {
            final Class<?> t = this.type();
            if (t == null) {
                return null;
            }
            final Object provider = Bukkit.getServicesManager().load(t);
            if (provider == null) {
                return null;
            }
            final String key = name + params.length;
            Method m = this.methods.get(key);
            if (m == null) {
                m = t.getMethod(name, params);
                this.methods.put(key, m);
            }
            return m.invoke(provider, args);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!this.warned) {
                this.warned = true;
                this.logger.warning("Nie udało się odczytać PostepApi#" + name + ": " + e);
            }
            return null;
        }
    }

    private Object cached(final String key, final java.util.function.Supplier<Object> f) {
        final long now = System.currentTimeMillis();
        final Cached c = this.cache.get(key);
        if (c != null && now - c.time() < CACHE_MILLIS) {
            return c.value();
        }
        final Object v = f.get();
        this.cache.put(key, new Cached(v, now));
        return v;
    }

    /** Epoch 1..4 of a country (1 without Postep). */
    public int era(final UUID country) {
        if (country == null) {
            return MEDIEVAL;
        }
        if (this.fixedEra != null) {
            return Math.max(1, Math.min(4, this.fixedEra.applyAsInt(country)));
        }
        final Object v = this.cached("era:" + country, () -> this.call("era", new Class<?>[] {UUID.class}, country));
        return v instanceof Number n ? Math.max(1, Math.min(4, n.intValue())) : MEDIEVAL;
    }

    /** The country made a discovery (false without Postep). */
    public boolean hasDiscovery(final UUID country, final String id) {
        if (country == null || id == null || id.isBlank()) {
            return id == null || id.isBlank();
        }
        if (this.fixedEra != null) {
            return true;
        }
        final Object v = this.cached("d:" + country + ":" + id,
            () -> this.call("hasDiscovery", new Class<?>[] {UUID.class, String.class}, country, id));
        return Boolean.TRUE.equals(v);
    }

    /** Effect of discoveries in percent ({@code jedzenie}, {@code ludnosc}, {@code tempo-budowy}...); 0 without Postep. */
    public double effect(final UUID country, final String key) {
        if (country == null || this.fixedEra != null) {
            return 0;
        }
        final Object v = this.cached("e:" + country + ":" + key,
            () -> this.call("effect", new Class<?>[] {UUID.class, String.class}, country, key));
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    /**
     * Registers the {@code EraAdvanceEvent} handler once Postep is enabled (call on enable and on every
     * {@code PluginEnableEvent}). {@code handler} gets {country, oldEra, newEra}.
     */
    public void listen(final Plugin owner, final Consumer<Object[]> handler) {
        if (this.eventRegistered) {
            return;
        }
        final Plugin postep = Bukkit.getPluginManager().getPlugin("Postep");
        if (postep == null || !postep.isEnabled()) {
            return;
        }
        try {
            final Class<?> raw = Class.forName(EVENT, true, postep.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) {
                return;
            }
            @SuppressWarnings("unchecked") final Class<? extends Event> eventClass = (Class<? extends Event>) raw;
            final Method country = raw.getMethod("countryId");
            final Method oldEra = raw.getMethod("oldEra");
            final Method newEra = raw.getMethod("newEra");
            Bukkit.getPluginManager().registerEvent(eventClass, new Listener() { }, EventPriority.MONITOR, (listener, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                try {
                    final Object[] args = {country.invoke(event), oldEra.invoke(event), newEra.invoke(event)};
                    this.cache.clear();
                    handler.accept(args);
                } catch (final ReflectiveOperationException | RuntimeException e) {
                    this.logger.warning("EraAdvanceEvent: " + e);
                }
            }, owner, true);
            this.eventRegistered = true;
            this.logger.info("Postep wykryty: epoki państw włączone.");
        } catch (final ReflectiveOperationException | LinkageError e) {
            this.logger.warning("Nie udało się zarejestrować EraAdvanceEvent z Postep: " + e);
        }
    }

    public void forget() {
        this.cache.clear();
    }
}
