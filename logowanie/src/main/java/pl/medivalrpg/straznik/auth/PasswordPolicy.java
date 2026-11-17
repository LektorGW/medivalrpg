// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.auth;

import java.util.Locale;
import java.util.Set;

/**
 * Rules for NEW passwords (register, change, admin set). Existing imported passwords are not re-validated: they keep
 * working until the player changes them. Returns the key of the message that explains the problem, or {@code null}.
 */
public final class PasswordPolicy {

    public static final String TOO_SHORT = "haslo-za-krotkie";
    public static final String TOO_LONG = "haslo-za-dlugie";
    public static final String WHITESPACE = "haslo-spacja";
    public static final String WEAK = "haslo-slabe";
    public static final String CONTAINS_NICK = "haslo-nick";

    private final int min;
    private final int max;
    private final boolean forbidNick;
    private final Set<String> weak;

    public PasswordPolicy(final int min, final int max, final boolean forbidNick, final Set<String> weak) {
        this.min = min;
        this.max = max;
        this.forbidNick = forbidNick;
        this.weak = weak;
    }

    public int min() {
        return this.min;
    }

    public int max() {
        return this.max;
    }

    public String check(final String nick, final String password) {
        final int length = password.codePointCount(0, password.length());
        if (length < this.min) {
            return TOO_SHORT;
        }
        if (length > this.max) {
            return TOO_LONG;
        }
        for (int i = 0; i < password.length(); i++) {
            if (Character.isWhitespace(password.charAt(i)) || Character.isISOControl(password.charAt(i))) {
                return WHITESPACE;
            }
        }
        final String lower = password.toLowerCase(Locale.ROOT);
        if (this.weak.contains(lower) || password.chars().distinct().count() <= 2 || isSequence(lower)) {
            return WEAK;
        }
        if (this.forbidNick && nick != null && nick.length() >= 3 && lower.contains(nick.toLowerCase(Locale.ROOT))) {
            return CONTAINS_NICK;
        }
        return null;
    }

    /** {@code 123456}, {@code abcdef}, {@code 987654} and similar runs. */
    static boolean isSequence(final String s) {
        if (s.length() < 4) {
            return false;
        }
        final int step = s.charAt(1) - s.charAt(0);
        if (step != 1 && step != -1) {
            return false;
        }
        for (int i = 2; i < s.length(); i++) {
            if (s.charAt(i) - s.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }
}
