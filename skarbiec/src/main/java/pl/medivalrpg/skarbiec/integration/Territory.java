// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.integration;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.skarbiec.config.SkarbiecConfig;

/**
 * Optional Kraje integration: may a player put a shop here? Without the Kraje plugin (or its API) the answer is
 * always yes. Kraje classes are only touched inside {@link KrajeTerritory}, which is loaded lazily the first
 * time Kraje is actually enabled — so Skarbiec runs fine without it.
 */
public final class Territory {

    public enum Verdict { OK, FOREIGN_COUNTRY, WILDERNESS_DENIED }

    private final Plugin plugin;
    private boolean broken;

    private Territory(final Plugin plugin) {
        this.plugin = plugin;
    }

    public static Territory create(final Plugin plugin) {
        return new Territory(plugin);
    }

    public Verdict check(final Player player, final Location location, final SkarbiecConfig.Territory mode) {
        if (mode == SkarbiecConfig.Territory.ANYWHERE || this.broken || !Bukkit.getPluginManager().isPluginEnabled("Kraje")) {
            return Verdict.OK;
        }
        try {
            return KrajeTerritory.check(player, location, mode);
        } catch (final LinkageError | RuntimeException e) {
            this.broken = true;
            this.plugin.getLogger().log(Level.WARNING, "Integracja z Kraje niedostępna (inna wersja API?) — "
                + "sklepy bez ograniczeń terenu", e);
            return Verdict.OK;
        }
    }

    /** Treasury account of the country with that name (admin commands {@code kraj:<nazwa>}); empty without Kraje. */
    public Optional<String> countryAccount(final String name) {
        if (this.broken || !Bukkit.getPluginManager().isPluginEnabled("Kraje")) {
            return Optional.empty();
        }
        try {
            return KrajeTerritory.countryAccount(name);
        } catch (final LinkageError | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Country name for a {@code country:<uuid>} account (log display); empty without Kraje. */
    public Optional<String> countryName(final UUID id) {
        if (this.broken || !Bukkit.getPluginManager().isPluginEnabled("Kraje")) {
            return Optional.empty();
        }
        try {
            return KrajeTerritory.countryName(id);
        } catch (final LinkageError | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** All countries known to Kraje (name, NPC flag, colour); empty without Kraje. */
    public java.util.List<pl.medivalrpg.skarbiec.command.NpcTreasuryTransfer.Country> countries() {
        if (this.broken || !Bukkit.getPluginManager().isPluginEnabled("Kraje")) {
            return java.util.List.of();
        }
        try {
            return KrajeTerritory.countries();
        } catch (final LinkageError | RuntimeException e) {
            return java.util.List.of();
        }
    }
}
