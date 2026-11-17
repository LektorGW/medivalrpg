// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.economy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns whatever another plugin returns (maps, lists, records, enums, public-field holders) into plain JSON values:
 * {@code String}, {@code Long}, {@code Double}, {@code Boolean}, {@code List}, {@code LinkedHashMap<String,?>} or null.
 * Bounded (depth, list length, string length) so an unexpected structure can never blow up a panel answer. Pure.
 */
public final class Plain {

    public static final int MAX_DEPTH = 6;
    public static final int MAX_LIST = 400;
    public static final int MAX_MAP = 400;
    public static final int MAX_STRING = 400;

    private Plain() {
    }

    public static Object of(final Object value) {
        return of(value, 0);
    }

    static Object of(final Object v, final int depth) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s.length() > MAX_STRING ? s.substring(0, MAX_STRING) + "…" : s;
        }
        if (v instanceof Boolean) {
            return v;
        }
        if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
            return ((Number) v).longValue();
        }
        if (v instanceof Number n) {
            final double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (v instanceof Character c) {
            return String.valueOf(c);
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        if (v instanceof java.util.UUID u) {
            return u.toString();
        }
        if (depth >= MAX_DEPTH) {
            return null;
        }
        if (v instanceof Map<?, ?> m) {
            final Map<String, Object> out = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> e : m.entrySet()) {
                if (out.size() >= MAX_MAP) {
                    break;
                }
                if (e.getKey() != null) {
                    final Object key = of(e.getKey(), depth + 1);
                    out.put(key == null ? String.valueOf(e.getKey()) : String.valueOf(key), of(e.getValue(), depth + 1));
                }
            }
            return out;
        }
        if (v instanceof Collection<?> c) {
            final List<Object> out = new ArrayList<>();
            for (final Object o : c) {
                if (out.size() >= MAX_LIST) {
                    break;
                }
                out.add(of(o, depth + 1));
            }
            return out;
        }
        if (v.getClass().isArray()) {
            final List<Object> out = new ArrayList<>();
            final int n = Math.min(java.lang.reflect.Array.getLength(v), MAX_LIST);
            for (int i = 0; i < n; i++) {
                out.add(of(java.lang.reflect.Array.get(v, i), depth + 1));
            }
            return out;
        }
        if (v.getClass().isRecord()) {
            return record(v, depth);
        }
        return String.valueOf(v).length() > MAX_STRING ? String.valueOf(v).substring(0, MAX_STRING) : String.valueOf(v);
    }

    private static Map<String, Object> record(final Object r, final int depth) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final RecordComponent c : r.getClass().getRecordComponents()) {
            try {
                final java.lang.reflect.Method m = c.getAccessor();
                m.setAccessible(true);
                out.put(c.getName(), of(m.invoke(r), depth + 1));
            } catch (final ReflectiveOperationException | RuntimeException e) {
                out.put(c.getName(), null);
            }
        }
        return out;
    }

    /**
     * Public, non-static fields of a plain data holder (e.g. Ludnosc's {@code LifeStore.CountryLife}) — only numbers,
     * booleans and strings. Null when the object is null.
     */
    public static Map<String, Object> publicFields(final Object holder) {
        if (holder == null) {
            return null;
        }
        if (holder.getClass().isRecord()) {
            return record(holder, 0);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final Field f : holder.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            final Class<?> t = f.getType();
            if (!(t.isPrimitive() || Number.class.isAssignableFrom(t) || t == Boolean.class || t == String.class)) {
                continue;
            }
            try {
                out.put(f.getName(), of(f.get(holder), 1));
            } catch (final ReflectiveOperationException | RuntimeException e) {
                // skip unreadable field
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tolerant readers of plain values

    /** Map value, or an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(final Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** List value, or an empty list. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(final Object v) {
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /** Number as long (strings with digits are accepted), else null. */
    public static Long num(final Object v) {
        if (v instanceof Number n) {
            final double d = n.doubleValue();
            return Double.isFinite(d) ? Math.round(d) : null;
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Math.round(Double.parseDouble(s.trim().replace(',', '.')));
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static long num(final Object v, final long def) {
        final Long n = num(v);
        return n == null ? def : n;
    }

    /** Number as double, else null. */
    public static Double dbl(final Object v) {
        if (v instanceof Number n) {
            final double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        return num(v) == null ? null : num(v).doubleValue();
    }

    /** String value (numbers and booleans are converted), else null. */
    public static String str(final Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return null;
    }

    public static String str(final Object v, final String def) {
        final String s = str(v);
        return s == null ? def : s;
    }

    /** First present key of {@code m}. */
    public static Object first(final Map<String, Object> m, final String... keys) {
        for (final String k : keys) {
            final Object v = m.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    public static Boolean bool(final Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return "true".equalsIgnoreCase(s) ? Boolean.TRUE : "false".equalsIgnoreCase(s) ? Boolean.FALSE : null;
        }
        return null;
    }
}
