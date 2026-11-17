// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.kolej;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to Kolej's data folder (I/O thread). {@code jobs.json} has millions of block entries, so it is
 * streamed and only counts are kept. A damaged file falls back to its {@code .bak}.
 */
public final class RailReader {

    /** Parsed request. */
    public record Request(List<String> parts, long maxTotalMd, boolean enabled) {
    }

    private RailReader() {
    }

    public static Request request(final Path kolejDir) throws IOException {
        final Path file = kolejDir.resolve("network-commission").resolve("request.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        final JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        final List<String> parts = new ArrayList<>();
        if (root.has("parts") && root.get("parts").isJsonArray()) {
            for (final JsonElement e : root.getAsJsonArray("parts")) {
                if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                    parts.add(e.getAsJsonObject().get("id").getAsString());
                }
            }
        }
        final long max = root.has("maxTotalMd") ? root.get("maxTotalMd").getAsLong() : 0;
        final boolean enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
        return new Request(List.copyOf(parts), max, enabled);
    }

    public static Map<String, RailSummary.Job> jobs(final Path kolejDir) throws IOException {
        final Path file = kolejDir.resolve("works").resolve("jobs.json");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            return readJobs(file);
        } catch (final IOException | RuntimeException e) {
            final Path bak = file.resolveSibling("jobs.json.bak");
            if (Files.isRegularFile(bak)) {
                return readJobs(bak);
            }
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    static Map<String, RailSummary.Job> readJobs(final Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return readJobs(r);
        }
    }

    /** Streams {@code {"version":1,"jobs":[{...}]}} keeping id/status/cursor/cost/reason and the entry count. */
    public static Map<String, RailSummary.Job> readJobs(final Reader source) throws IOException {
        final Map<String, RailSummary.Job> out = new LinkedHashMap<>();
        try (JsonReader in = new JsonReader(source)) {
            in.beginObject();
            while (in.hasNext()) {
                final String name = in.nextName();
                if (!"jobs".equals(name)) {
                    in.skipValue();
                    continue;
                }
                in.beginArray();
                while (in.hasNext()) {
                    String id = null, status = "", reason = "";
                    int cursor = 0, entries = 0;
                    long cost = 0;
                    in.beginObject();
                    while (in.hasNext()) {
                        final String f = in.nextName();
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                            continue;
                        }
                        switch (f) {
                            case "id" -> id = in.nextString();
                            case "status" -> status = in.nextString();
                            case "reason" -> reason = in.nextString();
                            case "cursor" -> cursor = in.nextInt();
                            case "cost" -> cost = in.nextLong();
                            case "entries" -> {
                                in.beginArray();
                                while (in.hasNext()) {
                                    in.skipValue();
                                    entries++;
                                }
                                in.endArray();
                            }
                            default -> in.skipValue();
                        }
                    }
                    in.endObject();
                    if (id != null) {
                        out.put(id, new RailSummary.Job(id, status, cursor, entries, cost, reason));
                    }
                }
                in.endArray();
            }
            in.endObject();
        }
        return out;
    }
}
