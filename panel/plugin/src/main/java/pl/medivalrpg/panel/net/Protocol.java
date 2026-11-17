// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.net;

import java.util.Map;
import java.util.UUID;
import pl.medivalrpg.panel.util.Json;

/**
 * Messages plugin → Durable Object (PANEL.md, field {@code t}). Built as ordered maps and serialized with
 * {@link Json#GSON}. Optional fields are omitted rather than sent as null.
 */
public final class Protocol {

    public static final int VERSION = 1;

    private Protocol() {
    }

    public static Map<String, Object> hello() {
        return Json.obj("t", "hello", "v", VERSION);
    }

    public static Map<String, Object> authOk(final String id, final UUID uuid, final String name) {
        return Json.obj("t", "auth-result", "id", id, "ok", true, "uuid", uuid.toString(), "name", name);
    }

    public static Map<String, Object> authFail(final String id, final String error) {
        return Json.obj("t", "auth-result", "id", id, "ok", false, "error", error);
    }

    public static Map<String, Object> linkToken(final String token, final UUID uuid, final String name) {
        return Json.obj("t", "link-token", "token", token, "uuid", uuid.toString(), "name", name);
    }

    /** {@code data} may be any Gson-serializable value (maps, lists, strings, numbers, booleans, null). */
    public static Map<String, Object> rpcOk(final String id, final Object data) {
        return Json.obj("t", "rpc-result", "id", id, "ok", true, "data", data);
    }

    public static Map<String, Object> rpcFail(final String id, final String error) {
        return Json.obj("t", "rpc-result", "id", id, "ok", false, "error", error);
    }

    public static Map<String, Object> revoke(final UUID uuid) {
        return Json.obj("t", "revoke", "uuid", uuid.toString());
    }

    public static String toJson(final Map<String, Object> message) {
        return Json.GSON.toJson(message);
    }
}
