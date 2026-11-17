// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.armia.api.NpcSettings;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;
import pl.medivalrpg.panel.PanelConfig;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.map.KrajeFiles;
import pl.medivalrpg.panel.map.MapRenderService;
import pl.medivalrpg.panel.plan.BuildingCatalog;
import pl.medivalrpg.panel.plan.ChunkGeometry;
import pl.medivalrpg.panel.plan.PlanValidator;
import pl.medivalrpg.panel.plan.TerritoryPlanner;
import pl.medivalrpg.panel.util.Json;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Plan editor of an NPC country (PANEL.md "Edytor planu państwa NPC"): map background, design plan (validated here,
 * stored and executed by Ludnosc) and the territory tool. Main thread, after the permission check (ADM, global NPC
 * manager, manager of {@code id}) and the NPC-country check done by {@link RpcService}.
 */
final class PlanMethods {

    static final List<String> READS = List.of("admin.npc.mapRender", "admin.npc.getPlan", "admin.npc.planPreview");
    static final List<String> WRITES = List.of("admin.npc.setPlan", "admin.npc.clearPlan", "admin.npc.territory");
    static final String UNSUPPORTED = "Zainstalowana wersja pluginu Ludnosc nie obsługuje planów państwa — zaktualizuj Ludnosc.";
    private static final int CHUNK_COORD_MAX = 1_875_000;
    /** Neighbour chunks shown around the rendered area. */
    private static final int OTHERS_RING = 8;

    private final PanelPlugin plugin;
    private final TerrainLevelTokens levelTokens = new TerrainLevelTokens();

    PlanMethods(final PanelPlugin plugin) {
        this.plugin = plugin;
    }

    Object invoke(final Call c) {
        return switch (c.method) {
            case "admin.npc.mapRender", "country.plan.mapRender" -> this.mapRender(c);
            case "admin.npc.getPlan", "country.plan.get" -> this.getPlan(c);
            case "admin.npc.setPlan", "country.plan.set" -> this.setPlan(c);
            case "admin.npc.clearPlan", "country.plan.clear" -> this.clearPlan(c);
            case "admin.npc.planPreview", "country.plan.preview" -> this.preview(c);
            case "admin.npc.territory" -> this.territory(c);
            case "country.plan.levelPreview" -> this.levelPreview(c);
            case "country.plan.level" -> this.level(c);
            default -> throw new RpcException("Nieznana metoda: " + c.method);
        };
    }

    // ------------------------------------------------------------------ helpers

    private PanelConfig cfg() {
        return this.plugin.cfg();
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
            throw new RpcException(UNSUPPORTED);
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

    /** Whether the installed Ludnosc overrides the design-plan methods (older ones only have the API defaults). */
    static boolean supportsPlans(final LudnoscApi lud) {
        if (lud == null) {
            return false;
        }
        try {
            return !lud.getClass().getMethod("setDesignPlan", UUID.class, String.class).getDeclaringClass().isInterface();
        } catch (final NoSuchMethodException | SecurityException | LinkageError e) {
            return false;
        }
    }

    private Set<Long> land(final KrajInfo k) {
        return new HashSet<>(this.plugin.kraje().getClaims(k.id(), k.capitalWorld()));
    }

    private Map<String, PlanValidator.TypeInfo> types(final LudnoscApi lud, final UUID id) {
        final Map<String, PlanValidator.TypeInfo> out = new LinkedHashMap<>();
        for (final LudnoscApi.BuildingRequirement r : quiet(() -> lud.buildingRequirements(id), List.<LudnoscApi.BuildingRequirement>of())) {
            if (r != null && r.type() != null) {
                final List<String> missing = r.missing() == null ? List.of() : r.missing();
                out.put(r.type().toLowerCase(Locale.ROOT), new PlanValidator.TypeInfo(r.type(), r.displayName() == null ? r.type() : r.displayName(),
                    r.available() && missing.isEmpty(), missing, r.built(), r.max()));
            }
        }
        if (out.isEmpty()) {
            for (final String t : quiet(lud::buildingTypes, List.<String>of())) {
                if (t != null) {
                    out.put(t.toLowerCase(Locale.ROOT), new PlanValidator.TypeInfo(t, t, true, List.of(), 0, -1));
                }
            }
        }
        return out;
    }

    private static Object parse(final String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(json);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private void aiLog(final Call c, final String text) {
        if (!c.target.npc()) return;
        final ArmiaApi a = this.plugin.armia();
        if (a != null) {
            quiet(() -> {
                a.logNpcDecision(c.target.id(), "BUILD", "Panel (" + c.actor.name() + "): " + text);
                return Boolean.TRUE;
            }, Boolean.FALSE);
        }
    }

    private static List<Map<String, Object>> issues(final List<PlanValidator.Issue> list) {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final PlanValidator.Issue i : list) {
            out.add(i.json());
        }
        return out;
    }

    // ------------------------------------------------------------------ plan

    private Object getPlan(final Call c) {
        final LudnoscApi lud = this.plugin.ludnosc();
        final boolean supported = supportsPlans(lud);
        final Object plan = lud == null ? null : parse(quiet(() -> lud.designPlan(c.target.id()), Optional.<String>empty()).orElse(null));
        return Json.obj("id", c.target.id().toString(), "available", lud != null, "supported", supported, "plan", plan,
            "margin", this.cfg().planMargin,
            "limits", Json.obj("zones", PlanValidator.MAX_ZONES, "roads", PlanValidator.MAX_ROADS, "walls", PlanValidator.MAX_WALLS,
                "buildings", PlanValidator.MAX_BUILDINGS, "pointsPerElement", PlanValidator.MAX_POINTS_PER_ELEMENT,
                "points", PlanValidator.MAX_POINTS_TOTAL, "gates", PlanValidator.MAX_GATES));
    }

    private Object setPlan(final Call c) {
        final LudnoscApi lud = this.lud();
        final JsonElement raw = c.params.element("plan");
        if (raw == null) {
            throw new RpcException("Brak parametru „plan”.");
        }
        final UUID id = c.target.id();
        final PlanValidator.Result r = PlanValidator.validate(raw, this.land(c.target), this.cfg().planMargin, this.types(lud, id));
        if (!r.ok()) {
            throw new RpcException(r.message());
        }
        final String json = Json.GSON.toJson(r.plan());
        final List<String> ludWarnings = compat(() -> lud.setDesignPlan(id, json));
        if (ludWarnings == null) {
            throw new RpcException(UNSUPPORTED);
        }
        final List<PlanValidator.Issue> warnings = new ArrayList<>(r.warnings());
        warnings.addAll(PlanValidator.parseWarnings(ludWarnings));
        final JsonObject p = r.plan();
        final int zones = p.getAsJsonArray("zones").size();
        final int roads = p.getAsJsonArray("roads").size();
        final int walls = p.getAsJsonArray("walls").size();
        final int buildings = p.getAsJsonArray("buildings").size();
        final String summary = "stref " + zones + ", dróg " + roads + ", murów " + walls + ", budynków " + buildings;
        this.aiLog(c, "zapisano plan państwa (" + summary + ")");
        return Json.obj("ok", true, "message", "Zapisano plan państwa: " + summary + (warnings.isEmpty() ? "." : " — ostrzeżenia: " + warnings.size() + "."),
            "warnings", issues(warnings), "counts", Json.obj("zones", zones, "roads", roads, "walls", walls, "buildings", buildings), "plan", p);
    }

    private Object clearPlan(final Call c) {
        final LudnoscApi lud = this.lud();
        if (!supportsPlans(lud)) {
            throw new RpcException(UNSUPPORTED);
        }
        final boolean had = compat(() -> lud.clearDesignPlan(c.target.id()));
        this.aiLog(c, "usunięto plan państwa — wraca automatyczny planer");
        return Json.obj("ok", true, "cleared", had, "message", had ? "Usunięto plan państwa — miasto rozbudowuje znów automatyczny planer." : "Państwo nie miało zapisanego planu.");
    }

    private Object preview(final Call c) {
        final LudnoscApi lud = this.plugin.ludnosc();
        final Object preview = lud == null ? null : parse(quiet(() -> lud.designPlanPreview(c.target.id()), Optional.<String>empty()).orElse(null));
        return Json.obj("id", c.target.id().toString(), "supported", supportsPlans(lud), "available", preview != null, "preview", preview);
    }

    // ------------------------------------------------------------------ manual grading

    private JsonObject terrainPreview(final UUID country, final TerrainLevelTokens.Request r) {
        final LudnoscApi lud = this.lud();
        final String json = compat(() -> lud.previewTerrainLevel(country, r.world(), r.minX(), r.minZ(), r.maxX(), r.maxZ(), r.targetY()));
        final Object parsed = parse(json);
        if (!(parsed instanceof JsonObject preview)) {
            throw new RpcException("Zainstalowana Ludnosc nie obsługuje wyrównywania terenu z panelu — zaktualizuj plugin.");
        }
        return preview;
    }

    private static boolean available(final JsonObject preview) {
        return preview.has("available") && preview.get("available").isJsonPrimitive()
            && preview.getAsJsonPrimitive("available").isBoolean() && preview.get("available").getAsBoolean();
    }

    private Object levelPreview(final Call c) {
        final TerrainLevelTokens.Request request = TerrainLevelTokens.Request.parse(c.params, c.target.capitalWorld(), c.actor.admin());
        final JsonObject preview = this.terrainPreview(c.target.id(), request);
        TerrainLevelTokens.Pending pending = null;
        if (available(preview)) {
            final int target = new Params(preview).integer("targetY", -2048, 4096);
            final TerrainLevelTokens.Request resolved = request.resolved(target);
            final String signature = TerrainLevelTokens.signature(preview);
            pending = this.levelTokens.issue(c.actor.uuid(), c.target.id(), resolved, signature, System.currentTimeMillis());
        }
        final long cost = preview.has("cost") && !preview.get("cost").isJsonNull() ? new Params(preview).integer("cost", 0L, Long.MAX_VALUE) : 0;
        return Json.obj("id", c.target.id().toString(), "token", pending == null ? null : pending.token(),
            "expiresAt", pending == null ? null : pending.expiresAt(), "preview", preview,
            "costText", this.plugin.skarbiec().format(request.free() ? 0 : cost), "free", request.free());
    }

    private Object level(final Call c) {
        // RpcService rechecks the actor's current role and country before the token is consumed.
        final TerrainLevelTokens.Pending pending = this.levelTokens.consume(c.params.string("token", 1, 128),
            c.actor.uuid(), c.target.id(), System.currentTimeMillis());
        final TerrainLevelTokens.Request r = pending.request();
        if (r.free() && !c.actor.admin()) throw RpcException.denied();
        if (!r.world().equals(c.target.capitalWorld())) throw new RpcException("Zmienił się świat stolicy. Przygotuj nowy podgląd.");
        final JsonObject current = this.terrainPreview(c.target.id(), r);
        if (!available(current) || !pending.signature().equals(TerrainLevelTokens.signature(current))) {
            throw new RpcException("Teren lub koszt zmienił się od podglądu. Obejrzyj nowy podgląd i zatwierdź ponownie.");
        }
        final LudnoscApi.BuildOrderResult result = compat(() -> this.lud().orderTerrainLevel(c.target.id(), r.world(),
            r.minX(), r.minZ(), r.maxX(), r.maxZ(), r.targetY(), r.free()));
        if (result != LudnoscApi.BuildOrderResult.OK) {
            throw new RpcException("Nie udało się zlecić wyrównywania: " + NpcV2Methods.orderError(result == null ? "ERROR" : result.name()));
        }
        this.plugin.mapRender().invalidate(c.target.id());
        this.aiLog(c, "zlecono wyrównywanie terenu na wysokości " + r.targetY());
        return Json.obj("ok", true, "result", result.name(), "free", r.free(),
            "message", "Dodano wyrównywanie terenu do kolejki budowniczych (poziom " + r.targetY() + ").");
    }

    // ------------------------------------------------------------------ map

    private Object mapRender(final Call c) {
        final KrajInfo k = c.target;
        final int margin = c.params.has("margin") ? c.params.integer("margin", 0, 128) : this.cfg().mapMargin;
        final boolean fresh = c.params.has("fresh") && c.params.bool("fresh");
        final Set<Long> land = this.land(k);
        if (land.isEmpty()) {
            throw new RpcException("Kraj nie ma ziemi w świecie " + k.capitalWorld() + ".");
        }
        final long capital = ChunkGeometry.key(k.capitalX(), k.capitalZ());
        final int[] used = {margin};
        final Set<Long> render = MapRenderService.renderSet(land, capital, margin, this.cfg().mapRender.maxChunks(), used);
        final Map<String, Object> extra = this.overlay(k, land, render);
        extra.put("margin", used[0]);
        return this.plugin.mapRender().render(new MapRenderService.Request(k.id(), k.capitalWorld(), used[0], render, extra), fresh, this.cfg().mapRender);
    }

    /** Main thread: land, capital, buildings, neighbours, protected areas and territory info sent with the image. */
    private Map<String, Object> overlay(final KrajInfo k, final Set<Long> land, final Set<Long> render) {
        final Map<String, Object> m = new HashMap<>();
        final List<int[]> chunks = new ArrayList<>();
        for (final long key : land) {
            chunks.add(new int[] {ChunkGeometry.cx(key), ChunkGeometry.cz(key)});
        }
        chunks.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        m.put("chunks", chunks);
        m.put("color", k.hexColor());
        m.put("name", k.name());
        m.put("capital", Json.obj("cx", k.capitalX(), "cz", k.capitalZ(), "x", (k.capitalX() << 4) + 8, "z", (k.capitalZ() << 4) + 8));
        final List<Map<String, Object>> buildings = new ArrayList<>();
        final List<Map<String, Object>> sites = new ArrayList<>();
        final LudnoscApi lud = this.plugin.ludnosc();
        final Optional<LudnoscInfo> info = lud == null ? Optional.empty() : quiet(() -> lud.info(k.id()), Optional.<LudnoscInfo>empty());
        for (final LudnoscInfo.Building b : info.map(LudnoscInfo::buildings).orElse(List.of())) {
            if (b.world() != null && !b.world().equals(k.capitalWorld())) {
                continue;
            }
            final int[] fp = BuildingCatalog.footprint(b.type(), b.x(), b.z(), 0);
            final String fn = b.function() == null ? "" : b.function();
            buildings.add(Json.obj("id", b.type() + "@" + b.x() + "," + b.z(), "type", b.type(), "name", b.name(), "function", fn,
                "x", b.x(), "z", b.z(), "minX", fp[0], "minZ", fp[1], "maxX", fp[2], "maxZ", fp[3], "approx", true,
                "demolish", fn.contains("do rozbiórki")));
        }
        for (final LudnoscInfo.Site s : info.map(LudnoscInfo::sites).orElse(List.of())) {
            if (s.world() != null && !s.world().equals(k.capitalWorld())) {
                continue;
            }
            final int[] fp = BuildingCatalog.footprint(s.type(), s.x(), s.z(), 0);
            sites.add(Json.obj("type", s.type(), "name", s.name(), "percent", s.percent(), "x", s.x(), "z", s.z(),
                "minX", fp[0], "minZ", fp[1], "maxX", fp[2], "maxZ", fp[3], "approx", true));
        }
        m.put("buildings", buildings);
        m.put("sites", sites);
        m.put("roads", List.of()); // LudnoscApi does not expose the street network (paths are visible on the image)
        int a = Integer.MAX_VALUE;
        int b = Integer.MAX_VALUE;
        int cMax = Integer.MIN_VALUE;
        int d = Integer.MIN_VALUE;
        for (final long key : render) {
            a = Math.min(a, ChunkGeometry.cx(key));
            b = Math.min(b, ChunkGeometry.cz(key));
            cMax = Math.max(cMax, ChunkGeometry.cx(key));
            d = Math.max(d, ChunkGeometry.cz(key));
        }
        final int minCX = a - OTHERS_RING;
        final int minCZ = b - OTHERS_RING;
        final int maxCX = cMax + OTHERS_RING;
        final int maxCZ = d + OTHERS_RING;
        final List<Map<String, Object>> others = new ArrayList<>();
        for (final KrajInfo o : this.plugin.kraje().getCountries()) {
            if (o.id().equals(k.id())) {
                continue;
            }
            final List<int[]> oc = new ArrayList<>();
            for (final long key : this.plugin.kraje().getClaims(o.id(), k.capitalWorld())) {
                final int x = ChunkGeometry.cx(key);
                final int z = ChunkGeometry.cz(key);
                if (x >= minCX && x <= maxCX && z >= minCZ && z <= maxCZ) {
                    oc.add(new int[] {x, z});
                }
            }
            if (!oc.isEmpty()) {
                others.add(Json.obj("id", o.id().toString(), "name", o.name(), "color", o.hexColor(), "npc", o.npc(), "chunks", oc));
            }
        }
        m.put("others", others);
        final List<Map<String, Object>> areas = new ArrayList<>();
        for (final KrajeFiles.Area ar : this.plugin.krajeFiles().areas()) {
            if (ar.world().equals(k.capitalWorld()) && ar.maxCX() >= minCX && ar.minCX() <= maxCX && ar.maxCZ() >= minCZ && ar.minCZ() <= maxCZ) {
                areas.add(Json.obj("id", ar.id(), "label", ar.label(), "minCX", ar.minCX(), "minCZ", ar.minCZ(), "maxCX", ar.maxCX(), "maxCZ", ar.maxCZ()));
            }
        }
        m.put("protected", areas);
        final KrajeFiles.ClaimCost cost = this.claimCost();
        final ArmiaApi armia = this.plugin.armia();
        final Integer limit = armia == null ? null : quiet(() -> armia.npcSettings(k.id()), Optional.<NpcSettings>empty()).map(NpcSettings::maxChunks).orElse(null);
        final long treasury = this.plugin.skarbiec().balance(k.treasuryAccount());
        m.put("territory", Json.obj("chunks", land.size(), "limit", limit, "costBase", cost.base(), "costPerChunk", cost.perChunk(),
            "nextCost", cost.base() + cost.perChunk() * land.size(), "treasury", treasury, "treasuryText", this.plugin.skarbiec().format(treasury),
            "maxChanges", TerritoryPlanner.MAX_CHANGES));
        return m;
    }

    private KrajeFiles.ClaimCost claimCost() {
        final KrajeFiles.ClaimCost fallback = new KrajeFiles.ClaimCost(this.cfg().territoryCostBase, this.cfg().territoryCostPerChunk);
        return this.cfg().territoryReadKrajeConfig ? this.plugin.krajeFiles().claimCost(fallback) : fallback;
    }

    // ------------------------------------------------------------------ territory

    private List<Long> chunkList(final Call c, final String name) {
        final JsonElement e = c.params.element(name);
        final List<Long> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        if (!e.isJsonArray()) {
            throw Params.bad(name, "lista chunków [[cx,cz],…]");
        }
        final JsonArray a = e.getAsJsonArray();
        if (a.size() > TerritoryPlanner.MAX_CHANGES) {
            throw Params.bad(name, "najwyżej " + TerritoryPlanner.MAX_CHANGES + " chunków naraz");
        }
        for (final JsonElement p : a) {
            if (!p.isJsonArray() || p.getAsJsonArray().size() != 2 || !isChunkCoord(p.getAsJsonArray().get(0)) || !isChunkCoord(p.getAsJsonArray().get(1))) {
                throw Params.bad(name, "chunk musi mieć postać [cx, cz] z liczb całkowitych");
            }
            out.add(ChunkGeometry.key(p.getAsJsonArray().get(0).getAsInt(), p.getAsJsonArray().get(1).getAsInt()));
        }
        return out;
    }

    private static boolean isChunkCoord(final JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        final double d = e.getAsDouble();
        return d == Math.rint(d) && Math.abs(d) <= CHUNK_COORD_MAX;
    }

    private Object territory(final Call c) {
        final KrajInfo k = c.target;
        final List<Long> add = this.chunkList(c, "add");
        final List<Long> remove = this.chunkList(c, "remove");
        final boolean free = c.params.has("free") && c.params.bool("free");
        if (add.isEmpty() && remove.isEmpty()) {
            throw new RpcException("Nie wybrano żadnych chunków do dodania ani usunięcia.");
        }
        if (add.size() + remove.size() > TerritoryPlanner.MAX_CHANGES) {
            throw new RpcException("Najwyżej " + TerritoryPlanner.MAX_CHANGES + " zmian terenu naraz.");
        }
        final String world = k.capitalWorld();
        final Set<Long> owned = this.land(k);
        final long capital = ChunkGeometry.key(k.capitalX(), k.capitalZ());
        final List<KrajeFiles.Area> areas = this.plugin.krajeFiles().areas();
        final KrajeFiles.ClaimCost price = this.claimCost();
        final TerritoryPlanner.Decision d = TerritoryPlanner.decide(owned, capital, add, remove,
            key -> this.plugin.kraje().getCountryAt(world, ChunkGeometry.cx(key), ChunkGeometry.cz(key))
                .filter(o -> !o.id().equals(k.id())).map(KrajInfo::name).orElse(null),
            key -> areas.stream().filter(ar -> ar.contains(world, ChunkGeometry.cx(key), ChunkGeometry.cz(key))).map(KrajeFiles.Area::label).findFirst().orElse(null),
            price.base(), price.perChunk());
        final String account = k.treasuryAccount();
        final long paid = free ? 0 : d.cost();
        if (paid > 0) {
            final long balance = this.plugin.skarbiec().balance(account);
            if (balance < paid) {
                throw new RpcException("Za mało pieniędzy w skarbcu: zajęcie " + d.claim().size() + " chunków kosztuje " + this.plugin.skarbiec().format(paid)
                    + ", a jest " + this.plugin.skarbiec().format(balance) + ". Zaznacz „Darmowo” albo dodaj mniej.");
            }
            final TxResult tx = this.plugin.skarbiec().withdraw(account, paid, "Panel: zajęcie " + d.claim().size() + " chunków (" + c.actor.name() + ")");
            if (tx == null || !tx.ok()) {
                throw new RpcException("Nie udało się pobrać opłaty ze skarbca (" + tx + ").");
            }
        }
        final CompletableFuture<Object> result = new CompletableFuture<>();
        final List<TerritoryPlanner.Skip> skipped = new ArrayList<>(d.skipped());
        final List<long[]> steps = new ArrayList<>();
        for (final long key : d.claim()) {
            steps.add(new long[] {key, 1});
        }
        for (final long key : d.release()) {
            steps.add(new long[] {key, 0});
        }
        final int[] counts = {0, 0, 0}; // added, removed, index
        final int perTick = this.cfg().territoryChangesPerTick;
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            try {
                int n = 0;
                while (counts[2] < steps.size() && n++ < perTick) {
                    final long[] s = steps.get(counts[2]++);
                    final int cx = ChunkGeometry.cx(s[0]);
                    final int cz = ChunkGeometry.cz(s[0]);
                    final KrajeResult r = s[1] == 1
                        ? this.plugin.kraje().claimChunk(k.id(), world, cx, cz, ClaimCause.ADMIN)
                        : this.plugin.kraje().unclaimChunk(k.id(), world, cx, cz, ClaimCause.ADMIN);
                    if (r != null && r.ok()) {
                        counts[s[1] == 1 ? 0 : 1]++;
                    } else {
                        final String why = r == null ? "Kraje odrzucił zmianę" : r.message() == null || r.message().isBlank() ? PanelMethods.defaultMessage(r.status()) : r.message();
                        skipped.add(new TerritoryPlanner.Skip(cx, cz, r == null ? "FAILED" : r.status().name(), why));
                    }
                }
                if (counts[2] < steps.size()) {
                    Bukkit.getScheduler().runTask(this.plugin, step[0]);
                    return;
                }
                final long cost = free ? 0 : TerritoryPlanner.cost(owned.size(), counts[0], price.base(), price.perChunk());
                if (paid > cost) {
                    this.plugin.skarbiec().deposit(account, paid - cost, "Panel: zwrot za niezajęte chunki (" + c.actor.name() + ")");
                }
                this.plugin.mapRender().invalidate(k.id());
                if (counts[0] + counts[1] > 0) {
                    final ArmiaApi a = this.plugin.armia();
                    if (a != null) {
                        quiet(() -> {
                            a.logNpcDecision(k.id(), "OTHER", "Panel (" + c.actor.name() + "): teren +" + counts[0] + " / −" + counts[1] + " chunków"
                                + (cost > 0 ? " (koszt " + this.plugin.skarbiec().format(cost) + ")" : free ? " (darmowo)" : ""));
                            return Boolean.TRUE;
                        }, Boolean.FALSE);
                    }
                }
                final List<Map<String, Object>> sk = new ArrayList<>();
                for (final TerritoryPlanner.Skip s : skipped) {
                    sk.add(Json.obj("cx", s.cx(), "cz", s.cz(), "code", s.code(), "reason", s.reason()));
                }
                final int now = this.plugin.kraje().getClaims(k.id(), world).size();
                result.complete(Json.obj("ok", true,
                    "message", "Teren: +" + counts[0] + " / −" + counts[1] + " chunków" + (skipped.isEmpty() ? "" : ", pominięto " + skipped.size())
                        + (cost > 0 ? ", koszt " + this.plugin.skarbiec().format(cost) : "") + ".",
                    "added", counts[0], "removed", counts[1], "skipped", sk, "cost", cost, "costText", this.plugin.skarbiec().format(cost),
                    "free", free, "chunks", now));
            } catch (final RuntimeException | LinkageError e) {
                result.completeExceptionally(e);
            }
        };
        step[0].run();
        return result;
    }
}
