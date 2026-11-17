// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.tools;

import java.util.Map;

/** A {@code tool_use} block in SDK-neutral form: id, tool name and the raw JSON input as Java values. */
public record ToolCall(String id, String name, Map<String, Object> input) {
}
