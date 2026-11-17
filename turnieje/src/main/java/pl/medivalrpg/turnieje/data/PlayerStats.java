// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Tournament record of one player. Plain fields for Gson. */
public final class PlayerStats {

    public UUID uuid;
    public String name;
    public int wins;
    public int losses;
    /** Tournaments won. */
    public int titles;
    /** Tournaments taken part in. */
    public int tournaments;

    public PlayerStats() {
    }

    public PlayerStats(final UUID uuid, final String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public int fights() {
        return this.wins + this.losses;
    }

    /** Win ratio in percent (0 without fights). */
    public int winPercent() {
        return this.fights() == 0 ? 0 : (int) Math.round(100.0 * this.wins / this.fights());
    }

    /** Ranking order: titles, wins, fewer losses, name. */
    public static final Comparator<PlayerStats> RANKING = Comparator
        .comparingInt((PlayerStats s) -> -s.titles)
        .thenComparingInt(s -> -s.wins)
        .thenComparingInt(s -> s.losses)
        .thenComparing(s -> s.name == null ? "" : s.name.toLowerCase(Locale.ROOT));

    /** Players with at least one fight or title, sorted by {@link #RANKING}. */
    public static List<PlayerStats> ranking(final Collection<PlayerStats> all) {
        final List<PlayerStats> out = new ArrayList<>();
        for (final PlayerStats s : all) {
            if (s.fights() > 0 || s.titles > 0) {
                out.add(s);
            }
        }
        out.sort(RANKING);
        return out;
    }

    /** One page (1-based, clamped) of a list. */
    public static <T> List<T> page(final List<T> list, final int page, final int perPage) {
        final int pages = pages(list.size(), perPage);
        final int p = Math.max(1, Math.min(page, pages));
        final int from = (p - 1) * perPage;
        return list.subList(Math.min(from, list.size()), Math.min(from + perPage, list.size()));
    }

    public static int pages(final int size, final int perPage) {
        return Math.max(1, (size + perPage - 1) / perPage);
    }
}
