// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import java.util.Map;

/** JSON conversion of the statistics' maps; whole numbers stay {@code Long} when read back. */
final class JsonFilesBridge {

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

    private JsonFilesBridge() {
    }

    static JsonElement toJson(final Object o) {
        return GSON.toJsonTree(o);
    }

    static Map<String, Object> toMap(final JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        return GSON.fromJson(e, new TypeToken<Map<String, Object>>() { }.getType());
    }
}
