// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reputation of every player with every country ({@code reputacja.json}), keyed {@code "<countryId>|<playerUuid>"}.
 * Values −100…100 ({@link RepTier}). Gains from play are capped per pair per day (anti-farm); losses and admin
 * changes are not. Main thread only; no Bukkit.
 */
public final class ReputationBook {

    /** JSON shape of one pair. */
    public static final class Entry {
        public int value;
        public String playerName = "";
        public String gainDay = "";
        public int gainedToday;
        public int completed;
        public int failed;
        public int abandoned;
    }

    /** Result of a change: the applied delta (after the daily cap) and the tiers before and after. */
    public record Change(int applied, int value, RepTier before, RepTier after) {
        public boolean tierChanged() {
            return this.before != this.after;
        }
    }

    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean dirty;

    public ReputationBook(final Clock clock, final Map<String, Entry> loaded) {
        this.clock = clock;
        if (loaded != null) {
            loaded.forEach((k, v) -> {
                if (k != null && v != null) {
                    v.value = RepTier.clamp(v.value);
                    if (v.playerName == null) {
                        v.playerName = "";
                    }
                    if (v.gainDay == null) {
                        v.gainDay = "";
                    }
                    this.entries.put(k, v);
                }
            });
        }
    }

    public static String key(final UUID country, final UUID player) {
        return country + "|" + player;
    }

    public int value(final UUID country, final UUID player) {
        final Entry e = this.entries.get(key(country, player));
        return e == null ? 0 : e.value;
    }

    public RepTier tier(final UUID country, final UUID player) {
        return RepTier.of(this.value(country, player));
    }

    /** Read-only copy of the pair (zeros when unknown). */
    public Entry peek(final UUID country, final UUID player) {
        final Entry e = this.entries.get(key(country, player));
        final Entry c = new Entry();
        if (e != null) {
            c.value = e.value;
            c.playerName = e.playerName;
            c.gainDay = e.gainDay;
            c.gainedToday = e.gainedToday;
            c.completed = e.completed;
            c.failed = e.failed;
            c.abandoned = e.abandoned;
        }
        return c;
    }

    private Entry entry(final UUID country, final UUID player, final String playerName) {
        final Entry e = this.entries.computeIfAbsent(key(country, player), k -> new Entry());
        if (playerName != null && !playerName.isBlank()) {
            e.playerName = playerName;
        }
        this.dirty = true;
        return e;
    }

    /**
     * Changes the reputation from play. Positive deltas are limited by what is left of {@code dailyGainCap} for this
     * pair today ({@code dailyGainCap <= 0} = no cap); negative deltas always apply.
     */
    public Change add(final UUID country, final UUID player, final String playerName, final int delta, final int dailyGainCap) {
        final Entry e = this.entry(country, player, playerName);
        final RepTier before = RepTier.of(e.value);
        int d = delta;
        if (d > 0 && dailyGainCap > 0) {
            final String today = LocalDate.now(this.clock).toString();
            if (!today.equals(e.gainDay)) {
                e.gainDay = today;
                e.gainedToday = 0;
            }
            d = Math.min(d, Math.max(0, dailyGainCap - e.gainedToday));
            e.gainedToday += d;
        }
        final int old = e.value;
        e.value = RepTier.clamp(old + d);
        return new Change(e.value - old, e.value, before, RepTier.of(e.value));
    }

    /** Admin: sets the value (no cap). */
    public Change set(final UUID country, final UUID player, final String playerName, final int value) {
        final Entry e = this.entry(country, player, playerName);
        final RepTier before = RepTier.of(e.value);
        final int old = e.value;
        e.value = RepTier.clamp(value);
        return new Change(e.value - old, e.value, before, RepTier.of(e.value));
    }

    public void countCompleted(final UUID country, final UUID player, final String playerName) {
        this.entry(country, player, playerName).completed++;
    }

    public void countFailed(final UUID country, final UUID player, final String playerName) {
        this.entry(country, player, playerName).failed++;
    }

    public void countAbandoned(final UUID country, final UUID player, final String playerName) {
        this.entry(country, player, playerName).abandoned++;
    }

    /** Admin: forgets one pair, or every country of the player when {@code country} is null. Returns how many. */
    public int reset(final UUID player, final UUID country) {
        final int before = this.entries.size();
        if (country != null) {
            this.entries.remove(key(country, player));
        } else {
            final String suffix = "|" + player;
            this.entries.keySet().removeIf(k -> k.endsWith(suffix));
        }
        final int removed = before - this.entries.size();
        if (removed > 0) {
            this.dirty = true;
        }
        return removed;
    }

    /** Country id → value of every country the player has a non-default entry with. */
    public Map<UUID, Integer> ofPlayer(final UUID player) {
        final Map<UUID, Integer> out = new HashMap<>();
        final String suffix = "|" + player;
        for (final Map.Entry<String, Entry> e : this.entries.entrySet()) {
            if (e.getKey().endsWith(suffix)) {
                try {
                    out.put(UUID.fromString(e.getKey().substring(0, e.getKey().indexOf('|'))), e.getValue().value);
                } catch (final IllegalArgumentException ignored) {
                    // malformed key from a hand-edited file
                }
            }
        }
        return out;
    }

    /** Best players of a country (value descending), at most {@code limit}. */
    public List<Map.Entry<String, Integer>> top(final UUID country, final int limit) {
        final String prefix = country + "|";
        final List<Map.Entry<String, Integer>> out = new ArrayList<>();
        for (final Map.Entry<String, Entry> e : this.entries.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.add(Map.entry(e.getValue().playerName == null || e.getValue().playerName.isBlank()
                    ? e.getKey().substring(prefix.length()) : e.getValue().playerName, e.getValue().value));
            }
        }
        out.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    public void forgetCountry(final UUID country) {
        final String prefix = country + "|";
        if (this.entries.keySet().removeIf(k -> k.startsWith(prefix))) {
            this.dirty = true;
        }
    }

    public int size() {
        return this.entries.size();
    }

    public boolean dirty() {
        return this.dirty;
    }

    /** Copy for the JSON writer (serialised on the main thread by {@code JsonStore}). Clears the dirty flag. */
    public Map<String, Entry> snapshot() {
        this.dirty = false;
        final Map<String, Entry> out = new HashMap<>();
        this.entries.forEach((k, v) -> {
            final Entry c = new Entry();
            c.value = v.value;
            c.playerName = v.playerName;
            c.gainDay = v.gainDay;
            c.gainedToday = v.gainedToday;
            c.completed = v.completed;
            c.failed = v.failed;
            c.abandoned = v.abandoned;
            out.put(k, c);
        });
        return out;
    }
}
