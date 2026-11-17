// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.menu.config.MenuSettings;

/**
 * Calls back once per session when a player is really "in the game", i.e. logged in.
 *
 * <ul>
 *   <li>Straznik (the MEDIVALRPG login plugin) present: listens to
 *       {@code pl.medivalrpg.straznik.api.event.PlayerAuthenticatedEvent} (login, register, session; called after
 *       the real position and inventory are back) and polls {@code StraznikApi#isAuthenticated(UUID)}.</li>
 *   <li>Otherwise nLogin (fallback while it is still installed): {@code AuthenticateEvent} and
 *       {@code nLoginAPI#isAuthenticated(String)}.</li>
 *   <li>Neither: a fixed delay after joining ({@code guide.login-fallback-seconds}).</li>
 * </ul>
 * Both plugins are reached by reflection, so Menu has no compile-time dependency on them.
 */
public final class LoginWatcher implements Listener {

    static final String STRAZNIK = "Straznik";
    static final String STRAZNIK_API = "pl.medivalrpg.straznik.api.StraznikApi";
    static final String STRAZNIK_EVENT = "pl.medivalrpg.straznik.api.event.PlayerAuthenticatedEvent";
    static final String NLOGIN = "nLogin";
    static final String AUTH_EVENT = "com.nickuc.login.api.event.bukkit.auth.AuthenticateEvent";
    static final String API_CLASS = "com.nickuc.login.api.nLoginAPI";
    /** Ticks between the authentication and the callback (lets the login plugin restore position/inventory first). */
    private static final long AFTER_AUTH_DELAY = 20L;

    private final Plugin plugin;
    private final Supplier<MenuSettings> settings;
    private final Consumer<Player> onReady;
    private final Map<UUID, BukkitTask> waiting = new HashMap<>();
    private final Set<UUID> done = new HashSet<>();

    /** Name of the login plugin in use, {@code null} = none. */
    private String backend;
    private boolean eventHooked;
    private Predicate<Player> authenticated;

    public LoginWatcher(final Plugin plugin, final Supplier<MenuSettings> settings, final Consumer<Player> onReady) {
        this.plugin = plugin;
        this.settings = settings;
        this.onReady = onReady;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        if (this.hookStraznik() || this.hookNLogin()) {
            this.plugin.getLogger().info("Integracja z " + this.backend + ": zdarzenie " + (this.eventHooked ? "tak" : "nie")
                + ", API " + (this.authenticated != null ? "tak" : "nie") + ".");
            return;
        }
        this.plugin.getLogger().info("Brak pluginu logowania (Straznik/nLogin) – Przewodnik będzie dawany "
            + this.settings.get().loginFallbackSeconds + " s po wejściu.");
    }

    /** Straznik with its service registered (it stays passive while nLogin is enabled). */
    private boolean hookStraznik() {
        final Plugin straznik = PluginHooks.enabled(STRAZNIK);
        if (straznik == null) {
            return false;
        }
        final ClassLoader loader = straznik.getClass().getClassLoader();
        try {
            final Class<?> apiClass = Class.forName(STRAZNIK_API, true, loader);
            final RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(apiClass);
            if (rsp == null) {
                return false; // passive (nLogin still installed)
            }
            final Object api = rsp.getProvider();
            final Method isAuth = apiClass.getMethod("isAuthenticated", UUID.class);
            this.authenticated = p -> {
                try {
                    return Boolean.TRUE.equals(isAuth.invoke(api, p.getUniqueId()));
                } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                    return false;
                }
            };
            this.eventHooked = this.hookEvent(Class.forName(STRAZNIK_EVENT, true, loader));
            this.backend = STRAZNIK;
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.plugin.getLogger().warning("Nie udało się podpiąć pod Straznika: " + e);
            this.authenticated = null;
            return false;
        }
    }

    private boolean hookNLogin() {
        final Plugin nlogin = PluginHooks.enabled(NLOGIN);
        if (nlogin == null) {
            return false;
        }
        final ClassLoader loader = nlogin.getClass().getClassLoader();
        try {
            this.eventHooked = this.hookEvent(Class.forName(AUTH_EVENT, true, loader));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.plugin.getLogger().warning("Nie udało się podpiąć pod AuthenticateEvent nLogina: " + e);
        }
        try {
            final Class<?> apiClass = Class.forName(API_CLASS, true, loader);
            final Object api = apiClass.getMethod("getApi").invoke(null);
            final Method isAuth = apiClass.getMethod("isAuthenticated", String.class);
            this.authenticated = p -> {
                try {
                    return Boolean.TRUE.equals(isAuth.invoke(api, p.getName()));
                } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                    return false;
                }
            };
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.authenticated = null;
            this.plugin.getLogger().warning("Nie udało się pobrać nLoginAPI: " + e);
        }
        this.backend = NLOGIN;
        return this.eventHooked || this.authenticated != null;
    }

    /** Registers a MONITOR listener for an event class with {@code getPlayer()}; {@code true} on success. */
    private boolean hookEvent(final Class<?> eventClass) throws ReflectiveOperationException {
        final Method getPlayer = eventClass.getMethod("getPlayer");
        Bukkit.getPluginManager().registerEvent(eventClass.asSubclass(Event.class), this, EventPriority.MONITOR,
            (listener, event) -> {
                // events may share a HandlerList with their parent, so filter by type
                if (!eventClass.isInstance(event)) {
                    return;
                }
                try {
                    if (getPlayer.invoke(event) instanceof Player p) {
                        // nLogin calls its event asynchronously; the scheduler is thread-safe
                        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.ready(p), AFTER_AUTH_DELAY);
                    }
                } catch (final ReflectiveOperationException | RuntimeException e) {
                    this.plugin.getLogger().fine("Zdarzenie logowania: " + e);
                }
            }, this.plugin, false);
        return true;
    }

    public void stop() {
        this.waiting.values().forEach(BukkitTask::cancel);
        this.waiting.clear();
        this.done.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID id = player.getUniqueId();
        this.done.remove(id);
        this.cancel(id);
        final MenuSettings s = this.settings.get();
        final boolean hooked = this.backend != null && PluginHooks.isEnabled(this.backend);
        if (!hooked) {
            this.waiting.put(id, Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.ready(player),
                Math.max(1L, s.loginFallbackSeconds * 20L)));
            return;
        }
        if (this.authenticated == null) {
            return; // the event listener will call ready()
        }
        final long deadline = System.currentTimeMillis() + s.loginMaxWaitSeconds * 1000L;
        this.waiting.put(id, Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (!player.isOnline() || System.currentTimeMillis() > deadline) {
                this.cancel(id);
            } else if (this.authenticated.test(player)) {
                this.cancel(id);
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.ready(player), AFTER_AUTH_DELAY);
            }
        }, 20L, 20L));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.cancel(event.getPlayer().getUniqueId());
        this.done.remove(event.getPlayer().getUniqueId());
    }

    private void cancel(final UUID id) {
        final BukkitTask task = this.waiting.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    private void ready(final Player player) {
        if (!player.isOnline() || !this.done.add(player.getUniqueId())) {
            return;
        }
        this.cancel(player.getUniqueId());
        try {
            this.onReady.accept(player);
        } catch (final RuntimeException e) {
            this.plugin.getLogger().warning("Błąd po zalogowaniu gracza " + player.getName() + ": " + e);
        }
    }
}
