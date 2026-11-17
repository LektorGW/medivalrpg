// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.RayTraceResult;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.config.UnitType;
import pl.medivalrpg.armia.logic.ArmyLimits;
import pl.medivalrpg.armia.logic.BarracksRules;
import pl.medivalrpg.armia.logic.Payroll;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.model.SoldierMode;
import pl.medivalrpg.armia.model.SoldierRecord;
import pl.medivalrpg.armia.service.Population;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.skarbiec.api.TxResult;

/** Everything players, admins and the AI do with soldiers: recruit, command, pay, dismiss. Main thread. */
public final class ArmyService {

    private final SoldierRegistry registry;
    private final SoldierController controller;
    private final Services services;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;
    private final MaceItem mace;
    private final Map<UUID, List<Pos>> patrolDrafts = new HashMap<>();
    private BiConsumer<Soldier, UUID> deathHook = (s, k) -> { };
    private pl.medivalrpg.armia.naval.FleetService fleet;
    public void fleet(final pl.medivalrpg.armia.naval.FleetService value) {this.fleet=value;}
    public pl.medivalrpg.armia.naval.FleetService fleet() {return this.fleet;}

    public ArmyService(final SoldierRegistry registry, final SoldierController controller, final Services services,
                       final Supplier<ArmiaConfig> config, final Supplier<Messages> messages, final MaceItem mace) {
        this.registry = registry;
        this.controller = controller;
        this.services = services;
        this.config = config;
        this.messages = messages;
        this.mace = mace;
    }

    public void onDeath(final BiConsumer<Soldier, UUID> hook) {
        this.deathHook = hook;
    }

    private Messages m() {
        return this.messages.get();
    }

    private ArmiaConfig cfg() {
        return this.config.get();
    }

    public SoldierRegistry registry() {
        return this.registry;
    }

    public SoldierController controller() {
        return this.controller;
    }

    public MaceItem mace() {
        return this.mace;
    }

    // ------------------------------------------------------------------ limits

    /** Soldier limit of a country: land limit plus the bunks of its working barracks (never above the global cap). */
    public int countryCap(final KrajInfo c) {
        return this.limit(c).total();
    }

    /** Limit from land only ({@code limits.country-*}), as before barracks counted. */
    public int baseCap(final KrajInfo c) {
        final ArmiaConfig cfg = this.cfg();
        return ArmyLimits.countryCap(cfg.countryBase, cfg.countryPerChunk, c.chunkCount(), cfg.countryMax);
    }

    /** Barracks of a country known to Ludnosc (empty without Ludnosc). */
    public List<BarracksRules.Barracks> barracks(final UUID country) {
        final ArmiaConfig cfg = this.cfg();
        return this.services.barracks(country, cfg.barracksBuilding, cfg.barracks);
    }

    /** The limit split into land and bunks; the total never exceeds {@code limits.global-max}. */
    public BarracksRules.Limit limit(final KrajInfo c) {
        final ArmiaConfig cfg = this.cfg();
        return BarracksRules.capped(BarracksRules.limit(this.baseCap(c), cfg.barracks, this.barracks(c.id())), cfg.globalMax);
    }

    /** Message key for "no more soldiers": global cap, country limit with the barracks hint, or the plain old text. */
    public String limitKey(final KrajInfo c) {
        final ArmiaConfig cfg = this.cfg();
        return BarracksRules.limitKey(cfg.barracks, this.limit(c), this.services.population().present(),
            this.registry.size(), cfg.globalMax);
    }

    /** Placeholders of the limit messages and of {@code /armia info}. */
    public TagResolver limitTags(final KrajInfo c) {
        final ArmiaConfig cfg = this.cfg();
        final BarracksRules.Limit l = this.limit(c);
        return TagResolver.resolver(
            Placeholder.unparsed("count", Integer.toString(this.registry.ofCountry(c.id()).size())),
            Placeholder.unparsed("cap", Integer.toString(l.total())),
            Placeholder.unparsed("base", Integer.toString(l.base())),
            Placeholder.unparsed("bonus", Integer.toString(l.bonus())),
            Placeholder.unparsed("beds", Integer.toString(l.beds())),
            Placeholder.unparsed("barracks", Integer.toString(l.working())),
            Placeholder.unparsed("idle", Integer.toString(l.idle())),
            Placeholder.unparsed("perbed", Integer.toString(cfg.barracks.limitPerBed())),
            Placeholder.unparsed("bonusmax", Integer.toString(cfg.barracks.limitBonusMax())),
            Placeholder.unparsed("building", cfg.barracksBuilding),
            Placeholder.unparsed("globalcount", Integer.toString(this.registry.size())),
            Placeholder.unparsed("global", Integer.toString(cfg.globalMax)));
    }

    /**
     * Where recruits muster: the nearest working barracks of the country in the world of {@code near} (empty when the
     * country has none, Ludnosc is missing or {@code barracks.recruit-spawn} is off). No chunk is loaded.
     */
    public Optional<Location> musterPoint(final UUID country, final Location near) {
        if (!this.cfg().barracks.recruitSpawn() || near == null || near.getWorld() == null) {
            return Optional.empty();
        }
        return BarracksRules.nearest(this.barracks(country), near.getWorld().getName(), near.getX(), near.getZ())
            .map(b -> standIn(new Location(near.getWorld(), b.x() + 0.5, b.y(), b.z() + 0.5)));
    }

    /**
     * A place to stand at or just above {@code at}: Ludnosc reports the door spot of a template or the floor centre of
     * a self-built barracks, which may be inside the floor block. Only looks at loaded chunks; otherwise unchanged.
     */
    static Location standIn(final Location at) {
        final World w = at.getWorld();
        if (w == null || !at.isChunkLoaded()) {
            return at;
        }
        final int x = at.getBlockX();
        final int z = at.getBlockZ();
        for (int y = at.getBlockY(); y <= Math.min(w.getMaxHeight() - 2, at.getBlockY() + 6); y++) {
            if (w.getBlockAt(x, y, z).isPassable() && w.getBlockAt(x, y + 1, z).isPassable() && !w.getBlockAt(x, y - 1, z).isPassable()) {
                return new Location(w, at.getX(), y, at.getZ());
            }
        }
        return at;
    }

    public int allowed(final KrajInfo c, final int requested) {
        return ArmyLimits.allowed(requested, this.registry.ofCountry(c.id()).size(), this.countryCap(c), this.registry.size(), this.cfg().globalMax);
    }

    public long dailyWages(final UUID country) {
        long sum = 0;
        for (final Soldier s : this.registry.ofCountry(country)) {
            final UnitType u = this.controller.unitOf(s);
            sum += u == null ? 0 : u.wage();
        }
        return sum;
    }

    /** Military power of a country's soldiers (sum of siege strengths). */
    public double soldierPower(final UUID country) {
        double sum = 0;
        for (final Soldier s : this.registry.ofCountry(country)) {
            final UnitType u = this.controller.unitOf(s);
            sum += u == null ? 1 : Math.max(0.2, u.siegeStrength());
        }
        return sum;
    }

    // ------------------------------------------------------------------ player context

    /** The sender's country and rank check; sends the error and returns empty when not allowed. */
    public Optional<KrajInfo> context(final Player p, final Rank required) {
        final Optional<KrajInfo> c = this.services.kraje().getCountryOf(p.getUniqueId());
        if (c.isEmpty()) {
            this.m().send(p, "no-country");
            return Optional.empty();
        }
        final Rank rank = this.services.kraje().getRank(p.getUniqueId()).orElse(Rank.CITIZEN);
        if (!rank.atLeast(required)) {
            this.m().send(p, "no-rank", Placeholder.unparsed("rank", required.displayName()));
            return Optional.empty();
        }
        return c;
    }

    // ------------------------------------------------------------------ recruitment

    public void recruit(final Player p, final String typeId, final int requested) {
        final ArmiaConfig cfg = this.cfg();
        final Optional<KrajInfo> ctx = this.context(p, cfg.recruitRank);
        if (ctx.isEmpty()) {
            return;
        }
        final KrajInfo c = ctx.get();
        final UnitType u = cfg.unit(typeId);
        if (u == null) {
            this.m().send(p, "unknown-unit", Placeholder.unparsed("type", typeId), Placeholder.unparsed("types", String.join(", ", cfg.units.keySet())));
            return;
        }
        if (!this.available(c.id(), u)) {
            this.m().send(p, "unit-locked", Placeholder.parsed("unit", u.name()), Placeholder.unparsed("reason", this.lockReason(c.id(), u)));
            return;
        }
        if (!this.services.kraje().getCountryAt(p.getLocation()).map(k -> k.id().equals(c.id())).orElse(false)) {
            this.m().send(p, "recruit-own-land");
            return;
        }
        if (cfg.barracksRequired && !this.hasBarracks(c, p.getLocation())) {
            final boolean ludnosc = this.services.population().present();
            this.m().send(p, ludnosc ? "recruit-no-barracks-building" : "recruit-no-barracks",
                Placeholder.unparsed("block", cfg.barracksBlock.getKey().getKey()),
                Placeholder.unparsed("building", cfg.barracksBuilding),
                Placeholder.unparsed("radius", Integer.toString(cfg.barracksRadius)));
            return;
        }
        final int amount = Math.max(1, Math.min(requested, cfg.maxPerCommand));
        int allowed = this.allowed(c, amount);
        if (allowed <= 0) {
            this.m().send(p, this.limitKey(c), this.limitTags(c));
            return;
        }
        final String account = this.services.treasury(c.id());
        final long balance = this.services.skarbiec().balance(account);
        // Barracks: recruits muster at the nearest working one. Close to the recruiter they walk to him; a far
        // barracks keeps them as its garrison until somebody collects them (/armia zamna on the spot).
        final Location muster = this.musterPoint(c.id(), p.getLocation()).orElse(null);
        final boolean musterLoaded = muster != null && muster.isChunkLoaded();
        final boolean garrison = muster != null
            && !BarracksRules.recruitsFollow(cfg.barracks, muster.distanceSquared(p.getLocation()));
        final boolean atBarracks = muster != null && (garrison || musterLoaded);
        if (u.cost() > 0) {
            allowed = (int) Math.min(allowed, balance / u.cost());
        }
        if (allowed <= 0) {
            this.m().send(p, "recruit-no-money", Placeholder.unparsed("cost", this.services.money(u.cost())),
                Placeholder.unparsed("balance", this.services.money(balance)));
            return;
        }
        final Population pop = this.services.population();
        final boolean needCitizen = cfg.requireCitizen && pop.present();
        int done = 0;
        long spent = 0;
        String failure = null;
        for (int i = 0; i < allowed; i++) {
            if (u.cost() > 0) {
                final TxResult tx = this.services.skarbiec().withdraw(account, u.cost(), "Armia: rekrutacja " + u.id() + " (" + p.getName() + ")");
                if (!tx.ok()) {
                    failure = "recruit-no-money";
                    break;
                }
            }
            Location spawn = p.getLocation();
            String profession = "minecraft:none";
            if (needCitizen) {
                final Optional<Population.Conscripted> cs = pop.conscript(c.id(), p.getLocation());
                if (cs.isEmpty()) {
                    if (u.cost() > 0) {
                        this.services.skarbiec().deposit(account, u.cost(), "Armia: zwrot (brak obywatela)");
                    }
                    failure = "recruit-no-citizen";
                    break;
                }
                profession = cs.get().profession();
                final Location at = cs.get().location();
                if (at != null && at.getWorld() == p.getWorld() && at.distanceSquared(p.getLocation()) <= cfg.citizenMaxDistance * cfg.citizenMaxDistance) {
                    spawn = at;
                }
            }
            if (garrison) {
                final Soldier s = this.create(c.id(), u, Pos.of(muster), cfg.guardRadiusDefault, SoldierMode.GUARD, null, profession);
                if (musterLoaded) {
                    this.controller.materialize(s, muster.clone().add((i % 3) - 1, 0, (i / 3 % 3) - 1));
                } else {
                    this.controller.queueSpawn(s);
                }
            } else {
                final Soldier s = this.create(c.id(), u, Pos.of(p.getLocation()), cfg.guardRadiusDefault, SoldierMode.FOLLOW, p.getUniqueId(), profession);
                this.controller.materialize(s, atBarracks ? muster.clone().add((i % 3) - 1, 0, (i / 3 % 3) - 1) : spawn);
            }
            done++;
            spent += u.cost();
        }
        if (done > 0) {
            final TagResolver tags = TagResolver.resolver(
                Placeholder.unparsed("count", Integer.toString(done)), Placeholder.parsed("unit", u.name()),
                Placeholder.unparsed("cost", this.services.money(spent)),
                Placeholder.unparsed("wage", this.services.money(u.wage() * done)),
                Placeholder.unparsed("x", muster == null ? "" : Integer.toString(muster.getBlockX())),
                Placeholder.unparsed("y", muster == null ? "" : Integer.toString(muster.getBlockY())),
                Placeholder.unparsed("z", muster == null ? "" : Integer.toString(muster.getBlockZ())),
                Placeholder.unparsed("distance", muster == null ? "" : Long.toString(Math.round(Math.sqrt(muster.distanceSquared(p.getLocation()))))));
            if (garrison) {
                // one message instead of "recruited" (whose text says the recruits follow the recruiter)
                this.m().send(p, "recruited-at-barracks", tags);
            } else {
                this.m().send(p, "recruited", tags);
                if (atBarracks) {
                    this.m().send(p, "recruited-from-barracks", tags);
                }
            }
        }
        if (failure != null) {
            this.m().send(p, failure, Placeholder.unparsed("cost", this.services.money(u.cost())),
                Placeholder.unparsed("balance", this.services.money(this.services.skarbiec().balance(account))));
        } else if (done < amount) {
            this.m().send(p, "recruit-partial", Placeholder.unparsed("count", Integer.toString(done)), Placeholder.unparsed("requested", Integer.toString(amount)));
        }
    }

    /**
     * Outcome of {@link #recruitAt}.
     *
     * @param done       soldiers recruited
     * @param requested  soldiers requested (after the per-command cap)
     * @param spent      copper taken from the treasury
     * @param failureKey message key of the reason recruitment stopped early (null = none)
     */
    public record RemoteRecruit(int done, int requested, long spent, String failureKey) {
    }

    /**
     * Recruitment without a recruiter on the spot (ArmiaApi / web panel): same limits, cost from the treasury and
     * citizen requirement as {@link #recruit(Player, String, int)}; soldiers guard {@code spawn} (barracks or capital).
     * Barracks / own-land checks are the caller's job. Main thread.
     */
    public RemoteRecruit recruitAt(final KrajInfo c, final UnitType u, final int requested, final Location spawn, final String actor) {
        final ArmiaConfig cfg = this.cfg();
        if (!this.available(c.id(), u)) {
            return new RemoteRecruit(0, requested, 0, "unit-locked");
        }
        final int amount = Math.max(1, Math.min(requested, cfg.maxPerCommand));
        int allowed = this.allowed(c, amount);
        if (allowed <= 0) {
            return new RemoteRecruit(0, amount, 0, "recruit-limit");
        }
        final String account = this.services.treasury(c.id());
        if (u.cost() > 0) {
            allowed = (int) Math.min(allowed, this.services.skarbiec().balance(account) / u.cost());
        }
        if (allowed <= 0) {
            return new RemoteRecruit(0, amount, 0, "recruit-no-money");
        }
        final Population pop = this.services.population();
        final boolean needCitizen = cfg.requireCitizen && pop.present();
        final boolean loaded = spawn.getWorld() != null && spawn.isChunkLoaded();
        int done = 0;
        long spent = 0;
        String failure = null;
        for (int i = 0; i < allowed; i++) {
            if (u.cost() > 0) {
                final TxResult tx = this.services.skarbiec().withdraw(account, u.cost(), "Armia: rekrutacja " + u.id() + " (" + actor + ")");
                if (!tx.ok()) {
                    failure = "recruit-no-money";
                    break;
                }
            }
            String profession = "minecraft:none";
            if (needCitizen) {
                final Optional<Population.Conscripted> cs = pop.conscript(c.id(), loaded ? spawn : null);
                if (cs.isEmpty()) {
                    if (u.cost() > 0) {
                        this.services.skarbiec().deposit(account, u.cost(), "Armia: zwrot (brak obywatela)");
                    }
                    failure = "recruit-no-citizen";
                    break;
                }
                profession = cs.get().profession();
            }
            final Soldier s = this.create(c.id(), u, Pos.of(spawn), cfg.guardRadiusDefault, SoldierMode.GUARD, null, profession);
            if (loaded) {
                this.controller.materialize(s, spawn.clone().add((i % 3) - 1, 0, (i / 3 % 3) - 1));
            } else {
                this.controller.queueSpawn(s);
            }
            done++;
            spent += u.cost();
        }
        if (failure == null && done < amount) {
            failure = done == allowed && allowed < amount ? (this.allowed(c, 1) <= 0 ? "recruit-limit" : "recruit-no-money") : null;
        }
        return new RemoteRecruit(done, amount, spent, failure);
    }

    /** Barracks check for {@link #recruitAt}: Ludnosc building, else the configured block near {@code at} (if given). */
    public boolean hasBarracksAt(final KrajInfo c, final Location at) {
        final ArmiaConfig cfg = this.cfg();
        final Optional<Boolean> known = this.services.population().present()
            ? this.services.population().hasBuilding(c.id(), cfg.barracksBuilding)
            : Optional.empty();
        if (known.isPresent()) {
            return known.get();
        }
        return at != null && at.getWorld() != null && at.isChunkLoaded() && this.nearBlock(at, cfg.barracksBlock, cfg.barracksRadius);
    }

    /**
     * Barracks requirement: Ludnosc knows the country's buildings ({@code hasBuilding}); without Ludnosc (or with an
     * older Ludnosc) a configured block near the recruiter stands in for barracks.
     */
    private boolean hasBarracks(final KrajInfo c, final Location at) {
        final ArmiaConfig cfg = this.cfg();
        final Optional<Boolean> known = this.services.population().present()
            ? this.services.population().hasBuilding(c.id(), cfg.barracksBuilding)
            : Optional.empty();
        return known.orElseGet(() -> this.nearBlock(at, cfg.barracksBlock, cfg.barracksRadius));
    }

    private boolean nearBlock(final Location at, final org.bukkit.Material material, final int r) {
        final World w = at.getWorld();
        final int bx = at.getBlockX();
        final int by = at.getBlockY();
        final int bz = at.getBlockZ();
        for (int x = bx - r; x <= bx + r; x++) {
            for (int z = bz - r; z <= bz + r; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = Math.max(w.getMinHeight(), by - 6); y <= Math.min(w.getMaxHeight() - 1, by + 6); y++) {
                    if (w.getBlockAt(x, y, z).getType() == material) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** New soldier in the registry (not spawned yet). */
    public Soldier create(final UUID country, final UnitType u, final Pos post, final double radius, final SoldierMode mode,
                          final UUID commander, final String profession) {
        final SoldierRecord rec = new SoldierRecord(country, u.id(), post, radius);
        rec.mode = mode;
        rec.commander = commander;
        rec.profession = profession == null ? "minecraft:none" : profession;
        rec.health = u.brainHealth();
        if(pl.medivalrpg.armia.logic.RoyalGarrisonRules.COUNTRY.equals(country)
            && this.registry.ofCountry(country).stream().anyMatch(s->s.record().royalEvacuated)) rec.royalEvacuated=true;
        return this.registry.add(rec);
    }

    /** Admin / AI: adds {@code n} soldiers guarding {@code at} (free, no citizen). Returns how many were created. */
    public int addSoldiers(final UUID country, final UnitType u, final int n, final Pos at, final double radius, final boolean respectLimits) {
        final KrajInfo c = this.services.kraje().getCountry(country).orElse(null);
        if (c == null) {
            return 0;
        }
        final int count = respectLimits ? this.allowed(c, n) : ArmyLimits.adminAllowed(n, this.registry.size(), this.cfg().globalMax);
        for (int i = 0; i < count; i++) {
            final Soldier s = this.create(country, u, at, radius, SoldierMode.GUARD, null, "minecraft:none");
            if (at.isChunkLoaded()) {
                final Location l = at.toLocation();
                if (l != null) {
                    this.controller.materialize(s, l.clone().add((i % 3) - 1, 0, (i / 3 % 3) - 1));
                    continue;
                }
            }
            this.controller.queueSpawn(s);
        }
        return count;
    }

    // ------------------------------------------------------------------ squads

    /** Soldiers commanded by the player (following or holding a position he gave). */
    public List<Soldier> squad(final Player p) {
        final List<Soldier> out = new ArrayList<>();
        final Optional<KrajInfo> c = this.services.kraje().getCountryOf(p.getUniqueId());
        if (c.isEmpty()) {
            return out;
        }
        for (final Soldier s : this.registry.ofCountry(c.get().id())) {
            if (p.getUniqueId().equals(s.rec.commander) && (s.rec.mode == SoldierMode.FOLLOW || s.rec.mode == SoldierMode.HOLD)) {
                out.add(s);
            }
        }
        return out;
    }

    public void gather(final Player p) {
        final Optional<KrajInfo> ctx = this.context(p, this.cfg().commandRank);
        if (ctx.isEmpty()) {
            return;
        }
        final double r = this.cfg().squadGatherRadius;
        int n = 0;
        for (final Soldier s : this.registry.ofCountry(ctx.get().id())) {
            final boolean mine = p.getUniqueId().equals(s.rec.commander);
            boolean near = false;
            if (s.attached() && s.brain.getWorld() == p.getWorld()) {
                near = s.brain.getLocation().distanceSquared(p.getLocation()) <= r * r;
            }
            final Player other = s.rec.commander == null ? null : Bukkit.getPlayer(s.rec.commander);
            final boolean takenByOther = !mine && other != null && other.isOnline() && s.rec.mode == SoldierMode.FOLLOW;
            if ((mine || near) && !takenByOther) {
                s.rec.mode = SoldierMode.FOLLOW;
                s.rec.commander = p.getUniqueId();
                s.rec.siege = false;
                s.clearTarget();
                n++;
            }
        }
        this.registry.markDirty();
        this.m().send(p, n > 0 ? "squad-follow" : "squad-none-near", Placeholder.unparsed("count", Integer.toString(n)),
            Placeholder.unparsed("radius", Integer.toString((int) r)));
    }

    public void hold(final Player p) {
        if (this.context(p, this.cfg().commandRank).isEmpty()) {
            return;
        }
        final List<Soldier> squad = this.squad(p);
        for (final Soldier s : squad) {
            s.rec.mode = SoldierMode.HOLD;
            s.rec.hold = s.attached() ? Pos.of(s.brain.getLocation()) : s.rec.spawnPos();
            s.clearTarget();
        }
        this.registry.markDirty();
        this.reply(p, squad.size(), "squad-hold");
    }

    public void moveSquad(final Player p, final Location where) {
        if (this.context(p, this.cfg().commandRank).isEmpty()) {
            return;
        }
        final List<Soldier> squad = this.squad(p);
        for (final Soldier s : squad) {
            s.rec.mode = SoldierMode.HOLD;
            s.rec.hold = Pos.of(where);
            s.clearTarget();
            s.nextRepathTick = 0;
        }
        this.registry.markDirty();
        this.reply(p, squad.size(), "squad-move", Placeholder.unparsed("pos", where.getBlockX() + " " + where.getBlockY() + " " + where.getBlockZ()));
    }

    public void toggle(final Player p) {
        final List<Soldier> squad = this.squad(p);
        final boolean allFollow = !squad.isEmpty() && squad.stream().allMatch(s -> s.rec.mode == SoldierMode.FOLLOW);
        if (allFollow) {
            this.hold(p);
        } else {
            this.gather(p);
        }
    }

    public void returnToPost(final Player p) {
        if (this.context(p, this.cfg().commandRank).isEmpty()) {
            return;
        }
        final List<Soldier> squad = this.squad(p);
        for (final Soldier s : squad) {
            s.rec.mode = s.rec.patrol.size() >= 2 ? SoldierMode.PATROL : SoldierMode.GUARD;
            if (s.rec.post == null) {
                s.rec.post = s.rec.spawnPos();
            }
            s.rec.commander = null;
            s.clearTarget();
        }
        this.registry.markDirty();
        this.reply(p, squad.size(), "squad-return");
    }

    public void guardHere(final Player p, final double radius) {
        if (this.context(p, this.cfg().commandRank).isEmpty()) {
            return;
        }
        final double r = Math.max(1, Math.min(radius, this.cfg().guardRadiusMax));
        final List<Soldier> squad = this.squad(p);
        final Pos post = Pos.of(p.getLocation());
        for (final Soldier s : squad) {
            s.rec.mode = SoldierMode.GUARD;
            s.rec.post = post;
            s.rec.radius = r;
            s.rec.patrol.clear();
            s.rec.commander = null;
            s.clearTarget();
        }
        this.registry.markDirty();
        this.reply(p, squad.size(), "squad-guard", Placeholder.unparsed("radius", Integer.toString((int) r)));
    }

    public void patrolAdd(final Player p) {
        if (this.context(p, this.cfg().commandRank).isEmpty()) {
            return;
        }
        final List<Pos> draft = this.patrolDrafts.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        if (!draft.isEmpty() && !draft.get(0).world().equals(p.getWorld().getName())) {
            draft.clear();
        }
        if (draft.size() >= this.cfg().patrolMaxPoints) {
            this.m().send(p, "patrol-full", Placeholder.unparsed("max", Integer.toString(this.cfg().patrolMaxPoints)));
            return;
        }
        draft.add(Pos.of(p.getLocation()));
        this.m().send(p, "patrol-added", Placeholder.unparsed("count", Integer.toString(draft.size())));
    }

    public void patrolClear(final Player p) {
        this.patrolDrafts.remove(p.getUniqueId());
        this.m().send(p, "patrol-cleared");
    }

    public void patrolStart(final Player p) {
        if (this.context(p, this.cfg().commandRank).isEmpty()) {
            return;
        }
        final List<Pos> draft = this.patrolDrafts.getOrDefault(p.getUniqueId(), List.of());
        if (draft.size() < 2) {
            this.m().send(p, "patrol-too-short");
            return;
        }
        final List<Soldier> squad = this.squad(p);
        for (final Soldier s : squad) {
            s.rec.mode = SoldierMode.PATROL;
            s.rec.patrol = new ArrayList<>(draft);
            s.rec.patrolIndex = 0;
            s.rec.post = draft.get(0);
            s.rec.commander = null;
            s.clearTarget();
        }
        this.registry.markDirty();
        this.reply(p, squad.size(), "patrol-started", Placeholder.unparsed("points", Integer.toString(draft.size())));
    }

    /** {@code /armia atakuj}: the entity the player looks at. */
    public void bombardLook(final Player p) {
        if (this.context(p,this.cfg().commandRank).isEmpty()) return;
        final var hit=p.rayTraceBlocks(64);
        if (hit==null || hit.getHitBlock()==null) {this.m().send(p,"siege-no-block");return;}
        final Location target=hit.getHitBlock().getLocation().add(0.5,0.5,0.5);
        final List<Soldier> engines=this.squad(p).stream().filter(Soldier::attached)
            .filter(s->this.controller.siegeEngines().isShooter(s)).toList();
        if (engines.isEmpty()) {
            final boolean rams=this.squad(p).stream().anyMatch(s->this.controller.siegeEngines().isRam(s));
            this.m().send(p,rams?"siege-ram-use":"siege-no-engines");return;
        }
        if (!this.controller.siegeEngines().mayBombard(engines.get(0).country(),target)) {
            this.m().send(p,"siege-forbidden");return;
        }
        int count=0;
        for (final Soldier soldier:engines) {
            final UnitType unit=this.controller.unitOf(soldier);
            if (unit!=null && this.controller.siegeEngines().fire(soldier,unit,target,null,unit.damage())) count++;
        }
        this.m().send(p,count==0?"siege-not-ready":"siege-fired",Placeholder.unparsed("count",Integer.toString(count)));
    }

    /** {@code /armia taranuj}: rams of the squad drive to the gate or wall the player looks at and strike it. */
    public void ramLook(final Player p) {
        if (this.context(p,this.cfg().commandRank).isEmpty()) return;
        final var hit=p.rayTraceBlocks(64);
        if (hit==null || hit.getHitBlock()==null) {this.m().send(p,"siege-no-block");return;}
        final List<Soldier> rams=this.squad(p).stream().filter(Soldier::attached)
            .filter(s->this.controller.siegeEngines().isRam(s)).toList();
        if (rams.isEmpty()) {this.m().send(p,"siege-ram-none");return;}
        if (!this.controller.siegeEngines().mayBombard(rams.get(0).country(),hit.getHitBlock().getLocation().add(0.5,0.5,0.5))) {
            this.m().send(p,"siege-forbidden");return;
        }
        int count=0;
        boolean high=false;
        for (final Soldier ram:rams) {
            final var result=this.controller.siegeEngines().orderRam(ram,hit.getHitBlock());
            if (result==pl.medivalrpg.armia.soldier.SiegeEngineService.RamOrderResult.OK) count++;
            else if (result==pl.medivalrpg.armia.soldier.SiegeEngineService.RamOrderResult.TOO_HIGH) high=true;
        }
        if (count==0) {this.m().send(p,high?"siege-ram-height":"siege-forbidden");return;}
        final var ram=this.cfg().ram;
        this.m().send(p,"siege-ram-ordered",Placeholder.unparsed("count",Integer.toString(count)),
            Placeholder.unparsed("wood",Integer.toString(ram.strikes(pl.medivalrpg.armia.logic.SiegeRules.RamClass.WOOD))),
            Placeholder.unparsed("stone",Integer.toString(ram.strikes(pl.medivalrpg.armia.logic.SiegeRules.RamClass.STONE))),
            Placeholder.unparsed("fort",Integer.toString(ram.strikes(pl.medivalrpg.armia.logic.SiegeRules.RamClass.FORTIFICATION))));
    }

    public void ammunition(final Player p, final String id) {
        if (this.context(p,this.cfg().commandRank).isEmpty()) return;
        final var ammo=pl.medivalrpg.armia.logic.SiegeRules.parseAmmo(id);
        if (ammo==null) {this.m().send(p,"siege-ammo-unknown");return;}
        int changed=0,skipped=0;
        for (final Soldier soldier:this.squad(p)) {
            if (!this.controller.siegeEngines().isEngine(soldier)) continue;
            if (!pl.medivalrpg.armia.logic.SiegeRules.accepts(soldier.record().type,ammo)) {skipped++;continue;}
            soldier.record().siegeAmmo=ammo.id(); changed++;
        }
        if (changed==0 && skipped==0) {this.m().send(p,"siege-no-engines");return;}
        if (changed>0) this.registry.markDirty();
        this.m().send(p,"siege-ammo-set",Placeholder.unparsed("count",Integer.toString(changed)),
            Placeholder.unparsed("ammo",id),Placeholder.unparsed("skipped",Integer.toString(skipped)));
    }

    public void attackLook(final Player p) {
        if (this.context(p, this.cfg().commandRank).isEmpty()) {
            return;
        }
        final LivingEntity target = this.lookedAt(p, this.cfg().maceRange);
        if (target == null) {
            this.m().send(p, "attack-no-target");
            return;
        }
        this.attack(p, target);
    }

    public LivingEntity lookedAt(final Player p, final double range) {
        final UUID own = this.services.kraje().getCountryOf(p.getUniqueId()).map(KrajInfo::id).orElse(null);
        final RayTraceResult hit = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, 0.4,
            e -> e instanceof LivingEntity && e != p && !this.isOwnSoldier(e, own));
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity le)) {
            return null;
        }
        // Do not shoot through walls.
        final RayTraceResult block = p.rayTraceBlocks(range);
        if (block != null && block.getHitPosition().distanceSquared(p.getEyeLocation().toVector())
            < hit.getHitPosition().distanceSquared(p.getEyeLocation().toVector())) {
            return null;
        }
        return le;
    }

    private boolean isOwnSoldier(final Entity e, final UUID own) {
        return own != null && this.controller.soldierOf(e).map(s -> s.country().equals(own)).orElse(false);
    }

    public void attack(final Player p, final LivingEntity entity) {
        if (this.context(p, this.cfg().commandRank).isEmpty()) {
            return;
        }
        final List<Soldier> squad = this.squad(p).stream().filter(Soldier::attached).toList();
        if (squad.isEmpty()) {
            this.m().send(p, "squad-empty");
            return;
        }
        final LivingEntity target = this.controller.resolveTarget(entity);
        if (!this.controller.orderable(squad.get(0), target)) {
            this.m().send(p, "attack-forbidden");
            return;
        }
        for (final Soldier s : squad) {
            this.controller.orderAttack(s, target);
        }
        final Component name = target instanceof Player pl ? Component.text(pl.getName())
            : target.customName() != null ? target.customName() : Component.translatable(target.getType().translationKey());
        this.m().send(p, "attack-ordered", Placeholder.unparsed("count", Integer.toString(squad.size())), Placeholder.component("target", name));
    }

    private void reply(final Player p, final int n, final String key, final TagResolver... extra) {
        if (n == 0) {
            this.m().send(p, "squad-empty");
            return;
        }
        final List<TagResolver> all = new ArrayList<>(List.of(extra));
        all.add(Placeholder.unparsed("count", Integer.toString(n)));
        this.m().send(p, key, all.toArray(TagResolver[]::new));
    }

    // ------------------------------------------------------------------ dismissal / desertion / death

    public void dismissSquad(final Player p) {
        if (this.context(p, this.cfg().commandRank).isEmpty()) {
            return;
        }
        final List<Soldier> squad = this.squad(p);
        int back = 0;
        for (final Soldier s : squad) {
            if (this.dismiss(s, true)) {
                back++;
            }
        }
        this.reply(p, squad.size(), "dismissed", Placeholder.unparsed("back", Integer.toString(back)));
    }

    /** Removes a soldier; with {@code toPopulation} a villager is left in its place (Ludnosc adopts it). True if so. */
    public boolean dismiss(final Soldier s, final boolean toPopulation) {
        boolean returned = false;
        if (toPopulation && !isMercenary(s) && s.attached()) { // mercenaries never become citizens
            final Location at = s.brain.getLocation();
            final String profession = s.rec.profession == null ? "minecraft:none" : s.rec.profession;
            final Population pop = this.services.population();
            if (pop.present() && pop.returnCitizen(s.country(), at, profession)) {
                returned = true;
            } else if (this.services.kraje().getCountryAt(at).map(k -> k.id().equals(s.country())).orElse(false)) {
                // Fallback: a plain villager on the country's land (Ludnosc, if present, adopts it).
                at.getWorld().spawn(at, Villager.class, v -> {
                    final NamespacedKey key = NamespacedKey.fromString(s.rec.profession == null ? "minecraft:none" : s.rec.profession);
                    final Villager.Profession prof = key == null ? null : Registry.VILLAGER_PROFESSION.get(key);
                    if (prof != null) {
                        v.setProfession(prof);
                    }
                });
                returned = true;
            }
        }
        this.controller.destroy(s);
        return returned;
    }

    public void handleDeath(final Soldier s, final Entity killer) {
        UUID killerCountry = null;
        Entity src = killer;
        if (src instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity sh) {
            src = sh;
        }
        if (src instanceof Player pl) {
            killerCountry = this.services.kraje().getCountryOf(pl.getUniqueId()).map(KrajInfo::id).orElse(null);
        } else if (src != null) {
            killerCountry = this.controller.soldierOf(src).map(Soldier::country).orElse(null);
        }
        this.controller.destroy(s);
        this.deathHook.accept(s, killerCountry);
    }

    public void removeCountry(final UUID country) {
        for (final Soldier s : this.registry.ofCountry(country)) {
            this.controller.destroy(s);
        }
    }

    // ------------------------------------------------------------------ upkeep

    /** Pays one day of wages for one country; deserters leave. */
    public Payroll.Result payCountry(final UUID country) {
        final List<Soldier> soldiers = this.registry.ofCountry(country);
        if (soldiers.isEmpty()) {
            return new Payroll.Result(0, List.of(), Map.of(), List.of());
        }
        final List<Payroll.Soldier> list = new ArrayList<>();
        final Map<UUID, Soldier> byId = new HashMap<>();
        for (final Soldier s : soldiers) {
            final UnitType u = this.controller.unitOf(s);
            list.add(new Payroll.Soldier(s.id(), u == null ? 0 : u.wage(), s.rec.morale));
            byId.put(s.id(), s);
        }
        final String account = this.services.treasury(country);
        Payroll.Result r = Payroll.pay(list, this.services.skarbiec().balance(account), this.cfg().payroll);
        if (r.paid() > 0 && !this.services.skarbiec().withdraw(account, r.paid(), "Armia: żołd " + r.paidIds().size() + " żołnierzy").ok()) {
            r = Payroll.pay(list, 0, this.cfg().payroll);
        }
        for (final Map.Entry<UUID, Integer> e : r.morale().entrySet()) {
            final Soldier s = byId.get(e.getKey());
            if (s != null) {
                s.rec.morale = e.getValue();
            }
        }
        this.unpaidMercenaries(country, r, byId);
        for (final UUID id : r.deserters()) {
            final Soldier s = byId.get(id);
            if (s != null && this.registry.get(id) != null) {
                this.dismiss(s, this.cfg().deserterToPopulation);
            }
        }
        this.registry.markDirty();
        final TagResolver[] tags = {
            Placeholder.unparsed("paid", this.services.money(r.paid())),
            Placeholder.unparsed("count", Integer.toString(r.paidIds().size())),
            Placeholder.unparsed("unpaid", Integer.toString(r.unpaid())),
            Placeholder.unparsed("deserters", Integer.toString(r.deserters().size()))};
        if (r.unpaid() > 0) {
            this.notifyCountry(country, r.deserters().isEmpty() ? "upkeep-unpaid" : "upkeep-desertion", tags);
        } else {
            this.notifyCountry(country, "upkeep-paid", tags);
        }
        return r;
    }

    public void notifyCountry(final UUID country, final String key, final TagResolver... tags) {
        this.services.kraje().getCountry(country).ifPresent(c -> {
            for (final UUID member : c.members().keySet()) {
                final Player pl = Bukkit.getPlayer(member);
                if (pl != null) {
                    this.m().send(pl, key, tags);
                }
            }
        });
    }

    // ------------------------------------------------------------------ info

    // ------------------------------------------------------------------ eras (Postep)

    private pl.medivalrpg.armia.service.Progress progress() {
        return this.services.progress();
    }

    public int era(final UUID country) {
        final pl.medivalrpg.armia.service.Progress p = this.progress();
        return p == null ? 1 : p.era(country);
    }

    private java.util.function.Predicate<String> discoveries(final UUID country) {
        final pl.medivalrpg.armia.service.Progress p = this.progress();
        return d -> p != null && p.has(country, d);
    }

    /** May the country recruit this unit type in its era (Postep era and discovery)? */
    public boolean available(final UUID country, final UnitType u) {
        return pl.medivalrpg.armia.logic.Eras.available(this.cfg().unitEra(u.id()), this.era(country), this.discoveries(country));
    }

    /** Polish reason a unit is locked ("epoka: Renesans", "odkrycie: Proch"). */
    public String lockReason(final UUID country, final UnitType u) {
        final pl.medivalrpg.armia.logic.Eras.UnitEra e = this.cfg().unitEra(u.id());
        final pl.medivalrpg.armia.service.Progress p = this.progress();
        if (this.era(country) < e.era()) {
            return "epoka: " + (p == null ? Integer.toString(e.era()) : p.eraName(e.era()));
        }
        return "odkrycie: " + (p == null ? e.discovery() : p.discoveryName(e.discovery()));
    }

    /** Best available replacement of an old unit type for this country. */
    public Optional<UnitType> upgradeOf(final UUID country, final String type) {
        return pl.medivalrpg.armia.logic.Eras.upgrade(type, this.cfg().unitEras, this.era(country), this.discoveries(country)).map(this.cfg()::unit);
    }

    /** Regular units the country should recruit now (available and not replaced). */
    public List<String> currentUnits(final UUID country) {
        return pl.medivalrpg.armia.logic.Eras.current(this.cfg().unitEras, this.era(country), this.discoveries(country));
    }

    public double hpMultiplier(final UUID country) {
        final pl.medivalrpg.armia.service.Progress p = this.progress();
        return pl.medivalrpg.armia.logic.Eras.power(this.cfg().eraRules.hpBonusPerEra(), this.era(country), p == null ? 0 : p.effect(country, "sila-armii"))
            * pl.medivalrpg.armia.logic.SettlementBonus.multiplier(this.services.population().settlementLevel(country));
    }

    public double damageMultiplier(final UUID country) {
        final pl.medivalrpg.armia.service.Progress p = this.progress();
        return pl.medivalrpg.armia.logic.Eras.power(this.cfg().eraRules.damageBonusPerEra(), this.era(country), p == null ? 0 : p.effect(country, "sila-armii"))
            * pl.medivalrpg.armia.logic.SettlementBonus.multiplier(this.services.population().settlementLevel(country));
    }

    /**
     * Rearms up to {@code max} soldiers of the country (optionally only of {@code type}) to the newest available unit,
     * paying {@code eras.rearm-cost-share} of the new unit's cost each from the treasury (at most {@code budget}).
     * Mercenaries and soldiers in a siege are skipped. Main thread.
     *
     * @return {done, spent}
     */
    public long[] rearm(final UUID country, final String type, final int max, final long budget) {
        final String account = this.services.treasury(country);
        int done = 0;
        long spent = 0;
        for (final Soldier s : new ArrayList<>(this.registry.ofCountry(country))) {
            if (done >= max || isMercenary(s) || s.rec.siege || (type != null && !type.equalsIgnoreCase(s.rec.type))) {
                continue;
            }
            final Optional<UnitType> up = this.upgradeOf(country, s.rec.type);
            if (up.isEmpty()) {
                continue;
            }
            final long cost = pl.medivalrpg.armia.logic.Eras.rearmCost(this.cfg().eraRules, up.get().cost());
            if (spent + cost > budget || (cost > 0 && !this.services.skarbiec().withdraw(account, cost, "Armia: przezbrojenie " + s.rec.type + " → " + up.get().id()).ok())) {
                break;
            }
            this.controller.retype(s, up.get());
            done++;
            spent += cost;
        }
        return new long[] {done, spent};
    }

    /** {@code /armia przezbroj [typ] [ilość]}. */
    public void rearm(final Player p, final String type, final int count) {
        final Optional<KrajInfo> ctx = this.context(p, this.cfg().recruitRank);
        if (ctx.isEmpty()) {
            return;
        }
        final UUID country = ctx.get().id();
        int possible = 0;
        for (final Soldier s : this.registry.ofCountry(country)) {
            if (!isMercenary(s) && (type == null || type.equalsIgnoreCase(s.rec.type)) && this.upgradeOf(country, s.rec.type).isPresent()) {
                possible++;
            }
        }
        if (possible == 0) {
            this.m().send(p, "rearm-none");
            return;
        }
        final long[] r = this.rearm(country, type, Math.max(1, Math.min(count, this.cfg().maxPerCommand)),
            this.services.skarbiec().balance(this.services.treasury(country)));
        this.m().send(p, r[0] > 0 ? "rearmed" : "recruit-no-money", Placeholder.unparsed("count", Long.toString(r[0])),
            Placeholder.unparsed("cost", this.services.money(r[1])), Placeholder.unparsed("left", Long.toString(possible - r[0])),
            Placeholder.unparsed("balance", this.services.money(this.services.skarbiec().balance(this.services.treasury(country)))));
    }

    /** Every 2 s: medics heal own soldiers within 8 blocks. */
    public void startMedics(final org.bukkit.plugin.Plugin plugin) {
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, this::healTick, 40L, 40L);
    }

    private void healTick() {
        for (final Soldier medic : this.registry.all()) {
            if (!medic.attached()) {
                continue;
            }
            final double heal = this.cfg().unitEra(medic.rec.type).healPerSecond() * 2;
            if (heal <= 0) {
                continue;
            }
            final Location at = medic.brain.getLocation();
            for (final Soldier s : this.registry.ofCountry(medic.country())) {
                if (s == medic || !s.attached() || s.brain.getWorld() != at.getWorld() || s.brain.getLocation().distanceSquared(at) > 64) {
                    continue;
                }
                final double max = SoldierController.maxHealth(s.brain);
                if (s.brain.getHealth() < max) {
                    s.brain.setHealth(Math.min(max, s.brain.getHealth() + heal));
                    s.rec.health = s.brain.getHealth();
                }
            }
        }
    }

    // ------------------------------------------------------------------ mercenaries

    public static boolean isMercenary(final Soldier s) {
        return s.rec.contractUntil > 0;
    }

    public List<Soldier> mercenaries(final UUID country) {
        final List<Soldier> out = new ArrayList<>();
        for (final Soldier s : this.registry.ofCountry(country)) {
            if (isMercenary(s)) {
                out.add(s);
            }
        }
        return out;
    }

    public int mercenaryCap(final KrajInfo c) {
        return pl.medivalrpg.armia.logic.Mercenaries.maxMercenaries(this.cfg().mercRules, this.countryCap(c));
    }

    public int allowedHire(final KrajInfo c, final int requested) {
        return pl.medivalrpg.armia.logic.Mercenaries.allowedHire(this.cfg().mercRules, requested, this.registry.ofCountry(c.id()).size(),
            this.mercenaries(c.id()).size(), this.countryCap(c), this.registry.size(), this.cfg().globalMax);
    }

    /**
     * Hires mercenaries paid from the treasury (no citizen, no barracks), on a contract of {@code mercenaries.contract-hours}.
     * Returns how many, the money spent and the reason it stopped early (message key, null = none). Main thread.
     */
    public RemoteRecruit hireAt(final KrajInfo c, final UnitType u, final int requested, final Location spawn, final SoldierMode mode,
                                final UUID commander, final String actor) {
        final ArmiaConfig cfg = this.cfg();
        final int amount = Math.max(1, Math.min(requested, cfg.maxPerCommand));
        int allowed = this.allowedHire(c, amount);
        if (allowed <= 0) {
            return new RemoteRecruit(0, amount, 0, this.allowed(c, 1) <= 0 ? "recruit-limit" : "mercenary-limit");
        }
        final String account = this.services.treasury(c.id());
        if (u.cost() > 0) {
            allowed = (int) Math.min(allowed, this.services.skarbiec().balance(account) / u.cost());
        }
        if (allowed <= 0) {
            return new RemoteRecruit(0, amount, 0, "recruit-no-money");
        }
        final long until = pl.medivalrpg.armia.logic.Mercenaries.contractEnd(cfg.mercRules, System.currentTimeMillis());
        final boolean loaded = spawn.getWorld() != null && spawn.isChunkLoaded();
        int done = 0;
        long spent = 0;
        for (int i = 0; i < allowed; i++) {
            if (u.cost() > 0 && !this.services.skarbiec().withdraw(account, u.cost(), "Armia: najemnik " + u.id() + " (" + actor + ")").ok()) {
                break;
            }
            final Soldier s = this.create(c.id(), u, Pos.of(spawn), cfg.guardRadiusDefault, mode, commander, "minecraft:none");
            s.rec.contractUntil = until;
            if (loaded) {
                this.controller.materialize(s, spawn.clone().add((i % 3) - 1, 0, (i / 3 % 3) - 1));
            } else {
                this.controller.queueSpawn(s);
            }
            done++;
            spent += u.cost();
        }
        this.registry.markDirty();
        return new RemoteRecruit(done, amount, spent, done < amount ? (this.allowedHire(c, 1) <= 0 ? "mercenary-limit" : "recruit-no-money") : null);
    }

    /** {@code /armia najmij <typ> [ilość]}. */
    public void hire(final Player p, final String typeId, final int requested) {
        final ArmiaConfig cfg = this.cfg();
        if (!cfg.mercenariesEnabled || cfg.mercenaries.isEmpty()) {
            this.m().send(p, "mercenary-disabled");
            return;
        }
        final Optional<KrajInfo> ctx = this.context(p, cfg.recruitRank);
        if (ctx.isEmpty()) {
            return;
        }
        final KrajInfo c = ctx.get();
        final UnitType u = cfg.mercenary(typeId);
        if (u == null) {
            this.m().send(p, "unknown-unit", Placeholder.unparsed("type", typeId), Placeholder.unparsed("types", String.join(", ", cfg.mercenaries.keySet())));
            return;
        }
        if (!this.services.kraje().getCountryAt(p.getLocation()).map(k -> k.id().equals(c.id())).orElse(false)) {
            this.m().send(p, "recruit-own-land");
            return;
        }
        final RemoteRecruit r = this.hireAt(c, u, requested, p.getLocation(), SoldierMode.FOLLOW, p.getUniqueId(), p.getName());
        final String balance = this.services.money(this.services.skarbiec().balance(this.services.treasury(c.id())));
        if (r.done() > 0) {
            this.m().send(p, "mercenary-hired", Placeholder.unparsed("count", Integer.toString(r.done())), Placeholder.parsed("unit", u.name()),
                Placeholder.unparsed("cost", this.services.money(r.spent())), Placeholder.unparsed("wage", this.services.money(u.wage() * r.done())),
                Placeholder.unparsed("hours", Long.toString(cfg.mercRules.contractHours())));
        }
        if (r.failureKey() != null) {
            this.m().send(p, "recruit-limit".equals(r.failureKey()) ? this.limitKey(c) : r.failureKey(),
                Placeholder.unparsed("cost", this.services.money(u.cost())), Placeholder.unparsed("balance", balance),
                Placeholder.unparsed("max", Integer.toString(this.mercenaryCap(c))), this.limitTags(c));
        }
    }

    private static String left(final long until) {
        final long min = Math.max(0, (until - System.currentTimeMillis()) / 60_000L);
        return min >= 120 ? (min / 60) + " h" : min + " min";
    }

    /** {@code /armia najemnicy}: contracts and time left. */
    public void listMercenaries(final Player p) {
        final Optional<KrajInfo> ctx = this.context(p, Rank.CITIZEN);
        if (ctx.isEmpty()) {
            return;
        }
        final List<Soldier> list = this.mercenaries(ctx.get().id());
        list.sort(java.util.Comparator.comparingLong(s -> s.rec.contractUntil));
        this.m().send(p, "mercenary-list-header", Placeholder.unparsed("count", Integer.toString(list.size())),
            Placeholder.unparsed("max", Integer.toString(this.mercenaryCap(ctx.get()))));
        for (final Soldier s : list) {
            final UnitType u = this.controller.unitOf(s);
            this.m().send(p, "mercenary-list-line", Placeholder.unparsed("id", s.id().toString().substring(0, 8)),
                Placeholder.parsed("unit", u == null ? s.rec.type : u.name()), Placeholder.unparsed("left", left(s.rec.contractUntil)),
                Placeholder.unparsed("wage", this.services.money(u == null ? 0 : u.wage())),
                Placeholder.unparsed("extend", this.services.money(u == null ? 0 : pl.medivalrpg.armia.logic.Mercenaries.extendCost(this.cfg().mercRules, u.cost()))),
                Messages.command("/armia przedluz " + s.id().toString().substring(0, 8)));
        }
    }

    /** {@code /armia przedluz [id|wszystkich]}: extends contracts, paying {@code extend-cost-share} of the hire cost each. */
    public void extendMercenaries(final Player p, final String which) {
        final Optional<KrajInfo> ctx = this.context(p, this.cfg().recruitRank);
        if (ctx.isEmpty()) {
            return;
        }
        final List<Soldier> targets = new ArrayList<>();
        for (final Soldier s : this.mercenaries(ctx.get().id())) {
            if (which == null || which.isBlank() || which.equalsIgnoreCase("wszystkich") || s.id().toString().startsWith(which.toLowerCase(Locale.ROOT))) {
                targets.add(s);
            }
        }
        if (targets.isEmpty()) {
            this.m().send(p, "mercenary-none");
            return;
        }
        final String account = this.services.treasury(ctx.get().id());
        final long now = System.currentTimeMillis();
        int done = 0;
        long spent = 0;
        for (final Soldier s : targets) {
            final UnitType u = this.controller.unitOf(s);
            final long cost = u == null ? 0 : pl.medivalrpg.armia.logic.Mercenaries.extendCost(this.cfg().mercRules, u.cost());
            if (cost > 0 && !this.services.skarbiec().withdraw(account, cost, "Armia: przedłużenie kontraktu " + s.rec.type).ok()) {
                break;
            }
            s.rec.contractUntil = pl.medivalrpg.armia.logic.Mercenaries.extended(this.cfg().mercRules, s.rec.contractUntil, now);
            done++;
            spent += cost;
        }
        this.registry.markDirty();
        this.m().send(p, done > 0 ? "mercenary-extended" : "recruit-no-money", Placeholder.unparsed("count", Integer.toString(done)),
            Placeholder.unparsed("cost", this.services.money(spent)), Placeholder.unparsed("hours", Long.toString(this.cfg().mercRules.contractHours())),
            Placeholder.unparsed("balance", this.services.money(this.services.skarbiec().balance(account))));
    }

    /** {@code /armia zwolnij-najemnikow}: every mercenary leaves (no refund, never into the population). */
    public void dismissMercenaries(final Player p) {
        final Optional<KrajInfo> ctx = this.context(p, this.cfg().recruitRank);
        if (ctx.isEmpty()) {
            return;
        }
        final List<Soldier> list = this.mercenaries(ctx.get().id());
        for (final Soldier s : list) {
            this.dismiss(s, false);
        }
        this.registry.markDirty();
        this.m().send(p, list.isEmpty() ? "mercenary-none" : "mercenary-dismissed", Placeholder.unparsed("count", Integer.toString(list.size())));
    }

    /** Every minute: expired contracts end (the mercenaries leave). Returns how many left. */
    public int expireContracts() {
        final long now = System.currentTimeMillis();
        final Map<UUID, Integer> gone = new HashMap<>();
        for (final Soldier s : new ArrayList<>(this.registry.all())) {
            if (isMercenary(s) && pl.medivalrpg.armia.logic.Mercenaries.expired(s.rec.contractUntil, now)) {
                gone.merge(s.country(), 1, Integer::sum);
                this.dismiss(s, false);
            }
        }
        for (final Map.Entry<UUID, Integer> e : gone.entrySet()) {
            this.notifyCountry(e.getKey(), "mercenary-contract-ended", Placeholder.unparsed("count", Integer.toString(e.getValue())));
        }
        if (!gone.isEmpty()) {
            this.registry.markDirty();
        }
        return gone.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Unpaid mercenaries: leave at once, or (option) go over to the richest enemy that can pay them. */
    private void unpaidMercenaries(final UUID country, final Payroll.Result r, final Map<UUID, Soldier> byId) {
        final java.util.Set<UUID> mercs = new java.util.HashSet<>();
        for (final Soldier s : byId.values()) {
            if (isMercenary(s)) {
                mercs.add(s.id());
            }
        }
        final List<UUID> unpaid = pl.medivalrpg.armia.logic.Mercenaries.unpaid(r, mercs);
        if (unpaid.isEmpty()) {
            return;
        }
        final long treasury = this.services.skarbiec().balance(this.services.treasury(country));
        final KrajInfo enemy = pl.medivalrpg.armia.logic.Mercenaries.mayDefect(this.cfg().mercRules, treasury) ? this.richestEnemy(country) : null;
        int left = 0;
        int defected = 0;
        for (final UUID id : unpaid) {
            final Soldier s = byId.get(id);
            if (s == null || this.registry.get(id) == null) {
                continue; // already deserted
            }
            if (enemy != null && this.defect(s, enemy)) {
                defected++;
            } else {
                this.dismiss(s, false);
                left++;
            }
        }
        this.notifyCountry(country, "mercenary-unpaid", Placeholder.unparsed("count", Integer.toString(left + defected)),
            Placeholder.unparsed("defected", Integer.toString(defected)), Placeholder.unparsed("enemy", enemy == null ? "" : enemy.name()));
    }

    private KrajInfo richestEnemy(final UUID country) {
        final KrajInfo c = this.services.kraje().getCountry(country).orElse(null);
        if (c == null) {
            return null;
        }
        KrajInfo best = null;
        long bestBalance = 0;
        for (final UUID e : c.enemies()) {
            final KrajInfo k = this.services.kraje().getCountry(e).orElse(null);
            final long b = k == null ? 0 : this.services.skarbiec().balance(this.services.treasury(e));
            if (k != null && b > bestBalance && this.allowedHire(k, 1) > 0) {
                best = k;
                bestBalance = b;
            }
        }
        return best;
    }

    /** The mercenary changes sides: a new soldier of the enemy where it stood, with the same contract. */
    private boolean defect(final Soldier s, final KrajInfo enemy) {
        final UnitType u = this.controller.unitOf(s);
        if (u == null) {
            return false;
        }
        final Location at = s.attached() ? s.brain.getLocation() : null;
        final Pos post = at != null ? Pos.of(at) : Pos.chunkCenter(enemy.capitalWorld(), enemy.capitalX(), enemy.capitalZ());
        final long until = s.rec.contractUntil;
        this.controller.destroy(s);
        final Soldier n = this.create(enemy.id(), u, post, this.cfg().guardRadiusDefault, SoldierMode.GUARD, null, "minecraft:none");
        n.rec.contractUntil = until;
        if (at != null) {
            this.controller.materialize(n, at);
        } else {
            this.controller.queueSpawn(n);
        }
        this.notifyCountry(enemy.id(), "mercenary-defected-to-us", Placeholder.parsed("unit", u.name()));
        return true;
    }

    public void giveMace(final Player p) {
        if (this.context(p, this.cfg().commandRank).isEmpty()) {
            return;
        }
        for (final var item : p.getInventory().getContents()) {
            if (this.mace.isMace(item)) {
                this.m().send(p, "mace-have");
                return;
            }
        }
        p.getInventory().addItem(this.mace.create()).values().forEach(rest -> p.getWorld().dropItem(p.getLocation(), rest));
        this.m().send(p, "mace-given");
    }

    public void info(final CommandSender to, final KrajInfo c) {
        final Messages m = this.m();
        final List<Soldier> all = this.registry.ofCountry(c.id());
        final long loaded = all.stream().filter(Soldier::attached).count();
        final double morale = all.stream().mapToInt(s -> s.rec.morale).average().orElse(100);
        m.send(to, "info-header", Placeholder.component("country", Component.text(c.name(), c.color())));
        m.send(to, "info-count", Placeholder.unparsed("count", Integer.toString(all.size())),
            Placeholder.unparsed("cap", Integer.toString(this.countryCap(c))),
            Placeholder.unparsed("loaded", Long.toString(loaded)),
            Placeholder.unparsed("global", this.registry.size() + "/" + this.cfg().globalMax));
        final BarracksRules.Settings bs = this.cfg().barracks;
        if (this.services.population().present() && (bs.limitEnabled() || bs.healEnabled() || bs.recruitSpawn())) {
            final BarracksRules.Limit l = this.limit(c);
            final TagResolver tags = TagResolver.resolver(this.limitTags(c),
                Placeholder.unparsed("radius", Long.toString(Math.round(bs.healRadius()))),
                Placeholder.unparsed("heal", trim(bs.healPerMinute())));
            if (bs.limitEnabled()) {
                m.send(to, l.working() > 0 ? "info-limit" : "info-limit-none", tags);
                if (all.size() > l.total()) {
                    m.send(to, "info-over-limit", tags);
                }
            }
            if (l.idle() > 0) {
                m.send(to, "info-barracks-idle", tags);
            }
            if (l.working() > 0 && (bs.healEnabled() || bs.recruitSpawn())) {
                m.send(to, bs.healEnabled() ? "info-barracks-garrison" : "info-barracks-muster", tags);
            }
        }
        final Map<String, Integer> byType = this.registry.countByType(c.id());
        for (final UnitType u : this.cfg().units.values()) {
            m.send(to, "info-type", Placeholder.parsed("unit", u.name()), Placeholder.unparsed("count", Integer.toString(byType.getOrDefault(u.id(), 0))),
                Placeholder.unparsed("cost", this.services.money(u.cost())), Placeholder.unparsed("wage", this.services.money(u.wage())));
        }
        m.send(to, "info-wages", Placeholder.unparsed("wages", this.services.money(this.dailyWages(c.id()))),
            Placeholder.unparsed("balance", this.services.money(this.services.skarbiec().balance(this.services.treasury(c.id())))),
            Placeholder.unparsed("morale", Integer.toString((int) Math.round(morale))));
    }

    public void list(final Player p, final int page) {
        final Optional<KrajInfo> ctx = this.context(p, Rank.CITIZEN);
        if (ctx.isEmpty()) {
            return;
        }
        final List<Soldier> all = new ArrayList<>(this.registry.ofCountry(ctx.get().id()));
        all.sort(Comparator.comparing((Soldier s) -> s.rec.type).thenComparing(s -> s.rec.recruitedAt));
        final int per = 10;
        final int pages = Math.max(1, (all.size() + per - 1) / per);
        final int pg = Math.max(1, Math.min(page, pages));
        this.m().send(p, "list-header", Placeholder.unparsed("page", Integer.toString(pg)), Placeholder.unparsed("pages", Integer.toString(pages)),
            Placeholder.unparsed("count", Integer.toString(all.size())));
        for (int i = (pg - 1) * per; i < Math.min(all.size(), pg * per); i++) {
            final Soldier s = all.get(i);
            final UnitType u = this.controller.unitOf(s);
            final String where = s.attached()
                ? s.brain.getLocation().getBlockX() + " " + s.brain.getLocation().getBlockY() + " " + s.brain.getLocation().getBlockZ()
                : (s.rec.spawnPos() == null ? "?" : s.rec.spawnPos().describe());
            final String hp = s.attached() ? Integer.toString((int) Math.ceil(s.brain.getHealth())) : Integer.toString((int) Math.ceil(s.rec.health));
            final String commander = s.rec.commander == null ? "-" : Optional.ofNullable(Bukkit.getOfflinePlayer(s.rec.commander).getName()).orElse("?");
            this.m().send(p, s.attached() ? "list-line" : "list-line-unloaded",
                Placeholder.unparsed("n", Integer.toString(i + 1)),
                Placeholder.parsed("unit", u == null ? s.rec.type : u.name()),
                Placeholder.unparsed("mode", s.rec.mode.displayName()),
                Placeholder.unparsed("pos", where),
                Placeholder.unparsed("hp", hp),
                Placeholder.unparsed("morale", Integer.toString(s.rec.morale)),
                Placeholder.unparsed("commander", commander));
        }
        if (pages > 1) {
            this.m().send(p, "list-footer", Placeholder.unparsed("next", Integer.toString(Math.min(pages, pg + 1))),
                pl.medivalrpg.armia.config.Messages.command("/armia lista " + Math.min(pages, pg + 1)));
        }
    }

    /** 4.0 → "4", 2.5 → "2,5". */
    private static String trim(final double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
    }

    public static String norm(final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** Block clicked with the mace → move target (on top of the block). */
    public static Location standOn(final Block block) {
        return block.getLocation().add(0.5, 1.0, 0.5);
    }
}
