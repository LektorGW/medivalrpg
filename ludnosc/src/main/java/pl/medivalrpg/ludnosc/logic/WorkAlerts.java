// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Main-thread, bounded incident ledger. Observations never consume a notification cooldown. */
public final class WorkAlerts {
    public static final int MAX_INCIDENTS = 1024;
    public static final long FRESH_MILLIS = 65_000;
    public static final long RETAIN_MILLIS = 1_800_000;
    public enum Reason { FULL, TORCHES, MISSING_STORE, UNAVAILABLE_STORE, FOOD }
    public record Key(UUID country, UUID workplace) { }
    public record Notice(Key key, Reason reason, String detail, boolean recovery) { }
    private static final class Incident {
        Reason reason;
        String detail;
        long seen, sent;
        boolean active, announced, hasSent;
    }
    private final LinkedHashMap<Key, Incident> incidents = new LinkedHashMap<>();

    public void observe(Key key, Reason reason, String detail, long now) {
        Incident i = incidents.get(key);
        if (i == null) {
            if (incidents.size() >= MAX_INCIDENTS) incidents.remove(incidents.firstEntry().getKey());
            i = new Incident(); incidents.put(key, i);
        }
        i.reason = reason; i.detail = detail; i.seen = now; i.active = true;
    }

    /** Confirmed recovery only; absence, night and unloaded chunks are not recovery. */
    public void recover(Key key, long now) {
        Incident i = incidents.get(key);
        if (i != null && i.active) { i.active = false; i.seen = now; }
    }

    /** Forget removed/transferred/unknown work without making a recovery claim. */
    public void forget(Key key) { incidents.remove(key); }
    public void clear() { incidents.clear(); }

    /** Round-robin bounded processing; failed/offline delivery does not consume the cooldown. */
    public void dispatch(long now, long cooldown, int budget, Predicate<Notice> deliver) {
        final int count = Math.min(Math.max(0, budget), incidents.size());
        for (int n = 0; n < count; n++) {
            Map.Entry<Key, Incident> e = incidents.pollFirstEntry();
            Incident i = e.getValue();
            if (now - i.seen > Math.max(RETAIN_MILLIS, cooldown)) continue;
            incidents.put(e.getKey(), i);
            if (i.active && now - i.seen > FRESH_MILLIS) continue;
            boolean due = i.active ? !i.hasSent || now - i.sent >= cooldown : i.announced;
            if (due && deliver.test(new Notice(e.getKey(), i.reason, i.detail, !i.active))) {
                if (i.active) { i.sent = now; i.hasSent = true; i.announced = true; }
                else i.announced = false;
            }
        }
    }

    public int size() { return incidents.size(); }
}
