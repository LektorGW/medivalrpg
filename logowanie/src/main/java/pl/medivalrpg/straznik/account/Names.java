// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.account;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Nick helpers shared by the store, the importer and the login checks. */
public final class Names {

    private Names() {
    }

    /** Case-insensitive account key. */
    public static String key(final String nick) {
        return nick.toLowerCase(Locale.ROOT);
    }

    /** UUID the server gives a nick in offline mode ({@code OfflinePlayer:<nick>}, case-sensitive). */
    public static UUID offlineUuid(final String nick) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + nick).getBytes(StandardCharsets.UTF_8));
    }

    /** nLogin stores UUIDs as 32 hex digits without dashes. */
    public static UUID parseCompactUuid(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.strip();
        try {
            if (t.length() == 32) {
                return new UUID(Long.parseUnsignedLong(t.substring(0, 16), 16), Long.parseUnsignedLong(t.substring(16), 16));
            }
            return t.length() == 36 ? UUID.fromString(t) : null;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public static String compactUuid(final UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
