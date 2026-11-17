// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.alert;

import java.util.List;

/**
 * An immediate alert: kind, short title and body lines (plain text, Polish).
 *
 * @param kind  POOL, SEVERE, RAIL, TPS, BACKUP, INFO
 * @param key   deduplication key
 */
public record Alert(String kind, String key, String title, List<String> lines, long time) {

    public Alert {
        lines = List.copyOf(lines);
    }

    /** Emoji per kind for Discord and the files. */
    public String icon() {
        return switch (this.kind) {
            case "POOL" -> "💰";
            case "SEVERE" -> "🛑";
            case "RAIL" -> "🚂";
            case "TPS" -> "🐢";
            case "BACKUP" -> "💾";
            default -> "ℹ️";
        };
    }

    /** Recovery/info messages are green, problems red. */
    public boolean problem() {
        return !"INFO".equals(this.kind);
    }
}
