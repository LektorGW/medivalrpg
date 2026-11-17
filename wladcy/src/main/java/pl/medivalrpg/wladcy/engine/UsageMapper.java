// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.engine;

import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.messages.Model;
import java.util.ArrayList;
import java.util.List;
import pl.medivalrpg.wladcy.cost.UsageSample;

/**
 * {@code response.usage} → {@link UsageSample}s. With server-side fallbacks the top-level usage covers only the
 * attempt that produced the message, while {@code usage.iterations} lists every attempt (declined ones and the
 * fallback model's) — so iterations, when present, are the source of truth.
 */
public final class UsageMapper {

    private UsageMapper() {
    }

    public static List<UsageSample> map(final BetaMessage m, final String requestedModel) {
        final BetaUsage u = m.usage();
        final String served = m.model().asString();
        final List<UsageSample> out = new ArrayList<>();
        final List<BetaUsage.Iteration> its = u.iterations().orElse(List.of());
        if (!its.isEmpty()) {
            for (final BetaUsage.Iteration it : its) {
                final String model = it.model().map(Model::asString).orElse(it.isFallbackMessage() ? served : requestedModel);
                out.add(new UsageSample(model, it.inputTokens(), it.outputTokens(), it.cacheReadInputTokens(), it.cacheCreationInputTokens()));
            }
            return out;
        }
        out.add(new UsageSample(served, u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L),
            u.cacheCreationInputTokens().orElse(0L)));
        return out;
    }
}
