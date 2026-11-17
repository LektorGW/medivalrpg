// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.report;

import java.time.ZonedDateTime;
import java.util.List;
import pl.medivalrpg.straz.backup.BackupPlan;
import pl.medivalrpg.straz.economy.Flows;
import pl.medivalrpg.straz.economy.Pool;
import pl.medivalrpg.straz.kolej.RailSummary;
import pl.medivalrpg.straz.log.ErrorBook;
import pl.medivalrpg.straz.metrics.Metrics;
import pl.medivalrpg.straz.people.Census;

/**
 * Everything the daily report shows. A section that could not be read carries its error text instead of data
 * (null data + non-empty error), so one missing plugin never stops the report.
 */
public record ReportData(
    ZonedDateTime at,
    String server,
    Pool.Snapshot pool, String poolError,
    List<AccountFlow> flows, String flowsError,
    List<ErrorBook.Group> errors, long errorsTotal,
    RailSummary.Summary rail, String railError,
    Metrics.Window perf, int onlineNow, double tpsNow, double tpsThreshold, int lagSpikeMs,
    Backup backup,
    People people,
    Army army
) {

    /** Balance and flows of one world account (households, merchant). {@code avgNet} NaN without history. */
    public record AccountFlow(String account, String label, long balance, Flows.Flow day, double avgNet, int avgDays, long daysLeft) {
    }

    /** Backup check: files on disk and the last run. */
    public record Backup(BackupPlan.Health health, int kept, LastRun lastRun, boolean enabled, String error) {
    }

    /** Last backup run. */
    public record LastRun(long at, boolean ok, String file, long bytes, int files, long durationMs, int warnings, String error) {
    }

    /** Population: {@code apiPopulation} −1 when Ludnosc is missing. */
    public record People(int apiPopulation, Census.Stats stats, int killed, String error) {
    }

    /** Soldiers versus the global limit; top countries by soldiers. */
    public record Army(boolean available, int total, int limit, List<Top> top, String error) {
    }

    /** A country and its count. */
    public record Top(String name, int count) {
    }
}
