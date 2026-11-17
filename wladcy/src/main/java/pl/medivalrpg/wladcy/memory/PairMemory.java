// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * What one NPC ruler remembers about one player ({@code memory.json}): the last exchanges (trimmed), short notes the
 * ruler chose to remember, counters of game events and the ruler's own attitude. Plain fields for Gson.
 */
public final class PairMemory {

    /** One player message and the ruler's answer. */
    public static final class Exchange {
        public long at;
        public String player = "";
        public String ruler = "";

        public Exchange() {
        }

        public Exchange(final long at, final String player, final String ruler) {
            this.at = at;
            this.player = player;
            this.ruler = ruler;
        }
    }

    public String playerName = "";
    /** −100 (hatred) .. 100 (friendship); Wladcy's own attitude, separate from Armia's AI opinion. */
    public int sympathy;
    public int conversations;
    public int tributesPaid;
    public long tributePaidTotal;
    public int tributesRefused;
    public int giftsReceived;
    public long giftTotal;
    public int warsDeclared;
    public int alliancesFormed;
    public int peacesMade;
    public long lastTalkAt;
    public List<Exchange> exchanges = new ArrayList<>();
    public List<String> notes = new ArrayList<>();

    public void normalize() {
        if (this.exchanges == null) {
            this.exchanges = new ArrayList<>();
        }
        if (this.notes == null) {
            this.notes = new ArrayList<>();
        }
        if (this.playerName == null) {
            this.playerName = "";
        }
        this.sympathy = clampSympathy(this.sympathy);
    }

    public void addSympathy(final int delta) {
        this.sympathy = clampSympathy(this.sympathy + delta);
    }

    /** Appends an exchange and drops the oldest ones beyond {@code max}. */
    public void addExchange(final Exchange e, final int max) {
        this.exchanges.add(e);
        trim(this.exchanges, max);
    }

    /** Appends a note and drops the oldest ones beyond {@code max}. */
    public void addNote(final String note, final int max) {
        this.notes.add(note);
        trim(this.notes, max);
    }

    static <T> void trim(final List<T> list, final int max) {
        final int m = Math.max(0, max);
        while (list.size() > m) {
            list.remove(0);
        }
    }

    static int clampSympathy(final int v) {
        return Math.max(-100, Math.min(100, v));
    }

    /** Short Polish summary of the relation for the prompt (no exchanges), e.g. "zapłacił trybut 2 razy (…)". */
    public String summary(final java.util.function.LongFunction<String> money) {
        final List<String> parts = new ArrayList<>();
        if (this.conversations > 0) {
            parts.add("rozmów: " + this.conversations);
        }
        if (this.tributesPaid > 0) {
            parts.add("zapłacił trybut " + times(this.tributesPaid) + " (razem " + money.apply(this.tributePaidTotal) + ")");
        }
        if (this.tributesRefused > 0) {
            parts.add("odmówił trybutu " + times(this.tributesRefused));
        }
        if (this.giftsReceived > 0) {
            parts.add("otrzymał od ciebie dary " + times(this.giftsReceived) + " (razem " + money.apply(this.giftTotal) + ")");
        }
        if (this.alliancesFormed > 0) {
            parts.add("zawarliście sojusz " + times(this.alliancesFormed));
        }
        if (this.peacesMade > 0) {
            parts.add("zawarliście pokój " + times(this.peacesMade));
        }
        if (this.warsDeclared > 0) {
            parts.add("wypowiedziałeś mu wojnę " + times(this.warsDeclared));
        }
        return parts.isEmpty() ? "pierwsze spotkanie" : String.join("; ", parts);
    }

    static String times(final int n) {
        return n == 1 ? "1 raz" : n + " razy";
    }

    /** Polish word for an attitude value. */
    public static String attitudeWord(final int sympathy) {
        if (sympathy <= -60) {
            return "wrogie";
        }
        if (sympathy <= -20) {
            return "nieufne";
        }
        if (sympathy < 20) {
            return "obojętne";
        }
        if (sympathy < 60) {
            return "przychylne";
        }
        return "serdeczne";
    }
}
