// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.kolej;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * State of the network construction of Kolej (pure): parts of {@code network-commission/request.json} matched with
 * the jobs of {@code works/jobs.json}, the live status line of the plugin and the one-off budget account.
 */
public final class RailSummary {

    /** A job as read from jobs.json (entries = number of block operations). */
    public record Job(String id, String status, int cursor, int entries, long cost, String reason) {
    }

    /** A stopped part with its reason. */
    public record Stop(String part, String reason) {
    }

    /** Summary. {@code budgetBalance} is -1 when no budget account exists yet. */
    public record Summary(boolean present, int parts, int done, int inProgress, int stopped, int waiting, long blocksDone,
                          long blocksTotal, long budget, long budgetBalance, long spent, long jobsCost, String status,
                          List<Stop> stops, String currentPart) {

        public boolean isStopped() {
            return !this.stops.isEmpty();
        }

        /** Stable key of the stop (alert deduplication). */
        public String stopKey() {
            final StringBuilder b = new StringBuilder();
            for (final Stop s : this.stops) {
                b.append(s.part()).append('|').append(s.reason()).append(';');
            }
            return b.toString();
        }
    }

    private RailSummary() {
    }

    public static Summary none(final String status) {
        return new Summary(false, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, status == null ? "" : status, List.of(), "");
    }

    /**
     * @param partIds       request parts in order
     * @param budget        request maxTotalMd
     * @param jobs          jobs by id
     * @param budgetBalance balance of the budget account, -1 when the account does not exist
     * @param status        live status line of Kolej ({@code networkCommissionStatus()}), null when unknown
     */
    public static Summary of(final List<String> partIds, final long budget, final Map<String, Job> jobs,
                             final long budgetBalance, final String status) {
        int done = 0, progress = 0, stopped = 0, waiting = 0;
        long blocksDone = 0, blocksTotal = 0, jobsCost = 0;
        final List<Stop> stops = new ArrayList<>();
        String current = "";
        final String st = status == null ? "" : status.strip();
        final boolean statusStopped = isStopStatus(st);
        for (final String id : partIds) {
            final Job j = jobs.get(id);
            if (j == null) {
                if (current.isEmpty()) {
                    current = id;
                }
                waiting++;
                continue;
            }
            blocksDone += Math.min(j.cursor(), j.entries());
            blocksTotal += j.entries();
            jobsCost += Math.max(0, j.cost());
            switch (j.status().toUpperCase(Locale.ROOT)) {
                case "DONE" -> done++;
                case "BLOCKED", "CANCELLED", "HELD" -> {
                    stopped++;
                    stops.add(new Stop(id, j.status() + (j.reason() == null || j.reason().isBlank() ? "" : ": " + j.reason())));
                    if (current.isEmpty()) {
                        current = id;
                    }
                }
                default -> {
                    progress++;
                    if (current.isEmpty()) {
                        current = id;
                    }
                }
            }
        }
        // a stop before any job exists (validation/quote failed) is only visible in the live status line
        if (statusStopped && stops.isEmpty()) {
            final String part = partMentioned(st, partIds, current);
            stops.add(new Stop(part, st));
            if (jobs.get(part) == null && waiting > 0) {
                waiting--;
                stopped++;
            }
        }
        final boolean allDone = !partIds.isEmpty() && done == partIds.size();
        final long spent;
        if (allDone || budgetBalance < 0) {
            spent = budgetBalance < 0 ? 0 : jobsCost;
        } else {
            spent = Math.max(0, budget - budgetBalance);
        }
        return new Summary(true, partIds.size(), done, progress, stopped, waiting, blocksDone, blocksTotal, budget,
            budgetBalance, spent, jobsCost, st, List.copyOf(stops), current);
    }

    /** Kolej's status line of a stopped construction ("Budowa wstrzymana: …", "Zatrzymane: …"). */
    public static boolean isStopStatus(final String status) {
        if (status == null) {
            return false;
        }
        final String s = status.strip().toLowerCase(Locale.ROOT);
        return s.startsWith("budowa wstrzymana") || s.startsWith("zatrzymane");
    }

    /** True when the line means work is running (for the "no progress" alert). */
    public static boolean isBuildingStatus(final String status) {
        return status != null && status.strip().toLowerCase(Locale.ROOT).startsWith("budowa ")
            && !isStopStatus(status);
    }

    private static String partMentioned(final String status, final List<String> parts, final String fallback) {
        for (final String p : parts) {
            if (status.contains(p)) {
                return p;
            }
        }
        return fallback.isEmpty() ? "?" : fallback;
    }
}
