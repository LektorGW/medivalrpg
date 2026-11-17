// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.integration;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.config.SkarbiecConfig;

/** The only class that references kraje-api (compile-only; provided at runtime by the Kraje plugin). */
final class KrajeTerritory {

    private KrajeTerritory() {
    }

    static Territory.Verdict check(final Player player, final Location location, final SkarbiecConfig.Territory mode) {
        final KrajeApi api = Bukkit.getServicesManager().load(KrajeApi.class);
        if (api == null) {
            return Territory.Verdict.OK;
        }
        final Optional<KrajInfo> here = api.getCountryAt(location);
        if (here.isEmpty()) {
            return mode == SkarbiecConfig.Territory.OWN ? Territory.Verdict.WILDERNESS_DENIED : Territory.Verdict.OK;
        }
        final Optional<KrajInfo> own = api.getCountryOf(player.getUniqueId());
        return own.isPresent() && own.get().id().equals(here.get().id())
            ? Territory.Verdict.OK
            : Territory.Verdict.FOREIGN_COUNTRY;
    }

    static Optional<String> countryAccount(final String name) {
        final KrajeApi api = Bukkit.getServicesManager().load(KrajeApi.class);
        return api == null ? Optional.empty() : api.getCountryByName(name).map(k -> SkarbiecApi.countryAccount(k.id()));
    }

    static Optional<String> countryName(final UUID id) {
        final KrajeApi api = Bukkit.getServicesManager().load(KrajeApi.class);
        return api == null ? Optional.empty() : api.getCountry(id).map(KrajInfo::name);
    }

    static java.util.List<pl.medivalrpg.skarbiec.command.NpcTreasuryTransfer.Country> countries() {
        final KrajeApi api = Bukkit.getServicesManager().load(KrajeApi.class);
        if (api == null) {
            return java.util.List.of();
        }
        final java.util.List<pl.medivalrpg.skarbiec.command.NpcTreasuryTransfer.Country> out = new java.util.ArrayList<>();
        for (final KrajInfo k : api.getCountries()) {
            out.add(new pl.medivalrpg.skarbiec.command.NpcTreasuryTransfer.Country(k.id(), k.name(), k.npc(), k.hexColor()));
        }
        return out;
    }
}
