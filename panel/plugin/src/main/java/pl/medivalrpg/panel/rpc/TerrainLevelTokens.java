// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import com.google.gson.JsonObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded, single-use confirmations. No world changes may be requested by browser-supplied preview data. */
final class TerrainLevelTokens {
    static final long TTL_MILLIS = 120_000L;
    private static final int MAX_PENDING = 512;

    record Request(String world, int minX, int minZ, int maxX, int maxZ, Integer targetY, boolean free) {
        static Request parse(final Params p, final String capitalWorld, final boolean admin) {
            final String world = p.optString("world", 1, 128, capitalWorld);
            if (!Objects.equals(world, capitalWorld)) throw new RpcException("Wybierz teren w głównym świecie tego kraju.");
            final int x1 = p.integer("minX", -29_999_000, 29_999_000);
            final int x2 = p.integer("maxX", -29_999_000, 29_999_000);
            final int z1 = p.integer("minZ", -29_999_000, 29_999_000);
            final int z2 = p.integer("maxZ", -29_999_000, 29_999_000);
            final long area = (Math.abs((long) x2 - x1) + 1) * (Math.abs((long) z2 - z1) + 1);
            if (area > 4096) throw new RpcException("Zaznacz najwyżej 4096 kolumn terenu naraz.");
            final Integer target = p.has("targetY") ? p.integer("targetY", -2048, 4096) : null;
            final boolean free = p.has("free") && p.bool("free");
            if (free && !admin) throw new RpcException("Darmowe wyrównywanie jest dostępne tylko dla administratora.");
            return new Request(world, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2), target, free);
        }

        Request resolved(final int height) {
            if (this.targetY != null && this.targetY != height) throw new RpcException("Podgląd zwrócił inną wysokość niż wybrana.");
            return new Request(this.world, this.minX, this.minZ, this.maxX, this.maxZ, height, this.free);
        }
    }

    record Pending(String token, UUID actor, UUID country, Request request, long expiresAt, String signature) { }
    private final Map<String, Pending> entries = new LinkedHashMap<>();

    Pending issue(final UUID actor, final UUID country, final Request request, final String signature, final long now) {
        this.entries.values().removeIf(p -> p.expiresAt() <= now || p.actor().equals(actor) && p.country().equals(country));
        if (this.entries.size() >= MAX_PENDING) {
            final Iterator<String> oldest = this.entries.keySet().iterator();
            oldest.next(); oldest.remove();
        }
        final String token = UUID.randomUUID().toString();
        final Pending pending = new Pending(token, actor, country, request, now + TTL_MILLIS, signature);
        this.entries.put(token, pending);
        return pending;
    }

    Pending consume(final String token, final UUID actor, final UUID country, final long now) {
        final Pending pending = this.entries.get(token);
        if (pending == null || pending.expiresAt() <= now) {
            this.entries.remove(token);
            throw new RpcException("Podgląd wyrównywania wygasł. Przygotuj go ponownie.");
        }
        if (!pending.actor().equals(actor) || !pending.country().equals(country)) throw RpcException.denied();
        this.entries.remove(token);
        return pending;
    }

    /** Inputs whose changes require a new visible confirmation, including amount charged to the treasury. */
    static String signature(final JsonObject preview) {
        final JsonObject signed = new JsonObject();
        for (final String key : new String[] {"world", "minX", "minZ", "maxX", "maxZ", "targetY", "columns", "changedColumns", "blocks", "cost"}) {
            if (!preview.has(key) || preview.get(key).isJsonNull()) throw new RpcException("Niepełny podgląd wyrównywania terenu.");
            signed.add(key, preview.get(key));
        }
        return signed.toString();
    }
}
