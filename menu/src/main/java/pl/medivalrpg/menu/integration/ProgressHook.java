// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import pl.medivalrpg.menu.tutorial.TutorialRules;
import pl.medivalrpg.menu.tutorial.WorldState;

/**
 * Reads the start of a player from the public APIs of Skarbiec, Kraje, Ludnosc and Armia (ServicesManager)
 * <b>by reflection</b>, like {@link KrajeHook}: Menu has no compile-time dependency on them. Only read methods are
 * called ({@code balance}, {@code countCoins}, {@code getCountryOf}, {@code housing}, {@code hasBuilding},
 * {@code info}, {@code soldierCount}); every failure means "unknown" for that value and is logged once. Main thread.
 */
public final class ProgressHook {

    static final String SKARBIEC = "pl.medivalrpg.skarbiec.api.SkarbiecApi";
    static final String KRAJE = "pl.medivalrpg.kraje.api.KrajeApi";
    static final String LUDNOSC = "pl.medivalrpg.ludnosc.api.LudnoscApi";
    static final String ARMIA = "pl.medivalrpg.armia.api.ArmiaApi";

    private final Logger logger;
    private final Set<String> warned = new HashSet<>();

    public ProgressHook(final Logger logger) {
        this.logger = logger;
    }

    public WorldState read(final Player player) {
        final Long money = this.money(player);
        final RegisteredServiceProvider<?> kraje = registration(KRAJE);
        if (kraje == null) {
            return new WorldState(money, null, null, null, null, null, null);
        }
        final Object country;
        final UUID countryId;
        try {
            country = unwrap(kraje.getService().getMethod("getCountryOf", UUID.class)
                .invoke(kraje.getProvider(), player.getUniqueId()));
            countryId = country != null && call(country, "id") instanceof UUID id ? id : null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warn("KrajeApi#getCountryOf", e);
            return new WorldState(money, null, null, null, null, null, null);
        }
        if (country == null) {
            return new WorldState(money, false, 0, 0, Set.of(), false, 0);
        }
        final Integer chunks = call(country, "chunkCount") instanceof Number n ? Integer.valueOf(n.intValue()) : null;
        if (countryId == null) {
            return new WorldState(money, true, chunks, null, null, null, null);
        }
        Integer housing = null;
        Set<String> buildings = null;
        Boolean construction = null;
        final RegisteredServiceProvider<?> ludnosc = registration(LUDNOSC);
        if (ludnosc != null) {
            try {
                final Class<?> api = ludnosc.getService();
                final Object provider = ludnosc.getProvider();
                if (api.getMethod("housing", UUID.class).invoke(provider, countryId) instanceof Number n) {
                    housing = n.intValue();
                }
                final Method hasBuilding = api.getMethod("hasBuilding", UUID.class, String.class);
                final Set<String> found = new HashSet<>();
                for (final String type : TutorialRules.WATCHED_BUILDINGS) {
                    if (Boolean.TRUE.equals(hasBuilding.invoke(provider, countryId, type))) {
                        found.add(type);
                    }
                }
                buildings = Set.copyOf(found);
                final Object info = unwrap(api.getMethod("info", UUID.class).invoke(provider, countryId));
                if (info != null && call(info, "buildings") instanceof Collection<?> built
                    && call(info, "sites") instanceof Collection<?> sites) {
                    construction = !built.isEmpty() || !sites.isEmpty();
                }
                if (!Boolean.TRUE.equals(construction) && ((housing != null && housing > 0) || !found.isEmpty())) {
                    construction = true;
                }
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                this.warn("LudnoscApi", e);
            }
        }
        Integer soldiers = null;
        final RegisteredServiceProvider<?> armia = registration(ARMIA);
        if (armia != null) {
            try {
                if (armia.getService().getMethod("soldierCount", UUID.class).invoke(armia.getProvider(), countryId)
                    instanceof Number n) {
                    soldiers = n.intValue();
                }
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                this.warn("ArmiaApi#soldierCount", e);
            }
        }
        return new WorldState(money, true, chunks, housing, buildings, construction, soldiers);
    }

    /** Bank account plus coins carried, in copper; {@code null} without Skarbiec. */
    private Long money(final Player player) {
        final RegisteredServiceProvider<?> skarbiec = registration(SKARBIEC);
        if (skarbiec == null) {
            return null;
        }
        try {
            final Class<?> api = skarbiec.getService();
            final Object provider = skarbiec.getProvider();
            long total = 0;
            if (api.getMethod("balance", String.class).invoke(provider, "player:" + player.getUniqueId())
                instanceof Number bank) {
                total += bank.longValue();
            }
            if (api.getMethod("countCoins", Player.class).invoke(provider, player) instanceof Number coins) {
                total += coins.longValue();
            }
            return total;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warn("SkarbiecApi", e);
            return null;
        }
    }

    private void warn(final String what, final Throwable e) {
        if (this.warned.add(what)) {
            this.logger.warning("Samouczek: " + what + " niedostępne (inna wersja pluginu?): " + e);
        }
    }

    private static RegisteredServiceProvider<?> registration(final String apiClass) {
        for (final Class<?> known : Bukkit.getServicesManager().getKnownServices()) {
            if (apiClass.equals(known.getName())) {
                return Bukkit.getServicesManager().getRegistration(known);
            }
        }
        return null;
    }

    private static Object unwrap(final Object o) {
        return o instanceof Optional<?> opt ? opt.orElse(null) : o;
    }

    /** Calls a public no-arg method; {@code null} when it does not exist. */
    private static Object call(final Object target, final String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
