// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.panel.Messages;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.access.Access;
import pl.medivalrpg.panel.access.Actor;
import pl.medivalrpg.panel.audit.AuditLog;
import pl.medivalrpg.panel.auth.RateLimiter;
import pl.medivalrpg.panel.net.Protocol;
import pl.medivalrpg.panel.util.Json;

/**
 * {@code rpc} messages from the Durable Object. The message is parsed on the handler thread, then executed on the
 * main server thread with a timeout: whichever comes first — the main-thread task starting or the timeout — wins,
 * so a call that timed out is never executed later. Permissions are recomputed for every call ({@link Actor}).
 */
public final class RpcService {

    private final PanelPlugin plugin;
    private final PanelMethods methods;
    private final ScheduledExecutorService timer;
    private volatile RateLimiter mutations;

    public RpcService(final PanelPlugin plugin, final ScheduledExecutorService timer) {
        this.plugin = plugin;
        this.methods = new PanelMethods(plugin);
        this.timer = timer;
        this.reload();
    }

    public void reload() {
        this.mutations = new RateLimiter(this.plugin.cfg().mutationsPerMinute, 60_000L);
    }

    /** Parsed {@code rpc} envelope. */
    record Request(String id, UUID uuid, String name, String ip, String method, JsonElement params) {
    }

    /** Validates the envelope; throws {@link RpcException} (with the id when known). */
    static Request parse(final JsonObject msg) {
        final String id = str(msg, "id");
        if (id == null || id.isEmpty() || id.length() > 64) {
            throw new RpcException("Brak identyfikatora wywołania.");
        }
        final JsonElement u = msg.get("user");
        if (u == null || !u.isJsonObject()) {
            throw new RpcException("Brak użytkownika.");
        }
        final JsonObject user = u.getAsJsonObject();
        final UUID uuid;
        try {
            uuid = UUID.fromString(str(user, "uuid"));
        } catch (final RuntimeException e) {
            throw new RpcException("Nieprawidłowy użytkownik.");
        }
        String name = str(user, "name");
        if (name == null || !Params.NICK.matcher(name).matches()) {
            name = "?";
        }
        String ip = str(user, "ip");
        if (ip == null) {
            ip = str(msg, "ip");
        }
        if (ip == null || ip.length() > 64) {
            ip = "?";
        }
        final String method = str(msg, "method");
        if (method == null || method.length() > 64) {
            throw new RpcException("Brak nazwy metody.");
        }
        return new Request(id, uuid, name, ip, method, msg.get("params"));
    }

    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** Handler thread. */
    public void handle(final JsonObject msg) {
        final Request req;
        try {
            req = parse(msg);
        } catch (final RpcException e) {
            final String id = str(msg, "id");
            if (id != null) {
                this.plugin.send(Protocol.rpcFail(id, e.getMessage()));
            }
            return;
        }
        final MethodSpec spec = MethodSpec.of(req.method());
        if (spec == null) {
            this.plugin.send(Protocol.rpcFail(req.id(), "Nieznana metoda: " + req.method()));
            return;
        }
        final Params params;
        try {
            params = Params.of(req.params());
        } catch (final RpcException e) {
            this.plugin.send(Protocol.rpcFail(req.id(), e.getMessage()));
            return;
        }
        final AtomicBoolean claimed = new AtomicBoolean();
        try {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (claimed.compareAndSet(false, true)) {
                    final java.util.Map<String, Object> result = this.execute(spec, req, params);
                    if (result != null) {
                        this.plugin.send(result);
                    }
                }
            });
        } catch (final IllegalPluginAccessException e) {
            this.plugin.send(Protocol.rpcFail(req.id(), "Serwer gry się wyłącza."));
            return;
        }
        this.timer.schedule(() -> {
            if (claimed.compareAndSet(false, true)) {
                this.plugin.send(Protocol.rpcFail(req.id(), "Serwer gry nie odpowiedział na czas (duże obciążenie). Spróbuj ponownie."));
            }
        }, this.plugin.cfg().rpcTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Main thread: permission check, method, audit and notifications. Returns the {@code rpc-result} message, or null when
     * the method answered with a future (the result is sent when it completes).
     */
    java.util.Map<String, Object> execute(final MethodSpec spec, final Request req, final Params params) {
        Actor actor = null;
        try {
            actor = this.plugin.roles().actor(req.uuid(), req.name());
            KrajInfo target = null;
            if (spec.targetsId()) {
                target = this.plugin.kraje().getCountry(params.uuid("id")).orElseThrow(() -> new RpcException("Nie ma takiego kraju."));
                if (spec.npcOnly() && !target.npc()) {
                    throw new RpcException("To nie jest państwo NPC.");
                }
            }
            if (!Access.allowed(actor, spec.requirement(), target == null ? null : target.id(), target != null && target.npc())) {
                throw RpcException.denied();
            }
            if (spec.mutation() && !this.mutations.tryAcquire(req.uuid().toString())) {
                throw new RpcException("Za dużo zmian w krótkim czasie — odczekaj chwilę.");
            }
            final Call call = new Call(spec.name(), actor, params, target, req.ip());
            final Object data = this.methods.invoke(call);
            if (data instanceof java.util.concurrent.CompletableFuture<?> future) {
                // Asynchronous method (e.g. a village search): answer when it completes, audit then.
                final Actor who = actor;
                future.whenComplete((value, error) -> {
                    final Throwable cause = error instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null ? ce.getCause() : error;
                    if (cause == null) {
                        if (spec.mutation()) {
                            this.audit(req, who, params, true, null);
                            this.notifyMembersOnMain(call);
                        }
                        this.plugin.send(Protocol.rpcOk(req.id(), value));
                    } else {
                        final String msg = cause instanceof RpcException re ? re.getMessage() : "Wewnętrzny błąd serwera gry.";
                        if (!(cause instanceof RpcException)) {
                            this.plugin.getLogger().log(Level.SEVERE, "Błąd metody panelu " + spec.name(), cause);
                        }
                        if (spec.mutation()) {
                            this.audit(req, who, params, false, msg);
                        }
                        this.plugin.send(Protocol.rpcFail(req.id(), msg));
                    }
                });
                return null;
            }
            if (spec.mutation()) {
                this.audit(req, actor, params, true, null);
                this.notifyMembers(call);
            }
            return Protocol.rpcOk(req.id(), data);
        } catch (final RpcException e) {
            if (spec.mutation()) {
                this.audit(req, actor, params, false, e.getMessage());
            }
            return Protocol.rpcFail(req.id(), e.getMessage());
        } catch (final RuntimeException | LinkageError e) {
            this.plugin.getLogger().log(Level.SEVERE, "Błąd metody panelu " + spec.name(), e);
            if (spec.mutation()) {
                this.audit(req, actor, params, false, "błąd serwera: " + e.getClass().getSimpleName());
            }
            return Protocol.rpcFail(req.id(), "Wewnętrzny błąd serwera gry.");
        }
    }

    private void audit(final Request req, final Actor actor, final Params params, final boolean ok, final String error) {
        final String name = actor == null ? req.name() : actor.name();
        this.plugin.audit().log(new AuditLog.Entry(System.currentTimeMillis(), req.uuid().toString(), name, req.ip(), req.method(),
            AuditLog.truncate(req.params() == null ? "{}" : Json.GSON.toJson(FlagMethods.auditParams(req.method(), req.params()))), ok, error));
        if (ok) {
            this.plugin.getLogger().info("[Panel] " + name + " (" + req.ip() + "): " + req.method());
        }
    }

    /**
     * Asynchronous answers complete on any thread (Skarbiec journal, map encoder, chunk loads); the notification reads
     * Kraje and online players, so it always runs on the main thread. Nothing is scheduled when there is no notice.
     */
    private void notifyMembersOnMain(final Call call) {
        if (call.notices().isEmpty()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            this.notifyMembers(call);
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this.plugin, () -> this.notifyMembers(call));
        } catch (final IllegalPluginAccessException e) {
            // plugin disabled: no in-game notification
        }
    }

    private void notifyMembers(final Call call) {
        if (!this.plugin.cfg().notifyInGame) {
            return;
        }
        final Messages m = this.plugin.messages();
        final String key = "notify." + call.method.replace('.', '-');
        if (!m.has(key)) {
            return;
        }
        for (final Call.Notice n : call.notices()) {
            final KrajInfo k = this.plugin.kraje().getCountry(n.country()).orElse(null);
            if (k == null) {
                continue;
            }
            for (final UUID member : k.members().keySet()) {
                final Player p = Bukkit.getPlayer(member);
                if (p != null) {
                    m.send(p, key, Messages.text("player", call.actor.name()), Messages.text("country", k.name()),
                        Messages.text("target", n.target()), Messages.text("value", n.value()));
                }
            }
        }
    }

    public void cleanup() {
        this.mutations.cleanup();
    }
}
