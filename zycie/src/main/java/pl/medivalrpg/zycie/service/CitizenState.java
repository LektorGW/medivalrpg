// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.ArrayDeque;
import java.util.Deque;
import org.bukkit.Location;
import pl.medivalrpg.zycie.logic.Destination;

/** Transient behaviour state of one loaded citizen (never saved). Main thread only. */
final class CitizenState {

    /** Day × phase × destination of the current plan; a change resets the steering. */
    long planKey = Long.MIN_VALUE;
    Destination destination = Destination.NONE;
    /** Steering target for the current plan, null = vanilla. */
    Location target;
    boolean targetResolved;
    boolean arrived;
    long lastMoveTick = -1_000_000L;
    int failures;
    long blockedUntilTick;
    long nextBubbleMs;
    long fleeUntilMs;
    long talkUntilMs;
    long nextTalkMs;
    long lastSeenMs;
    final Deque<String> recent = new ArrayDeque<>();

    void remember(final String line) {
        this.recent.addLast(line);
        while (this.recent.size() > 4) {
            this.recent.removeFirst();
        }
    }
}
