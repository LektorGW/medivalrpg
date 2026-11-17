// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ostatnio wydany token linku per gracz. Ponowne {@code /glos} w krótkim czasie wysyła ten sam link
 * zamiast rejestrować nowy token w Workerze – spamowanie komendą nie zużywa zapisów Durable Objectu.
 * Tylko główny wątek, bez Bukkit API.
 */
final class RecentTokens {
    private record Issued(String token, long at) {
    }

    private final long reuseMs;
    private final Map<UUID, Issued> issued = new HashMap<>();

    RecentTokens(long reuseMs) {
        this.reuseMs = reuseMs;
    }

    /** @return token wydany temu graczowi mniej niż {@code reuseMs} temu albo {@code null}. */
    String reusable(UUID id, long now) {
        Issued i = issued.get(id);
        if (i == null) {
            return null;
        }
        long age = now - i.at;
        if (age < 0 || age >= reuseMs) {
            issued.remove(id);
            return null;
        }
        return i.token;
    }

    void issued(UUID id, String token, long now) {
        issued.put(id, new Issued(token, now));
    }

    /** Token został unieważniony w Workerze (kick) – nie wolno go już podawać. */
    void forget(UUID id) {
        issued.remove(id);
    }

    void clear() {
        issued.clear();
    }
}
