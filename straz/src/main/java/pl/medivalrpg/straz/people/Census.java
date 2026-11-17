// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.people;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Citizens of Ludnosc for the daily report (pure): births from the life records ({@code zycie-obywateli.json},
 * field {@code born}), departures as the difference to the previous report's register (old-age deaths, killed,
 * conscripted and departed citizens all leave the register), finished buildings and running construction sites
 * from {@code data.json}.
 */
public final class Census {

    /** Parsed files. */
    public record Data(Set<String> citizens, List<Long> born, List<Building> buildings, int sites) {
    }

    /** A finished building. */
    public record Building(String type, long createdAt, boolean registered) {
    }

    /** Report numbers. {@code removed}/{@code arrived} are -1 without a previous register. */
    public record Stats(int citizens, int births, int removed, int arrived, int buildingsFinished,
                        Map<String, Integer> buildingTypes, int sitesActive) {
    }

    private Census() {
    }

    public static Data parse(final String dataJson, final String lifeJson) {
        final JsonObject root = JsonParser.parseString(dataJson).getAsJsonObject();
        final Set<String> ids = new HashSet<>();
        if (root.has("citizens")) {
            for (final JsonElement e : root.getAsJsonArray("citizens")) {
                final JsonObject c = e.getAsJsonObject();
                if (c.has("entityId")) {
                    ids.add(c.get("entityId").getAsString());
                }
            }
        }
        final List<Building> buildings = new ArrayList<>();
        if (root.has("buildings")) {
            for (final JsonElement e : root.getAsJsonArray("buildings")) {
                final JsonObject b = e.getAsJsonObject();
                buildings.add(new Building(b.has("type") ? b.get("type").getAsString() : "?",
                    b.has("createdAt") ? b.get("createdAt").getAsLong() : 0,
                    b.has("registered") && b.get("registered").getAsBoolean()));
            }
        }
        final int sites = root.has("sites") ? root.getAsJsonArray("sites").size() : 0;
        final List<Long> born = new ArrayList<>();
        if (lifeJson != null && !lifeJson.isBlank()) {
            final JsonObject life = JsonParser.parseString(lifeJson).getAsJsonObject();
            if (life.has("citizens")) {
                for (final Map.Entry<String, JsonElement> e : life.getAsJsonObject("citizens").entrySet()) {
                    final JsonObject l = e.getValue().getAsJsonObject();
                    if (l.has("born")) {
                        final long b = l.get("born").getAsLong();
                        if (b > 0) {
                            born.add(b);
                        }
                    }
                }
            }
        }
        return new Data(Set.copyOf(ids), List.copyOf(born), List.copyOf(buildings), sites);
    }

    /**
     * @param previous register of the previous report (null on the first report)
     */
    public static Stats stats(final Data now, final Set<String> previous, final long fromMs, final long toMs) {
        int births = 0;
        for (final long b : now.born()) {
            if (b >= fromMs && b <= toMs) {
                births++;
            }
        }
        int removed = -1, arrived = -1;
        if (previous != null) {
            removed = 0;
            for (final String id : previous) {
                if (!now.citizens().contains(id)) {
                    removed++;
                }
            }
            int added = 0;
            for (final String id : now.citizens()) {
                if (!previous.contains(id)) {
                    added++;
                }
            }
            arrived = Math.max(0, added - births);
        }
        final Map<String, Integer> types = new TreeMap<>();
        int finished = 0;
        for (final Building b : now.buildings()) {
            if (!b.registered() && b.createdAt() >= fromMs && b.createdAt() <= toMs) {
                finished++;
                types.merge(b.type(), 1, Integer::sum);
            }
        }
        return new Stats(now.citizens().size(), births, removed, arrived, finished, Map.copyOf(types), now.sites());
    }
}
