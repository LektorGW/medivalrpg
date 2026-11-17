// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.login;

import com.nickuc.login.api.event.bukkit.auth.AuthenticateEvent;
import com.nickuc.login.api.nLoginAPI;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * nLogin 2.x integration. The ONLY class that touches nLogin classes, so it is loaded only when the nLogin
 * plugin is enabled ({@code softdepend: [nLogin]}).
 *
 * <p>{@link AuthenticateEvent} is fired by nLogin once a player is fully authenticated, whatever the way:
 * {@code /login}, {@code /register}, session or premium auto-login (it is called from nLogin's common
 * "authenticated" routine after the {@code LoginType} handling, checked in the 2.0.24 bytecode). Unlike
 * {@code LoginEvent} / {@code RegisterEvent} it is not cancellable, i.e. it means a successful login. It is an
 * <b>asynchronous</b> event, so the handler only reads the player's UUID and hands it to the main thread.
 */
public final class NLoginHook implements Listener, AuthGate {

    private final Consumer<Player> onAuthenticated;

    private NLoginHook(final Consumer<Player> onAuthenticated) {
        this.onAuthenticated = onAuthenticated;
    }

    /**
     * Registers the listener.
     *
     * @param onAuthenticated called from nLogin's (async) thread; must be thread-safe (e.g. only schedule a task)
     */
    public static AuthGate register(final Plugin plugin, final Consumer<Player> onAuthenticated) {
        final NLoginHook hook = new NLoginHook(onAuthenticated);
        Bukkit.getPluginManager().registerEvents(hook, plugin);
        return hook;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthenticate(final AuthenticateEvent event) {
        final Player player = event.getPlayer();
        if (player != null) {
            this.onAuthenticated.accept(player);
        }
    }

    @Override
    public boolean isAuthenticated(final Player player) {
        try {
            final nLoginAPI api = nLoginAPI.getApi();
            return api == null || !api.isAvailable() || api.isAuthenticated(player.getName());
        } catch (final RuntimeException | LinkageError e) {
            return true; // API not ready or changed: do not hide announcements because of it
        }
    }
}
