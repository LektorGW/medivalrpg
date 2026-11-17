// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.merchant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * State of the merchant's real warehouse (pure Java): stock of every good, the slowly following reference stock,
 * daily turnover and an hourly price history. Money never lives here — it stays in the ledger's merchant budget; the
 * settle methods move items only after the ledger confirmed the payment, so a failed payment never changes stock.
 */
public final class MerchantMarket {

    /** One good of the warehouse. */
    public static final class Good {
        long stock;
        double reference;
        double carry;
        long boughtItems;
        long soldItems;
        long paid;
        long received;

        public long stock() {
            return this.stock;
        }

        public double reference() {
            return this.reference;
        }

        public long boughtItems() {
            return this.boughtItems;
        }

        public long soldItems() {
            return this.soldItems;
        }

        public long paid() {
            return this.paid;
        }

        public long received() {
            return this.received;
        }
    }

    /** Hourly sample: budget and per good {stock, buy price, sell price}. */
    public record Sample(long t, long budget, Map<String, long[]> goods) {
    }

    public static final long HOUR = 3_600_000L;

    private final Map<String, Good> goods = new LinkedHashMap<>();
    private final Deque<Sample> history = new ArrayDeque<>();
    private long updatedAt;
    private LocalDate day;
    private int historyHours = 168;

    public MerchantMarket(final long now, final ZoneId zone) {
        this.updatedAt = now;
        this.day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    }

    public void historyHours(final int hours) {
        this.historyHours = Math.max(24, Math.min(24 * 31, hours));
    }

    public Good good(final String key) {
        return this.goods.computeIfAbsent(key, k -> new Good());
    }

    public Map<String, Good> goods() {
        return this.goods;
    }

    public Deque<Sample> history() {
        return this.history;
    }

    public long updatedAt() {
        return this.updatedAt;
    }

    /**
     * Lets time pass: reference stock follows the real stock, surplus above the target is shipped away, configured
     * restock adds goods (never above the target), daily counters reset at midnight.
     *
     * @param targets      target stock per good (items)
     * @param restockPerDay explicit restock per good (items/day); goods without an entry are never created
     */
    public void advance(final long now, final ZoneId zone, final MerchantMath.Market m, final Map<String, Long> targets,
                        final Map<String, Long> restockPerDay) {
        this.advance(now, zone, m, targets, restockPerDay, java.util.Set.of());
    }

    /**
     * Like {@link #advance(long, ZoneId, MerchantMath.Market, Map, Map)}, but goods in {@code resting} (out of every
     * merchant's current offer) keep their stock untouched: no surplus shipping, no restock. Their reference stock still
     * follows the real one, so a good coming back is priced from its real stock.
     */
    public void advance(final long now, final ZoneId zone, final MerchantMath.Market m, final Map<String, Long> targets,
                        final Map<String, Long> restockPerDay, final java.util.Set<String> resting) {
        final long dt = now - this.updatedAt;
        if (dt <= 0) {
            return;
        }
        for (final Map.Entry<String, Long> t : targets.entrySet()) {
            final Good g = this.good(t.getKey());
            final long target = t.getValue();
            final boolean rests = resting.contains(t.getKey());
            double change = rests ? 0 : -m.decay(g.stock, target, dt);
            final long rate = rests ? 0 : restockPerDay.getOrDefault(t.getKey(), 0L);
            if (rate > 0 && g.stock < target) {
                change += Math.min(target - g.stock, rate * (dt / 86_400_000.0));
            }
            g.carry += change;
            final long whole = (long) (g.carry >= 0 ? Math.floor(g.carry) : Math.ceil(g.carry));
            if (whole != 0) {
                g.stock = Math.max(0, g.stock + whole);
                g.carry -= whole;
            }
            g.reference = m.smooth(g.reference, g.stock, dt);
        }
        this.updatedAt = now;
        final LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        if (!today.equals(this.day)) {
            this.day = today;
            for (final Good g : this.goods.values()) {
                g.boughtItems = 0;
                g.soldItems = 0;
                g.paid = 0;
                g.received = 0;
            }
        }
    }

    /** Adds an hourly sample when the last one is at least an hour old. Returns whether one was added. */
    public boolean sample(final long now, final long budget, final Map<String, long[]> prices) {
        final Sample last = this.history.peekLast();
        if (last != null && now - last.t() < HOUR) {
            return false;
        }
        this.history.addLast(new Sample(now, budget, prices));
        while (this.history.size() > this.historyHours) {
            this.history.removeFirst();
        }
        return true;
    }

    /** Buying price about 24 h ago (the oldest sample within 30 h, else the oldest), or -1. */
    public long buyPriceAgo(final String key, final long now) {
        Sample pick = null;
        for (final Sample s : this.history) {
            if (now - s.t() <= 30 * HOUR && now - s.t() >= 20 * HOUR) {
                pick = s;
                break;
            }
        }
        if (pick == null) {
            pick = this.history.peekFirst();
        }
        if (pick == null || now - pick.t() < HOUR) {
            return -1;
        }
        final long[] p = pick.goods().get(key);
        return p == null ? -1 : p[1];
    }

    // ------------------------------------------------------------------ settlement (closed money pool)

    /**
     * The merchant buys {@code items} from a player for {@code payout}: {@code pay} moves the money out of the
     * merchant's budget; only when it succeeds do the items enter the warehouse.
     */
    public TxResult settleSale(final String key, final long items, final long payout, final Supplier<TxResult> pay) {
        if (items <= 0 || payout <= 0) {
            return TxResult.INVALID_AMOUNT;
        }
        final TxResult r = pay.get();
        if (r.ok()) {
            final Good g = this.good(key);
            g.stock += items;
            g.boughtItems += items;
            g.paid += payout;
        }
        return r;
    }

    /**
     * The merchant sells {@code items} from real stock for {@code price}: refused without stock, otherwise
     * {@code charge} moves the money into the budget and only then the items leave the warehouse.
     */
    public TxResult settlePurchase(final String key, final long items, final long price, final Supplier<TxResult> charge) {
        if (items <= 0 || price <= 0) {
            return TxResult.INVALID_AMOUNT;
        }
        final Good g = this.good(key);
        if (g.stock < items) {
            return TxResult.INSUFFICIENT_FUNDS;
        }
        final TxResult r = charge.get();
        if (r.ok()) {
            g.stock -= items;
            g.soldItems += items;
            g.received += price;
        }
        return r;
    }

    // ------------------------------------------------------------------ persistence

    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("updatedAt", this.updatedAt);
        o.addProperty("day", this.day.toString());
        final JsonObject gs = new JsonObject();
        this.goods.forEach((k, g) -> {
            final JsonObject j = new JsonObject();
            j.addProperty("stock", g.stock);
            j.addProperty("reference", g.reference);
            j.addProperty("carry", g.carry);
            j.addProperty("boughtItems", g.boughtItems);
            j.addProperty("soldItems", g.soldItems);
            j.addProperty("paid", g.paid);
            j.addProperty("received", g.received);
            gs.add(k, j);
        });
        o.add("goods", gs);
        final JsonArray hist = new JsonArray();
        for (final Sample s : this.history) {
            final JsonObject j = new JsonObject();
            j.addProperty("t", s.t());
            j.addProperty("budget", s.budget());
            final JsonObject p = new JsonObject();
            s.goods().forEach((k, v) -> {
                final JsonArray a = new JsonArray();
                for (final long x : v) {
                    a.add(x);
                }
                p.add(k, a);
            });
            j.add("goods", p);
            hist.add(j);
        }
        o.add("history", hist);
        return o;
    }

    public static MerchantMarket fromJson(final JsonElement e, final long now, final ZoneId zone) {
        final MerchantMarket m = new MerchantMarket(now, zone);
        if (e == null || !e.isJsonObject()) {
            return m;
        }
        final JsonObject o = e.getAsJsonObject();
        m.updatedAt = o.has("updatedAt") ? Math.min(now, o.get("updatedAt").getAsLong()) : now;
        if (o.has("day")) {
            m.day = LocalDate.parse(o.get("day").getAsString());
        }
        if (o.has("goods")) {
            for (final Map.Entry<String, JsonElement> ge : o.getAsJsonObject("goods").entrySet()) {
                final JsonObject j = ge.getValue().getAsJsonObject();
                final Good g = m.good(ge.getKey());
                g.stock = Math.max(0, j.get("stock").getAsLong());
                g.reference = j.has("reference") ? j.get("reference").getAsDouble() : g.stock;
                g.carry = j.has("carry") ? j.get("carry").getAsDouble() : 0;
                g.boughtItems = j.has("boughtItems") ? j.get("boughtItems").getAsLong() : 0;
                g.soldItems = j.has("soldItems") ? j.get("soldItems").getAsLong() : 0;
                g.paid = j.has("paid") ? j.get("paid").getAsLong() : 0;
                g.received = j.has("received") ? j.get("received").getAsLong() : 0;
            }
        }
        if (o.has("history")) {
            for (final JsonElement he : o.getAsJsonArray("history")) {
                final JsonObject j = he.getAsJsonObject();
                final Map<String, long[]> goods = new LinkedHashMap<>();
                for (final Map.Entry<String, JsonElement> pe : j.getAsJsonObject("goods").entrySet()) {
                    final JsonArray a = pe.getValue().getAsJsonArray();
                    final long[] v = new long[a.size()];
                    for (int i = 0; i < v.length; i++) {
                        v[i] = a.get(i).getAsLong();
                    }
                    goods.put(pe.getKey(), v);
                }
                m.history.addLast(new Sample(j.get("t").getAsLong(), j.get("budget").getAsLong(), goods));
            }
        }
        return m;
    }
}
