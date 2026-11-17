// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JSON shape of {@code zlecenia.json}: daily boards, active quests and per-player / per-country day counters. */
public final class QuestData {

    /** One offer on a country's daily board. Not exclusive: every player may take it once. */
    public static final class Offer {
        public String id = "";
        public String country = "";
        public String type = QuestType.DOSTAWA.key();
        /** Bukkit material name (DOSTAWA/SUROWCE). */
        public String material = "";
        /** Polish item name (DOSTAWA/SUROWCE) or the goods carried (TRANSPORT). */
        public String itemName = "";
        public int amount;
        /** Resource key for SUROWCE ({@code drewno}, {@code zywnosc}…), else empty. */
        public String need = "";
        /** True when the country really lacks it (Ludnosc data), not just a random pick. */
        public boolean urgent;
        /** Reward before the reputation multiplier and treasury caps, copper. */
        public long baseReward;
        public int reputation;
        public int timeLimitMinutes;
        public String targetCountry = "";
        public String targetName = "";
        public int distance;
        public boolean premium;

        public QuestType type() {
            return QuestType.byKey(this.type).orElse(QuestType.DOSTAWA);
        }
    }

    /** A quest a player accepted. */
    public static final class Active {
        /** Unique id (cargo PDC, escrow account). */
        public String uid = "";
        public String offerId = "";
        public String player = "";
        public String playerName = "";
        public String country = "";
        public String countryName = "";
        public String type = QuestType.DOSTAWA.key();
        public String material = "";
        public String itemName = "";
        public int amount;
        /** Reward fixed at acceptance (reputation multiplier and caps applied), copper. */
        public long reward;
        /** True when {@link #reward} waits in the escrow account {@code wladcy:zlecenie-<uid>}. */
        public boolean escrow;
        /** Set by the start-up audit ({@link EscrowCoverage#reconcile}): the reward is not (fully) covered by an escrow. */
        public boolean uncovered;
        public SupplyAcceptance.Phase acceptancePhase = SupplyAcceptance.Phase.ACCEPTED;
        public String acceptanceBinding = "";
        public int acceptanceMinutes;
        public boolean acceptanceNoticePending;
        /** New resource contracts use the optional Ludnosc physical delivery bridge; old JSON defaults to false. */
        public boolean supplyDelivery;
        public SupplySettlement.Phase supplyPhase = SupplySettlement.Phase.NEW;
        public String supplyBinding = "";
        public String supplyReceipt = "";
        public int reputation;
        public long acceptedAt;
        public long deadline;
        public String targetCountry = "";
        public String targetName = "";
        public int distance;

        public QuestType type() {
            return QuestType.byKey(this.type).orElse(QuestType.DOSTAWA);
        }
    }

    /** Today's board of one country. */
    public static final class Board {
        public String day = "";
        public List<Offer> offers = new ArrayList<>();
    }

    /** Per-player counters, reset every day (except cooldowns). */
    public static final class PlayerDay {
        public String day = "";
        public int completed;
        public Map<String, Integer> perCountry = new HashMap<>();
        /** Offer ids already taken or refused today (each offer once per player). */
        public Set<String> used = new HashSet<>();
        public long lastAcceptAt;
        /** country id → epoch millis until which the country gives this player nothing (after a failure). */
        public Map<String, Long> cooldownUntil = new HashMap<>();
        /** Notices for the next login (quest expired while offline), plain Polish text. */
        public List<String> notices = new ArrayList<>();
        public int totalCompleted;
        public int totalFailed;
    }

    public Map<String, Board> boards = new HashMap<>();
    /** player uuid → active quests. */
    public Map<String, List<Active>> active = new HashMap<>();
    public Map<String, PlayerDay> players = new HashMap<>();
    /** country id → day → copper paid in rewards that day (only today's entry is kept). */
    public Map<String, Long> paidToday = new HashMap<>();
    public String paidDay = "";

    /** Fills nulls left by Gson for fields missing in an older file. */
    void normalize() {
        if (this.boards == null) {
            this.boards = new HashMap<>();
        }
        if (this.active == null) {
            this.active = new HashMap<>();
        }
        if (this.players == null) {
            this.players = new HashMap<>();
        }
        if (this.paidToday == null) {
            this.paidToday = new HashMap<>();
        }
        if (this.paidDay == null) {
            this.paidDay = "";
        }
        this.boards.values().removeIf(b -> b == null);
        for (final Board b : this.boards.values()) {
            if (b.offers == null) {
                b.offers = new ArrayList<>();
            }
            b.offers.removeIf(o -> o == null || o.id == null);
        }
        this.active.values().removeIf(l -> l == null);
        for (final List<Active> l : this.active.values()) {
            l.removeIf(a -> a == null || a.uid == null || a.country == null);
        }
        this.active.values().removeIf(List::isEmpty);
        this.players.values().removeIf(p -> p == null);
        for (final PlayerDay p : this.players.values()) {
            if (p.perCountry == null) {
                p.perCountry = new HashMap<>();
            }
            if (p.used == null) {
                p.used = new HashSet<>();
            }
            if (p.cooldownUntil == null) {
                p.cooldownUntil = new HashMap<>();
            }
            if (p.notices == null) {
                p.notices = new ArrayList<>();
            }
            if (p.day == null) {
                p.day = "";
            }
        }
    }
}
