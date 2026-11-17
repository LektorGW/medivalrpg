// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.postep.PostepPlugin;
import pl.medivalrpg.postep.api.PostepApi;
import pl.medivalrpg.postep.api.event.EraAdvanceEvent;
import pl.medivalrpg.postep.config.Messages;
import pl.medivalrpg.postep.config.PostepConfig;
import pl.medivalrpg.postep.logic.DailySchedule;
import pl.medivalrpg.postep.logic.EraRules;
import pl.medivalrpg.postep.logic.InvestmentMath;
import pl.medivalrpg.postep.logic.NpcStrategy;
import pl.medivalrpg.postep.logic.ResearchMath;
import pl.medivalrpg.postep.logic.TechTree;
import pl.medivalrpg.postep.model.Discovery;
import pl.medivalrpg.postep.model.EraDef;
import pl.medivalrpg.postep.storage.CountryState;
import pl.medivalrpg.postep.storage.DataStore;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * The heart of Postep: country states, daily research tick (with catch-up), discoveries, investments, era advances
 * and the immutable {@link Snapshot}s read by everything else. All mutating methods run on the main thread.
 */
public final class ProgressService {

    /** Outcome of {@link #discover}. */
    public enum DiscoverKind { UNLOCKED, TARGET_SET, ALREADY_OWNED, LATER_ERA, UNKNOWN, NO_COUNTRY }

    public record DiscoverResult(DiscoverKind kind, Discovery discovery, double missingPoints, List<Discovery> path) {
    }

    private final PostepPlugin plugin;
    private final KrajeApi kraje;
    private final SkarbiecApi skarbiec;
    private final Integrations integrations;
    private final DataStore store;
    private DataStore.Data data;
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private volatile PostepConfig cfg;
    private volatile TechTree tree;
    private volatile Messages messages;
    private boolean dirty;

    public ProgressService(final PostepPlugin plugin, final KrajeApi kraje, final SkarbiecApi skarbiec,
                           final Integrations integrations, final DataStore store) {
        this.plugin = plugin;
        this.kraje = kraje;
        this.skarbiec = skarbiec;
        this.integrations = integrations;
        this.store = store;
    }

    public void configure(final PostepConfig cfg, final TechTree tree, final Messages messages) {
        this.cfg = cfg;
        this.tree = tree;
        this.messages = messages;
    }

    public void load() {
        this.data = this.store.load();
        if (this.data.lastTickAt <= 0) {
            // First start: the schedule begins now, nothing is charged for the past.
            this.data.lastTickAt = DailySchedule.latestScheduled(System.currentTimeMillis(), this.cfg.tickTime, this.cfg.zone);
            this.dirty = true;
        }
    }

    public void onChange(final Runnable listener) {
        this.changeListeners.add(listener);
    }

    public PostepConfig cfg() {
        return this.cfg;
    }

    public TechTree tree() {
        return this.tree;
    }

    public Messages messages() {
        return this.messages;
    }

    public KrajeApi kraje() {
        return this.kraje;
    }

    public SkarbiecApi skarbiec() {
        return this.skarbiec;
    }

    public long lastTickAt() {
        return this.data.lastTickAt;
    }

    public long nextTickAt() {
        return DailySchedule.nextScheduled(System.currentTimeMillis(), this.cfg.tickTime, this.cfg.zone);
    }

    // ------------------------------------------------------------------ reads (any thread)

    public Optional<Snapshot> snapshot(final UUID country) {
        return Optional.ofNullable(country == null ? null : this.snapshots.get(country));
    }

    public Collection<Snapshot> snapshots() {
        return List.copyOf(this.snapshots.values());
    }

    /** Era desc, progress desc, total points desc, name. */
    public List<Snapshot> ranking() {
        return this.snapshots.values().stream()
            .sorted(Comparator.comparingInt(Snapshot::era).reversed()
                .thenComparing(Comparator.comparingDouble((Snapshot s) -> s.check().progress()).reversed())
                .thenComparing(Comparator.comparingDouble(Snapshot::totalPoints).reversed())
                .thenComparing(s -> s.name().toLowerCase(java.util.Locale.ROOT)))
            .toList();
    }

    public EraDef era(final int id) {
        return this.cfg.era(id);
    }

    /** Points the next {@code amount} copper would give now. */
    public double investPreview(final UUID country, final long amount) {
        final Snapshot s = this.snapshots.get(country);
        if (s == null) {
            return 0;
        }
        final EraDef era = this.era(s.era());
        return InvestmentMath.gain(s.investedToday(), amount, InvestmentMath.perCopper(era.investPerGold()), era.investDailyCap());
    }

    /** Sum of an effect over the owned discoveries. */
    public double effect(final UUID country, final String key) {
        final Snapshot s = this.snapshots.get(country);
        return s == null ? 0.0 : s.effects().getOrDefault(key, 0.0);
    }

    // ------------------------------------------------------------------ state

    private CountryState state(final KrajInfo info, final long now) {
        CountryState st = this.data.countries.get(info.id());
        if (st == null) {
            st = new CountryState();
            st.eraSince = now;
            st.history.add(new CountryState.EraChange(1, now, "start"));
            this.data.countries.put(info.id(), st);
            this.dirty = true;
        }
        if (st.limitEra(this.cfg.maxEra(), now)) {
            this.dirty = true;
            this.plugin.getLogger().info("Limit epok: " + info.name() + " → " + this.cfg.era(st.era).name());
        }
        if (st.target != null && !this.tree.contains(st.target)) {
            st.target = null;
            this.dirty = true;
        }
        if (!info.name().equals(st.name)) {
            st.name = info.name();
            this.dirty = true;
        }
        return st;
    }

    public void ensureCountry(final UUID countryId) {
        this.kraje.getCountry(countryId).ifPresent(info -> {
            this.state(info, System.currentTimeMillis());
            this.evaluate(info, System.currentTimeMillis(), true);
            this.changed();
        });
    }

    public void removeCountry(final UUID countryId) {
        if (this.data.countries.remove(countryId) != null) {
            this.dirty = true;
        }
        this.snapshots.remove(countryId);
        this.changed();
    }

    // ------------------------------------------------------------------ evaluation

    /** Main thread: every country is evaluated (points per day, era conditions) and may advance. */
    public void refreshAll() {
        final long now = System.currentTimeMillis();
        for (final KrajInfo info : this.kraje.getCountries()) {
            try {
                this.state(info, now);
                this.evaluate(info, now, true);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Błąd oceny postępu kraju " + info.name(), e);
            }
        }
        this.snapshots.keySet().removeIf(id -> !this.data.countries.containsKey(id));
        this.saveIfDirty();
        this.changed();
    }

    /** Re-evaluates one country (after a command), may advance it. */
    public void refresh(final UUID countryId) {
        this.kraje.getCountry(countryId).ifPresent(info -> this.evaluate(info, System.currentTimeMillis(), true));
        this.saveIfDirty();
        this.changed();
    }

    private Snapshot evaluate(final KrajInfo info, final long now, final boolean mayAdvance) {
        final CountryState st = this.state(info, now);
        final PostepConfig c = this.cfg;
        final Integrations.Population pop = this.integrations.population();
        final boolean popPresent = pop.present();
        final int citizens = popPresent ? pop.population(info.id()) : -1;
        final int tier = popPresent ? pop.tier(info.id()) : -1;
        final Map<String, Integer> buildings = popPresent ? pop.buildings(info.id()) : Map.of();
        final int players = info.memberCount();
        final int effectivePop = citizens < 0 ? -1 : citizens + players * c.playerCountsAsCitizens;
        final Map<String, Double> effects = this.effects(st);

        final EraDef era = c.era(st.era);
        final ResearchMath.Breakdown daily = ResearchMath.daily(c.research, new ResearchMath.Input(
            Math.max(0, citizens), players, buildings, info.allies().size(), info.enemies().size(),
            this.kraje.getOverlord(info.id()).isPresent(), info.npc(), era.pointMultiplier(),
            effects.getOrDefault("badania", 0.0)));

        EraRules.Check check = this.check(st, now, effectivePop, tier);
        if (mayAdvance && check.ready()) {
            if (info.npc() || !c.rulerConfirmation) {
                this.advance(info, st, now, "awans");
                check = this.check(st, now, effectivePop, tier);
            } else if (!st.pendingAdvance) {
                st.pendingAdvance = true;
                this.dirty = true;
                this.notifyRulers(info, st);
            }
        } else if (st.pendingAdvance && !check.ready()) {
            st.pendingAdvance = false;
            this.dirty = true;
        }

        final List<String> activeDiscoveries = st.discoveries.keySet().stream().filter(this.tree::contains).toList();
        final Snapshot snap = new Snapshot(info.id(), info.name(), info.hexColor(), info.npc(), players, st.era, st.eraSince,
            st.points, st.totalPoints, activeDiscoveries, Set.copyOf(activeDiscoveries), st.target,
            check, daily, st.investedToday, st.investPointsToday, st.pendingAdvance, st.npcInvestShare, Map.copyOf(effects),
            citizens, effectivePop, tier);
        this.snapshots.put(info.id(), snap);
        return snap;
    }

    private EraRules.Check check(final CountryState st, final long now, final int effectivePop, final int tier) {
        final PostepConfig c = this.cfg;
        final int maxEra = c.maxEra();
        if (st.era >= maxEra) {
            return EraRules.Check.MAX;
        }
        final EraDef current = c.era(st.era);
        final EraDef next = c.era(st.era + 1);
        return EraRules.evaluate(new EraRules.Input(st.era, maxEra, st.eraSince, now, current.minDays(),
            st.discoveries.keySet(), this.tree.requiredToLeave(st.era), st.points, effectivePop, next.minPopulation(),
            tier, tier < 0 ? "?" : c.tierName(tier), next.minTier(), c.tierName(next.minTier()), c.skipUnknownThresholds),
            c.weights, c.missingTexts);
    }

    private Map<String, Double> effects(final CountryState st) {
        final Map<String, Double> out = new LinkedHashMap<>();
        for (final String id : st.discoveries.keySet()) {
            this.tree.get(id).ifPresent(d -> d.effects().forEach((k, v) -> out.merge(k, v, Double::sum)));
        }
        return out;
    }

    // ------------------------------------------------------------------ daily tick

    /** Main thread, every minute: processes due daily ticks (catching up missed ones). */
    public void checkDaily() {
        final long now = System.currentTimeMillis();
        final List<Long> due = DailySchedule.dueTicks(this.data.lastTickAt, now, this.cfg.tickTime, this.cfg.zone, this.cfg.maxCatchUp);
        if (due.isEmpty()) {
            final long latest = DailySchedule.latestScheduled(now, this.cfg.tickTime, this.cfg.zone);
            if (latest > this.data.lastTickAt && this.cfg.maxCatchUp == 0) {
                this.data.lastTickAt = latest;
                this.dirty = true;
            }
            return;
        }
        if (due.size() > 1) {
            this.plugin.getLogger().info("Nadrabiam " + due.size() + " dzienne naliczenia postępu po przerwie");
        }
        for (final long at : due) {
            // marked BEFORE the tick (it saves inside): a crash right after the save never runs this day twice
            this.data.lastTickAt = at;
            this.dailyTick(at);
        }
        this.data.lastTickAt = Math.max(this.data.lastTickAt, DailySchedule.latestScheduled(now, this.cfg.tickTime, this.cfg.zone));
        this.dirty = true;
        this.refreshAll();
    }

    /** One daily tick at {@code at} (admin {@code tick} uses now): points, NPC investment and research, advances. */
    public void dailyTick(final long at) {
        for (final KrajInfo info : this.kraje.getCountries()) {
            try {
                this.dailyTick(info, at);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Błąd dziennego naliczenia postępu dla " + info.name(), e);
            }
        }
        this.dirty = true;
        this.saveIfDirty();
        this.changed();
    }

    private void dailyTick(final KrajInfo info, final long at) {
        final CountryState st = this.state(info, at);
        final Snapshot before = this.evaluate(info, at, false);
        final double gained = before.daily().total();
        st.points += gained;
        st.totalPoints += gained;
        st.lastDaily = gained;
        st.investedToday = 0;
        st.investPointsToday = 0;
        if (info.npc() && this.cfg.npcAutoInvest) {
            this.npcInvest(info, st);
        }
        this.research(info, st, at);
        this.evaluate(info, at, true);
    }

    private void npcInvest(final KrajInfo info, final CountryState st) {
        final Integrations.NpcAi ai = this.integrations.npcAi();
        final NpcStrategy.Personality p = ai.personality(info.id()).orElse(NpcStrategy.Personality.NEUTRAL);
        final OptionalLong income = ai.income24h(info.id());
        final long dailyIncome = income.isPresent() ? income.getAsLong() : this.integrations.population().dailyTax(info.id());
        final double share = NpcStrategy.investShare(this.cfg.npcInvest, p, !info.enemies().isEmpty(), st.npcInvestShare);
        final EraDef era = this.cfg.era(st.era);
        final String account = this.account(info);
        final long amount = NpcStrategy.investAmount(this.cfg.npcInvest, dailyIncome, this.skarbiec.balance(account), share,
            st.investedToday, InvestmentMath.perCopper(era.investPerGold()), era.investDailyCap());
        if (amount <= 0) {
            return;
        }
        final double points = this.investInternal(info, st, amount);
        if (points > 0) {
            ai.log(info.id(), "gospodarka", this.plain("npc-log-inwestycja",
                Placeholder.unparsed("amount", this.skarbiec.format(amount)),
                Placeholder.unparsed("points", fmt(points))));
        }
    }

    /** Unlocks the target (and its path) while the points suffice; picks NPC / auto targets. */
    private void research(final KrajInfo info, final CountryState st, final long now) {
        final TechTree t = this.tree;
        for (int guard = 0; guard < 32; guard++) {
            if (st.target != null) {
                final Optional<Discovery> target = t.get(st.target);
                if (target.isEmpty() || st.discoveries.containsKey(st.target) || target.get().era() > st.era) {
                    st.target = null;
                    this.dirty = true;
                    continue;
                }
                final List<Discovery> path = t.pathTo(st.target, st.discoveries.keySet());
                final Discovery next = path.isEmpty() ? null : path.get(0);
                if (next == null || next.era() > st.era) {
                    st.target = null;
                    this.dirty = true;
                    continue;
                }
                if (st.points + 1e-9 < next.cost()) {
                    return;
                }
                this.unlock(info, st, next, now, false);
                continue;
            }
            final boolean auto = info.npc() ? this.cfg.npcAutoResearch : this.cfg.playersAutoTarget;
            if (!auto) {
                return;
            }
            final NpcStrategy.Personality p = info.npc()
                ? this.integrations.npcAi().personality(info.id()).orElse(NpcStrategy.Personality.NEUTRAL)
                : NpcStrategy.Personality.NEUTRAL;
            final EraRules.Check check = this.check(st, now, -1, -1);
            final Optional<Discovery> choice = NpcStrategy.chooseResearch(t, st.discoveries.keySet(), st.era, p, info.id(),
                check.timePart() >= 1.0);
            if (choice.isEmpty()) {
                return;
            }
            st.target = choice.get().id();
            this.dirty = true;
            if (info.npc()) {
                this.integrations.npcAi().log(info.id(), "inne", this.plain("npc-log-cel",
                    Placeholder.unparsed("discovery", choice.get().name())));
            }
        }
    }

    private void unlock(final KrajInfo info, final CountryState st, final Discovery d, final long now, final boolean free) {
        if (!free) {
            st.points = Math.max(0, st.points - d.cost());
        }
        st.discoveries.put(d.id(), now);
        if (d.id().equals(st.target)) {
            st.target = null;
        }
        this.dirty = true;
        this.announceDiscovery(info, d);
        if (info.npc()) {
            this.integrations.npcAi().log(info.id(), "inne", this.plain("npc-log-odkrycie",
                Placeholder.unparsed("discovery", d.name())));
        }
    }

    // ------------------------------------------------------------------ actions

    /**
     * {@code /postep odkryj <id>}: unlocks now when the points suffice (and every requirement is owned), otherwise sets
     * the research target (requirements are researched first, automatically).
     */
    public DiscoverResult discover(final UUID countryId, final String id) {
        final Optional<KrajInfo> info = this.kraje.getCountry(countryId);
        if (info.isEmpty()) {
            return new DiscoverResult(DiscoverKind.NO_COUNTRY, null, 0, List.of());
        }
        final Optional<Discovery> d = this.tree.get(id == null ? null : id.toLowerCase(java.util.Locale.ROOT));
        if (d.isEmpty()) {
            return new DiscoverResult(DiscoverKind.UNKNOWN, null, 0, List.of());
        }
        final long now = System.currentTimeMillis();
        final CountryState st = this.state(info.get(), now);
        if (st.discoveries.containsKey(d.get().id())) {
            return new DiscoverResult(DiscoverKind.ALREADY_OWNED, d.get(), 0, List.of());
        }
        if (d.get().era() > st.era) {
            return new DiscoverResult(DiscoverKind.LATER_ERA, d.get(), 0, List.of());
        }
        final List<Discovery> path = this.tree.pathTo(d.get().id(), st.discoveries.keySet());
        if (path.size() == 1 && st.points + 1e-9 >= d.get().cost()) {
            this.unlock(info.get(), st, d.get(), now, false);
            this.research(info.get(), st, now);
            this.refresh(countryId);
            return new DiscoverResult(DiscoverKind.UNLOCKED, d.get(), 0, path);
        }
        st.target = d.get().id();
        this.dirty = true;
        this.research(info.get(), st, now); // may already unlock prerequisites
        final boolean owned = st.discoveries.containsKey(d.get().id());
        this.refresh(countryId);
        if (owned) {
            return new DiscoverResult(DiscoverKind.UNLOCKED, d.get(), 0, path);
        }
        final long left = this.tree.pathTo(d.get().id(), st.discoveries.keySet()).stream().mapToLong(Discovery::cost).sum();
        return new DiscoverResult(DiscoverKind.TARGET_SET, d.get(), Math.max(0, left - st.points),
            this.tree.pathTo(d.get().id(), st.discoveries.keySet()));
    }

    public boolean clearTarget(final UUID countryId) {
        final CountryState st = this.data.countries.get(countryId);
        if (st == null || st.target == null) {
            return false;
        }
        st.target = null;
        this.dirty = true;
        this.refresh(countryId);
        return true;
    }

    /** Converts treasury money into points (see {@link PostepApi#invest}). */
    public PostepApi.InvestResult invest(final UUID countryId, final long amount, final UUID byPlayer) {
        final Optional<KrajInfo> info = this.kraje.getCountry(countryId);
        if (info.isEmpty()) {
            return new PostepApi.InvestResult(false, 0, 0, this.plain("invest-err-country"));
        }
        if (amount < this.cfg.minInvest) {
            return new PostepApi.InvestResult(false, 0, 0, this.plain("invest-err-min",
                Placeholder.unparsed("min", this.skarbiec.format(this.cfg.minInvest))));
        }
        final CountryState st = this.state(info.get(), System.currentTimeMillis());
        final EraDef era = this.cfg.era(st.era);
        final double rate = InvestmentMath.perCopper(era.investPerGold());
        // only what still buys points today is taken (above ~99% of the daily cap the money would be wasted)
        final long pay = InvestmentMath.useful(st.investedToday, amount, rate, era.investDailyCap());
        final double preview = InvestmentMath.gain(st.investedToday, pay, rate, era.investDailyCap());
        if (preview < 0.05) {
            return new PostepApi.InvestResult(false, 0, 0, this.plain("invest-err-cap"));
        }
        final String account = this.account(info.get());
        if (!this.skarbiec.has(account, pay)) {
            return new PostepApi.InvestResult(false, 0, 0, this.plain("invest-err-funds",
                Placeholder.unparsed("balance", this.skarbiec.format(this.skarbiec.balance(account)))));
        }
        final double points = this.investInternal(info.get(), st, pay);
        if (points <= 0) {
            return new PostepApi.InvestResult(false, 0, 0, this.plain("invest-err-funds",
                Placeholder.unparsed("balance", this.skarbiec.format(this.skarbiec.balance(account)))));
        }
        this.research(info.get(), st, System.currentTimeMillis());
        this.refresh(countryId);
        return new PostepApi.InvestResult(true, pay, points, "");
    }

    private double investInternal(final KrajInfo info, final CountryState st, final long amount) {
        final EraDef era = this.cfg.era(st.era);
        final double points = InvestmentMath.gain(st.investedToday, amount, InvestmentMath.perCopper(era.investPerGold()), era.investDailyCap());
        final TxResult tx = this.skarbiec.withdraw(this.account(info), amount, "Postęp: inwestycja w badania");
        if (tx != TxResult.OK) {
            return 0;
        }
        st.investedToday += amount;
        st.investPointsToday += points;
        st.points += points;
        st.totalPoints += points;
        this.dirty = true;
        return points;
    }

    public boolean setNpcInvestShare(final UUID countryId, final int percent) {
        final Optional<KrajInfo> info = this.kraje.getCountry(countryId);
        if (info.isEmpty() || percent < -1 || percent > 100) {
            return false;
        }
        this.state(info.get(), System.currentTimeMillis()).npcInvestShare = percent;
        this.dirty = true;
        this.refresh(countryId);
        return true;
    }

    /** {@code /postep awansuj}: the ruler confirms a pending advance. False when not ready. */
    public boolean confirmAdvance(final UUID countryId) {
        final Optional<KrajInfo> info = this.kraje.getCountry(countryId);
        if (info.isEmpty()) {
            return false;
        }
        final long now = System.currentTimeMillis();
        final CountryState st = this.state(info.get(), now);
        final Snapshot s = this.evaluate(info.get(), now, false);
        if (!s.check().ready()) {
            return false;
        }
        this.advance(info.get(), st, now, "awans");
        this.refresh(countryId);
        return true;
    }

    // ------------------------------------------------------------------ admin

    /** Sets the era; the required discoveries of all earlier eras are granted so the tree stays consistent. */
    public boolean adminSetEra(final UUID countryId, final int era) {
        final Optional<KrajInfo> info = this.kraje.getCountry(countryId);
        if (info.isEmpty() || era < 1 || era > this.cfg.maxEra()) {
            return false;
        }
        final long now = System.currentTimeMillis();
        final CountryState st = this.state(info.get(), now);
        for (int e = 1; e < era; e++) {
            for (final Discovery d : this.tree.requiredToLeave(e)) {
                for (final Discovery p : this.tree.pathTo(d.id(), st.discoveries.keySet())) {
                    st.discoveries.put(p.id(), now);
                }
            }
        }
        if (st.era == era) {
            this.dirty = true;
            this.refresh(countryId);
            return true;
        }
        final int old = st.era;
        st.era = era;
        st.eraSince = now;
        st.pendingAdvance = false;
        st.history.add(new CountryState.EraChange(era, now, "admin"));
        this.dirty = true;
        this.saveIfDirty();
        if (era > old) {
            this.announceAdvance(info.get(), era);
        }
        Bukkit.getPluginManager().callEvent(new EraAdvanceEvent(countryId, old, era));
        this.refresh(countryId);
        return true;
    }

    public boolean adminPoints(final UUID countryId, final double delta) {
        final Optional<KrajInfo> info = this.kraje.getCountry(countryId);
        if (info.isEmpty()) {
            return false;
        }
        final CountryState st = this.state(info.get(), System.currentTimeMillis());
        st.points = Math.max(0, st.points + delta);
        if (delta > 0) {
            st.totalPoints += delta;
        }
        this.dirty = true;
        this.research(info.get(), st, System.currentTimeMillis());
        this.refresh(countryId);
        return true;
    }

    /** Grants a discovery (and its missing prerequisites) for free, ignoring the era. */
    public List<Discovery> adminGrant(final UUID countryId, final String id) {
        final Optional<KrajInfo> info = this.kraje.getCountry(countryId);
        final Optional<Discovery> d = this.tree.get(id);
        if (info.isEmpty() || d.isEmpty()) {
            return List.of();
        }
        final long now = System.currentTimeMillis();
        final CountryState st = this.state(info.get(), now);
        final List<Discovery> path = this.tree.pathTo(d.get().id(), st.discoveries.keySet());
        for (final Discovery p : path) {
            this.unlock(info.get(), st, p, now, true);
        }
        this.refresh(countryId);
        return path;
    }

    /** Removes a discovery and everything that depends on it; returns the removed ids. */
    public List<String> adminRevoke(final UUID countryId, final String id) {
        final CountryState st = this.data.countries.get(countryId);
        if (st == null || !st.discoveries.containsKey(id)) {
            return List.of();
        }
        final Set<String> remove = new LinkedHashSet<>();
        remove.add(id);
        remove.addAll(this.tree.dependents(id));
        remove.retainAll(st.discoveries.keySet());
        remove.forEach(st.discoveries::remove);
        this.dirty = true;
        this.refresh(countryId);
        return List.copyOf(remove);
    }

    // ------------------------------------------------------------------ advance & announcements

    private void advance(final KrajInfo info, final CountryState st, final long now, final String cause) {
        final int old = st.era;
        if (old >= this.cfg.maxEra()) {
            return;
        }
        st.era = old + 1;
        st.eraSince = now;
        st.pendingAdvance = false;
        st.history.add(new CountryState.EraChange(st.era, now, cause));
        this.dirty = true;
        this.saveIfDirty();
        this.plugin.getLogger().info(info.name() + " wkracza w epokę " + this.cfg.era(st.era).name());
        this.announceAdvance(info, st.era);
        if (info.npc()) {
            this.integrations.npcAi().log(info.id(), "inne", this.plain("npc-log-epoka",
                Placeholder.unparsed("era", this.cfg.era(st.era).name())));
        }
        Bukkit.getPluginManager().callEvent(new EraAdvanceEvent(info.id(), old, st.era));
    }

    private void announceAdvance(final KrajInfo info, final int eraId) {
        final PostepConfig c = this.cfg;
        final EraDef era = c.era(eraId);
        final TagResolver r = this.eraResolver(info, era);
        if (c.announce) {
            Bukkit.getServer().sendMessage(this.messages.get("awans-czat", r));
        }
        final Collection<? extends Player> audience = c.titleForAll ? Bukkit.getOnlinePlayers() : this.onlineMembers(info);
        if (c.title) {
            final Title title = Title.title(this.messages.get("awans-title", r), this.messages.get("awans-subtitle", r),
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(4500), Duration.ofMillis(1200)));
            audience.forEach(p -> p.showTitle(title));
        }
        if (c.sound != null && !c.sound.isBlank()) {
            try {
                final Sound sound = Sound.sound(Key.key(c.sound.trim()), Sound.Source.MASTER, c.soundVolume, c.soundPitch);
                audience.forEach(p -> p.playSound(sound, Sound.Emitter.self()));
            } catch (final RuntimeException e) {
                this.plugin.getLogger().warning("awans.dzwiek: niepoprawny klucz dźwięku " + c.sound);
            }
        }
    }

    private void announceDiscovery(final KrajInfo info, final Discovery d) {
        final String mode = this.cfg.discoveryAnnounce;
        if ("brak".equals(mode)) {
            return;
        }
        final TagResolver r = TagResolver.resolver(this.countryResolver(info),
            Placeholder.unparsed("discovery", d.name()),
            Placeholder.unparsed("effect", d.effectText()),
            Placeholder.unparsed("description", d.description()));
        if ("wszyscy".equals(mode)) {
            Bukkit.getServer().sendMessage(this.messages.get("odkrycie-wszyscy", r));
        } else {
            this.onlineMembers(info).forEach(p -> this.messages.send(p, "odkrycie-kraj", r));
        }
    }

    private void notifyRulers(final KrajInfo info, final CountryState st) {
        final EraDef next = this.cfg.era(st.era + 1);
        final TagResolver r = this.eraResolver(info, next);
        for (final Map.Entry<UUID, Rank> e : info.members().entrySet()) {
            final Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                this.messages.send(p, e.getValue() == Rank.RULER ? "awans-gotowy-wladca" : "awans-gotowy", r);
            }
        }
    }

    /** On join: reminds the ruler about a pending advance. */
    public void remindPending(final Player player) {
        this.kraje.getCountryOf(player.getUniqueId()).ifPresent(info -> {
            final CountryState st = this.data.countries.get(info.id());
            if (st != null && st.pendingAdvance && info.rankOf(player.getUniqueId()) == Rank.RULER) {
                this.messages.send(player, "awans-gotowy-wladca", this.eraResolver(info, this.cfg.era(st.era + 1)));
            }
        });
    }

    public TagResolver eraResolver(final KrajInfo info, final EraDef era) {
        return TagResolver.resolver(this.countryResolver(info),
            Placeholder.unparsed("era", era.name()),
            Placeholder.unparsed("era_acc", era.accusative()),
            Placeholder.unparsed("years", era.years()),
            Placeholder.unparsed("icon", era.icon()),
            Placeholder.styling("era_color", color(era.color())));
    }

    public TagResolver countryResolver(final KrajInfo info) {
        return TagResolver.resolver(
            Placeholder.component("country", Component.text(info.name(), info.color())),
            Placeholder.unparsed("country_name", info.name()),
            Placeholder.styling("country_color", info.color()));
    }

    public static TextColor color(final String hex) {
        final TextColor c = hex == null ? null : TextColor.fromHexString(hex.trim());
        return c == null ? TextColor.color(0xFFFFFF) : c;
    }

    private List<Player> onlineMembers(final KrajInfo info) {
        final List<Player> out = new ArrayList<>();
        for (final UUID id : info.members().keySet()) {
            final Player p = Bukkit.getPlayer(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private String account(final KrajInfo info) {
        return info.treasuryAccount() == null || info.treasuryAccount().isBlank()
            ? SkarbiecApi.countryAccount(info.id()) : info.treasuryAccount();
    }

    private String plain(final String key, final TagResolver... r) {
        return PlainTextComponentSerializer.plainText().serialize(this.messages.get(key, r));
    }

    /** "12", "12,5". */
    public static String fmt(final double v) {
        final double rounded = Math.round(v * 10.0) / 10.0;
        if (Math.abs(rounded - Math.rint(rounded)) < 1e-9) {
            return Long.toString(Math.round(rounded));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", rounded).replace('.', ',');
    }

    // ------------------------------------------------------------------ persistence

    private void changed() {
        for (final Runnable r : this.changeListeners) {
            try {
                r.run();
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Błąd odświeżania widoków postępu", e);
            }
        }
    }

    public void saveIfDirty() {
        if (this.dirty) {
            this.dirty = false;
            this.store.saveAsync(this.data);
        }
    }

    public void saveNow() {
        this.dirty = false;
        this.store.saveNow(this.data);
    }

    /** Copy of the persisted states (for the JSON export). */
    public Map<UUID, CountryState> statesView() {
        return new HashMap<>(this.data.countries);
    }
}
