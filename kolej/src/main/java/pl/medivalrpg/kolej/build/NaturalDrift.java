// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Self-changing vanilla states between the offline survey and the live build (leaf distance, kelp growth, snowy soil,
 * mushroom faces, plant age...). Single source of truth: {@code natural-drift.json}, shared with the plan generator
 * ({@code pipeline/natural_drift.py}). Same coordinate and block family only; a material change other than a listed
 * growth pair of one plant, a different value of a fixed property, or a player-placed state never matches.
 * A missing or broken resource fails closed: only identical states match.
 */
public final class NaturalDrift {
    record Family(String id, Set<String> types, boolean interchangeable, Set<String> mutable, Map<String, String> require) { }
    private static final Map<String, Family> BY_TYPE = load();
    private NaturalDrift() { }

    static Map<String, Family> load() {
        try (InputStream in = NaturalDrift.class.getResourceAsStream("/natural-drift.json")) {
            if (in == null) return Map.of();
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.get("version").getAsInt() != 1) return Map.of();
            Map<String, Family> out = new HashMap<>();
            for (JsonElement e : json.getAsJsonArray("families")) {
                JsonObject f = e.getAsJsonObject();
                Set<String> types = strings(f.getAsJsonArray("types")), mutable = strings(f.getAsJsonArray("mutable"));
                Map<String, String> require = new HashMap<>();
                if (f.has("require")) for (var r : f.getAsJsonObject("require").entrySet()) require.put(r.getKey(), r.getValue().getAsString());
                Family family = new Family(f.get("id").getAsString(), types, f.has("interchangeable") && f.get("interchangeable").getAsBoolean(), mutable, Map.copyOf(require));
                for (String type : types) if (out.put(type, family) != null) return Map.of(); // ambiguous resource: fail closed
            }
            return Map.copyOf(out);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> out = new HashSet<>();
        for (var e : array) out.add(e.getAsString());
        return Set.copyOf(out);
    }

    public static boolean loaded() { return !BY_TYPE.isEmpty(); }

    record State(String type, Map<String, String> props) {
        static State parse(String data) {
            if (data == null || data.length() > 512 || !data.matches("minecraft:[a-z0-9_]+(?:\\[[a-z0-9_=,]+])?")) return null;
            int bracket = data.indexOf('[');
            Map<String, String> props = new HashMap<>();
            if (bracket >= 0) for (String item : data.substring(bracket + 1, data.length() - 1).split(",", -1)) {
                String[] pair = item.split("=", -1);
                if (pair.length != 2 || pair[1].isEmpty() || props.putIfAbsent(pair[0], pair[1]) != null) return null;
            }
            return new State(data.substring(10, bracket < 0 ? data.length() : bracket), props);
        }
    }

    /** True when {@code live} is {@code expected} after natural self-change only (identical states always match). */
    public static boolean same(String expected, String live) {
        if (expected != null && expected.equals(live)) return true;
        State a = State.parse(expected), b = State.parse(live);
        if (a == null || b == null) return false;
        Family family = BY_TYPE.get(a.type());
        if (family == null) return false;
        if (!a.type().equals(b.type()) && !(family.interchangeable() && family.types().contains(b.type()))) return false;
        for (var r : family.require().entrySet()) {
            if (!r.getValue().equals(a.props().get(r.getKey())) || !r.getValue().equals(b.props().get(r.getKey()))) return false;
        }
        Set<String> keys = new HashSet<>(a.props().keySet());
        keys.addAll(b.props().keySet());
        for (String key : keys) {
            if (family.mutable().contains(key)) continue;
            if (!Objects.equals(a.props().get(key), b.props().get(key))) return false;
        }
        return true;
    }
}
