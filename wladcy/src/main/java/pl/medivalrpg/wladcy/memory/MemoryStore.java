// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Memories of every ruler about every player, keyed {@code "<countryId>|<playerUuid>"}. Main thread only; saved
 * through {@link #snapshot()} by the plugin's JSON writer.
 */
public final class MemoryStore {

    private final Map<String, PairMemory> pairs;
    private boolean dirty;

    public MemoryStore(final Map<String, PairMemory> loaded) {
        this.pairs = new HashMap<>();
        if (loaded != null) {
            loaded.forEach((k, v) -> {
                if (k != null && v != null) {
                    v.normalize();
                    this.pairs.put(k, v);
                }
            });
        }
    }

    public static String key(final UUID country, final UUID player) {
        return country + "|" + player;
    }

    /** Existing or new memory (marks the store dirty, the caller is about to change it). */
    public PairMemory get(final UUID country, final UUID player) {
        this.dirty = true;
        return this.pairs.computeIfAbsent(key(country, player), k -> new PairMemory());
    }

    /** Read-only lookup. */
    public PairMemory peek(final UUID country, final UUID player) {
        return this.pairs.get(key(country, player));
    }

    /** Forgets everything about a disbanded country. */
    public void forgetCountry(final UUID country) {
        final String prefix = country + "|";
        if (this.pairs.keySet().removeIf(k -> k.startsWith(prefix))) {
            this.dirty = true;
        }
    }

    public int size() {
        return this.pairs.size();
    }

    public boolean dirty() {
        return this.dirty;
    }

    /** Deep enough copy for writing on another thread (lists copied). Clears the dirty flag. */
    public Map<String, PairMemory> snapshot() {
        this.dirty = false;
        final Map<String, PairMemory> out = new HashMap<>();
        for (final Map.Entry<String, PairMemory> e : this.pairs.entrySet()) {
            final PairMemory s = e.getValue();
            final PairMemory c = new PairMemory();
            c.playerName = s.playerName;
            c.sympathy = s.sympathy;
            c.conversations = s.conversations;
            c.tributesPaid = s.tributesPaid;
            c.tributePaidTotal = s.tributePaidTotal;
            c.tributesRefused = s.tributesRefused;
            c.giftsReceived = s.giftsReceived;
            c.giftTotal = s.giftTotal;
            c.warsDeclared = s.warsDeclared;
            c.alliancesFormed = s.alliancesFormed;
            c.peacesMade = s.peacesMade;
            c.lastTalkAt = s.lastTalkAt;
            for (final PairMemory.Exchange x : s.exchanges) {
                c.exchanges.add(new PairMemory.Exchange(x.at, x.player, x.ruler));
            }
            c.notes.addAll(s.notes);
            out.put(e.getKey(), c);
        }
        return out;
    }
}
