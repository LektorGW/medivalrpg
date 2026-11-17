// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.IllegalPluginAccessException;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.audit.AuditLog;
import pl.medivalrpg.panel.net.Protocol;
import pl.medivalrpg.panel.rpc.Params;

/**
 * Password login ({@code auth} → {@code auth-result}) and login links ({@code /panel} → {@code link-token}).
 * Passwords are checked by Straznik (fallback nLogin) off the main thread; failed attempts are rate-limited per nick and per IP
 * ({@link LoginGuard}). The UUID of a successful login comes from the server's player cache.
 */
public final class AuthService {

    /** Same wording for a wrong password and an unknown nick (no account enumeration). */
    static final String WRONG = "Nieprawidłowy nick lub hasło.";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final PanelPlugin plugin;
    private final ExecutorService authExecutor;
    private final SecureRandom random = new SecureRandom();
    private volatile LoginGuard guard;
    private volatile PasswordVerifier verifier = PasswordVerifier.NONE;

    public AuthService(final PanelPlugin plugin, final ExecutorService authExecutor) {
        this.plugin = plugin;
        this.authExecutor = authExecutor;
        this.reload();
    }

    public void reload() {
        this.guard = new LoginGuard(this.plugin.cfg().loginAttempts, this.plugin.cfg().loginWindowMillis);
    }

    public void setVerifier(final PasswordVerifier verifier) {
        this.verifier = verifier == null ? PasswordVerifier.NONE : verifier;
    }

    public LoginGuard guard() {
        return this.guard;
    }

    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** Handler thread. */
    public void handle(final JsonObject msg) {
        final String id = str(msg, "id");
        if (id == null || id.isEmpty() || id.length() > 64) {
            return;
        }
        final String name = str(msg, "name");
        final String password = str(msg, "password");
        String ip = str(msg, "ip");
        if (ip == null || ip.isBlank() || ip.length() > 64) {
            ip = "?";
        }
        final String clientIp = ip;
        if (name == null || !Params.NICK.matcher(name).matches() || password == null || password.isEmpty() || password.length() > 128) {
            this.plugin.send(Protocol.authFail(id, WRONG));
            return;
        }
        final LoginGuard g = this.guard;
        if (this.refuseBlocked(id, name, clientIp, g)) {
            return;
        }
        try {
            this.authExecutor.execute(() -> this.check(id, name, password, clientIp, g));
        } catch (final RejectedExecutionException e) {
            this.plugin.send(Protocol.authFail(id, "Serwer gry się wyłącza."));
        }
    }

    /**
     * Refuses (and audits) a blocked nick or IP. Checked when the message arrives and again on the auth thread right before
     * the password check: a burst of attempts queued before the first failures were recorded must not bypass the limit.
     */
    private boolean refuseBlocked(final String id, final String name, final String ip, final LoginGuard g) {
        final String refusal = blockedMessage(g, name, ip);
        if (refusal == null) {
            return false;
        }
        this.plugin.send(Protocol.authFail(id, refusal));
        this.audit(null, name, ip, "login", false, "zablokowane (limit prób)");
        return true;
    }

    /** Polish refusal when the nick or IP reached the failed-login limit, else null. */
    static String blockedMessage(final LoginGuard g, final String name, final String ip) {
        final long wait = g.blockedFor(name, ip);
        if (wait <= 0) {
            return null;
        }
        final long minutes = Math.max(1, (wait + 59_999) / 60_000);
        return "Za dużo nieudanych prób logowania. Spróbuj ponownie za " + minutes + " min.";
    }

    private void check(final String id, final String name, final String password, final String ip, final LoginGuard g) {
        if (this.refuseBlocked(id, name, ip, g)) {
            return;
        }
        final PasswordVerifier.Result result = this.verifier.verify(name, password);
        switch (result) {
            case UNAVAILABLE -> this.plugin.send(Protocol.authFail(id,
                "Logowanie hasłem jest chwilowo niedostępne. Wpisz w grze /panel, aby dostać link logowania."));
            case WRONG, NOT_REGISTERED -> {
                g.failure(name, ip);
                this.plugin.send(Protocol.authFail(id, WRONG));
                this.audit(null, name, ip, "login", false, result == PasswordVerifier.Result.WRONG ? "złe hasło" : "brak konta");
            }
            case OK -> {
                final String canonical = this.verifier.canonicalName(name);
                try {
                    Bukkit.getScheduler().runTask(this.plugin, () -> this.finish(id, canonical, ip, g));
                } catch (final IllegalPluginAccessException e) {
                    this.plugin.send(Protocol.authFail(id, "Serwer gry się wyłącza."));
                }
            }
        }
    }

    /** Main thread: UUID and exact name from the server cache, ban check. */
    private void finish(final String id, final String name, final String ip, final LoginGuard g) {
        UUID uuid = null;
        String exact = name;
        final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            uuid = cached.getUniqueId();
            exact = cached.getName() == null ? name : cached.getName();
        } else if (!Bukkit.getOnlineMode()) {
            uuid = offlineUuid(name);
        }
        if (uuid == null) {
            this.plugin.send(Protocol.authFail(id, "Najpierw wejdź na serwer, a potem zaloguj się w panelu."));
            return;
        }
        if (Bukkit.getOfflinePlayer(uuid).isBanned()) {
            this.plugin.send(Protocol.authFail(id, "To konto jest zablokowane na serwerze."));
            this.audit(uuid, exact, ip, "login", false, "ban");
            return;
        }
        g.success(name);
        this.plugin.send(Protocol.authOk(id, uuid, exact));
        this.audit(uuid, exact, ip, "login", true, null);
    }

    /** UUID of an offline-mode player ({@code OfflinePlayer:<name>}, case-sensitive like the server). */
    public static UUID offlineUuid(final String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** 32 random characters [A-Za-z0-9] (≈190 bits). */
    public String newToken() {
        final StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(ALPHABET.charAt(this.random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private void audit(final UUID uuid, final String name, final String ip, final String action, final boolean ok, final String error) {
        this.plugin.audit().log(new AuditLog.Entry(System.currentTimeMillis(), uuid == null ? null : uuid.toString(), name, ip, action,
            null, ok, error));
    }
}
