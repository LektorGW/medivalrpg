// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.engine;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;

/** One blocking Messages API call. Called only from the plugin's API threads, never the main thread. */
public interface ClaudeGateway {

    BetaMessage send(MessageCreateParams params);

    /** Gateway backed by the official SDK client ({@code client.beta().messages().create}). */
    static ClaudeGateway of(final AnthropicClient client) {
        return params -> client.beta().messages().create(params);
    }
}
