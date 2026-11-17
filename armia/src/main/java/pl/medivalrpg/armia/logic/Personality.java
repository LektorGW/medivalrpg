// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.List;
import java.util.Locale;

/**
 * Settings of one NPC country ("Władca NPC"). Traits are 0–100. Pure value, no Bukkit.
 *
 * @param aggression  agresja — willingness to declare war and to refuse peace
 * @param expansion   ekspansja — how eagerly wilderness chunks are claimed
 * @param diplomacy   dyplomacja — willingness to accept/propose alliances and peace
 * @param wealth      bogactwo — virtual income multiplier and how much money is kept in reserve
 * @param maxChunks   max-chunki — territory cap
 * @param maxSoldiers max-zolnierze — army cap (the global/country limits still apply)
 * @param active      aktywna — AI enabled for this country
 */
public record Personality(int aggression, int expansion, int diplomacy, int wealth, int maxChunks, int maxSoldiers, boolean active) {

    /** Parameter names accepted by {@link #with(String, String)} (Polish, as typed in {@code /nk ustaw}). */
    public static final List<String> PARAMS = List.of("agresja", "ekspansja", "dyplomacja", "bogactwo", "max-chunki", "max-zolnierze", "aktywna");

    public Personality {
        aggression = clamp(aggression);
        expansion = clamp(expansion);
        diplomacy = clamp(diplomacy);
        wealth = clamp(wealth);
        maxChunks = Math.max(1, maxChunks);
        maxSoldiers = Math.max(0, maxSoldiers);
    }

    public static int clamp(final int trait) {
        return Math.max(0, Math.min(100, trait));
    }

    /**
     * Copy with one parameter changed.
     *
     * @throws IllegalArgumentException unknown parameter or a value that does not parse (message in Polish)
     */
    public Personality with(final String param, final String value) {
        final String p = param.toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (p) {
            case "agresja" -> new Personality(trait(value), this.expansion, this.diplomacy, this.wealth, this.maxChunks, this.maxSoldiers, this.active);
            case "ekspansja" -> new Personality(this.aggression, trait(value), this.diplomacy, this.wealth, this.maxChunks, this.maxSoldiers, this.active);
            case "dyplomacja" -> new Personality(this.aggression, this.expansion, trait(value), this.wealth, this.maxChunks, this.maxSoldiers, this.active);
            case "bogactwo" -> new Personality(this.aggression, this.expansion, this.diplomacy, trait(value), this.maxChunks, this.maxSoldiers, this.active);
            case "max-chunki" -> new Personality(this.aggression, this.expansion, this.diplomacy, this.wealth, atLeast(value, 1), this.maxSoldiers, this.active);
            case "max-zolnierze" -> new Personality(this.aggression, this.expansion, this.diplomacy, this.wealth, this.maxChunks, atLeast(value, 0), this.active);
            case "aktywna" -> new Personality(this.aggression, this.expansion, this.diplomacy, this.wealth, this.maxChunks, this.maxSoldiers, bool(value));
            default -> throw new IllegalArgumentException("Nieznany parametr: " + param + " (dostępne: " + String.join(", ", PARAMS) + ")");
        };
    }

    /** Current value of a parameter as text. */
    public String get(final String param) {
        return switch (param.toLowerCase(Locale.ROOT)) {
            case "agresja" -> Integer.toString(this.aggression);
            case "ekspansja" -> Integer.toString(this.expansion);
            case "dyplomacja" -> Integer.toString(this.diplomacy);
            case "bogactwo" -> Integer.toString(this.wealth);
            case "max-chunki" -> Integer.toString(this.maxChunks);
            case "max-zolnierze" -> Integer.toString(this.maxSoldiers);
            case "aktywna" -> this.active ? "tak" : "nie";
            default -> "?";
        };
    }

    private static int trait(final String value) {
        final int v = parseInt(value);
        if (v < 0 || v > 100) {
            throw new IllegalArgumentException("Wartość musi być liczbą 0–100: " + value);
        }
        return v;
    }

    private static int atLeast(final String value, final int min) {
        final int v = parseInt(value);
        if (v < min) {
            throw new IllegalArgumentException("Wartość musi być liczbą co najmniej " + min + ": " + value);
        }
        return v;
    }

    private static int parseInt(final String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("To nie jest liczba: " + value);
        }
    }

    /** Key of the optional honor trait (stored outside the personality; see {@code NpcState#honor}). */
    public static final String HONOR = "honor";

    /**
     * Value of {@code /nk ustaw <kraj> honor <wartość>}: 0–100, or {@code auto} (null = derived from the personality).
     *
     * @throws IllegalArgumentException with a Polish message
     */
    public static Integer honorValue(final String value) {
        final String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("auto") || v.equals("brak") || v.equals("-")) {
            return null;
        }
        return trait(value);
    }

    /** {@code tak/nie} (also true/false, 1/0, on/off). */
    public static boolean bool(final String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "tak", "true", "1", "on", "wlacz", "włącz" -> true;
            case "nie", "false", "0", "off", "wylacz", "wyłącz" -> false;
            default -> throw new IllegalArgumentException("Podaj tak albo nie: " + value);
        };
    }
}
