// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import java.util.*;
import java.util.function.LongSupplier;
public final class RateGate {
    private record Window(long start, int count) {}
    private final Map<String,Window> windows = new HashMap<>();
    private final LongSupplier clock;
    public RateGate(LongSupplier clock) { this.clock = clock; }
    public synchronized boolean allow(String key, int limit, long duration) {
        long now = clock.getAsLong();
        windows.entrySet().removeIf(e -> now - e.getValue().start >= 60_000);
        Window old = windows.get(key);
        if (old == null || now - old.start >= duration) old = new Window(now, 0);
        if (old.count >= limit || windows.size() >= 10000 && !windows.containsKey(key)) return false;
        windows.put(key, new Window(old.start, old.count + 1)); return true;
    }
}
