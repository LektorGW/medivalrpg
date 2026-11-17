// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.report;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import pl.medivalrpg.straz.economy.Pool;
import pl.medivalrpg.straz.kolej.RailSummary;
import pl.medivalrpg.straz.log.ErrorBook;
import pl.medivalrpg.straz.metrics.Metrics;
import pl.medivalrpg.straz.people.Census;
import pl.medivalrpg.straz.util.Fmt;

/**
 * Turns {@link ReportData} into the report sections (pure). The same sections become the Markdown file, the
 * in-game view and the Discord embed (see {@link DiscordPayload}).
 */
public final class ReportFormatter {

    /** One section: title with emoji and Markdown body. */
    public record Section(String title, String body) {
    }

    /** The finished report. */
    public record Report(String title, List<String> issues, List<Section> sections, long epochMs, String appendix) {

        public boolean ok() {
            return this.issues.isEmpty();
        }

        public String statusLine() {
            if (this.issues.isEmpty()) {
                return "✅ Wszystko w porządku.";
            }
            return "⚠️ Wymaga uwagi (" + this.issues.size() + "): " + String.join(" · ", this.issues);
        }
    }

    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Locale PL = Locale.forLanguageTag("pl-PL");
    private static final int MAX_ERROR_LINES = 10;
    private static final int MAX_LINES_PER_PLUGIN = 3;
    /** Households/merchant forecast shorter than this many days is an issue. */
    static final int LOW_DAYS = 14;

    private ReportFormatter() {
    }

    public static Report format(final ReportData d) {
        final ZoneId zone = d.at().getZone();
        final List<String> issues = new ArrayList<>();
        final List<Section> s = new ArrayList<>();
        s.add(new Section("💰 Zamknięta pula", pool(d, issues)));
        s.add(new Section("🏠 Gospodarstwa i kupiec", flows(d, issues)));
        s.add(new Section("🛑 Błędy ERROR/SEVERE (24 h)", errors(d, zone, issues)));
        s.add(new Section("🚂 Budowa kolei", rail(d, issues)));
        s.add(new Section("👥 Gracze i wydajność (24 h)", perf(d, zone, issues)));
        s.add(new Section("💾 Kopie zapasowe", backup(d, zone, issues)));
        s.add(new Section("🏘️ Ludność", people(d, issues)));
        s.add(new Section("⚔️ Armia", army(d, issues)));
        final String day = d.at().getDayOfWeek().getDisplayName(TextStyle.FULL, PL);
        final String title = "📋 Raport dzienny MEDIVALRPG — " + day + " " + DATE.format(d.at()) + ", " + HM.format(d.at());
        return new Report(title, List.copyOf(issues), List.copyOf(s), d.at().toInstant().toEpochMilli(), allErrors(d, zone));
    }

    /** Full error list for the file / in-game view (not sent to Discord); empty without errors. */
    static String allErrors(final ReportData d, final ZoneId zone) {
        if (d.errors() == null || d.errors().isEmpty()) {
            return "";
        }
        final StringBuilder b = new StringBuilder();
        int n = 0;
        for (final ErrorBook.Group g : d.errors()) {
            b.append("\n**").append(g.plugin()).append("**");
            for (final ErrorBook.Line l : g.lines()) {
                if (++n > 100) {
                    b.append("\n… (ponad 100 rodzajów — reszta w logu serwera)");
                    return b.toString().strip();
                }
                final String first = HM.format(java.time.Instant.ofEpochMilli(l.firstInWindow()).atZone(zone));
                final String last = HM.format(java.time.Instant.ofEpochMilli(l.last()).atZone(zone));
                b.append("\n• ").append(Fmt.groups(l.count())).append("× `").append(code(Fmt.clip(l.sample(), 300))).append("` (")
                    .append(first.equals(last) ? last : "od ok. " + first + " do " + last).append(")");
                if (l.detail() != null && !l.detail().isBlank()) {
                    b.append("\n  ↳ ").append(code(Fmt.clip(l.detail(), 300)));
                }
            }
        }
        return b.toString().strip();
    }

    // ------------------------------------------------------------------ sections

    static String pool(final ReportData d, final List<String> issues) {
        final Pool.Snapshot p = d.pool();
        if (p == null) {
            issues.add("pula nieodczytana");
            return "❔ Nie odczytano puli: " + safe(d.poolError());
        }
        final StringBuilder b = new StringBuilder();
        if (p.ok()) {
            b.append("✅ Suma: **").append(Fmt.md(p.total())).append("** = oczekiwana pula");
        } else {
            issues.add("rozbieżność puli " + Fmt.signed(p.diff()) + " md");
            b.append("🔴 Suma: **").append(Fmt.md(p.total())).append("**, oczekiwano ").append(Fmt.md(p.expected()))
                .append(" — różnica **").append(Fmt.signed(p.diff())).append(" md**");
            if (p.negative() > 0) {
                b.append(" · konta ujemne: ").append(p.negative());
            }
        }
        b.append("\n").append(p.accountCount()).append(" kont · zapis nr ").append(p.seq());
        for (final Pool.Part part : p.parts()) {
            b.append("\n• ").append(part.label());
            if (part.accounts() > 1) {
                b.append(" (").append(part.accounts()).append(")");
            }
            b.append(": ").append(Fmt.md(part.amount()));
        }
        return b.toString();
    }

    static String flows(final ReportData d, final List<String> issues) {
        if (d.flows() == null || d.flows().isEmpty()) {
            return "❔ Brak danych" + (d.flowsError() == null || d.flowsError().isBlank() ? "" : ": " + d.flowsError());
        }
        final StringBuilder b = new StringBuilder();
        for (final ReportData.AccountFlow f : d.flows()) {
            if (b.length() > 0) {
                b.append("\n");
            }
            b.append("**").append(f.label()).append("**: ").append(Fmt.mdZl(f.balance()));
            if (f.day() != null) {
                b.append("\n  24 h: wpływy ").append(Fmt.signed(f.day().in())).append(" · wydatki ")
                    .append(Fmt.groups(-f.day().out())).append(" · netto ").append(Fmt.signed(f.day().net())).append(" md");
                if (f.day().grantsIn() > 0 || f.day().returnsOut() > 0) {
                    b.append(" · zasilenia z rezerwy ").append(Fmt.signed(f.day().grantsIn()));
                    if (f.day().returnsOut() > 0) {
                        b.append(", zwroty ").append(Fmt.groups(-f.day().returnsOut()));
                    }
                }
            }
            b.append("\n  Prognoza: ");
            if (f.daysLeft() < 0) {
                b.append("saldo nie maleje");
            } else {
                b.append("starczy na ≈ **").append(Fmt.groups(f.daysLeft())).append(" ").append(Fmt.plural(f.daysLeft(), "dzień", "dni", "dni"))
                    .append("**");
                if (f.daysLeft() < LOW_DAYS) {
                    issues.add(f.account() + " na " + f.daysLeft() + " dni");
                    b.append(" ⚠️");
                }
            }
            if (!Double.isNaN(f.avgNet())) {
                b.append(" (średnio ").append(Fmt.signed(Math.round(f.avgNet()))).append(" md/dzień z ")
                    .append(f.avgDays()).append(" ").append(Fmt.plural(f.avgDays(), "dnia", "dni", "dni")).append(")");
            }
        }
        return b.toString();
    }

    static String errors(final ReportData d, final ZoneId zone, final List<String> issues) {
        if (d.errors() == null) {
            return "❔ Nie odczytano logu serwera.";
        }
        if (d.errors().isEmpty()) {
            return "✅ Brak błędów.";
        }
        long fresh = 0;
        for (final ErrorBook.Group g : d.errors()) {
            for (final ErrorBook.Line l : g.lines()) {
                if (l.newInWindow()) {
                    fresh++;
                }
            }
        }
        issues.add(Fmt.count(d.errorsTotal(), "błąd", "błędy", "błędów") + " w logu");
        final StringBuilder b = new StringBuilder();
        b.append("Razem **").append(Fmt.groups(d.errorsTotal())).append("** w ").append(d.errors().size()).append(" ")
            .append(Fmt.plural(d.errors().size(), "pluginie", "pluginach", "pluginach"));
        if (fresh > 0) {
            b.append(" · nowe rodzaje: ").append(fresh).append(" 🆕");
        }
        int shown = 0;
        int hidden = 0;
        for (final ErrorBook.Group g : d.errors()) {
            b.append("\n**").append(g.plugin()).append("** — ").append(Fmt.groups(g.count())).append("× (")
                .append(Fmt.count(g.lines().size(), "rodzaj", "rodzaje", "rodzajów")).append(")");
            int inGroup = 0;
            for (final ErrorBook.Line l : g.lines()) {
                if (shown >= MAX_ERROR_LINES || inGroup >= MAX_LINES_PER_PLUGIN) {
                    hidden++;
                    continue;
                }
                shown++;
                inGroup++;
                b.append("\n• ").append(Fmt.groups(l.count())).append("× `").append(code(Fmt.clip(l.sample(), 110))).append("`");
                b.append(" (").append(HM.format(java.time.Instant.ofEpochMilli(l.last()).atZone(zone)));
                b.append(")");
                if (l.newInWindow()) {
                    b.append(" 🆕");
                }
            }
        }
        if (hidden > 0) {
            b.append("\n… i ").append(hidden).append(" ").append(Fmt.plural(hidden, "inny rodzaj", "inne rodzaje", "innych rodzajów"))
                .append(" (pełna lista: plik raportu i /straz raport)");
        }
        return b.toString();
    }

    static String rail(final ReportData d, final List<String> issues) {
        final RailSummary.Summary r = d.rail();
        if (r == null) {
            return "❔ Nie odczytano stanu kolei" + (d.railError() == null ? "" : ": " + d.railError());
        }
        if (!r.present()) {
            return "Brak zlecenia budowy sieci." + (r.status().isBlank() ? "" : " Status: „" + r.status() + "”");
        }
        final StringBuilder b = new StringBuilder();
        if (r.isStopped()) {
            issues.add("budowa kolei zatrzymana");
            b.append("⛔ **Zatrzymana**");
        } else if (r.done() == r.parts()) {
            b.append("✅ **Ukończona**");
        } else if (r.inProgress() == 0 && r.status().toLowerCase(Locale.ROOT).startsWith("oczekiwanie")) {
            b.append("⏸ Czeka (bez robót)");
        } else {
            b.append("🔨 W toku");
        }
        if (!r.status().isBlank()) {
            b.append(" · status: „").append(Fmt.clip(r.status(), 160)).append("”");
        }
        b.append("\nCzęści (z ").append(r.parts()).append("): ✅ ").append(r.done()).append(" ukończone · 🔨 ").append(r.inProgress())
            .append(" w toku · ⛔ ").append(r.stopped()).append(" zatrzymane · ⏳ ").append(r.waiting()).append(" czeka");
        if (r.blocksTotal() > 0) {
            b.append("\nBloki zleceń: ").append(Fmt.groups(r.blocksDone())).append(" / ").append(Fmt.groups(r.blocksTotal()))
                .append(" (").append(Fmt.percent(r.blocksDone(), r.blocksTotal())).append(")");
        }
        b.append("\nBudżet: ").append(Fmt.md(r.budget())).append(" · wydano **").append(Fmt.md(r.spent())).append("**");
        if (r.budgetBalance() >= 0) {
            b.append(" · saldo budżetu ").append(Fmt.md(r.budgetBalance()));
        } else {
            b.append(" · budżet jeszcze nieprzydzielony");
        }
        for (final RailSummary.Stop stop : r.stops()) {
            b.append("\n• ⛔ ").append(stop.part()).append(": ").append(Fmt.clip(stop.reason(), 200));
        }
        return b.toString();
    }

    static String perf(final ReportData d, final ZoneId zone, final List<String> issues) {
        final Metrics.Window w = d.perf();
        final StringBuilder b = new StringBuilder();
        if (w == null) {
            return "❔ Brak pomiarów.";
        }
        b.append("Szczyt graczy: **").append(w.peakPlayers()).append("**");
        if (w.peakAt() > 0) {
            b.append(" (").append(HM.format(java.time.Instant.ofEpochMilli(w.peakAt()).atZone(zone))).append(")");
        }
        b.append(" · teraz ").append(d.onlineNow());
        if (w.samples() == 0) {
            b.append("\nTPS: brak pomiarów (serwer działa krócej niż minutę)");
            return b.toString();
        }
        final boolean low = w.tpsMin() < d.tpsThreshold() || w.lowTpsMinutes() > 0;
        b.append("\nTPS: średnio **").append(Fmt.dec1(w.tpsAvg())).append("** · minimum ").append(Fmt.dec1(w.tpsMin()))
            .append(low ? " ⚠️" : "").append(" · teraz ").append(Fmt.dec1(d.tpsNow()));
        b.append("\nMinut z TPS poniżej ").append(Fmt.dec1(d.tpsThreshold())).append(": ").append(w.lowTpsMinutes())
            .append(" · lag spikes (tick ponad ").append(d.lagSpikeMs()).append(" ms): ").append(w.lagSpikes());
        if (w.lagSpikes() > 0) {
            b.append(" (najdłuższy ").append(Fmt.groups(Math.round(w.worstTickMs()))).append(" ms)");
        }
        b.append(" · próbek: ").append(w.samples());
        if (w.lowTpsMinutes() > 0) {
            issues.add("TPS poniżej " + Fmt.dec1(d.tpsThreshold()) + " przez " + w.lowTpsMinutes() + " min");
        }
        return b.toString();
    }

    static String backup(final ReportData d, final ZoneId zone, final List<String> issues) {
        final ReportData.Backup k = d.backup();
        if (k == null) {
            return "❔ Brak danych o kopiach.";
        }
        final StringBuilder b = new StringBuilder();
        if (!k.enabled()) {
            b.append("⚪ Nocna kopia danych pluginów wyłączona (kopia.wlaczona: false).");
        } else if (k.health() == null) {
            issues.add("kopie nieodczytane");
            b.append("❔ Nie odczytano katalogu kopii: ").append(safe(k.error()));
        } else {
            final var latest = k.health().latest();
            if (k.health().ok()) {
                b.append("✅ ");
            } else {
                issues.add("kopia: " + k.health().summary());
                b.append("🔴 ").append(k.health().summary()).append("\n");
            }
            if (latest != null) {
                b.append("Najnowsza: **").append(latest.name()).append("** · ").append(Fmt.bytes(latest.bytes()));
            }
            b.append(" · na dysku ").append(k.kept()).append(" ").append(Fmt.plural(k.kept(), "kopia", "kopie", "kopii"));
        }
        final ReportData.LastRun r = k.lastRun();
        if (r != null && r.at() > 0) {
            b.append("\nOstatnie uruchomienie ").append(DATE.format(java.time.Instant.ofEpochMilli(r.at()).atZone(zone)))
                .append(" ").append(HM.format(java.time.Instant.ofEpochMilli(r.at()).atZone(zone))).append(": ");
            if (r.ok()) {
                b.append("OK, ").append(Fmt.count(r.files(), "plik", "pliki", "plików")).append(", ")
                    .append(Fmt.dec1(r.durationMs() / 1000.0)).append(" s, zweryfikowana (CRC)");
                if (r.warnings() > 0) {
                    b.append(" · ostrzeżenia: ").append(r.warnings());
                }
            } else {
                if (k.enabled()) {
                    issues.add("nieudana kopia");
                }
                b.append("🔴 NIEUDANA — ").append(Fmt.clip(r.error(), 200));
            }
        }
        b.append("\nHosting spot.gg: kopie świata wg harmonogramu panelu (sprawdzać w panelu).");
        return b.toString();
    }

    static String people(final ReportData d, final List<String> issues) {
        final ReportData.People p = d.people();
        if (p == null) {
            return "❔ Brak danych.";
        }
        final Census.Stats st = p.stats();
        final StringBuilder b = new StringBuilder();
        final int citizens = p.apiPopulation() >= 0 ? p.apiPopulation() : st == null ? -1 : st.citizens();
        if (citizens < 0) {
            return "❔ Ludnosc niedostępna" + (p.error() == null || p.error().isBlank() ? "." : ": " + p.error());
        }
        b.append("Obywatele: **").append(Fmt.groups(citizens)).append("**");
        if (st == null) {
            b.append("\n❔ Szczegóły niedostępne: ").append(safe(p.error()));
            return b.toString();
        }
        b.append("\nUrodzenia: ").append(st.births());
        if (st.removed() >= 0) {
            b.append(" · ubyło z ewidencji: ").append(st.removed()).append(" (zgony ze starości, zabici, odejścia, pobór)")
                .append(" · przybyli osadnicy: ").append(st.arrived());
        } else {
            b.append(" · ubytki: od następnego raportu (pierwszy spis)");
        }
        b.append("\nZabici obywatele (zgony w świecie): ").append(p.killed());
        b.append("\nBudowy: ukończono **").append(st.buildingsFinished()).append("**");
        if (!st.buildingTypes().isEmpty()) {
            final List<String> types = new ArrayList<>();
            for (final Map.Entry<String, Integer> e : st.buildingTypes().entrySet()) {
                types.add(e.getKey() + " ×" + e.getValue());
            }
            b.append(" (").append(Fmt.clip(String.join(", ", types), 300)).append(")");
        }
        b.append(" · w toku ").append(st.sitesActive()).append(" ").append(Fmt.plural(st.sitesActive(), "plac", "place", "placów"));
        return b.toString();
    }

    static String army(final ReportData d, final List<String> issues) {
        final ReportData.Army a = d.army();
        if (a == null || !a.available()) {
            return "❔ Armia niedostępna" + (a == null || a.error() == null || a.error().isBlank() ? "." : ": " + a.error());
        }
        final StringBuilder b = new StringBuilder();
        final boolean over = a.total() > a.limit();
        if (over) {
            issues.add("wojsko ponad limit " + a.total() + "/" + a.limit());
        }
        b.append(over ? "🔴 " : "").append("Żołnierze: **").append(Fmt.groups(a.total())).append(" / ").append(Fmt.groups(a.limit()))
            .append("** (").append(Fmt.percent(a.total(), a.limit())).append(" limitu serwera)");
        if (!a.top().isEmpty()) {
            final List<String> top = new ArrayList<>();
            for (final ReportData.Top t : a.top()) {
                top.add(t.name() + " " + t.count());
            }
            b.append("\nNajwięcej: ").append(String.join(" · ", top));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ outputs

    /** Markdown file / in-game text. */
    public static String markdown(final Report r) {
        final StringBuilder b = new StringBuilder();
        b.append("# ").append(r.title()).append("\n\n").append(r.statusLine()).append("\n");
        for (final Section s : r.sections()) {
            b.append("\n## ").append(s.title()).append("\n").append(s.body()).append("\n");
        }
        if (r.appendix() != null && !r.appendix().isBlank()) {
            b.append("\n## 📄 Wszystkie błędy ERROR/SEVERE (24 h)\n").append(r.appendix()).append("\n");
        }
        b.append("\n---\nStraż MEDIVALRPG · © 2026 Maciej Kaczmarczyk — lektoro.pl\n");
        return b.toString();
    }

    /** {@code "30.10 09:00"} in the zone. */
    public static String formatTime(final long epochMs, final ZoneId zone) {
        return DateTimeFormatter.ofPattern("dd.MM HH:mm").format(java.time.Instant.ofEpochMilli(epochMs).atZone(zone));
    }

    static String code(final String s) {
        return s.replace('`', '\'');
    }

    static String safe(final String s) {
        return s == null || s.isBlank() ? "nieznany błąd" : s;
    }
}
