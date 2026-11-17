// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import pl.medivalrpg.skarbiec.ledger.TxRecord;

/** Filters and aggregates ledger operations for the owner's statistics (pure Java, JSON-ready maps). */
public final class FlowQuery {

    public static final int MAX_LIMIT = 1000;
    public static final int MAX_WINDOW = 60_000;
    public static final int TOP = 15;
    /** Type of the missing side of pre-closed-supply DEPOSIT/WITHDRAW/SET operations. */
    public static final String EXTERNAL = "external";

    /** Query parameters; nulls/empties mean "no filter". */
    public record Filter(long from, long to, String account, String category, String type, String query, long minAmount,
                         int offset, int limit) {
        public Filter {
            limit = Math.max(1, Math.min(MAX_LIMIT, limit));
            offset = Math.max(0, Math.min(MAX_WINDOW - limit, offset));
            minAmount = Math.max(0, minAmount);
            query = query == null || query.isBlank() ? null : fold(query.strip());
            account = blank(account);
            category = blank(category);
            type = blank(type);
        }
    }

    private FlowQuery() {
    }

    private static String blank(final String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Lower case without Polish diacritics (ł→l), for searching. */
    public static String fold(final String s) {
        final String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('ł', 'l');
    }

    public static String typeOf(final String account) {
        return account == null ? EXTERNAL : AccountTypes.type(account);
    }

    public static Map<String, String> typeLabels() {
        final Map<String, String> m = new LinkedHashMap<>(AccountTypes.LABELS);
        m.put(EXTERNAL, "Poza obiegiem (operacje sprzed zamkniętej puli)");
        return m;
    }

    public static boolean matches(final TxRecord r, final Filter f, final Function<String, String> label) {
        if (r.amount() < f.minAmount()) {
            return false;
        }
        if (f.account() != null && !f.account().equals(r.from()) && !f.account().equals(r.to())) {
            return false;
        }
        if (f.type() != null && !f.type().equals(typeOf(r.from())) && !f.type().equals(typeOf(r.to()))) {
            return false;
        }
        if (f.category() != null && !f.category().equals(Categories.category(r.reason(), r.from(), r.to()))) {
            return false;
        }
        if (f.query() != null) {
            final String hay = fold((r.reason() == null ? "" : r.reason()) + " " + nz(r.from()) + " " + nz(r.to()) + " "
                + (r.from() == null ? "" : label.apply(r.from())) + " " + (r.to() == null ? "" : label.apply(r.to())));
            return hay.contains(f.query());
        }
        return true;
    }

    private static String nz(final String s) {
        return s == null ? "" : s;
    }

    public static Map<String, Object> row(final TxRecord r, final Function<String, String> label) {
        final Map<String, Object> m = new LinkedHashMap<>();
        final String cat = Categories.category(r.reason(), r.from(), r.to());
        m.put("seq", r.seq());
        m.put("time", r.time());
        m.put("type", r.type().name());
        m.put("from", r.from());
        m.put("fromLabel", r.from() == null ? null : label.apply(r.from()));
        m.put("fromType", typeOf(r.from()));
        m.put("to", r.to());
        m.put("toLabel", r.to() == null ? null : label.apply(r.to()));
        m.put("toType", typeOf(r.to()));
        m.put("amount", r.amount());
        m.put("fromAfter", r.fromAfter());
        m.put("toAfter", r.toAfter());
        m.put("reason", r.reason());
        m.put("category", cat);
        m.put("categoryLabel", Categories.label(cat));
        m.put("plugin", Categories.plugin(r.reason(), r.from(), r.to()));
        return m;
    }

    private static final class Agg {
        long count;
        long amount;
        long in;
        long out;

        void add(final long v) {
            this.count++;
            this.amount += v;
        }
    }

    private static final class DayAgg {
        long count;
        long volume;
        long in;
        long out;
        final Map<String, Long> byCategory = new TreeMap<>();
    }

    /** Runs the query over a record source that streams in ascending sequence order. */
    public static Map<String, Object> run(final Source source, final Filter f, final Function<String, String> label, final ZoneId zone,
                                          final int retentionDays, final Long oldest) throws IOException {
        final int window = f.offset() + f.limit();
        final Deque<TxRecord> newest = new ArrayDeque<>(Math.min(window, 4096));
        final long[] totals = new long[2];
        final TreeMap<LocalDate, DayAgg> daily = new TreeMap<>();
        final Map<String, Agg> categories = new LinkedHashMap<>();
        final Map<String, Agg> matrix = new LinkedHashMap<>();
        final Map<String, Agg> senders = new HashMap<>();
        final Map<String, Agg> receivers = new HashMap<>();
        final Map<String, Agg> plugins = new HashMap<>();
        source.forEach(f.from(), f.to(), r -> {
            if (!matches(r, f, label)) {
                return;
            }
            totals[0]++;
            totals[1] += r.amount();
            newest.addLast(r);
            if (newest.size() > window) {
                newest.removeFirst();
            }
            final String cat = Categories.category(r.reason(), r.from(), r.to());
            final String fromType = typeOf(r.from());
            final String toType = typeOf(r.to());
            final DayAgg d = daily.computeIfAbsent(Instant.ofEpochMilli(r.time()).atZone(zone).toLocalDate(), k -> new DayAgg());
            d.count++;
            d.volume += r.amount();
            d.byCategory.merge(cat, r.amount(), Long::sum);
            if (f.account() != null) {
                if (f.account().equals(r.to())) {
                    d.in += r.amount();
                }
                if (f.account().equals(r.from())) {
                    d.out += r.amount();
                }
            } else {
                final boolean fromPool = AccountTypes.WORLD_POOLS.contains(fromType);
                final boolean toPool = AccountTypes.WORLD_POOLS.contains(toType);
                if (fromPool && !toPool) {
                    d.in += r.amount();
                } else if (toPool && !fromPool) {
                    d.out += r.amount();
                }
            }
            categories.computeIfAbsent(cat, k -> new Agg()).add(r.amount());
            matrix.computeIfAbsent(fromType + "\u0000" + toType, k -> new Agg()).add(r.amount());
            if (r.from() != null) {
                senders.computeIfAbsent(r.from(), k -> new Agg()).add(r.amount());
            }
            if (r.to() != null) {
                receivers.computeIfAbsent(r.to(), k -> new Agg()).add(r.amount());
            }
            plugins.computeIfAbsent(Categories.plugin(r.reason(), r.from(), r.to()), k -> new Agg()).add(r.amount());
        });

        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<TxRecord> desc = new ArrayList<>(newest);
        java.util.Collections.reverse(desc);
        for (int i = f.offset(); i < desc.size() && rows.size() < f.limit(); i++) {
            rows.add(row(desc.get(i), label));
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f.from());
        out.put("to", f.to());
        out.put("retentionDays", retentionDays);
        out.put("oldest", oldest);
        out.put("total", totals[0]);
        out.put("offset", f.offset());
        out.put("limit", f.limit());
        out.put("sumAmount", totals[1]);
        out.put("rows", rows);
        final List<Map<String, Object>> days = new ArrayList<>();
        daily.forEach((day, a) -> {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", day.toString());
            m.put("t", day.atStartOfDay(zone).toInstant().toEpochMilli());
            m.put("count", a.count);
            m.put("volume", a.volume);
            m.put("in", a.in);
            m.put("out", a.out);
            m.put("byCategory", a.byCategory);
            days.add(m);
        });
        out.put("daily", days);
        final List<Map<String, Object>> cats = new ArrayList<>();
        categories.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, Agg> e) -> e.getValue().amount).reversed())
            .forEach(e -> cats.add(map("key", e.getKey(), "label", Categories.label(e.getKey()), "count", e.getValue().count, "volume", e.getValue().amount)));
        out.put("categories", cats);
        final List<Map<String, Object>> mx = new ArrayList<>();
        matrix.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, Agg> e) -> e.getValue().amount).reversed())
            .forEach(e -> {
                final String[] k = e.getKey().split("\u0000", 2);
                mx.add(map("from", k[0], "to", k[1], "amount", e.getValue().amount, "count", e.getValue().count));
            });
        out.put("matrix", mx);
        out.put("topSenders", top(senders, label));
        out.put("topReceivers", top(receivers, label));
        final List<Map<String, Object>> pl = new ArrayList<>();
        plugins.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, Agg> e) -> e.getValue().amount).reversed())
            .forEach(e -> pl.add(map("plugin", e.getKey(), "count", e.getValue().count, "volume", e.getValue().amount)));
        out.put("plugins", pl);
        out.put("categoryLabels", Categories.LABELS);
        out.put("typeLabels", typeLabels());
        return out;
    }

    private static List<Map<String, Object>> top(final Map<String, Agg> m, final Function<String, String> label) {
        final List<Map<String, Object>> out = new ArrayList<>();
        m.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, Agg> e) -> e.getValue().amount).reversed()
                .thenComparing(Map.Entry::getKey)).limit(TOP)
            .forEach(e -> out.add(map("account", e.getKey(), "label", label.apply(e.getKey()), "type", AccountTypes.type(e.getKey()),
                "amount", e.getValue().amount, "count", e.getValue().count)));
        return out;
    }

    /** Balance history, categories and counterparties of one account. */
    public static Map<String, Object> account(final Source source, final String account, final long from, final long to,
                                              final long balanceNow, final Function<String, String> label) throws IOException {
        final List<long[]> points = new ArrayList<>();
        final Deque<TxRecord> last = new ArrayDeque<>();
        final Map<String, Agg> cats = new LinkedHashMap<>();
        final Map<String, Agg> parties = new HashMap<>();
        final long[] sums = new long[3];
        final Long[] startBalance = {null};
        source.forEach(from, to, r -> {
            final boolean in = account.equals(r.to());
            final boolean out = account.equals(r.from());
            if (!in && !out) {
                return;
            }
            sums[2]++;
            final Long after = out ? r.fromAfter() : r.toAfter();
            final long signed = r.type() == TxRecord.Type.SET ? 0 : in ? r.amount() : -r.amount();
            if (startBalance[0] == null && after != null) {
                startBalance[0] = r.type() == TxRecord.Type.SET ? after : after - signed;
            }
            if (after != null) {
                points.add(new long[] {r.time(), after});
            }
            final String cat = Categories.category(r.reason(), r.from(), r.to());
            final Agg c = cats.computeIfAbsent(cat, k -> new Agg());
            c.count++;
            final String other = in ? r.from() : r.to();
            final Agg p = other == null ? null : parties.computeIfAbsent(other, k -> new Agg());
            if (p != null) {
                p.count++;
            }
            if (in && r.type() != TxRecord.Type.SET) {
                sums[0] += r.amount();
                c.in += r.amount();
                if (p != null) {
                    p.in += r.amount();
                }
            }
            if (out) {
                sums[1] += r.amount();
                c.out += r.amount();
                if (p != null) {
                    p.out += r.amount();
                }
            }
            last.addLast(r);
            if (last.size() > 200) {
                last.removeFirst();
            }
        });
        final List<Map<String, Object>> pts = new ArrayList<>();
        pts.add(map("t", from, "balance", startBalance[0] != null ? startBalance[0] : balanceNow));
        for (final long[] p : downsample(points, 498)) {
            pts.add(map("t", p[0], "balance", p[1]));
        }
        pts.add(map("t", to, "balance", balanceNow));
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("in", sums[0]);
        out.put("out", sums[1]);
        out.put("count", sums[2]);
        out.put("points", pts);
        final List<Map<String, Object>> c = new ArrayList<>();
        cats.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, Agg> e) -> e.getValue().in + e.getValue().out).reversed())
            .forEach(e -> c.add(map("key", e.getKey(), "label", Categories.label(e.getKey()), "in", e.getValue().in, "out", e.getValue().out,
                "count", e.getValue().count)));
        out.put("categories", c);
        final List<Map<String, Object>> cp = new ArrayList<>();
        parties.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, Agg> e) -> e.getValue().in + e.getValue().out).reversed())
            .limit(50).forEach(e -> cp.add(map("account", e.getKey(), "label", label.apply(e.getKey()), "type", AccountTypes.type(e.getKey()),
                "in", e.getValue().in, "out", e.getValue().out, "count", e.getValue().count)));
        out.put("counterparties", cp);
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<TxRecord> desc = new ArrayList<>(last);
        java.util.Collections.reverse(desc);
        for (final TxRecord r : desc) {
            rows.add(row(r, label));
        }
        out.put("rows", rows);
        return out;
    }

    /** Keeps at most {@code max} points, preferring the last point of each bucket (balance after the last operation). */
    public static List<long[]> downsample(final List<long[]> points, final int max) {
        if (points.size() <= max) {
            return points;
        }
        final List<long[]> out = new ArrayList<>(max);
        final double step = (double) points.size() / max;
        for (int i = 1; i <= max; i++) {
            final int idx = Math.min(points.size() - 1, (int) Math.ceil(i * step) - 1);
            out.add(points.get(idx));
        }
        return out;
    }

    /** Last activity, 7-day counters per account. */
    public static Map<String, long[]> activity(final Source source, final long from, final long weekFrom, final long to) throws IOException {
        final Map<String, long[]> out = new HashMap<>(); // lastActivity, count7d, in7d, out7d
        source.forEach(from, to, r -> {
            final boolean week = r.time() >= weekFrom;
            if (r.from() != null) {
                final long[] a = out.computeIfAbsent(r.from(), k -> new long[4]);
                a[0] = Math.max(a[0], r.time());
                if (week) {
                    a[1]++;
                    a[3] += r.amount();
                }
            }
            if (r.to() != null) {
                final long[] a = out.computeIfAbsent(r.to(), k -> new long[4]);
                a[0] = Math.max(a[0], r.time());
                if (week) {
                    a[1]++;
                    a[2] += r.type() == TxRecord.Type.SET ? 0 : r.amount();
                }
            }
        });
        return out;
    }

    /** A stream of records in ascending sequence order ({@link FlowIndex#forEach} or a list in tests). */
    @FunctionalInterface
    public interface Source {
        void forEach(long from, long to, java.util.function.Consumer<TxRecord> sink) throws IOException;

        static Source of(final List<TxRecord> records) {
            return (from, to, sink) -> records.stream().filter(r -> r.time() >= from && r.time() <= to)
                .sorted(Comparator.comparingLong(TxRecord::seq)).forEach(sink);
        }
    }

    static Map<String, Object> map(final Object... kv) {
        final Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
