// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.integration;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * Read-only numbers from the optional Ludnosc (NPC population, housing, NPC taxes), Armia (soldiers) and Postep (eras)
 * plugins, used by {@code /kraj info}, the map popup, the tab list, the claim limit and the vassal tribute.
 *
 * <p>Ludnosc, Armia and Postep depend on Kraje, so they are enabled <i>after</i> it, and listing them in Kraje's
 * {@code softdepend} would create a load-order cycle. Their services are therefore looked up lazily on each
 * call through {@code ServicesManager#getKnownServices()} and invoked reflectively: Kraje never links against
 * their classes, works in any load order and simply shows nothing while they are absent.</p>
 */
public final class Integrations {

    private static final String LUDNOSC = "pl.medivalrpg.ludnosc.api.LudnoscApi";
    private static final String ARMIA = "pl.medivalrpg.armia.api.ArmiaApi";
    private static final String POSTEP = "pl.medivalrpg.postep.api.PostepApi";

    private final Logger logger;
    private final Service ludnosc = new Service(LUDNOSC);
    private final Service armia = new Service(ARMIA);
    private final Service postep = new Service(POSTEP);

    public Integrations(final Logger logger) {
        this.logger = logger;
    }

    /** NPC citizens of a country (Ludnosc), empty when Ludnosc is absent. */
    public OptionalInt population(final UUID countryId) {
        return toInt(this.ludnosc.call("population", countryId));
    }

    /** Housing capacity (Ludnosc). */
    public OptionalInt housing(final UUID countryId) {
        return toInt(this.ludnosc.call("housing", countryId));
    }

    /** Expected daily tax income from NPC citizens in copper (Ludnosc). */
    public OptionalLong npcDailyTax(final UUID countryId) {
        final Object v = this.ludnosc.call("dailyTaxIncome", countryId);
        return v instanceof Number n ? OptionalLong.of(Math.max(0, n.longValue())) : OptionalLong.empty();
    }

    /** Soldiers of a country (Armia). */
    public OptionalInt soldiers(final UUID countryId) {
        return toInt(this.armia.call("soldierCount", countryId));
    }

    /**
     * Server-wide season as Ludnosc reports it ({@code LudnoscApi#season()}, record {@code SeasonInfo}).
     *
     * @param id   {@code wiosna}, {@code lato}, {@code jesien}, {@code zima}
     * @param name Polish name, e.g. "Jesień"
     * @param day  day of the season, 1-based
     * @param days length of one season in days
     */
    public record Season(String id, String name, int day, int days) {
    }

    /** Current season, empty when Ludnosc is absent, has seasons off or is too old to report them. Used by the tab list. */
    public Optional<Season> season() {
        final Object result = this.ludnosc.call("season", null, null);
        if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
            return Optional.empty();
        }
        final Object info = optional.get();
        final String name = str(callNoArg(info, "name"), "");
        if (name.isBlank()) {
            return Optional.empty();
        }
        final Object day = callNoArg(info, "day");
        final Object days = callNoArg(info, "daysPerSeason");
        return Optional.of(new Season(str(callNoArg(info, "id"), ""), name,
            day instanceof Number n ? n.intValue() : 0, days instanceof Number n ? n.intValue() : 0));
    }

    // ------------------------------------------------------------------ Postep (eras)

    /**
     * Era of a country as Postep reports it.
     *
     * @param id       1..4
     * @param name     Polish name, e.g. "Renesans"
     * @param color    {@code #RRGGBB} (empty when Postep gave no valid colour)
     * @param icon     one symbol, e.g. "⚜" (may be empty)
     * @param badge    MiniMessage badge, e.g. {@code <#C9A227>⚜ Renesans</#C9A227>}
     * @param progress 0..1 toward the next era
     * @param max      already in the last era
     */
    public record Era(int id, String name, String color, String icon, String badge, double progress, boolean max) {

        /** Coloured icon as MiniMessage (empty without an icon). */
        public String iconMini() {
            if (this.icon.isEmpty()) {
                return "";
            }
            return this.color.isEmpty() ? this.icon : "<color:" + this.color + ">" + this.icon + "</color>";
        }
    }

    /** Is the Postep service registered? */
    public boolean postepPresent() {
        return this.postep.present();
    }

    /** Era id only (cheap; used by the era titles). */
    public OptionalInt eraId(final UUID countryId) {
        return toInt(this.postep.call("era", countryId));
    }

    /** Full era view of a country, empty when Postep is absent or its API cannot be read. */
    public Optional<Era> era(final UUID countryId) {
        final OptionalInt id = this.eraId(countryId);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        final int era = id.getAsInt();
        final Object info = this.postep.call("eraInfo", int.class, era);
        final String name = info == null ? "Epoka " + era : str(callNoArg(info, "name"), "Epoka " + era);
        final String rawColor = info == null ? "" : str(callNoArg(info, "color"), "");
        final String color = rawColor.matches("#[0-9A-Fa-f]{6}") ? rawColor.toUpperCase(java.util.Locale.ROOT) : "";
        final String icon = str(this.postep.call("eraIcon", int.class, era), "");
        String badge = str(this.postep.call("eraBadge", UUID.class, countryId), "");
        if (badge.isBlank()) {
            final String text = (icon.isEmpty() ? "" : icon + " ") + name;
            badge = color.isEmpty() ? text : "<color:" + color + ">" + text + "</color>";
        }
        final Object rawProgress = this.postep.call("progress", UUID.class, countryId);
        final double progress = rawProgress instanceof Number n ? Math.max(0.0, Math.min(1.0, n.doubleValue())) : 0.0;
        final Object eras = this.postep.call("eras", null, null);
        final int count = eras instanceof List<?> list && !list.isEmpty() ? list.size() : 4;
        return Optional.of(new Era(era, name, color, icon, badge, progress, era >= count));
    }

    private static String str(final Object o, final String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static Object callNoArg(final Object target, final String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static OptionalInt toInt(final Object v) {
        return v instanceof Number n ? OptionalInt.of(n.intValue()) : OptionalInt.empty();
    }

    /** One reflectively accessed service; methods are cached per service class (reloads are picked up). */
    private final class Service {
        private final String className;
        private Class<?> cachedType;
        private final java.util.Map<String, Method> methods = new java.util.HashMap<>();
        private final java.util.Set<String> warned = new java.util.HashSet<>();

        Service(final String className) {
            this.className = className;
        }

        private Class<?> type() {
            if (this.cachedType != null && Bukkit.getServicesManager().isProvidedFor(this.cachedType)) {
                return this.cachedType;
            }
            this.cachedType = null;
            this.methods.clear();
            for (final Class<?> known : Bukkit.getServicesManager().getKnownServices()) {
                if (known.getName().equals(this.className)) {
                    this.cachedType = known;
                    break;
                }
            }
            return this.cachedType;
        }

        boolean present() {
            synchronized (this) {
                final Class<?> type = this.type();
                return type != null && Bukkit.getServicesManager().load(type) != null;
            }
        }

        /** Result of {@code method(countryId)}, null when unavailable. */
        Object call(final String method, final UUID countryId) {
            return this.call(method, UUID.class, countryId);
        }

        /**
         * Result of {@code method(arg)} ({@code argType} null = no-arg method), null when unavailable.
         */
        synchronized Object call(final String method, final Class<?> argType, final Object arg) {
            try {
                final Class<?> type = this.type();
                if (type == null) {
                    return null;
                }
                final Object provider = Bukkit.getServicesManager().load(type);
                if (provider == null) {
                    return null;
                }
                final String key = method + "/" + (argType == null ? "" : argType.getName());
                Method m = this.methods.get(key);
                if (m == null) {
                    m = argType == null ? type.getMethod(method) : type.getMethod(method, argType);
                    this.methods.put(key, m);
                }
                return argType == null ? m.invoke(provider) : m.invoke(provider, arg);
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                if (this.warned.add(method)) {
                    Integrations.this.logger.warning("Nie udało się odczytać " + this.className + "#" + method + ": " + e);
                }
                return null;
            }
        }
    }
}
