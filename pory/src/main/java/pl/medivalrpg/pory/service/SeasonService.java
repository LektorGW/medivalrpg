// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.service;

import java.util.Optional;
import java.util.logging.Logger;
import pl.medivalrpg.pory.logic.VisualSeason;

/** Reads the one server season clock (Ludnosc). Main thread only. */
public final class SeasonService {

    /**
     * @param fromLudnosc false when Ludnosc is missing/disabled and the fallback season is shown
     */
    public record State(VisualSeason season, int day, int days, long startedAt, long endsAt, boolean fromLudnosc) {
    }

    private final Logger log;
    private State state;
    private boolean warnedMissing;

    public SeasonService(final Logger log, final VisualSeason fallback) {
        this.log = log;
        this.state = fallbackState(fallback);
    }

    private static State fallbackState(final VisualSeason fallback) {
        return new State(fallback, 1, 1, 0, 0, false);
    }

    public State state() {
        return this.state;
    }

    /** Polls the clock; returns true when season or day changed. */
    public boolean poll(final VisualSeason fallback) {
        Optional<State> read;
        try {
            read = LudnoscBridge.read();
        } catch (final LinkageError | RuntimeException e) {
            read = Optional.empty();
            if (!this.warnedMissing) {
                this.log.warning("Zegar pór roku Ludnosc niedostępny (" + e.getClass().getSimpleName()
                    + ") — pokazuję porę zapasową: " + fallback.id());
            }
        }
        if (read.isEmpty() && !this.warnedMissing) {
            this.warnedMissing = true;
            this.log.info("Brak pory roku z Ludnosc — świat wygląda jak: " + fallback.id());
        } else if (read.isPresent()) {
            this.warnedMissing = false;
        }
        final State next = read.orElseGet(() -> fallbackState(fallback));
        final boolean changed = next.season() != this.state.season() || next.day() != this.state.day()
            || next.fromLudnosc() != this.state.fromLudnosc();
        this.state = next;
        return changed;
    }
}
