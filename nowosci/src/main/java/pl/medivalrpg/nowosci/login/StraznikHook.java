// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.login;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Straznik (MEDIVALRPG login plugin) integration, by reflection — Nowosci has no compile-time dependency on it.
 * {@code PlayerAuthenticatedEvent} is called on the main thread once per connection (login, register, session),
 * after the player's real position and inventory are back. {@code StraznikApi#isAuthenticated(UUID)} gates
 * announcements for players still on the login screen.
 */
public final class StraznikHook implements Listener, AuthGate {

    static final String PLUGIN = "Straznik";
    static final String API = "pl.medivalrpg.straznik.api.StraznikApi";
    static final String EVENT = "pl.medivalrpg.straznik.api.event.PlayerAuthenticatedEvent";

    private final Object api;
    private final Method isAuthenticated;

    private StraznikHook(final Object api, final Method isAuthenticated) {
        this.api = api;
        this.isAuthenticated = isAuthenticated;
    }

    /**
     * Hooks Straznik when it is enabled AND active (its API service is registered; it stays passive while nLogin is
     * enabled). Returns {@code null} when Straznik cannot be used.
     *
     * @param onAuthenticated called on the main thread
     */
    public static AuthGate tryRegister(final Plugin plugin, final Consumer<Player> onAuthenticated) throws ReflectiveOperationException {
        final Plugin straznik = Bukkit.getPluginManager().getPlugin(PLUGIN);
        if (straznik == null || !straznik.isEnabled()) {
            return null;
        }
        final ClassLoader loader = straznik.getClass().getClassLoader();
        final Class<?> apiClass = Class.forName(API, true, loader);
        final RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(apiClass);
        if (rsp == null) {
            return null;
        }
        final StraznikHook hook = new StraznikHook(rsp.getProvider(), apiClass.getMethod("isAuthenticated", UUID.class));
        final Class<?> eventClass = Class.forName(EVENT, true, loader);
        final Method getPlayer = eventClass.getMethod("getPlayer");
        Bukkit.getPluginManager().registerEvent(eventClass.asSubclass(Event.class), hook, EventPriority.MONITOR,
            (listener, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                try {
                    if (getPlayer.invoke(event) instanceof Player p) {
                        onAuthenticated.accept(p);
                    }
                } catch (final ReflectiveOperationException | RuntimeException e) {
                    plugin.getLogger().fine("PlayerAuthenticatedEvent: " + e);
                }
            }, plugin, false);
        return hook;
    }

    @Override
    public boolean isAuthenticated(final Player player) {
        try {
            return Boolean.TRUE.equals(this.isAuthenticated.invoke(this.api, player.getUniqueId()));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return true; // API changed: do not hide announcements because of it
        }
    }
}
