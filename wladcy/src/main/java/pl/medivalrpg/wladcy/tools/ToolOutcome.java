// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.tools;

/**
 * Result of one tool call, sent back as a {@code tool_result} block.
 *
 * @param content Polish text for the model
 * @param error   true → {@code is_error: true} (nothing changed in the game)
 */
public record ToolOutcome(String content, boolean error) {

    public static ToolOutcome ok(final String content) {
        return new ToolOutcome(content, false);
    }

    public static ToolOutcome error(final String content) {
        return new ToolOutcome(content, true);
    }
}
