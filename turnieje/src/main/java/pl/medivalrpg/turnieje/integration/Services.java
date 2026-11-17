// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.integration;

import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * Lazily resolved optional services (Skarbiec, Kraje). Re-resolved while missing, so a provider enabled later is
 * picked up; the provider classes are touched only when the plugin is enabled.
 */
public final class Services {

    private final Logger logger;
    private final pl.medivalrpg.turnieje.data.Storage storage;
    private Economy economy;
    private Countries countries;
    private boolean warnedEconomy;

    public Services(final Logger logger, final pl.medivalrpg.turnieje.data.Storage storage) {
        this.logger = logger;
        this.storage = storage;
    }

    public Economy economy() {
        if (this.storage.moneyBlocked()) return Economy.NONE;
        if (this.economy != null && this.economy.available() && Bukkit.getPluginManager().isPluginEnabled("Skarbiec")) {
            return this.economy;
        }
        this.economy = null;
        if (Bukkit.getPluginManager().isPluginEnabled("Skarbiec")) {
            try {
                this.economy = SkarbiecEconomy.tryCreate(this.storage);
            } catch (final LinkageError e) {
                if (!this.warnedEconomy) {
                    this.logger.warning("Skarbiec jest włączony, ale jego API jest niezgodne: " + e);
                    this.warnedEconomy = true;
                }
            }
        }
        return this.economy == null ? Economy.NONE : this.economy;
    }

    public Countries countries() {
        if (this.countries != null && Bukkit.getPluginManager().isPluginEnabled("Kraje")) {
            return this.countries;
        }
        this.countries = null;
        if (Bukkit.getPluginManager().isPluginEnabled("Kraje")) {
            try {
                this.countries = KrajeCountries.tryCreate();
            } catch (final LinkageError e) {
                this.logger.warning("Kraje jest włączone, ale jego API jest niezgodne: " + e);
            }
        }
        return this.countries == null ? Countries.NONE : this.countries;
    }
}
