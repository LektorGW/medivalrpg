// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.armia.api.NpcLogEntry;
import pl.medivalrpg.armia.api.NpcSettings;
import pl.medivalrpg.armia.api.UnitTypeInfo;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.api.MemberInfo;
import pl.medivalrpg.kraje.api.SiegeInfo;
import pl.medivalrpg.kraje.api.TreasuryLogEntry;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.access.Access;
import pl.medivalrpg.panel.access.Actor;
import pl.medivalrpg.panel.audit.AuditLog;
import pl.medivalrpg.panel.data.PanelData;
import pl.medivalrpg.panel.util.Json;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Implementations of the RPC methods of PANEL.md. Called on the main thread by {@link RpcService} after the
 * permission check; every parameter is validated here through {@link Params}. Game rules, costs and events come from
 * the Kraje / Armia / Ludnosc APIs (same as the in-game commands).
 */
public final class PanelMethods {

    private static final List<String> MEMBER_RANKS = List.of("ADVISOR", "CITIZEN");
    private static final List<String> PROPOSALS = List.of("ALLIANCE", "NEUTRAL");
    private static final List<String> RELATIONS = List.of("ALLIANCE", "NEUTRAL", "WAR");
    private static final int COORD_MAX = 29_999_000;

    private final PanelPlugin plugin;
    private final NpcV2Methods v2;
    private final PostepMethods postep;
    private final PlanMethods plan;
    private final NpcExtraMethods extra;
    private final EconomyMethods economy;

    public PanelMethods(final PanelPlugin plugin) {
        this.plugin = plugin;
        this.v2 = new NpcV2Methods(plugin);
        this.postep = new PostepMethods(plugin);
        this.plan = new PlanMethods(plugin);
        this.extra = new NpcExtraMethods(plugin);
        this.economy = new EconomyMethods(plugin);
    }

    private KrajeApi kraje() {
        return this.plugin.kraje();
    }

    private SkarbiecApi eco() {
        return this.plugin.skarbiec();
    }

    /** Dispatches one method (already permission-checked). */
    public Object invoke(final Call c) {
        return switch (c.method) {
            case "me" -> this.me(c.actor);
            case "overview" -> this.overview();
            case "world.get", "world.allocate", "world.return", "world.npcTreasuries", "world.npcTransfer", "world.grants", "world.grantsApply" -> WorldMethods.invoke(c);
            case "world.stats.summary", "world.stats.accounts", "world.stats.flows", "world.stats.account", "world.stats.coins",
                 "world.stats.trail", "world.stats.merchant", "world.stats.census", "world.stats.households" -> WorldMethods.stats(c);
            case "railway.get", "railway.request.create", "railway.request.quote", "railway.request.confirm", "railway.request.cancel", "railway.work.resume", "railway.work.cancel", "railway.line.create",
                 "railway.line.configure", "railway.train.assign", "railway.crew.assign" -> RailwayMethods.invoke(c);
            case "firmy.list", "firmy.get", "firmy.admin", "firmy.contracts", "firmy.disputes", "firmy.loans", "firmy.world.loans",
                 "firmy.admin.freeze", "firmy.dispute.resolve", "firmy.world.loanDecide", "firmy.world.loanSweep" -> FirmyMethods.invoke(c);
            case "country.get" -> this.countryGet(c);
            case "country.economy" -> this.economy.invoke(c);
            case "country.flag.get", "country.flag.upload", "country.flag.withdraw", "country.flag.reset", "country.flag.restore",
                 "admin.flag.queue", "admin.flag.approve", "admin.flag.reject", "admin.flag.rebuild" -> FlagMethods.invoke(c);
            case "country.setColor" -> this.setColor(c);
            case "country.setDescription" -> this.setDescription(c);
            case "country.setTax" -> this.setTax(c);
            case "country.transferRuler" -> this.transferRuler(c);
            case "member.setRank" -> this.setRank(c);
            case "member.kick" -> this.kick(c);
            case "member.invite" -> this.invite(c);
            case "relation.propose" -> this.propose(c);
            case "relation.declareWar" -> this.declareWar(c);
            case "relation.breakAlliance" -> this.breakAlliance(c);
            case "treasury.deposit" -> this.deposit(c);
            case "treasury.withdraw" -> this.withdraw(c);
            case "army.recruit" -> this.recruit(c);
            case "army.disband" -> this.disband(c);
            case "admin.npc.create" -> this.npcCreate(c);
            case "admin.npc.fromVillage" -> this.npcFromVillage(c);
            case "admin.npc.set" -> this.npcSet(c);
            case "admin.npc.pause" -> this.npcPause(c);
            case "admin.npc.tick" -> this.npcTick(c);
            case "admin.npc.armyAdd" -> this.npcArmyAdd(c);
            case "admin.npc.ruler" -> this.npcRuler(c);
            case "admin.npc.setManager" -> this.npcSetManager(c);
            case "admin.country.delete" -> this.countryDelete(c);
            case "admin.country.setRuler" -> this.countrySetRuler(c);
            case "admin.relation.set" -> this.relationSet(c);
            case "admin.treasury.adjust" -> this.treasuryAdjust(c);
            case "admin.economy.adjust" -> this.economyAdjust(c);
            case "admin.players" -> this.players();
            case "admin.audit" -> this.audit(c);
            case "admin.health" -> this.health(c);
            case "postep.get", "postep.investPreview", "postep.discover", "postep.invest", "admin.postep.setEra",
                 "admin.postep.points", "admin.postep.grant" -> this.postep.invoke(c);
            case "admin.npc.mapRender", "admin.npc.getPlan", "admin.npc.setPlan", "admin.npc.clearPlan", "admin.npc.planPreview",
                 "admin.npc.territory", "country.plan.get", "country.plan.mapRender", "country.plan.preview", "country.plan.set",
                 "country.plan.clear", "country.plan.levelPreview", "country.plan.level" -> this.plan.invoke(c);
            case "country.plan.buildings" -> this.v2.buildings(c);
            case "admin.npc.festival", "admin.npc.forceRedesign", "admin.npc.forceRoads", "admin.npc.forceModernize" -> this.extra.invoke(c);
            default -> this.v2.invoke(c);
        };
    }

    // ------------------------------------------------------------------ helpers

    /** Throws the API's message when the result failed. */
    static KrajeResult check(final KrajeResult r) {
        if (r == null) {
            throw new RpcException("Operacja nie powiodła się.");
        }
        if (!r.ok()) {
            final String msg = text(r.message(), defaultMessage(r.status()));
            throw new RpcException(msg);
        }
        return r;
    }

    static String defaultMessage(final KrajeResult.Status status) {
        return switch (status) {
            case NOT_FOUND -> "Nie znaleziono.";
            case NAME_TAKEN -> "Ta nazwa jest zajęta.";
            case INSUFFICIENT_FUNDS -> "Za mało pieniędzy.";
            case LIMIT_REACHED -> "Osiągnięto limit.";
            case NOT_AT_WAR -> "Te kraje nie są w stanie wojny.";
            case NO_PROPOSAL -> "Brak ważnej propozycji.";
            case DENIED -> "Operacja niedozwolona.";
            default -> "Nieprawidłowa operacja.";
        };
    }

    /** {@code s} unless it is null or blank (API results may carry a null message despite their docs), else {@code fallback}. */
    static String text(final String s, final String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static Map<String, Object> ok() {
        return Json.obj("ok", true);
    }

    private static Map<String, Object> ok(final KrajeResult r) {
        return Json.obj("ok", true, "message", r.message() == null ? "" : r.message());
    }

    private ArmiaApi requireArmia() {
        final ArmiaApi a = this.plugin.armia();
        if (a == null) {
            throw new RpcException("Plugin Armia nie jest dostępny.");
        }
        return a;
    }

    /** Runs a call to an optional plugin, mapping "old plugin without this method" to a Polish error. */
    private static <T> T compat(final Supplier<T> call) {
        try {
            return call.get();
        } catch (final LinkageError e) {
            throw new RpcException("Zainstalowana wersja pluginu nie obsługuje tej funkcji — zaktualizuj ją.");
        }
    }

    private static <T> T quiet(final Supplier<T> call, final T fallback) {
        try {
            return call.get();
        } catch (final LinkageError | RuntimeException e) {
            return fallback;
        }
    }

    private KrajInfo country(final UUID id) {
        return this.kraje().getCountry(id).orElseThrow(() -> new RpcException("Nie ma takiego kraju."));
    }

    private static String plainName(final KrajInfo k) {
        return k == null ? "?" : k.name();
    }

    /** Player member of {@code k} by nick. */
    private MemberInfo member(final KrajInfo k, final String nick) {
        for (final MemberInfo m : this.kraje().members(k.id())) {
            if (m.name() != null && m.name().equalsIgnoreCase(nick)) {
                return m;
            }
        }
        // Names may be stale in Kraje's cache: fall back to the snapshot and the server's name cache.
        final OfflinePlayer p = Bukkit.getOfflinePlayerIfCached(nick);
        if (p != null && k.members().containsKey(p.getUniqueId())) {
            return new MemberInfo(p.getUniqueId(), p.getName() == null ? nick : p.getName(), k.members().get(p.getUniqueId()), 0, 0, 0);
        }
        throw new RpcException("Gracz " + nick + " nie jest członkiem kraju " + k.name() + ".");
    }

    /** A player known to the server (online or in the name cache). */
    private static OfflinePlayer knownPlayer(final String nick) {
        final Player online = Bukkit.getPlayerExact(nick);
        if (online != null) {
            return online;
        }
        final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(nick);
        if (cached == null) {
            throw new RpcException("Nie znaleziono gracza " + nick + " (musiał choć raz wejść na serwer).");
        }
        return cached;
    }

    private Map<String, Object> countryRef(final UUID id) {
        final KrajInfo k = this.kraje().getCountry(id).orElse(null);
        return Json.obj("id", id.toString(), "name", plainName(k), "color", k == null ? "#808080" : k.hexColor(), "npc", k != null && k.npc());
    }

    private List<Map<String, Object>> countryRefs(final Set<UUID> ids) {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final UUID id : ids) {
            out.add(this.countryRef(id));
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return out;
    }

    private static List<String> ids(final Set<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().toList();
    }

    // ------------------------------------------------------------------ reads

    Map<String, Object> me(final Actor a) {
        final KrajInfo k = a.countryId() == null ? null : this.kraje().getCountry(a.countryId()).orElse(null);
        final long balance = this.eco().balance(SkarbiecApi.playerAccount(a.uuid()));
        final List<String> managed = a.admin() || a.npcManager() ? ids(this.plugin.roles().npcCountries()) : ids(a.managedNpcCountries());
        return Json.obj(
            "uuid", a.uuid().toString(),
            "name", a.name(),
            "admin", a.admin(),
            "balance", balance,
            "balanceText", this.eco().format(balance),
            "country", k == null ? null : Json.obj("id", k.id().toString(), "name", k.name(), "color", k.hexColor(),
                "rank", a.rank() == null ? null : a.rank().name(), "rankName", a.rank() == null ? null : a.rank().displayName()),
            "roles", Json.obj("admin", a.admin(), "npcManager", a.npcManager(), "managedNpcCountries", managed,
                "railwayManager", RailwayMethods.manager(a.uuid()), "flagModerator", FlagMethods.moderator(a.uuid(), a.admin()),
                "worldManager", WorldMethods.manager(a.uuid())));
    }

    Map<String, Object> overview() {
        final LudnoscApi lud = this.plugin.ludnosc();
        final ArmiaApi armia = this.plugin.armia();
        final pl.medivalrpg.postep.api.PostepApi postep = this.plugin.postep();
        final List<Map<String, Object>> countries = new ArrayList<>();
        final List<KrajInfo> all = new ArrayList<>(this.kraje().getCountries());
        all.sort(Comparator.comparing(KrajInfo::name, String.CASE_INSENSITIVE_ORDER));
        for (final KrajInfo k : all) {
            countries.add(Json.obj(
                "id", k.id().toString(),
                "name", k.name(),
                "color", k.hexColor(),
                "npc", k.npc(),
                "ruler", k.rulerName(),
                "members", k.memberCount(),
                "population", lud == null ? null : quiet(() -> lud.population(k.id()), null),
                "soldiers", armia == null ? null : quiet(() -> armia.soldierCount(k.id()), null),
                "chunks", k.chunkCount(),
                "areaBlocks", k.chunkCount() * 256L,
                "allies", ids(k.allies()),
                "wars", ids(k.enemies()),
                "era", postep == null ? null : quiet(() -> PostepMethods.eraRef(postep, postep.era(k.id())), null),
                "eraProgress", postep == null ? null : quiet(() -> Math.round(postep.progress(k.id()) * 1000) / 1000.0, null)));
        }
        return Json.obj("online", Bukkit.getOnlinePlayers().size(), "maxPlayers", Bukkit.getMaxPlayers(), "countries", countries);
    }

    Map<String, Object> countryGet(final Call c) {
        final KrajInfo k = c.target;
        final Actor a = c.actor;
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", k.id().toString());
        out.put("name", k.name());
        out.put("color", k.hexColor());
        out.put("npc", k.npc());
        out.put("description", this.kraje().getDescription(k.id()).orElse(""));
        out.put("ruler", k.rulerName());
        out.put("rulerUuid", k.npc() ? null : k.ruler().toString());
        out.put("capital", Json.obj("world", k.capitalWorld(), "x", k.capitalX(), "z", k.capitalZ(),
            "blockX", (k.capitalX() << 4) + 8, "blockZ", (k.capitalZ() << 4) + 8));
        out.put("chunks", k.chunkCount());
        out.put("areaBlocks", k.chunkCount() * 256L);
        out.put("memberCount", k.memberCount());
        out.put("tax", k.taxPerCitizen());
        out.put("taxText", this.eco().format(k.taxPerCitizen()));
        final Rank myRank = a.rankIn(k.id());
        out.put("myRank", myRank == null ? null : myRank.name());
        final boolean npcManage = Access.canManageNpc(a, k.id(), k.npc());
        out.put("access", Json.obj(
            "member", Access.canSeeMembers(a, k.id()),
            "advisor", Access.canSeeTreasury(a, k.id()),
            "ruler", Access.allowed(a, pl.medivalrpg.panel.access.Requirement.RULER, k.id(), k.npc()),
            "admin", a.admin(),
            "npcManage", npcManage,
            "planRead", Access.allowed(a, pl.medivalrpg.panel.access.Requirement.ADVISOR_OR_NPC_MANAGE, k.id(), k.npc()),
            "planWrite", Access.allowed(a, pl.medivalrpg.panel.access.Requirement.RULER_OR_NPC_MANAGE, k.id(), k.npc()),
            "planLevel", Access.allowed(a, pl.medivalrpg.panel.access.Requirement.ADVISOR_OR_NPC_MANAGE, k.id(), k.npc()),
            "planTerritory", npcManage));

        // M: members with tax debts
        if (Access.canSeeMembers(a, k.id())) {
            final List<Map<String, Object>> members = new ArrayList<>();
            final List<MemberInfo> list = new ArrayList<>(this.kraje().members(k.id()));
            list.sort(Comparator.comparing((MemberInfo m) -> -m.rank().weight()).thenComparing(m -> m.name() == null ? "" : m.name(), String.CASE_INSENSITIVE_ORDER));
            for (final MemberInfo m : list) {
                final OfflinePlayer op = Bukkit.getOfflinePlayer(m.uuid());
                final boolean online = op.isOnline();
                members.add(Json.obj("uuid", m.uuid().toString(), "name", m.name(), "rank", m.rank().name(),
                    "rankName", m.rank().displayName(), "online", online, "taxDebt", m.taxDebt(), "debtDays", m.debtDays(),
                    "joinedAt", m.joinedAt(), "lastSeen", online ? System.currentTimeMillis() : op.getLastSeen()));
            }
            out.put("members", members);
        }

        // A: treasury
        if (Access.canSeeTreasury(a, k.id())) {
            final long balance = this.eco().balance(k.treasuryAccount());
            final List<Map<String, Object>> log = new ArrayList<>();
            for (final TreasuryLogEntry e : this.kraje().treasuryLog(k.id(), this.plugin.cfg().treasuryLog)) {
                log.add(Json.obj("time", e.time(), "type", e.type(), "amount", e.amount(), "amountText", this.eco().format(Math.abs(e.amount())),
                    "actor", e.actor()));
            }
            out.put("treasury", Json.obj("balance", balance, "balanceText", this.eco().format(balance), "log", log));
        }

        // NPC: settings, pause, AI log (admin / NPC managers)
        if (npcManage) {
            out.put("npc", this.npcPart(k));
        }

        // public: population, army, relations, sieges
        out.put("population", this.populationPart(k));
        out.put("mood", this.extra.moodPart(k)); // plugin Zycie (null bez niego)
        out.put("army", this.armyPart(k));
        out.put("relations", Json.obj("allies", this.countryRefs(k.allies()), "wars", this.countryRefs(k.enemies())));
        final List<Map<String, Object>> sieges = new ArrayList<>();
        for (final SiegeInfo s : this.kraje().getSieges()) {
            if (s.attacker().equals(k.id()) || s.defender().equals(k.id())) {
                sieges.add(Json.obj("id", s.id().toString(), "attacker", this.countryRef(s.attacker()), "defender", this.countryRef(s.defender()),
                    "world", s.world(), "cx", s.cx(), "cz", s.cz(), "progress", s.progress(), "startedAt", s.startedAt(), "endsAt", s.endsAt()));
            }
        }
        out.put("sieges", sieges);
        return out;
    }

    private Map<String, Object> npcPart(final KrajInfo k) {
        final ArmiaApi armia = this.plugin.armia();
        final Optional<NpcSettings> s = armia == null ? Optional.empty() : quiet(() -> armia.npcSettings(k.id()), Optional.empty());
        final List<Map<String, Object>> log = new ArrayList<>();
        if (armia != null) {
            for (final NpcLogEntry e : quiet(() -> armia.npcLog(k.id(), this.plugin.cfg().npcLog), List.<NpcLogEntry>of())) {
                log.add(Json.obj("time", e.time(), "text", e.text()));
            }
        }
        final PanelData.Manager mgr = this.plugin.data().manager(k.id());
        return Json.obj(
            "available", s.isPresent(),
            "settings", s.map(NpcSettings::asParams).orElse(null),
            "paused", s.map(NpcSettings::paused).orElse(null),
            "globalPaused", s.map(NpcSettings::globalPaused).orElse(null),
            "nextTickAt", s.map(NpcSettings::nextTickAt).orElse(null),
            "log", log,
            "manager", mgr == null ? null : Json.obj("uuid", mgr.uuid().toString(), "name", mgr.name()));
    }

    private Map<String, Object> populationPart(final KrajInfo k) {
        final LudnoscApi lud = this.plugin.ludnosc();
        if (lud == null) {
            return null;
        }
        final Optional<LudnoscInfo> info = quiet(() -> lud.info(k.id()), Optional.empty());
        if (info.isEmpty()) {
            return Json.obj("population", quiet(() -> lud.population(k.id()), 0), "housing", quiet(() -> lud.housing(k.id()), 0),
                "dailyTax", quiet(() -> lud.dailyTaxIncome(k.id()), 0L));
        }
        final LudnoscInfo i = info.get();
        final List<Map<String, Object>> buildings = new ArrayList<>();
        for (final LudnoscInfo.Building b : i.buildings()) {
            buildings.add(Json.obj("type", b.type(), "name", b.name(), "function", b.function(), "beds", b.beds(),
                "world", b.world(), "x", b.x(), "y", b.y(), "z", b.z()));
        }
        final List<Map<String, Object>> sites = new ArrayList<>();
        for (final LudnoscInfo.Site s : i.sites()) {
            sites.add(Json.obj("type", s.type(), "name", s.name(), "percent", s.percent(), "world", s.world(), "x", s.x(), "y", s.y(), "z", s.z()));
        }
        return Json.obj("population", i.population(), "housing", i.housing(), "food", i.food(), "foodCapacity", i.foodCapacity(),
            "foodBalance", i.foodBalance(), "dailyTax", i.dailyTax(), "dailyTaxText", this.eco().format(i.dailyTax()),
            "hungryDays", i.hungryDays(), "homelessDays", i.homelessDays(), "buildings", buildings, "sites", sites);
    }

    private Map<String, Object> armyPart(final KrajInfo k) {
        final ArmiaApi armia = this.plugin.armia();
        if (armia == null) {
            return null;
        }
        final Map<String, Integer> byType = quiet(() -> armia.soldiersByType(k.id()), Map.<String, Integer>of());
        final List<Map<String, Object>> types = new ArrayList<>();
        for (final UnitTypeInfo u : quiet(armia::unitTypes, List.<UnitTypeInfo>of())) {
            types.add(Json.obj("id", u.id(), "name", u.name(), "description", u.description(), "cost", u.cost(),
                "costText", this.eco().format(u.cost()), "wage", u.wage(), "wageText", this.eco().format(u.wage()),
                "siegeStrength", u.siegeStrength(), "count", byType.getOrDefault(u.id(), 0)));
        }
        final OptionalInt morale = quiet(() -> armia.morale(k.id()), OptionalInt.empty());
        final long wages = quiet(() -> armia.dailyWages(k.id()), 0L);
        return Json.obj("total", quiet(() -> armia.soldierCount(k.id()), 0), "byType", byType, "types", types,
            "morale", morale.isPresent() ? morale.getAsInt() : null, "wages", wages, "wagesText", this.eco().format(wages));
    }

    List<Map<String, Object>> players() {
        final List<Map<String, Object>> out = new ArrayList<>();
        final Map<UUID, Boolean> seen = new LinkedHashMap<>();
        final List<OfflinePlayer> all = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (final OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            all.add(p);
        }
        for (final OfflinePlayer p : all) {
            if (p.getName() == null || seen.putIfAbsent(p.getUniqueId(), Boolean.TRUE) != null) {
                continue;
            }
            final KrajInfo k = this.kraje().getCountryOf(p.getUniqueId()).orElse(null);
            final Rank rank = k == null ? null : k.rankOf(p.getUniqueId());
            out.add(Json.obj("uuid", p.getUniqueId().toString(), "name", p.getName(), "online", p.isOnline(),
                "country", k == null ? null : Json.obj("id", k.id().toString(), "name", k.name()),
                "rank", rank == null ? null : rank.name(),
                "balance", this.eco().balance(SkarbiecApi.playerAccount(p.getUniqueId())),
                "lastSeen", p.isOnline() ? System.currentTimeMillis() : p.getLastSeen()));
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name")), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    List<Map<String, Object>> audit(final Call c) {
        final int limit = c.params.has("limit") ? c.params.integer("limit", 1, 500) : 100;
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final AuditLog.Entry e : this.plugin.audit().recent(limit)) {
            out.add(e.toJson());
        }
        return out;
    }

    /** {@code admin.health {range?}}: TPS / MSPT / players / NPC load charts and warnings (PANEL.md „Wydajność serwera”). */
    Map<String, Object> health(final Call c) {
        final String range = c.params.absentOrEmpty("range") ? "1h"
            : c.params.oneOf("range", List.copyOf(pl.medivalrpg.panel.health.HealthJson.RANGES.keySet()));
        final pl.medivalrpg.panel.health.HealthService svc = this.plugin.health();
        final pl.medivalrpg.panel.health.HealthSettings s = this.plugin.cfg().health;
        final long now = System.currentTimeMillis();
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.enabled());
        out.put("intervalMs", s.intervalMs());
        out.put("npcScan", s.npcScan());
        out.putAll(pl.medivalrpg.panel.health.HealthJson.series(svc.history(), range, now, s.intervalMs(), s.maxPoints()));
        out.put("current", pl.medivalrpg.panel.health.HealthJson.current(svc.current(), id -> {
            final KrajInfo k = this.kraje().getCountry(id).orElse(null);
            return k == null ? null : Json.obj("name", k.name(), "color", k.hexColor(), "npc", k.npc());
        }, s.countryLimit()));
        out.put("plugins", pl.medivalrpg.panel.health.HealthJson.plugins());
        out.put("thresholds", pl.medivalrpg.panel.health.HealthJson.thresholds(s.thresholds()));
        out.put("warnings", pl.medivalrpg.panel.health.HealthJson.warnings(svc.warnings().active()));
        out.put("episodes", pl.medivalrpg.panel.health.HealthJson.episodes(svc.warnings().episodes(), 50));
        return out;
    }

    // ------------------------------------------------------------------ country (R / A / M)

    private Object setColor(final Call c) {
        final int rgb = c.params.color("color");
        check(this.kraje().setColor(c.target.id(), rgb));
        final String hex = String.format("#%06X", rgb);
        c.notice(c.target.id(), "", hex);
        return Json.obj("ok", true, "color", hex);
    }

    private Object setDescription(final Call c) {
        final String text = c.params.absentOrEmpty("text") ? "" : c.params.string("text", 0, this.plugin.cfg().descriptionMaxLength);
        check(this.kraje().setDescription(c.target.id(), text));
        c.notice(c.target.id(), "", "");
        return ok();
    }

    private Object setTax(final Call c) {
        final long amount = c.params.amountOrZero("amount");
        check(this.kraje().setTax(c.target.id(), amount));
        c.notice(c.target.id(), "", amount == 0 ? "brak" : this.eco().format(amount));
        return Json.obj("ok", true, "tax", amount);
    }

    private Object transferRuler(final Call c) {
        final MemberInfo m = this.member(c.target, c.params.nick("player"));
        check(this.kraje().transferRuler(c.target.id(), m.uuid()));
        c.notice(c.target.id(), "", m.name());
        return ok();
    }

    private Object setRank(final Call c) {
        final MemberInfo m = this.member(c.target, c.params.nick("player"));
        final Rank rank = Rank.valueOf(c.params.oneOf("rank", MEMBER_RANKS));
        check(this.kraje().setRank(c.target.id(), m.uuid(), rank));
        c.notice(c.target.id(), m.name(), rank.displayName());
        return ok();
    }

    private Object kick(final Call c) {
        final MemberInfo m = this.member(c.target, c.params.nick("player"));
        check(this.kraje().kickMember(c.target.id(), m.uuid(), c.actor.uuid()));
        c.notice(c.target.id(), m.name(), "");
        return ok();
    }

    private Object invite(final Call c) {
        final String nick = c.params.nick("name");
        check(this.kraje().invite(c.target.id(), c.actor.uuid(), nick));
        c.notice(c.target.id(), nick, "");
        return ok();
    }

    private KrajInfo otherCountry(final Call c, final String param) {
        final KrajInfo other = this.country(c.params.uuid(param));
        if (other.id().equals(c.target.id())) {
            throw new RpcException("Nie można wskazać własnego kraju.");
        }
        return other;
    }

    private Object propose(final Call c) {
        final KrajInfo other = this.otherCountry(c, "target");
        final Relation rel = Relation.valueOf(c.params.oneOf("relation", PROPOSALS));
        check(this.kraje().propose(c.target.id(), other.id(), rel));
        c.notice(c.target.id(), other.name(), rel == Relation.ALLIANCE ? "sojusz" : "pokój");
        // The relation may already be formed (the other side had proposed): report the current one.
        return Json.obj("ok", true, "relation", this.kraje().getRelation(c.target.id(), other.id()).name());
    }

    private Object declareWar(final Call c) {
        final KrajInfo other = this.otherCountry(c, "target");
        check(this.kraje().declareWar(c.target.id(), other.id()));
        c.notice(c.target.id(), other.name(), "");
        return ok();
    }

    private Object breakAlliance(final Call c) {
        final KrajInfo other = this.otherCountry(c, "target");
        check(this.kraje().breakAlliance(c.target.id(), other.id()));
        c.notice(c.target.id(), other.name(), "");
        return ok();
    }

    private Object deposit(final Call c) {
        final long amount = c.params.amount("amount", false);
        check(this.kraje().treasuryDeposit(c.target.id(), c.actor.uuid(), amount));
        c.notice(c.target.id(), "", this.eco().format(amount));
        return Json.obj("ok", true, "balance", this.eco().balance(c.target.treasuryAccount()),
            "playerBalance", this.eco().balance(SkarbiecApi.playerAccount(c.actor.uuid())));
    }

    private Object withdraw(final Call c) {
        final long amount = c.params.amount("amount", false);
        check(this.kraje().treasuryWithdraw(c.target.id(), c.actor.uuid(), amount));
        c.notice(c.target.id(), "", this.eco().format(amount));
        return Json.obj("ok", true, "balance", this.eco().balance(c.target.treasuryAccount()),
            "playerBalance", this.eco().balance(SkarbiecApi.playerAccount(c.actor.uuid())));
    }

    private String unitType(final Call c) {
        return c.params.matching("type", Params.ID, "identyfikator jednostki (np. piechota)");
    }

    private Object recruit(final Call c) {
        final ArmiaApi armia = this.requireArmia();
        final String type = this.unitType(c);
        final int count = c.params.integer("count", 1, this.plugin.cfg().maxArmyCount);
        final KrajeResult r = check(compat(() -> armia.recruit(c.target.id(), type, count, c.actor.uuid())));
        c.notice(c.target.id(), "", r.message());
        return ok(r);
    }

    private Object disband(final Call c) {
        final ArmiaApi armia = this.requireArmia();
        final String type = this.unitType(c);
        final int count = c.params.integer("count", 1, this.plugin.cfg().maxArmyCount);
        final KrajeResult r = check(compat(() -> armia.disband(c.target.id(), type, count)));
        c.notice(c.target.id(), "", r.message());
        return ok(r);
    }

    // ------------------------------------------------------------------ admin.npc.*

    private Object npcCreate(final Call c) {
        final String name = c.params.string("name", 1, 32);
        final int rgb = c.params.color("color");
        final String ruler = c.params.absentOrEmpty("ruler") ? null : c.params.string("ruler", 1, 32);
        final String worldName = c.params.string("world", 1, 64);
        final int x = c.params.integer("x", -COORD_MAX, COORD_MAX);
        final int z = c.params.integer("z", -COORD_MAX, COORD_MAX);
        final World world = Bukkit.getWorld(worldName);
        if (world == null) {
            throw new RpcException("Nie ma świata " + worldName + ".");
        }
        if (!world.getWorldBorder().isInside(new Location(world, x, 64, z))) {
            throw new RpcException("Ten punkt leży poza granicą świata.");
        }
        final int y = world.getHighestBlockYAt(x, z) + 1;
        final Location at = new Location(world, x + 0.5, y, z + 0.5);
        final ArmiaApi armia = this.plugin.armia();
        final KrajeResult r = armia != null
            ? check(compat(() -> armia.createNpcCountry(name, rgb, at, ruler)))
            : check(this.kraje().createNpcCountry(name, rgb, at, ruler == null ? "Władca" : ruler, false));
        return Json.obj("ok", true, "id", r.countryId() == null ? null : r.countryId().toString(), "aiAvailable", armia != null);
    }

    /** {@code admin.npc.fromVillage {x, z, world?, name?, color?, ruler?}}: the village near (x, z) becomes an NPC country (async). */
    private Object npcFromVillage(final Call c) {
        final ArmiaApi armia = this.requireArmia();
        final int x = c.params.integer("x", -COORD_MAX, COORD_MAX);
        final int z = c.params.integer("z", -COORD_MAX, COORD_MAX);
        final String world = c.params.absentOrEmpty("world") ? null : c.params.string("world", 1, 64);
        if (world != null && Bukkit.getWorld(world) == null) {
            throw new RpcException("Nie ma świata " + world + ".");
        }
        final String name = c.params.absentOrEmpty("name") ? null : c.params.string("name", 3, 24);
        if (name != null && !name.matches("[\\p{L}\\p{N}_-]+")) {
            throw new RpcException("Nazwa kraju: litery, cyfry, _ i - (bez spacji).");
        }
        final int rgb = c.params.absentOrEmpty("color") ? -1 : c.params.color("color");
        final String ruler = c.params.absentOrEmpty("ruler") ? null : c.params.string("ruler", 1, 32);
        final java.util.concurrent.CompletableFuture<pl.medivalrpg.armia.api.NpcVillageResult> f =
            compat(() -> armia.createNpcFromVillage(world, x, z, name, rgb, ruler));
        return f.thenApply(r -> {
            if (!r.ok()) {
                throw new RpcException(text(r.message(), "Nie udało się utworzyć kraju z wioski."));
            }
            return Json.obj("ok", true, "id", r.countryId() == null ? null : r.countryId().toString(), "name", r.name(), "message", r.message(), "chunks", r.chunks(),
                "beds", r.beds(), "villagers", r.villagers(), "adopted", r.adopted() < 0 ? null : r.adopted());
        });
    }

    private Object npcSet(final Call c) {
        final ArmiaApi armia = this.requireArmia();
        final String param = c.params.oneOf("param", NpcSettings.PARAMS);
        final String value = c.params.scalar("value");
        return ok(check(compat(() -> armia.setNpcSetting(c.target.id(), param, value))));
    }

    private Object npcPause(final Call c) {
        final ArmiaApi armia = this.requireArmia();
        final boolean paused = c.params.bool("paused");
        check(compat(() -> armia.setNpcPaused(c.target.id(), paused)));
        return Json.obj("ok", true, "paused", paused);
    }

    private Object npcTick(final Call c) {
        final ArmiaApi armia = this.requireArmia();
        check(compat(() -> armia.npcTick(c.target.id())));
        final List<Map<String, Object>> log = new ArrayList<>();
        for (final NpcLogEntry e : quiet(() -> armia.npcLog(c.target.id(), 10), List.<NpcLogEntry>of())) {
            log.add(Json.obj("time", e.time(), "text", e.text()));
        }
        return Json.obj("ok", true, "log", log);
    }

    private Object npcArmyAdd(final Call c) {
        final ArmiaApi armia = this.requireArmia();
        final String type = this.unitType(c);
        final int count = c.params.integer("count", 1, Math.min(100, this.plugin.cfg().maxArmyCount));
        return ok(check(compat(() -> armia.addSoldiers(c.target.id(), type, count))));
    }

    private Object npcRuler(final Call c) {
        final ArmiaApi armia = this.requireArmia();
        final String name = c.params.string("name", 1, 32);
        final String skin = c.params.absentOrEmpty("skin") ? null : c.params.matching("skin", Params.SKIN, "nick Mojang (1–16 znaków: litery, cyfry, _)");
        return ok(check(compat(() -> armia.placeRuler(c.target.id(), name, skin))));
    }

    private Object npcSetManager(final Call c) {
        if (c.params.absentOrEmpty("player")) {
            this.plugin.data().setManager(c.target.id(), null);
            return Json.obj("ok", true, "manager", null);
        }
        final OfflinePlayer p = knownPlayer(c.params.nick("player"));
        final String name = p.getName() == null ? c.params.nick("player") : p.getName();
        this.plugin.data().setManager(c.target.id(), new PanelData.Manager(p.getUniqueId(), name));
        return Json.obj("ok", true, "manager", Json.obj("uuid", p.getUniqueId().toString(), "name", name));
    }

    // ------------------------------------------------------------------ admin.*

    private Object countryDelete(final Call c) {
        check(this.kraje().deleteCountry(c.target.id()));
        this.plugin.data().setManager(c.target.id(), null);
        return ok();
    }

    private Object countrySetRuler(final Call c) {
        final String nick = c.params.nick("player");
        check(this.kraje().setRuler(c.target.id(), nick));
        c.notice(c.target.id(), "", nick);
        return ok();
    }

    private Object relationSet(final Call c) {
        final KrajInfo a = this.country(c.params.uuid("a"));
        final KrajInfo b = this.country(c.params.uuid("b"));
        if (a.id().equals(b.id())) {
            throw new RpcException("Wskaż dwa różne kraje.");
        }
        final Relation rel = Relation.valueOf(c.params.oneOf("relation", RELATIONS));
        check(this.kraje().setRelation(a.id(), b.id(), rel));
        c.notice(a.id(), b.name(), rel.displayName());
        c.notice(b.id(), a.name(), rel.displayName());
        return ok();
    }

    private static void tx(final TxResult r) {
        if (r == null || !r.ok()) {
            throw new RpcException(r == TxResult.INSUFFICIENT_FUNDS ? "Za mało pieniędzy na koncie." : "Operacja na koncie nie powiodła się (" + r + ").");
        }
    }

    private long adjust(final String account, final long amount, final String who) {
        final String reason = "Panel: korekta admina " + who;
        tx(amount > 0 ? this.eco().deposit(account, amount, reason) : this.eco().withdraw(account, -amount, reason));
        return this.eco().balance(account);
    }

    private Object treasuryAdjust(final Call c) {
        WorldMethods.legacyAdjust(c);
        final long amount = c.params.amount("amount", true);
        final long balance = this.adjust(c.target.treasuryAccount(), amount, c.actor.name());
        c.notice(c.target.id(), "", (amount > 0 ? "+" : "-") + this.eco().format(Math.abs(amount)));
        return Json.obj("ok", true, "balance", balance, "balanceText", this.eco().format(balance));
    }

    private Object economyAdjust(final Call c) {
        WorldMethods.legacyAdjust(c);
        final OfflinePlayer p = knownPlayer(c.params.nick("player"));
        final long amount = c.params.amount("amount", true);
        final long balance = this.adjust(SkarbiecApi.playerAccount(p.getUniqueId()), amount, c.actor.name());
        return Json.obj("ok", true, "player", p.getName(), "balance", balance, "balanceText", this.eco().format(balance));
    }
}
