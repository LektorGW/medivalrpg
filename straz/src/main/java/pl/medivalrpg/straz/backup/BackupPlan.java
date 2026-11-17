// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.backup;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names, rotation and health check of the plugin-data backups (pure). Nightly: {@code plugins-RRRRMMDD.zip};
 * manual ({@code /straz kopia teraz}): {@code plugins-RRRRMMDD-HHmm.zip}. Only files matching this pattern are ever
 * deleted by the rotation.
 */
public final class BackupPlan {

    private static final Pattern NAME = Pattern.compile("^plugins-(\\d{8})(?:-(\\d{4}))?\\.zip$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HHmm");

    /** A backup file on disk. */
    public record Entry(String name, LocalDateTime at, long bytes) {
    }

    /** Health of the newest backup for the report. */
    public record Health(boolean ok, String summary, Entry latest) {
    }

    private BackupPlan() {
    }

    public static String nightlyName(final LocalDate day) {
        return "plugins-" + DAY.format(day) + ".zip";
    }

    public static String manualName(final LocalDateTime at) {
        return "plugins-" + DAY.format(at.toLocalDate()) + "-" + HM.format(at) + ".zip";
    }

    /** Parses a backup name; empty for any other file. Nightly files get the time 00:00 of their day. */
    public static Optional<LocalDateTime> parse(final String name) {
        final Matcher m = NAME.matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            final LocalDate d = LocalDate.parse(m.group(1), DAY);
            final LocalTime t = m.group(2) == null ? LocalTime.MIDNIGHT : LocalTime.parse(m.group(2), HM);
            return Optional.of(LocalDateTime.of(d, t));
        } catch (final RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Newest first. */
    public static List<Entry> sorted(final List<Entry> entries) {
        final List<Entry> out = new ArrayList<>(entries);
        out.sort(Comparator.comparing(Entry::at).reversed().thenComparing(Entry::name, Comparator.reverseOrder()));
        return out;
    }

    /**
     * Files to delete: everything outside the newest {@code keepDays} distinct days (nightly and manual of a kept day
     * stay). Never deletes the newest file.
     */
    public static List<String> toDelete(final List<Entry> entries, final int keepDays) {
        final List<Entry> sorted = sorted(entries);
        final Set<LocalDate> kept = new LinkedHashSet<>();
        for (final Entry e : sorted) {
            if (kept.size() >= Math.max(1, keepDays)) {
                break;
            }
            kept.add(e.at().toLocalDate());
        }
        final List<String> out = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            final Entry e = sorted.get(i);
            if (!kept.contains(e.at().toLocalDate())) {
                out.add(e.name());
            }
        }
        return out;
    }

    /**
     * Is there a fresh, reasonably sized backup? Fresh = taken within {@code maxAgeHours} before {@code now}
     * (nightly files count from their scheduled time {@code nightlyAt}); reasonable = at least {@code minBytes} and at
     * least half of the previous backup.
     */
    public static Health health(final List<Entry> entries, final LocalDateTime now, final LocalTime nightlyAt,
                                final int maxAgeHours, final long minBytes) {
        final List<Entry> sorted = sorted(entries);
        if (sorted.isEmpty()) {
            return new Health(false, "brak kopii na dysku", null);
        }
        final Entry latest = sorted.get(0);
        final LocalDateTime taken = latest.at().toLocalTime().equals(LocalTime.MIDNIGHT) && isNightly(latest.name())
            ? latest.at().toLocalDate().atTime(nightlyAt) : latest.at();
        final List<String> problems = new ArrayList<>();
        if (taken.isBefore(now.minusHours(maxAgeHours))) {
            problems.add("najnowsza kopia jest starsza niż " + maxAgeHours + " h");
        }
        if (latest.bytes() < minBytes) {
            problems.add("podejrzanie mała (" + latest.bytes() + " B, minimum " + minBytes + " B)");
        }
        if (sorted.size() > 1 && latest.bytes() * 2 < sorted.get(1).bytes()) {
            problems.add("mniej niż połowa poprzedniej kopii");
        }
        return new Health(problems.isEmpty(), problems.isEmpty() ? "OK" : String.join("; ", problems), latest);
    }

    public static boolean isNightly(final String name) {
        final Matcher m = NAME.matcher(name);
        return m.matches() && m.group(2) == null;
    }

    /** Today in the zone (helper for callers). */
    public static LocalDate today(final ZoneId zone, final long now) {
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    }
}
