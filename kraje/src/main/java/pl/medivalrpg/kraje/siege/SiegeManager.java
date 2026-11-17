// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.siege;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.KrajeResult.Status;
import pl.medivalrpg.kraje.api.SiegeContributor;
import pl.medivalrpg.kraje.api.SiegeInfo;
import pl.medivalrpg.kraje.api.event.SiegeEndEvent;
import pl.medivalrpg.kraje.api.event.SiegeStartEvent;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.manager.Notify;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.protection.ProtectionService;
import pl.medivalrpg.kraje.util.Format;

/**
 * Running sieges: start rules, progress once per second (players in the chunk + {@link SiegeContributor}s from the
 * ServicesManager), boss bars, conquest and the build exemption inside a sieged chunk. Sieges live only in memory:
 * a restart ends them without conquest. Main thread only.
 */
public final class SiegeManager {

    /** A running siege (mutable, main thread). */
    public static final class Siege {
        final UUID id = UUID.randomUUID();
        final UUID attacker;
        final UUID defender;
        final ChunkPos pos;
        final long startedAt;
        final long endsAt;
        final BossBar bar;
        boolean landing;
        final Set<UUID> viewers = new HashSet<>();
        double progress;
        double attackers;
        double defenders;

        Siege(final UUID attacker, final UUID defender, final ChunkPos pos, final long startedAt, final long endsAt) {
            this.attacker = attacker;
            this.defender = defender;
            this.pos = pos;
            this.startedAt = startedAt;
            this.endsAt = endsAt;
            this.bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        }

        public UUID attacker() {
            return this.attacker;
        }

        public UUID defender() {
            return this.defender;
        }

        public ChunkPos pos() {
            return this.pos;
        }

        public double progress() {
            return this.progress;
        }

        public long endsAt() {
            return this.endsAt;
        }

        SiegeInfo info() {
            return new SiegeInfo(this.id, this.attacker, this.defender, this.pos.world(), this.pos.x(), this.pos.z(),
                this.progress, this.startedAt, this.endsAt);
        }
    }

    private final KrajePlugin plugin;
    private final Map<UUID, Siege> sieges = new LinkedHashMap<>();
    private volatile List<SiegeInfo> snapshot = List.of();
    private BukkitTask task;
    private long ticksSinceMapRefresh;
    private KrajeConfig excludedFor;
    private Set<Material> excluded = EnumSet.noneOf(Material.class);
    private final Set<String> failingContributors = new HashSet<>();

    public SiegeManager(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    private CountryManager manager() {
        return this.plugin.manager();
    }

    private KrajeConfig cfg() {
        return this.plugin.cfg();
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    public void start() {
        if (this.task == null) {
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L, 20L);
        }
    }

    // ------------------------------------------------------------------ queries

    /** Thread-safe snapshot. */
    public List<SiegeInfo> infos() {
        return this.snapshot;
    }

    public Collection<Siege> sieges() {
        return List.copyOf(this.sieges.values());
    }

    public Siege siegeAt(final ChunkPos pos) {
        for (final Siege s : this.sieges.values()) {
            if (s.pos.equals(pos)) {
                return s;
            }
        }
        return null;
    }

    public Siege siegeOf(final UUID attacker) {
        for (final Siege s : this.sieges.values()) {
            if (s.attacker.equals(attacker)) {
                return s;
            }
        }
        return null;
    }

    private void updateSnapshot() {
        this.snapshot = this.sieges.values().stream().map(Siege::info).toList();
    }

    // ------------------------------------------------------------------ start

    /** Starts a siege of {@code pos} by {@code attacker} (rules + cost). {@code actor} is the player's name or null. */
    public Siege start(final Country attacker, final ChunkPos pos, final String actor) {
        return this.start(attacker,pos,actor,false);
    }

    public Siege startLanding(final Country attacker, final ChunkPos pos, final String actor) {
        return this.start(attacker,pos,actor,true);
    }

    private Siege start(final Country attacker, final ChunkPos pos, final String actor, final boolean landing) {
        final KrajeConfig cfg = this.cfg();
        if (!cfg.siegeEnabled) {
            throw new KrajeException(Status.DENIED, "siege-disabled");
        }
        final Country defender = this.manager().countryAt(pos);
        if (defender == null) {
            throw new KrajeException(Status.NOT_FOUND, "siege-not-enemy");
        }
        if (defender == attacker) {
            throw new KrajeException(Status.INVALID, "siege-own");
        }
        if (attacker.relationTo(defender.id()) != Relation.WAR) {
            throw new KrajeException(Status.NOT_AT_WAR, "siege-not-at-war", Notify.country(defender));
        }
        if (this.siegeOf(attacker.id()) != null) {
            throw new KrajeException(Status.LIMIT_REACHED, "siege-already");
        }
        if (this.siegeAt(pos) != null) {
            throw new KrajeException(Status.LIMIT_REACHED, "siege-chunk-busy");
        }
        if (landing) {
            if (!cfg.landingEnabled) throw new KrajeException(Status.DENIED,"landing-disabled");
            if (cfg.isWorldBlocked(pos.world())) throw new KrajeException(Status.DENIED,"world-blocked");
            this.plugin.actions().requireNotProtected(pos);
            if (!this.landingSlot(attacker,pos)) throw new KrajeException(Status.LIMIT_REACHED,"landing-limit");
            if (!LandingCoast.coastal(Bukkit.getWorld(pos.world()),pos)) throw new KrajeException(Status.DENIED,"landing-not-coastal");
            boolean supported=false;
            for (final var reg:Bukkit.getServicesManager().getRegistrations(pl.medivalrpg.kraje.api.LandingSupport.class)) {
                try {
                    if (reg.getProvider().canLand(attacker.id(),pos.world(),pos.x(),pos.z())) {supported=true;break;}
                } catch (final RuntimeException | LinkageError e) {
                    this.plugin.getLogger().log(Level.WARNING,"LandingSupport: odmowa po błędzie dostawcy",e);
                }
            }
            if (!supported) throw new KrajeException(Status.DENIED,"landing-no-transport");
        }
        final ConquestPlan.Outcome plan = ConquestPlan.plan(attacker.claimsMap(), defender.claimsMap(), defender.capital(), pos,
            cfg.siegeRequireAdjacent, cfg.siegeCapitalAnytime, landing);
        switch (plan.problem()) {
            case NOT_ADJACENT -> throw new KrajeException(Status.NOT_ADJACENT, "siege-not-adjacent");
            case SPLITS_DEFENDER -> throw new KrajeException(Status.DENIED, "siege-splits", Notify.country(defender));
            case CAPITAL_PROTECTED -> throw new KrajeException(Status.DENIED, "siege-capital", Notify.country(defender));
            case NOT_OWNED -> throw new KrajeException(Status.NOT_FOUND, "siege-not-enemy");
            case NONE -> { }
        }
        this.plugin.treasury().charge(attacker, cfg.siegeCost+(landing ? cfg.landingExtraCost : 0), landing ? "desant" : "siege", actor);

        final long now = System.currentTimeMillis();
        final Siege s = new Siege(attacker.id(), defender.id(), pos, now, now + cfg.siegeMaxDurationMillis);
        s.landing=landing;
        this.sieges.put(s.id, s);
        this.updateSnapshot();
        this.refreshBar(s, attacker, defender);
        this.updateViewers(s, attacker, defender);

        final TagResolver r = TagResolver.resolver(Notify.pair(attacker, defender),
            Notify.unparsed("x", pos.centerBlockX()), Notify.unparsed("z", pos.centerBlockZ()),
            Notify.unparsed("minutes", cfg.siegeMaxDurationMillis / 60_000L));
        Notify.broadcast(this.messages(), landing ? "landing-started" : "siege-started", r);
        Notify.members(this.messages(), defender, "siege-started-defender", r);
        Bukkit.getPluginManager().callEvent(new SiegeStartEvent(s.id, attacker.id(), defender.id(), pos.world(), pos.x(), pos.z()));
        this.plugin.mapHook().refreshSieges();
        return s;
    }

    private boolean landingSlot(final Country attacker,final ChunkPos pos) {
        return pl.medivalrpg.kraje.geometry.Bridgeheads.canEstablish(attacker,pos,this.cfg().landingMaxBridgeheads);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (this.sieges.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final Siege s : List.copyOf(this.sieges.values())) {
            if (!this.sieges.containsKey(s.id)) {
                continue; // ended by a previous siege's conquest in this tick
            }
            try {
                this.tick(s, now);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Błąd oblężenia " + s.pos, e);
            }
        }
        this.updateSnapshot();
        this.ticksSinceMapRefresh += 20;
        if (!this.sieges.isEmpty() && this.ticksSinceMapRefresh >= this.cfg().siegeMapRefreshTicks) {
            this.ticksSinceMapRefresh = 0;
            this.plugin.mapHook().refreshSieges();
        }
    }

    private void tick(final Siege s, final long now) {
        final Country attacker = this.manager().get(s.attacker);
        final Country defender = this.manager().get(s.defender);
        if (attacker == null || defender == null || this.manager().countryAt(s.pos) != defender) {
            this.end(s, false, "siege-ended-lost");
            return;
        }
        if (attacker.relationTo(defender.id()) != Relation.WAR) {
            this.end(s, false, "siege-ended-peace");
            return;
        }
        this.measure(s, attacker, defender);
        final KrajeConfig cfg = this.cfg();
        s.progress = SiegeMath.step(s.progress, s.attackers, s.defenders, 1.0, cfg.siegeCaptureSeconds, cfg.siegeMaxSpeedMultiplier);
        if (s.progress >= 1.0) {
            this.conquer(s, attacker, defender);
            return;
        }
        if (now >= s.endsAt) {
            this.end(s, false, "siege-ended-timeout");
            return;
        }
        this.refreshBar(s, attacker, defender);
        this.updateViewers(s, attacker, defender);
    }

    /** Which side a country fights on: 1 attacker, -1 defender, 0 none. */
    private int side(final UUID country, final Country attacker, final Country defender) {
        if (country == null) {
            return 0;
        }
        if (country.equals(attacker.id())) {
            return 1;
        }
        if (country.equals(defender.id())) {
            return -1;
        }
        if (!this.cfg().siegeAlliesHelp) {
            return 0;
        }
        final boolean withAttacker = attacker.relationTo(country) == Relation.ALLIANCE;
        final boolean withDefender = defender.relationTo(country) == Relation.ALLIANCE;
        return withAttacker == withDefender ? 0 : withAttacker ? 1 : -1;
    }

    private void measure(final Siege s, final Country attacker, final Country defender) {
        double att = 0;
        double def = 0;
        final World world = Bukkit.getWorld(s.pos.world());
        if (world != null) {
            for (final Player p : world.getPlayers()) {
                if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) {
                    continue;
                }
                final Location l = p.getLocation();
                if (l.getBlockX() >> 4 != s.pos.x() || l.getBlockZ() >> 4 != s.pos.z()) {
                    continue;
                }
                final int side = this.side(this.manager().countryIdOf(p.getUniqueId()), attacker, defender);
                if (side > 0) {
                    att += 1;
                } else if (side < 0) {
                    def += 1;
                }
            }
        }
        final Collection<RegisteredServiceProvider<SiegeContributor>> contributors =
            Bukkit.getServicesManager().getRegistrations(SiegeContributor.class);
        if (!contributors.isEmpty()) {
            final List<UUID> attackSide = new ArrayList<>();
            final List<UUID> defenseSide = new ArrayList<>();
            for (final Country c : this.manager().countries()) {
                final int side = this.side(c.id(), attacker, defender);
                if (side > 0) {
                    attackSide.add(c.id());
                } else if (side < 0) {
                    defenseSide.add(c.id());
                }
            }
            for (final RegisteredServiceProvider<SiegeContributor> reg : contributors) {
                final SiegeContributor contributor = reg.getProvider();
                try {
                    for (final UUID id : attackSide) {
                        att += Math.max(0, contributor.presence(id, s.pos.world(), s.pos.x(), s.pos.z()));
                    }
                    for (final UUID id : defenseSide) {
                        def += Math.max(0, contributor.presence(id, s.pos.world(), s.pos.x(), s.pos.z()));
                    }
                } catch (final RuntimeException | LinkageError e) {
                    if (this.failingContributors.add(reg.getPlugin().getName())) {
                        this.plugin.getLogger().log(Level.WARNING, "SiegeContributor z " + reg.getPlugin().getName() + " rzucił wyjątek", e);
                    }
                }
            }
        }
        s.attackers = att;
        s.defenders = def;
    }

    private void refreshBar(final Siege s, final Country attacker, final Country defender) {
        final int trend = SiegeMath.trend(s.attackers, s.defenders);
        final String stateKey = trend > 0 ? "siege-state-rising" : trend < 0 ? "siege-state-falling" : "siege-state-standing";
        final Component title = this.messages().get("siege-bossbar", Notify.pair(attacker, defender),
            Notify.unparsed("progress", (int) Math.floor(s.progress * 100)),
            Notify.unparsed("time", Format.clock(s.endsAt - System.currentTimeMillis())),
            Notify.unparsed("attackers", formatStrength(s.attackers)),
            Notify.unparsed("defenders", formatStrength(s.defenders)),
            Placeholder.component("state", this.messages().get(stateKey)));
        s.bar.name(title);
        s.bar.progress((float) Math.max(0, Math.min(1, s.progress)));
        s.bar.color(trend > 0 ? BossBar.Color.RED : trend < 0 ? BossBar.Color.BLUE : BossBar.Color.WHITE);
    }

    static String formatStrength(final double v) {
        return Math.abs(v - Math.rint(v)) < 0.05 ? Long.toString(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private void updateViewers(final Siege s, final Country attacker, final Country defender) {
        final Set<UUID> now = new HashSet<>();
        final int radius = this.cfg().siegeBossbarRadiusChunks;
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().getName().equals(s.pos.world())) {
                continue;
            }
            final int dx = Math.abs((p.getLocation().getBlockX() >> 4) - s.pos.x());
            final int dz = Math.abs((p.getLocation().getBlockZ() >> 4) - s.pos.z());
            final boolean inside = dx == 0 && dz == 0;
            final boolean participant = this.side(this.manager().countryIdOf(p.getUniqueId()), attacker, defender) != 0;
            if (inside || (participant && Math.max(dx, dz) <= radius)) {
                now.add(p.getUniqueId());
            }
        }
        for (final UUID id : s.viewers) {
            if (!now.contains(id)) {
                final Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.hideBossBar(s.bar);
                }
            }
        }
        for (final UUID id : now) {
            if (!s.viewers.contains(id)) {
                final Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.showBossBar(s.bar);
                }
            }
        }
        s.viewers.clear();
        s.viewers.addAll(now);
    }

    private void hideBar(final Siege s) {
        for (final UUID id : s.viewers) {
            final Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(s.bar);
            }
        }
        s.viewers.clear();
    }

    // ------------------------------------------------------------------ end

    private void conquer(final Siege s, final Country attacker, final Country defender) {
        if (s.landing && this.plugin.protectedAreas().at(s.pos.world(),s.pos.x(),s.pos.z()).isPresent()) {
            this.end(s,false,"landing-ended-protected"); return;
        }
        if (s.landing && !this.landingSlot(attacker,s.pos)) {
            this.end(s,false,"landing-ended-limit"); return;
        }
        final ConquestPlan.Outcome plan = ConquestPlan.plan(attacker.claimsMap(), defender.claimsMap(), defender.capital(), s.pos,
            false, this.cfg().siegeCapitalAnytime, s.landing);
        if (!plan.possible()) {
            this.end(s, false, plan.problem() == ConquestPlan.Problem.SPLITS_DEFENDER ? "siege-ended-split" : "siege-ended-lost");
            return;
        }
        this.sieges.remove(s.id);
        this.hideBar(s);
        this.updateSnapshot();

        final TagResolver r = TagResolver.resolver(Notify.pair(attacker, defender),
            Notify.unparsed("x", s.pos.centerBlockX()), Notify.unparsed("z", s.pos.centerBlockZ()));
        this.manager().transferChunk(defender, plan.toAttacker() ? attacker : null, s.pos.world(), s.pos.key(), ClaimCause.CONQUEST,s.landing);
        Notify.broadcast(this.messages(), s.landing ? "landing-conquered" : plan.toAttacker() ? "siege-conquered" : "siege-razed", r);
        // Spoils (only with Skarbiec): a share of the defender's current treasury for every conquered chunk.
        this.plugin.treasury().lootChunk(attacker, defender);
        if (plan.defenderFalls()) {
            Notify.broadcast(this.messages(), "siege-country-fell", r);
            final List<pl.medivalrpg.kraje.model.Member> former = List.copyOf(defender.members());
            // The rest of the treasury: most of it to the winner, the remainder destroyed or shared among the citizens.
            this.plugin.treasury().lootFall(attacker, defender);
            this.plugin.actions().disband(defender, ClaimCause.CONQUEST);
            // Former citizens may join the conqueror (persistent invitation, also for offline players).
            if (this.manager().get(attacker.id()) != null) {
                this.plugin.actions().inviteFormerCitizens(attacker, defender, former);
            }
        } else if (plan.newCapital() != null) {
            this.manager().setCapital(defender, plan.newCapital(), spawnIn(plan.newCapital(), defender.spawn()));
            Notify.members(this.messages(), defender, "siege-capital-moved", Notify.country(defender),
                Notify.unparsed("x", plan.newCapital().centerBlockX()), Notify.unparsed("z", plan.newCapital().centerBlockZ()));
        }
        Bukkit.getPluginManager().callEvent(new SiegeEndEvent(s.id, s.attacker, s.defender, s.pos.world(), s.pos.x(), s.pos.z(), true));
        this.plugin.mapHook().refreshSieges();
    }

    /**
     * Spawn point on top of the centre of a chunk. Never loads the chunk: the new capital is usually far from the
     * fight, and {@code getHighestBlockYAt} on an unloaded chunk loads (even generates) it on the main thread. Unloaded
     * → the height of the old spawn (as the background conquest does), 64 without a world.
     */
    static SpawnPoint spawnIn(final ChunkPos pos, final SpawnPoint old) {
        final World world = Bukkit.getWorld(pos.world());
        final int x = pos.centerBlockX();
        final int z = pos.centerBlockZ();
        final double y = world != null && world.isChunkLoaded(pos.x(), pos.z()) ? world.getHighestBlockYAt(x, z) + 1
            : old != null ? old.y() : 64;
        return new SpawnPoint(pos.world(), x + 0.5, y, z + 0.5, 0f, 0f);
    }

    /** Ends without conquest; {@code messageKey} (empty = silent) is broadcast. */
    private void end(final Siege s, final boolean conquered, final String messageKey) {
        if (this.sieges.remove(s.id) == null) {
            return;
        }
        this.hideBar(s);
        this.updateSnapshot();
        final Country attacker = this.manager().get(s.attacker);
        final Country defender = this.manager().get(s.defender);
        if (!messageKey.isEmpty() && attacker != null && defender != null) {
            Notify.broadcast(this.messages(), messageKey, Notify.pair(attacker, defender),
                Notify.unparsed("x", s.pos.centerBlockX()), Notify.unparsed("z", s.pos.centerBlockZ()));
        }
        Bukkit.getPluginManager().callEvent(new SiegeEndEvent(s.id, s.attacker, s.defender, s.pos.world(), s.pos.x(), s.pos.z(), conquered));
        this.plugin.mapHook().refreshSieges();
    }

    /** Admin: ends the siege at {@code pos} without conquest. */
    public boolean stopAt(final ChunkPos pos) {
        final Siege s = this.siegeAt(pos);
        if (s == null) {
            return false;
        }
        this.end(s, false, "siege-ended-admin");
        return true;
    }

    /** Ends sieges where the country is attacker or defender (country disbanded). */
    public void endAllFor(final UUID country) {
        for (final Siege s : List.copyOf(this.sieges.values())) {
            if (s.attacker.equals(country) || s.defender.equals(country)) {
                this.end(s, false, "");
            }
        }
    }

    /** Ends sieges between two countries (war ended). */
    public void endBetween(final UUID a, final UUID b) {
        for (final Siege s : List.copyOf(this.sieges.values())) {
            if ((s.attacker.equals(a) && s.defender.equals(b)) || (s.attacker.equals(b) && s.defender.equals(a))) {
                this.end(s, false, "siege-ended-peace");
            }
        }
    }

    /** Plugin disable: ends every siege without conquest (sieges are not persisted). */
    public void shutdown() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        for (final Siege s : List.copyOf(this.sieges.values())) {
            this.end(s, false, "");
        }
    }

    // ------------------------------------------------------------------ building inside a sieged chunk

    /**
     * Whether an attacker (or an ally helping the attacker) may do {@code flag} on {@code block} because the chunk is
     * under siege by their side ({@code siege.build.*}: allowed actions, excluded blocks, containers).
     */
    public boolean allowsAction(final Player player, final Block block, final ProtectionService.Flag flag) {
        return this.allowsCountryAction(this.manager().countryIdOf(player.getUniqueId()),block,flag);
    }

    public boolean allowsCountryAction(final UUID country, final Block block, final ProtectionService.Flag flag) {
        if (country==null) return false;
        if (this.sieges.isEmpty()) {
            return false;
        }
        final Siege s = this.siegeAt(new ChunkPos(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4));
        if (s == null) {
            return false;
        }
        final KrajeConfig cfg = this.cfg();
        if (!cfg.siegeAllowedActions.contains(flag)) {
            return false;
        }
        final Country attacker = this.manager().get(s.attacker);
        final Country defender = this.manager().get(s.defender);
        if (attacker == null || defender == null
            || this.side(country, attacker, defender) <= 0) {
            return false;
        }
        if (this.excluded(cfg).contains(block.getType())) {
            return false;
        }
        return !(cfg.siegeExcludeContainers && block.getState(false) instanceof Container);
    }

    private Set<Material> excluded(final KrajeConfig cfg) {
        if (this.excludedFor == cfg) {
            return this.excluded;
        }
        final Set<Material> set = EnumSet.noneOf(Material.class);
        for (final String raw : cfg.siegeExcludedBlocks) {
            final String entry = raw.trim();
            if (entry.startsWith("#")) {
                final NamespacedKey key = NamespacedKey.fromString(entry.substring(1).toLowerCase(Locale.ROOT));
                final Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag != null) {
                    set.addAll(tag.getValues());
                } else {
                    this.plugin.getLogger().warning("siege.build.excluded-blocks: nieznany tag " + entry);
                }
            } else {
                final Material m = Material.matchMaterial(entry);
                if (m != null) {
                    set.add(m);
                } else {
                    this.plugin.getLogger().warning("siege.build.excluded-blocks: nieznany blok " + entry);
                }
            }
        }
        this.excluded = set;
        this.excludedFor = cfg;
        return set;
    }

    /** A player left: their boss bars are gone client-side, so they must be shown again after a rejoin. */
    public void forgetViewer(final UUID player) {
        for (final Siege s : this.sieges.values()) {
            s.viewers.remove(player);
        }
    }
}
