// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.integration;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.SeasonInfo;
import pl.medivalrpg.skarbiec.merchant.MerchantAssortment;

/** The only class that references ludnosc-api (compile-only; provided at runtime by the Ludnosc plugin). */
final class LudnoscSeasons {

    private LudnoscSeasons() {
    }

    static Optional<Seasons.Season> current() {
        final LudnoscApi api = Bukkit.getServicesManager().load(LudnoscApi.class);
        if (api == null) {
            return Optional.empty();
        }
        final Optional<SeasonInfo> info = api.season();
        if (info.isEmpty()) {
            return Optional.empty();
        }
        final SeasonInfo s = info.get();
        final String id = s.id() == null ? "" : s.id().toLowerCase(Locale.ROOT).replace('ę', 'e');
        if (!MerchantAssortment.SEASONS.contains(id)) {
            return Optional.empty();
        }
        return Optional.of(new Seasons.Season(new MerchantAssortment.SeasonClock(id, MerchantAssortment.SEASONS.indexOf(id),
            s.startedAt(), s.endsAt()), s.name() == null || s.name().isBlank() ? Seasons.name(id) : s.name()));
    }
}
