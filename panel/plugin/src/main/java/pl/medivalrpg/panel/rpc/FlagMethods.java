// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

/**
 * Country banners with an uploaded flag image and their moderation — an optional bridge to the Kraje plugin by reflection
 * (no compile dependency, like {@link RailwayMethods}). Kraje decides everything again on every call: the rank in the
 * country, the moderator role ({@code panelAdmin} computed here from the Panel role lists, never from the browser, or
 * the Kraje moderator list) and the image rules. The Panel only whitelists and bounds the parameters.
 */
final class FlagMethods {

    /** Reads (not audited). */
    static final List<String> READS = List.of("country.flag.get", "admin.flag.queue");
    /** Country mutations (advisor or ruler of {@code params.id}; Kraje checks the rank again). */
    static final List<String> COUNTRY_WRITES = List.of("country.flag.upload", "country.flag.withdraw", "country.flag.reset", "country.flag.restore");
    /** Moderator mutations (Kraje checks the moderator role). */
    static final List<String> ADMIN_WRITES = List.of("admin.flag.approve", "admin.flag.reject", "admin.flag.rebuild");
    /** Every banner method. */
    static final List<String> METHODS = List.of("country.flag.get", "country.flag.upload", "country.flag.withdraw", "country.flag.reset",
        "country.flag.restore", "admin.flag.queue", "admin.flag.approve", "admin.flag.reject", "admin.flag.rebuild");

    static final String UNAVAILABLE = "Kraje są obecnie niedostępne.";
    static final String OUTDATED = "Zainstalowany Kraje nie obsługuje jeszcze chorągwi.";
    static final String FAILED = "Chorągwie nie mogły wykonać operacji.";
    static final String BAD_PARAM = "Nieprawidłowy parametr chorągwi.";

    /** Base64 image with an optional data URL prefix (≈ 175 KB of binary data). */
    static final int MAX_DATA = 240_000;
    static final int MAX_REASON = 200;
    static final int MAX_FILENAME = 120;
    private static final Pattern IMAGE_ID = Pattern.compile("[a-f0-9]{1,32}");
    private static final List<String> DATA_PREFIXES = List.of("data:image/png;base64,", "data:image/jpeg;base64,");

    private FlagMethods() {
    }

    private static Object kraje() {
        final var plugin = Bukkit.getPluginManager().getPlugin("Kraje");
        return plugin == null || !plugin.isEnabled() ? null : plugin;
    }

    /** {@code me.roles.flagModerator}; false when Kraje is missing or too old. */
    static boolean moderator(final UUID actor, final boolean panelAdmin) {
        return moderator(kraje(), actor, panelAdmin);
    }

    /** Testable core: {@code kraje} is the Kraje plugin or a stand-in with the same public method. */
    static boolean moderator(final Object kraje, final UUID actor, final boolean panelAdmin) {
        if (kraje == null || actor == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(kraje.getClass().getMethod("flagModerator", UUID.class, boolean.class).invoke(kraje, actor, panelAdmin));
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static Object invoke(final Call call) {
        return invoke(call, kraje());
    }

    /** Testable core of {@link #invoke(Call)}; {@code kraje} null = Kraje missing or disabled. */
    static Object invoke(final Call call, final Object kraje) {
        if (kraje == null) {
            throw new RpcException(UNAVAILABLE);
        }
        final Map<String, String> args = args(call.params);
        final Object result;
        try {
            result = kraje.getClass().getMethod("flagPanel", UUID.class, String.class, boolean.class, String.class, Map.class)
                .invoke(kraje, call.actor.uuid(), call.actor.name(), call.actor.admin(), call.method, args);
        } catch (final InvocationTargetException e) {
            throw error(e.getCause());
        } catch (final ReflectiveOperationException e) {
            throw new RpcException(OUTDATED);
        }
        return unwrap(result);
    }

    /**
     * {@code country.flag.upload} answers with a future (the image is decoded off the main thread): a NEW future is returned
     * whose failure carries the Polish message ({@link RpcService} answers when it completes). Other results pass through.
     */
    static Object unwrap(final Object result) {
        if (result instanceof CompletableFuture<?> future) {
            return future.handle((value, failure) -> {
                if (failure == null) {
                    return value;
                }
                throw error(failure instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : failure);
            });
        }
        return result;
    }

    /** Kraje's rule violation keeps its message; anything else becomes a generic Polish error. */
    static RpcException error(final Throwable cause) {
        if (cause instanceof IllegalArgumentException && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return new RpcException(cause.getMessage());
        }
        return cause instanceof RpcException re ? re : new RpcException(FAILED);
    }

    /** Whitelisted, bounded parameters for Kraje; anything else is dropped, a bad value refuses the call. */
    static Map<String, String> args(final Params params) {
        final Map<String, String> args = new LinkedHashMap<>();
        for (final String key : List.of("id", "imageId", "reason", "filename", "data")) {
            final JsonElement value = params.element(key);
            if (value == null) {
                continue;
            }
            if (!value.isJsonPrimitive()) {
                throw new RpcException(BAD_PARAM);
            }
            final String s = value.getAsString();
            final boolean valid = switch (key) {
                case "id" -> uuid(s);
                case "imageId" -> IMAGE_ID.matcher(s).matches();
                case "reason" -> s.length() <= MAX_REASON;
                case "filename" -> s.length() <= MAX_FILENAME;
                default -> s.length() <= MAX_DATA && base64(s);
            };
            if (!valid) {
                throw new RpcException(BAD_PARAM);
            }
            args.put(key, s);
        }
        return args;
    }

    private static boolean uuid(final String s) {
        if (s.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(s);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    /** Base64 alphabet (with padding), optionally after a PNG/JPEG data URL prefix. */
    static boolean base64(final String s) {
        int from = 0;
        for (final String prefix : DATA_PREFIXES) {
            if (s.startsWith(prefix)) {
                from = prefix.length();
                break;
            }
        }
        if (from == s.length()) {
            return false;
        }
        for (int i = from; i < s.length(); i++) {
            final char c = s.charAt(i);
            final boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/'
                || (c == '=' && i >= s.length() - 2);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Copy of the parameters for audit.log: the uploaded image is replaced by its length, so neither audit.log nor
     * {@code admin.audit} ever holds image data. Other methods are returned unchanged.
     */
    static JsonElement auditParams(final String method, final JsonElement params) {
        if (method == null || !method.startsWith("country.flag.") || params == null || !params.isJsonObject()
            || !params.getAsJsonObject().has("data")) {
            return params;
        }
        final JsonObject copy = params.getAsJsonObject().deepCopy();
        final JsonElement data = copy.get("data");
        final int length = data != null && data.isJsonPrimitive() ? data.getAsString().length() : 0;
        copy.add("data", new JsonPrimitive("<obraz " + length + " znaków>"));
        return copy;
    }
}
