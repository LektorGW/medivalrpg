// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.auth;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Password check through Straznik, the MEDIVALRPG login plugin ({@code StraznikApi#verifyPassword}), reached by
 * reflection (no compile-time dependency). Its {@code PasswordChangedEvent} (password changed or account removed)
 * revokes the player's panel sessions. Used whenever Straznik is active; otherwise the panel falls back to nLogin.
 */
public final class StraznikVerifier implements PasswordVerifier, Listener {

    static final String PLUGIN = "Straznik";
    static final String API = "pl.medivalrpg.straznik.api.StraznikApi";
    static final String EVENT = "pl.medivalrpg.straznik.api.event.PasswordChangedEvent";

    private final Logger logger;
    private final Object api;
    private final Method verify;
    private final Method registeredName;

    private StraznikVerifier(final Logger logger, final Object api, final Method verify, final Method registeredName) {
        this.logger = logger;
        this.api = api;
        this.verify = verify;
        this.registeredName = registeredName;
    }

    /**
     * Straznik verifier when Straznik is enabled and active (its service is registered — it stays passive while
     * nLogin is enabled), otherwise {@code fallback} (nLogin or none).
     */
    public static PasswordVerifier createOr(final Plugin plugin, final Consumer<UUID> revoke, final Supplier<PasswordVerifier> fallback) {
        final Plugin straznik = Bukkit.getPluginManager().getPlugin(PLUGIN);
        if (straznik == null || !straznik.isEnabled()) {
            return fallback.get();
        }
        try {
            final ClassLoader loader = straznik.getClass().getClassLoader();
            final Class<?> apiClass = Class.forName(API, true, loader);
            final RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(apiClass);
            if (rsp == null) {
                return fallback.get();
            }
            final StraznikVerifier v = new StraznikVerifier(plugin.getLogger(), rsp.getProvider(),
                apiClass.getMethod("verifyPassword", String.class, String.class),
                apiClass.getMethod("registeredName", String.class));
            final Class<?> eventClass = Class.forName(EVENT, true, loader);
            final Method getPlayerId = eventClass.getMethod("getPlayerId");
            Bukkit.getPluginManager().registerEvent(eventClass.asSubclass(Event.class), v, EventPriority.MONITOR,
                (listener, event) -> {
                    if (!eventClass.isInstance(event)) {
                        return;
                    }
                    try {
                        if (getPlayerId.invoke(event) instanceof UUID id) {
                            revoke.accept(id);
                        }
                    } catch (final ReflectiveOperationException | RuntimeException e) {
                        plugin.getLogger().log(Level.WARNING, "Straznik: zdarzenie zmiany hasła", e);
                    }
                }, plugin, false);
            plugin.getLogger().info("Logowanie hasłem w panelu: Straznik.");
            return v;
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Nie udało się podpiąć pod Straznika — próbuję nLogin", e);
            return fallback.get();
        }
    }

    /** Unknown nick → {@link Result#NOT_REGISTERED} (reported to the user as a wrong password, no enumeration). */
    @Override
    public Result verify(final String name, final String password) {
        try {
            final Object r = this.verify.invoke(this.api, name, password);
            return switch (r == null ? "" : ((Enum<?>) r).name()) {
                case "OK" -> Result.OK;
                case "WRONG" -> Result.WRONG;
                case "NOT_REGISTERED" -> Result.NOT_REGISTERED;
                default -> Result.UNAVAILABLE;
            };
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            this.logger.log(Level.WARNING, "Straznik: błąd sprawdzania hasła", e);
            return Result.UNAVAILABLE;
        }
    }

    @Override
    public String canonicalName(final String name) {
        try {
            if (this.registeredName.invoke(this.api, name) instanceof Optional<?> o && o.orElse(null) instanceof String s) {
                return s;
            }
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            // keep the typed name
        }
        return name;
    }
}
