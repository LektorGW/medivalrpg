// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.visual;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BetterModel-free mailbox for visual events raised outside the runtime (e.g. freight transfers).
 * Safe to call when BetterModel is absent; TrainModels consumes each stamp once per car tracker.
 */
public final class AnimationRequests {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<String, Long> FREIGHT_DOORS = new ConcurrentHashMap<>();
    private static final int LIMIT = 256;
    private AnimationRequests() { }
    /** Freight load/unload of a train: covered wagons open their sliding doors once. */
    public static void freightDoors(String train) {
        if (train == null || train.isEmpty()) return;
        if (FREIGHT_DOORS.size() >= LIMIT && !FREIGHT_DOORS.containsKey(train)) FREIGHT_DOORS.clear(); // bounded; stale stamps are harmless
        FREIGHT_DOORS.put(train, SEQUENCE.incrementAndGet());
    }
    /** 0 = never requested. */
    public static long freightDoorsStamp(String train) { return train == null ? 0 : FREIGHT_DOORS.getOrDefault(train, 0L); }
    /** Train id of a car tracker key "<train>/car<i>", or null. */
    public static String trainOfCar(String key) {
        int i = key == null ? -1 : key.lastIndexOf("/car");
        return i <= 0 ? null : key.substring(0, i);
    }
    public static void clear() { FREIGHT_DOORS.clear(); }
}
