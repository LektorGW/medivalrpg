// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

/**
 * Named reputation tiers of a player with one country (value −100…100). Thresholds are fixed so every module and
 * the ruler's prompt speak the same language. Pure.
 */
public enum RepTier {
    /** ≤ −60: no quests, no gifts. */
    WROG("wrog", "Wróg", "<dark_red>", -100,
        "uważasz go za wroga swojego kraju: mówisz chłodno albo wrogo, nie dajesz mu zleceń ani darów"),
    /** −59 … −20: only simple deliveries. */
    NIEUFNY("nieufny", "Nieufny", "<red>", -59,
        "nie ufasz mu: pilnujesz go, powierzasz najwyżej proste dostawy i płacisz skąpiej"),
    /** −19 … 19: a stranger. */
    OBCY("obcy", "Obcy", "<gray>", -19,
        "jeszcze go nie znasz: uprzejmie, ale z rezerwą; niech się wykaże przy zleceniach"),
    /** 20 … 49. */
    ZNAJOMY("znajomy", "Znajomy", "<green>", 20,
        "znasz go z rzetelnej roboty: życzliwie, możesz mu powierzyć ważniejsze sprawy"),
    /** 50 … 79. */
    PRZYJACIEL("przyjaciel", "Przyjaciel", "<aqua>", 50,
        "to przyjaciel dworu: serdecznie i z zaufaniem, płacisz hojniej"),
    /** ≥ 80. */
    BOHATER("bohater", "Bohater", "<gold>", 80,
        "to bohater twojego kraju: przyjmujesz go z honorami i wdzięcznością");

    public static final int MIN = -100;
    public static final int MAX = 100;

    private final String key;
    private final String displayName;
    private final String colorTag;
    private final int from;
    private final String tone;

    RepTier(final String key, final String displayName, final String colorTag, final int from, final String tone) {
        this.key = key;
        this.displayName = displayName;
        this.colorTag = colorTag;
        this.from = from;
        this.tone = tone;
    }

    /** Config key, e.g. {@code przyjaciel}. */
    public String key() {
        return this.key;
    }

    /** Polish name shown to players, e.g. "Przyjaciel". */
    public String displayName() {
        return this.displayName;
    }

    /** MiniMessage colour tag (from code, never player text). */
    public String colorTag() {
        return this.colorTag;
    }

    /** Lowest value of the tier. */
    public int from() {
        return this.from;
    }

    /** How the ruler treats a player of this tier (Polish, for the prompt). */
    public String tone() {
        return this.tone;
    }

    public boolean atLeast(final RepTier other) {
        return this.ordinal() >= other.ordinal();
    }

    public static int clamp(final int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    public static RepTier of(final int value) {
        final int v = clamp(value);
        RepTier best = WROG;
        for (final RepTier t : values()) {
            if (v >= t.from) {
                best = t;
            }
        }
        return best;
    }

    /** Points still needed to reach the next tier (0 at {@link #BOHATER}). */
    public static int toNext(final int value) {
        final RepTier t = of(value);
        if (t == BOHATER) {
            return 0;
        }
        return values()[t.ordinal() + 1].from - clamp(value);
    }
}
