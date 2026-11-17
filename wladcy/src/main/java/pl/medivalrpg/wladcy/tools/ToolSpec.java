// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.tools;

import java.util.List;

/**
 * SDK-independent definition of a tool the ruler may call. {@code engine.ToolCatalog} turns it into a strict
 * {@code BetaTool}; {@link ToolArgs} validates the model's input against it.
 */
public record ToolSpec(String name, String description, List<Param> params) {

    public enum Type { STRING, INTEGER }

    /**
     * @param enumValues allowed values for a string ({@code null} = any)
     * @param min        inclusive minimum for an integer / minimum length for a string (checked by the server —
     *                   strict tool schemas do not support numeric or length constraints)
     * @param max        inclusive maximum for an integer / maximum length for a string
     */
    public record Param(String name, Type type, String description, List<String> enumValues, long min, long max) {

        public static Param integer(final String name, final String description, final long min, final long max) {
            return new Param(name, Type.INTEGER, description, null, min, max);
        }

        public static Param string(final String name, final String description, final int minLength, final int maxLength) {
            return new Param(name, Type.STRING, description, null, minLength, maxLength);
        }

        public static Param choice(final String name, final String description, final List<String> values) {
            return new Param(name, Type.STRING, description, List.copyOf(values), 0, Long.MAX_VALUE);
        }
    }
}
