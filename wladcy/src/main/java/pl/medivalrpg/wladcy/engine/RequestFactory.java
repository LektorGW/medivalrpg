// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.engine;

import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolChoiceNone;
import com.anthropic.models.beta.messages.MessageCreateParams;
import java.util.List;
import java.util.Locale;
import pl.medivalrpg.wladcy.config.WladcyConfig;
import pl.medivalrpg.wladcy.prompt.PromptBuilder;
import pl.medivalrpg.wladcy.tools.ToolSpecs;

/**
 * Builds the Messages API request (beta endpoint, needed for server-side fallbacks).
 *
 * <p>Prompt-cache layout (render order tools → system → messages):</p>
 * <ol>
 *   <li>tools (fixed, sorted) + system block 1 = stable rules, with an explicit {@code cache_control} breakpoint —
 *       shared by every ruler, player and request;</li>
 *   <li>system block 2 = the conversation's frozen context (persona, country state, memory) — after the
 *       breakpoint;</li>
 *   <li>messages — the growing conversation, cached by top-level automatic caching.</li>
 * </ol>
 * <p>Thinking is not sent: on Claude Opus 5.5 it is always on (adaptive) and {@code output_config.effort} controls
 * it. {@code tool_choice} stays {@code auto} (forced tool choice is rejected by Opus 5.5) except on the last
 * permitted round, where {@code none} makes the ruler answer in words. No prefill.</p>
 */
public final class RequestFactory {

    private final WladcyConfig config;
    private final String stableSystem;
    private final List<BetaTool> tools;

    public RequestFactory(final WladcyConfig config) {
        this.config = config;
        this.stableSystem = PromptBuilder.stableSystem(config.allowWar, config.styleFreedom, config.quests.enabled);
        this.tools = ToolCatalog.tools(ToolSpecs.all(config.allowWar, config.quests.enabled));
    }

    public WladcyConfig config() {
        return this.config;
    }

    public String model() {
        return this.config.model;
    }

    public MessageCreateParams build(final String sessionContext, final List<BetaMessageParam> history, final boolean lastRound) {
        final MessageCreateParams.Builder b = MessageCreateParams.builder()
            .model(this.config.model)
            .maxTokens(this.config.maxTokens)
            .outputConfig(BetaOutputConfig.builder()
                .effort(BetaOutputConfig.Effort.of(this.config.effort.name().toLowerCase(Locale.ROOT)))
                .build())
            .systemOfBetaTextBlockParams(List.of(
                BetaTextBlockParam.builder()
                    .text(this.stableSystem)
                    .cacheControl(BetaCacheControlEphemeral.builder().build())
                    .build(),
                BetaTextBlockParam.builder()
                    .text(sessionContext)
                    .build()))
            .messages(history)
            // Automatic breakpoint on the last cacheable block: the conversation so far is read back next turn.
            .cacheControl(BetaCacheControlEphemeral.builder().build());
        for (final BetaTool t : this.tools) {
            b.addTool(t);
        }
        if (lastRound) {
            b.toolChoice(BetaToolChoiceNone.builder().build());
        }
        if (this.config.fallbacksFor(this.config.model)) {
            // Refusal fallbacks routed by Anthropic per refusal category ("default" form, beta 2026-07-01).
            b.fallbacksDefault();
            b.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01);
        }
        return b.build();
    }
}
