// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.hook;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * Mood of the inhabitants from the optional Zycie plugin. Zycie has Ludnosc in its {@code depend}, so Ludnosc must not
 * list it (load-order cycle) and never links against its classes: {@code ZycieApi} is found lazily through
 * {@code ServicesManager#getKnownServices()} and called reflectively; answers are cached for a few seconds. Without
 * Zycie the mood multiplier is 1 and there is no unrest.
 */
public final class ZycieHook {

    private static final String API = "pl.medivalrpg.zycie.api.ZycieApi";
    private static final long CACHE_MILLIS = 5_000;

    private record Cached(Object value, long time) {
    }

    private final Logger logger;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Method> methods = new ConcurrentHashMap<>();
    private Class<?> type;
    private boolean warned;

    public ZycieHook(final Logger logger) {
        this.logger = logger;
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

    private Object call(final String name, final UUID country) {
        final long now = System.currentTimeMillis();
        final String key = name + ":" + country;
        final Cached c = this.cache.get(key);
        if (c != null && now - c.time() < CACHE_MILLIS) {
            return c.value();
        }
        Object v = null;
        try {
            final Class<?> t = this.type();
            if (t != null) {
                final Object provider = Bukkit.getServicesManager().load(t);
                if (provider != null) {
                    Method m = this.methods.get(name);
                    if (m == null) {
                        m = t.getMethod(name, UUID.class);
                        this.methods.put(name, m);
                    }
                    v = m.invoke(provider, country);
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!this.warned) {
                this.warned = true;
                this.logger.warning("Nie udało się odczytać ZycieApi#" + name + ": " + e);
            }
        }
        this.cache.put(key, new Cached(v, now));
        return v;
    }

    /** Mood multiplier 0.6..1.25 (1 without Zycie): NPC taxes, growth and production. */
    public double moodMultiplier(final UUID country) {
        if (country == null) {
            return 1.0;
        }
        final Object v = this.call("moodMultiplier", country);
        return v instanceof Number n ? Math.max(0.6, Math.min(1.25, n.doubleValue())) : 1.0;
    }

    /**
     * Zycie runs (it walks the citizens to their workplaces in its daily plan, so Ludnosc then only steers them within
     * the workplace). Cached like the other answers.
     */
    public boolean available() {
        final long now = System.currentTimeMillis();
        final Cached c = this.cache.get("available");
        if (c != null && now - c.time() < CACHE_MILLIS) {
            return Boolean.TRUE.equals(c.value());
        }
        boolean v;
        try {
            v = this.type() != null;
        } catch (final RuntimeException | LinkageError e) {
            v = false;
        }
        this.cache.put("available", new Cached(v, now));
        return v;
    }

    /** Unrest: the population does not grow. */
    public boolean unrest(final UUID country) {
        return country != null && Boolean.TRUE.equals(this.call("unrest", country));
    }
}
