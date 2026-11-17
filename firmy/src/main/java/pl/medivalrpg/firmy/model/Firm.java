// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A player company (serialized by Gson into {@code firmy.json}; public fields on purpose). Money is never stored here:
 * the balance lives in Skarbiec on {@link #account()}. Everything else (people, shares, salaries, arrears, warehouse,
 * statistics, own ledger) is kept by Firmy.
 */
public final class Firm {

    public static final String ACCOUNT_PREFIX = "firma:";

    public enum State { ACTIVE, LIQUIDATED }

    /** One person of the company. {@code shareBp} in basis points (10000 = 100 %). */
    public static final class Member {
        public String uuid;
        public String name;
        public Role role;
        public int shareBp;
        /** Daily salary in copper (0 = none). */
        public long salary;
        public long joinedAt;

        public Member() {
        }

        public Member(final UUID uuid, final String name, final Role role, final int shareBp, final long joinedAt) {
            this.uuid = uuid.toString();
            this.name = name;
            this.role = role;
            this.shareBp = shareBp;
            this.joinedAt = joinedAt;
        }

        public UUID id() {
            return UUID.fromString(this.uuid);
        }
    }

    /** A protected warehouse chest. */
    public static final class Chest {
        public String world;
        public int x;
        public int y;
        public int z;
        public long addedAt;
        public String addedBy;

        public Chest() {
        }

        public Chest(final String world, final int x, final int y, final int z, final long addedAt, final String addedBy) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.addedAt = addedAt;
            this.addedBy = addedBy;
        }

        public String key() {
            return this.world + ":" + this.x + ":" + this.y + ":" + this.z;
        }
    }

    /** "Who took what" from a warehouse chest. {@code delta} > 0 = put in, < 0 = taken out. */
    public static final class ChestLog {
        public long time;
        public String player;
        public String item;
        public int delta;
        public String chest;

        public ChestLog() {
        }

        public ChestLog(final long time, final String player, final String item, final int delta, final String chest) {
            this.time = time;
            this.player = player;
            this.item = item;
            this.delta = delta;
            this.chest = chest;
        }
    }

    /** One line of the company's own ledger (what, who, how much). */
    public static final class Entry {
        public long time;
        /** Short category: wplata, pensja, dywidenda, podatek, sklep, oplata, likwidacja, ludzie, admin, ... */
        public String kind;
        public String text;
        /** Signed change of the company balance in copper (0 for non-money events). */
        public long amount;
        public String actor;

        public Entry() {
        }

        public Entry(final long time, final String kind, final String text, final long amount, final String actor) {
            this.time = time;
            this.kind = kind;
            this.text = text;
            this.amount = amount;
            this.actor = actor;
        }
    }

    /** A delayed large payout (dividend) waiting 24 h. */
    public static final class Payout {
        public String id;
        public long amount;
        public long createdAt;
        public long dueAt;
        public String by;

        public Payout() {
        }

        public Payout(final String id, final long amount, final long createdAt, final long dueAt, final String by) {
            this.id = id;
            this.amount = amount;
            this.createdAt = createdAt;
            this.dueAt = dueAt;
            this.by = by;
        }
    }

    public String id;
    public String name;
    public FirmType type;
    public String seatCountry;
    public String seatCountryName;
    /** {@code #RRGGBB}. */
    public String color = "#C9A227";
    /** Serialized banner item (Base64 of ItemStack#serializeAsBytes), or null. */
    public String banner;
    /** Banner for the web panel: base colour + list of "pattern:color". */
    public String bannerBase;
    public List<String> bannerPatterns = new ArrayList<>();
    public State state = State.ACTIVE;
    public long createdAt;
    public Map<String, Member> members = new LinkedHashMap<>();
    public List<Chest> warehouse = new ArrayList<>();
    public List<ChestLog> chestLog = new ArrayList<>();
    public List<Entry> journal = new ArrayList<>();
    public List<Payout> payouts = new ArrayList<>();
    /** Unpaid salaries: member uuid → copper. */
    public Map<String, Long> arrears = new LinkedHashMap<>();
    /** Consecutive daily settlements that ended with unpaid salaries. */
    public int unpaidDays;
    /** Gross income per day (yyyy-MM-dd) and the tax paid from it, kept for 35 days. */
    public TreeMap<String, Long> incomeByDay = new TreeMap<>();
    public TreeMap<String, Long> taxByDay = new TreeMap<>();
    public long incomeTotal;
    public long taxTotal;
    /** Copper paid to members today (salaries excluded) for the large-payout threshold; reset with {@link #payoutDay}. */
    public long paidOutToday;
    public String payoutDay = "";
    /** Last login of any member or last money movement. */
    public long lastActivity;
    /** Set while the company is dormant (no activity for N days): shops stand still. */
    public long dormantSince;
    /** Set when the seat country no longer exists (shops stand still until the seat moves). */
    public long seatLostAt;
    /** The seat country's ruler suspended the shops in war (master is a citizen of an enemy). */
    public boolean warSuspended;
    /** Frozen by an administrator or by an uncertain money operation (audit): no outflows. */
    public boolean frozen;
    public String frozenReason = "";
    public long liquidatedAt;
    public String liquidationReason = "";
    /** After liquidation: former master may open the company chests for N days. */
    public String exMaster;
    public int contractsDone;
    public long promotedAt;

    public String account() {
        return ACCOUNT_PREFIX + this.id;
    }

    public boolean active() {
        return this.state == State.ACTIVE;
    }

    public Optional<Member> member(final UUID uuid) {
        return Optional.ofNullable(this.members.get(uuid.toString()));
    }

    public Optional<Role> role(final UUID uuid) {
        return this.member(uuid).map(m -> m.role);
    }

    public boolean has(final UUID uuid, final Role atLeast) {
        return this.role(uuid).map(r -> r.atLeast(atLeast)).orElse(false);
    }

    public Optional<Member> master() {
        return this.members.values().stream().filter(m -> m.role == Role.MISTRZ).findFirst();
    }

    public int totalShares() {
        int sum = 0;
        for (final Member m : this.members.values()) {
            sum += m.shareBp;
        }
        return sum;
    }

    public long totalArrears() {
        long sum = 0;
        for (final long v : this.arrears.values()) {
            sum += v;
        }
        return sum;
    }

    /** Gross income of the last {@code days} days ending with {@code today} (inclusive). */
    public long income(final String fromDayInclusive) {
        long sum = 0;
        for (final var e : this.incomeByDay.tailMap(fromDayInclusive, true).entrySet()) {
            sum += e.getValue();
        }
        return sum;
    }

    public long tax(final String fromDayInclusive) {
        long sum = 0;
        for (final var e : this.taxByDay.tailMap(fromDayInclusive, true).entrySet()) {
            sum += e.getValue();
        }
        return sum;
    }

    public Optional<Chest> chestAt(final String key) {
        return this.warehouse.stream().filter(c -> c.key().equals(key)).findFirst();
    }
}
