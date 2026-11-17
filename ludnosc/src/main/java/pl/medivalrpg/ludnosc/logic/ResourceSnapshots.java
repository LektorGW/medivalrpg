// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Published by the main thread; readers only receive deeply immutable maps, without Bukkit calls. */
public final class ResourceSnapshots {
    private final Map<UUID,Map<String,Long>> countries=new ConcurrentHashMap<>();
    public void publish(UUID country,Map<String,Long> legacy,Map<String,Long> physical) {
        final Map<String,Long> merged=new HashMap<>(legacy);
        physical.forEach((key,value) -> merged.merge(key,value,Long::sum));
        countries.put(country,Map.copyOf(merged));
    }
    public Map<String,Long> read(UUID country) { return country==null ? Map.of() : countries.getOrDefault(country,Map.of()); }
}
