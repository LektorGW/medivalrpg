// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.integration;

import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.skarbiec.merchant.MerchantAssortment;

/**
 * Optional Ludnosc integration: the server-wide season for seasonal merchant goods. Ludnosc depends on Skarbiec, so
 * there is no softdepend; the Ludnosc API is looked up lazily (only {@link LudnoscSeasons} touches its classes). Without
 * Ludnosc, with seasons disabled or with an incompatible API the answer is "unknown" and merchants have no seasonal
 * goods. The answer is cached for a minute (main thread).
 */
public final class Seasons {

    private static final long CACHE_MILLIS = 60_000L;
    /** "Unknown" is kept only briefly: Ludnosc enables after Skarbiec (it depends on it). */
    private static final long CACHE_EMPTY_MILLIS = 5_000L;

    private final Plugin plugin;
    private boolean broken;
    /** When {@link #cached} was read; -1 = never (no arithmetic on a sentinel: MIN_VALUE would overflow). */
    private long checkedAt = -1;
    private Optional<Season> cached = Optional.empty();

    /** Current season: clock for {@link MerchantAssortment} and the Polish name. */
    public record Season(MerchantAssortment.SeasonClock clock, String name) {
    }

    public Seasons(final Plugin plugin) {
        this.plugin = plugin;
    }

    public Optional<Season> current() {
        final long now = System.currentTimeMillis();
        if (fresh(this.checkedAt, now, this.cached.isPresent())) {
            return this.cached;
        }
        this.checkedAt = now;
        this.cached = this.lookup();
        return this.cached;
    }

    /** Whether an answer read at {@code checkedAt} may still be used at {@code now} (pure, unit tested). */
    static boolean fresh(final long checkedAt, final long now, final boolean known) {
        return checkedAt >= 0 && now >= checkedAt && now - checkedAt < (known ? CACHE_MILLIS : CACHE_EMPTY_MILLIS);
    }

    private Optional<Season> lookup() {
        if (this.broken || !Bukkit.getPluginManager().isPluginEnabled("Ludnosc")) {
            return Optional.empty();
        }
        try {
            return LudnoscSeasons.current();
        } catch (final LinkageError | RuntimeException e) {
            this.broken = true;
            this.plugin.getLogger().log(Level.WARNING, "Integracja z Ludnosc (pory roku) niedostępna — kupcy bez towarów sezonowych", e);
            return Optional.empty();
        }
    }

    /** Polish name of a season id. */
    public static String name(final String id) {
        return switch (id == null ? "" : id) {
            case "wiosna" -> "Wiosna";
            case "lato" -> "Lato";
            case "jesien" -> "Jesień";
            case "zima" -> "Zima";
            default -> "—";
        };
    }
}
