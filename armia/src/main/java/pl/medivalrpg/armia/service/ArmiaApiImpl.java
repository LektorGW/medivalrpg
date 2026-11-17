// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import pl.medivalrpg.armia.ai.NpcAiService;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.armia.api.NpcBudget;
import pl.medivalrpg.armia.api.NpcEconomy;
import pl.medivalrpg.armia.api.NpcGoal;
import pl.medivalrpg.armia.api.NpcLogCategory;
import pl.medivalrpg.armia.api.NpcLogEntry;
import pl.medivalrpg.armia.api.NpcLogRecord;
import pl.medivalrpg.armia.api.NpcOutpost;
import pl.medivalrpg.armia.api.NpcPosture;
import pl.medivalrpg.armia.api.NpcProposal;
import pl.medivalrpg.armia.api.NpcReply;
import pl.medivalrpg.armia.api.NpcSettings;
import pl.medivalrpg.armia.api.NpcStance;
import pl.medivalrpg.armia.api.NpcTribute;
import pl.medivalrpg.armia.logic.BudgetPlan;
import pl.medivalrpg.armia.logic.Ledger;
import pl.medivalrpg.armia.logic.LogText;
import pl.medivalrpg.armia.logic.Opinions;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.armia.api.UnitTypeInfo;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.config.UnitType;
import pl.medivalrpg.armia.logic.DecisionLog;
import pl.medivalrpg.armia.logic.Personality;
import pl.medivalrpg.armia.model.NpcState;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.model.RulerRecord;
import pl.medivalrpg.armia.ruler.RulerService;
import pl.medivalrpg.armia.soldier.ArmyService;
import pl.medivalrpg.armia.soldier.Keys;
import pl.medivalrpg.armia.soldier.Soldier;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.api.KrajeResult.Status;

/**
 * {@link ArmiaApi} for other plugins (Kraje info/map, Ludnosc, the web panel). Counts are thread-safe snapshots;
 * the panel methods (recruitment, NPC administration) must be called on the main thread and run the same rules as
 * {@code /armia} and {@code /npckraj}.
 */
public final class ArmiaApiImpl implements ArmiaApi {

    private final Keys keys;
    private final ArmyService army;
    private final NpcAiService ai;
    private final RulerService rulers;
    private final Services services;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;

    public ArmiaApiImpl(final Keys keys, final ArmyService army, final NpcAiService ai, final RulerService rulers,
                        final Services services, final Supplier<ArmiaConfig> config, final Supplier<Messages> messages) {
        this.keys = keys;
        this.army = army;
        this.ai = ai;
        this.rulers = rulers;
        this.services = services;
        this.config = config;
        this.messages = messages;
    }

    private ArmiaConfig cfg() {
        return this.config.get();
    }

    // ------------------------------------------------------------------ read (thread-safe)

    @Override
    public int soldierCount(final UUID countryId) {
        return this.army.registry().count(countryId);
    }

    @Override
    public Map<String, Integer> soldiersByType(final UUID countryId) {
        return this.army.registry().countByType(countryId);
    }

    @Override
    public Optional<UUID> soldierCountry(final Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        final String part = this.keys.part(entity);
        if (!Keys.PART_BRAIN.equals(part) && !Keys.PART_BODY.equals(part)) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.keys.countryId(entity));
    }

    @Override
    public boolean isAiActive(final UUID countryId) {
        return this.ai.isActive(countryId);
    }

    @Override
    public List<UnitTypeInfo> unitTypes() {
        final List<UnitTypeInfo> out = new ArrayList<>();
        for (final UnitType u : this.cfg().units.values()) {
            out.add(new UnitTypeInfo(u.id(), plain(u.name()), plain(u.description()), u.cost(), u.wage(), u.siegeStrength()));
        }
        return List.copyOf(out);
    }

    /** MiniMessage → plain text (for logs and other services). */
    public static String plainText(final String miniMessage) {
        return plain(miniMessage);
    }

    static String plain(final String miniMessage) {
        if (miniMessage == null || miniMessage.isEmpty()) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(Messages.mini().deserialize(miniMessage));
    }

    @Override
    public OptionalInt morale(final UUID countryId) {
        final List<Soldier> all = this.army.registry().ofCountry(countryId);
        if (all.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Math.round(all.stream().mapToInt(s -> s.record().morale).average().orElse(100)));
    }

    @Override
    public long dailyWages(final UUID countryId) {
        return this.army.dailyWages(countryId);
    }

    // ------------------------------------------------------------------ helpers

    private interface Action {
        KrajeResult run();
    }

    private static KrajeResult main(final Action action) {
        if (!Bukkit.isPrimaryThread()) {
            return KrajeResult.fail(Status.INVALID, "Wywołaj na głównym wątku serwera.");
        }
        return action.run();
    }

    private Optional<KrajInfo> country(final UUID id) {
        return id == null ? Optional.empty() : this.services.kraje().getCountry(id);
    }

    private static KrajeResult notFound() {
        return KrajeResult.fail(Status.NOT_FOUND, "Nie ma takiego kraju.");
    }

    private static KrajeResult notNpc(final KrajInfo c) {
        return KrajeResult.fail(Status.INVALID, c.name() + " nie jest krajem NPC.");
    }

    private KrajeResult unknownUnit(final String type) {
        return KrajeResult.fail(Status.INVALID, "Nieznany typ jednostki " + type + ". Dostępne: " + String.join(", ", this.cfg().units.keySet()));
    }

    /** Limit refusals name the country's limit split into land and barracks bunks. */
    private String plainMessage(final KrajInfo c, final String key, final String cost, final String balance) {
        if (c == null || !"recruit-limit".equals(key)) {
            return this.plainMessage(key, cost, balance);
        }
        final String text = this.messages.get().plain(this.army.limitKey(c), this.army.limitTags(c));
        final String prefix = this.messages.get().plain("prefix");
        return (text.startsWith(prefix) ? text.substring(prefix.length()) : text).trim();
    }

    private String plainMessage(final String key, final String cost, final String balance) {
        final String text = this.messages.get().plain(key, Placeholder.unparsed("cost", cost), Placeholder.unparsed("balance", balance),
            Placeholder.unparsed("cap", "?"), Placeholder.unparsed("global", Integer.toString(this.cfg().globalMax)),
            Placeholder.unparsed("block", this.cfg().barracksBlock.getKey().getKey()),
            Placeholder.unparsed("building", this.cfg().barracksBuilding),
            Placeholder.unparsed("radius", Integer.toString(this.cfg().barracksRadius)));
        final String prefix = this.messages.get().plain("prefix");
        return (text.startsWith(prefix) ? text.substring(prefix.length()) : text).trim();
    }

    /** Capital chunk centre on the surface (loads the chunk if needed — admin/panel actions only). */
    private static Location capital(final KrajInfo c) {
        final World w = Bukkit.getWorld(c.capitalWorld());
        if (w == null) {
            return null;
        }
        final Pos center = Pos.chunkCenter(c.capitalWorld(), c.capitalX(), c.capitalZ());
        final int top = w.getHighestBlockYAt((int) Math.floor(center.x()), (int) Math.floor(center.z()));
        return new Location(w, center.x(), top + 1.0, center.z());
    }

    // ------------------------------------------------------------------ soldiers

    @Override
    public KrajeResult recruit(final UUID countryId, final String unitType, final int count, final UUID byPlayer) {
        return main(() -> {
            final Optional<KrajInfo> oc = this.country(countryId);
            if (oc.isEmpty()) {
                return notFound();
            }
            final KrajInfo c = oc.get();
            final UnitType u = unitType == null ? null : this.cfg().unit(unitType);
            if (u == null) {
                return this.unknownUnit(unitType);
            }
            if (!this.army.available(c.id(), u)) {
                return KrajeResult.fail(Status.DENIED, plain(u.name()) + " jest jeszcze niedostępna (" + this.army.lockReason(c.id(), u) + ").");
            }
            if (count < 1) {
                return KrajeResult.fail(Status.INVALID, "Liczba żołnierzy musi być dodatnia.");
            }
            // Nearest working barracks to the capital (also koszary2 and self-built ones); the first plain "koszary"
            // as before when barracks.recruit-spawn is off.
            final World capitalWorld = Bukkit.getWorld(c.capitalWorld());
            final Location musterFrom = capitalWorld == null ? null
                : new Location(capitalWorld, (c.capitalX() << 4) + 8, 64, (c.capitalZ() << 4) + 8);
            final Location barracks = this.cfg().barracks.recruitSpawn()
                ? this.army.musterPoint(c.id(), musterFrom).orElse(null)
                : this.services.population().buildingSpot(c.id(), this.cfg().barracksBuilding).orElse(null);
            // The capital is only needed without a known barracks: its surface probe would load the chunk synchronously.
            final Location capital = barracks != null ? null : capital(c);
            if (this.cfg().barracksRequired && !this.army.hasBarracksAt(c, barracks != null ? barracks : capital)) {
                final boolean ludnosc = this.services.population().present();
                return KrajeResult.fail(Status.DENIED, this.plainMessage(ludnosc ? "recruit-no-barracks-building" : "recruit-no-barracks", "", ""));
            }
            final Location spawn = barracks != null ? barracks : capital;
            if (spawn == null) {
                return KrajeResult.fail(Status.INVALID, "Świat stolicy nie jest załadowany.");
            }
            final String actor = byPlayer == null ? "panel" : Optional.ofNullable(Bukkit.getOfflinePlayer(byPlayer).getName()).orElse("panel");
            final ArmyService.RemoteRecruit r = this.army.recruitAt(c, u, count, spawn, actor);
            final String money = this.services.money(u.cost());
            final String balance = this.services.money(this.services.skarbiec().balance(this.services.treasury(c.id())));
            if (r.done() <= 0) {
                final String key = r.failureKey() == null ? "recruit-limit" : r.failureKey();
                final Status status = "recruit-no-money".equals(key) ? Status.INSUFFICIENT_FUNDS
                    : "recruit-limit".equals(key) ? Status.LIMIT_REACHED : Status.DENIED;
                return KrajeResult.fail(status, this.plainMessage(c, key, money, balance));
            }
            String summary = "Zrekrutowano " + r.done() + "/" + r.requested() + " × " + plain(u.name()) + " za " + this.services.money(r.spent())
                + (barracks != null ? " (koszary)" : " (stolica)") + ".";
            if (r.failureKey() != null) {
                summary += " " + this.plainMessage(c, r.failureKey(), money, balance);
            }
            return new KrajeResult(Status.OK, c.id(), summary);
        });
    }

    @Override
    public KrajeResult disband(final UUID countryId, final String unitType, final int count) {
        return main(() -> {
            final Optional<KrajInfo> oc = this.country(countryId);
            if (oc.isEmpty()) {
                return notFound();
            }
            if (unitType == null || this.cfg().soldierUnit(unitType) == null) {
                return this.unknownUnit(unitType);
            }
            if (count < 1) {
                return KrajeResult.fail(Status.INVALID, "Liczba żołnierzy musi być dodatnia.");
            }
            final String type = this.cfg().soldierUnit(unitType).id();
            final List<Soldier> candidates = new ArrayList<>();
            for (final Soldier s : this.army.registry().ofCountry(countryId)) {
                if (type.equals(s.record().type) && !s.record().siege) {
                    candidates.add(s);
                }
            }
            // Loaded soldiers first (they can go back to the population), the newest first.
            candidates.sort(Comparator.comparing((Soldier s) -> !s.attached()).thenComparing(s -> -s.record().recruitedAt));
            int done = 0;
            int back = 0;
            for (final Soldier s : candidates) {
                if (done >= count) {
                    break;
                }
                if (this.army.dismiss(s, true)) {
                    back++;
                }
                done++;
            }
            if (done == 0) {
                return KrajeResult.fail(Status.NOT_FOUND, "Brak żołnierzy tego typu poza oblężeniem.");
            }
            this.army.registry().markDirty();
            return new KrajeResult(Status.OK, countryId, "Rozpuszczono " + done + " żołnierzy, do ludności wróciło " + back + ".");
        });
    }

    @Override
    public KrajeResult addSoldiers(final UUID countryId, final String unitType, final int count) {
        return main(() -> {
            final Optional<KrajInfo> oc = this.country(countryId);
            if (oc.isEmpty()) {
                return notFound();
            }
            final UnitType u = unitType == null ? null : this.cfg().unit(unitType);
            if (u == null) {
                return this.unknownUnit(unitType);
            }
            if (count < 1 || count > 100) {
                return KrajeResult.fail(Status.INVALID, "Liczba żołnierzy musi być w zakresie 1–100.");
            }
            final KrajInfo c = oc.get();
            final Pos at = Pos.chunkCenter(c.capitalWorld(), c.capitalX(), c.capitalZ());
            final int added = this.army.addSoldiers(c.id(), u, count, at, this.cfg().guardRadiusDefault, false);
            if (added <= 0) {
                return KrajeResult.fail(Status.LIMIT_REACHED, "Osiągnięto globalny limit żołnierzy.");
            }
            this.ai.log(c.id(), "Panel: +" + added + " × " + u.id() + " (admin)");
            return new KrajeResult(Status.OK, c.id(), "Dodano " + added + " × " + plain(u.name()) + ".");
        });
    }

    // ------------------------------------------------------------------ NPC AI

    @Override
    public Optional<NpcSettings> npcSettings(final UUID countryId) {
        final Optional<KrajInfo> c = this.country(countryId);
        if (c.isEmpty() || !c.get().npc()) {
            return Optional.empty();
        }
        final Optional<NpcState> st = this.ai.state(countryId);
        final Personality p = st.map(NpcState::personality).orElse(this.cfg().aiDefaults);
        return Optional.of(new NpcSettings(p.aggression(), p.expansion(), p.diplomacy(), p.wealth(), p.maxChunks(), p.maxSoldiers(),
            p.active(), st.map(s -> s.paused).orElse(false), !this.cfg().aiEnabled || this.ai.globalPaused(),
            st.map(s -> s.nextTickAt).orElse(0L),
            st.map(NpcState::honor).orElse(NpcSettings.deriveHonor(p.aggression(), p.diplomacy(), p.wealth()))));
    }

    private KrajeResult withNpc(final UUID countryId, final java.util.function.Function<KrajInfo, KrajeResult> action) {
        return main(() -> {
            final Optional<KrajInfo> c = this.country(countryId);
            if (c.isEmpty()) {
                return notFound();
            }
            if (!c.get().npc()) {
                return notNpc(c.get());
            }
            return action.apply(c.get());
        });
    }

    @Override
    public KrajeResult setNpcSetting(final UUID countryId, final String key, final String value) {
        return this.withNpc(countryId, c -> {
            if (key == null || value == null) {
                return KrajeResult.fail(Status.INVALID, "Podaj parametr i wartość.");
            }
            final NpcState st = this.ai.ensure(c.id());
            try {
                if (Personality.HONOR.equalsIgnoreCase(key.trim())) {
                    st.honor = Personality.honorValue(value);
                    final String shown = st.honor() + (st.honor == null ? " (auto)" : "");
                    this.ai.log(c.id(), "Panel: honor = " + shown);
                    return new KrajeResult(Status.OK, c.id(), "honor = " + shown);
                }
                final Personality p = st.personality().with(key, value);
                st.apply(p);
                final String param = key.toLowerCase(Locale.ROOT).replace('_', '-');
                this.ai.log(c.id(), "Panel: " + param + " = " + p.get(param));
                return new KrajeResult(Status.OK, c.id(), param + " = " + p.get(param));
            } catch (final IllegalArgumentException e) {
                return KrajeResult.fail(Status.INVALID, e.getMessage());
            }
        });
    }

    @Override
    public KrajeResult setNpcPaused(final UUID countryId, final boolean paused) {
        return this.withNpc(countryId, c -> {
            final NpcState st = this.ai.ensure(c.id());
            st.paused = paused;
            if (!paused) {
                st.nextTickAt = Math.min(st.nextTickAt, System.currentTimeMillis() + 10_000L);
            }
            this.ai.log(c.id(), paused ? "Panel: pauza" : "Panel: wznowiono");
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult npcTick(final UUID countryId) {
        return this.withNpc(countryId, c -> this.ai.tick(c.id(), true)
            ? KrajeResult.ok(c.id())
            : KrajeResult.fail(Status.INVALID, "Nie udało się wykonać ruchu AI."));
    }

    @Override
    public Optional<UUID> rulerCountry(final Entity entity) {
        return entity == null ? Optional.empty() : this.rulers.countryOf(entity);
    }

    @Override
    public void logNpcDecision(final UUID countryId, final String text) {
        if (countryId == null || text == null || text.isBlank() || this.ai.state(countryId).isEmpty()) {
            return;
        }
        this.ai.log(countryId, text.replaceAll("[\\p{Cntrl}§]", " ").strip());
    }

    @Override
    public List<NpcLogEntry> npcLog(final UUID countryId, final int limit) {
        if (countryId == null || limit <= 0 || this.ai.state(countryId).isEmpty()) {
            return List.of();
        }
        final List<NpcLogEntry> out = new ArrayList<>();
        for (final DecisionLog.Entry e : this.ai.log(countryId).last(limit)) {
            out.add(new NpcLogEntry(e.time(), e.text()));
        }
        Collections.reverse(out);
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ v2: NPC-country management

    private Optional<NpcState> npcState(final UUID npc) {
        final Optional<KrajInfo> c = this.country(npc);
        if (c.isEmpty() || !c.get().npc()) {
            return Optional.empty();
        }
        return Optional.of(this.ai.ensure(npc));
    }

    private Optional<KrajInfo> other(final KrajInfo c, final UUID other) {
        if (other == null || other.equals(c.id())) {
            return Optional.empty();
        }
        return this.country(other);
    }

    private static KrajeResult badTarget() {
        return KrajeResult.fail(Status.INVALID, "Nieprawidłowy kraj docelowy.");
    }

    @Override
    public int opinion(final UUID npc, final UUID other) {
        return other == null ? 0 : this.npcState(npc).map(s -> s.opinionOf(other)).orElse(0);
    }

    @Override
    public KrajeResult setOpinion(final UUID npc, final UUID other, final int value) {
        return this.withNpc(npc, c -> {
            final Optional<KrajInfo> o = this.other(c, other);
            if (o.isEmpty()) {
                return badTarget();
            }
            final NpcState st = this.ai.ensure(c.id());
            final int v = Opinions.clamp(value);
            if (v == 0) {
                st.opinion.remove(other);
            } else {
                st.opinion.put(other, v);
            }
            this.ai.log(c.id(), NpcLogCategory.DIPLOMACY, "Panel: opinia o " + o.get().name() + " = " + v);
            return new KrajeResult(Status.OK, c.id(), Integer.toString(v));
        });
    }

    @Override
    public KrajeResult adjustOpinion(final UUID npc, final UUID other, final int delta) {
        return this.withNpc(npc, c -> {
            final Optional<KrajInfo> o = this.other(c, other);
            if (o.isEmpty()) {
                return badTarget();
            }
            final NpcState st = this.ai.ensure(c.id());
            final int before = st.opinionOf(other);
            final int v = Opinions.add(before, delta);
            if (v == 0) {
                st.opinion.remove(other);
            } else {
                st.opinion.put(other, v);
            }
            if (v != before) {
                this.ai.log(c.id(), NpcLogCategory.DIPLOMACY, "Opinia o " + o.get().name() + ": " + before + " → " + v);
            }
            return new KrajeResult(Status.OK, c.id(), Integer.toString(v));
        });
    }

    @Override
    public NpcStance stance(final UUID npc, final UUID other) {
        return other == null ? NpcStance.AUTO : this.npcState(npc).map(s -> s.stanceOf(other)).orElse(NpcStance.AUTO);
    }

    @Override
    public KrajeResult setStance(final UUID npc, final UUID other, final String stance) {
        return this.withNpc(npc, c -> {
            final Optional<KrajInfo> o = this.other(c, other);
            if (o.isEmpty()) {
                return badTarget();
            }
            final Optional<NpcStance> s = NpcStance.parse(stance);
            if (s.isEmpty()) {
                return KrajeResult.fail(Status.INVALID, "Nieznane nastawienie (auto, przyjazne, wrogie, nigdy_wojny, cel_ataku).");
            }
            final NpcState st = this.ai.ensure(c.id());
            if (s.get() == NpcStance.AUTO) {
                st.stance.remove(other);
            } else {
                st.stance.put(other, s.get().name());
            }
            this.ai.log(c.id(), NpcLogCategory.DIPLOMACY, "Panel: nastawienie wobec " + o.get().name() + " = " + s.get().polish());
            return new KrajeResult(Status.OK, c.id(), s.get().name());
        });
    }

    @Override
    public Optional<NpcBudget> budgetShares(final UUID npc) {
        return this.npcState(npc).map(this.ai::budget);
    }

    @Override
    public KrajeResult setBudgetShares(final UUID npc, final int army, final int buildings, final int reserve) {
        return this.withNpc(npc, c -> {
            final String error = BudgetPlan.validate(army, buildings, reserve);
            if (error != null) {
                return KrajeResult.fail(Status.INVALID, error);
            }
            this.ai.ensure(c.id()).budget = new NpcState.Budget(army, buildings, reserve);
            this.ai.applyResearchShare(c.id());
            this.ai.log(c.id(), NpcLogCategory.ECONOMY, "Panel: podział budżetu — wojsko " + army + "%, budowy " + buildings + "%, rezerwa " + reserve + "%");
            return new KrajeResult(Status.OK, c.id(), "wojsko " + army + "%, budowy " + buildings + "%, rezerwa " + reserve + "%");
        });
    }

    @Override
    public int researchShare(final UUID npc) {
        return this.npcState(npc).map(s -> s.budget == null || s.budget.research == null ? -1 : s.budget.research).orElse(-1);
    }

    @Override
    public KrajeResult setBudgetShares(final UUID npc, final int army, final int buildings, final int research, final int reserve) {
        if (research < 0) {
            return this.setBudgetShares(npc, army, buildings, reserve);
        }
        return this.withNpc(npc, c -> {
            final String error = BudgetPlan.validate(army, buildings, research, reserve);
            if (error != null) {
                return KrajeResult.fail(Status.INVALID, error);
            }
            final NpcState.Budget b = new NpcState.Budget(army, buildings, reserve);
            b.research = research;
            this.ai.ensure(c.id()).budget = b;
            this.ai.applyResearchShare(c.id());
            this.ai.log(c.id(), NpcLogCategory.ECONOMY, "Panel: podział budżetu — wojsko " + army + "%, budowy " + buildings + "%, badania "
                + research + "%, rezerwa " + reserve + "%");
            return new KrajeResult(Status.OK, c.id(), "wojsko " + army + "%, budowy " + buildings + "%, badania " + research + "%, rezerwa " + reserve + "%");
        });
    }

    @Override
    public Optional<NpcPosture> posture(final UUID npc) {
        return this.npcState(npc).map(NpcState::posture);
    }

    @Override
    public KrajeResult setPosture(final UUID npc, final String posture) {
        return this.withNpc(npc, c -> {
            final Optional<NpcPosture> p = NpcPosture.parse(posture);
            if (p.isEmpty()) {
                return KrajeResult.fail(Status.INVALID, "Nieznana postawa (obronna, zrownowazona, ofensywna).");
            }
            this.ai.ensure(c.id()).posture = p.get().name();
            this.ai.log(c.id(), NpcLogCategory.ARMY, "Panel: postawa armii = " + p.get().polish());
            return new KrajeResult(Status.OK, c.id(), p.get().name());
        });
    }

    @Override
    public Optional<UUID> siegeTarget(final UUID npc) {
        return this.npcState(npc).map(s -> s.siegeTarget);
    }

    @Override
    public KrajeResult setSiegeTarget(final UUID npc, final UUID target) {
        return this.withNpc(npc, c -> {
            final NpcState st = this.ai.ensure(c.id());
            if (target == null) {
                st.siegeTarget = null;
                this.ai.log(c.id(), NpcLogCategory.WAR, "Panel: cel oblężeń = automatyczny");
                return KrajeResult.ok(c.id());
            }
            final Optional<KrajInfo> o = this.other(c, target);
            if (o.isEmpty()) {
                return badTarget();
            }
            st.siegeTarget = target;
            this.ai.log(c.id(), NpcLogCategory.WAR, "Panel: cel oblężeń = " + o.get().name());
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public Optional<UUID> autoSiegeTarget(final UUID npc) {
        final Optional<KrajInfo> c = this.country(npc);
        if (c.isEmpty() || !c.get().npc()) {
            return Optional.empty();
        }
        return this.ai.autoTarget(c.get(), this.ai.ensure(npc));
    }

    @Override
    public String aiGoal(final UUID npc) {
        return this.aiGoalDetails(npc).map(NpcGoal::text).orElse("");
    }

    @Override
    public Optional<NpcGoal> aiGoalDetails(final UUID npc) {
        final Optional<KrajInfo> c = this.country(npc);
        if (c.isEmpty() || !c.get().npc()) {
            return Optional.empty();
        }
        final NpcState st = this.ai.ensure(npc);
        if (st.goal == null) {
            st.goal = this.ai.computeGoal(c.get(), st);
        }
        if (!this.ai.isActive(npc)) {
            return Optional.of(new NpcGoal("IDLE", "AI wstrzymana" + (st.goal == null ? "" : " — ostatni cel: " + st.goal.text()), null, null));
        }
        return Optional.ofNullable(st.goal);
    }

    @Override
    public Optional<NpcEconomy> economyBreakdown(final UUID npc) {
        final Optional<NpcState> st = this.npcState(npc);
        if (st.isEmpty()) {
            return Optional.empty();
        }
        this.ai.syncKrajeLedger(npc);
        final long now = System.currentTimeMillis();
        final Ledger.Breakdown b = Ledger.breakdown(st.get().ledger, now, Ledger.DAY);
        final List<NpcEconomy.Flow> flows = new ArrayList<>();
        for (final Ledger.Entry e : b.newestFirst()) {
            flows.add(new NpcEconomy.Flow(e.time(), e.key(), e.amount(), e.actor(), e.note()));
        }
        return Optional.of(new NpcEconomy(now - Ledger.DAY, now, this.services.skarbiec().balance(this.services.treasury(npc)),
            b.income(), b.expenses(), List.copyOf(flows)));
    }

    @Override
    public long tributeMax() {
        return this.cfg().rules.tributeMax();
    }

    @Override
    public NpcReply demandTribute(final UUID npc, final UUID target, final long amount) {
        if (!Bukkit.isPrimaryThread()) {
            return NpcReply.fail("Wywołaj na głównym wątku serwera.");
        }
        final Optional<KrajInfo> c = this.country(npc);
        if (c.isEmpty() || !c.get().npc()) {
            return NpcReply.fail("To nie jest państwo NPC.");
        }
        return this.ai.demandTribute(c.get(), target, amount);
    }

    @Override
    public NpcReply npcPropose(final UUID npc, final UUID target, final Relation relation) {
        if (!Bukkit.isPrimaryThread()) {
            return NpcReply.fail("Wywołaj na głównym wątku serwera.");
        }
        final Optional<KrajInfo> c = this.country(npc);
        if (c.isEmpty() || !c.get().npc()) {
            return NpcReply.fail("To nie jest państwo NPC.");
        }
        return this.ai.proposeFromNpc(c.get(), target, relation);
    }

    @Override
    public KrajeResult npcDeclareWar(final UUID npc, final UUID target) {
        return this.withNpc(npc, c -> {
            final Optional<KrajInfo> o = this.other(c, target);
            if (o.isEmpty()) {
                return badTarget();
            }
            if (c.enemies().contains(target)) {
                return KrajeResult.fail(Status.INVALID, "Te kraje już są w stanie wojny.");
            }
            final KrajeResult r = this.ai.declareWar(c, target, "rozkaz z panelu");
            return r.ok() ? new KrajeResult(Status.OK, c.id(), c.name() + " wypowiada wojnę: " + o.get().name()) : r;
        });
    }

    @Override
    public Optional<NpcProposal> pendingProposal(final UUID npc, final UUID other) {
        if (other == null) {
            return Optional.empty();
        }
        final long limit = System.currentTimeMillis() - this.cfg().aiProposalExpiryMinutes * 60_000L;
        return this.npcState(npc).map(s -> s.pending.get(other)).filter(p -> p.at >= limit)
            .map(p -> new NpcProposal(p.relation, p.out, p.at));
    }

    @Override
    public Optional<NpcTribute> tribute(final UUID npc, final UUID other) {
        if (other == null || this.npcState(npc).isEmpty()) {
            return Optional.empty();
        }
        return this.ai.tributeInfo(npc, other);
    }

    @Override
    public int taxMultiplier(final UUID npc) {
        return this.npcState(npc).map(s -> s.taxMultiplier).orElse(100);
    }

    @Override
    public KrajeResult setTaxMultiplier(final UUID npc, final int percent) {
        return this.withNpc(npc, c -> {
            if (percent < 0 || percent > 300) {
                return KrajeResult.fail(Status.INVALID, "Mnożnik podatku musi wynosić 0–300%.");
            }
            this.ai.ensure(c.id()).taxMultiplier = percent;
            this.ai.log(c.id(), NpcLogCategory.ECONOMY, "Panel: mnożnik podatku od obywateli = " + percent + "%");
            return new KrajeResult(Status.OK, c.id(), percent + "%");
        });
    }

    @Override
    public List<NpcOutpost> outposts(final UUID npc) {
        final Optional<KrajInfo> c = this.country(npc);
        return c.isEmpty() ? List.of() : this.ai.outposts(c.get());
    }

    @Override
    public List<NpcLogRecord> npcLog(final UUID npc, final String category, final String query, final int limit) {
        if (npc == null || limit <= 0 || this.ai.state(npc).isEmpty()) {
            return List.of();
        }
        final NpcLogCategory cat = category == null || category.isBlank() || "ALL".equalsIgnoreCase(category.trim())
            ? null : NpcLogCategory.parse(category).orElse(null);
        if (cat == null && category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category.trim())) {
            return List.of(); // unknown category
        }
        final List<NpcLogRecord> out = new ArrayList<>();
        for (final DecisionLog.Entry e : this.ai.log(npc).find(cat, query, limit)) {
            out.add(new NpcLogRecord(e.time(), e.category(), e.text()));
        }
        return List.copyOf(out);
    }

    @Override
    public void logNpcDecision(final UUID countryId, final String category, final String text) {
        if (countryId == null || text == null || text.isBlank() || this.ai.state(countryId).isEmpty()) {
            return;
        }
        final String clean = text.replaceAll("[\\p{Cntrl}§]", " ").strip();
        this.ai.log(countryId, NpcLogCategory.parse(category).orElse(LogText.classify(clean)), clean);
    }

    @Override
    public List<pl.medivalrpg.armia.api.NpcMercenary> mercenaries(final UUID countryId) {
        if (countryId == null) {
            return List.of();
        }
        final java.util.Map<String, int[]> groups = new java.util.TreeMap<>();
        final java.util.Map<String, UnitType> types = new java.util.HashMap<>();
        for (final Soldier s : this.army.mercenaries(countryId)) {
            final long minute = s.record().contractUntil / 60_000L * 60_000L;
            final String key = String.format("%015d|%s", minute, s.record().type);
            groups.computeIfAbsent(key, k -> new int[1])[0]++;
            types.putIfAbsent(s.record().type, this.cfg().soldierUnit(s.record().type));
        }
        final List<pl.medivalrpg.armia.api.NpcMercenary> out = new ArrayList<>();
        for (final java.util.Map.Entry<String, int[]> e : groups.entrySet()) {
            final String[] parts = e.getKey().split("\\|", 2);
            final UnitType u = types.get(parts[1]);
            out.add(new pl.medivalrpg.armia.api.NpcMercenary(parts[1], u == null ? parts[1] : plain(u.name()), e.getValue()[0],
                Long.parseLong(parts[0]), u == null ? 0 : u.wage()));
        }
        return List.copyOf(out);
    }

    private pl.medivalrpg.armia.village.VillageService villages;

    public void setVillages(final pl.medivalrpg.armia.village.VillageService villages) {
        this.villages = villages;
    }

    @Override
    public java.util.concurrent.CompletableFuture<pl.medivalrpg.armia.api.NpcVillageResult> createNpcFromVillage(final String world, final int x,
                                                                                                                 final int z, final String name, final int rgb, final String ruler) {
        if (this.villages == null || !Bukkit.isPrimaryThread()) {
            return java.util.concurrent.CompletableFuture.completedFuture(pl.medivalrpg.armia.api.NpcVillageResult.fail("Wywołaj na głównym wątku serwera."));
        }
        return this.villages.fromVillage(world, x, z, name, rgb, ruler);
    }

    @Override
    public Optional<Location> npcCapitalSpot(final UUID npc) {
        final Optional<KrajInfo> oc = this.country(npc);
        if (oc.isEmpty() || !oc.get().npc() || !Bukkit.isPrimaryThread()) {
            return Optional.empty();
        }
        final KrajInfo c = oc.get();
        final Optional<RulerRecord> ruler = this.rulers.of(c.id());
        if (ruler.isPresent() && ruler.get().pos != null && ruler.get().pos.bukkitWorld() != null) {
            final Pos p = ruler.get().pos;
            // two blocks in front of the ruler, facing it
            final double rad = Math.toRadians(p.yaw());
            final int x = (int) Math.floor(p.x() - Math.sin(rad) * 2.5);
            final int z = (int) Math.floor(p.z() + Math.cos(rad) * 2.5);
            final Location spot = SafeSpot.near(p.bukkitWorld(), x, z, 6, p.yaw() + 180f);
            if (spot != null) {
                return Optional.of(spot);
            }
        }
        final World w = Bukkit.getWorld(c.capitalWorld());
        if (w == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(SafeSpot.near(w, (c.capitalX() << 4) + 8, (c.capitalZ() << 4) + 8, 8, 0f));
    }

    @Override
    public KrajeResult placeRuler(final UUID countryId, final String name, final String skin) {
        return this.withNpc(countryId, c -> {
            final String clean = name == null ? "" : name.replaceAll("[\\p{Cntrl}§]", "").trim();
            if (clean.isEmpty() || clean.length() > 32) {
                return KrajeResult.fail(Status.INVALID, "Imię władcy musi mieć 1–32 znaki.");
            }
            final String skinName = skin == null || skin.isBlank() ? this.cfg().rulerDefaultSkin : skin.trim();
            if (!skinName.isEmpty() && !skinName.matches("[A-Za-z0-9_]{1,16}")) {
                return KrajeResult.fail(Status.INVALID, "Nieprawidłowa nazwa skina (nick Mojang).");
            }
            Location at = null;
            final Optional<RulerRecord> old = this.rulers.of(c.id());
            if (old.isPresent() && old.get().pos != null) {
                final Pos p = old.get().pos;
                final World w = p.bukkitWorld();
                if (w != null) {
                    final double y = p.isSurface() ? w.getHighestBlockYAt((int) Math.floor(p.x()), (int) Math.floor(p.z())) + 1.0 : p.y();
                    at = new Location(w, p.x(), y, p.z(), p.yaw(), 0f);
                }
            }
            if (at == null) {
                at = capital(c);
            }
            if (at == null) {
                return KrajeResult.fail(Status.INVALID, "Świat stolicy nie jest załadowany.");
            }
            this.rulers.place(c.id(), clean, skinName, at);
            this.ai.log(c.id(), "Panel: władca " + clean);
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult createNpcCountry(final String name, final int rgb, final Location capital, final String rulerName) {
        return main(() -> {
            if (capital == null || capital.getWorld() == null) {
                return KrajeResult.fail(Status.INVALID, "Nieprawidłowe miejsce albo świat.");
            }
            final List<String> names = this.cfg().rulerNames;
            final String ruler = rulerName == null || rulerName.isBlank()
                ? names.get(ThreadLocalRandom.current().nextInt(names.size()))
                : rulerName.replaceAll("[\\p{Cntrl}§]", "").trim();
            final KrajeResult r = this.services.kraje().createNpcCountry(name, rgb, capital, ruler, this.cfg().npcCreateForce);
            if (!r.ok() || r.countryId() == null) {
                return r;
            }
            final NpcState st = this.ai.ensure(r.countryId());
            st.nextTickAt = System.currentTimeMillis() + 30_000L;
            this.rulers.place(r.countryId(), ruler, this.cfg().rulerDefaultSkin, capital);
            this.ai.log(r.countryId(), "Utworzono kraj NPC (panel), władca " + ruler);
            return r;
        });
    }
}
