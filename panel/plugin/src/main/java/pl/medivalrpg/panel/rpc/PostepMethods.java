// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.access.Access;
import pl.medivalrpg.panel.access.Requirement;
import pl.medivalrpg.panel.util.Json;
import pl.medivalrpg.postep.api.PostepApi;

/**
 * {@code postep.*} and {@code admin.postep.*} (PANEL.md "Postęp — epoki i odkrycia"). Postep is optional: reads answer
 * {@code available:false} without it, mutations fail with a Polish error. Called on the main thread after the
 * permission check by {@link RpcService}.
 */
final class PostepMethods {

    /** Status of a discovery for one country (tree in {@code postep.get}). */
    static final String OWNED = "OWNED";
    static final String AVAILABLE = "AVAILABLE";
    static final String LOCKED = "LOCKED";
    static final String ERA_LOCKED = "ERA_LOCKED";

    /** Amounts (copper) previewed in {@code postep.get.invest.presets}. */
    static final List<Long> PRESETS = List.of(100L, 500L, 1_000L, 5_000L);

    private final PanelPlugin plugin;

    PostepMethods(final PanelPlugin plugin) {
        this.plugin = plugin;
    }

    Object invoke(final Call c) {
        return switch (c.method) {
            case "postep.get" -> this.get(c);
            case "postep.investPreview" -> this.preview(c);
            case "postep.discover" -> this.discover(c);
            case "postep.invest" -> this.invest(c);
            case "admin.postep.setEra" -> this.setEra(c);
            case "admin.postep.points" -> this.points(c);
            case "admin.postep.grant" -> this.grant(c);
            default -> throw new RpcException("Nieznana metoda: " + c.method);
        };
    }

    private PostepApi require() {
        final PostepApi p = this.plugin.postep();
        if (p == null) {
            throw new RpcException("Plugin Postep nie jest dostępny.");
        }
        return p;
    }

    private static <T> T compat(final Supplier<T> call) {
        try {
            return call.get();
        } catch (final LinkageError e) {
            throw new RpcException("Zainstalowana wersja pluginu Postep nie obsługuje tej funkcji — zaktualizuj ją.");
        }
    }

    // ------------------------------------------------------------------ pure helpers (unit-tested)

    /** {@link #OWNED}, {@link #AVAILABLE} (era reached, every requirement owned), {@link #LOCKED} or {@link #ERA_LOCKED}. */
    static String status(final PostepApi.DiscoveryInfo d, final Set<String> owned, final int era) {
        if (owned.contains(d.id())) {
            return OWNED;
        }
        if (d.era() > era) {
            return ERA_LOCKED;
        }
        return owned.containsAll(d.requires()) ? AVAILABLE : LOCKED;
    }

    /** Not-owned discoveries needed for {@code target}, prerequisites first, ending with the target (cycle-safe). */
    static List<PostepApi.DiscoveryInfo> path(final List<PostepApi.DiscoveryInfo> tree, final String target, final Set<String> owned) {
        final Map<String, PostepApi.DiscoveryInfo> byId = new java.util.HashMap<>();
        tree.forEach(d -> byId.put(d.id(), d));
        final LinkedHashSet<String> order = new LinkedHashSet<>();
        collect(byId, target, owned, order, new HashSet<>());
        return order.stream().map(byId::get).toList();
    }

    private static void collect(final Map<String, PostepApi.DiscoveryInfo> byId, final String id, final Set<String> owned,
                                final LinkedHashSet<String> order, final Set<String> visiting) {
        final PostepApi.DiscoveryInfo d = byId.get(id);
        if (d == null || owned.contains(id) || order.contains(id) || !visiting.add(id)) {
            return;
        }
        for (final String r : d.requires()) {
            collect(byId, r, owned, order, visiting);
        }
        order.add(id);
    }

    static Map<String, Object> eraRef(final PostepApi p, final int era) {
        final PostepApi.EraInfo e = p.eraInfo(era);
        return Json.obj("id", e.id(), "name", e.name(), "years", e.yearsLabel(), "color", e.color(), "icon", p.eraIcon(e.id()),
            "description", e.description());
    }

    private static double round1(final double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // ------------------------------------------------------------------ reads

    /** {@code postep.get {id}} (public; {@code access} tells the page which actions to show). */
    Map<String, Object> get(final Call c) {
        final KrajInfo k = c.target;
        final PostepApi p = this.plugin.postep();
        if (p == null) {
            return Json.obj("id", k.id().toString(), "available", false);
        }
        final UUID id = k.id();
        final int era = p.era(id);
        final int maxEra = p.eras().size();
        final Set<String> owned = p.discoveries(id);
        final long points = p.researchPoints(id);
        final double daily = p.dailyResearch(id);
        final List<PostepApi.DiscoveryInfo> tree = compat(p::discoveryTree);

        final List<Map<String, Object>> eras = new ArrayList<>();
        for (final PostepApi.EraInfo e : p.eras()) {
            eras.add(eraRef(p, e.id()));
        }
        final List<Map<String, Object>> nodes = new ArrayList<>();
        for (final PostepApi.DiscoveryInfo d : tree) {
            final String st = status(d, owned, era);
            nodes.add(Json.obj("id", d.id(), "name", d.name(), "era", d.era(), "cost", d.cost(), "requires", d.requires(),
                "requiredForEra", d.requiredForEra(), "category", d.category(), "description", d.description(),
                "effect", d.effectText(), "status", st, "affordable", AVAILABLE.equals(st) && points >= d.cost()));
        }

        Map<String, Object> target = null;
        final Optional<String> targetId = compat(() -> p.researchTarget(id));
        if (targetId.isPresent()) {
            final List<PostepApi.DiscoveryInfo> path = path(tree, targetId.get(), owned);
            final long cost = path.stream().mapToLong(PostepApi.DiscoveryInfo::cost).sum();
            final long missing = Math.max(0, cost - points);
            final String name = tree.stream().filter(d -> d.id().equals(targetId.get())).map(PostepApi.DiscoveryInfo::name).findFirst().orElse(targetId.get());
            target = Json.obj("id", targetId.get(), "name", name, "cost", cost, "missing", missing,
                "etaDays", missing == 0 ? 0 : daily > 0.01 ? (long) Math.ceil(missing / daily) : null,
                "path", path.stream().map(d -> Json.obj("id", d.id(), "name", d.name(), "cost", d.cost())).toList());
        }

        final boolean canAct = Access.allowed(c.actor, Requirement.ADVISOR_OR_NPC_MANAGE, id, k.npc());
        final Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("available", true);
        out.put("era", eraRef(p, era));
        out.put("nextEra", era < maxEra ? eraRef(p, era + 1) : null);
        out.put("maxEra", era >= maxEra);
        out.put("eraSince", p.eraSince(id).orElse(null));
        out.put("progress", Math.round(p.progress(id) * 1000) / 1000.0);
        out.put("ready", era < maxEra && p.missingForNextEra(id).isEmpty() && p.progress(id) >= 1.0);
        out.put("missing", p.missingForNextEra(id));
        out.put("points", points);
        out.put("totalPoints", compat(() -> p.totalResearchPoints(id)));
        out.put("daily", round1(daily));
        out.put("discoveryCount", owned.size());
        out.put("discoveryTotal", tree.size());
        out.put("target", target);
        out.put("eras", eras);
        out.put("tree", nodes);
        if (canAct) {
            final long invested = compat(() -> p.investedToday(id));
            final List<Map<String, Object>> presets = new ArrayList<>();
            for (final long amount : PRESETS) {
                presets.add(Json.obj("amount", amount, "amountText", this.plugin.skarbiec().format(amount),
                    "points", round1(compat(() -> p.investPreview(id, amount)))));
            }
            final long balance = this.plugin.skarbiec().balance(k.treasuryAccount());
            out.put("invest", Json.obj("investedToday", invested, "investedTodayText", this.plugin.skarbiec().format(invested),
                "treasury", balance, "treasuryText", this.plugin.skarbiec().format(balance), "presets", presets));
        }
        out.put("access", Json.obj("discover", canAct, "invest", canAct, "admin", c.actor.admin()));
        out.put("ranking", this.ranking(p));
        return out;
    }

    /** All countries: era desc, progress desc, total points desc. */
    private List<Map<String, Object>> ranking(final PostepApi p) {
        record Row(KrajInfo k, int era, double progress, long total, int discoveries) {
        }
        final List<Row> rows = new ArrayList<>();
        for (final KrajInfo k : this.plugin.kraje().getCountries()) {
            rows.add(new Row(k, p.era(k.id()), p.progress(k.id()), compat(() -> p.totalResearchPoints(k.id())), p.discoveries(k.id()).size()));
        }
        rows.sort(Comparator.comparingInt(Row::era).reversed()
            .thenComparing(Comparator.comparingDouble(Row::progress).reversed())
            .thenComparing(Comparator.comparingLong(Row::total).reversed())
            .thenComparing(r -> r.k().name(), String.CASE_INSENSITIVE_ORDER));
        final List<Map<String, Object>> out = new ArrayList<>();
        int pos = 1;
        for (final Row r : rows) {
            final PostepApi.EraInfo e = p.eraInfo(r.era());
            out.add(Json.obj("pos", pos++, "id", r.k().id().toString(), "name", r.k().name(), "color", r.k().hexColor(), "npc", r.k().npc(),
                "era", r.era(), "eraName", e.name(), "eraIcon", p.eraIcon(r.era()), "eraColor", e.color(),
                "progress", Math.round(r.progress() * 1000) / 1000.0, "discoveries", r.discoveries(), "totalPoints", r.total()));
        }
        return out;
    }

    /** {@code postep.investPreview {id, amount}} → {@code {amount, amountText, points}}. */
    private Object preview(final Call c) {
        final PostepApi p = this.require();
        final long amount = c.params.amount("amount", false);
        final double pts = compat(() -> p.investPreview(c.target.id(), amount));
        return Json.obj("amount", amount, "amountText", this.plugin.skarbiec().format(amount), "points", round1(pts));
    }

    // ------------------------------------------------------------------ mutations

    private Object discover(final Call c) {
        final PostepApi p = this.require();
        final String discovery = c.params.matching("discovery", Params.ID, "identyfikator odkrycia (np. druk)");
        final PostepApi.DiscoverResult r = compat(() -> p.discover(c.target.id(), discovery, c.actor.uuid()));
        if (PostepApi.DiscoverResult.UNSUPPORTED.equals(r.status())) {
            throw new RpcException("Zainstalowana wersja pluginu Postep nie obsługuje tej funkcji — zaktualizuj ją.");
        }
        if (!r.ok()) {
            throw new RpcException(r.message());
        }
        final String name = p.discovery(discovery).map(PostepApi.DiscoveryInfo::name).orElse(discovery);
        c.notice(c.target.id(), name, PostepApi.DiscoverResult.UNLOCKED.equals(r.status()) ? "odkryto" : "cel badań");
        return Json.obj("ok", true, "status", r.status(), "discovery", r.discovery(), "missingPoints", Math.ceil(r.missingPoints()),
            "message", r.message(), "points", p.researchPoints(c.target.id()));
    }

    private Object invest(final Call c) {
        final PostepApi p = this.require();
        final long amount = c.params.amount("amount", false);
        final PostepApi.InvestResult r = compat(() -> p.invest(c.target.id(), amount, c.actor.uuid()));
        if (!r.ok()) {
            throw new RpcException(r.message() == null || r.message().isBlank() ? "Inwestycja nie powiodła się." : r.message());
        }
        final String paid = this.plugin.skarbiec().format(r.paid());
        c.notice(c.target.id(), "", paid + " (+" + round1(r.points()) + " pkt)");
        final long balance = this.plugin.skarbiec().balance(c.target.treasuryAccount());
        return Json.obj("ok", true, "paid", r.paid(), "paidText", paid, "points", round1(r.points()),
            "researchPoints", p.researchPoints(c.target.id()), "investedToday", compat(() -> p.investedToday(c.target.id())),
            "treasury", balance, "message", "Zainwestowano " + paid + ": +" + round1(r.points()) + " pkt badań.");
    }

    private Object setEra(final Call c) {
        final PostepApi p = this.require();
        final int era = c.params.integer("era", 1, Math.max(1, p.eras().size()));
        if (!compat(() -> p.setEra(c.target.id(), era))) {
            throw new RpcException("Nie udało się ustawić epoki.");
        }
        final String name = p.eraInfo(era).name();
        c.notice(c.target.id(), "", name);
        return Json.obj("ok", true, "era", era, "eraName", name, "message", "Ustawiono epokę: " + name + ".");
    }

    private Object points(final Call c) {
        final PostepApi p = this.require();
        final long delta = c.params.integer("delta", -1_000_000L, 1_000_000L);
        if (delta == 0) {
            throw new RpcException("Nieprawidłowy parametr delta: liczba różna od zera.");
        }
        if (!compat(() -> p.addResearchPoints(c.target.id(), delta))) {
            throw new RpcException("Nie udało się zmienić punktów.");
        }
        c.notice(c.target.id(), "", (delta > 0 ? "+" : "") + delta);
        return Json.obj("ok", true, "points", p.researchPoints(c.target.id()));
    }

    private Object grant(final Call c) {
        final PostepApi p = this.require();
        final String discovery = c.params.matching("discovery", Params.ID, "identyfikator odkrycia (np. druk)");
        if (p.discovery(discovery).isEmpty()) {
            throw new RpcException("Nie ma odkrycia: " + discovery + ".");
        }
        final List<String> granted = compat(() -> p.grantDiscovery(c.target.id(), discovery));
        if (granted.isEmpty()) {
            throw new RpcException("Kraj zna już to odkrycie.");
        }
        final List<String> names = granted.stream().map(id -> p.discovery(id).map(PostepApi.DiscoveryInfo::name).orElse(id)).toList();
        c.notice(c.target.id(), "", String.join(", ", names));
        return Json.obj("ok", true, "granted", granted, "message", "Nadano: " + String.join(", ", names) + ".");
    }
}
