// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pending consent requests. At most one pending request per (sender, target) pair. Time is passed in
 * explicitly. Main thread only.
 */
public final class RequestManager {

    public record Request(int id, UUID sender, UUID target, String interactionId, long expiresAt) {
        public boolean involves(UUID player) {
            return sender.equals(player) || target.equals(player);
        }
    }

    private final Map<Integer, Request> byId = new LinkedHashMap<>();
    private int nextId = 1;

    /** Pending (not yet expired) request from {@code sender} to {@code target}, or {@code null}. */
    public Request pending(UUID sender, UUID target, long now) {
        for (Request r : byId.values()) {
            if (r.sender.equals(sender) && r.target.equals(target) && r.expiresAt > now) {
                return r;
            }
        }
        return null;
    }

    /**
     * Creates a request.
     *
     * @return the new request, or {@code null} when the pair already has a pending one
     */
    public Request create(UUID sender, UUID target, String interactionId, long now, long timeoutMs) {
        Objects.requireNonNull(sender);
        Objects.requireNonNull(target);
        if (pending(sender, target, now) != null) {
            return null;
        }
        // an expired-but-not-yet-purged request of the same pair must not linger
        byId.values().removeIf(r -> r.sender.equals(sender) && r.target.equals(target));
        Request r = new Request(nextId++, sender, target, interactionId, now + timeoutMs);
        if (nextId == Integer.MAX_VALUE) {
            nextId = 1;
        }
        byId.put(r.id, r);
        return r;
    }

    /** Valid request with this id addressed to {@code target}; {@code null} when missing, expired or not theirs. */
    public Request find(int id, UUID target, long now) {
        Request r = byId.get(id);
        if (r == null || !r.target.equals(target) || r.expiresAt <= now) {
            return null;
        }
        return r;
    }

    /** Newest valid request addressed to {@code target}, or {@code null}. */
    public Request latestFor(UUID target, long now) {
        Request latest = null;
        for (Request r : byId.values()) {
            if (r.target.equals(target) && r.expiresAt > now) {
                latest = r; // insertion order = creation order
            }
        }
        return latest;
    }

    public void remove(Request r) {
        byId.remove(r.id);
    }

    /** Removes and returns requests that expired at {@code now}. */
    public List<Request> expire(long now) {
        List<Request> out = new ArrayList<>();
        Iterator<Request> it = byId.values().iterator();
        while (it.hasNext()) {
            Request r = it.next();
            if (r.expiresAt <= now) {
                out.add(r);
                it.remove();
            }
        }
        return out;
    }

    /** Removes and returns every request sent by or to {@code player} (e.g. on quit). */
    public List<Request> removeInvolving(UUID player) {
        List<Request> out = new ArrayList<>();
        Iterator<Request> it = byId.values().iterator();
        while (it.hasNext()) {
            Request r = it.next();
            if (r.involves(player)) {
                out.add(r);
                it.remove();
            }
        }
        return out;
    }

    public int size() {
        return byId.size();
    }

    public void clear() {
        byId.clear();
    }
}
