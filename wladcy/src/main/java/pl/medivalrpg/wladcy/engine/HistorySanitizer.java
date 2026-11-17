// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.engine;

import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessageParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns an assistant response into the message echoed back in the next request. Thinking blocks are passed back
 * unchanged (Opus 5.5 binds them to the conversation). After a server-side fallback, everything before the last
 * {@code fallback} block except text is dropped (the skill's echo rule), and the {@code fallback} marker itself is
 * omitted.
 */
public final class HistorySanitizer {

    private HistorySanitizer() {
    }

    public static Optional<BetaMessageParam> assistant(final List<BetaContentBlock> content) {
        int lastFallback = -1;
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i).isFallback()) {
                lastFallback = i;
            }
        }
        final List<BetaContentBlockParam> out = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            final BetaContentBlock b = content.get(i);
            if (b.isFallback()) {
                continue;
            }
            if (i < lastFallback && !b.isText()) {
                continue;
            }
            out.add(b.toParam());
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(BetaMessageParam.ofAssistantOfContentBlockParams(out));
    }
}
