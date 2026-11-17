// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.zycie.ZyciePlugin;
import pl.medivalrpg.zycie.config.ZycieConfig;
import pl.medivalrpg.zycie.logic.TextContext;

/**
 * Dialogues of two citizens standing next to each other: 2–3 lines from the {@code rozmowy} pool, shown alternately
 * above both, who turn to each other. Candidates are offered by {@link LifeService} (only citizens with a player
 * nearby), so nothing is searched in the world. Main thread only.
 */
public final class ConversationService {

    private final ZyciePlugin plugin;
    private final LudnoscApi ludnosc;
    private final LifeService life;
    private final Map<UUID, UUID> candidates = new LinkedHashMap<>();
    private ZycieConfig cfg;
    private long started;

    public ConversationService(final ZyciePlugin plugin, final LudnoscApi ludnosc, final LifeService life) {
        this.plugin = plugin;
        this.ludnosc = ludnosc;
        this.life = life;
    }

    public void configure(final ZycieConfig config) {
        this.cfg = config;
    }

    public long started() {
        return this.started;
    }

    /** A citizen with a player nearby (from the life rotation). */
    void offer(final UUID villager, final UUID country) {
        if (this.candidates.size() >= 64) {
            final Iterator<UUID> it = this.candidates.keySet().iterator();
            it.next();
            it.remove();
        }
        this.candidates.put(villager, country);
    }

    public void tick() {
        if (this.candidates.isEmpty() || this.cfg.dialogues.size() == 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        final List<Map.Entry<UUID, UUID>> list = new ArrayList<>(this.candidates.entrySet());
        this.candidates.clear();
        int attempts = 0;
        for (int i = list.size() - 1; i >= 0 && attempts < 3; i--) {
            final Map.Entry<UUID, UUID> c = list.get(i);
            if (ThreadLocalRandom.current().nextDouble() >= this.cfg.talkChance) {
                continue;
            }
            attempts++;
            final Entity e = Bukkit.getEntity(c.getKey());
            if (e instanceof Villager a && a.isValid() && this.free(a, now)) {
                this.findPartner(a, now).ifPresent(b -> this.start(a, b, c.getValue(), now));
            }
        }
    }

    private boolean free(final Villager v, final long now) {
        final CitizenState s = this.life.state(v.getUniqueId());
        return !v.isSleeping() && !v.isTrading() && s.talkUntilMs <= now && s.nextTalkMs <= now && s.fleeUntilMs <= now
            && !this.plugin.fires().hasPriority(v.getUniqueId(), now);
    }

    private Optional<Villager> findPartner(final Villager a, final long now) {
        final double d = this.cfg.talkDistance;
        Villager best = null;
        double bestD = Double.MAX_VALUE;
        for (final Entity e : a.getNearbyEntities(d, 1.5, d)) {
            if (e instanceof Villager b && b.isValid() && this.free(b, now) && this.ludnosc.countryOf(b).isPresent()) {
                final double dist = b.getLocation().distanceSquared(a.getLocation());
                if (dist < bestD) {
                    bestD = dist;
                    best = b;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private void start(final Villager a, final Villager b, final UUID country, final long now) {
        final TextContext ctx = this.life.context(a, country, null);
        final Optional<List<String>> dialogue = this.cfg.dialogues.pick(ctx, ThreadLocalRandom.current(),
            this.life.state(a.getUniqueId()).recent);
        if (dialogue.isEmpty() || dialogue.get().isEmpty()) {
            return;
        }
        final List<String> lines = dialogue.get();
        final int gap = this.cfg.talkGap;
        final long duration = (long) lines.size() * gap * 50L;
        for (final Villager v : List.of(a, b)) {
            final CitizenState s = this.life.state(v.getUniqueId());
            s.talkUntilMs = now + duration;
            s.nextTalkMs = now + duration + this.cfg.talkCooldownMs;
            s.nextBubbleMs = Math.max(s.nextBubbleMs, now + duration + 5000L);
            v.getPathfinder().stopPathfinding();
        }
        this.life.state(a.getUniqueId()).remember(lines.getFirst());
        this.started++;
        final UUID countryB = this.ludnosc.countryOf(b).orElse(country);
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            final boolean first = i % 2 == 0;
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                final Villager speaker = first ? a : b;
                final Villager listener = first ? b : a;
                if (!speaker.isValid() || !listener.isValid()
                    || speaker.getLocation().distanceSquared(listener.getLocation()) > 64) {
                    return;
                }
                speaker.lookAt(listener);
                listener.lookAt(speaker);
                this.life.sayLine(speaker, line, first ? country : countryB, gap + 15);
            }, (long) i * gap);
        }
    }
}
