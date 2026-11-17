// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.economy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the economy of one country from Ludnosc through its public plugin methods, by reflection — none of them is part
 * of the frozen {@code LudnoscApi}:
 * <ul>
 *   <li>{@code LudnoscPlugin#materialFlow(UUID)} — immutable snapshot (workplaces, stores, granary, sites, haulers, recent);</li>
 *   <li>{@code life().stats(UUID)} — citizens (homes, work, families, savings) and {@code life().countryLife(UUID)} — last daily
 *       settlement (wages, tax, income);</li>
 *   <li>{@code work().summary(UUID)} — workers per job, food delivered and fish caught today.</li>
 * </ul>
 * Every call is optional: a missing plugin, an older jar without the method, or an exception gives null plus a reason,
 * never an error for the whole answer. Values are converted to plain JSON types ({@link Plain}). Main thread
 * ({@code materialFlow} itself requires it). The target is any object with the same public methods, so tests use stand-ins.
 */
public final class LudnoscBridge {

    /** What was read. Null fields = not available ({@code errors} says why, keyed by source). */
    public record Sources(Map<String, Object> flow, Map<String, Object> lifeStats, Map<String, Object> lifeDay,
                          Map<String, Object> work, Map<String, Object> settings, Map<String, String> errors) {
    }

    private static final Object MISSING = new Object();
    private final Map<String, Object> methods = new ConcurrentHashMap<>();

    /** Reads every source of {@code country} from {@code ludnosc} (the Ludnosc plugin instance, or null). */
    public Sources read(final Object ludnosc, final UUID country) {
        final Map<String, String> errors = new LinkedHashMap<>();
        if (ludnosc == null) {
            errors.put("ludnosc", "Plugin Ludnosc nie jest włączony.");
            return new Sources(null, null, null, null, Map.of(), errors);
        }
        final Map<String, Object> flow = asMap(this.call(ludnosc, "materialFlow", country, errors, "flow"));
        if (flow == null && !errors.containsKey("flow")) {
            errors.put("flow", "Ludnosc nie zwróciła danych przepływu.");
        }
        Map<String, Object> lifeStats = null;
        Map<String, Object> lifeDay = null;
        final Object life = this.call(ludnosc, "life", null, errors, "life");
        if (life != null) {
            final Object enabled = this.call(life, "enabled", null, errors, "lifeEnabled");
            lifeStats = asMap(Plain.of(this.call(life, "stats", country, errors, "life")));
            if (Boolean.FALSE.equals(enabled)) {
                errors.put("life", "Życie obywateli jest wyłączone (life.enabled: false).");
            }
            // countryLife() creates an empty entry for an unknown country: only ask when the country has citizens.
            if (lifeStats != null && Plain.num(lifeStats.get("population"), 0) > 0) {
                lifeDay = Plain.publicFields(this.call(life, "countryLife", country, errors, "lifeDay"));
            }
        }
        Map<String, Object> work = null;
        final Object ws = this.call(ludnosc, "work", null, errors, "work");
        if (ws != null) {
            work = asMap(Plain.of(this.call(ws, "summary", country, errors, "work")));
        }
        return new Sources(flow, lifeStats, lifeDay, work, settings(ludnosc), errors);
    }

    /** A few Ludnosc settings the page explains (config of the running plugin; defaults when unreadable). */
    static Map<String, Object> settings(final Object ludnosc) {
        final Map<String, Object> out = new LinkedHashMap<>();
        if (ludnosc instanceof org.bukkit.plugin.Plugin p) {
            try {
                final org.bukkit.configuration.file.FileConfiguration c = p.getConfig();
                out.put("materialWaitMinutes", (long) c.getInt("construction.material-wait-minutes", 60));
                out.put("requireMaterials", c.getBoolean("construction.require-materials", false));
                out.put("foodPerSettler", c.getLong("growth.food-per-settler", 20));
                out.put("unloadFill", c.getDouble("logistics.unload-fill", 0.75));
            } catch (final RuntimeException | LinkageError e) {
                // keep what was read
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object v) {
        if (v instanceof Map<?, ?> m) {
            // Ludnosc already gives plain values, but convert anyway (records, enums, foreign types).
            final Object plain = Plain.of(m);
            return plain instanceof Map<?, ?> pm ? (Map<String, Object>) pm : null;
        }
        return null;
    }

    /** {@code target.name()} or {@code target.name(arg)}; null with a reason in {@code errors} when it cannot be called. */
    Object call(final Object target, final String name, final UUID arg, final Map<String, String> errors, final String key) {
        final Method m = this.method(target.getClass(), name, arg != null);
        if (m == null) {
            errors.putIfAbsent(key, "Zainstalowana Ludnosc nie ma metody " + name + " — zaktualizuj plugin.");
            return null;
        }
        try {
            return arg == null ? m.invoke(target) : m.invoke(target, arg);
        } catch (final InvocationTargetException e) {
            final Throwable c = e.getCause() == null ? e : e.getCause();
            errors.putIfAbsent(key, "Ludnosc: " + c.getClass().getSimpleName() + (c.getMessage() == null ? "" : " — " + trim(c.getMessage())));
            return null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            errors.putIfAbsent(key, "Ludnosc: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private Method method(final Class<?> type, final String name, final boolean withUuid) {
        // identity of the class, not its name: a reloaded Ludnosc has a new class loader
        final String key = type.getName() + "@" + System.identityHashCode(type) + "#" + name + (withUuid ? "(UUID)" : "()");
        final Object cached = this.methods.computeIfAbsent(key, k -> {
            try {
                final Method m = withUuid ? type.getMethod(name, UUID.class) : type.getMethod(name);
                try {
                    m.setAccessible(true);
                } catch (final RuntimeException e) {
                    // public method of a public class: fine without
                }
                return m;
            } catch (final NoSuchMethodException | SecurityException | LinkageError e) {
                return MISSING;
            }
        });
        return cached instanceof Method m ? m : null;
    }

    private static String trim(final String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
