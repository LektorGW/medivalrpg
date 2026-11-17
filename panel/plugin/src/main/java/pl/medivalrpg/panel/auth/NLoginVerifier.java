// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.auth;

import com.nickuc.login.api.event.bukkit.account.PasswordUpdateEvent;
import com.nickuc.login.api.nLoginAPI;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * The only class touching nLogin (softdepend). Loaded only when nLogin is enabled, so the panel works without it
 * (password login is then unavailable, the {@code /panel} link still works).
 */
public final class NLoginVerifier implements PasswordVerifier {

    private final Logger logger;

    private NLoginVerifier(final Logger logger) {
        this.logger = logger;
    }

    /** nLogin verifier when nLogin is enabled, else {@link PasswordVerifier#NONE}. */
    public static PasswordVerifier create(final Plugin plugin, final Consumer<UUID> revoke) {
        final Plugin nlogin = Bukkit.getPluginManager().getPlugin("nLogin");
        if (nlogin == null || !nlogin.isEnabled()) {
            plugin.getLogger().warning("Brak pluginu nLogin — logowanie hasłem w panelu jest wyłączone (działa link z /panel).");
            return NONE;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new PasswordListener(revoke), plugin);
        } catch (final LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Nie udało się nasłuchiwać zmian hasła nLogin", e);
        }
        return new NLoginVerifier(plugin.getLogger());
    }

    /**
     * {@code nLoginAPI.getApi().comparePassword(name, password)} as required by PANEL.md (deprecated in nLogin 2.x in
     * favour of {@code getAccount(Identity)}, but still the documented name/password check). An unknown account is
     * reported as a wrong password (no account enumeration).
     */
    @Override
    @SuppressWarnings("deprecation")
    public Result verify(final String name, final String password) {
        try {
            final nLoginAPI api = nLoginAPI.getApi();
            if (api == null || !api.isAvailable()) {
                return Result.UNAVAILABLE;
            }
            return api.comparePassword(name, password) ? Result.OK : Result.WRONG;
        } catch (final LinkageError | RuntimeException e) {
            this.logger.log(Level.WARNING, "nLogin: błąd sprawdzania hasła", e);
            return Result.UNAVAILABLE;
        }
    }

    /** Password changed in game → all panel sessions of that player are revoked. */
    private static final class PasswordListener implements Listener {

        private final Consumer<UUID> revoke;

        PasswordListener(final Consumer<UUID> revoke) {
            this.revoke = revoke;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPasswordUpdate(final PasswordUpdateEvent event) {
            UUID uuid = null;
            final Object p = event.getPlayer();
            if (p instanceof Player player) {
                uuid = player.getUniqueId();
            } else if (event.getPlayerName() != null) {
                final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(event.getPlayerName());
                uuid = cached == null ? event.getPlayerId() : cached.getUniqueId();
            } else {
                uuid = event.getPlayerId();
            }
            if (uuid != null) {
                this.revoke.accept(uuid);
            }
        }
    }
}
