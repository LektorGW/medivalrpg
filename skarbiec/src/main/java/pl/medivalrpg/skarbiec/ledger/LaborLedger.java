// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Pending labour of construction payments and its deferred settlement to {@code world:households} (pure Java).
 *
 * <p>{@link #observe} sees every committed ledger operation (called by {@link Ledger} under its lock — memory only):
 * a construction payment {@code payer → PAYMENTS} adds {@code share × amount} of pending labour for the payer; a refund
 * {@code PAYMENTS → payer} removes {@code share × refund} from the payer's newest pending entries (a 100 % refund of a
 * queued order cancels its labour completely). {@link #settle} (main thread, periodically) moves labour older than
 * the maturity period from PAYMENTS to HOUSEHOLDS as one ordinary ledger transfer, never more than PAYMENTS holds,
 * within the daily cap and the households target. Until then the money stays in PAYMENTS, so refunds are never
 * blocked by the labour share.</p>
 */
public final class LaborLedger {

    /** One pending piece: time of the payment and labour still owed for it. */
    static final class Piece {
        final long time;
        long amount;

        Piece(final long time, final long amount) {
            this.time = time;
            this.amount = amount;
        }
    }

    private final Map<String, Deque<Piece>> pending = new LinkedHashMap<>();
    private volatile LaborShare policy;
    private ZoneId zone;
    private long settledDay = Long.MIN_VALUE;
    private long settledToday;
    private long settledTotal;
    private long cancelledTotal;
    private boolean dirty;

    public LaborLedger(final LaborShare policy, final ZoneId zone) {
        this.policy = policy == null ? LaborShare.OFF : policy;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
    }

    public synchronized void policy(final LaborShare policy, final ZoneId zone) {
        this.policy = policy == null ? LaborShare.OFF : policy;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
    }

    public LaborShare policy() {
        return this.policy;
    }

    /** Ledger observer: memory only. */
    public synchronized void observe(final TxRecord r) {
        if (r.type() != TxRecord.Type.TRANSFER || r.from() == null || r.to() == null) {
            return;
        }
        final LaborShare p = this.policy;
        if (WorldMoney.PAYMENTS.equals(r.to()) && p.payment(r.reason())) {
            final long part = p.part(r.amount());
            if (part > 0) {
                this.pending.computeIfAbsent(r.from(), k -> new ArrayDeque<>()).addLast(new Piece(r.time(), part));
                this.dirty = true;
            }
        } else if (WorldMoney.PAYMENTS.equals(r.from()) && p.refund(r.reason())) {
            long cancel = p.part(r.amount());
            final Deque<Piece> q = this.pending.get(r.to());
            while (q != null && cancel > 0 && !q.isEmpty()) {
                final Piece last = q.peekLast();
                final long take = Math.min(cancel, last.amount);
                last.amount -= take;
                cancel -= take;
                this.cancelledTotal += take;
                if (last.amount == 0) {
                    q.removeLast();
                }
                this.dirty = true;
            }
            if (q != null && q.isEmpty()) {
                this.pending.remove(r.to());
            }
        }
    }

    public synchronized long pendingTotal() {
        long t = 0;
        for (final Deque<Piece> q : this.pending.values()) {
            for (final Piece p : q) {
                t += p.amount;
            }
        }
        return t;
    }

    /** Labour old enough to settle at {@code now}. */
    public synchronized long due(final long now) {
        final long limit = now - this.policy.settleAfterMillis();
        long t = 0;
        for (final Deque<Piece> q : this.pending.values()) {
            for (final Piece p : q) {
                if (p.time <= limit) {
                    t += p.amount;
                }
            }
        }
        return t;
    }

    public synchronized long settledToday(final long now) {
        return this.day(now) == this.settledDay ? this.settledToday : 0;
    }

    public synchronized long settledTotal() {
        return this.settledTotal;
    }

    public synchronized long cancelledTotal() {
        return this.cancelledTotal;
    }

    private long day(final long now) {
        return Instant.ofEpochMilli(now).atZone(this.zone).toLocalDate().toEpochDay();
    }

    /**
     * Moves due labour PAYMENTS → HOUSEHOLDS (one transfer). Returns the amount moved. Call from one thread (main),
     * never from inside a ledger observer.
     */
    public long settle(final Ledger ledger, final long now) {
        // Ledger reads happen BEFORE taking this lock: observe() runs under the ledger lock and then takes this one,
        // so holding this lock while asking the ledger could deadlock.
        if (!ledger.closedSupply()) {
            return 0;
        }
        final long households = ledger.balance(WorldMoney.HOUSEHOLDS);
        final long payments = ledger.balance(WorldMoney.PAYMENTS);
        final long amount;
        final int pieces;
        synchronized (this) {
            final LaborShare p = this.policy;
            if (p.basisPoints() <= 0) {
                return 0;
            }
            final long today = this.day(now);
            if (today != this.settledDay) {
                this.settledDay = today;
                this.settledToday = 0;
            }
            long a = this.due(now);
            if (p.dailyCap() > 0) {
                a = Math.min(a, Math.max(0, p.dailyCap() - this.settledToday));
            }
            if (p.targetBalance() > 0) {
                a = Math.min(a, Math.max(0, p.targetBalance() - households));
            }
            a = Math.min(a, payments);
            amount = a;
            pieces = this.countDue(now);
        }
        if (amount <= 0) {
            return 0;
        }
        final TxResult r = ledger.transfer(WorldMoney.PAYMENTS, WorldMoney.HOUSEHOLDS, amount, LaborShare.REASON_PREFIX
            + (this.policy.basisPoints() / 100) + "% budów, napraw i robót (" + pieces + " płatności) — wynagrodzenie obywateli NPC");
        if (!r.ok()) {
            return 0;
        }
        synchronized (this) {
            this.consumeOldest(now, amount);
            this.settledToday += amount;
            this.settledTotal += amount;
            this.dirty = true;
        }
        return amount;
    }

    private int countDue(final long now) {
        final long limit = now - this.policy.settleAfterMillis();
        int n = 0;
        for (final Deque<Piece> q : this.pending.values()) {
            for (final Piece p : q) {
                if (p.time <= limit && p.amount > 0) {
                    n++;
                }
            }
        }
        return n;
    }

    private void consumeOldest(final long now, final long amount) {
        final long limit = now - this.policy.settleAfterMillis();
        long left = amount;
        while (left > 0) {
            Deque<Piece> best = null;
            for (final Deque<Piece> q : this.pending.values()) {
                if (!q.isEmpty() && q.peekFirst().time <= limit && (best == null || q.peekFirst().time < best.peekFirst().time)) {
                    best = q;
                }
            }
            if (best == null) {
                return;
            }
            final Piece first = best.peekFirst();
            final long take = Math.min(left, first.amount);
            first.amount -= take;
            left -= take;
            if (first.amount == 0) {
                best.removeFirst();
            }
        }
        this.pending.values().removeIf(Deque::isEmpty);
    }

    public synchronized boolean dirty() {
        return this.dirty;
    }

    public synchronized JsonObject toJson() {
        this.dirty = false;
        final JsonObject o = new JsonObject();
        o.addProperty("format", 1);
        o.addProperty("settledDay", this.settledDay);
        o.addProperty("settledToday", this.settledToday);
        o.addProperty("settledTotal", this.settledTotal);
        o.addProperty("cancelledTotal", this.cancelledTotal);
        final JsonObject p = new JsonObject();
        this.pending.forEach((payer, q) -> {
            final JsonArray a = new JsonArray();
            for (final Piece piece : q) {
                final JsonArray e = new JsonArray();
                e.add(piece.time);
                e.add(piece.amount);
                a.add(e);
            }
            p.add(payer, a);
        });
        o.add("pending", p);
        return o;
    }

    /**
     * Replaces the state with the saved one ({@code null} = empty). All or nothing: a malformed file throws and leaves the
     * current state untouched, never half loaded.
     */
    public synchronized void load(final JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            this.pending.clear();
            return;
        }
        final JsonObject o = e.getAsJsonObject();
        final long day = o.has("settledDay") ? o.get("settledDay").getAsLong() : Long.MIN_VALUE;
        final long today = o.has("settledToday") ? o.get("settledToday").getAsLong() : 0;
        final long total = o.has("settledTotal") ? o.get("settledTotal").getAsLong() : 0;
        final long cancelled = o.has("cancelledTotal") ? o.get("cancelledTotal").getAsLong() : 0;
        final Map<String, Deque<Piece>> loaded = new LinkedHashMap<>();
        if (o.has("pending")) {
            for (final Map.Entry<String, JsonElement> pe : o.getAsJsonObject("pending").entrySet()) {
                final Deque<Piece> q = new ArrayDeque<>();
                for (final JsonElement x : pe.getValue().getAsJsonArray()) {
                    final JsonArray a = x.getAsJsonArray();
                    final long amount = a.get(1).getAsLong();
                    if (amount > 0) {
                        q.addLast(new Piece(a.get(0).getAsLong(), amount));
                    }
                }
                if (!q.isEmpty()) {
                    loaded.put(pe.getKey(), q);
                }
            }
        }
        this.settledDay = day;
        this.settledToday = today;
        this.settledTotal = total;
        this.cancelledTotal = cancelled;
        this.pending.clear();
        this.pending.putAll(loaded);
    }
}
