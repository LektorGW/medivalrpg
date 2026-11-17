// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ai;

import com.google.gson.GsonBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.armia.api.NpcLogCategory;
import pl.medivalrpg.armia.api.NpcPosture;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.config.UnitType;
import pl.medivalrpg.armia.logic.ChunkPlanner;
import pl.medivalrpg.armia.logic.SiegeRules;
import pl.medivalrpg.armia.logic.conquest.BattleMath;
import pl.medivalrpg.armia.logic.conquest.ConquestRules;
import pl.medivalrpg.armia.logic.conquest.ConquestSettings;
import pl.medivalrpg.armia.model.ConquestState;
import pl.medivalrpg.armia.model.NpcState;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.service.Population;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.armia.soldier.ArmyService;
import pl.medivalrpg.armia.soldier.Soldier;
import pl.medivalrpg.armia.storage.ConquestStore;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.model.Relation;

/**
 * Background wars of NPC countries ({@code ai.conquest}): far from every player an offensive is fought as an abstract
 * siege in rounds ({@link BattleMath}) with real soldiers committed and really lost (registry, global cap). Kraje applies
 * a won siege with its own rules ({@link KrajeBridge}); near players the offensive pauses and the physical system takes
 * over. Balance ({@link ConquestRules}): one offensive per country, daily limits, war exhaustion, overextension upkeep,
 * revolts, vassal secession and a coalition against the strongest NPC country. All money only moves between existing
 * accounts. Work runs on the main thread in small steps within {@code budget-ms}; no chunk is ever loaded.
 */
public final class ConquestService {

    private static final long MINUTE = 60_000L;
    private static final long PAUSE_LIMIT = 30 * MINUTE;

    private final Plugin plugin;
    private final Services services;
    private final ArmyService army;
    private final NpcAiService ai;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;
    private final ConquestStore store;
    private final KrajeBridge bridge;
    private ConquestState state;
    private BukkitTask task;
    private long nextCycleAt;
    private long lastCycleAt;
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private boolean dirty;
    private long lastAnnounceAt = System.currentTimeMillis();
    /** "attacker>defender" → [chunks, bridgeheads] since the last summary. */
    private final Map<String, int[]> conquests = new LinkedHashMap<>();
    /** "holder>former" → revolts since the last summary. */
    private final Map<String, int[]> revolts = new LinkedHashMap<>();
    private final List<Component> otherNews = new ArrayList<>();
    /** Per-cycle caches. */
    private final Map<UUID, List<Population.Fort>> forts = new HashMap<>();
    private final Map<UUID, Set<UUID>> reach = new HashMap<>();
    private UUID hegemon;
    private long hegemonSince;
    /** Seedable randomness for tests; normally ThreadLocalRandom. */
    private java.util.function.DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();
    private boolean disabledReleased;

    public ConquestService(final Plugin plugin, final Services services, final ArmyService army, final NpcAiService ai,
                           final Supplier<ArmiaConfig> config, final Supplier<Messages> messages) {
        this.plugin = plugin;
        this.services = services;
        this.army = army;
        this.ai = ai;
        this.config = config;
        this.messages = messages;
        this.store = new ConquestStore(plugin.getDataFolder().toPath(), plugin.getLogger());
        this.bridge = new KrajeBridge(plugin.getLogger());
    }

    private ConquestSettings s() {
        return this.config.get().conquest;
    }

    private KrajeApi kraje() {
        return this.services.kraje();
    }

    public ConquestState state() {
        return this.state;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        this.state = this.store.load();
        this.reconcile();
        this.nextCycleAt = System.currentTimeMillis() + 60_000L;
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::step, 200L, 20L);
        this.plugin.getLogger().info("Wojny w tle: " + this.state.offensives.size() + " trwających ofensyw"
            + (this.s().enabled() ? "" : " (wyłączone: ai.conquest.enabled=false)"));
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        if (this.state != null) {
            this.store.saveNow(this.state);
        }
    }

    /** After a restart: soldiers and offensives must agree (both files are saved separately). */
    private void reconcile() {
        final Map<String, ConquestState.Offensive> byId = new HashMap<>();
        for (final ConquestState.Offensive o : this.state.offensives.values()) {
            byId.put(o.id, o);
        }
        int returned = 0;
        for (final Soldier s : List.copyOf(this.army.registry().all())) {
            final String c = s.record().campaign;
            if (c == null || c.startsWith(NavalAiService.CAMPAIGN_PREFIX)) {
                continue; // long-range naval expeditions reconcile their own soldiers (NavalAiService)
            }
            final ConquestState.Offensive o = byId.get(c);
            if (o == null || !o.soldiers.contains(s.id()) || !o.attacker.equals(s.country())) {
                this.army.controller().returnFromMarch(s);
                returned++;
            }
        }
        for (final ConquestState.Offensive o : this.state.offensives.values()) {
            o.soldiers.removeIf(id -> {
                final Soldier s = this.army.registry().get(id);
                return s == null || !o.id.equals(s.record().campaign);
            });
        }
        if (returned > 0) {
            this.plugin.getLogger().info("Wojny w tle: " + returned + " żołnierzy bez ofensywy wraca do domu");
        }
    }

    // ------------------------------------------------------------------ public queries (AI, commands, panel)

    /** An offensive that is being fought now (not paused near players). */
    public boolean hasActiveOffensive(final UUID country) {
        final ConquestState.Offensive o = this.state == null ? null : this.state.offensives.get(country);
        return o != null && !o.paused;
    }

    public double exhaustion(final UUID country) {
        final ConquestState.CountryWar w = this.state == null ? null : this.state.countries.get(country);
        return w == null ? 0 : w.exhaustion;
    }

    /** NpcAiService#answerProposal: an exhausted country accepts any peace. */
    public boolean acceptsPeace(final UUID country) {
        return this.s().enabled() && this.exhaustion(country) >= this.s().exhaustion().acceptPeace();
    }

    /** NpcAiService#answerCapitulation: a winner tired of the war accepts a vassal instead of fighting on. */
    public boolean acceptsCapitulation(final UUID winner) {
        return this.s().enabled() && this.exhaustion(winner) >= this.s().exhaustion().proposePeace();
    }

    /** In this war {@code self} lost more land in the background than it took from {@code enemy}. */
    private boolean losing(final UUID self, final UUID enemy) {
        final ConquestState.WarStats lost = this.state.wars.get(enemy + ">" + self);
        final ConquestState.WarStats won = this.state.wars.get(self + ">" + enemy);
        return (lost == null ? 0 : lost.chunks) > (won == null ? 0 : won.chunks);
    }

    /** An exhausted, losing NPC offers to capitulate (Kraje's offer; the winner's AI answers). True when the offer went out. */
    private boolean capitulate(final KrajInfo c, final KrajInfo enemy, final ConquestState.CountryWar w) {
        try {
            if (this.kraje().getOverlord(c.id()).isPresent()) {
                return false;
            }
            final KrajeResult r = this.kraje().offerCapitulation(c.id(), enemy.id(), 0);
            this.ai.log(c.id(), NpcLogCategory.DIPLOMACY, "Zmęczenie wojną " + Math.round(w.exhaustion) + "/100 i utracone ziemie — propozycja KAPITULACJI dla "
                + enemy.name() + ": " + (r.ok() ? "wysłana" : r.status() + " " + r.message()));
            return r.ok();
        } catch (final LinkageError e) {
            return false;
        }
    }

    /** NPC countries within {@code war-range-chunks} (possible war targets beyond direct neighbours). Cached per cycle. */
    public Set<UUID> reachable(final KrajInfo c) {
        final ConquestSettings s = this.s();
        if (!s.enabled() || s.warRangeChunks() <= 0 || c.capitalWorld() == null) {
            return Set.of();
        }
        return this.reach.computeIfAbsent(c.id(), id -> {
            final String world = c.capitalWorld();
            final Set<Long> own = this.kraje().getClaims(id, world);
            final int[] box = box(own);
            final Set<UUID> out = new HashSet<>();
            for (final KrajInfo o : this.kraje().getCountries()) {
                if (o.id().equals(id) || !o.npc()) {
                    continue;
                }
                final Set<Long> theirs = this.kraje().getClaims(o.id(), world);
                final int[] tb = box(theirs);
                if (box == null || tb == null) {
                    continue;
                }
                final int gx = Math.max(0, Math.max(tb[0] - box[2], box[0] - tb[2]));
                final int gz = Math.max(0, Math.max(tb[1] - box[3], box[1] - tb[3]));
                if (Math.max(gx, gz) > s.warRangeChunks()) {
                    continue;
                }
                if (ConquestRules.gap(own, theirs) <= s.warRangeChunks()) {
                    out.add(o.id());
                }
            }
            return out;
        });
    }

    private static int[] box(final Set<Long> land) {
        if (land.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (final long k : land) {
            minX = Math.min(minX, ChunkPlanner.cx(k));
            maxX = Math.max(maxX, ChunkPlanner.cx(k));
            minZ = Math.min(minZ, ChunkPlanner.cz(k));
            maxZ = Math.max(maxZ, ChunkPlanner.cz(k));
        }
        return new int[] {minX, minZ, maxX, maxZ};
    }

    // ------------------------------------------------------------------ scheduling

    /** Testing / admin: run the next cycle at once. */
    public void runCycleNow() {
        this.nextCycleAt = 0;
        for (final ConquestState.Offensive o : this.state.offensives.values()) {
            o.lastRoundAt = 0;
        }
    }

    public void setRandom(final java.util.function.DoubleSupplier random) {
        this.random = random;
    }

    private void step() {
        final ConquestSettings s = this.s();
        final long now = System.currentTimeMillis();
        if (!s.enabled()) {
            if (!this.disabledReleased && !this.state.offensives.isEmpty()) {
                for (final ConquestState.Offensive o : List.copyOf(this.state.offensives.values())) {
                    this.end(o, "wojny w tle wyłączone", false);
                }
                this.dirty = true;
            }
            this.disabledReleased = true;
            this.flush(now, true);
            return;
        }
        this.disabledReleased = false;
        if (this.queue.isEmpty() && now >= this.nextCycleAt && !this.ai.globalPaused() && this.config.get().aiEnabled) {
            this.nextCycleAt = now + s.intervalMinutes() * MINUTE;
            this.buildCycle(now);
        }
        final long budget = (long) (s.budgetMs() * 1_000_000L);
        final long t0 = System.nanoTime();
        while (!this.queue.isEmpty() && System.nanoTime() - t0 < budget) {
            final Runnable r = this.queue.poll();
            try {
                r.run();
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Wojny w tle: błąd kroku", e);
            }
        }
        this.flush(now, false);
    }

    private void buildCycle(final long now) {
        final double minutes = this.lastCycleAt == 0 ? this.s().intervalMinutes() : Math.max(0, (now - this.lastCycleAt) / 60_000.0);
        this.lastCycleAt = now;
        this.forts.clear();
        this.reach.clear();
        final Map<UUID, Integer> npcChunks = new HashMap<>();
        final List<UUID> npcs = new ArrayList<>();
        for (final KrajInfo c : this.kraje().getCountries()) {
            if (c.npc()) {
                npcChunks.put(c.id(), c.chunkCount());
                npcs.add(c.id());
            }
        }
        final Optional<UUID> heg = ConquestRules.hegemon(npcChunks, this.s());
        if (heg.isPresent() && !heg.get().equals(this.hegemon)) {
            this.hegemonSince = now;
            this.history(now, "Hegemonia: " + this.ai.name(heg.get()) + " (" + npcChunks.get(heg.get()) + " chunków) — inne kraje NPC tworzą koalicję");
        }
        this.hegemon = heg.orElse(null);
        // Drop state of countries that no longer exist.
        this.state.countries.keySet().removeIf(id -> this.kraje().getCountry(id).isEmpty());
        for (final ConquestState.Offensive o : List.copyOf(this.state.offensives.values())) {
            if (this.kraje().getCountry(o.attacker).isEmpty()) {
                this.end(o, "kraj atakujący zniknął", false);
            }
        }
        for (final UUID id : npcs) {
            this.queue.add(() -> this.country(id, minutes));
        }
        this.queue.add(() -> this.revolts(minutes));
        this.queue.add(() -> {
            this.dirty = true;
            this.store.saveAsync(this.state);
            this.dirty = false;
        });
    }

    // ------------------------------------------------------------------ per country

    private void country(final UUID id, final double minutes) {
        final Optional<KrajInfo> oc = this.kraje().getCountry(id);
        if (oc.isEmpty() || !oc.get().npc()) {
            return;
        }
        final KrajInfo c = oc.get();
        final ConquestSettings s = this.s();
        final long now = System.currentTimeMillis();
        final ConquestState.CountryWar w = this.state.country(id);
        final boolean atWar = !c.enemies().isEmpty();
        final double hours = w.exhaustionAt == 0 ? 0 : (now - w.exhaustionAt) / 3_600_000.0;
        w.exhaustion = ConquestRules.exhaustion(w.exhaustion, hours, atWar, s);
        w.exhaustionAt = now;
        if (!this.ai.isActive(id)) {
            return;
        }
        this.overextension(c, w, now);
        this.coalition(c, w, now, minutes);
        this.secession(c, minutes);
        if (atWar && w.exhaustion >= s.exhaustion().proposePeace() && now - w.lastPeaceProposalAt >= 60 * MINUTE) {
            w.lastPeaceProposalAt = now;
            for (final UUID e : List.copyOf(c.enemies())) {
                final KrajInfo enemy = this.kraje().getCountry(e).orElse(null);
                if (enemy == null || !enemy.npc()) {
                    continue;
                }
                if (this.losing(id, e) && this.capitulate(c, enemy, w)) {
                    continue; // losing the war: rather a vassal with tribute than more lost land
                }
                final var reply = this.ai.proposeFromNpc(c, e, Relation.NEUTRAL, "Zmęczenie wojną");
                this.ai.log(id, NpcLogCategory.DIPLOMACY, "Zmęczenie wojną " + Math.round(w.exhaustion) + "/100 — propozycja pokoju dla "
                    + enemy.name() + ": " + reply.message());
            }
        }
        final ConquestState.Offensive o = this.state.offensives.get(id);
        if (o != null) {
            this.round(o, now);
        } else if (atWar) {
            this.maybeStart(c, w, now);
        }
    }

    // ------------------------------------------------------------------ offensive start

    private boolean playersNear(final String world, final double x, final double z) {
        final World w = Bukkit.getWorld(world);
        if (w == null) {
            return false;
        }
        final double r = this.s().playerRadius();
        for (final Player p : w.getPlayers()) {
            final double dx = p.getLocation().getX() - x;
            final double dz = p.getLocation().getZ() - z;
            if (dx * dx + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }

    private boolean playersNearChunk(final String world, final int cx, final int cz) {
        return this.playersNear(world, (cx << 4) + 8, (cz << 4) + 8);
    }

    /** Free for a campaign: not commanded, not on another task, no live entity near a player. */
    private boolean free(final Soldier s) {
        final var r = s.record();
        if (r.campaign != null || r.siege || r.commander != null || r.suspended() || r.royalPost != null) {
            return false;
        }
        if (s.attached()) {
            final var l = s.brain().getLocation();
            return !this.playersNear(l.getWorld().getName(), l.getX(), l.getZ());
        }
        return true;
    }

    private static boolean engine(final Soldier s) {
        return SiegeRules.breaksFortifications(s.record().type); // catapult, cannon and ram (taran)
    }

    private double unitStrength(final Soldier s) {
        final UnitType u = this.army.controller().unitOf(s);
        return u == null ? 1.0 : u.siegeStrength();
    }

    private double eraMultiplier(final UUID country) {
        return Math.sqrt(Math.max(0.1, this.army.hpMultiplier(country)) * Math.max(0.1, this.army.damageMultiplier(country)));
    }

    private long kraje(final String key, final long def) {
        final Plugin k = Bukkit.getPluginManager().getPlugin("Kraje");
        return k == null ? def : Math.max(0, k.getConfig().getLong(key, def));
    }

    private static String chunkKey(final String world, final int cx, final int cz) {
        return world + ":" + cx + ":" + cz;
    }

    private void maybeStart(final KrajInfo c, final ConquestState.CountryWar w, final long now) {
        final ConquestSettings s = this.s();
        if (!this.bridge.available()) {
            return;
        }
        final NpcState st = this.ai.ensure(c.id());
        final List<Soldier> all = this.army.registry().ofCountry(c.id());
        final List<Soldier> free = new ArrayList<>();
        for (final Soldier x : all) {
            if (this.free(x)) {
                free.add(x);
            }
        }
        final boolean physical = this.kraje().getSieges().stream().anyMatch(x -> x.attacker().equals(c.id()));
        double warMinutes = 0;
        for (final UUID e : c.enemies()) {
            final Long since = st.warStartedAt.get(e);
            warMinutes = Math.max(warMinutes, since == null ? 0 : (now - since) / 60_000.0);
        }
        final int force = ConquestRules.forceSize(all.size(), free.size(), s);
        final long siegeCost = this.kraje("siege.cost", 500);
        final long balance = this.services.skarbiec().balance(this.services.treasury(c.id()));
        final boolean reclaim = !st.lostChunks.isEmpty();
        final ConquestRules.StartView view = new ConquestRules.StartView(this.ai.isActive(c.id()), true, warMinutes,
            w.lastOffensiveAt == 0 ? Double.MAX_VALUE : (now - w.lastOffensiveAt) / 60_000.0,
            ConquestRules.lastDay(w.taken, now), ConquestRules.lastDay(this.state.globalTaken, now), w.exhaustion, force,
            balance, siegeCost + s.supplyCostPerSoldier() * force * 3, st.posture() == NpcPosture.DEFENSIVE, reclaim, physical, false);
        final String why = ConquestRules.cannotStart(view, s);
        if (why != null) {
            return;
        }
        w.retryBlocked.values().removeIf(until -> until < now);
        final List<Soldier> picked = this.pickForce(free, force);
        double bestOdds = -1;
        final String world = c.capitalWorld();
        if (world == null || Bukkit.getWorld(world) == null) {
            return;
        }
        final Set<Long> own = this.kraje().getClaims(c.id(), world);
        final Set<Long> preferred = new HashSet<>();
        for (final NpcState.LostChunk l : st.lostChunks) {
            if (world.equals(l.world)) {
                preferred.add(ChunkPlanner.key(l.cx, l.cz));
            }
        }
        for (final UUID enemyId : this.ai.siegeOrder(c, st)) {
            final KrajInfo enemy = this.kraje().getCountry(enemyId).orElse(null);
            if (enemy == null || (!enemy.npc() && !s.abstractVsPlayers())) {
                continue;
            }
            if (ConquestRules.lastDay(this.state.country(enemyId).lost, now) >= s.maxLossPerDay()) {
                continue;
            }
            final Set<Long> land = new HashSet<>(this.kraje().getClaims(enemyId, world));
            final Long cap = world.equals(enemy.capitalWorld()) ? ChunkPlanner.key(enemy.capitalX(), enemy.capitalZ()) : null;
            final Set<Long> blocked = new HashSet<>();
            for (final String k : w.retryBlocked.keySet()) {
                final String[] p = k.split(":");
                if (p.length == 3 && p[0].equals(world)) {
                    blocked.add(ChunkPlanner.key(Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                }
            }
            if (defensiveOnly(st, view)) {
                land.retainAll(preferred);
            }
            int tried = 0;
            for (final ConquestRules.Target t : ConquestRules.targets(own, land, cap, preferred, blocked, s)) {
                if (++tried > 12) {
                    break;
                }
                final int cx = ChunkPlanner.cx(t.key());
                final int cz = ChunkPlanner.cz(t.key());
                if (this.playersNearChunk(world, cx, cz)) {
                    continue;
                }
                // The AI only attacks where its own estimate (same formula as the battle, without luck) promises success.
                final Forces f = this.evaluate(c, enemy, world, cx, cz, t.expedition(), picked);
                final double odds = f.attack() / Math.max(0.01, f.defence());
                bestOdds = Math.max(bestOdds, odds);
                if (odds < s.minOdds()) {
                    continue;
                }
                final String check = this.bridge.begin(c.id(), world, cx, cz, t.expedition(), false);
                if (!check.startsWith("OK")) {
                    continue;
                }
                this.launch(c, enemy, world, cx, cz, t, picked, f, w, now);
                return;
            }
        }
        if (bestOdds >= 0 && now - w.lastOffensiveAt >= 6 * ConquestRules.HOUR && st.tickCount % 12 == 0) {
            this.ai.log(c.id(), NpcLogCategory.WAR, "Brak ofensywy w tle: za mała przewaga (najlepsza szansa " + NpcAiService.fmt(bestOdds)
                + " < " + NpcAiService.fmt(s.minOdds()) + ")");
        }
    }

    /** Strongest free soldiers first, at most two siege engines (they break walls). */
    private List<Soldier> pickForce(final List<Soldier> free, final int force) {
        final List<Soldier> sorted = new ArrayList<>(free);
        sorted.sort(Comparator.comparingDouble((Soldier x) -> -this.unitStrength(x)).thenComparing(Soldier::id));
        final List<Soldier> out = new ArrayList<>();
        int engines = 0;
        for (final Soldier x : sorted) {
            if (out.size() >= force) {
                break;
            }
            if (engine(x)) {
                if (engines >= 2) {
                    continue;
                }
                engines++;
            }
            out.add(x);
        }
        return out;
    }

    private static boolean defensiveOnly(final NpcState st, final ConquestRules.StartView v) {
        return v.defensivePosture() && !st.crisis;
    }

    private void launch(final KrajInfo c, final KrajInfo enemy, final String world, final int cx, final int cz,
                        final ConquestRules.Target t, final List<Soldier> picked, final Forces estimate,
                        final ConquestState.CountryWar w, final long now) {
        final String paid = this.bridge.begin(c.id(), world, cx, cz, t.expedition(), true);
        if (!paid.startsWith("OK")) {
            this.ai.log(c.id(), NpcLogCategory.WAR, "Ofensywa w tle na " + enemy.name() + " (" + cx + "," + cz + ") niemożliwa: " + KrajeBridge.reason(paid));
            w.retryBlocked.put(chunkKey(world, cx, cz), now + this.s().targetRetryMinutes() * MINUTE);
            return;
        }
        final ConquestState.Offensive o = new ConquestState.Offensive();
        o.id = UUID.randomUUID().toString();
        o.attacker = c.id();
        o.defender = enemy.id();
        o.world = world;
        o.cx = cx;
        o.cz = cz;
        o.expedition = t.expedition();
        o.startedAt = now;
        o.lastRoundAt = now;
        for (final Soldier x : picked) {
            x.record().siege = true;
            this.army.controller().march(x, o.id);
            o.soldiers.add(x.id());
        }
        this.state.offensives.put(c.id(), o);
        w.lastOffensiveAt = now;
        this.state.war(c.id(), enemy.id()).offensives++;
        this.ai.syncKrajeLedger(c.id());
        final String kind = t.expedition() ? "WYPRAWA (przyczółek, " + t.distance() + " chunków od naszych ziem)" : "OFENSYWA";
        this.ai.log(c.id(), NpcLogCategory.WAR, kind + " w tle na " + enemy.name() + ": chunk " + cx + "," + cz + ", "
            + o.soldiers.size() + " żołnierzy, szacunek sił " + NpcAiService.fmt(estimate.attack()) + " vs "
            + NpcAiService.fmt(estimate.defence()) + ", koszt " + this.services.money(parseLong(paid.substring(3))));
        if (this.ai.state(enemy.id()).isPresent()) {
            this.ai.log(enemy.id(), NpcLogCategory.WAR, "Wojska " + c.name() + " nadciągają na chunk " + cx + "," + cz + " (bitwa w tle)");
        }
        this.history(now, c.name() + " → " + enemy.name() + ": " + (t.expedition() ? "wyprawa" : "ofensywa") + " na " + cx + "," + cz
            + " (" + o.soldiers.size() + " żołn.)");
        this.dirty = true;
    }

    private static long parseLong(final String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ battle rounds

    private List<Population.Fort> fortsOf(final UUID country) {
        return this.forts.computeIfAbsent(country, id -> {
            try {
                return this.services.population().fortifications(id);
            } catch (final LinkageError | RuntimeException e) {
                return List.of();
            }
        });
    }

    private double fortification(final UUID defender, final String world, final int cx, final int cz) {
        int walls = 0;
        int towers = 0;
        int castles = 0;
        final int r = this.s().fortRadiusChunks();
        for (final Population.Fort f : this.fortsOf(defender)) {
            if (!world.equals(f.world()) || Math.max(Math.abs((f.x() >> 4) - cx), Math.abs((f.z() >> 4) - cz)) > r) {
                continue;
            }
            switch (f.kind()) {
                case "WALL" -> walls++;
                case "TOWER" -> towers++;
                case "CASTLE" -> castles++;
                default -> { }
            }
        }
        return BattleMath.fortification(walls, towers, castles, this.s());
    }

    private static int chebyshev(final int ax, final int az, final int bx, final int bz) {
        return Math.max(Math.abs(ax - bx), Math.abs(az - bz));
    }

    /** Defenders who fight: posted near the target fully, a share of the rest comes to help (nearest first). */
    private List<Soldier> garrison(final UUID defender, final String world, final int cx, final int cz) {
        final ConquestSettings s = this.s();
        final List<Soldier> near = new ArrayList<>();
        final List<Soldier> rest = new ArrayList<>();
        for (final Soldier x : this.army.registry().ofCountry(defender)) {
            if (!this.free(x)) {
                continue;
            }
            final Pos p = x.record().spawnPos();
            final int d = p == null || !world.equals(p.world()) ? Integer.MAX_VALUE : chebyshev(p.chunkX(), p.chunkZ(), cx, cz);
            if (d <= s.garrisonRadiusChunks()) {
                near.add(x);
            } else {
                rest.add(x);
            }
        }
        rest.sort(Comparator.comparingInt((Soldier x) -> {
            final Pos p = x.record().spawnPos();
            return p == null || !world.equals(p.world()) ? Integer.MAX_VALUE : chebyshev(p.chunkX(), p.chunkZ(), cx, cz);
        }).thenComparing(Soldier::id));
        final int help = (int) Math.ceil(rest.size() * s.reinforcementShare());
        near.addAll(rest.subList(0, Math.min(help, rest.size())));
        return near;
    }

    /** Free strength of NPC allies of {@code side} that are at war with {@code enemy} (support only, no losses). */
    private double allies(final KrajInfo side, final UUID enemy) {
        double sum = 0;
        for (final UUID a : side.allies()) {
            final KrajInfo ally = this.kraje().getCountry(a).orElse(null);
            if (ally == null || !ally.npc() || !ally.enemies().contains(enemy) || !this.ai.isActive(a)) {
                continue;
            }
            sum += this.army.soldierPower(a) * this.eraMultiplier(a);
        }
        return sum * this.s().allyShare();
    }

    /** Both sides of a (planned or running) siege: final strengths with every multiplier, defenders who fight. */
    private record Forces(double attack, double defence, double fort, List<Soldier> defenders) {
    }

    private Forces evaluate(final KrajInfo att, final KrajInfo def, final String world, final int cx, final int cz,
                            final boolean expedition, final List<Soldier> force) {
        final ConquestSettings s = this.s();
        final double eraA = this.eraMultiplier(att.id());
        double baseA = 0;
        int engines = 0;
        for (final Soldier x : force) {
            baseA += BattleMath.soldier(this.unitStrength(x), eraA, x.record().morale);
            if (engine(x)) {
                engines++;
            }
        }
        final int distance = world.equals(att.capitalWorld()) ? chebyshev(att.capitalX(), att.capitalZ(), cx, cz) : s.expeditionMaxDistance();
        final boolean ship = this.army.fleet() != null && this.army.fleet().aiCountryShips(att.id()) > 0;
        final double attack = baseA * BattleMath.attacker(distance, expedition, ship, this.exhaustion(att.id()), att.chunkCount(), s)
            + this.allies(att, def.id());
        final List<Soldier> garrison = this.garrison(def.id(), world, cx, cz);
        final double eraD = this.eraMultiplier(def.id());
        double baseD = s.militia();
        for (final Soldier x : garrison) {
            baseD += BattleMath.soldier(this.unitStrength(x), eraD, x.record().morale);
        }
        final double fort = BattleMath.effectiveFort(this.fortification(def.id(), world, cx, cz), engines, s);
        final int dCap = world.equals(def.capitalWorld()) ? chebyshev(def.capitalX(), def.capitalZ(), cx, cz) : 999;
        final double defence = baseD * BattleMath.defender(dCap, fort, this.exhaustion(def.id()), s) + this.allies(def, att.id());
        return new Forces(attack, defence, fort, garrison);
    }

    private void round(final ConquestState.Offensive o, final long now) {
        final ConquestSettings s = this.s();
        final KrajInfo att = this.kraje().getCountry(o.attacker).orElse(null);
        final KrajInfo def = this.kraje().getCountry(o.defender).orElse(null);
        if (att == null) {
            this.end(o, "kraj atakujący zniknął", false);
            return;
        }
        if (def == null) {
            this.end(o, "wróg już nie istnieje", false);
            return;
        }
        if (!att.enemies().contains(def.id())) {
            this.end(o, "koniec wojny", false);
            return;
        }
        final Optional<KrajInfo> owner = this.kraje().getCountryAt(o.world, o.cx, o.cz);
        if (owner.isEmpty() || !owner.get().id().equals(def.id())) {
            this.end(o, "cel zmienił właściciela", false);
            return;
        }
        if (this.kraje().getSieges().stream().anyMatch(x -> x.attacker().equals(att.id())
            || (x.world().equals(o.world) && x.cx() == o.cx && x.cz() == o.cz))) {
            this.end(o, "przejście do walki fizycznej (oblężenie w Kraje)", false);
            return;
        }
        if (this.playersNearChunk(o.world, o.cx, o.cz)) {
            if (!o.paused) {
                o.paused = true;
                o.pausedSince = now;
                this.ai.log(att.id(), NpcLogCategory.WAR, "Ofensywa na " + o.where() + " wstrzymana: gracze w pobliżu — walka fizyczna");
            } else if (now - o.pausedSince >= PAUSE_LIMIT) {
                this.end(o, "gracze przy froncie (walka fizyczna)", false);
            }
            return;
        }
        if (o.paused) {
            o.paused = false;
            this.ai.log(att.id(), NpcLogCategory.WAR, "Ofensywa na " + o.where() + " wznowiona (brak graczy w pobliżu)");
        }
        if (now - o.lastRoundAt < s.intervalMinutes() * MINUTE - 5_000L) {
            return;
        }
        final List<Soldier> force = new ArrayList<>();
        for (final UUID id : o.soldiers) {
            final Soldier x = this.army.registry().get(id);
            if (x != null && o.id.equals(x.record().campaign)) {
                force.add(x);
            }
        }
        o.soldiers.clear();
        force.forEach(x -> o.soldiers.add(x.id()));
        // Supplies: from the attacker's treasury to the world operating budget (closed pool: nothing is created).
        final long supply = s.supplyCostPerSoldier() * force.size();
        if (supply > 0) {
            final boolean ok = this.services.skarbiec().withdraw(this.services.treasury(att.id()), supply,
                "Armia NPC: zaopatrzenie wyprawy " + att.name() + " → " + def.name()).ok();
            if (ok) {
                o.supplies += supply;
                o.unpaidRounds = 0;
                this.ai.flow(att.id(), "war", -supply, "AI", "Zaopatrzenie wojska w polu (" + force.size() + " żołn.)");
            } else {
                o.unpaidRounds++;
                force.forEach(x -> x.record().morale = Math.max(0, x.record().morale - 10));
            }
        }
        final ConquestState.CountryWar wa = this.state.country(att.id());
        final ConquestState.CountryWar wd = this.state.country(def.id());
        final Forces f = this.evaluate(att, def, o.world, o.cx, o.cz, o.expedition, force);
        final double attack = f.attack();
        final double defence = f.defence();
        final double fort = f.fort();
        final List<Soldier> garrison = f.defenders();
        final BattleMath.Round r = BattleMath.round(attack, defence, force.size(), garrison.size(), o.progress, s, this.random);
        // Real losses: weakest attackers fall first (siege engines last), nearest defenders first.
        final List<Soldier> attOrder = new ArrayList<>(force);
        attOrder.sort(Comparator.comparing(ConquestService::engine).thenComparingDouble(this::unitStrength).thenComparing(Soldier::id));
        final int attLost = this.kill(attOrder, r.attackerLosses(), def.id());
        final int defLost = this.kill(garrison, r.defenderLosses(), att.id());
        o.soldiers.removeIf(id -> this.army.registry().get(id) == null);
        final boolean won = r.delta() > 0;
        for (final UUID id : o.soldiers) {
            final Soldier x = this.army.registry().get(id);
            if (x != null) {
                x.record().morale = Math.max(0, Math.min(100, x.record().morale + (won ? 2 : -3)));
            }
        }
        this.army.registry().markDirty();
        wa.exhaustion = ConquestRules.clamp(wa.exhaustion + attLost * s.exhaustion().perSoldierLost());
        wd.exhaustion = ConquestRules.clamp(wd.exhaustion + defLost * s.exhaustion().perSoldierLost());
        final ConquestState.WarStats stats = this.state.war(att.id(), def.id());
        stats.soldiersLost += attLost;
        stats.enemiesKilled += defLost;
        o.attackerLosses += attLost;
        o.defenderLosses += defLost;
        o.rounds++;
        o.progress = r.progress();
        o.lastRoundAt = now;
        o.lastAttack = attack;
        o.lastDefence = defence;
        this.dirty = true;
        if (s.logRounds()) {
            final String line = "Bitwa o " + o.where() + " (runda " + o.rounds + "): siły " + NpcAiService.fmt(attack) + " vs "
                + NpcAiService.fmt(defence) + (fort > 0 ? " (umocnienia +" + Math.round(fort * 100) + "%)" : "") + ", postęp "
                + Math.round(o.progress * 100) + "%, straty " + attLost + " / " + defLost;
            this.ai.log(att.id(), NpcLogCategory.WAR, line);
            if (this.ai.state(def.id()).isPresent()) {
                this.ai.log(def.id(), NpcLogCategory.WAR, "Obrona " + o.where() + " przed " + att.name() + " (runda " + o.rounds + "): "
                    + garrison.size() + " obrońców, postęp wroga " + Math.round(o.progress * 100) + "%, straty " + defLost + " / " + attLost);
            }
        }
        switch (BattleMath.verdict(o.progress, o.rounds, o.soldiers.size(), s)) {
            case CAPTURED -> this.capture(o, att, def, now);
            case BROKEN -> this.fail(o, "rozbite wojsko (" + o.soldiers.size() + " żołn.)", now);
            case REPULSED -> this.fail(o, "odparci", now);
            case STALLED -> this.fail(o, "przedłużające się oblężenie (" + o.rounds + " rund)", now);
            case CONTINUE -> {
                if (o.unpaidRounds >= 3) {
                    this.fail(o, "brak zaopatrzenia", now);
                }
            }
        }
    }

    /** Kills {@code n} soldiers from the list (front first) for real; returns how many. */
    private int kill(final List<Soldier> order, final int n, final UUID killer) {
        int done = 0;
        for (final Soldier x : order) {
            if (done >= n) {
                break;
            }
            if (this.army.registry().get(x.id()) == null) {
                continue;
            }
            this.army.controller().destroy(x);
            this.ai.onSoldierDied(x, killer);
            done++;
        }
        return done;
    }

    private void capture(final ConquestState.Offensive o, final KrajInfo att, final KrajInfo def, final long now) {
        final String r = this.bridge.conquer(att.id(), o.world, o.cx, o.cz, o.expedition);
        if (!r.startsWith("OK")) {
            this.fail(o, "Kraje odmówiło zdobycia: " + KrajeBridge.reason(r), now);
            return;
        }
        final String[] parts = r.split(":");
        final String kind = parts.length > 1 ? parts[1] : "CONQUERED";
        final long loot = parts.length > 2 ? parseLong(parts[2]) : 0;
        final ConquestState.CountryWar wa = this.state.country(att.id());
        wa.taken.add(now);
        this.state.globalTaken.add(now);
        this.state.country(def.id()).lost.add(now);
        this.state.war(att.id(), def.id()).chunks++;
        if (!"RAZED".equals(kind) && !"FELL".equals(kind)) {
            this.state.conquered.add(new ConquestState.Conquered(o.world, o.cx, o.cz, att.id(), def.id(), now));
        }
        final String what = switch (kind) {
            case "BRIDGEHEAD" -> "przyczółek";
            case "RAZED" -> "spalona (dzicz)";
            case "FELL" -> "ostatnia ziemia — UPADEK";
            default -> "zdobyta";
        };
        this.ai.log(att.id(), NpcLogCategory.WAR, "ZDOBYTO w tle chunk " + o.where() + " od " + def.name() + " (" + what + ", łup "
            + this.services.money(loot) + ", straty " + o.attackerLosses + " / " + o.defenderLosses + ")");
        if (this.ai.state(def.id()).isPresent() && !"FELL".equals(kind)) {
            this.ai.log(def.id(), NpcLogCategory.WAR, "Utracono w bitwie w tle chunk " + o.where() + " na rzecz " + att.name());
        }
        this.history(now, att.name() + " zdobywa " + o.where() + " od " + def.name() + " (" + what + ")");
        if ("FELL".equals(kind)) {
            this.otherNews.add(this.messages.get().get("ai-conquest-fell", country(att), Placeholder.unparsed("target", def.name())));
        } else {
            final int[] n = this.conquests.computeIfAbsent(att.id() + ">" + def.id(), k -> new int[2]);
            n[0]++;
            if ("BRIDGEHEAD".equals(kind)) {
                n[1]++;
            }
        }
        this.ai.syncKrajeLedger(att.id());
        this.end(o, "zwycięstwo", true);
    }

    private void fail(final ConquestState.Offensive o, final String why, final long now) {
        final ConquestState.CountryWar wa = this.state.country(o.attacker);
        wa.exhaustion = ConquestRules.clamp(wa.exhaustion + this.s().exhaustion().perFailedOffensive());
        wa.retryBlocked.put(chunkKey(o.world, o.cx, o.cz), now + this.s().targetRetryMinutes() * MINUTE);
        this.state.war(o.attacker, o.defender).failed++;
        this.history(now, this.ai.name(o.attacker) + " → " + this.ai.name(o.defender) + ": ofensywa na " + o.where() + " nieudana (" + why + ")");
        if (this.ai.state(o.defender).isPresent()) {
            this.ai.log(o.defender, NpcLogCategory.WAR, "Obroniono " + o.where() + " przed " + this.ai.name(o.attacker) + " (" + why + ")");
        }
        this.end(o, "porażka: " + why, false);
    }

    /** Ends an offensive: surviving soldiers come home (campaign cleared, they reappear at their last position). */
    void end(final ConquestState.Offensive o, final String why, final boolean won) {
        this.state.offensives.remove(o.attacker, o);
        int back = 0;
        for (final UUID id : o.soldiers) {
            final Soldier x = this.army.registry().get(id);
            if (x != null && o.id.equals(x.record().campaign)) {
                this.army.controller().returnFromMarch(x);
                back++;
            }
        }
        o.soldiers.clear();
        if (this.ai.state(o.attacker).isPresent()) {
            this.ai.log(o.attacker, NpcLogCategory.WAR, "Koniec ofensywy w tle na " + o.where() + ": " + why + " — wraca " + back + " żołnierzy"
                + (o.supplies > 0 ? ", zaopatrzenie " + this.services.money(o.supplies) : ""));
        }
        this.dirty = true;
    }

    // ------------------------------------------------------------------ balance: overextension, coalition, vassals, revolts

    private void overextension(final KrajInfo c, final ConquestState.CountryWar w, final long now) {
        final long cost = ConquestRules.overextensionCost(c.chunkCount(), this.s());
        if (cost <= 0) {
            w.overextensionPaidAt = 0;
            return;
        }
        if (w.overextensionPaidAt == 0) {
            w.overextensionPaidAt = now; // first day starts now, nothing charged retroactively
            return;
        }
        if (now - w.overextensionPaidAt < ConquestRules.DAY) {
            return;
        }
        w.overextensionPaidAt = now;
        final String account = this.services.treasury(c.id());
        final long pay = Math.min(cost, this.services.skarbiec().balance(account));
        if (pay > 0 && this.services.skarbiec().withdraw(account, pay,
            "Armia NPC: utrzymanie rozległego terytorium (" + c.chunkCount() + " chunków)").ok()) {
            this.ai.flow(c.id(), "upkeep", -pay, "AI", "Utrzymanie rozległego terytorium (" + c.chunkCount() + " chunków)");
        }
        final boolean short_ = pay < cost;
        if (short_) {
            w.exhaustion = ConquestRules.clamp(w.exhaustion + 5);
        }
        this.ai.log(c.id(), NpcLogCategory.ECONOMY, "Rozległe terytorium (" + c.chunkCount() + " chunków): koszt zarządu "
            + this.services.money(pay) + (short_ ? " z " + this.services.money(cost) + " — niepokoje (zmęczenie +5)" : ""));
    }

    private void coalition(final KrajInfo c, final ConquestState.CountryWar w, final long now, final double minutes) {
        final UUID h = this.hegemon;
        if (h == null || h.equals(c.id())) {
            return;
        }
        final ConquestSettings s = this.s();
        final NpcState st = this.ai.ensure(c.id());
        final double days = (now - this.hegemonSince) / (double) ConquestRules.DAY;
        final int target = ConquestRules.coalitionOpinion(0, Math.max(days, 1.0 / 24), s);
        final int current = st.opinionOf(h);
        if (current > target) {
            st.opinion.put(h, target);
        }
        if (now - w.coalitionAt >= ConquestRules.DAY) {
            w.coalitionAt = now;
            this.ai.log(c.id(), NpcLogCategory.DIPLOMACY, "Obawa przed hegemonią " + this.ai.name(h) + " — opinia " + st.opinionOf(h));
        }
        final KrajInfo heg = this.kraje().getCountry(h).orElse(null);
        if (heg == null || heg.enemies().isEmpty() || heg.enemies().contains(c.id()) || !c.enemies().isEmpty()
            || st.posture() == NpcPosture.DEFENSIVE || this.ai.protectedFromUs(c.id(), h) || st.opinionOf(h) >= 0) {
            return;
        }
        if (this.random.getAsDouble() >= ConquestRules.perCycle(s.coalition().joinChancePerDay(), minutes)) {
            return;
        }
        final KrajeResult r = this.ai.declareWar(c, h, "koalicja przeciw hegemonowi");
        if (r.ok()) {
            this.otherNews.add(this.messages.get().get("ai-conquest-coalition", country(c), Placeholder.unparsed("target", heg.name())));
            this.history(now, c.name() + " dołącza do koalicji przeciw " + heg.name());
        }
    }

    private void secession(final KrajInfo overlord, final double minutes) {
        final ConquestSettings s = this.s();
        final Set<UUID> vassals;
        try {
            vassals = this.kraje().getVassals(overlord.id());
        } catch (final LinkageError e) {
            return;
        }
        if (vassals.isEmpty()) {
            return;
        }
        final double exh = this.exhaustion(overlord.id());
        for (final UUID v : vassals) {
            final KrajInfo vassal = this.kraje().getCountry(v).orElse(null);
            if (vassal == null || !vassal.npc()) {
                continue;
            }
            final int opinion = this.ai.ensure(v).opinionOf(overlord.id());
            if (!ConquestRules.secessionPossible(exh, overlord.chunkCount(), opinion, s)
                || this.random.getAsDouble() >= ConquestRules.perCycle(s.secessionChancePerDay(), minutes)) {
                continue;
            }
            KrajeResult r;
            try {
                r = this.kraje().releaseVassal(overlord.id(), v);
            } catch (final LinkageError e) {
                r = KrajeResult.fail(KrajeResult.Status.DENIED, "Kraje bez wasalstwa");
            }
            if (r.ok()) {
                this.ai.log(v, NpcLogCategory.DIPLOMACY, "ODPADNIĘCIE od suwerena " + overlord.name() + " (zmęczenie " + Math.round(exh) + ", opinia " + opinion + ")");
                this.ai.log(overlord.id(), NpcLogCategory.DIPLOMACY, "Wasal " + vassal.name() + " odpada — nie mamy sił, by go utrzymać");
                this.otherNews.add(this.messages.get().get("ai-conquest-secession", country(overlord),
                    Placeholder.unparsed("vassal", vassal.name())));
                this.history(System.currentTimeMillis(), vassal.name() + " odpada od " + overlord.name());
            }
        }
    }

    private void revolts(final double minutes) {
        final ConquestSettings s = this.s();
        final long now = System.currentTimeMillis();
        this.state.conquered.removeIf(x -> (now - x.at) / 3_600_000.0 > s.revolt().windowHours());
        for (final ConquestState.Conquered x : List.copyOf(this.state.conquered)) {
            final KrajInfo holder = this.kraje().getCountry(x.holder).orElse(null);
            final Optional<KrajInfo> owner = this.kraje().getCountryAt(x.world, x.cx, x.cz);
            if (holder == null || owner.isEmpty() || !owner.get().id().equals(holder.id())) {
                this.state.conquered.remove(x);
                continue;
            }
            if (!holder.npc() || !this.ai.isActive(holder.id())) {
                continue;
            }
            final ConquestState.CountryWar w = this.state.country(holder.id());
            if (ConquestRules.lastDay(w.revolts, now) >= s.revolt().maxPerDay()) {
                continue;
            }
            final double pDay = ConquestRules.revoltChancePerDay((now - x.at) / 3_600_000.0, holder.chunkCount(), w.exhaustion, s);
            if (this.random.getAsDouble() >= ConquestRules.perCycle(pDay, minutes) || this.playersNearChunk(x.world, x.cx, x.cz)) {
                continue;
            }
            final boolean formerAlive = x.former != null && this.kraje().getCountry(x.former).isPresent();
            final String r = this.bridge.revolt(holder.id(), x.world, x.cx, x.cz, formerAlive ? x.former : null);
            if (!r.startsWith("OK")) {
                continue;
            }
            this.state.conquered.remove(x);
            w.revolts.add(now);
            w.exhaustion = ConquestRules.clamp(w.exhaustion + 2);
            final String to = formerAlive ? this.ai.name(x.former) : "wolnych osadników (dzicz)";
            this.ai.log(holder.id(), NpcLogCategory.WAR, "BUNT na zdobytej ziemi " + x.cx + "," + x.cz + " — przechodzi do: " + to);
            if (formerAlive && this.ai.state(x.former).isPresent()) {
                this.ai.log(x.former, NpcLogCategory.WAR, "Bunt na ziemi " + holder.name() + ": chunk " + x.cx + "," + x.cz + " wraca do nas");
            }
            this.revolts.computeIfAbsent(holder.id() + ">" + (formerAlive ? x.former : ""), k -> new int[1])[0]++;
            this.history(now, "Bunt: " + holder.name() + " traci " + x.cx + "," + x.cz + " (" + to + ")");
            this.dirty = true;
        }
    }

    // ------------------------------------------------------------------ events

    /** Kraje: relation changed. War: new statistics; peace: offensives end, peace terms (tribute) for the winner. */
    public void onRelationChange(final UUID a, final UUID b, final Relation oldRel, final Relation newRel) {
        if (this.state == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (newRel == Relation.WAR && oldRel != Relation.WAR) {
            for (final String k : new String[] {a + ">" + b, b + ">" + a}) {
                final ConquestState.WarStats w = new ConquestState.WarStats();
                w.since = now;
                this.state.wars.put(k, w);
            }
            return;
        }
        if (oldRel != Relation.WAR || newRel == Relation.WAR) {
            return;
        }
        for (final ConquestState.Offensive o : List.copyOf(this.state.offensives.values())) {
            if ((o.attacker.equals(a) && o.defender.equals(b)) || (o.attacker.equals(b) && o.defender.equals(a))) {
                this.end(o, "pokój", false);
            }
        }
        // One tick later: a capitulation (vassalage) is applied right after the relation change.
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.peaceTerms(a, b), 2L);
    }

    private void peaceTerms(final UUID a, final UUID b) {
        final ConquestState.WarStats ab = this.state.wars.get(a + ">" + b);
        final ConquestState.WarStats ba = this.state.wars.get(b + ">" + a);
        final int net = (ab == null ? 0 : ab.chunks) - (ba == null ? 0 : ba.chunks);
        if (net == 0 || !this.s().enabled()) {
            return;
        }
        final UUID winner = net > 0 ? a : b;
        final UUID loser = net > 0 ? b : a;
        final KrajInfo w = this.kraje().getCountry(winner).orElse(null);
        final KrajInfo l = this.kraje().getCountry(loser).orElse(null);
        if (w == null || l == null || !w.npc() || !l.npc()) {
            return;
        }
        try {
            if (this.kraje().getOverlord(loser).map(winner::equals).orElse(false)) {
                return; // capitulation: Kraje's vassal tribute already applies
            }
        } catch (final LinkageError ignored) {
            // older Kraje: no vassals
        }
        final NpcState ws = this.ai.ensure(winner);
        if (ws.tributeWith(loser) != null) {
            return;
        }
        final long amount = ConquestRules.peaceTribute(this.ai.dailyIncome(l, this.ai.ensure(loser)), Math.abs(net), this.s());
        if (amount <= 0) {
            return;
        }
        this.ai.startTribute(winner, loser, amount);
        final int days = this.config.get().rules.tributeDays();
        this.ai.log(winner, NpcLogCategory.DIPLOMACY, "Warunki pokoju z " + l.name() + ": trybut " + this.services.money(amount) + "/dzień przez " + days
            + " dni (zdobyte " + Math.abs(net) + " chunków)");
        this.ai.log(loser, NpcLogCategory.DIPLOMACY, "Warunki pokoju z " + w.name() + ": płacimy trybut " + this.services.money(amount) + "/dzień przez " + days + " dni");
        this.otherNews.add(this.messages.get().get("ai-conquest-peace-tribute", country(w), Placeholder.unparsed("target", l.name()),
            Placeholder.unparsed("amount", this.services.money(amount)), Placeholder.unparsed("days", Integer.toString(days))));
        this.history(System.currentTimeMillis(), "Pokój " + w.name() + " – " + l.name() + ": trybut " + this.services.money(amount) + "/dzień");
        this.dirty = true;
    }

    /** Kraje: chunk changed owner — every conquest (also physical) tires the loser. */
    public void onChunkChange(final UUID oldOwner, final UUID newOwner, final ClaimCause cause) {
        if (this.state == null || oldOwner == null || newOwner == null || cause != ClaimCause.CONQUEST) {
            return;
        }
        if (this.kraje().getCountry(oldOwner).map(KrajInfo::npc).orElse(false)) {
            final ConquestState.CountryWar w = this.state.country(oldOwner);
            w.exhaustion = ConquestRules.clamp(w.exhaustion + this.s().exhaustion().perChunkLost());
        }
    }

    /** Kraje: a country disappeared — its offensives end, offensives against it end. */
    public void onDisband(final UUID country) {
        if (this.state == null) {
            return;
        }
        for (final ConquestState.Offensive o : List.copyOf(this.state.offensives.values())) {
            if (o.attacker.equals(country) || o.defender.equals(country)) {
                this.end(o, o.attacker.equals(country) ? "kraj atakujący zniknął" : "wróg upadł", false);
            }
        }
        this.state.countries.remove(country);
        this.dirty = true;
    }

    // ------------------------------------------------------------------ announcements (aggregated)

    private static TagResolver country(final KrajInfo c) {
        return Placeholder.component("country", Component.text(c.name(), c.color()));
    }

    static String lands(final int n) {
        final int mod10 = n % 10;
        final int mod100 = n % 100;
        if (n == 1) {
            return "ziemię";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "ziemie";
        }
        return "ziem";
    }

    private void flush(final long now, final boolean force) {
        if (!force && now - this.lastAnnounceAt < this.s().announceMinutes() * MINUTE) {
            return;
        }
        this.lastAnnounceAt = now;
        final Messages m = this.messages.get();
        final List<Component> lines = new ArrayList<>();
        for (final Map.Entry<String, int[]> e : this.conquests.entrySet()) {
            final String[] p = e.getKey().split(">");
            final KrajInfo a = this.kraje().getCountry(UUID.fromString(p[0])).orElse(null);
            if (a == null) {
                continue;
            }
            final int n = e.getValue()[0];
            lines.add(m.get("ai-conquest-summary", country(a), Placeholder.unparsed("count", Integer.toString(n)),
                Placeholder.unparsed("lands", lands(n)), Placeholder.unparsed("target", this.ai.name(UUID.fromString(p[1]))),
                Placeholder.unparsed("extra", e.getValue()[1] > 0 ? " (przyczółek za granicą)" : "")));
        }
        for (final Map.Entry<String, int[]> e : this.revolts.entrySet()) {
            final String[] p = e.getKey().split(">", -1);
            final KrajInfo h = this.kraje().getCountry(UUID.fromString(p[0])).orElse(null);
            if (h == null) {
                continue;
            }
            final int n = e.getValue()[0];
            lines.add(m.get("ai-conquest-revolt", country(h), Placeholder.unparsed("count", Integer.toString(n)),
                Placeholder.unparsed("lands", lands(n)),
                Placeholder.unparsed("target", p.length > 1 && !p[1].isEmpty() ? this.ai.name(UUID.fromString(p[1])) : "wolnych osadników")));
        }
        lines.addAll(this.otherNews);
        this.conquests.clear();
        this.revolts.clear();
        this.otherNews.clear();
        final int max = 6;
        for (int i = 0; i < Math.min(max, lines.size()); i++) {
            Bukkit.broadcast(lines.get(i));
        }
        if (lines.size() > max) {
            Bukkit.broadcast(m.get("ai-conquest-more", Placeholder.unparsed("count", Integer.toString(lines.size() - max))));
        }
    }

    private void history(final long now, final String text) {
        this.state.event(now, text);
        this.dirty = true;
    }

    // ------------------------------------------------------------------ /nk wojny, panel

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static String esc(final String s) {
        return MM.escapeTags(s == null ? "?" : s);
    }

    /** {@code /nk wojny [kraj]}: wars involving NPC countries, offensives with progress, exhaustion, recent events. */
    public void describe(final CommandSender to, final UUID only) {
        final ConquestSettings s = this.s();
        final long now = System.currentTimeMillis();
        to.sendMessage(MM.deserialize("<gold><bold>Wojny państw NPC</bold></gold> <gray>(w tle: " + (s.enabled() ? "<green>włączone" : "<red>wyłączone")
            + "<gray>, co " + s.intervalMinutes() + " min, zdobycze dziś " + ConquestRules.lastDay(this.state.globalTaken, now) + "/"
            + s.globalMaxChunksPerDay() + (this.hegemon != null ? ", hegemon: <white>" + esc(this.ai.name(this.hegemon)) + "</white>" : "") + ")"));
        final Set<String> seen = new HashSet<>();
        int wars = 0;
        for (final KrajInfo c : this.kraje().getCountries()) {
            for (final UUID e : c.enemies()) {
                final KrajInfo o = this.kraje().getCountry(e).orElse(null);
                if (o == null || (!c.npc() && !o.npc()) || (only != null && !only.equals(c.id()) && !only.equals(e))) {
                    continue;
                }
                final String key = c.id().compareTo(e) < 0 ? c.id() + "|" + e : e + "|" + c.id();
                if (!seen.add(key)) {
                    continue;
                }
                wars++;
                final ConquestState.WarStats ab = this.state.wars.get(c.id() + ">" + e);
                final ConquestState.WarStats ba = this.state.wars.get(e + ">" + c.id());
                final long since = Math.max(ab == null ? 0 : ab.since, ba == null ? 0 : ba.since);
                to.sendMessage(MM.deserialize("<dark_red>⚔</dark_red> <white>" + esc(c.name()) + "</white> <gray>(" + (c.npc() ? "NPC" : "gracz")
                    + ", zmęczenie " + Math.round(this.exhaustion(c.id())) + ")</gray> <dark_gray>vs</dark_gray> <white>" + esc(o.name())
                    + "</white> <gray>(" + (o.npc() ? "NPC" : "gracz") + ", zmęczenie " + Math.round(this.exhaustion(e)) + ")"
                    + (since > 0 ? " — " + NpcAiService.fmt((now - since) / 3_600_000.0) + " h" : "")
                    + " — zdobycze " + (ab == null ? 0 : ab.chunks) + " : " + (ba == null ? 0 : ba.chunks)
                    + ", ofensywy " + (ab == null ? 0 : ab.offensives) + " : " + (ba == null ? 0 : ba.offensives)
                    + ", straty " + (ab == null ? 0 : ab.soldiersLost) + " : " + (ba == null ? 0 : ba.soldiersLost) + "</gray>"));
                for (final UUID side : new UUID[] {c.id(), e}) {
                    final ConquestState.Offensive off = this.state.offensives.get(side);
                    if (off == null) {
                        continue;
                    }
                    to.sendMessage(MM.deserialize("  <yellow>→</yellow> <white>" + esc(this.ai.name(off.attacker)) + "</white> <gray>"
                        + (off.expedition ? "wyprawa" : "ofensywa") + " na " + off.where() + ": <white>" + Math.round(off.progress * 100)
                        + "%</white>, runda " + off.rounds + "/" + s.maxRounds() + ", " + off.soldiers.size() + " żołn., siły "
                        + NpcAiService.fmt(off.lastAttack) + " vs " + NpcAiService.fmt(off.lastDefence) + ", straty " + off.attackerLosses
                        + " / " + off.defenderLosses + (off.paused ? " <red>(wstrzymana — gracze w pobliżu)</red>" : " <green>(w tle)</green>")));
                }
            }
        }
        if (wars == 0) {
            to.sendMessage(MM.deserialize("<gray>Brak wojen z udziałem państw NPC."));
        }
        final List<ConquestState.Event> h = this.state.history;
        if (!h.isEmpty()) {
            to.sendMessage(MM.deserialize("<gold>Ostatnie wydarzenia:"));
            final java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm");
            for (int i = Math.max(0, h.size() - 8); i < h.size(); i++) {
                final ConquestState.Event ev = h.get(i);
                to.sendMessage(MM.deserialize("<dark_gray>" + java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ev.at),
                    java.time.ZoneId.systemDefault()).format(f) + "</dark_gray> <gray>" + esc(ev.text)));
            }
        }
    }

    /** Snapshot for the web panel (narrow bridge {@code ArmiaPlugin#npcConquestJson}). Main thread. */
    public String json() {
        final Map<String, Object> out = new LinkedHashMap<>();
        final long now = System.currentTimeMillis();
        out.put("enabled", this.s().enabled());
        out.put("hegemon", this.hegemon == null ? null : this.hegemon.toString());
        out.put("takenToday", ConquestRules.lastDay(this.state.globalTaken, now));
        final List<Map<String, Object>> offs = new ArrayList<>();
        for (final ConquestState.Offensive o : this.state.offensives.values()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("attacker", o.attacker.toString());
            m.put("attackerName", this.ai.name(o.attacker));
            m.put("defender", o.defender.toString());
            m.put("defenderName", this.ai.name(o.defender));
            m.put("world", o.world);
            m.put("cx", o.cx);
            m.put("cz", o.cz);
            m.put("expedition", o.expedition);
            m.put("progress", o.progress);
            m.put("rounds", o.rounds);
            m.put("soldiers", o.soldiers.size());
            m.put("paused", o.paused);
            m.put("attack", o.lastAttack);
            m.put("defence", o.lastDefence);
            offs.add(m);
        }
        out.put("offensives", offs);
        final Map<String, Double> exh = new LinkedHashMap<>();
        this.state.countries.forEach((k, v) -> exh.put(k.toString(), (double) Math.round(v.exhaustion)));
        out.put("exhaustion", exh);
        final List<String> hist = new ArrayList<>();
        for (final ConquestState.Event e : this.state.history) {
            hist.add(e.at + " " + e.text);
        }
        out.put("history", hist);
        return new GsonBuilder().disableHtmlEscaping().create().toJson(out);
    }

    /** For tests/diagnostics. */
    public String bridgeStatus() {
        return this.bridge.available() ? "OK" : "brak";
    }

    static String lower(final String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
