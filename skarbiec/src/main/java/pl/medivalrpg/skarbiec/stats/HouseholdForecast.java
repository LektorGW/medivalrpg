// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily in/out of the households pool and how long it lasts (pure Java). The forecast uses the last 7 complete days
 * (today is partial and excluded); a non-negative average net means the pool is self-sustaining ({@code forecastDays}
 * null).
 */
public final class HouseholdForecast {

    private HouseholdForecast() {
    }

    public static Map<String, Object> of(final List<?> daily, final long balance, final ZoneId zone, final long now) {
        final LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        final List<Map<String, Object>> days = new ArrayList<>();
        long in = 0;
        long out = 0;
        final Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        LocalDate first = null;
        for (final Object o : daily) {
            final Map<?, ?> d = (Map<?, ?>) o;
            final long i = ((Number) d.get("in")).longValue();
            final long x = ((Number) d.get("out")).longValue();
            in += i;
            out += x;
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", d.get("day"));
            m.put("t", d.get("t"));
            m.put("in", i);
            m.put("out", x);
            m.put("net", i - x);
            m.put("byCategory", d.get("byCategory"));
            days.add(m);
            final LocalDate day = LocalDate.parse(String.valueOf(d.get("day")));
            byDay.put(day, new long[] {i, x});
            if (first == null || day.isBefore(first)) {
                first = day;
            }
        }
        // Average over the last up-to-7 COMPLETE days since the first logged day; quiet days count as zero.
        final LocalDate yesterday = today.minusDays(1);
        final int window = first == null || first.isAfter(yesterday) ? 0
            : (int) Math.min(7, java.time.temporal.ChronoUnit.DAYS.between(first, yesterday) + 1);
        long in7 = 0;
        long out7 = 0;
        for (int k = 0; k < window; k++) {
            final long[] d = byDay.get(yesterday.minusDays(k));
            if (d != null) {
                in7 += d[0];
                out7 += d[1];
            }
        }
        final Map<String, Object> r = new LinkedHashMap<>();
        r.put("daily", days);
        r.put("totals", FlowQuery.map("in", in, "out", out, "net", in - out));
        final Double avgIn = window == 0 ? null : (double) in7 / window;
        final Double avgOut = window == 0 ? null : (double) out7 / window;
        final Double avgNet = window == 0 ? null : (double) (in7 - out7) / window;
        r.put("avg7", FlowQuery.map("in", avgIn, "out", avgOut, "net", avgNet, "days", window));
        final Long forecast = avgNet == null || avgNet >= 0 ? null : (long) Math.floor(balance / -avgNet);
        r.put("forecastDays", forecast);
        r.put("forecastDate", forecast == null ? null : today.plusDays(forecast).toString());
        r.put("selfSustaining", avgNet != null && avgNet >= 0);
        return r;
    }
}
