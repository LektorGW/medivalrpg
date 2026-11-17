// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.ceremony;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.model.Rank;

import java.util.Optional;
import java.util.UUID;

/**
 * Optional link to the Kraje plugin (softdepend): is a player the ruler of a country? The Kraje API
 * classes are only touched inside this class, and only after Kraje is known to be enabled, so the
 * plugin loads without Kraje.
 */
public final class KrajeBridge {

    /** Result of a ruler check. */
    public enum Ruler {
        YES, NO, UNKNOWN
    }

    private KrajeBridge() {
    }

    public static boolean available() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Kraje")) {
            return false;
        }
        try {
            return Bukkit.getServicesManager().getRegistration(KrajeApi.class) != null;
        } catch (LinkageError e) {
            return false;
        }
    }

    /** {@link Ruler#UNKNOWN} when Kraje is missing. */
    public static Ruler isRuler(UUID player) {
        if (!available()) {
            return Ruler.UNKNOWN;
        }
        try {
            RegisteredServiceProvider<KrajeApi> rsp = Bukkit.getServicesManager().getRegistration(KrajeApi.class);
            if (rsp == null) {
                return Ruler.UNKNOWN;
            }
            KrajeApi api = rsp.getProvider();
            Optional<Rank> rank = api.getRank(player);
            return rank.isPresent() && rank.get() == Rank.RULER && api.getCountryOf(player).isPresent()
                    ? Ruler.YES : Ruler.NO;
        } catch (LinkageError | RuntimeException e) {
            return Ruler.UNKNOWN;
        }
    }

    /** Name of the player's country, or {@code null}. */
    public static String countryName(UUID player) {
        if (!available()) {
            return null;
        }
        try {
            RegisteredServiceProvider<KrajeApi> rsp = Bukkit.getServicesManager().getRegistration(KrajeApi.class);
            return rsp == null ? null : rsp.getProvider().getCountryName(player).orElse(null);
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }
}
