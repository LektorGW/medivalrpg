// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.medivalrpg.straz.alert.Alert;
import pl.medivalrpg.straz.alert.AlertGate;
import pl.medivalrpg.straz.api.Bridges;
import pl.medivalrpg.straz.backup.BackupPlan;
import pl.medivalrpg.straz.backup.BackupWriter;
import pl.medivalrpg.straz.discord.DiscordSender;
import pl.medivalrpg.straz.economy.Flows;
import pl.medivalrpg.straz.economy.Pool;
import pl.medivalrpg.straz.kolej.RailReader;
import pl.medivalrpg.straz.kolej.RailSummary;
import pl.medivalrpg.straz.log.ErrorBook;
import pl.medivalrpg.straz.log.LogParser;
import pl.medivalrpg.straz.log.LogTailer;
import pl.medivalrpg.straz.metrics.LowTpsDetector;
import pl.medivalrpg.straz.metrics.Metrics;
import pl.medivalrpg.straz.people.Census;
import pl.medivalrpg.straz.report.DiscordPayload;
import pl.medivalrpg.straz.report.ReportData;
import pl.medivalrpg.straz.report.ReportFormatter;
import pl.medivalrpg.straz.state.StateStore;
import pl.medivalrpg.straz.state.StrazState;
import pl.medivalrpg.straz.util.DailyClock;
import pl.medivalrpg.straz.util.Fmt;

/**
 * The watch: samples (main thread, once a minute), log/pool/rail checks (I/O thread), the daily report and the
 * nightly backup (own background thread). Reads other plugins' data only; never writes outside its own folder and
 * the backup directory.
 */
public final class StrazService {

    private static final long DAY = 86_400_000L;
    private static final DateTimeFormatter FILE_HM = DateTimeFormatter.ofPattern("HHmm");

    private final StrazPlugin plugin;
    private final Logger log;
    private final Path serverDir;
    private final Path pluginsDir;
    private final Path reportsDir;
    private final StateStore store;
    private final StrazState state;
    private final Bridges bridges = new Bridges();
    private final DiscordSender discord;
    private volatile StrazConfig cfg;
    private volatile LowTpsDetector tps;
    private volatile LogTailer tailer;

    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "Straz-IO");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService backupThread = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Straz-Kopia");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean reportRunning = new AtomicBoolean();
    private final AtomicBoolean backupRunning = new AtomicBoolean();
    private final List<ErrorBook.Line> pendingNew = new ArrayList<>();
    private long pendingSince;
    private volatile String kolejStatus;
    private volatile RailSummary.Summary lastRail;
    private long railFilesStamp = -1;
    private RailReader.Request railRequest;
    private Map<String, RailSummary.Job> railJobs = Map.of();

    public StrazService(final StrazPlugin plugin, final StrazConfig cfg) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.pluginsDir = plugin.getDataFolder().toPath().toAbsolutePath().getParent();
        this.serverDir = this.pluginsDir.getParent() == null ? Path.of(".").toAbsolutePath() : this.pluginsDir.getParent();
        this.reportsDir = plugin.getDataFolder().toPath().resolve("raporty");
        this.store = new StateStore(plugin.getDataFolder().toPath().resolve("stan.json"), this.log);
        this.state = this.store.load();
        this.cfg = cfg;
        this.discord = new DiscordSender(this.log, () -> this.cfg.webhookUrl(), () -> this.cfg.allowLoopback());
        this.apply(cfg);
    }

    public void apply(final StrazConfig c) {
        this.cfg = c;
        this.tps = new LowTpsDetector(c.tpsThreshold(), c.tpsMinutes());
        synchronized (this.state) {
            this.tailer = new LogTailer(this.serverDir.resolve("logs").resolve("latest.log"), java.time.ZoneId.systemDefault(),
                this.state.log);
        }
    }

    public StrazConfig config() {
        return this.cfg;
    }

    public DiscordSender discord() {
        return this.discord;
    }

    public StrazState state() {
        return this.state;
    }

    public Path reportsDir() {
        return this.reportsDir;
    }

    public void start() {
        this.io.scheduleWithFixedDelay(this.guard("log", this::pollLog), 3, 5, TimeUnit.SECONDS);
        this.io.scheduleWithFixedDelay(this.guard("pula", this::checkPool), 20, this.cfg.poolEverySeconds(), TimeUnit.SECONDS);
        this.io.scheduleWithFixedDelay(this.guard("kolej", this::checkRail), 40, 300, TimeUnit.SECONDS);
        this.io.scheduleWithFixedDelay(this.guard("zapis", this::saveAndPrune), 300, 300, TimeUnit.SECONDS);
        final String hook = this.discord.configured()
            ? (this.discord.valid() ? "Discord: webhook ustawiony (" + pl.medivalrpg.straz.discord.WebhookUrl.mask(this.cfg.webhookUrl()) + ")."
                : "Discord: discord.webhook-url ma niepoprawny format — raporty tylko w logu i w plugins/Straz/raporty/.")
            : "Discord: brak webhooka — raporty i alerty tylko w logu i w plugins/Straz/raporty/.";
        this.log.info(hook + " Raport codziennie o " + this.cfg.reportAt() + ", kopia "
            + (this.cfg.backupEnabled() ? "o " + this.cfg.backupAt() + " do " + this.cfg.backupDir() + "/" : "wyłączona") + ".");
    }

    public void stop() {
        this.io.shutdownNow();
        this.backupThread.shutdownNow();
        this.discord.close();
        try {
            this.store.save(this.state);
        } catch (final IOException e) {
            this.log.warning("stan.json: nie zapisano (" + e.getMessage() + ")");
        }
    }

    private Runnable guard(final String what, final Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (final RuntimeException | LinkageError e) {
                this.log.log(Level.WARNING, "Straż (" + what + "): " + e, e instanceof RuntimeException ? null : e);
            }
        };
    }

    // ================================================================== main thread: every minute

    /** Called once a minute on the main thread. */
    public void minuteTick() {
        final long now = System.currentTimeMillis();
        final double current = Bukkit.getTPS()[0];
        final int online = Bukkit.getOnlinePlayers().size();
        final LowTpsDetector.Signal sig;
        synchronized (this.state) {
            this.state.metrics.tps(now, current, this.tps.isLow(current));
            this.state.metrics.players(now, online);
            sig = this.tps.sample(current);
        }
        if (sig == LowTpsDetector.Signal.ALERT) {
            this.alert(new Alert("TPS", "tps|" + now / 3_600_000L, "TPS poniżej " + Fmt.dec1(this.tps.threshold()),
                List.of("TPS poniżej " + Fmt.dec1(this.tps.threshold()) + " od " + this.tps.streak() + " min (najniżej "
                    + Fmt.dec1(this.tps.streakMin()) + ", teraz " + Fmt.dec1(current) + ").", "Graczy online: " + online + "."), now), 0);
        } else if (sig == LowTpsDetector.Signal.RECOVERED) {
            this.log.info("TPS wróciło do normy po " + this.tps.lastStreak() + " min (teraz " + Fmt.dec1(current) + ").");
        }
        this.kolejStatus = this.bridges.kolejStatus();
        if (RailSummary.isStopStatus(this.kolejStatus)) {
            this.io.execute(this.guard("kolej", this::checkRail));
        }
        final ZonedDateTime z = ZonedDateTime.now(this.cfg.zone());
        final String reportDay;
        final String backupDay;
        synchronized (this.state) {
            reportDay = this.state.lastReportDay;
            backupDay = this.state.lastBackupDay;
        }
        if (DailyClock.due(z, this.cfg.reportAt(), parseDay(reportDay), this.cfg.catchUp())) {
            this.startReport(true, null);
        }
        if (this.cfg.backupEnabled() && DailyClock.due(z, this.cfg.backupAt(), parseDay(backupDay), true)) {
            this.startBackup(false, null);
        }
    }

    /** Paper tick end: lag spikes (main thread, cheap). */
    public void tickEnded(final double durationMs) {
        if (durationMs >= this.cfg.lagSpikeMs()) {
            synchronized (this.state) {
                this.state.metrics.lagSpike(System.currentTimeMillis(), durationMs);
            }
        }
    }

    public void playerJoined() {
        final int online = Bukkit.getOnlinePlayers().size();
        synchronized (this.state) {
            this.state.metrics.players(System.currentTimeMillis(), online);
        }
    }

    public void citizenKilled() {
        synchronized (this.state) {
            this.state.metrics.citizenKilled(System.currentTimeMillis());
        }
    }

    public Bridges bridges() {
        return this.bridges;
    }

    private static LocalDate parseDay(final String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    // ================================================================== I/O: log

    private void pollLog() {
        final long now = System.currentTimeMillis();
        final List<LogParser.Event> events;
        try {
            events = this.tailer.poll(now);
        } catch (final IOException e) {
            return;
        }
        final StrazConfig c = this.cfg;
        boolean railError = false;
        synchronized (this.state) {
            for (final LogParser.Event e : events) {
                final boolean isNew = this.state.errors.record(e, c.newErrorDays() * DAY);
                if ("Kolej".equalsIgnoreCase(e.plugin())) {
                    railError = true;
                }
                if (isNew && !"Straz".equalsIgnoreCase(e.plugin()) && !c.ignored(e.message())) {
                    if (this.pendingNew.isEmpty()) {
                        this.pendingSince = now;
                    }
                    if (this.pendingNew.size() < 50) {
                        this.pendingNew.add(new ErrorBook.Line(e.plugin(), e.message(), e.detail(), 1, e.time(), e.time(), true));
                    }
                }
            }
        }
        if (railError) {
            this.checkRail();
        }
        // new error kinds are collected for 20 s, then one alert lists them (a burst at startup = one message)
        if (!this.pendingNew.isEmpty() && now - this.pendingSince >= 20_000L) {
            final Alert a = pl.medivalrpg.straz.alert.SevereBatch.build(this.pendingNew, now);
            this.pendingNew.clear();
            this.alert(a, 0);
        }
    }

    // ================================================================== I/O: pool

    private Pool.Snapshot readPool() throws IOException {
        final Path f = this.pluginsDir.resolve("Skarbiec").resolve("bank").resolve("accounts.json");
        if (!Files.isRegularFile(f)) {
            throw new IOException("brak pliku Skarbiec/bank/accounts.json");
        }
        // snapshot first, then the operations after it (journal + the day's transaction logs; duplicates by sequence)
        final String snapshot = Files.readString(f, StandardCharsets.UTF_8);
        final Path bank = f.getParent();
        final List<String> after = new ArrayList<>();
        final Path journal = bank.resolve("journal.log");
        if (Files.isRegularFile(journal)) {
            after.addAll(Files.readAllLines(journal, StandardCharsets.UTF_8));
        }
        final LocalDate today = LocalDate.now(this.cfg.zone());
        for (final LocalDate d : List.of(today.minusDays(1), today, today.plusDays(1))) {
            final Path log = bank.resolve("logs").resolve("transakcje-" + d + ".log");
            if (Files.isRegularFile(log)) {
                after.addAll(Files.readAllLines(log, StandardCharsets.UTF_8));
            }
        }
        return Pool.parse(snapshot, after, this.cfg.expectedPool());
    }

    void checkPool() {
        final long now = System.currentTimeMillis();
        final Pool.Snapshot p;
        try {
            p = this.readPool();
        } catch (final IOException | RuntimeException e) {
            return; // Skarbiec missing or the file being replaced: next check
        }
        if (!p.certain()) {
            return; // gap in the replayed sequence (snapshot/log rotation in progress): next check
        }
        Alert alert = null;
        Alert info = null;
        synchronized (this.state) {
            this.state.poolLastTotal = p.total();
            this.state.poolLastCheck = now;
            this.state.poolLastSeq = p.seq();
            if (p.ok()) {
                if (!this.state.poolAlertTotal.isEmpty()) {
                    info = new Alert("INFO", "pool-ok|" + now, "pula znów zgodna",
                        List.of("Suma kont: " + Fmt.md(p.total()) + " = oczekiwana pula (zapis nr " + p.seq() + ")."), now);
                    this.state.alerts.clear("pool|" + this.state.poolAlertTotal);
                }
                this.state.poolMismatchStreak = 0;
                this.state.poolAlertTotal = "";
            } else {
                this.state.poolMismatchStreak++;
                // two checks in a row (different moments) confirm a real difference, not a file being replaced
                if (this.state.poolMismatchStreak >= 2 && !Long.toString(p.total()).equals(this.state.poolAlertTotal)) {
                    this.state.poolAlertTotal = Long.toString(p.total());
                    final List<String> lines = new ArrayList<>();
                    lines.add("Suma wszystkich kont: **" + Fmt.md(p.total()) + "**, oczekiwano " + Fmt.md(p.expected()) + ".");
                    lines.add("Różnica: **" + Fmt.signed(p.diff()) + " md** · kont ujemnych: " + p.negative() + " · zapis nr " + p.seq() + ".");
                    for (final Pool.Part part : p.parts()) {
                        lines.add("• " + part.label() + ": " + Fmt.md(part.amount()));
                    }
                    lines.add("Sprawdź /skarbswiata info i dziennik Skarbca. Straż niczego nie koryguje.");
                    alert = new Alert("POOL", "pool|" + p.total(), "rozbieżność zamkniętej puli", lines, now);
                }
            }
        }
        if (alert != null) {
            this.alert(alert, DAY);
        }
        if (info != null) {
            this.alert(info, 0);
        }
    }

    // ================================================================== I/O: rail

    private RailSummary.Summary readRail(final String status) throws IOException {
        final Path dir = this.pluginsDir.resolve("Kolej");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        final Path req = dir.resolve("network-commission").resolve("request.json");
        final Path jobs = dir.resolve("works").resolve("jobs.json");
        final long stamp = (Files.isRegularFile(req) ? Files.getLastModifiedTime(req).toMillis() : 0) * 31
            + (Files.isRegularFile(jobs) ? Files.getLastModifiedTime(jobs).toMillis() + Files.size(jobs) : 0);
        if (stamp != this.railFilesStamp) {
            this.railRequest = RailReader.request(dir);
            this.railJobs = RailReader.jobs(dir);
            this.railFilesStamp = stamp;
        }
        if (this.railRequest == null || !this.railRequest.enabled()) {
            return RailSummary.none(status);
        }
        long budgetBalance = -1;
        try {
            final Pool.Snapshot p = this.readPool();
            for (final Map.Entry<String, Long> e : p.balances().entrySet()) {
                if (e.getKey().startsWith("kolej:budzet_")) {
                    budgetBalance = Math.max(budgetBalance, 0) + e.getValue();
                }
            }
        } catch (final IOException | RuntimeException ignored) {
            // budget unknown
        }
        return RailSummary.of(this.railRequest.parts(), this.railRequest.maxTotalMd(), this.railJobs, budgetBalance, status);
    }

    void checkRail() {
        final long now = System.currentTimeMillis();
        final RailSummary.Summary r;
        try {
            r = this.readRail(this.kolejStatus);
        } catch (final IOException | RuntimeException e) {
            return;
        }
        this.lastRail = r;
        if (r == null || !r.present()) {
            return;
        }
        Alert alert = null;
        synchronized (this.state) {
            if (r.isStopped()) {
                final String key = r.stopKey();
                if (!key.equals(this.state.railStopKey)) {
                    this.state.railStopKey = key;
                    final List<String> lines = new ArrayList<>();
                    for (final RailSummary.Stop s : r.stops()) {
                        lines.add("⛔ **" + s.part() + "**: " + Fmt.clip(s.reason(), 300));
                    }
                    lines.add("Części: ✅ " + r.done() + " / " + r.parts() + " · wydano " + Fmt.md(r.spent()) + " z " + Fmt.md(r.budget()) + ".");
                    lines.add("Diagnoza: /kolej siec status. Nie ponawiać bez usunięcia przyczyny.");
                    alert = new Alert("RAIL", "rail|" + key, "budowa kolei zatrzymana", lines, now);
                }
            } else {
                this.state.railStopKey = "";
            }
            // no progress while building
            final int stallHours = this.cfg.railStallHours();
            if (stallHours > 0 && RailSummary.isBuildingStatus(r.status())) {
                if (r.blocksDone() != this.state.railBlocks) {
                    this.state.railBlocks = r.blocksDone();
                    this.state.railBlocksSince = now;
                    this.state.railStallAlerted = false;
                } else if (!this.state.railStallAlerted && now - this.state.railBlocksSince >= stallHours * 3_600_000L) {
                    this.state.railStallAlerted = true;
                    alert = new Alert("RAIL", "rail-stall|" + r.blocksDone(), "budowa kolei bez postępu",
                        List.of("Od " + stallHours + " h brak nowych bloków (" + Fmt.groups(r.blocksDone()) + " / "
                            + Fmt.groups(r.blocksTotal()) + ").", "Status: „" + Fmt.clip(r.status(), 200) + "”"), now);
                }
            } else {
                this.state.railBlocks = r.blocksDone();
                this.state.railBlocksSince = now;
                this.state.railStallAlerted = false;
            }
        }
        if (alert != null) {
            this.alert(alert, DAY);
        }
    }

    // ================================================================== alerts

    /** Gate, log, file and (when configured) Discord. Any thread. */
    public void alert(final Alert a, final long cooldownMs) {
        final AlertGate.Decision d;
        synchronized (this.state) {
            d = this.state.alerts.offer(a.key(), a.time(), cooldownMs, a.problem() ? this.cfg.alertsPerHour() : 1000);
            if (d.send() || !"duplikat".equals(d.why())) {
                this.state.addRecentAlert(ReportFormatter.formatTime(a.time(), this.cfg.zone()) + " " + a.icon() + " " + a.title());
            }
        }
        if (!d.send() && "duplikat".equals(d.why())) {
            return;
        }
        final StringBuilder text = new StringBuilder();
        text.append(a.icon()).append(" ALERT — ").append(a.title());
        for (final String l : a.lines()) {
            text.append("\n").append(l);
        }
        this.log.warning(text.toString().replace("**", "").replace("`", "") + (d.send() ? "" : " [nie wysłano na Discord: " + d.why() + "]"));
        this.io.execute(() -> this.appendAlertFile(a, text.toString()));
        if (d.send()) {
            this.discord.send(DiscordPayload.alert(a, this.cfg.zone(), this.cfg.username(), d.suppressedBefore()), "alert");
        }
    }

    private void appendAlertFile(final Alert a, final String text) {
        try {
            Files.createDirectories(this.reportsDir);
            final LocalDate day = Instant.ofEpochMilli(a.time()).atZone(this.cfg.zone()).toLocalDate();
            final Path f = this.reportsDir.resolve("alerty-" + day + ".md");
            final String block = "## " + ReportFormatter.formatTime(a.time(), this.cfg.zone()) + " " + text.replaceFirst("\n", "\n\n") + "\n\n";
            Files.writeString(f, block, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException e) {
            this.log.warning("Nie zapisano pliku alertów: " + e.getMessage());
        }
    }

    // ================================================================== report

    /** Main thread snapshot of the APIs, then the files and the formatting on the I/O thread. */
    public CompletableFuture<ReportFormatter.Report> startReport(final boolean automatic, final Runnable whenBusy) {
        if (!this.reportRunning.compareAndSet(false, true)) {
            if (whenBusy != null) {
                whenBusy.run();
            }
            return CompletableFuture.completedFuture(null);
        }
        final long now = System.currentTimeMillis();
        if (automatic) {
            synchronized (this.state) {
                // mark first: a failing report must not retry every minute
                this.state.lastReportDay = Instant.ofEpochMilli(now).atZone(this.cfg.zone()).toLocalDate().toString();
            }
        }
        final List<Bridges.Country> countries = this.bridges.countries();
        final int population = this.bridges.population(countries);
        final Map<Bridges.Country, Integer> soldiers = this.bridges.soldiers(countries);
        final int online = Bukkit.getOnlinePlayers().size();
        final double tpsNow = Bukkit.getTPS()[0];
        final String status = this.bridges.kolejStatus();
        this.kolejStatus = status;
        final String server = Bukkit.getName() + " " + Bukkit.getVersion();
        final CompletableFuture<ReportFormatter.Report> out = new CompletableFuture<>();
        this.io.execute(() -> {
            try {
                final ReportFormatter.Report r = this.buildAndPublish(now, automatic, population, soldiers, online, tpsNow, status, server);
                out.complete(r);
            } catch (final RuntimeException | LinkageError e) {
                this.log.log(Level.WARNING, "Raport: błąd przygotowania", e);
                out.completeExceptionally(e);
            } finally {
                this.reportRunning.set(false);
            }
        });
        return out;
    }

    private ReportFormatter.Report buildAndPublish(final long now, final boolean automatic, final int population,
                                                   final Map<Bridges.Country, Integer> soldiers, final int online,
                                                   final double tpsNow, final String status, final String server) {
        final StrazConfig c = this.cfg;
        final ZonedDateTime at = Instant.ofEpochMilli(now).atZone(c.zone());
        // pool
        Pool.Snapshot pool = null;
        String poolError = "";
        try {
            pool = this.readPool();
        } catch (final IOException | RuntimeException e) {
            poolError = e.getMessage();
        }
        // flows
        final List<ReportData.AccountFlow> flows = new ArrayList<>();
        String flowsError = "";
        if (pool != null) {
            try {
                final List<String> lines = this.transactionLines(at.toLocalDate());
                for (final String[] acc : new String[][] {{"world:households", "Gospodarstwa NPC (world:households)"},
                    {"world:merchant", "Kupiec (world:merchant)"}}) {
                    final Flows.Flow day = Flows.of(acc[0], lines, now - DAY, now);
                    double avg = Double.NaN;
                    int avgDays = 0;
                    synchronized (this.state) {
                        final List<StrazState.DayNet> hist = new ArrayList<>(this.state.flowHistory.getOrDefault(acc[0], List.of()));
                        if (automatic) {
                            hist.removeIf(h -> h.day.equals(at.toLocalDate().toString()));
                            hist.add(new StrazState.DayNet(at.toLocalDate().toString(), day.net()));
                            while (hist.size() > 7) {
                                hist.remove(0);
                            }
                            this.state.flowHistory.put(acc[0], hist);
                        }
                        if (hist.size() >= 2) {
                            avg = hist.stream().mapToLong(h -> h.net).average().orElse(Double.NaN);
                            avgDays = hist.size();
                        }
                    }
                    final long balance = pool.balance(acc[0]);
                    final long days = Flows.daysLeft(balance, Double.isNaN(avg) ? day.net() : avg);
                    flows.add(new ReportData.AccountFlow(acc[0], acc[1], balance, day, avg, avgDays, days));
                }
            } catch (final IOException | RuntimeException e) {
                flowsError = e.getMessage();
            }
        } else {
            flowsError = "brak puli";
        }
        // errors
        final List<ErrorBook.Group> errors;
        synchronized (this.state) {
            errors = this.state.errors.summary(now, DAY, c.newErrorDays() * DAY);
        }
        final long errorsTotal = errors.stream().mapToLong(ErrorBook.Group::count).sum();
        // rail
        RailSummary.Summary rail = null;
        String railError = "";
        try {
            rail = this.readRail(status);
            if (rail == null) {
                railError = "Kolej nie jest zainstalowana";
            }
        } catch (final IOException | RuntimeException e) {
            railError = e.getMessage();
        }
        // perf
        final Metrics.Window perf;
        synchronized (this.state) {
            perf = this.state.metrics.window(now, DAY);
        }
        // backup
        final ReportData.Backup backup = this.backupInfo(now);
        // people
        ReportData.People people;
        try {
            final Path lud = this.pluginsDir.resolve("Ludnosc");
            if (!Files.isRegularFile(lud.resolve("data.json"))) {
                people = new ReportData.People(population, null, perf.citizensKilled(), "brak plików Ludnosci");
            } else {
                final String data = readJsonWithBak(lud.resolve("data.json"));
                final Path lifeFile = lud.resolve("zycie-obywateli.json");
                final String life = Files.isRegularFile(lifeFile) ? readJsonWithBak(lifeFile) : null;
                final Census.Data cd = Census.parse(data, life);
                final Set<String> previous;
                synchronized (this.state) {
                    previous = this.state.citizens == null ? null : new HashSet<>(this.state.citizens);
                    if (automatic) {
                        this.state.citizens = new ArrayList<>(cd.citizens());
                    }
                }
                people = new ReportData.People(population, Census.stats(cd, previous, now - DAY, now), perf.citizensKilled(), "");
            }
        } catch (final IOException | RuntimeException e) {
            people = new ReportData.People(population, null, perf.citizensKilled(), e.getMessage());
        }
        // army
        ReportData.Army army;
        if (soldiers == null) {
            army = new ReportData.Army(false, 0, 0, List.of(), "Armia niedostępna");
        } else {
            final int total = soldiers.values().stream().mapToInt(Integer::intValue).sum();
            final List<ReportData.Top> top = soldiers.entrySet().stream()
                .sorted(Map.Entry.<Bridges.Country, Integer>comparingByValue().reversed()).limit(3)
                .map(e -> new ReportData.Top(e.getKey().name(), e.getValue())).toList();
            army = new ReportData.Army(true, total, this.armyLimit(), top, "");
        }
        final ReportData data = new ReportData(at, server, pool, poolError, flows, flowsError, errors, errorsTotal, rail, railError,
            perf, online, tpsNow, c.tpsThreshold(), c.lagSpikeMs(), backup, people, army);
        final ReportFormatter.Report report = ReportFormatter.format(data);
        final String md = ReportFormatter.markdown(report);
        final String name = at.toLocalDate() + (automatic ? "" : "-" + FILE_HM.format(at)) + ".md";
        try {
            Files.createDirectories(this.reportsDir);
            final Path f = this.reportsDir.resolve(name);
            final Path tmp = this.reportsDir.resolve(name + ".tmp");
            Files.writeString(tmp, md, StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            synchronized (this.state) {
                this.state.lastReportAt = now;
                this.state.lastReportFile = name;
            }
        } catch (final IOException e) {
            this.log.warning("Raport: nie zapisano pliku " + name + " (" + e.getMessage() + ")");
        }
        if (this.discord.configured() && this.discord.valid()) {
            this.log.info("Raport " + (automatic ? "dzienny" : "na żądanie") + ": " + report.statusLine() + " — plik raporty/" + name + ", wysyłam na Discord.");
            this.discord.send(DiscordPayload.report(report, c.username()), "raport");
        } else {
            this.log.info("Raport " + (automatic ? "dzienny" : "na żądanie") + " (plik raporty/" + name + "):\n" + md.replace("**", "").replace("`", ""));
        }
        this.pruneReportFiles(now);
        this.saveQuietly();
        return report;
    }

    private List<String> transactionLines(final LocalDate today) throws IOException {
        final Path logs = this.pluginsDir.resolve("Skarbiec").resolve("bank").resolve("logs");
        final List<String> lines = new ArrayList<>();
        for (final LocalDate d : List.of(today.minusDays(1), today)) {
            final Path f = logs.resolve("transakcje-" + d + ".log");
            if (Files.isRegularFile(f)) {
                lines.addAll(Files.readAllLines(f, StandardCharsets.UTF_8));
            }
        }
        // the log of the server zone's "today" may already be tomorrow's in UTC files: include it when present
        final Path next = logs.resolve("transakcje-" + today.plusDays(1) + ".log");
        if (Files.isRegularFile(next)) {
            lines.addAll(Files.readAllLines(next, StandardCharsets.UTF_8));
        }
        return lines;
    }

    static String readJsonWithBak(final Path f) throws IOException {
        final String s = Files.readString(f, StandardCharsets.UTF_8);
        try {
            com.google.gson.JsonParser.parseString(s);
            return s;
        } catch (final RuntimeException e) {
            final Path bak = f.resolveSibling(f.getFileName() + ".bak");
            if (Files.isRegularFile(bak)) {
                return Files.readString(bak, StandardCharsets.UTF_8);
            }
            throw e;
        }
    }

    private int armyLimit() {
        final Path f = this.pluginsDir.resolve("Armia").resolve("config.yml");
        if (Files.isRegularFile(f)) {
            final int v = YamlConfiguration.loadConfiguration(f.toFile()).getInt("limits.global-max", 1000);
            return v > 0 ? v : 1000;
        }
        return 1000;
    }

    private void pruneReportFiles(final long now) {
        final LocalDate limit = Instant.ofEpochMilli(now).atZone(this.cfg.zone()).toLocalDate().minusDays(this.cfg.reportFileDays());
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(this.reportsDir, "*.md")) {
            for (final Path p : ds) {
                final String n = p.getFileName().toString();
                final String date = n.startsWith("alerty-") ? n.substring(7, Math.min(n.length(), 17)) : n.substring(0, Math.min(n.length(), 10));
                final LocalDate d = parseDay(date);
                if (d != null && d.isBefore(limit)) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (final IOException ignored) {
            // next time
        }
    }

    /** Newest report file (for /straz raport). */
    public Path latestReportFile() {
        try (var s = Files.list(this.reportsDir)) {
            return s.filter(p -> {
                final String n = p.getFileName().toString();
                return n.endsWith(".md") && !n.startsWith("alerty-");
            }).max(Comparator.comparing(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (final IOException e) {
                    return 0L;
                }
            })).orElse(null);
        } catch (final IOException e) {
            return null;
        }
    }

    // ================================================================== backup

    public Path backupDir() {
        final Path p = Path.of(this.cfg.backupDir());
        return p.isAbsolute() ? p : this.serverDir.resolve(p).normalize();
    }

    List<BackupPlan.Entry> backupEntries() throws IOException {
        final Path dir = this.backupDir();
        final List<BackupPlan.Entry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "plugins-*.zip")) {
            for (final Path p : ds) {
                final var at = BackupPlan.parse(p.getFileName().toString());
                if (at.isPresent()) {
                    out.add(new BackupPlan.Entry(p.getFileName().toString(), at.get(), Files.size(p)));
                }
            }
        }
        return out;
    }

    public ReportData.Backup backupInfo(final long now) {
        final ReportData.LastRun last;
        synchronized (this.state) {
            final StrazState.LastBackup b = this.state.lastBackup;
            last = new ReportData.LastRun(b.at, b.ok, b.file, b.bytes, b.files, b.durationMs, b.warnings, b.error);
        }
        try {
            final List<BackupPlan.Entry> entries = this.backupEntries();
            final BackupPlan.Health h = BackupPlan.health(entries,
                LocalDateTime.ofInstant(Instant.ofEpochMilli(now), this.cfg.zone()), this.cfg.backupAt(), 26, this.cfg.backupMinBytes());
            return new ReportData.Backup(h, entries.size(), last, this.cfg.backupEnabled(), "");
        } catch (final IOException | RuntimeException e) {
            return new ReportData.Backup(null, 0, last, this.cfg.backupEnabled(), e.getMessage());
        }
    }

    /** Starts a backup in the background; {@code manual} = /straz kopia teraz (own file name). */
    public CompletableFuture<BackupWriter.Result> startBackup(final boolean manual, final Runnable whenBusy) {
        if (!this.backupRunning.compareAndSet(false, true)) {
            if (whenBusy != null) {
                whenBusy.run();
            }
            return CompletableFuture.completedFuture(null);
        }
        final long now = System.currentTimeMillis();
        final ZonedDateTime at = Instant.ofEpochMilli(now).atZone(this.cfg.zone());
        if (!manual) {
            synchronized (this.state) {
                this.state.lastBackupDay = at.toLocalDate().toString();
            }
        }
        final StrazConfig c = this.cfg;
        final String server = Bukkit.getName() + " " + Bukkit.getVersion();
        final CompletableFuture<BackupWriter.Result> out = new CompletableFuture<>();
        this.backupThread.execute(() -> {
            try {
                out.complete(this.runBackup(c, manual, at, server));
            } catch (final RuntimeException | LinkageError e) {
                out.completeExceptionally(e);
            } finally {
                this.backupRunning.set(false);
            }
        });
        return out;
    }

    private BackupWriter.Result runBackup(final StrazConfig c, final boolean manual, final ZonedDateTime at, final String server) {
        final Path dir = this.backupDir();
        final String name = manual ? BackupPlan.manualName(at.toLocalDateTime()) : BackupPlan.nightlyName(at.toLocalDate());
        final Path target = dir.resolve(name);
        BackupWriter.Result result;
        try {
            Files.createDirectories(dir);
            final List<BackupPlan.Entry> before = this.backupEntries();
            final long lastSize = BackupPlan.sorted(before).stream().findFirst().map(BackupPlan.Entry::bytes).orElse(0L);
            final long need = lastSize * c.backupSpaceFactor() + 100_000_000L;
            final long free = Files.getFileStore(dir).getUsableSpace();
            if (free < need) {
                result = new BackupWriter.Result(false, name, 0, 0, 0, List.of(),
                    "za mało miejsca na dysku: wolne " + Fmt.bytes(free) + ", wymagane " + Fmt.bytes(need));
            } else {
                result = new BackupWriter(this.pluginsDir, c.backupDirs(), c.backupExcludes(), c.backupMbPerSecond()).write(target, server);
            }
        } catch (final IOException | RuntimeException e) {
            result = new BackupWriter.Result(false, name, 0, 0, 0, List.of(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        synchronized (this.state) {
            final StrazState.LastBackup b = this.state.lastBackup;
            b.at = at.toInstant().toEpochMilli();
            b.ok = result.ok();
            b.file = result.file();
            b.bytes = result.bytes();
            b.files = result.files();
            b.durationMs = result.durationMs();
            b.warnings = result.warnings().size();
            b.error = result.error();
        }
        final long now = System.currentTimeMillis();
        if (result.ok()) {
            this.log.info("Kopia " + result.file() + ": " + result.files() + " plików, " + Fmt.bytes(result.bytes()) + ", "
                + Fmt.dec1(result.durationMs() / 1000.0) + " s, zweryfikowana." + (result.warnings().isEmpty() ? ""
                : " Ostrzeżenia: " + String.join("; ", result.warnings())));
            // rotation (only plugins-*.zip names) and size sanity
            try {
                final List<BackupPlan.Entry> entries = this.backupEntries();
                for (final String old : BackupPlan.toDelete(entries, c.backupKeepDays())) {
                    Files.deleteIfExists(dir.resolve(old));
                    this.log.info("Kopia: usunięto starą " + old + " (zachowuję " + c.backupKeepDays() + " dni).");
                }
                final BackupPlan.Health h = BackupPlan.health(this.backupEntries(), at.toLocalDateTime(), c.backupAt(), 26, c.backupMinBytes());
                if (!h.ok()) {
                    this.alert(new Alert("BACKUP", "backup-size|" + result.file(), "kopia podejrzana",
                        List.of(result.file() + " (" + Fmt.bytes(result.bytes()) + "): " + h.summary() + "."), now), DAY);
                }
            } catch (final IOException e) {
                this.log.warning("Kopia: rotacja nieudana (" + e.getMessage() + ")");
            }
        } else {
            this.alert(new Alert("BACKUP", "backup-fail|" + name, "nieudana kopia danych pluginów",
                List.of(name + ": " + Fmt.clip(result.error(), 400), "Kolejna próba: następna noc albo /straz kopia teraz."), now), 6 * 3_600_000L);
        }
        this.saveQuietly();
        return result;
    }

    // ================================================================== misc

    public RailSummary.Summary lastRail() {
        return this.lastRail;
    }

    public String kolejStatus() {
        return this.kolejStatus;
    }

    public LowTpsDetector tpsDetector() {
        return this.tps;
    }

    private void saveAndPrune() {
        final long now = System.currentTimeMillis();
        synchronized (this.state) {
            this.state.errors.prune(now, 3 * DAY, 30 * DAY);
            this.state.metrics.prune(now, 3 * DAY);
            this.state.alerts.prune(now, 7 * DAY);
        }
        this.saveQuietly();
    }

    void saveQuietly() {
        try {
            this.store.save(this.state);
        } catch (final IOException e) {
            this.log.warning("stan.json: nie zapisano (" + e.getMessage() + ")");
        }
    }

    /** Runs on the I/O thread (for commands needing files). */
    public void io(final Runnable r) {
        this.io.execute(this.guard("komenda", r));
    }
}
