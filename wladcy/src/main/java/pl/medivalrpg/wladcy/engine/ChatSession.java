// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.engine;

import com.anthropic.models.beta.messages.BetaMessageParam;
import java.util.ArrayList;
import java.util.List;

/**
 * API-side state of one conversation: the frozen context block and the message history exactly as sent (assistant
 * turns with their thinking blocks, tool results). Append-only except for rolling back a failed turn's tail. Main
 * thread only.
 */
public final class ChatSession {

    private final String context;
    private final List<BetaMessageParam> history = new ArrayList<>();
    private final List<String> pendingEvents = new ArrayList<>();
    private boolean busy;
    private boolean endRequested;
    private int turns;

    public ChatSession(final String context) {
        this.context = context;
    }

    public String context() {
        return this.context;
    }

    public List<BetaMessageParam> history() {
        return this.history;
    }

    /** A game event (e.g. tribute paid by clicking) that the ruler learns about with the next player message. */
    public void addEvent(final String event) {
        this.pendingEvents.add(event);
    }

    List<String> takeEvents() {
        final List<String> e = List.copyOf(this.pendingEvents);
        this.pendingEvents.clear();
        return e;
    }

    void restoreEvents(final List<String> events) {
        this.pendingEvents.addAll(0, events);
    }

    void truncate(final int size) {
        while (this.history.size() > size) {
            this.history.remove(this.history.size() - 1);
        }
    }

    public boolean busy() {
        return this.busy;
    }

    void busy(final boolean busy) {
        this.busy = busy;
    }

    public boolean endRequested() {
        return this.endRequested;
    }

    public void requestEnd() {
        this.endRequested = true;
    }

    public int turns() {
        return this.turns;
    }

    void countTurn() {
        this.turns++;
    }
}
