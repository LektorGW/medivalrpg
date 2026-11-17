// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.model.Rank;

/** {@link Countries} backed by KrajeApi. Only touched after checking that the Kraje plugin is enabled. */
final class KrajeCountries implements Countries {

    private final KrajeApi api;

    private KrajeCountries(final KrajeApi api) {
        this.api = api;
    }

    static Countries tryCreate() {
        final KrajeApi api = Bukkit.getServicesManager().load(KrajeApi.class);
        return api == null ? null : new KrajeCountries(api);
    }

    private static Country map(final KrajInfo k, final UUID player) {
        int weight = 0;
        if (player != null) {
            final Rank r = k.rankOf(player);
            weight = r == null ? 0 : r.weight();
            if (player.equals(k.ruler())) {
                weight = Math.max(weight, Rank.RULER.weight());
            }
        }
        return new Country(k.id(), k.name(), k.treasuryAccount(), weight);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<Country> countryOf(final UUID player) {
        return this.api.getCountryOf(player).map(k -> map(k, player));
    }

    @Override
    public Optional<Country> byId(final UUID id) {
        return this.api.getCountry(id).map(k -> map(k, null));
    }

    @Override
    public Optional<Country> byName(final String name) {
        return this.api.getCountryByName(name).map(k -> map(k, null));
    }

    @Override
    public List<String> names() {
        return this.api.getCountries().stream().map(KrajInfo::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
