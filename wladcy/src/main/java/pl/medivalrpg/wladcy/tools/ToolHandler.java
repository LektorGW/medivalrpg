// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.tools;

/**
 * Executes the ruler's tool calls for one conversation. Called on the main server thread only. Implementations
 * validate everything ({@link ToolArgs}, {@link ToolRules}, the game APIs) and never throw: problems become
 * {@link ToolOutcome#error}.
 */
public interface ToolHandler {

    ToolOutcome execute(ToolCall call);
}
