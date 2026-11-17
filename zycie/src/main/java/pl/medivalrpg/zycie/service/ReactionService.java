// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.util.Vector;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.zycie.config.ZycieConfig;
import pl.medivalrpg.zycie.logic.Cooldowns;
import pl.medivalrpg.zycie.logic.ReactionRules;
import pl.medivalrpg.zycie.logic.TextContext;

/**
 * Reactions of citizens to players closer than {@code reakcje.promien}: greetings for the ruler, compatriots, the
 * overlord and allies, distrust for strangers, and for an enemy at war: the citizen runs home (vanilla bell reaction
 * via the {@code HEARD_BELL_TIME} memory + a pathfinder move), shouts "Straż! Wróg!", nearby citizens hide too and,
 * when Armia is present, nearby soldiers of the country get the enemy as their target (a nudge — Armia's own AI
 * already attacks enemies at war and may choose differently). Cooldowns per citizen and per citizen–player pair.
 */
public final class ReactionService {

    private final KrajeApi kraje;
    private final LudnoscApi ludnosc;
    private final LifeService life;
    private final Integrations integrations;
    private final Cooldowns<String> pairs = new Cooldowns<>();
    private final Cooldowns<UUID> villagers = new Cooldowns<>();
    private ZycieConfig cfg;
    private int runs;

    public ReactionService(final KrajeApi kraje, final LudnoscApi ludnosc, final LifeService life, final Integrations integrations) {
        this.kraje = kraje;
        this.ludnosc = ludnosc;
        this.life = life;
        this.integrations = integrations;
    }

    public void configure(final ZycieConfig config) {
        this.cfg = config;
    }

    public void tick() {
        final long now = System.currentTimeMillis();
        if (++this.runs % 600 == 0) {
            this.pairs.prune(now);
            this.villagers.prune(now);
        }
        final double r = this.cfg.reactRadius;
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || !this.cfg.worldEnabled(p.getWorld().getName()) || p.isInvisible()) {
                continue;
            }
            final List<Villager> near = new ArrayList<>();
            for (final Entity e : p.getNearbyEntities(r, 3, r)) {
                if (e instanceof Villager v && v.isValid() && !v.isSleeping()) {
                    near.add(v);
                }
            }
            if (near.isEmpty()) {
                continue;
            }
            near.sort(Comparator.comparingDouble(v -> v.getLocation().distanceSquared(p.getLocation())));
            int handled = 0;
            for (final Villager v : near) {
                if (handled >= 2) {
                    break;
                }
                if (this.react(p, v, now)) {
                    handled++;
                }
            }
        }
    }

    private boolean react(final Player p, final Villager v, final long now) {
        if (this.life.emergency(v.getUniqueId(), now)) return false;
        if (!this.villagers.ready(v.getUniqueId(), now) || !this.pairs.ready(v.getUniqueId() + "|" + p.getUniqueId(), now)) {
            return false;
        }
        final Optional<UUID> country = this.ludnosc.countryOf(v);
        if (country.isEmpty()) {
            return false;
        }
        final UUID c = country.get();
        final Optional<KrajInfo> playerCountry = this.kraje.getCountryOf(p.getUniqueId());
        final UUID pc = playerCountry.map(KrajInfo::id).orElse(null);
        final Rank rank = playerCountry.map(k -> k.rankOf(p.getUniqueId())).orElse(null);
        final Relation rel = pc == null || pc.equals(c) ? Relation.NEUTRAL : this.kraje.getRelation(c, pc);
        UUID overlord = null;
        try {
            overlord = this.kraje.getOverlord(c).orElse(null);
        } catch (final RuntimeException ignored) {
            // older Kraje
        }
        final ReactionRules.Standing standing = ReactionRules.classify(c, pc, rank, rel, overlord);
        this.villagers.set(v.getUniqueId(), now + this.cfg.reactVillagerCooldownMs);
        this.pairs.set(v.getUniqueId() + "|" + p.getUniqueId(), now + this.cfg.reactPairCooldownMs);
        final TextContext ctx = this.life.context(v, c, standing.key());
        switch (standing.action()) {
            case GREET, SUSPICIOUS -> {
                v.lookAt(p);
                this.life.say(v, this.cfg.reactions, ctx, c, p.getName());
                if (standing.action() == ReactionRules.Action.SUSPICIOUS) {
                    v.shakeHead();
                }
            }
            case FLEE -> this.flee(p, v, c, ctx, now);
        }
        return true;
    }

    private void flee(final Player enemy, final Villager v, final UUID country, final TextContext ctx, final long now) {
        this.life.say(v, this.cfg.alarm, ctx, country, enemy.getName());
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.2f);
        this.runHome(v, enemy, now);
        // neighbours hide too
        int alarmed = 0;
        final double ar = this.cfg.alarmRadius;
        for (final Entity e : v.getNearbyEntities(ar, 4, ar)) {
            if (alarmed >= this.cfg.alarmMax) {
                break;
            }
            if (e instanceof Villager other && other.isValid() && !other.isSleeping()
                && this.ludnosc.countryOf(other).map(country::equals).orElse(false)) {
                this.runHome(other, enemy, now);
                this.villagers.set(other.getUniqueId(), now + this.cfg.reactVillagerCooldownMs);
                alarmed++;
            }
        }
        if (this.cfg.callGuards && this.integrations.armiaPresent()) {
            this.callGuards(v, enemy, country);
        }
    }

    private void runHome(final Villager v, final Player enemy, final long now) {
        if (this.life.emergency(v.getUniqueId(), now)) return;
        this.life.markFleeing(v.getUniqueId(), now + this.cfg.fleeMs);
        // vanilla: a villager who heard the bell hides at home (HIDE activity) — the brain does the rest
        v.setMemory(MemoryKey.HEARD_BELL_TIME, v.getWorld().getGameTime());
        final Location home = v.getMemory(MemoryKey.HOME);
        Location target = home != null && home.getWorld() == v.getWorld() && home.distanceSquared(v.getLocation()) < 48 * 48 ? home : null;
        if (target == null) {
            final Vector away = v.getLocation().toVector().subtract(enemy.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.01) {
                away.setX(1);
            }
            target = v.getLocation().add(away.normalize().multiply(12));
        }
        v.getPathfinder().moveTo(target, this.cfg.fleeSpeed);
    }

    private void callGuards(final Villager v, final Player enemy, final UUID country) {
        int called = 0;
        final double gr = this.cfg.guardRadius;
        for (final Entity e : v.getNearbyEntities(gr, 8, gr)) {
            if (called >= this.cfg.guardMax) {
                return;
            }
            if (e instanceof Mob mob && !(e instanceof Villager) && mob.getTarget() == null
                && this.integrations.soldierCountry(e).map(country::equals).orElse(false)) {
                mob.setTarget(enemy);
                called++;
            }
        }
    }
}
