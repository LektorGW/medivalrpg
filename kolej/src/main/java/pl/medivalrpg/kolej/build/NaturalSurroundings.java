// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Allow-list of vanilla world-generation states that may stand within 8 m of a hash-pinned natural clearance
 * ({@link NaturalClearance.Policy#auditProblem}). Single source of truth: {@code natural-surroundings.json}, shared with
 * the offline plan generator (deploy-mainline-realistic-20261029/pipeline/natural_surroundings.py). It never authorizes an
 * operation by itself. Unknown, man-made, functional and persistent (player-placed) leaves fail; a missing or broken
 * resource fails closed.
 */
public final class NaturalSurroundings {
    private static final Rules RULES = load();
    private NaturalSurroundings() { }

    record Rules(Set<String> any, Set<String> leaves, Set<String> logs, List<String> suffixes) {
        static final Rules NONE = new Rules(Set.of(), Set.of(), Set.of(), List.of());
    }

    static Rules load() {
        try (InputStream in = NaturalSurroundings.class.getResourceAsStream("/natural-surroundings.json")) {
            if (in == null) return Rules.NONE;
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.get("version").getAsInt() != 1) return Rules.NONE;
            return new Rules(set(json.getAsJsonArray("anyState")), set(json.getAsJsonArray("leaves")), set(json.getAsJsonArray("logs")),
                List.copyOf(set(json.getAsJsonArray("suffixes"))));
        } catch (Exception e) {
            return Rules.NONE;
        }
    }

    private static Set<String> set(JsonArray array) {
        Set<String> out = new HashSet<>();
        for (var e : array) out.add(e.getAsString());
        return Set.copyOf(out);
    }

    /** True when the rules loaded (a broken jar must never widen anything). */
    public static boolean loaded() { return !RULES.any().isEmpty(); }

    /** Canonical live state, e.g. {@code minecraft:azalea_leaves[distance=2,persistent=false,waterlogged=false]}. */
    public static boolean permitted(String data, boolean tileEntity) {
        if (tileEntity || data == null || data.length() > 512 || !data.matches("minecraft:[a-z0-9_]+(?:\\[[a-z0-9_=,]+])?")) return false;
        int bracket = data.indexOf('[');
        String type = data.substring(10, bracket < 0 ? data.length() : bracket);
        Map<String, String> props = new HashMap<>();
        if (bracket >= 0) for (String item : data.substring(bracket + 1, data.length() - 1).split(",", -1)) {
            String[] pair = item.split("=", -1);
            if (pair.length != 2 || pair[1].isEmpty() || props.putIfAbsent(pair[0], pair[1]) != null) return false;
        }
        if (type.endsWith("_leaves")) props.putIfAbsent("persistent", "false");
        if (type.endsWith("_log")) props.putIfAbsent("axis", "y");
        if (RULES.any().contains(type)) return true;
        for (String suffix : RULES.suffixes()) if (type.endsWith(suffix)) return true;
        if (RULES.leaves().contains(type)) return "false".equals(props.get("persistent"));
        if (RULES.logs().contains(type)) return props.keySet().equals(Set.of("axis")) && Set.of("x", "y", "z").contains(props.get("axis"));
        return false;
    }
}
