// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.ledger.TxRecord;
import pl.medivalrpg.skarbiec.ledger.WorldMoney;

/**
 * Owner-only statistics of the closed world economy ({@code world.stats.*} panel methods).
 *
 * <p>Authorization: {@link #invoke} checks the actor's UUID against {@link WorldMoney#MANAGER} on EVERY call, before
 * touching any data — panel administrators, operators, rulers and the console have no bypass. Heavy reads (logs, files)
 * run on a background thread and the returned future is completed on the main thread.</p>
 */
public final class WorldStats {

    public static final String DENIED = "Tylko WorldSteward ma dostęp do statystyk Skarbu Świata.";
    public static final List<String> METHODS = List.of("world.stats.summary", "world.stats.accounts", "world.stats.flows",
        "world.stats.account", "world.stats.coins", "world.stats.census", "world.stats.trail", "world.stats.merchant", "world.stats.households");

    private final SkarbiecPlugin plugin;
    private final ExecutorService queries;
    private final ExecutorService census;
    private final FlowIndex flows;
    private final CoinCensus coinCensus;
    private final TrailLog trail;
    private final CoinTrailListener trailListener;

    public WorldStats(final SkarbiecPlugin plugin) {
        this.plugin = plugin;
        this.queries = Executors.newSingleThreadExecutor(r -> daemon(r, "Skarbiec-Stats"));
        this.census = Executors.newSingleThreadExecutor(r -> {
            final Thread t = daemon(r, "Skarbiec-Census");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        final var dir = plugin.getDataFolder().toPath();
        this.flows = new FlowIndex(dir.resolve("bank").resolve("logs"), plugin.cfg().zone);
        this.coinCensus = new CoinCensus(plugin, this.census);
        this.trail = new TrailLog(dir.resolve("stats").resolve("ruchy"), plugin.cfg().zone, plugin.cfg().logKeepDays, plugin.getLogger());
        this.trailListener = new CoinTrailListener(plugin.coins(), this.trail,
            plugin.getConfig().getInt("stats.trail.max-events-per-second", 200));
    }

    private static Thread daemon(final Runnable r, final String name) {
        final Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public void start() {
        this.trail.start();
        if (this.plugin.getConfig().getBoolean("stats.trail.enabled", true)) {
            Bukkit.getPluginManager().registerEvents(this.trailListener, this.plugin);
            this.trailListener.start(this.plugin);
        }
    }

    public void stop() {
        this.trailListener.stop();
        this.coinCensus.stop();
        this.trail.close();
        this.census.shutdownNow();
        this.queries.shutdown();
        try {
            this.queries.awaitTermination(2, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The only authorization rule: the personal manager's UUID. Roles, OP and country ranks are irrelevant. */
    public static void requireOwner(final UUID actor) {
        if (actor == null || !WorldMoney.MANAGER.equals(actor)) {
            throw new IllegalArgumentException(DENIED);
        }
    }

    /** Authorizes and dispatches; main thread. Returns a map or a future of a map. */
    public Object invoke(final UUID actor, final String method, final Map<String, String> args) {
        requireOwner(actor);
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Main thread required");
        }
        return switch (method) {
            case "world.stats.summary" -> this.summary();
            case "world.stats.accounts" -> this.accounts();
            case "world.stats.flows" -> this.flows(args);
            case "world.stats.account" -> this.account(args);
            case "world.stats.coins" -> this.coinCensus.snapshot();
            case "world.stats.census" -> this.startCensus();
            case "world.stats.trail" -> this.trail(args);
            case "world.stats.merchant" -> this.plugin.merchant().statistics();
            case "world.stats.households" -> this.households(args);
            default -> throw new IllegalArgumentException("Nieznana metoda statystyk Skarbu Świata.");
        };
    }

    // ================================================================ labels (main thread)

    /** Name of every known account: players and countries resolved now, technical accounts described. */
    private Map<String, String> labels(final Iterable<String> accounts) {
        final Map<String, String> out = new HashMap<>();
        for (final String a : accounts) {
            out.put(a, this.label(a));
        }
        return out;
    }

    private String label(final String account) {
        try {
            if (account.startsWith("player:")) {
                return SkarbiecPlugin.nameOf(UUID.fromString(account.substring(7)));
            }
            if (account.startsWith("ludnosc:obywatele_")) {
                final UUID country = UUID.fromString(account.substring("ludnosc:obywatele_".length()));
                return "Obywatele NPC — " + this.plugin.territory().countryName(country).orElse("kraj " + country.toString().substring(0, 8));
            }
            if (account.startsWith("country:")) {
                return this.plugin.territory().countryName(UUID.fromString(account.substring(8))).orElse("Kraj " + account.substring(8, 16));
            }
        } catch (final IllegalArgumentException e) {
            return account;
        }
        return AccountTypes.technicalName(account);
    }

    private static Function<String, String> lookup(final Map<String, String> labels) {
        return a -> {
            final String l = labels.get(a);
            return l != null ? l : AccountTypes.technicalName(a);
        };
    }

    // ================================================================ summary

    private Map<String, Object> summary() {
        final Map<String, Long> balances = this.plugin.ledger().balances();
        final Map<String, long[]> byType = new LinkedHashMap<>();
        for (final String t : AccountTypes.ORDER) {
            byType.put(t, new long[2]);
        }
        long total = 0;
        int negative = 0;
        long other = 0;
        for (final Map.Entry<String, Long> e : balances.entrySet()) {
            final long v = e.getValue();
            final String t = AccountTypes.type(e.getKey());
            final long[] a = byType.get(t);
            a[0] += v;
            a[1]++;
            total += v;
            if (v < 0) {
                negative++;
            }
            if (t.equals("other")) {
                other += v;
            }
        }
        final long cap = WorldMoney.CAP;
        final boolean closed = this.plugin.ledger().closedSupply();
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", System.currentTimeMillis());
        out.put("cap", cap);
        out.put("total", total);
        out.put("difference", total - cap);
        out.put("closed", closed);
        out.put("seq", this.plugin.ledger().seq());
        final List<Map<String, Object>> cats = new ArrayList<>();
        final long base = Math.max(1, total);
        byType.forEach((k, v) -> cats.add(FlowQuery.map("key", k, "label", AccountTypes.label(k), "amount", v[0], "accounts", v[1],
            "share", (double) v[0] / base)));
        out.put("categories", cats);
        final List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("closed", closed ? "OK" : "ERROR", closed ? "Zamknięty obieg jest aktywny (nowe pieniądze nie powstają)."
            : "Zamknięty obieg NIE jest aktywny — księga działa w starym trybie.", null, null));
        checks.add(check("sum", total == cap ? "OK" : "ERROR", total == cap
                ? "Suma wszystkich kont = " + fmt(cap) + " (pula świata)."
                : "Rozbieżność puli: suma kont " + fmt(total) + " zamiast " + fmt(cap) + " (różnica " + fmt(total - cap) + ").",
            cap, total));
        checks.add(check("negative", negative == 0 ? "OK" : "ERROR", negative == 0 ? "Żadne konto nie ma ujemnego salda."
            : "Konta z ujemnym saldem: " + negative + ".", 0L, (long) negative));
        checks.add(check("unknown", other == 0 ? "OK" : "WARN", other == 0 ? "Wszystkie konta mają znany typ."
            : "Pieniądze na kontach nieznanego typu: " + fmt(other) + " — sprawdź zakładkę Konta.", 0L, other));
        final Map<String, Object> coins = this.coinCensus.snapshot();
        final Map<String, Object> census = new LinkedHashMap<>();
        census.put("status", coins.get("status"));
        census.put("startedAt", coins.get("startedAt"));
        census.put("finishedAt", coins.get("finishedAt"));
        census.put("total", coins.get("total"));
        census.put("cashAccount", coins.get("cashAccount"));
        census.put("difference", coins.get("difference"));
        census.put("differenceAtCensus", coins.get("differenceAtCensus"));
        census.put("progress", coins.getOrDefault("progress", 0));
        out.put("census", census);
        final long cash = this.plugin.ledger().balance(WorldMoney.CASH);
        if (!"DONE".equals(coins.get("status")) || !(coins.get("total") instanceof Number t)) {
            checks.add(check("census", "WARN", "Brak zakończonego spisu monet fizycznych — wykonaj spis w zakładce Monety.", cash, null));
        } else {
            final long found = t.longValue();
            final long diffAt = coins.get("differenceAtCensus") instanceof Number d ? d.longValue() : found - cash;
            final long age = System.currentTimeMillis() - ((Number) coins.get("finishedAt")).longValue();
            checks.add(check("census", diffAt == 0 ? "OK" : "WARN", diffAt == 0
                    ? "Spis monet zgodny z pokryciem w chwili spisu: " + fmt(found) + "."
                    : "Rozbieżność spisu monet: znaleziono " + fmt(found) + ", pokrycie w chwili spisu " + fmt(found - diffAt)
                        + " (różnica " + fmt(diffAt) + "; spis na gorąco — powtórz, by potwierdzić).",
                found - diffAt, found));
            if (age > 24L * 3_600_000L) {
                checks.add(check("censusAge", "WARN", "Ostatni spis monet ma ponad dobę — wynik może być nieaktualny.", null, null));
            }
        }
        out.put("checks", checks);
        out.put("typeLabels", AccountTypes.LABELS);
        return out;
    }

    private static Map<String, Object> check(final String key, final String level, final String text, final Long expected, final Long actual) {
        return FlowQuery.map("key", key, "level", level, "text", text, "expected", expected, "actual", actual,
            "difference", expected == null || actual == null ? null : actual - expected);
    }

    private static String fmt(final long md) {
        return String.format(java.util.Locale.ROOT, "%,d md", md).replace(',', ' ');
    }

    // ================================================================ accounts

    private Object accounts() {
        final Map<String, Long> balances = this.plugin.ledger().balances();
        final Map<String, String> labels = this.labels(balances.keySet());
        final long now = System.currentTimeMillis();
        final long from = now - 31L * 86_400_000L;
        final long week = now - 7L * 86_400_000L;
        return this.async(() -> {
            final Map<String, long[]> act = FlowQuery.activity(this.flows::forEach, from, week, now);
            final long total = Math.max(1, balances.values().stream().mapToLong(Long::longValue).sum());
            final List<Map<String, Object>> rows = new ArrayList<>();
            balances.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(5000).forEach(e -> {
                    final String a = e.getKey();
                    final String type = AccountTypes.type(a);
                    final long[] x = act.get(a);
                    final boolean personal = a.startsWith("player:") || a.startsWith("country:");
                    rows.add(FlowQuery.map("account", a, "type", type, "typeLabel", AccountTypes.label(type), "owner", labels.get(a),
                        "ownerId", personal ? a.substring(a.indexOf(':') + 1) : null, "balance", e.getValue(),
                        "share", (double) e.getValue() / total, "lastActivity", x == null || x[0] == 0 ? null : x[0],
                        "count7d", x == null ? 0 : x[1], "in7d", x == null ? 0 : x[2], "out7d", x == null ? 0 : x[3]));
                });
            return FlowQuery.map("generatedAt", now, "total", total, "count", balances.size(), "truncated", balances.size() > 5000,
                "window", FlowQuery.map("from", week, "to", now, "days", 7, "activityDays", 31), "typeLabels", AccountTypes.LABELS,
                "accounts", rows);
        });
    }

    // ================================================================ flows

    private long retentionFrom(final long now) {
        return now - (long) this.plugin.cfg().logKeepDays * 86_400_000L;
    }

    private Object flows(final Map<String, String> args) {
        final long now = System.currentTimeMillis();
        long to = longArg(args, "to", now);
        to = Math.min(to, now);
        long from = longArg(args, "from", to - 7L * 86_400_000L);
        from = Math.max(from, this.retentionFrom(now));
        if (from > to) {
            throw new IllegalArgumentException("Początek zakresu jest po jego końcu.");
        }
        final String query = args.get("query");
        if (query != null && query.length() > 64) {
            throw new IllegalArgumentException("Wyszukiwany tekst może mieć najwyżej 64 znaki.");
        }
        final String category = args.get("category");
        if (category != null && !category.isBlank() && !Categories.LABELS.containsKey(category)) {
            throw new IllegalArgumentException("Nieznana kategoria.");
        }
        final String type = args.get("type");
        if (type != null && !type.isBlank() && !FlowQuery.typeLabels().containsKey(type)) {
            throw new IllegalArgumentException("Nieznany typ konta.");
        }
        final FlowQuery.Filter f = new FlowQuery.Filter(from, to, args.get("account"), category, type, query,
            longArg(args, "minAmount", 0), (int) Math.min(Integer.MAX_VALUE, longArg(args, "offset", 0)),
            (int) Math.min(Integer.MAX_VALUE, longArg(args, "limit", 200)));
        final Map<String, String> labels = this.labels(this.plugin.ledger().balances().keySet());
        final int retention = this.plugin.cfg().logKeepDays;
        return this.async(() -> {
            final LocalDate oldest = this.flows.oldestDay();
            final Long oldestMs = oldest == null ? null : oldest.atStartOfDay(this.flows.zone()).toInstant().toEpochMilli();
            return FlowQuery.run(this.flows::forEach, f, lookup(labels), this.flows.zone(), retention, oldestMs);
        });
    }

    private Object account(final Map<String, String> args) {
        final String account = args.get("account");
        if (account == null || !pl.medivalrpg.skarbiec.ledger.Ledger.validAccount(account)) {
            throw new IllegalArgumentException("Podaj prawidłowe konto.");
        }
        final int days = (int) Math.max(1, Math.min(Math.min(90, this.plugin.cfg().logKeepDays), longArg(args, "days", 30)));
        final long now = System.currentTimeMillis();
        final long from = now - days * 86_400_000L;
        final long balance = this.plugin.ledger().balance(account);
        final boolean exists = this.plugin.ledger().exists(account);
        final Map<String, String> labels = this.labels(this.plugin.ledger().balances().keySet());
        final String label = this.label(account);
        return this.async(() -> {
            final Map<String, Object> out = new LinkedHashMap<>();
            final String type = AccountTypes.type(account);
            out.put("account", account);
            out.put("exists", exists);
            out.put("label", label);
            out.put("type", type);
            out.put("typeLabel", AccountTypes.label(type));
            out.put("owner", label);
            out.put("balance", balance);
            out.put("days", days);
            out.putAll(FlowQuery.account(this.flows::forEach, account, from, now, balance, lookup(labels)));
            return out;
        });
    }

    // ================================================================ households pool

    /** Circulation of world:households: daily in/out, purposes, forecast of how long the pool lasts. */
    private Object households(final Map<String, String> args) {
        final int days = (int) Math.max(7, Math.min(Math.min(90, this.plugin.cfg().logKeepDays), longArg(args, "days", 30)));
        final long now = System.currentTimeMillis();
        final long from = now - days * 86_400_000L;
        final long balance = this.plugin.ledger().balance(WorldMoney.HOUSEHOLDS);
        final Map<String, String> labels = this.labels(this.plugin.ledger().balances().keySet());
        final var laborLedger = this.plugin.labor();
        final var labor = laborLedger == null ? pl.medivalrpg.skarbiec.ledger.LaborShare.OFF : laborLedger.policy();
        final long laborToday = laborLedger == null ? 0 : laborLedger.settledToday(now);
        final long laborPending = laborLedger == null ? 0 : laborLedger.pendingTotal();
        final long laborDue = laborLedger == null ? 0 : laborLedger.due(now);
        final long laborSettled = laborLedger == null ? 0 : laborLedger.settledTotal();
        final long laborCancelled = laborLedger == null ? 0 : laborLedger.cancelledTotal();
        final List<Map<String, Object>> grants = new ArrayList<>();
        if (this.plugin.grants() != null) {
            for (final var g : pl.medivalrpg.skarbiec.ledger.WorldGrants.DEPLOYMENT) {
                if (g.account().equals(WorldMoney.HOUSEHOLDS) || g.account().equals(WorldMoney.MERCHANT)) {
                    grants.add(FlowQuery.map("key", g.key(), "label", g.label(), "account", g.account(), "amount", g.amount(),
                        "state", this.plugin.grants().stored(g.key())));
                }
            }
        }
        return this.async(() -> {
            final Map<String, Object> run = FlowQuery.run(this.flows::forEach, new FlowQuery.Filter(from, now, WorldMoney.HOUSEHOLDS, null, null, null,
                0, 0, 1), lookup(labels), this.flows.zone(), this.plugin.cfg().logKeepDays, null);
            final Map<String, Object> acc = FlowQuery.account(this.flows::forEach, WorldMoney.HOUSEHOLDS, from, now, balance, lookup(labels));
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("account", WorldMoney.HOUSEHOLDS);
            out.put("balance", balance);
            out.put("days", days);
            out.put("from", from);
            out.put("to", now);
            out.putAll(HouseholdForecast.of((List<?>) run.get("daily"), balance, this.flows.zone(), now));
            out.put("categories", acc.get("categories"));
            out.put("counterparties", acc.get("counterparties"));
            out.put("points", acc.get("points"));
            out.put("rows", acc.get("rows"));
            out.put("labor", FlowQuery.map("percent", labor.basisPoints() / 100.0, "dailyCap", labor.dailyCap(), "targetBalance", labor.targetBalance(),
                "settleAfterHours", labor.settleAfterHours(), "today", laborToday, "pending", laborPending, "due", laborDue,
                "settledTotal", laborSettled, "cancelledTotal", laborCancelled, "prefixes", labor.prefixes(), "refundPrefixes", labor.refundPrefixes()));
            out.put("grants", grants);
            out.put("categoryLabels", Categories.LABELS);
            return out;
        });
    }

    // ================================================================ coins

    private Map<String, Object> startCensus() {
        final boolean started = this.coinCensus.start();
        return FlowQuery.map("ok", true, "started", started, "message", started
            ? "Rozpoczęto spis monet. Załadowane chunki są liczone porcjami na serwerze, pliki świata w tle; potrwa to kilka minut."
            : "Spis monet już trwa.");
    }

    private Object trail(final Map<String, String> args) {
        final long now = System.currentTimeMillis();
        long to = Math.min(longArg(args, "to", now), now);
        long from = Math.max(longArg(args, "from", to - 86_400_000L), this.retentionFrom(now));
        if (from > to) {
            throw new IllegalArgumentException("Początek zakresu jest po jego końcu.");
        }
        final String kind = blank(args.get("kind"));
        if (kind != null && !TrailLog.KIND_LABELS.containsKey(kind)) {
            throw new IllegalArgumentException("Nieznany rodzaj ruchu.");
        }
        final String query = blank(args.get("query"));
        if (query != null && query.length() > 64) {
            throw new IllegalArgumentException("Wyszukiwany tekst może mieć najwyżej 64 znaki.");
        }
        String player = blank(args.get("player"));
        if (player != null && player.length() > 36) {
            throw new IllegalArgumentException("Nieprawidłowy gracz.");
        }
        final int limit = (int) Math.max(1, Math.min(1000, longArg(args, "limit", 200)));
        final int offset = (int) Math.max(0, Math.min(FlowQuery.MAX_WINDOW - limit, longArg(args, "offset", 0)));
        final String needle = query == null ? null : FlowQuery.fold(query);
        final String who = player == null ? null : FlowQuery.fold(player);
        final Map<String, String> playerNames = new HashMap<>();
        final long dropped = this.trail.dropped();
        final long since = this.trail.since();
        final long ffrom = from;
        final long fto = to;
        return this.async(() -> {
            final List<TrailLog.Event> events = new ArrayList<>();
            this.trail.forEach(ffrom, fto, events::add);
            // Bank deposits/withdrawals of physical coins come from the ledger: CASH -> account / account -> CASH.
            this.flows.forEach(ffrom, fto, r -> {
                final TrailLog.Event e = cashEvent(r);
                if (e != null) {
                    events.add(e);
                }
            });
            events.sort(Comparator.comparingLong(TrailLog.Event::time));
            final Deque<TrailLog.Event> window = new ArrayDeque<>();
            final Map<String, long[]> byKind = new LinkedHashMap<>();
            for (final String k : TrailLog.KIND_LABELS.keySet()) {
                byKind.put(k, new long[2]);
            }
            long total = 0;
            for (final TrailLog.Event e : events) {
                if (kind != null && !kind.equals(e.kind())) {
                    continue;
                }
                if (who != null && !(e.playerId() != null && FlowQuery.fold(e.playerId()).equals(who)
                    || e.player() != null && FlowQuery.fold(e.player()).equals(who))) {
                    continue;
                }
                if (needle != null && !FlowQuery.fold(String.join(" ", String.valueOf(e.player()), String.valueOf(e.target()),
                    String.valueOf(e.note()), String.valueOf(e.world()), e.kind(), TrailLog.KIND_LABELS.getOrDefault(e.kind(), ""))).contains(needle)) {
                    continue;
                }
                total++;
                final long[] k = byKind.computeIfAbsent(e.kind(), x -> new long[2]);
                k[0]++;
                k[1] += e.amount();
                window.addLast(e);
                if (window.size() > offset + limit) {
                    window.removeFirst();
                }
            }
            final List<TrailLog.Event> desc = new ArrayList<>(window);
            java.util.Collections.reverse(desc);
            final List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = offset; i < desc.size() && rows.size() < limit; i++) {
                rows.add(desc.get(i).toMap());
            }
            final List<Map<String, Object>> kinds = new ArrayList<>();
            byKind.forEach((k, v) -> kinds.add(FlowQuery.map("kind", k, "label", TrailLog.KIND_LABELS.getOrDefault(k, k), "count", v[0], "amount", v[1])));
            return FlowQuery.map("from", ffrom, "to", fto, "total", total, "offset", offset, "limit", limit, "since", since,
                "dropped", dropped, "rows", rows, "byKind", kinds, "kindLabels", TrailLog.KIND_LABELS);
        }, result -> {
            // Resolve player names of ledger cash events on the main thread.
            if (result.get("rows") instanceof List<?> rows) {
                for (final Object o : rows) {
                    if (o instanceof Map<?, ?> rowMap && rowMap.get("player") == null && rowMap.get("playerId") instanceof String id) {
                        @SuppressWarnings("unchecked") final Map<String, Object> row = (Map<String, Object>) rowMap;
                        row.put("player", playerNames.computeIfAbsent(id, WorldStats::playerName));
                    }
                }
            }
            return result;
        });
    }

    private static String playerName(final String id) {
        try {
            return SkarbiecPlugin.nameOf(UUID.fromString(id));
        } catch (final IllegalArgumentException e) {
            return id;
        }
    }

    /** Ledger cash conversion as a trail event: {@code cash <uuid>: …} reasons written by CoinService. */
    static TrailLog.Event cashEvent(final TxRecord r) {
        final boolean deposit = WorldMoney.CASH.equals(r.from());
        final boolean withdraw = WorldMoney.CASH.equals(r.to());
        if (!deposit && !withdraw) {
            return null;
        }
        String player = null;
        final String reason = r.reason() == null ? "" : r.reason();
        if (reason.startsWith("cash ") && reason.length() >= 41) {
            final String id = reason.substring(5, 41);
            try {
                player = UUID.fromString(id).toString();
            } catch (final IllegalArgumentException ignored) {
                // not a player conversion
            }
        }
        final String account = deposit ? r.to() : r.from();
        return new TrailLog.Event(r.time(), deposit ? "DEPOSIT" : "WITHDRAW", player, null, r.amount(), 0, null, null, null, null,
            (deposit ? "Monety → konto " : "Konto → monety ") + account, reason);
    }

    // ================================================================ helpers

    private interface Work {
        Map<String, Object> run() throws IOException;
    }

    private CompletableFuture<Object> async(final Work work) {
        return this.async(work, r -> r);
    }

    private CompletableFuture<Object> async(final Work work, final java.util.function.UnaryOperator<Map<String, Object>> onMain) {
        final CompletableFuture<Object> out = new CompletableFuture<>();
        try {
            this.queries.execute(() -> {
                Map<String, Object> result = null;
                Throwable error = null;
                try {
                    result = work.run();
                } catch (final IOException | RuntimeException e) {
                    error = e;
                    this.plugin.getLogger().log(Level.WARNING, "Statystyki Skarbu Świata: błąd odczytu", e);
                }
                final Map<String, Object> r = result;
                final Throwable err = error;
                try {
                    Bukkit.getScheduler().runTask(this.plugin, () -> {
                        if (err != null) {
                            out.completeExceptionally(new IllegalArgumentException("Nie udało się odczytać dzienników Skarbca: " + err.getMessage()));
                        } else {
                            try {
                                out.complete(onMain.apply(r));
                            } catch (final RuntimeException e) {
                                out.completeExceptionally(e);
                            }
                        }
                    });
                } catch (final RuntimeException e) { // plugin disabled meanwhile
                    out.completeExceptionally(new IllegalArgumentException("Skarbiec się wyłącza."));
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            throw new IllegalArgumentException("Skarbiec się wyłącza.");
        }
        return out;
    }

    private static String blank(final String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static long longArg(final Map<String, String> args, final String key, final long def) {
        final String v = args.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return (long) Double.parseDouble(v.strip());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Nieprawidłowa liczba: " + key);
        }
    }
}
