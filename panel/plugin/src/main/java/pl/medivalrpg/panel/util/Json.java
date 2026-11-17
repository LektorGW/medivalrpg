// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Gson (bundled with Paper) and a tiny ordered-map builder for responses. */
public final class Json {

    /** Nulls are kept ({@code "country": null}); no HTML escaping (the browser renders text, never HTML). */
    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private Json() {
    }

    /** {@code obj("a", 1, "b", "x")} → ordered map. Keys must be strings; odd arguments are an error. */
    public static Map<String, Object> obj(final Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("obj() needs key/value pairs");
        }
        final Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
