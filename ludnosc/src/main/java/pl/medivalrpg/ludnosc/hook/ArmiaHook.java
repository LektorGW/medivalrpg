// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.hook;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import pl.medivalrpg.armia.api.ArmiaApi;

/** Armia API access (softdepend); only called after {@link Hooks#armia()}. */
public final class ArmiaHook {

    private ArmiaHook() {
    }

    /** Soldiers of a country (0 without Armia). */
    public static int soldierCount(final UUID country) {
        final ArmiaApi api = Bukkit.getServicesManager().load(ArmiaApi.class);
        return api == null ? 0 : api.soldierCount(country);
    }

    /** Country of an NPC soldier entity (any of its parts), if Armia knows it. */
    public static Optional<UUID> soldierCountry(final Entity entity) {
        final ArmiaApi api = Bukkit.getServicesManager().load(ArmiaApi.class);
        return api == null ? Optional.empty() : api.soldierCountry(entity);
    }
}
