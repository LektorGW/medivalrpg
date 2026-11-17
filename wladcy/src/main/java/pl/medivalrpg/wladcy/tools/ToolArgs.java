// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Server-side validation of a tool call's input against its {@link ToolSpec}. Strict tool use already guarantees the
 * JSON shape, but numeric ranges and string lengths are not part of strict schemas, and a refusal or
 * {@code max_tokens} can cut an input short, so everything is checked again here. Pure.
 */
public final class ToolArgs {

    /** Valid input: {@link #values} hold {@code Long} for integers and trimmed {@code String}s. */
    public record Parsed(Map<String, Object> values) {
        public long integer(final String name) {
            return (Long) this.values.get(name);
        }

        public String string(final String name) {
            return (String) this.values.get(name);
        }
    }

    /** Thrown with a Polish message returned to the model as an {@code is_error} tool result. */
    public static final class InvalidArgs extends Exception {
        public InvalidArgs(final String message) {
            super(message);
        }
    }

    private ToolArgs() {
    }

    public static Parsed parse(final ToolSpec spec, final Map<String, ?> input) throws InvalidArgs {
        final Map<String, ?> in = input == null ? Map.of() : input;
        final Set<String> known = new java.util.HashSet<>();
        final Map<String, Object> out = new HashMap<>();
        for (final ToolSpec.Param p : spec.params()) {
            known.add(p.name());
            final Object v = in.get(p.name());
            if (v == null) {
                throw new InvalidArgs("Brak wymaganego pola „" + p.name() + "”.");
            }
            switch (p.type()) {
                case INTEGER -> out.put(p.name(), integer(p, v));
                case STRING -> out.put(p.name(), string(p, v));
                default -> throw new InvalidArgs("Nieznany typ pola " + p.name());
            }
        }
        for (final String k : in.keySet()) {
            if (!known.contains(k)) {
                throw new InvalidArgs("Nieznane pole „" + k + "”.");
            }
        }
        return new Parsed(Map.copyOf(out));
    }

    private static Long integer(final ToolSpec.Param p, final Object v) throws InvalidArgs {
        final long n;
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            n = ((Number) v).longValue();
        } else if (v instanceof Number num && num.doubleValue() == Math.rint(num.doubleValue()) && !Double.isInfinite(num.doubleValue())) {
            n = num.longValue();
        } else {
            throw new InvalidArgs("Pole „" + p.name() + "” musi być liczbą całkowitą.");
        }
        if (n < p.min() || n > p.max()) {
            throw new InvalidArgs("Pole „" + p.name() + "” musi być w zakresie " + p.min() + "…" + p.max() + ".");
        }
        return n;
    }

    private static String string(final ToolSpec.Param p, final Object v) throws InvalidArgs {
        if (!(v instanceof String s)) {
            throw new InvalidArgs("Pole „" + p.name() + "” musi być tekstem.");
        }
        final String t = s.strip();
        if (p.enumValues() != null) {
            if (!p.enumValues().contains(t)) {
                throw new InvalidArgs("Pole „" + p.name() + "” musi mieć jedną z wartości: " + String.join(", ", p.enumValues()) + ".");
            }
            return t;
        }
        if (t.length() < p.min()) {
            throw new InvalidArgs("Pole „" + p.name() + "” jest za krótkie.");
        }
        if (t.length() > p.max()) {
            throw new InvalidArgs("Pole „" + p.name() + "” jest za długie (max " + p.max() + " znaków).");
        }
        return t;
    }
}
