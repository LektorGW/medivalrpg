// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.armia.api.NpcGoal;
import pl.medivalrpg.armia.api.NpcPosture;
import pl.medivalrpg.armia.api.NpcStance;
import pl.medivalrpg.armia.logic.Ledger;
import pl.medivalrpg.armia.logic.Personality;

/**
 * Persistent AI state of one NPC country ({@code npc.json}). Main thread only. Fields added later are null in old
 * files; {@link #normalize()} fills them, so old files load unchanged.
 */
public final class NpcState {

    /** A chunk conquered from us (reason to reclaim it). */
    public static final class LostChunk {
        public String world;
        public int cx;
        public int cz;
        public UUID taker;
        public long at;

        public LostChunk() {
        }

        public LostChunk(final String world, final int cx, final int cz, final UUID taker, final long at) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.taker = taker;
            this.at = at;
        }

        public boolean is(final String w, final int x, final int z) {
            return this.cx == x && this.cz == z && this.world != null && this.world.equals(w);
        }
    }

    /** An accepted daily tribute (tribute demand). */
    public static final class TributeDeal {
        public UUID other;
        public long amount;
        /** true = this country pays {@link #other}. */
        public boolean pays;
        public long until;
        public long lastPaidAt;
        public int missed;

        public TributeDeal() {
        }

        public TributeDeal(final UUID other, final long amount, final boolean pays, final long until, final long lastPaidAt) {
            this.other = other;
            this.amount = amount;
            this.pays = pays;
            this.until = until;
            this.lastPaidAt = lastPaidAt;
        }
    }

    /** A tribute demand waiting for a player country's answer. */
    public static final class Demand {
        public long amount;
        public long at;

        public Demand() {
        }

        public Demand(final long amount, final long at) {
            this.amount = amount;
            this.at = at;
        }
    }

    /** A pending alliance/peace proposal. */
    public static final class Pending {
        public String relation;
        public boolean out;
        public long at;

        public Pending() {
        }

        public Pending(final String relation, final boolean out, final long at) {
            this.relation = relation;
            this.out = out;
            this.at = at;
        }
    }

    /** Persisted line of the decision log. */
    public static final class LogLine {
        public long time;
        public String category;
        public String text;

        public LogLine() {
        }

        public LogLine(final long time, final String category, final String text) {
            this.time = time;
            this.category = category;
            this.text = text;
        }
    }

    /** Budget split set by an admin. */
    public static final class Budget {
        public int army;
        public int buildings;
        public int reserve;
        /** Research share in percent (Postep invests it from the daily income); null = Postep decides (auto). */
        public Integer research;

        public Budget() {
        }

        public Budget(final int army, final int buildings, final int reserve) {
            this.army = army;
            this.buildings = buildings;
            this.reserve = reserve;
        }
    }

    public UUID country;
    public int aggression = 50;
    public int expansion = 50;
    public int diplomacy = 50;
    public int wealth = 50;
    public int maxChunks = 40;
    public int maxSoldiers = 20;
    /** Parameter "aktywna". */
    public boolean active = true;
    /** Admin pause ({@code /nk pauza}). */
    public boolean paused;
    public long nextTickAt;
    public long lastWarDeclaredAt;
    /** Enemy → war start (epoch millis). */
    public Map<UUID, Long> warStartedAt = new HashMap<>();
    /** Enemy → soldiers lost in that war. */
    public Map<UUID, Integer> warLosses = new HashMap<>();
    /** Country → opinion −100..100. */
    public Map<UUID, Integer> opinion = new HashMap<>();
    public Map<UUID, Long> lastPeaceProposal = new HashMap<>();
    public Map<UUID, Long> lastAllianceProposal = new HashMap<>();
    /** Building type → orders placed. */
    public Map<String, Integer> buildingsOrdered = new HashMap<>();
    /** Running siege started by this country. */
    public UUID siegeId;
    public Set<UUID> siegeSoldiers = new HashSet<>();
    public long lastSiegeAt;
    /** Rich country saving for a representative building (last development failed for lack of funds). */
    public boolean savingForBuilding;
    /** Last result of a development attempt (logged only when it changes). */
    public String lastBuildResult;

    // ---- AI v2 (null in older npc.json) ----------------------------------------------------------

    /** Country → {@link NpcStance} name (missing = AUTO). */
    public Map<UUID, String> stance = new HashMap<>();
    /** Admin budget split; null = derived from the personality. */
    public Budget budget;
    /** {@link NpcPosture} name. */
    public String posture = NpcPosture.BALANCED.name();
    /** Admin siege target; null = automatic. */
    public UUID siegeTarget;
    public List<LostChunk> lostChunks = new ArrayList<>();
    public long lastChunkLostAt;
    public long lastSiegeOnUsAt;
    /** When the last war of this country ended (0 = never at war / at war now). */
    public long peaceSince;
    public long crisisSince;
    public long lastHelpRequestAt;
    /** Enemies we declared war on ourselves (no revenge for those wars). */
    public Set<UUID> declaredByUs = new HashSet<>();
    public Map<UUID, Long> treasuryAtWarStart = new HashMap<>();
    public Map<UUID, Integer> chunksLostInWar = new HashMap<>();
    public Map<UUID, Long> lastCapitulationOffer = new HashMap<>();
    public UUID capitulatingTo;
    public long capitulatingAt;
    public List<TributeDeal> tributes = new ArrayList<>();
    /** Player country → our pending tribute demand. */
    public Map<UUID, Demand> tributeDemands = new HashMap<>();
    /** Country → pending proposal between us. */
    public Map<UUID, Pending> pending = new HashMap<>();
    public List<Ledger.Entry> ledger = new ArrayList<>();
    /** Time of the newest Kraje treasury history line copied into {@link #ledger}. */
    public long krajeLogSeen;
    /** Tax of NPC citizens in percent of Ludnosc's tax. */
    public int taxMultiplier = 100;
    public long tickCount;
    public long lastBuildAt;
    /** Decision log (persisted copy; the live one is in NpcAiService). */
    public List<LogLine> log = new ArrayList<>();
    /**
     * Naval landing in progress (wave 13/16 AI). Persisted so the soldiers reserved for it are released after a restart;
     * the autopilot itself is not persisted, a restarted operation is wound down (cargo unloaded on a safe shore).
     */
    public static final class NavalOp {
        public String phase = "EMBARK";
        public UUID ship;
        public UUID enemy;
        public String world;
        public int cx;
        public int cz;
        /** Water waypoint near the enemy shore. */
        public double wx;
        public double wz;
        /** Shore where the soldiers gather (own land/wilderness). */
        public double gx;
        public double gy;
        public double gz;
        public boolean reclaim;
        /** When the first soldiers were unloaded on the enemy shore (0 = not yet). */
        public long landedAt;
        public long startedAt;
        public long phaseAt;
        public Set<UUID> soldiers = new HashSet<>();

        // ---- long-range expedition (ai.naval.long-range, 2026-10-31); absent in older saves = short-range operation
        /** The voyage is computed in the background (phases VOYAGE → OFFSHORE → LAND → SIEGE → HOMEWARD). */
        public boolean longRange;
        /** Stable id; soldiers at sea carry {@code campaign = "desant:" + id}. */
        public String id;
        /** Ordered by an administrator ({@code /nk desant}). */
        public boolean admin;
        public String shipType;
        /** Voyage distance in blocks (capital → target); becomes the ship's sailed distance. */
        public double distance;
        public long voyageMinutes;
        /** Arrival off the enemy coast (VOYAGE) / at home (HOMEWARD), epoch millis. */
        public long arriveAt;
        public long returnAt;
        /** OFFSHORE: the fleet sails home when no landing was possible until then. */
        public long waitUntil;
        public boolean approachWarned;
        public boolean offshoreAnnounced;
        /** Soldiers that went ashore (they come home with the fleet unless they hold a conquered bridgehead). */
        public Set<UUID> landed = new HashSet<>();
        /** Home post of every soldier at departure. */
        public Map<UUID, Pos> homes = new HashMap<>();

        public NavalOp() {
        }

        public String campaign() {
            return "desant:" + this.id;
        }
    }

    public NavalOp naval;
    public long lastNavalAt;
    /** Soldiers temporarily sent to the shore because an enemy ship is near (released after the alarm). */
    public Set<UUID> coastGuard = new HashSet<>();
    public long coastAlarmUntil;

    /** Last computed goal (not saved). */
    public transient NpcGoal goal;
    public transient boolean crisis;

    public NpcState() {
    }

    public NpcState(final UUID country, final Personality defaults) {
        this.country = country;
        this.apply(defaults);
    }

    /** Honor 0–100 set by an admin or the scenario ({@code /nk ustaw <kraj> honor}); null = derived from the personality. */
    public Integer honor;

    /** Effective honor: the set value, else {@link pl.medivalrpg.armia.api.NpcSettings#deriveHonor}. */
    public int honor() {
        return this.honor != null ? Personality.clamp(this.honor)
            : pl.medivalrpg.armia.api.NpcSettings.deriveHonor(this.aggression, this.diplomacy, this.wealth);
    }

    public Personality personality() {
        return new Personality(this.aggression, this.expansion, this.diplomacy, this.wealth, this.maxChunks, this.maxSoldiers, this.active);
    }

    public void apply(final Personality p) {
        this.aggression = p.aggression();
        this.expansion = p.expansion();
        this.diplomacy = p.diplomacy();
        this.wealth = p.wealth();
        this.maxChunks = p.maxChunks();
        this.maxSoldiers = p.maxSoldiers();
        this.active = p.active();
    }

    public int opinionOf(final UUID other) {
        return this.opinion.getOrDefault(other, 0);
    }

    public NpcStance stanceOf(final UUID other) {
        final String s = other == null ? null : this.stance.get(other);
        return s == null ? NpcStance.AUTO : NpcStance.parse(s).orElse(NpcStance.AUTO);
    }

    public NpcPosture posture() {
        return NpcPosture.parse(this.posture).orElse(NpcPosture.BALANCED);
    }

    /** Chunks held by {@code taker} that were conquered from us. */
    public int lostTo(final UUID taker) {
        int n = 0;
        for (final LostChunk l : this.lostChunks) {
            if (taker.equals(l.taker)) {
                n++;
            }
        }
        return n;
    }

    public TributeDeal tributeWith(final UUID other) {
        for (final TributeDeal d : this.tributes) {
            if (other.equals(d.other)) {
                return d;
            }
        }
        return null;
    }

    public void normalize() {
        if (this.coastGuard == null) {
            this.coastGuard = new HashSet<>();
        }
        if (this.naval != null && (this.naval.soldiers == null || this.naval.world == null || this.naval.enemy == null)) {
            if (this.naval.soldiers == null) {
                this.naval.soldiers = new HashSet<>();
            }
        }
        if (this.naval != null) {
            if (this.naval.landed == null) {
                this.naval.landed = new HashSet<>();
            }
            if (this.naval.homes == null) {
                this.naval.homes = new HashMap<>();
            }
            if (this.naval.longRange && this.naval.id == null) {
                this.naval.id = UUID.randomUUID().toString();
            }
        }
        if (this.warStartedAt == null) {
            this.warStartedAt = new HashMap<>();
        }
        if (this.warLosses == null) {
            this.warLosses = new HashMap<>();
        }
        if (this.opinion == null) {
            this.opinion = new HashMap<>();
        }
        if (this.lastPeaceProposal == null) {
            this.lastPeaceProposal = new HashMap<>();
        }
        if (this.lastAllianceProposal == null) {
            this.lastAllianceProposal = new HashMap<>();
        }
        if (this.buildingsOrdered == null) {
            this.buildingsOrdered = new HashMap<>();
        }
        if (this.siegeSoldiers == null) {
            this.siegeSoldiers = new HashSet<>();
        }
        if (this.stance == null) {
            this.stance = new HashMap<>();
        }
        if (this.posture == null || NpcPosture.parse(this.posture).isEmpty()) {
            this.posture = NpcPosture.BALANCED.name();
        }
        if (this.lostChunks == null) {
            this.lostChunks = new ArrayList<>();
        }
        this.lostChunks.removeIf(l -> l == null || l.world == null || l.taker == null);
        if (this.declaredByUs == null) {
            this.declaredByUs = new HashSet<>();
        }
        if (this.treasuryAtWarStart == null) {
            this.treasuryAtWarStart = new HashMap<>();
        }
        if (this.chunksLostInWar == null) {
            this.chunksLostInWar = new HashMap<>();
        }
        if (this.lastCapitulationOffer == null) {
            this.lastCapitulationOffer = new HashMap<>();
        }
        if (this.tributes == null) {
            this.tributes = new ArrayList<>();
        }
        this.tributes.removeIf(d -> d == null || d.other == null || d.amount <= 0);
        if (this.tributeDemands == null) {
            this.tributeDemands = new HashMap<>();
        }
        if (this.pending == null) {
            this.pending = new HashMap<>();
        }
        if (this.ledger == null) {
            this.ledger = new ArrayList<>();
        }
        this.ledger.removeIf(e -> e == null || e.key() == null);
        if (this.log == null) {
            this.log = new ArrayList<>();
        }
        this.log.removeIf(l -> l == null || l.text == null);
        if (this.taxMultiplier < 0 || this.taxMultiplier > 300) {
            this.taxMultiplier = 100;
        }
        if (this.budget != null && (this.budget.army < 0 || this.budget.buildings < 0 || this.budget.reserve < 0
            || this.budget.army + this.budget.buildings + this.budget.reserve + (this.budget.research == null ? 0 : this.budget.research) != 100)) {
            this.budget = null;
        }
        this.apply(this.personality());
    }
}
