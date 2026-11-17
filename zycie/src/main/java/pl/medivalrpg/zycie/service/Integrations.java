// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Optional Armia (soldiers, NPC tax multiplier) and Postep (eras, {@code EraAdvanceEvent}) — looked up lazily through
 * {@code ServicesManager#getKnownServices()} and called reflectively, exactly like Kraje's integration. Zycie never
 * links against their classes, so it works in any load order, with or without them, and a later Postep/Armia
 * {@code softdepend: [Zycie]} creates no cycle.
 */
public final class Integrations {

    private static final String ARMIA = "pl.medivalrpg.armia.api.ArmiaApi";
    private static final String POSTEP = "pl.medivalrpg.postep.api.PostepApi";
    private static final String ERA_EVENT = "pl.medivalrpg.postep.api.event.EraAdvanceEvent";

    /** Callback of an era advance. */
    public interface EraListener {
        void advanced(UUID country, int oldEra, int newEra);
    }

    private final Logger logger;
    private final Service armia = new Service(ARMIA);
    private final Service postep = new Service(POSTEP);
    private final Listener eraHandle = new Listener() {
    };
    private boolean eraRegistered;

    public Integrations(final Logger logger) {
        this.logger = logger;
    }

    public boolean armiaPresent() {
        return this.armia.present();
    }

    public boolean postepPresent() {
        return this.postep.present();
    }

    /** Era 1..4 of a country (1 without Postep). */
    public int era(final UUID countryId) {
        return this.eraIfKnown(countryId).orElse(1);
    }

    /** Era 1..4 reported by Postep; empty when Postep is absent (not loaded yet, disabled) or the call failed. */
    public java.util.OptionalInt eraIfKnown(final UUID countryId) {
        final Object v = this.postep.call("era", new Class<?>[] {UUID.class}, countryId);
        return v instanceof Number n ? java.util.OptionalInt.of(Math.max(1, Math.min(4, n.intValue()))) : java.util.OptionalInt.empty();
    }

    /** Country of an Armia soldier entity (any part); empty without Armia. */
    public Optional<UUID> soldierCountry(final Entity entity) {
        final Object v = this.armia.call("soldierCountry", new Class<?>[] {Entity.class}, entity);
        if (v instanceof Optional<?> o && o.isPresent() && o.get() instanceof UUID id) {
            return Optional.of(id);
        }
        return Optional.empty();
    }

    private Method lifeAccessor;
    private Method lifeMoodInputs;
    private Object lifeOwner;

    /**
     * Wages and savings of a country's citizens from Ludnosc's citizens' life (reflection on
     * {@code LudnoscPlugin#life()#moodInputs(UUID)}, plain Java types; the frozen LudnoscApi has no such method):
     * {unpaid %, average savings md, homeless, unemployed %}. Null when Ludnosc is older or the feature is off.
     */
    public int[] ludnoscLife(final UUID countryId) {
        try {
            final Plugin p = Bukkit.getPluginManager().getPlugin("Ludnosc");
            if (p == null || !p.isEnabled()) {
                return null;
            }
            if (this.lifeOwner != p) {
                this.lifeOwner = p;
                this.lifeAccessor = p.getClass().getMethod("life");
                this.lifeMoodInputs = null;
            }
            final Object life = this.lifeAccessor.invoke(p);
            if (life == null) {
                return null;
            }
            if (this.lifeMoodInputs == null || this.lifeMoodInputs.getDeclaringClass() != life.getClass()) {
                this.lifeMoodInputs = life.getClass().getMethod("moodInputs", UUID.class);
            }
            return this.lifeMoodInputs.invoke(life, countryId) instanceof int[] a && a.length >= 2 ? a : null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** NPC tax multiplier of an NPC country in percent (Armia); empty without Armia. */
    public OptionalInt npcTaxMultiplier(final UUID countryId) {
        final Object v = this.armia.call("taxMultiplier", new Class<?>[] {UUID.class}, countryId);
        return v instanceof Number n ? OptionalInt.of(n.intValue()) : OptionalInt.empty();
    }

    /**
     * Registers a reflective listener for Postep's {@code EraAdvanceEvent} once the class can be loaded (Postep present).
     * Safe to call again (e.g. after Postep was enabled later); no-op when already registered or Postep is absent.
     */
    public synchronized void hookEraEvent(final Plugin owner, final EraListener callback) {
        if (this.eraRegistered) {
            return;
        }
        final Plugin postepPlugin = Bukkit.getPluginManager().getPlugin("Postep");
        if (postepPlugin == null || !postepPlugin.isEnabled()) {
            return;
        }
        try {
            final Class<?> raw = Class.forName(ERA_EVENT, true, postepPlugin.getClass().getClassLoader());
            final Class<? extends Event> type = raw.asSubclass(Event.class);
            final Method country = raw.getMethod("countryId");
            final Method oldEra = raw.getMethod("oldEra");
            final Method newEra = raw.getMethod("newEra");
            Bukkit.getPluginManager().registerEvent(type, this.eraHandle, EventPriority.MONITOR, (listener, event) -> {
                if (!type.isInstance(event)) {
                    return;
                }
                try {
                    callback.advanced((UUID) country.invoke(event), ((Number) oldEra.invoke(event)).intValue(),
                        ((Number) newEra.invoke(event)).intValue());
                } catch (final ReflectiveOperationException | RuntimeException e) {
                    this.logger.warning("Nie udało się odczytać EraAdvanceEvent: " + e);
                }
            }, owner, true);
            this.eraRegistered = true;
            this.logger.info("Podłączono do Postep (nowe epoki = festyn).");
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.logger.warning("Nie udało się podłączyć do EraAdvanceEvent (Postep): " + e);
        }
    }

    public synchronized void unhook() {
        HandlerList.unregisterAll(this.eraHandle);
        this.eraRegistered = false;
    }

    /** One reflectively accessed service; methods are cached per service class (reloads are picked up). */
    private final class Service {
        private final String className;
        private Class<?> cachedType;
        private final Map<String, Method> methods = new HashMap<>();
        private final Set<String> warned = new HashSet<>();

        Service(final String className) {
            this.className = className;
        }

        private Class<?> type() {
            if (this.cachedType != null && Bukkit.getServicesManager().isProvidedFor(this.cachedType)) {
                return this.cachedType;
            }
            this.cachedType = null;
            this.methods.clear();
            for (final Class<?> known : Bukkit.getServicesManager().getKnownServices()) {
                if (known.getName().equals(this.className)) {
                    this.cachedType = known;
                    break;
                }
            }
            return this.cachedType;
        }

        synchronized boolean present() {
            final Class<?> type = this.type();
            return type != null && Bukkit.getServicesManager().load(type) != null;
        }

        synchronized Object call(final String method, final Class<?>[] argTypes, final Object... args) {
            try {
                final Class<?> type = this.type();
                if (type == null) {
                    return null;
                }
                final Object provider = Bukkit.getServicesManager().load(type);
                if (provider == null) {
                    return null;
                }
                final StringBuilder key = new StringBuilder(method);
                for (final Class<?> a : argTypes) {
                    key.append('/').append(a.getName());
                }
                Method m = this.methods.get(key.toString());
                if (m == null) {
                    m = type.getMethod(method, argTypes);
                    this.methods.put(key.toString(), m);
                }
                return m.invoke(provider, args);
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                if (this.warned.add(method)) {
                    Integrations.this.logger.warning("Nie udało się odczytać " + this.className + "#" + method + ": " + e);
                }
                return null;
            }
        }
    }
}
