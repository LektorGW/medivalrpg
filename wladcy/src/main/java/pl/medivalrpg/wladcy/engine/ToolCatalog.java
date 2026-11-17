// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.engine;

import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.medivalrpg.wladcy.tools.ToolSpec;

/**
 * {@link ToolSpec}s → strict {@link BetaTool}s ({@code strict: true}, {@code additionalProperties: false}, every
 * field required). Numeric ranges and lengths are not expressible in strict schemas, so they live in the
 * descriptions and are enforced by {@code ToolArgs}. Deterministic output (same order, same bytes) keeps the tools
 * part of the cached prefix stable.
 */
public final class ToolCatalog {

    private ToolCatalog() {
    }

    public static List<BetaTool> tools(final List<ToolSpec> specs) {
        final List<BetaTool> out = new ArrayList<>();
        for (final ToolSpec s : specs) {
            out.add(tool(s));
        }
        return List.copyOf(out);
    }

    static BetaTool tool(final ToolSpec s) {
        final BetaTool.InputSchema.Properties.Builder props = BetaTool.InputSchema.Properties.builder();
        final List<String> required = new ArrayList<>();
        for (final ToolSpec.Param p : s.params()) {
            props.putAdditionalProperty(p.name(), JsonValue.from(schema(p)));
            required.add(p.name());
        }
        return BetaTool.builder()
            .name(s.name())
            .description(s.description())
            .strict(true)
            .inputSchema(BetaTool.InputSchema.builder()
                .properties(props.build())
                .required(required)
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build())
            .build();
    }

    static Map<String, Object> schema(final ToolSpec.Param p) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", p.type() == ToolSpec.Type.INTEGER ? "integer" : "string");
        m.put("description", p.description());
        if (p.enumValues() != null) {
            m.put("enum", p.enumValues());
        }
        return m;
    }
}
