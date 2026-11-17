// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.service;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Eras of the Postep plugin, found lazily and called by reflection: Postep has Armia in its softdepend, so Armia must
 * not depend on it (no class of {@code pl.medivalrpg.postep.api} is linked here). Without Postep every country is in
 * era 1 without discoveries and effects. {@code EraAdvanceEvent} is registered dynamically when the service appears.
 */
public final class Progress {

    private static final String API = "pl.medivalrpg.postep.api.PostepApi";
    private static final String EVENT = "pl.medivalrpg.postep.api.event.EraAdvanceEvent";

    /** Era change of a country. */
    @FunctionalInterface
    public interface EraListener {
        void onEra(UUID country, int oldEra, int newEra);
    }

    private final Plugin plugin;
    private final EraListener onEra;
    private final Map<String, Double> effectCache = new ConcurrentHashMap<>();
    private final Map<String, Method> methods = new ConcurrentHashMap<>();
    private volatile Object api;
    private volatile Class<?> apiClass;
    private volatile long nextLookup;
    private volatile long cacheTime;
    private boolean eventRegistered;
    private boolean warned;

    public Progress(final Plugin plugin, final EraListener onEra) {
        this.plugin = plugin;
        this.onEra = onEra;
    }

    /** Forget the provider (Postep enabled / disabled / reloaded). */
    public void reset() {
        this.api = null;
        this.apiClass = null;
        this.methods.clear();
        this.effectCache.clear();
        this.nextLookup = 0;
    }

    private Object api() {
        final Object a = this.api;
        if (a != null) {
            return a;
        }
        final long now = System.currentTimeMillis();
        if (now < this.nextLookup) {
            return null;
        }
        this.nextLookup = now + 5000L;
        try {
            for (final Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (API.equals(c.getName())) {
                    final RegisteredServiceProvider<?> r = Bukkit.getServicesManager().getRegistration(c);
                    if (r != null && r.getProvider() != null) {
                        this.apiClass = c;
                        this.api = r.getProvider();
                        if (Bukkit.isPrimaryThread()) {
                            this.registerEvent(c.getClassLoader());
                        }
                        return this.api;
                    }
                }
            }
        } catch (final RuntimeException e) {
            return null;
        }
        return null;
    }

    public boolean present() {
        return this.api() != null;
    }

    @SuppressWarnings("unchecked")
    private void registerEvent(final ClassLoader loader) {
        if (this.eventRegistered) {
            return;
        }
        try {
            final Class<? extends Event> cls = (Class<? extends Event>) Class.forName(EVENT, true, loader);
            final Method country = cls.getMethod("countryId");
            final Method oldEra = cls.getMethod("oldEra");
            final Method newEra = cls.getMethod("newEra");
            Bukkit.getPluginManager().registerEvent(cls, new Listener() { }, EventPriority.MONITOR, (l, e) -> {
                if (!cls.isInstance(e)) {
                    return;
                }
                try {
                    this.effectCache.clear();
                    this.onEra.onEra((UUID) country.invoke(e), (Integer) oldEra.invoke(e), (Integer) newEra.invoke(e));
                } catch (final ReflectiveOperationException | RuntimeException ex) {
                    this.plugin.getLogger().log(Level.WARNING, "EraAdvanceEvent", ex);
                }
            }, this.plugin);
            this.eventRegistered = true;
            this.plugin.getLogger().info("Postep znaleziony — jednostki epok włączone");
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.plugin.getLogger().warning("Nie udało się nasłuchiwać EraAdvanceEvent z Postep: " + e);
        }
    }

    private Object call(final String name, final Class<?>[] types, final Object... args) {
        final Object a = this.api();
        if (a == null) {
            return null;
        }
        try {
            final Method m = this.methods.computeIfAbsent(name + types.length, k -> {
                try {
                    return this.apiClass.getMethod(name, types);
                } catch (final NoSuchMethodException e) {
                    return null;
                }
            });
            return m == null ? null : m.invoke(a, args);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            if (!this.warned) {
                this.warned = true;
                this.plugin.getLogger().log(Level.WARNING, "Wywołanie PostepApi." + name + " nie powiodło się", e);
            }
            return null;
        }
    }

    /** Era 1..4 (1 without Postep). */
    public int era(final UUID country) {
        final Object r = this.call("era", new Class<?>[] {UUID.class}, country);
        return r instanceof Integer i ? Math.max(1, Math.min(4, i)) : 1;
    }

    public boolean has(final UUID country, final String discovery) {
        if (discovery == null || discovery.isBlank()) {
            return true;
        }
        final Object r = this.call("hasDiscovery", new Class<?>[] {UUID.class, String.class}, country, discovery);
        return r instanceof Boolean b && b;
    }

    /** Effect in percent (cached for a minute). */
    public double effect(final UUID country, final String key) {
        final long now = System.currentTimeMillis();
        if (now - this.cacheTime > 60_000L) {
            this.effectCache.clear();
            this.cacheTime = now;
        }
        return this.effectCache.computeIfAbsent(country + "|" + key, k -> {
            final Object r = this.call("effect", new Class<?>[] {UUID.class, String.class}, country, key);
            return r instanceof Number n ? n.doubleValue() : 0.0;
        });
    }

    /** Polish era name, e.g. "Renesans". */
    public String eraName(final int era) {
        final Object info = this.call("eraInfo", new Class<?>[] {int.class}, era);
        if (info != null) {
            try {
                return String.valueOf(info.getClass().getMethod("name").invoke(info));
            } catch (final ReflectiveOperationException | RuntimeException e) {
                // fall through
            }
        }
        return switch (era) {
            case 2 -> "Renesans";
            case 3 -> "Epoka przemysłowa";
            case 4 -> "Nowoczesność";
            default -> "Średniowiecze";
        };
    }

    /** Polish name of a discovery (its id when unknown). */
    public String discoveryName(final String id) {
        final Object opt = this.call("discovery", new Class<?>[] {String.class}, id);
        if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
            try {
                return String.valueOf(o.get().getClass().getMethod("name").invoke(o.get()));
            } catch (final ReflectiveOperationException | RuntimeException e) {
                return id;
            }
        }
        return id;
    }

    /** Main thread. Investment share of an NPC country: 0..100, -1 = Postep decides. */
    public boolean setNpcInvestShare(final UUID country, final int percent) {
        final Object r = this.call("setNpcInvestShare", new Class<?>[] {UUID.class, int.class}, country, percent);
        return r instanceof Boolean b && b;
    }

    /** Main thread. Invests treasury money into research; copper actually taken (0 when unsupported). */
    public long invest(final UUID country, final long amount) {
        final Object r = this.call("invest", new Class<?>[] {UUID.class, long.class, UUID.class}, country, amount, null);
        if (r == null) {
            return 0;
        }
        try {
            final Object paid = r.getClass().getMethod("paid").invoke(r);
            return paid instanceof Number n ? n.longValue() : 0;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }
}
