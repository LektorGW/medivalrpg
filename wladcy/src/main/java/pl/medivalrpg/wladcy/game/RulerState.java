// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent server-side counters of rulers ({@code state.json}): wars declared in conversations (cooldown) and
 * gifts given today. Main thread only.
 */
public final class RulerState {

    /** JSON shape. */
    public static final class Data {
        public Map<String, Long> lastWarAt = new HashMap<>();
        public String giftDay = "";
        public Map<String, Long> giftsByRuler = new HashMap<>();
        public Map<String, Long> giftsByPair = new HashMap<>();
    }

    private final Clock clock;
    private final Data data;
    private boolean dirty;

    public RulerState(final Clock clock, final Data loaded) {
        this.clock = clock;
        this.data = loaded == null ? new Data() : loaded;
        if (this.data.lastWarAt == null) {
            this.data.lastWarAt = new HashMap<>();
        }
        if (this.data.giftsByRuler == null) {
            this.data.giftsByRuler = new HashMap<>();
        }
        if (this.data.giftsByPair == null) {
            this.data.giftsByPair = new HashMap<>();
        }
        if (this.data.giftDay == null) {
            this.data.giftDay = "";
        }
    }

    public long lastWarAt(final UUID npc) {
        return this.data.lastWarAt.getOrDefault(npc.toString(), 0L);
    }

    public void warDeclared(final UUID npc) {
        this.data.lastWarAt.put(npc.toString(), this.clock.millis());
        this.dirty = true;
    }

    private void rollGifts() {
        final String today = LocalDate.now(this.clock).toString();
        if (!today.equals(this.data.giftDay)) {
            this.data.giftDay = today;
            this.data.giftsByRuler.clear();
            this.data.giftsByPair.clear();
            this.dirty = true;
        }
    }

    public long giftedToday(final UUID npc) {
        this.rollGifts();
        return this.data.giftsByRuler.getOrDefault(npc.toString(), 0L);
    }

    public long giftedToday(final UUID npc, final UUID player) {
        this.rollGifts();
        return this.data.giftsByPair.getOrDefault(npc + "|" + player, 0L);
    }

    public void gift(final UUID npc, final UUID player, final long amount) {
        this.rollGifts();
        this.data.giftsByRuler.merge(npc.toString(), amount, Long::sum);
        this.data.giftsByPair.merge(npc + "|" + player, amount, Long::sum);
        this.dirty = true;
    }

    public boolean dirty() {
        return this.dirty;
    }

    public Data snapshot() {
        this.dirty = false;
        final Data d = new Data();
        d.lastWarAt = new HashMap<>(this.data.lastWarAt);
        d.giftDay = this.data.giftDay;
        d.giftsByRuler = new HashMap<>(this.data.giftsByRuler);
        d.giftsByPair = new HashMap<>(this.data.giftsByPair);
        return d;
    }
}
