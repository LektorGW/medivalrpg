// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent state of background NPC wars ({@code plugins/Armia/conquest.json}). Main thread only; survives restarts
 * (offensives continue where they stopped, committed soldiers stay on campaign).
 */
public final class ConquestState {

    /** One running offensive (at most one per attacker). */
    public static final class Offensive {
        public String id;
        public UUID attacker;
        public UUID defender;
        public String world;
        public int cx;
        public int cz;
        public boolean expedition;
        public List<UUID> soldiers = new ArrayList<>();
        public double progress;
        public int rounds;
        public long startedAt;
        public long lastRoundAt;
        public int attackerLosses;
        public int defenderLosses;
        public boolean paused;
        public long pausedSince;
        public int unpaidRounds;
        public double lastAttack;
        public double lastDefence;
        public long supplies;

        public Offensive() {
        }

        public String where() {
            return this.cx + "," + this.cz;
        }
    }

    /** Per-country war bookkeeping. */
    public static final class CountryWar {
        public double exhaustion;
        public long exhaustionAt;
        public long lastOffensiveAt;
        public List<Long> taken = new ArrayList<>();
        public List<Long> lost = new ArrayList<>();
        public List<Long> revolts = new ArrayList<>();
        public long overextensionPaidAt;
        public long lastPeaceProposalAt;
        public long coalitionAt;
        public long secessionCheckAt;
        /** "world:cx:cz" → until (no retry before). */
        public Map<String, Long> retryBlocked = new HashMap<>();

        public CountryWar() {
        }

        public void normalize() {
            if (this.taken == null) this.taken = new ArrayList<>();
            if (this.lost == null) this.lost = new ArrayList<>();
            if (this.revolts == null) this.revolts = new ArrayList<>();
            if (this.retryBlocked == null) this.retryBlocked = new HashMap<>();
        }
    }

    /** A chunk taken in the background (revolts, peace terms). */
    public static final class Conquered {
        public String world;
        public int cx;
        public int cz;
        public UUID holder;
        public UUID former;
        public long at;

        public Conquered() {
        }

        public Conquered(final String world, final int cx, final int cz, final UUID holder, final UUID former, final long at) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.holder = holder;
            this.former = former;
            this.at = at;
        }
    }

    /** Statistics of one war direction "attacker>defender". */
    public static final class WarStats {
        public long since;
        public int chunks;
        public int offensives;
        public int failed;
        public int soldiersLost;
        public int enemiesKilled;

        public WarStats() {
        }
    }

    /** History line for {@code /nk wojny}. */
    public static final class Event {
        public long at;
        public String text;

        public Event() {
        }

        public Event(final long at, final String text) {
            this.at = at;
            this.text = text;
        }
    }

    public int version = 1;
    public Map<UUID, Offensive> offensives = new LinkedHashMap<>();
    public Map<UUID, CountryWar> countries = new HashMap<>();
    public List<Conquered> conquered = new ArrayList<>();
    public List<Long> globalTaken = new ArrayList<>();
    public Map<String, WarStats> wars = new HashMap<>();
    public List<Event> history = new ArrayList<>();

    public ConquestState() {
    }

    public CountryWar country(final UUID id) {
        final CountryWar w = this.countries.computeIfAbsent(id, k -> new CountryWar());
        w.normalize();
        return w;
    }

    public WarStats war(final UUID attacker, final UUID defender) {
        return this.wars.computeIfAbsent(attacker + ">" + defender, k -> new WarStats());
    }

    public void normalize() {
        if (this.offensives == null) this.offensives = new LinkedHashMap<>();
        if (this.countries == null) this.countries = new HashMap<>();
        if (this.conquered == null) this.conquered = new ArrayList<>();
        if (this.globalTaken == null) this.globalTaken = new ArrayList<>();
        if (this.wars == null) this.wars = new HashMap<>();
        if (this.history == null) this.history = new ArrayList<>();
        this.offensives.values().removeIf(o -> o == null || o.attacker == null || o.defender == null || o.world == null);
        for (final Offensive o : this.offensives.values()) {
            if (o.soldiers == null) o.soldiers = new ArrayList<>();
        }
        this.countries.values().removeIf(c -> c == null);
        this.countries.values().forEach(CountryWar::normalize);
        this.conquered.removeIf(c -> c == null || c.world == null || c.holder == null);
    }

    public void event(final long at, final String text) {
        this.history.add(new Event(at, text));
        while (this.history.size() > 80) {
            this.history.removeFirst();
        }
    }
}
