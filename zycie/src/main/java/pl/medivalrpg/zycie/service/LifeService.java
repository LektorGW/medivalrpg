// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.zycie.ZyciePlugin;
import pl.medivalrpg.zycie.config.ZycieConfig;
import pl.medivalrpg.zycie.logic.DailyPlan;
import pl.medivalrpg.zycie.logic.DayPhase;
import pl.medivalrpg.zycie.logic.DaySchedule;
import pl.medivalrpg.zycie.logic.Destination;
import pl.medivalrpg.zycie.logic.Hashing;
import pl.medivalrpg.zycie.logic.JobCatalog;
import pl.medivalrpg.zycie.logic.Places;
import pl.medivalrpg.zycie.logic.SteeringRules;
import pl.medivalrpg.zycie.logic.TextContext;
import pl.medivalrpg.zycie.logic.TextPool;
import pl.medivalrpg.zycie.logic.Texts;

/**
 * The daily life of loaded citizens: a rotating queue of citizen entity ids (from {@link LudnoscApi#citizens}),
 * a fixed number of them visited per tick, at most {@code ruchy-na-tick} pathfinder moves per tick.
 *
 * <p><b>Vanilla stays on.</b> The villager brain is never disabled ({@code setAware} is not used — an unaware villager
 * would not walk at all). The brain's {@code MoveToTargetSink} replaces our path whenever vanilla sets its own walk
 * target (stroll, job site, bell, bed), so we steer in short windows: a move at most every {@code ruch.co-tickow}
 * ticks, only in phases where vanilla has nothing important to do (no steering at night, in the morning and during
 * the vanilla bell meeting), and we stop as soon as the citizen is within {@code promien-dotarcia} of the target —
 * from then on vanilla acts freely inside {@code smycz}. Citizens with their own vanilla workstation and no
 * workplace building are left to vanilla at work time. Sleeping or trading villagers are never touched.</p>
 */
public final class LifeService {

    private static final NamespacedKey LUDNOSC_NAME = NamespacedKey.fromString("ludnosc:name");
    /** Permanent home (bed) and workplace written by Ludnosc's citizens' life: int[]{x, y, z} in the villager's world. */
    private static final NamespacedKey LUDNOSC_HOME = NamespacedKey.fromString("ludnosc:home");
    private static final NamespacedKey LUDNOSC_WORK = NamespacedKey.fromString("ludnosc:workplace");

    private record Entry(UUID entity, UUID country) {
    }

    private final ZyciePlugin plugin;
    private final KrajeApi kraje;
    private final LudnoscApi ludnosc;
    private final CityCache cities;
    private final MoodService mood;
    private final BubbleService bubbles;
    private final Integrations integrations;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final Map<UUID, CitizenState> states = new HashMap<>();
    private final Map<World, List<Location>> playersThisTick = new HashMap<>();
    private ZycieConfig cfg;
    private long tick;
    private long lastRefillMs;
    private int movesThisTick;
    private int movesMinute;
    private int failedMinute;
    private int lastMoves;
    private int lastFailed;
    private ConversationService conversations;
    private SiegeService sieges;

    public LifeService(final ZyciePlugin plugin, final KrajeApi kraje, final LudnoscApi ludnosc, final CityCache cities,
                       final MoodService mood, final BubbleService bubbles, final Integrations integrations) {
        this.plugin = plugin;
        this.kraje = kraje;
        this.ludnosc = ludnosc;
        this.cities = cities;
        this.mood = mood;
        this.bubbles = bubbles;
        this.integrations = integrations;
    }

    public void wire(final ConversationService conv, final SiegeService siege) {
        this.conversations = conv;
        this.sieges = siege;
    }

    public void configure(final ZycieConfig config) {
        this.cfg = config;
        this.lastRefillMs = 0L;
        for (final CitizenState s : this.states.values()) {
            s.planKey = Long.MIN_VALUE;
        }
    }

    // ------------------------------------------------------------------ stats

    public int queueSize() {
        return this.queue.size();
    }

    public int tracked() {
        return this.states.size();
    }

    public int movesLastMinute() {
        return this.lastMoves;
    }

    public int failedLastMinute() {
        return this.lastFailed;
    }

    // ------------------------------------------------------------------ state for other services

    CitizenState state(final UUID villager) {
        return this.states.computeIfAbsent(villager, k -> new CitizenState());
    }

    /** The villager runs from an enemy: no plan steering until then. */
    public void markFleeing(final UUID villager, final long untilMs) {
        final CitizenState s = this.state(villager);
        s.fleeUntilMs = Math.max(s.fleeUntilMs, untilMs);
        s.planKey = Long.MIN_VALUE;
    }

    public boolean fleeing(final UUID villager, final long nowMs) {
        final CitizenState s = this.states.get(villager);
        return s != null && s.fleeUntilMs > nowMs;
    }

    public boolean emergency(final UUID villager, final long nowMs) {
        return this.plugin.fires().hasPriority(villager, nowMs);
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        this.tick++;
        this.movesThisTick = 0;
        this.playersThisTick.clear();
        if (this.tick % 1200 == 0) {
            this.lastMoves = this.movesMinute;
            this.lastFailed = this.failedMinute;
            this.movesMinute = 0;
            this.failedMinute = 0;
            this.prune();
        }
        final long nowMs = System.currentTimeMillis();
        if (this.queue.isEmpty() || nowMs - this.lastRefillMs > this.cfg.refreshSeconds * 1000L) {
            this.refill();
            this.lastRefillMs = nowMs;
        }
        int processed = 0;
        int lookups = 0;
        final int budget = this.cfg.citizensPerTick;
        while (processed < budget && lookups < budget * 4 && !this.queue.isEmpty()) {
            final Entry e = this.queue.pollFirst();
            lookups++;
            final Entity entity = Bukkit.getEntity(e.entity());
            if (!(entity instanceof Villager v) || !v.isValid()) {
                continue; // unloaded / dead: dropped until the next refill
            }
            this.queue.addLast(e);
            processed++;
            try {
                this.process(v, e.country(), nowMs);
            } catch (final RuntimeException ex) {
                this.plugin.getLogger().log(java.util.logging.Level.FINE, "Błąd zachowania wieśniaka", ex);
            }
        }
    }

    private void refill() {
        this.queue.clear();
        final Set<UUID> seen = new HashSet<>();
        for (final KrajInfo k : this.kraje.getCountries()) {
            final java.util.Collection<UUID> ids;
            try {
                ids = this.ludnosc.citizens(k.id());
            } catch (final RuntimeException e) {
                continue;
            }
            for (final UUID id : ids) {
                if (seen.add(id)) {
                    this.queue.addLast(new Entry(id, k.id()));
                }
            }
        }
    }

    private void prune() {
        final long cutoff = System.currentTimeMillis() - 120_000L;
        final Iterator<Map.Entry<UUID, CitizenState>> it = this.states.entrySet().iterator();
        while (it.hasNext()) {
            final CitizenState s = it.next().getValue();
            if (s.lastSeenMs < cutoff && s.fleeUntilMs < cutoff && s.talkUntilMs < cutoff) {
                it.remove();
            }
        }
    }

    public void clear() {
        this.queue.clear();
        this.states.clear();
    }

    /** Online players' locations in a world (cached per tick). */
    List<Location> players(final World w) {
        return this.playersThisTick.computeIfAbsent(w, world -> {
            final List<Location> out = new ArrayList<>();
            for (final Player p : world.getPlayers()) {
                if (p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                    out.add(p.getLocation());
                }
            }
            return out;
        });
    }

    boolean playerWithin(final Location at, final double radius) {
        if (radius <= 0) {
            return true;
        }
        final double r2 = radius * radius;
        for (final Location p : this.players(at.getWorld())) {
            if (p.distanceSquared(at) <= r2) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ one citizen

    private void process(final Villager v, final UUID country, final long nowMs) {
        final World world = v.getWorld();
        if (!this.cfg.worldEnabled(world.getName())) {
            return;
        }
        final Location loc = v.getLocation();
        final CitizenState st = this.state(v.getUniqueId());
        st.lastSeenMs = nowMs;
        if (this.plugin.fires().hasPriority(v.getUniqueId(), nowMs)) {
            st.planKey = Long.MIN_VALUE;
            return;
        }
        if (this.cfg.playerRange > 0 && !this.playerWithin(loc, this.cfg.playerRange)) {
            return;
        }
        final MoodSnapshot snap = this.mood.snapshotOrNeutral(country);
        final KrajInfo k = this.kraje.getCountry(country).orElse(null);
        if (k == null) {
            return;
        }
        final CityCache.City city = this.cities.get(country);
        final JobCatalog.Job job = this.job(v, city, snap.era());
        if (this.cfg.jobNames) {
            this.applyTitle(v, k, job, snap.era());
        }

        final long fullTime = world.getFullTime();
        final DayPhase phase = this.cfg.schedule.phase(fullTime);
        final long day = DaySchedule.dayNumber(fullTime);
        final boolean militia = this.sieges != null && this.sieges.isMilitia(v.getUniqueId());
        final boolean shelter = st.fleeUntilMs > nowMs || (this.sieges != null && this.sieges.isSheltering(v.getUniqueId(), nowMs));
        final double share = Hashing.unit(v.getUniqueId(), "plan", day);
        final Destination dest = this.cfg.plan.destination(new DailyPlan.Situation(phase, v.isAdult(), shelter, militia,
            snap.festival(), snap.protest(), share));

        final long planKey = day * 64 + phase.ordinal() * 16L + dest.ordinal();
        if (planKey != st.planKey) {
            st.planKey = planKey;
            st.destination = dest;
            st.target = null;
            st.targetResolved = false;
            st.arrived = false;
            st.failures = 0;
        }

        final boolean busy = v.isSleeping() || v.isTrading() || v.isInsideVehicle() || v.isLeashed() || st.talkUntilMs > nowMs;
        if (!busy && dest != Destination.NONE && dest != Destination.SHELTER) {
            if (!st.targetResolved) {
                st.target = this.resolveTarget(v, k, dest, job);
                st.targetResolved = true;
            }
            if (st.target != null) {
                this.steer(v, st);
            }
        }

        // festival dancing
        if (dest == Destination.FESTIVAL && snap.festival() && !busy && st.target != null
            && loc.distanceSquared(st.target) <= this.cfg.danceRadius * this.cfg.danceRadius) {
            this.dance(v);
        }

        // ambient bubble
        if (!v.isSleeping() && nowMs >= st.nextBubbleMs && st.talkUntilMs <= nowMs && this.playerWithin(loc, this.cfg.bubbleRange)) {
            double chance = this.cfg.bubbleChance;
            TextPool pool = this.cfg.ambient;
            if (dest == Destination.FESTIVAL && snap.festival()) {
                pool = this.cfg.festivalTexts;
                chance *= 2.5;
            } else if (dest == Destination.PROTEST && st.arrived) {
                pool = this.cfg.protest;
                chance *= 3;
            } else if (militia) {
                pool = this.cfg.levyTexts;
            } else if (shelter && snap.siege()) {
                pool = this.cfg.siegeTexts;
                chance *= 2;
            }
            if (ThreadLocalRandom.current().nextDouble() < Math.min(1.0, chance)) {
                final TextContext ctx = this.context(v, job, snap, phase, null);
                if (this.say(v, pool, ctx, k, snap, job, null)) {
                    st.nextBubbleMs = nowMs + this.cfg.bubbleCooldownMs
                        + ThreadLocalRandom.current().nextLong(Math.max(1, this.cfg.bubbleCooldownMs / 2));
                }
            }
        }

        if (this.conversations != null && !busy && this.playerWithin(loc, this.cfg.bubbleRange + 4)) {
            this.conversations.offer(v.getUniqueId(), country);
        }
    }

    private void steer(final Villager v, final CitizenState st) {
        final Location from = v.getLocation();
        final Location target = st.target;
        if (target.getWorld() != from.getWorld()) {
            return;
        }
        final double dist = from.distance(target);
        final SteeringRules rules = this.cfg.steering;
        switch (rules.decide(dist, st.arrived, this.tick - st.lastMoveTick)) {
            case ARRIVED -> {
                st.arrived = true;
                st.failures = 0;
            }
            case IDLE -> {
                // vanilla acts
            }
            case MOVE -> {
                if (this.movesThisTick >= this.cfg.movesPerTick || this.tick < st.blockedUntilTick) {
                    return;
                }
                Location point = target;
                final double f = rules.stepFraction(dist);
                if (f < 1.0) {
                    final Vector d = target.toVector().subtract(from.toVector()).multiply(f);
                    final Location mid = from.clone().add(d);
                    final World w = from.getWorld();
                    if (!w.isChunkLoaded(mid.getBlockX() >> 4, mid.getBlockZ() >> 4)) {
                        st.blockedUntilTick = this.tick + rules.repathTicks();
                        return;
                    }
                    mid.setY(w.getHighestBlockYAt(mid.getBlockX(), mid.getBlockZ()) + 1.0);
                    point = mid;
                }
                this.movesThisTick++;
                this.movesMinute++;
                st.lastMoveTick = this.tick;
                st.arrived = false;
                final boolean ok = v.getPathfinder().moveTo(point, this.cfg.speed);
                if (ok) {
                    st.failures = 0;
                } else {
                    this.failedMinute++;
                    if (++st.failures >= rules.failLimit()) {
                        st.failures = 0;
                        st.blockedUntilTick = this.tick + rules.failCooldown();
                    }
                }
            }
        }
    }

    private void dance(final Villager v) {
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() < 0.5 && v.isOnGround()) {
            v.setVelocity(new Vector(0, 0.36 + r.nextDouble() * 0.08, 0));
        }
        if (r.nextDouble() < 0.6) {
            v.setRotation(v.getLocation().getYaw() + 60f + r.nextFloat() * 120f, 0f);
        }
    }

    /** First existing place for the destination, null = leave to vanilla. */
    private Location resolveTarget(final Villager v, final KrajInfo k, final Destination dest, final JobCatalog.Job job) {
        final Location from = v.getLocation();
        final CityCache.City city = this.cities.get(k.id());
        final double max2 = this.cfg.maxTravel * this.cfg.maxTravel;
        for (final Places.Place p : this.cfg.places.candidates(dest, job.buildings())) {
            Location found = null;
            switch (p.kind()) {
                case BUILDINGS -> {
                    final Optional<CityCache.Spot> s = city.nearest(p.tokens(), from);
                    if (s.isPresent()) {
                        found = new Location(from.getWorld(), s.get().x() + 0.5, s.get().y(), s.get().z() + 0.5);
                    }
                }
                case JOB_SITE -> {
                    if (v.getMemory(MemoryKey.JOB_SITE) != null) {
                        return null; // its own workstation: vanilla walks there at work time
                    }
                }
                case BELL -> found = v.getMemory(MemoryKey.MEETING_POINT);
                case SQUARE_SPOT -> found = this.mood.squareSpot(k.id());
                case CENTER -> found = CityCache.capitalCenter(k);
                case LUDNOSC_WORK -> found = pdcSpot(v, LUDNOSC_WORK);
                case LUDNOSC_HOME -> {
                    final Location home = pdcSpot(v, LUDNOSC_HOME);
                    if (home != null && home.distanceSquared(from) <= max2) {
                        return home; // the bed itself: no spreading through the walls
                    }
                }
            }
            if (found != null && found.getWorld() == from.getWorld() && found.distanceSquared(from) <= max2) {
                final int spread = this.cfg.spread;
                return found.clone().add(Hashing.offset(v.getUniqueId(), "dx", spread), 0, Hashing.offset(v.getUniqueId(), "dz", spread));
            }
        }
        return null;
    }

    /** Position stored by Ludnosc in the villager's PDC ({@code int[]{x, y, z}}, same world), null when absent. */
    static Location pdcSpot(final Villager v, final NamespacedKey key) {
        if (key == null) {
            return null;
        }
        final int[] p = v.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        return p == null || p.length < 3 ? null : new Location(v.getWorld(), p[0] + 0.5, p[1], p[2] + 0.5);
    }

    // ------------------------------------------------------------------ jobs & names

    public JobCatalog.Job job(final Villager v, final CityCache.City city, final int era) {
        final NamespacedKey key = Registry.VILLAGER_PROFESSION.getKey(v.getProfession());
        final String prof = key == null ? "none" : key.getKey();
        return this.cfg.jobs.resolve(prof, v.getUniqueId(), era, city::has);
    }

    /** Ludnosc's citizen name (PDC {@code ludnosc:name}), without a title we may have added. */
    static String baseName(final Villager v) {
        final String name = LUDNOSC_NAME == null ? null : v.getPersistentDataContainer().get(LUDNOSC_NAME, PersistentDataType.STRING);
        if (name == null || name.isBlank()) {
            return null;
        }
        final int comma = name.indexOf(", ");
        return comma > 0 ? name.substring(0, comma) : name;
    }

    private void applyTitle(final Villager v, final KrajInfo k, final JobCatalog.Job job, final int era) {
        final String base = baseName(v);
        if (base == null) {
            return;
        }
        final String title = job.title(era);
        final String wanted = base + ", " + title;
        final Component current = v.customName();
        if (current != null && PlainTextComponentSerializer.plainText().serialize(current).equals(wanted)) {
            return;
        }
        final TextColor color = k.color() == null ? NamedTextColor.WHITE : k.color();
        v.customName(Component.text(base, color).append(Component.text(", " + title, NamedTextColor.GRAY)));
    }

    // ------------------------------------------------------------------ texts

    public TextContext context(final Villager v, final JobCatalog.Job job, final MoodSnapshot snap, final DayPhase phase,
                               final String standing) {
        final NamespacedKey key = Registry.VILLAGER_PROFESSION.getKey(v.getProfession());
        final Set<String> jobs = Set.of(key == null ? "none" : Texts.normalize(key.getKey()), job.id());
        return new TextContext(phase, snap.era(), snap.mood(), snap.war(), snap.siege(), snap.hungry(), snap.highTaxes(),
            snap.homeless(), snap.festival(), snap.protest(), snap.events(), jobs, standing);
    }

    /** Context for a villager with everything looked up (used by reactions and dialogues). */
    public TextContext context(final Villager v, final UUID country, final String standing) {
        final MoodSnapshot snap = this.mood.snapshotOrNeutral(country);
        final JobCatalog.Job job = this.job(v, this.cities.get(country), snap.era());
        return this.context(v, job, snap, this.cfg.schedule.phase(v.getWorld().getFullTime()), standing);
    }

    /** Picks a line from {@code pool} and shows it above {@code v}. */
    public boolean say(final Villager v, final TextPool pool, final TextContext ctx, final UUID country, final String player) {
        final KrajInfo k = this.kraje.getCountry(country).orElse(null);
        if (k == null) {
            return false;
        }
        final MoodSnapshot snap = this.mood.snapshotOrNeutral(country);
        return this.say(v, pool, ctx, k, snap, this.job(v, this.cities.get(country), snap.era()), player);
    }

    private boolean say(final Villager v, final TextPool pool, final TextContext ctx, final KrajInfo k, final MoodSnapshot snap,
                        final JobCatalog.Job job, final String player) {
        final CitizenState st = this.state(v.getUniqueId());
        final Optional<String> line = pool.pickLine(ctx, ThreadLocalRandom.current(), st.recent);
        if (line.isEmpty()) {
            return false;
        }
        st.remember(line.get());
        return this.bubbles.show(v, this.render(line.get(), v, k, snap, job, player), this.cfg.bubbleTicks);
    }

    /** Shows one given line (dialogues). */
    public boolean sayLine(final Villager v, final String line, final UUID country, final int ticks) {
        final KrajInfo k = this.kraje.getCountry(country).orElse(null);
        if (k == null) {
            return false;
        }
        final MoodSnapshot snap = this.mood.snapshotOrNeutral(country);
        return this.bubbles.show(v, this.render(line, v, k, snap, this.job(v, this.cities.get(country), snap.era()), null), ticks);
    }

    private Component render(final String line, final Villager v, final KrajInfo k, final MoodSnapshot snap,
                             final JobCatalog.Job job, final String player) {
        final String name = baseName(v);
        final TagResolver resolver = TagResolver.resolver(
            Placeholder.unparsed("imie", name == null ? "" : name),
            Placeholder.unparsed("zawod", job.title(snap.era())),
            Placeholder.unparsed("kraj", k.name()),
            Placeholder.unparsed("wrog", snap.enemy().isBlank() ? "wrogiem" : snap.enemy()),
            Placeholder.unparsed("wladca", k.rulerName() == null || k.rulerName().isBlank() ? "król" : k.rulerName()),
            Placeholder.unparsed("gracz", player == null ? "" : player),
            Placeholder.unparsed("budynek", snap.lastBuilding().isBlank() ? "budynek" : snap.lastBuilding()));
        return this.plugin.messages().parse(line, resolver);
    }
}
