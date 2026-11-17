// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.medivalrpg.straz.alert.AlertGate;
import pl.medivalrpg.straz.log.ErrorBook;
import pl.medivalrpg.straz.log.LogTailer;
import pl.medivalrpg.straz.metrics.Metrics;

/**
 * Persistent state of Straz ({@code plugins/Straz/stan.json}). Every access holds the monitor of this object
 * (main thread for samples, I/O threads for log/pool/report).
 */
public final class StrazState {

    /** Last backup run. */
    public static final class LastBackup {
        public long at;
        public boolean ok;
        public String file = "";
        public long bytes;
        public int files;
        public long durationMs;
        public int warnings;
        public String error = "";
    }

    /** One day of organic net flow of an account (for the forecast). */
    public static final class DayNet {
        public String day;
        public long net;

        public DayNet() {
        }

        public DayNet(final String day, final long net) {
            this.day = day;
            this.net = net;
        }
    }

    public int version = 1;
    public String lastReportDay = "";
    public long lastReportAt;
    public String lastReportFile = "";
    public String lastBackupDay = "";
    public LastBackup lastBackup = new LastBackup();
    public LogTailer.Position log = new LogTailer.Position();
    public ErrorBook errors = new ErrorBook();
    public Metrics metrics = new Metrics();
    public AlertGate alerts = new AlertGate();
    /** Citizen register of the last automatic report; null before the first one. */
    public List<String> citizens;
    public Map<String, List<DayNet>> flowHistory = new LinkedHashMap<>();
    /** Pool: consecutive mismatching checks and the active alert (total) — empty when the pool is fine. */
    public int poolMismatchStreak;
    public String poolAlertTotal = "";
    public long poolLastTotal = -1;
    public long poolLastCheck;
    public long poolLastSeq = -1;
    /** Rail: active stop (alert key) and the progress watch for the "no progress" alert. */
    public String railStopKey = "";
    public long railBlocks = -1;
    public long railBlocksSince;
    public boolean railStallAlerted;
    /** Last alerts for /straz alerty (newest last). */
    public List<String> recentAlerts = new ArrayList<>();

    public void addRecentAlert(final String line) {
        this.recentAlerts.add(line);
        while (this.recentAlerts.size() > 30) {
            this.recentAlerts.remove(0);
        }
    }
}
