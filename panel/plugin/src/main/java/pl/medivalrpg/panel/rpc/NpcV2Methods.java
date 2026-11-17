// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.armia.api.NpcBudget;
import pl.medivalrpg.armia.api.NpcEconomy;
import pl.medivalrpg.armia.api.NpcGoal;
import pl.medivalrpg.armia.api.NpcLogRecord;
import pl.medivalrpg.armia.api.NpcOutpost;
import pl.medivalrpg.armia.api.NpcProposal;
import pl.medivalrpg.armia.api.NpcReply;
import pl.medivalrpg.armia.api.NpcSettings;
import pl.medivalrpg.armia.api.NpcTribute;
import pl.medivalrpg.armia.api.UnitTypeInfo;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.api.SiegeInfo;
import pl.medivalrpg.kraje.api.TreasuryLogEntry;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.history.HistoryLog;
import pl.medivalrpg.panel.util.Json;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * {@code admin.npc.*} v2 (PANEL.md "Kształty odpowiedzi v2"): full management of one NPC country. Called on the main
 * thread by {@link RpcService} after the permission check (ADM, global NPC manager, manager of {@code id}) and the
 * NPC-country check. AI state comes from Armia, population and buildings from Ludnosc (both optional: a missing
 * plugin gives a Polish error for mutations and nulls in reads).
 */
final class NpcV2Methods {

    static final List<String> STANCES = List.of("AUTO", "FRIENDLY", "HOSTILE", "NEVER_WAR", "PREFERRED_TARGET");
    static final List<String> POSTURES = List.of("DEFENSIVE", "BALANCED", "OFFENSIVE");
    static final List<String> CATEGORIES = List.of("WAR", "DIPLOMACY", "BUILD", "ARMY", "ECONOMY", "OTHER");
    static final List<String> INCOME_KEYS = List.of("npcTax", "aiIncome", "tribute", "other");
    static final List<String> EXPENSE_KEYS = List.of("wages", "land", "buildings", "recruit", "tribute", "war", "other");
    static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("npcTax", "Podatki od ludności"), Map.entry("aiIncome", "Dochód AI"), Map.entry("tribute", "Trybuty"),
        Map.entry("other", "Inne"), Map.entry("wages", "Żołd"), Map.entry("land", "Utrzymanie ziemi"), Map.entry("buildings", "Budowy"),
        Map.entry("recruit", "Rekrutacja"), Map.entry("war", "Wojny i oblężenia"));
    /** Ludnosc's default settlement levels (population, treasury) — only for the "next tier" hint. */
    static final String[] TIER_NAMES = {"Osada", "Wieś", "Miasteczko", "Miasto", "Stolica"};
    static final int[] TIER_POPULATION = {0, 8, 20, 40, 70};
    static final long[] TIER_TREASURY = {0, 2_000, 10_000, 30_000, 80_000};
    static final Map<String, String> FUNCTIONS = Map.ofEntries(
        Map.entry("chata", "HOUSING"), Map.entry("dom", "HOUSING"), Map.entry("kamienica", "HOUSING"), Map.entry("spichlerz", "GRANARY"),
        Map.entry("studnia", "WELL"), Map.entry("koszary", "BARRACKS"), Map.entry("kuznia", "SMITHY"), Map.entry("wieza", "TOWER"),
        Map.entry("mur", "WALL"), Map.entry("brama", "GATE"), Map.entry("targ", "MARKET"), Map.entry("kosciol", "CHURCH"),
        Map.entry("palac", "PALACE"), Map.entry("zamek", "CASTLE"), Map.entry("ratusz", "TOWNHALL"), Map.entry("karczma", "TAVERN"),
        Map.entry("mlyn", "MILL"), Map.entry("pole", "FARM"), Map.entry("stajnia", "STABLE"));
    private static final int COORD_MAX = 29_999_000;

    private final PanelPlugin plugin;

    NpcV2Methods(final PanelPlugin plugin) {
        this.plugin = plugin;
    }

    Object invoke(final Call c) {
        return switch (c.method) {
            case "admin.npc.history" -> this.history(c);
            case "admin.npc.relations" -> this.relations(c);
            case "admin.npc.setOpinion" -> this.setOpinion(c);
            case "admin.npc.setStance" -> this.setStance(c);
            case "admin.npc.propose" -> this.propose(c);
            case "admin.npc.declareWar" -> this.declareWar(c);
            case "admin.npc.demandTribute" -> this.demandTribute(c);
            case "admin.npc.economy" -> this.economy(c);
            case "admin.npc.setBudget" -> this.setBudget(c);
            case "admin.npc.food" -> this.food(c);
            case "admin.npc.settlers" -> this.settlers(c);
            case "admin.npc.buildings" -> this.buildings(c);
            case "admin.npc.order" -> this.order(c);
            case "admin.npc.develop" -> this.develop(c);
            case "admin.npc.queue" -> this.queue(c);
            case "admin.npc.army" -> this.army(c);
            case "admin.npc.setPosture" -> this.setPosture(c);
            case "admin.npc.setSiegeTarget" -> this.setSiegeTarget(c);
            case "admin.npc.log" -> this.log(c);
            case "admin.npc.treasury" -> this.treasury(c);
            case "admin.npc.disband" -> this.disband(c);
            case "admin.npc.setTaxMultiplier" -> this.setTaxMultiplier(c);
            default -> throw new RpcException("Nieznana metoda: " + c.method);
        };
    }

    // ------------------------------------------------------------------ helpers

    private KrajeApi kraje() {
        return this.plugin.kraje();
    }

    private ArmiaApi armia() {
        final ArmiaApi a = this.plugin.armia();
        if (a == null) {
            throw new RpcException("Plugin Armia nie jest dostępny.");
        }
        return a;
    }

    private LudnoscApi lud() {
        final LudnoscApi l = this.plugin.ludnosc();
        if (l == null) {
            throw new RpcException("Plugin Ludnosc nie jest dostępny.");
        }
        return l;
    }

    private static <T> T compat(final Supplier<T> call) {
        try {
            return call.get();
        } catch (final LinkageError e) {
            throw new RpcException("Zainstalowana wersja pluginu nie obsługuje tej funkcji — zaktualizuj ją.");
        }
    }

    private static <T> T quiet(final Supplier<T> call, final T fallback) {
        try {
            final T v = call.get();
            return v == null ? fallback : v;
        } catch (final LinkageError | RuntimeException e) {
            return fallback;
        }
    }

    private long balance(final KrajInfo k) {
        return this.plugin.skarbiec().balance(k.treasuryAccount());
    }

    private String money(final long v) {
        return this.plugin.skarbiec().format(v);
    }

    /** {@code {id,name,color}} or null. */
    Map<String, Object> ref(final UUID id) {
        if (id == null) {
            return null;
        }
        final KrajInfo k = this.kraje().getCountry(id).orElse(null);
        return k == null ? null : Json.obj("id", k.id().toString(), "name", k.name(), "color", k.hexColor());
    }

    private Map<String, Object> refNpc(final UUID id) {
        final KrajInfo k = this.kraje().getCountry(id).orElse(null);
        return Json.obj("id", id.toString(), "name", k == null ? "?" : k.name(), "color", k == null ? "#808080" : k.hexColor(), "npc", k != null && k.npc());
    }

    private KrajInfo other(final Call c, final String param) {
        final UUID id = c.params.uuid(param);
        if (id.equals(c.target.id())) {
            throw new RpcException("Nieprawidłowy kraj docelowy.");
        }
        return this.kraje().getCountry(id).orElseThrow(() -> new RpcException("Nieprawidłowy kraj docelowy."));
    }

    private void aiLog(final Call c, final String category, final String text) {
        final ArmiaApi a = this.plugin.armia();
        if (a != null) {
            quiet(() -> {
                a.logNpcDecision(c.target.id(), category, "Panel (" + c.actor.name() + "): " + text);
                return Boolean.TRUE;
            }, Boolean.FALSE);
        }
    }

    private static Object goalJson(final NpcGoal g, final NpcV2Methods m) {
        if (g == null) {
            return null;
        }
        return Json.obj("kind", g.kind(), "text", g.text(), "target", m.ref(g.target()), "progress", g.progress());
    }

    // ------------------------------------------------------------------ admin.npc.history

    private Object history(final Call c) {
        final KrajInfo k = c.target;
        final int days = c.params.has("days") ? (int) Math.max(1, Math.min(7, c.params.integer("days", -1_000_000L, 1_000_000L))) : 7;
        final long now = System.currentTimeMillis();
        final long from = now - days * HistoryLog.DAY;
        final List<Map<String, Object>> points = new ArrayList<>();
        final List<HistoryLog.Point> stored = this.plugin.history().points(k.id(), from, days * 24 + 1);
        for (final HistoryLog.Point p : stored) {
            points.add(point(p));
        }
        if (!points.isEmpty()) {
            points.add(point(this.plugin.history().sample(k, now))); // live value as the last point
        }
        return Json.obj("id", k.id().toString(), "days", days, "intervalMs", HistoryLog.HOUR, "from", from, "to", now, "points", points,
            "current", this.current(k));
    }

    private static Map<String, Object> point(final HistoryLog.Point p) {
        return Json.obj("t", p.t(), "treasury", p.treasury(), "population", p.population(), "soldiers", p.soldiers(), "chunks", p.chunks(), "food", p.food());
    }

    private Map<String, Object> current(final KrajInfo k) {
        final ArmiaApi armia = this.plugin.armia();
        final LudnoscApi lud = this.plugin.ludnosc();
        final Optional<NpcSettings> s = armia == null ? Optional.empty() : quiet(() -> armia.npcSettings(k.id()), Optional.<NpcSettings>empty());
        final Optional<LudnoscInfo> info = lud == null ? Optional.empty() : quiet(() -> lud.info(k.id()), Optional.<LudnoscInfo>empty());
        final Optional<LudnoscApi.SettlementTier> tier = lud == null ? Optional.empty() : quiet(() -> lud.settlementTier(k.id()), Optional.<LudnoscApi.SettlementTier>empty());
        final NpcGoal goal = armia == null ? null : quiet(() -> armia.aiGoalDetails(k.id()), Optional.<NpcGoal>empty()).orElse(null);
        return Json.obj(
            "treasury", this.balance(k),
            "population", lud == null ? null : info.map(i -> (Object) i.population()).orElseGet(() -> quiet(() -> lud.population(k.id()), null)),
            "housing", lud == null ? null : info.map(i -> (Object) i.housing()).orElseGet(() -> quiet(() -> lud.housing(k.id()), null)),
            "soldiers", armia == null ? null : quiet(() -> armia.soldierCount(k.id()), null),
            "maxSoldiers", s.map(NpcSettings::maxSoldiers).orElse(null),
            "chunks", k.chunkCount(),
            "maxChunks", s.map(NpcSettings::maxChunks).orElse(null),
            "food", info.map(LudnoscInfo::food).orElse(null),
            "foodCapacity", info.map(LudnoscInfo::foodCapacity).orElse(null),
            "tier", tier.map(LudnoscApi.SettlementTier::level).orElse(null),
            "tierName", tier.map(LudnoscApi.SettlementTier::name).orElse(null),
            "goal", goalJson(goal, this));
    }

    // ------------------------------------------------------------------ relations

    private long warCost() {
        final org.bukkit.plugin.Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
        return kraje == null ? 0 : Math.max(0, kraje.getConfig().getLong("war.declare-cost", 0));
    }

    private Object relations(final Call c) {
        final KrajInfo k = c.target;
        final ArmiaApi armia = this.plugin.armia();
        final Optional<UUID> overlord = quiet(() -> this.kraje().getOverlord(k.id()), Optional.<UUID>empty());
        final Set<UUID> vassals = quiet(() -> this.kraje().getVassals(k.id()), Set.<UUID>of());
        final List<KrajInfo> all = new ArrayList<>(this.kraje().getCountries());
        all.sort(Comparator.comparing(KrajInfo::name, String.CASE_INSENSITIVE_ORDER));
        final List<Map<String, Object>> countries = new ArrayList<>();
        for (final KrajInfo o : all) {
            if (o.id().equals(k.id())) {
                continue;
            }
            final String relation = vassals.contains(o.id()) ? "VASSAL"
                : overlord.isPresent() && overlord.get().equals(o.id()) ? "SUZERAIN"
                : this.kraje().getRelation(k.id(), o.id()).name();
            Object pending = null;
            Object tribute = null;
            int opinion = 0;
            String stance = "AUTO";
            if (armia != null) {
                opinion = quiet(() -> armia.opinion(k.id(), o.id()), 0);
                stance = quiet(() -> armia.stance(k.id(), o.id()).name(), "AUTO");
                final Optional<NpcProposal> p = quiet(() -> armia.pendingProposal(k.id(), o.id()), Optional.<NpcProposal>empty());
                if (p.isPresent()) {
                    pending = Json.obj("relation", p.get().relation(), "direction", p.get().outgoing() ? "OUT" : "IN", "at", p.get().at());
                }
                final Optional<NpcTribute> t = quiet(() -> armia.tribute(k.id(), o.id()), Optional.<NpcTribute>empty());
                if (t.isPresent()) {
                    tribute = Json.obj("amount", t.get().amount(), "direction", t.get().pays() ? "PAYS" : "RECEIVES", "until", t.get().until());
                }
            }
            countries.add(Json.obj("id", o.id().toString(), "name", o.name(), "color", o.hexColor(), "npc", o.npc(), "ruler", o.rulerName(),
                "soldiers", armia == null ? null : quiet(() -> armia.soldierCount(o.id()), null), "chunks", o.chunkCount(),
                "relation", relation, "opinion", opinion, "stance", stance, "pending", pending, "tribute", tribute));
        }
        final long tributeMax = armia == null ? 100_000L : quiet(armia::tributeMax, 0L);
        return Json.obj("id", k.id().toString(), "warCost", this.warCost(), "tributeMax", tributeMax > 0 ? tributeMax : 100_000L, "countries", countries);
    }

    private Object setOpinion(final Call c) {
        final KrajInfo o = this.other(c, "target");
        final int value = c.params.integer("value", -100, 100);
        final ArmiaApi armia = this.armia();
        PanelMethods.check(compat(() -> armia.setOpinion(c.target.id(), o.id(), value)));
        return Json.obj("ok", true, "opinion", value);
    }

    private Object setStance(final Call c) {
        final KrajInfo o = this.other(c, "target");
        final String stance = c.params.oneOf("stance", STANCES);
        final ArmiaApi armia = this.armia();
        PanelMethods.check(compat(() -> armia.setStance(c.target.id(), o.id(), stance)));
        return Json.obj("ok", true, "stance", stance);
    }

    static Map<String, Object> reply(final NpcReply r) {
        if (r == null || !r.ok()) {
            throw new RpcException(PanelMethods.text(r == null ? null : r.message(), "Operacja nie powiodła się."));
        }
        return Json.obj("ok", true, "message", r.message(), "accepted", r.accepted());
    }

    private Object propose(final Call c) {
        final KrajInfo o = this.other(c, "target");
        final Relation rel = Relation.valueOf(c.params.oneOf("relation", List.of("ALLIANCE", "NEUTRAL")));
        final ArmiaApi armia = this.armia();
        return reply(compat(() -> armia.npcPropose(c.target.id(), o.id(), rel)));
    }

    private Object declareWar(final Call c) {
        final KrajInfo o = this.other(c, "target");
        final ArmiaApi armia = this.armia();
        final long before = this.balance(c.target);
        final KrajeResult r = PanelMethods.check(compat(() -> armia.npcDeclareWar(c.target.id(), o.id())));
        final long cost = Math.max(0, before - this.balance(c.target));
        c.notice(o.id(), c.target.name(), "");
        return Json.obj("ok", true, "message", PanelMethods.text(r.message(), c.target.name() + " wypowiada wojnę: " + o.name()), "cost", cost);
    }

    private Object demandTribute(final Call c) {
        final KrajInfo o = this.other(c, "target");
        final ArmiaApi armia = this.armia();
        final long max = Math.max(1, quiet(armia::tributeMax, 100_000L));
        final long amount = c.params.integer("amount", 1L, max);
        return reply(compat(() -> armia.demandTribute(c.target.id(), o.id(), amount)));
    }

    // ------------------------------------------------------------------ economy

    /** Treasury-history type of a ledger flow (codes the SPA knows: tax, income, wages, upkeep, claim, war, siege, build...). */
    static String txType(final String key, final String note) {
        final String n = note == null ? "" : note.toLowerCase(Locale.ROOT);
        return switch (key == null ? "" : key) {
            case "npcTax" -> "tax";
            case "aiIncome" -> "income";
            case "wages" -> "wages";
            case "land" -> n.startsWith("zajęcie") ? "claim" : "upkeep";
            case "war" -> n.startsWith("oblężenie") ? "siege" : "war";
            case "buildings" -> "build";
            case "recruit" -> "recruit";
            case "tribute" -> "tribute";
            default -> "other";
        };
    }

    private Object economy(final Call c) {
        final KrajInfo k = c.target;
        final ArmiaApi armia = this.armia();
        final Optional<NpcEconomy> eco = quiet(() -> armia.economyBreakdown(k.id()), Optional.<NpcEconomy>empty());
        final long now = System.currentTimeMillis();
        final List<Map<String, Object>> income = new ArrayList<>();
        final List<Map<String, Object>> expenses = new ArrayList<>();
        long it = 0;
        long et = 0;
        final List<Map<String, Object>> log = new ArrayList<>();
        if (eco.isPresent()) {
            for (final String key : INCOME_KEYS) {
                final long a = eco.get().income().getOrDefault(key, 0L);
                if (a > 0) {
                    income.add(Json.obj("key", key, "label", LABELS.get(key), "amount", a));
                    it += a;
                }
            }
            for (final String key : EXPENSE_KEYS) {
                final long a = eco.get().expenses().getOrDefault(key, 0L);
                if (a > 0) {
                    expenses.add(Json.obj("key", key, "label", LABELS.get(key), "amount", a));
                    et += a;
                }
            }
            for (final NpcEconomy.Flow f : eco.get().log()) {
                if (log.size() >= 200) {
                    break;
                }
                log.add(Json.obj("time", f.time(), "type", txType(f.key(), f.note()), "amount", f.amount(), "amountText", this.money(Math.abs(f.amount())),
                    "actor", f.actor(), "note", f.note()));
            }
        } else {
            for (final TreasuryLogEntry e : this.kraje().treasuryLog(k.id(), 50)) {
                log.add(Json.obj("time", e.time(), "type", e.type(), "amount", e.amount(), "amountText", this.money(Math.abs(e.amount())), "actor", e.actor(), "note", ""));
            }
        }
        final NpcBudget b = quiet(() -> armia.budgetShares(k.id()), Optional.<NpcBudget>empty()).orElse(new NpcBudget(45, 35, 20, false));
        final Optional<NpcSettings> s = quiet(() -> armia.npcSettings(k.id()), Optional.<NpcSettings>empty());
        final Integer wealth = s.map(NpcSettings::wealth).orElse(null);
        return Json.obj("id", k.id().toString(), "balance", this.balance(k), "from", eco.map(NpcEconomy::from).orElse(now - HistoryLog.DAY),
            "to", eco.map(NpcEconomy::to).orElse(now), "income", income, "expenses", expenses, "incomeTotal", it, "expensesTotal", et, "net", it - et,
            "budget", Json.obj("army", b.army(), "buildings", b.buildings(), "reserve", b.reserve(),
                "research", quiet(() -> armia.researchShare(k.id()), -1) < 0 ? null : quiet(() -> armia.researchShare(k.id()), -1)),
            "wealth", wealth, "incomeMultiplier", wealth == null ? null : Math.round((0.5 + wealth / 100.0) * 100) / 100.0,
            "taxMultiplier", quiet(() -> armia.taxMultiplier(k.id()), 100), "log", log);
    }

    private Object setBudget(final Call c) {
        final int army = c.params.integer("army", 0, 100);
        final int buildings = c.params.integer("buildings", 0, 100);
        final int reserve = c.params.integer("reserve", 0, 100);
        // research: optional percent; absent / null / "auto" = Postep decides (then the other three sum to 100)
        final boolean auto = c.params.absentOrEmpty("research") || (c.params.has("research") && "auto".equalsIgnoreCase(String.valueOf(c.params.scalar("research"))));
        final int research = auto ? -1 : c.params.integer("research", 0, 100);
        final int sum = army + buildings + reserve + Math.max(0, research);
        if (sum != 100) {
            throw new RpcException("Podział budżetu musi sumować się do 100% (jest " + sum + "%).");
        }
        final ArmiaApi armia = this.armia();
        PanelMethods.check(compat(() -> research < 0 ? armia.setBudgetShares(c.target.id(), army, buildings, reserve)
            : armia.setBudgetShares(c.target.id(), army, buildings, research, reserve)));
        return Json.obj("ok", true, "budget", Json.obj("army", army, "buildings", buildings, "reserve", reserve, "research", research < 0 ? null : research));
    }

    private Object treasury(final Call c) {
        final long delta = c.params.integer("delta", -1_000_000_000_000L, 1_000_000_000_000L);
        if (delta == 0) {
            throw Params.bad("delta", "kwota różna od zera (md)");
        }
        final String account = c.target.treasuryAccount();
        final long balance = this.plugin.skarbiec().balance(account);
        if (balance + delta < 0) {
            throw new RpcException("Skarbiec nie może zejść poniżej zera (jest " + this.money(balance) + ").");
        }
        final String reason = "Panel: korekta skarbca NPC (" + c.actor.name() + ")";
        final TxResult r = delta > 0 ? this.plugin.skarbiec().deposit(account, delta, reason) : this.plugin.skarbiec().withdraw(account, -delta, reason);
        if (r == null || !r.ok()) {
            throw new RpcException("Operacja na skarbcu nie powiodła się (" + r + ").");
        }
        this.aiLog(c, "ECONOMY", "korekta skarbca " + (delta > 0 ? "+" : "−") + this.money(Math.abs(delta)));
        final long after = this.plugin.skarbiec().balance(account);
        return Json.obj("ok", true, "balance", after, "balanceText", this.money(after));
    }

    private Object setTaxMultiplier(final Call c) {
        final String param = c.params.has("percent") ? "percent" : "value";
        final int percent = c.params.integer(param, 0, 300);
        final ArmiaApi armia = this.armia();
        PanelMethods.check(compat(() -> armia.setTaxMultiplier(c.target.id(), percent)));
        return Json.obj("ok", true, "taxMultiplier", percent);
    }

    // ------------------------------------------------------------------ population

    private Object food(final Call c) {
        final long delta = c.params.integer("delta", -100_000L, 100_000L);
        if (delta == 0) {
            throw Params.bad("delta", "liczba różna od zera");
        }
        final LudnoscApi lud = this.lud();
        final UUID id = c.target.id();
        final LudnoscInfo before = compat(() -> lud.info(id)).orElseThrow(() -> new RpcException("Ludnosc nie ma danych o spichlerzu tego kraju."));
        if (!compat(() -> lud.adjustFood(id, delta))) {
            throw new RpcException("Nie udało się zmienić zapasów jedzenia (Ludnosc odmówiła albo nie obsługuje tej funkcji).");
        }
        final LudnoscInfo after = compat(() -> lud.info(id)).orElse(before);
        this.aiLog(c, "ECONOMY", "jedzenie " + (delta > 0 ? "+" : "") + delta + " (stan " + after.food() + ")");
        return Json.obj("ok", true, "food", after.food(), "foodCapacity", after.foodCapacity(), "applied", after.food() - before.food());
    }

    private Object settlers(final Call c) {
        final int delta = c.params.integer("delta", -500, 500);
        if (delta == 0) {
            throw Params.bad("delta", "liczba różna od zera");
        }
        final LudnoscApi lud = this.lud();
        final UUID id = c.target.id();
        final int applied = compat(() -> lud.adjustSettlers(id, delta));
        if (applied == 0) {
            throw new RpcException(delta < 0 ? "Nie ma kogo usunąć (ludność 0)." : "Nie udało się dodać osadników (Ludnosc odmówiła albo nie obsługuje tej funkcji).");
        }
        this.aiLog(c, "OTHER", "osadnicy " + (applied > 0 ? "+" : "") + applied);
        return Json.obj("ok", true, "population", compat(() -> lud.population(id)), "housing", compat(() -> lud.housing(id)), "applied", applied);
    }

    // ------------------------------------------------------------------ buildings

    private Map<String, LudnoscApi.BuildingRequirement> requirements(final LudnoscApi lud, final UUID id) {
        final Map<String, LudnoscApi.BuildingRequirement> out = new LinkedHashMap<>();
        for (final LudnoscApi.BuildingRequirement r : quiet(() -> lud.buildingRequirements(id), List.<LudnoscApi.BuildingRequirement>of())) {
            if (r != null && r.type() != null) {
                out.put(r.type().toLowerCase(Locale.ROOT), r);
            }
        }
        return out;
    }

    static boolean autoOrder(final String orderedBy) {
        if (orderedBy == null) {
            return true;
        }
        final String o = orderedBy.trim().toLowerCase(Locale.ROOT);
        return o.isEmpty() || o.equals("ai") || o.startsWith("planer") || o.startsWith("planner") || o.equals("auto");
    }

    private List<Map<String, Object>> queueJson(final LudnoscApi lud, final UUID id, final Map<String, LudnoscApi.BuildingRequirement> reqs) {
        final List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (final LudnoscApi.QueuedOrderInfo q : quiet(() -> lud.queue(id), List.<LudnoscApi.QueuedOrderInfo>of())) {
            final LudnoscApi.BuildingRequirement r = reqs.get(q.type() == null ? "" : q.type().toLowerCase(Locale.ROOT));
            final boolean auto = autoOrder(q.orderedBy());
            out.add(Json.obj("index", i++, "type", q.type(), "name", r == null ? q.type() : r.displayName(), "cost", q.cost(), "auto", auto,
                "x", auto ? null : q.x(), "z", auto ? null : q.z(), "rotation", q.rotation(),
                "orderedBy", q.orderedBy() == null ? "AI" : q.orderedBy(), "orderedAt", q.orderedAt()));
        }
        return out;
    }

    /** Next settlement tier with what is missing (Ludnosc's default thresholds; a server config may differ). */
    static Map<String, Object> nextTier(final int level, final int population, final long treasury) {
        if (level < 1 || level >= TIER_NAMES.length) {
            return null;
        }
        final List<String> missing = new ArrayList<>();
        if (population < TIER_POPULATION[level]) {
            missing.add(TIER_POPULATION[level] + " mieszkańców (jest " + population + ")");
        }
        if (treasury < TIER_TREASURY[level]) {
            missing.add("Skarbiec " + TIER_TREASURY[level] / 100 + " zł (jest " + treasury / 100 + " zł)");
        }
        return Json.obj("tier", level + 1, "name", TIER_NAMES[level], "missing", missing);
    }

    Object buildings(final Call c) {
        final KrajInfo k = c.target;
        final LudnoscApi lud = this.lud();
        final UUID id = k.id();
        final Optional<LudnoscInfo> info = quiet(() -> lud.info(id), Optional.<LudnoscInfo>empty());
        final Optional<LudnoscApi.SettlementTier> tier = quiet(() -> lud.settlementTier(id), Optional.<LudnoscApi.SettlementTier>empty());
        final Map<String, LudnoscApi.BuildingRequirement> reqs = this.requirements(lud, id);
        final long balance = this.balance(k);
        final int population = info.map(LudnoscInfo::population).orElseGet(() -> quiet(() -> lud.population(id), 0));
        final List<Map<String, Object>> buildings = new ArrayList<>();
        final Map<String, String> functions = new HashMap<>(FUNCTIONS);
        for (final LudnoscInfo.Building b : info.map(LudnoscInfo::buildings).orElse(List.of())) {
            final String fn = b.function() == null ? "" : b.function();
            buildings.add(Json.obj("type", b.type(), "name", b.name(), "function", fn, "beds", b.beds(), "world", b.world(),
                "x", b.x(), "y", b.y(), "z", b.z(), "demolish", fn.contains("do rozbiórki")));
        }
        final List<Map<String, Object>> sites = new ArrayList<>();
        for (final LudnoscInfo.Site s : info.map(LudnoscInfo::sites).orElse(List.of())) {
            sites.add(Json.obj("type", s.type(), "name", s.name(), "percent", s.percent(), "placed", null, "total", null, "world", s.world(),
                "x", s.x(), "y", s.y(), "z", s.z(), "startedAt", null));
        }
        final List<Map<String, Object>> types = new ArrayList<>();
        for (final LudnoscApi.BuildingRequirement r : reqs.values()) {
            final List<String> missing = r.missing() == null ? List.of() : r.missing();
            final pl.medivalrpg.panel.plan.BuildingCatalog.Entry size = pl.medivalrpg.panel.plan.BuildingCatalog.of(r.type());
            types.add(Json.obj("type", r.type(), "name", r.displayName(), "function", functions.get(r.type().toLowerCase(Locale.ROOT)),
                "size", Json.obj("w", size.width(), "d", size.depth(), "source", pl.medivalrpg.panel.plan.BuildingCatalog.known(r.type()) ? "catalog" : "default"),
                "category", size.category(),
                "description", "", "cost", r.cost(), "costText", this.money(r.cost()), "beds", null, "minTier", null, "count", r.built(),
                "max", r.max() < 0 ? null : r.max(), "available", r.available() && missing.isEmpty(),
                "missing", r.available() ? missing : missing.isEmpty() ? List.of("Wymagania niespełnione") : missing));
        }
        final ArmiaApi armia = this.plugin.armia();
        final int share = armia == null ? 35 : quiet(() -> armia.budgetShares(id), Optional.<NpcBudget>empty()).map(NpcBudget::buildings).orElse(35);
        return Json.obj("id", id.toString(),
            "tier", tier.map(LudnoscApi.SettlementTier::level).orElse(null),
            "tierName", tier.map(LudnoscApi.SettlementTier::name).orElse(null),
            "nextTier", tier.map(t -> nextTier(t.level(), population, balance)).orElse(null),
            "buildings", buildings, "sites", sites, "queue", this.queueJson(lud, id, reqs), "types", types,
            "develop", Json.obj("suggested", Math.min(balance, Math.round(balance * share / 100.0)), "max", Math.max(0, balance)));
    }

    static String orderError(final String result) {
        return switch (result) {
            case "UNKNOWN_BUILDING" -> "Nieznany typ budynku albo Ludnosc nie obsługuje tej funkcji.";
            case "NOT_ON_OWN_LAND" -> "To miejsce nie leży na ziemi kraju.";
            case "AREA_OBSTRUCTED" -> "Brak wolnego miejsca na ten budynek.";
            case "INSUFFICIENT_FUNDS" -> "Za mało pieniędzy w skarbcu.";
            case "TOO_MANY_ORDERS" -> "Kolejka budów jest pełna.";
            default -> "Zamówienie nie powiodło się (" + result + ").";
        };
    }

    private Object order(final Call c) {
        final KrajInfo k = c.target;
        final LudnoscApi lud = this.lud();
        final String type = c.params.matching("type", Params.ID, "identyfikator budynku (np. dom)");
        final Map<String, LudnoscApi.BuildingRequirement> reqs = this.requirements(lud, k.id());
        final LudnoscApi.BuildingRequirement req = reqs.get(type.toLowerCase(Locale.ROOT));
        if (req == null) {
            throw new RpcException("Nieznany typ budynku: " + type + ".");
        }
        if (!req.available()) {
            final List<String> missing = req.missing() == null || req.missing().isEmpty() ? List.of("wymagania") : req.missing();
            throw new RpcException("Nie można zamówić: brakuje — " + String.join(", ", missing));
        }
        final boolean hasX = c.params.has("x");
        if (hasX != c.params.has("z")) {
            throw new RpcException("Podaj obie współrzędne x i z albo żadnej.");
        }
        final int rotation = c.params.has("rotation") ? c.params.integer("rotation", 0, 270) : 0;
        if (rotation % 90 != 0) {
            throw Params.bad("rotation", "0, 90, 180 albo 270");
        }
        final String result;
        Integer x = null;
        Integer z = null;
        if (hasX) {
            x = c.params.integer("x", -COORD_MAX, COORD_MAX);
            z = c.params.integer("z", -COORD_MAX, COORD_MAX);
            final World w = Bukkit.getWorld(k.capitalWorld());
            if (w == null) {
                throw new RpcException("Świat stolicy nie jest załadowany.");
            }
            final Location at = new Location(w, x, w.getHighestBlockYAt(x, z) + 1, z);
            result = compat(() -> lud.orderBuilding(k.id(), req.type(), at, rotation)).name();
        } else {
            result = compat(() -> lud.orderBuildingAuto(k.id(), req.type())).name();
        }
        if (!"OK".equals(result)) {
            throw new RpcException(orderError(result));
        }
        this.aiLog(c, "BUILD", "zamówiono " + req.displayName() + (hasX ? " (x " + x + ", z " + z + ")" : " (miejsce wybierze planer)"));
        final List<Map<String, Object>> queue = this.queueJson(lud, k.id(), reqs);
        Map<String, Object> queued = null;
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (req.type().equalsIgnoreCase(String.valueOf(queue.get(i).get("type")))) {
                queued = queue.get(i);
                break;
            }
        }
        final Object index = queued == null ? null : queued.get("index");
        final String message = req.displayName() + (index == null ? " — budowa rozpoczęta" : " dodano do kolejki (pozycja " + ((Integer) index + 1) + ")");
        return Json.obj("ok", true, "message", message, "queued", Json.obj("index", index, "type", req.type(), "name", req.displayName(),
            "auto", !hasX, "x", x, "z", z));
    }

    private Object develop(final Call c) {
        final KrajInfo k = c.target;
        final LudnoscApi lud = this.lud();
        final long before = this.balance(k);
        if (before < 1) {
            throw new RpcException("Skarbiec jest pusty.");
        }
        final long budget = c.params.integer("budget", 1L, before);
        final Map<String, LudnoscApi.BuildingRequirement> reqs = this.requirements(lud, k.id());
        final Map<String, Integer> was = this.orderCounts(lud, k.id());
        final String result = compat(() -> lud.developSettlement(k.id(), budget)).name();
        if (!"OK".equals(result)) {
            throw new RpcException(orderError(result));
        }
        final long spent = Math.max(0, before - this.balance(k));
        final List<Map<String, Object>> ordered = new ArrayList<>();
        for (final Map.Entry<String, Integer> e : this.orderCounts(lud, k.id()).entrySet()) {
            final int added = e.getValue() - was.getOrDefault(e.getKey(), 0);
            final LudnoscApi.BuildingRequirement r = reqs.get(e.getKey());
            for (int i = 0; i < added; i++) {
                ordered.add(Json.obj("type", e.getKey(), "name", r == null ? e.getKey() : r.displayName(), "cost", r == null ? null : r.cost()));
            }
        }
        final String names = ordered.isEmpty() ? "zamówienie przyjęte" : String.join(", ", ordered.stream().map(o -> String.valueOf(o.get("name"))).toList());
        this.aiLog(c, "BUILD", "rozbudowa osady (budżet " + this.money(budget) + "): " + names);
        return Json.obj("ok", true, "message", "Planer zamówił: " + names, "ordered", ordered, "spent", spent);
    }

    /** Queued orders + construction sites per type (to see what the planner just ordered). */
    private Map<String, Integer> orderCounts(final LudnoscApi lud, final UUID id) {
        final Map<String, Integer> out = new HashMap<>();
        for (final LudnoscApi.QueuedOrderInfo q : quiet(() -> lud.queue(id), List.<LudnoscApi.QueuedOrderInfo>of())) {
            out.merge(q.type().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        for (final LudnoscInfo.Site s : quiet(() -> lud.info(id), Optional.<LudnoscInfo>empty()).map(LudnoscInfo::sites).orElse(List.of())) {
            out.merge(s.type().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return out;
    }

    private Object queue(final Call c) {
        final KrajInfo k = c.target;
        final LudnoscApi lud = this.lud();
        final String action = c.params.oneOf("action", List.of("up", "down", "cancel"));
        final int index = c.params.integer("index", 0, 10_000);
        final int size = compat(() -> lud.queue(k.id())).size();
        if (index >= size) {
            throw new RpcException("Nie ma takiej pozycji w kolejce.");
        }
        final boolean ok;
        if ("cancel".equals(action)) {
            ok = compat(() -> lud.cancelQueued(k.id(), index));
        } else {
            final int delta = "up".equals(action) ? -1 : 1;
            if (index + delta < 0 || index + delta >= size) {
                throw new RpcException("Nie można przesunąć dalej.");
            }
            ok = compat(() -> lud.moveQueue(k.id(), index, delta));
        }
        if (!ok) {
            throw new RpcException("Ludnosc odmówiła zmiany kolejki.");
        }
        this.aiLog(c, "BUILD", "kolejka: " + action + " #" + (index + 1));
        return Json.obj("ok", true, "queue", this.queueJson(lud, k.id(), this.requirements(lud, k.id())));
    }

    // ------------------------------------------------------------------ army

    private Object army(final Call c) {
        final KrajInfo k = c.target;
        final ArmiaApi armia = this.armia();
        final UUID id = k.id();
        final Map<String, Integer> byType = quiet(() -> armia.soldiersByType(id), Map.<String, Integer>of());
        final List<Map<String, Object>> types = new ArrayList<>();
        for (final UnitTypeInfo u : quiet(armia::unitTypes, List.<UnitTypeInfo>of())) {
            types.add(Json.obj("id", u.id(), "name", u.name(), "description", u.description(), "cost", u.cost(), "wage", u.wage(),
                "count", byType.getOrDefault(u.id(), 0)));
        }
        final OptionalInt morale = quiet(() -> armia.morale(id), OptionalInt.empty());
        final List<Map<String, Object>> outposts = new ArrayList<>();
        for (final NpcOutpost o : quiet(() -> armia.outposts(id), List.<NpcOutpost>of())) {
            outposts.add(Json.obj("name", o.name(), "world", o.world(), "x", o.x(), "z", o.z(), "soldiers", o.soldiers()));
        }
        final List<Map<String, Object>> sieges = new ArrayList<>();
        for (final SiegeInfo s : this.kraje().getSieges()) {
            if (s.attacker().equals(id) || s.defender().equals(id)) {
                sieges.add(Json.obj("id", s.id().toString(), "attacker", this.refNpc(s.attacker()), "defender", this.refNpc(s.defender()),
                    "world", s.world(), "cx", s.cx(), "cz", s.cz(), "progress", s.progress(), "startedAt", s.startedAt(), "endsAt", s.endsAt()));
            }
        }
        return Json.obj("id", id.toString(),
            "total", quiet(() -> armia.soldierCount(id), 0),
            "max", quiet(() -> armia.npcSettings(id), Optional.<NpcSettings>empty()).map(NpcSettings::maxSoldiers).orElse(null),
            "morale", morale.isPresent() ? morale.getAsInt() : null,
            "wages", quiet(() -> armia.dailyWages(id), 0L),
            "types", types,
            "posture", quiet(() -> armia.posture(id).map(Enum::name).orElse("BALANCED"), "BALANCED"),
            "siegeTarget", this.ref(quiet(() -> armia.siegeTarget(id), Optional.<UUID>empty()).orElse(null)),
            "autoTarget", this.ref(quiet(() -> armia.autoSiegeTarget(id), Optional.<UUID>empty()).orElse(null)),
            "outposts", outposts,
            "mercenaries", this.mercenaries(armia, id),
            "sieges", sieges);
    }

    private List<Map<String, Object>> mercenaries(final ArmiaApi armia, final UUID id) {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final pl.medivalrpg.armia.api.NpcMercenary m : quiet(() -> armia.mercenaries(id), List.<pl.medivalrpg.armia.api.NpcMercenary>of())) {
            out.add(Json.obj("type", m.type(), "name", m.name(), "count", m.count(), "contractUntil", m.contractUntil(), "wage", m.wage()));
        }
        return out;
    }

    private Object setPosture(final Call c) {
        final String posture = c.params.oneOf("posture", POSTURES);
        final ArmiaApi armia = this.armia();
        PanelMethods.check(compat(() -> armia.setPosture(c.target.id(), posture)));
        return Json.obj("ok", true, "posture", posture);
    }

    private Object setSiegeTarget(final Call c) {
        final ArmiaApi armia = this.armia();
        final UUID target = c.params.absentOrEmpty("target") ? null : this.other(c, "target").id();
        PanelMethods.check(compat(() -> armia.setSiegeTarget(c.target.id(), target)));
        return Json.obj("ok", true, "siegeTarget", this.ref(target));
    }

    private Object disband(final Call c) {
        final ArmiaApi armia = this.armia();
        final String type = c.params.matching("type", Params.ID, "identyfikator jednostki (np. piechota)");
        final int have = quiet(() -> armia.soldiersByType(c.target.id()), Map.<String, Integer>of()).getOrDefault(type, 0);
        if (have <= 0) {
            throw new RpcException("Brak żołnierzy tego typu.");
        }
        final int count = c.params.integer("count", 1, have);
        final KrajeResult r = PanelMethods.check(compat(() -> armia.disband(c.target.id(), type, count)));
        this.aiLog(c, "ARMY", "rozwiązano " + count + " × " + type);
        return Json.obj("ok", true, "message", r.message(), "count", count);
    }

    // ------------------------------------------------------------------ log

    private Object log(final Call c) {
        final ArmiaApi armia = this.armia();
        final int limit = c.params.has("limit") ? (int) Math.max(1, Math.min(500, c.params.integer("limit", -1_000_000L, 1_000_000L))) : 100;
        final String category = c.params.absentOrEmpty("category") ? "ALL" : c.params.oneOf("category", List.of("ALL", "WAR", "DIPLOMACY", "BUILD", "ARMY", "ECONOMY", "OTHER"));
        String query = c.params.absentOrEmpty("query") ? "" : c.params.string("query", 0, 64);
        if (query.length() > 64) {
            query = query.substring(0, 64);
        }
        final String q = query;
        final List<NpcLogRecord> byQuery = compat(() -> armia.npcLog(c.target.id(), null, q, 100_000));
        final Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("ALL", byQuery.size());
        for (final String cat : CATEGORIES) {
            counts.put(cat, byQuery.stream().filter(e -> e.category() != null && e.category().name().equals(cat)).count());
        }
        final List<NpcLogRecord> matched = "ALL".equals(category) ? byQuery
            : byQuery.stream().filter(e -> e.category() != null && e.category().name().equals(category)).toList();
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (final NpcLogRecord e : matched) {
            if (entries.size() >= limit) {
                break;
            }
            entries.add(Json.obj("time", e.time(), "category", e.category() == null ? "OTHER" : e.category().name(), "text", e.text()));
        }
        return Json.obj("id", c.target.id().toString(), "total", matched.size(), "counts", counts, "entries", entries);
    }
}
