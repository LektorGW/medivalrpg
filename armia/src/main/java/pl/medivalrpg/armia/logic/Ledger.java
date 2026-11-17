// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Money flows of an NPC country (for the 24 h economy breakdown). Armia records its own flows (AI income, wages,
 * recruitment, buildings, tax multiplier, tribute agreements) and copies Kraje's treasury history (claims, upkeep,
 * wars, sieges, vassal tribute). Pure functions over a mutable list kept in the NPC state.
 */
public final class Ledger {

    /** Income keys (PANEL.md). */
    public static final List<String> INCOME = List.of("npcTax", "aiIncome", "tribute", "other");
    /** Expense keys (PANEL.md). */
    public static final List<String> EXPENSES = List.of("wages", "land", "buildings", "recruit", "tribute", "war", "other");

    public static final long DAY = 86_400_000L;

    /**
     * One flow.
     *
     * @param time   epoch millis
     * @param key    income/expense key
     * @param amount signed: positive = income, negative = expense
     * @param actor  other party (may be empty)
     * @param note   Polish description (may be empty)
     */
    public record Entry(long time, String key, long amount, String actor, String note) {
    }

    /** Sums of one window. */
    public record Breakdown(Map<String, Long> income, Map<String, Long> expenses, List<Entry> newestFirst) {

        public long incomeTotal() {
            return this.income.values().stream().mapToLong(Long::longValue).sum();
        }

        public long expensesTotal() {
            return this.expenses.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private Ledger() {
    }

    /** Appends a flow (0 is ignored), drops flows older than {@code keepMs} and keeps at most {@code maxSize}. */
    public static void add(final List<Entry> ledger, final Entry e, final long now, final long keepMs, final int maxSize) {
        if (e.amount() != 0) {
            ledger.add(e);
        }
        ledger.removeIf(x -> x == null || now - x.time() > keepMs);
        while (ledger.size() > Math.max(1, maxSize)) {
            ledger.remove(0);
        }
    }

    /** Income / expenses of the last {@code windowMs} before {@code now}. Unknown keys count as {@code other}. */
    public static Breakdown breakdown(final List<Entry> ledger, final long now, final long windowMs) {
        final Map<String, Long> income = new LinkedHashMap<>();
        final Map<String, Long> expenses = new LinkedHashMap<>();
        final List<Entry> window = new ArrayList<>();
        for (final Entry e : ledger) {
            if (e == null || e.time() > now || now - e.time() > windowMs || e.amount() == 0) {
                continue;
            }
            window.add(e);
            if (e.amount() > 0) {
                income.merge(INCOME.contains(e.key()) ? e.key() : "other", e.amount(), Long::sum);
            } else {
                expenses.merge(EXPENSES.contains(e.key()) ? e.key() : "other", -e.amount(), Long::sum);
            }
        }
        window.sort((a, b) -> Long.compare(b.time(), a.time()));
        return new Breakdown(ordered(income, INCOME), ordered(expenses, EXPENSES), List.copyOf(window));
    }

    private static Map<String, Long> ordered(final Map<String, Long> m, final List<String> order) {
        final Map<String, Long> out = new LinkedHashMap<>();
        for (final String k : order) {
            final Long v = m.get(k);
            if (v != null && v > 0) {
                out.put(k, v);
            }
        }
        return out;
    }

    /**
     * Key of a Kraje treasury history line ({@code TreasuryLogEntry.type}), or null for lines Armia records itself or
     * that are no flow of the NPC economy.
     */
    public static String krajeKey(final String type) {
        if (type == null) {
            return "other";
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "claim", "upkeep" -> "land";
            case "war", "siege" -> "war";
            case "tribute", "buyout" -> "tribute";
            default -> "other";
        };
    }

    /** Polish note for a Kraje history line. */
    public static String krajeNote(final String type) {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "claim" -> "Zajęcie chunka";
            case "upkeep" -> "Utrzymanie ziemi";
            case "war" -> "Wypowiedzenie wojny";
            case "siege" -> "Oblężenie";
            case "tribute" -> "Trybut wasala";
            case "buyout" -> "Wykup z wasalstwa";
            case "loot" -> "Łupy wojenne";
            case "tax" -> "Podatki graczy";
            case "deposit" -> "Wpłata";
            case "withdraw" -> "Wypłata";
            default -> type == null ? "" : type;
        };
    }
}
