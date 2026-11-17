// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import java.util.Locale;

/** Tryb głosu gracza. {@link #id()} to identyfikator z protokołu ({@code whisper|normal|shout}). */
public enum VoiceMode {
    WHISPER("whisper"),
    NORMAL("normal"),
    SHOUT("shout");

    private final String id;

    VoiceMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Zwraca tryb dla identyfikatora z protokołu albo {@code null}, gdy wartość jest nieprawidłowa. */
    public static VoiceMode fromId(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        for (VoiceMode mode : values()) {
            if (mode.id.equals(s)) {
                return mode;
            }
        }
        return null;
    }
}
