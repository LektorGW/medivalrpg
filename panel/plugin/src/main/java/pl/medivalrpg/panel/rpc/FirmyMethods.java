// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import com.google.gson.JsonElement;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;

/**
 * Optional bridge to the Firmy plugin (player companies, page {@code #/firmy}) by reflection, like {@link RailwayMethods}:
 * {@code FirmyPlugin.firmyPanel(UUID actor, String method, Map<String,String> args)} on the main thread. Firmy decides access
 * on EVERY call (public ranking and cards, company members, the owner WorldSteward); the Panel only forwards the logged-in UUID
 * and a whitelist of primitive parameters.
 */
final class FirmyMethods {

    /** Reads (not audited, never cached by the Worker or the SPA). */
    static final List<String> READS = MethodSpec.FIRMY_READS;
    /** Mutations (audited, mutation rate limit). */
    static final List<String> WRITES = MethodSpec.FIRMY_WRITES;
    /** Parameters forwarded to Firmy; anything else is dropped. */
    static final List<String> KEYS = List.of("id", "sort", "frozen", "reason", "approve", "note", "winner", "page", "query", "kind");
    static final int MAX_VALUE = 2048;

    static final String UNAVAILABLE = "Firmy są obecnie niedostępne.";
    static final String OUTDATED = "Zainstalowane Firmy nie obsługują jeszcze panelu WWW.";
    static final String FAILED = "Firmy nie mogły wykonać operacji.";
    static final String BAD_PARAM = "Nieprawidłowy parametr firmy.";

    private FirmyMethods() {
    }

    private static Object firmy() {
        final var plugin = Bukkit.getPluginManager().getPlugin("Firmy");
        return plugin == null || !plugin.isEnabled() ? null : plugin;
    }

    static Object invoke(final Call call) {
        return invoke(call, firmy());
    }

    /** Testable core of {@link #invoke(Call)}; {@code firmy} null = Firmy missing or disabled. */
    static Object invoke(final Call call, final Object firmy) {
        if (firmy == null) {
            throw new RpcException(UNAVAILABLE);
        }
        final Map<String, String> args = args(call.params);
        final java.lang.reflect.Method method;
        try {
            method = firmy.getClass().getMethod("firmyPanel", UUID.class, String.class, Map.class);
        } catch (final NoSuchMethodException e) {
            throw new RpcException(OUTDATED);
        }
        try {
            return method.invoke(firmy, call.actor.uuid(), call.method, args);
        } catch (final InvocationTargetException e) {
            throw error(e.getCause());
        } catch (final ReflectiveOperationException e) {
            throw new RpcException(OUTDATED);
        }
    }

    /** A Firmy refusal (IllegalArgumentException) keeps its Polish message; anything else becomes a generic error. */
    static RpcException error(final Throwable cause) {
        if (cause instanceof IllegalArgumentException && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return new RpcException(cause.getMessage());
        }
        return cause instanceof RpcException re ? re : new RpcException(FAILED);
    }

    /** Whitelisted primitive parameters (as strings, at most {@link #MAX_VALUE} characters); an object or array refuses the call. */
    static Map<String, String> args(final Params params) {
        final Map<String, String> args = new LinkedHashMap<>();
        for (final String key : KEYS) {
            final JsonElement value = params.element(key);
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (!value.isJsonPrimitive()) {
                throw new RpcException(BAD_PARAM);
            }
            final String s = value.getAsString();
            if (s.length() > MAX_VALUE) {
                throw new RpcException(BAD_PARAM);
            }
            args.put(key, s);
        }
        return args;
    }
}
