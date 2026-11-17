// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.service;

import java.util.Optional;
import org.bukkit.Bukkit;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.SeasonInfo;
import pl.medivalrpg.pory.logic.VisualSeason;

/** The only class that references ludnosc-api (compile-only; provided at runtime by the Ludnosc plugin). */
final class LudnoscBridge {

    private LudnoscBridge() {
    }

    static Optional<SeasonService.State> read() {
        final LudnoscApi api = Bukkit.getServicesManager().load(LudnoscApi.class);
        if (api == null) {
            return Optional.empty();
        }
        final Optional<SeasonInfo> info = api.season();
        if (info.isEmpty()) {
            return Optional.empty();
        }
        final SeasonInfo s = info.get();
        VisualSeason season = VisualSeason.byIndex(s.index());
        if (season == null) {
            season = VisualSeason.parse(s.id());
        }
        if (season == null) {
            return Optional.empty();
        }
        return Optional.of(new SeasonService.State(season, Math.max(1, s.day()), Math.max(1, s.daysPerSeason()),
            s.startedAt(), s.endsAt(), true));
    }
}
