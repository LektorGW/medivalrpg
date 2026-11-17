// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.economy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closed world pool read from Skarbiec's {@code bank/accounts.json} (pure): total, breakdown into the main groups and
 * the difference to the expected pool. accounts.json is a periodic snapshot; operations after it are replayed from
 * {@code journal.log} and the day's transaction log, so the result is the live state of the bank.
 */
public final class Pool {

    /** One line of the breakdown. */
    public record Part(String label, long amount, int accounts) {
    }

    /** Parsed pool. */
    public record Snapshot(long seq, String saved, long total, long expected, List<Part> parts, Map<String, Long> balances,
                           int accountCount, int negative, int replayed, boolean gaps) {

        public long diff() {
            return this.total - this.expected;
        }

        /** A gap in the replayed sequence means the cut is not certain (file being rotated): do not alert on it. */
        public boolean certain() {
            return !this.gaps;
        }

        public boolean ok() {
            return this.total == this.expected && this.negative == 0;
        }

        public long balance(final String account) {
            return this.balances.getOrDefault(account, 0L);
        }
    }

    private Pool() {
    }

    /** Main accounts shown one by one, in this order. */
    static final Map<String, String> MAIN = new LinkedHashMap<>();

    static {
        MAIN.put("world:reserve", "Rezerwa Skarbu Świata");
        MAIN.put("world:households", "Gospodarstwa NPC (households)");
        MAIN.put("world:merchant", "Kupiec (merchant)");
        MAIN.put("world:payments", "Budżet operacyjny (payments)");
        MAIN.put("world:cash", "Monety fizyczne w obiegu (cash)");
    }

    public static Snapshot parse(final String json, final long expected) {
        return parse(json, List.of(), expected);
    }

    /**
     * Snapshot {@code accounts.json} + replay of the journal/transaction lines with a higher sequence number
     * (each record sets the logged "balance after" of its accounts — the same idempotent replay Skarbiec uses on load).
     */
    public static Snapshot parse(final String json, final List<String> journal, final long expected) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final JsonObject accounts = root.getAsJsonObject("accounts");
        if (accounts == null) {
            throw new IllegalArgumentException("Brak sekcji accounts");
        }
        final Map<String, Long> balances = new java.util.TreeMap<>();
        for (final Map.Entry<String, JsonElement> e : accounts.entrySet()) {
            balances.put(e.getKey(), e.getValue().getAsLong());
        }
        long seq = root.has("seq") ? root.get("seq").getAsLong() : -1;
        // the snapshot is periodic: replay the journal/transaction lines after it, exactly like Skarbiec on load
        final java.util.TreeMap<Long, String[]> records = new java.util.TreeMap<>();
        for (final String line : journal) {
            final String[] f = line.split("\t", 9);
            if (f.length < 8) {
                continue;
            }
            try {
                final long s = Long.parseLong(f[0]);
                if (s > seq) {
                    records.putIfAbsent(s, f);
                }
            } catch (final NumberFormatException ignored) {
                // torn line
            }
        }
        boolean gaps = false;
        for (final Map.Entry<Long, String[]> r : records.entrySet()) {
            if (r.getKey() != seq + 1) {
                gaps = true;
            }
            final String[] f = r.getValue();
            try {
                if (!"-".equals(f[3]) && !"-".equals(f[6])) {
                    balances.put(f[3], Long.parseLong(f[6]));
                }
                if (!"-".equals(f[4]) && !"-".equals(f[7])) {
                    balances.put(f[4], Long.parseLong(f[7]));
                }
            } catch (final NumberFormatException e) {
                gaps = true;
            }
            seq = r.getKey();
        }
        long total = 0;
        int negative = 0;
        for (final long v : balances.values()) {
            total = Math.addExact(total, v);
            if (v < 0) {
                negative++;
            }
        }
        final List<Part> parts = new ArrayList<>();
        for (final Map.Entry<String, String> m : MAIN.entrySet()) {
            if (balances.containsKey(m.getKey())) {
                parts.add(new Part(m.getValue(), balances.get(m.getKey()), 1));
            }
        }
        long kolej = 0, countries = 0, players = 0, other = 0;
        int nk = 0, nc = 0, np = 0, no = 0;
        for (final Map.Entry<String, Long> e : balances.entrySet()) {
            final String k = e.getKey();
            if (MAIN.containsKey(k)) {
                continue;
            }
            if (k.startsWith("kolej:")) {
                kolej += e.getValue();
                nk++;
            } else if (k.startsWith("country:")) {
                countries += e.getValue();
                nc++;
            } else if (k.startsWith("player:")) {
                players += e.getValue();
                np++;
            } else {
                other += e.getValue();
                no++;
            }
        }
        if (nc > 0) {
            parts.add(new Part("Skarbce krajów", countries, nc));
        }
        if (np > 0) {
            parts.add(new Part("Konta graczy", players, np));
        }
        if (nk > 0) {
            parts.add(new Part("Kolej (przewoźnik i budżety)", kolej, nk));
        }
        if (no > 0) {
            parts.add(new Part("Pozostałe konta", other, no));
        }
        final String saved = root.has("saved") ? root.get("saved").getAsString() : "";
        return new Snapshot(seq, saved, total, expected, List.copyOf(parts), Map.copyOf(balances), balances.size(), negative,
            records.size(), gaps);
    }
}
