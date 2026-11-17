// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.engine;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import pl.medivalrpg.wladcy.cost.BudgetTracker;
import pl.medivalrpg.wladcy.prompt.PromptBuilder;
import pl.medivalrpg.wladcy.tools.ToolCall;
import pl.medivalrpg.wladcy.tools.ToolHandler;
import pl.medivalrpg.wladcy.tools.ToolOutcome;

/**
 * The manual tool-use loop for one player message. API calls run on {@code async} (never the main thread); response
 * handling and tool execution run on {@code main} (the server thread in game, a direct executor in tests).
 *
 * <p>{@code stop_reason} handling: {@code tool_use} → run the tools, send all {@code tool_result}s in one user
 * message, ask again (at most {@code api.max-tool-rounds}, the last round with {@code tool_choice: none});
 * {@code refusal} → nothing from that response is used or executed; {@code max_tokens} → text is kept only when no
 * tool call was cut; {@code end_turn} and the rest → the reply. Failed turns roll the history back to the last
 * consistent point, so the next request is a valid continuation.</p>
 */
public final class ConversationEngine {

    private final ClaudeGateway gateway;
    private final Supplier<RequestFactory> factory;
    private final BudgetTracker budget;
    private final Executor async;
    private final Executor main;
    private final Logger logger;

    public ConversationEngine(final ClaudeGateway gateway, final Supplier<RequestFactory> factory, final BudgetTracker budget,
                              final Executor async, final Executor main, final Logger logger) {
        this.gateway = gateway;
        this.factory = factory;
        this.budget = budget;
        this.async = async;
        this.main = main;
        this.logger = logger;
    }

    /** Per-turn accumulator. */
    private static final class Turn {
        final ChatSession session;
        final ToolHandler tools;
        final RequestFactory factory;
        final int mark;
        final List<String> events;
        int safePoint;
        int requests;
        double usd;
        final List<String> texts = new ArrayList<>();

        Turn(final ChatSession session, final ToolHandler tools, final RequestFactory factory, final int mark, final List<String> events) {
            this.session = session;
            this.tools = tools;
            this.factory = factory;
            this.mark = mark;
            this.safePoint = mark;
            this.events = events;
        }
    }

    /** Main thread. Sends the player's message; the future completes on {@code main}. */
    public CompletableFuture<TurnResult> turn(final ChatSession session, final String playerText, final ToolHandler tools) {
        final RequestFactory f = this.factory.get();
        session.busy(true);
        final List<String> events = session.takeEvents();
        final Turn t = new Turn(session, tools, f, session.history().size(), events);
        session.history().add(BetaMessageParam.ofUser(PromptBuilder.userTurn(playerText, events, f.config().maxInputChars)));
        session.countTurn();
        return this.round(t, 0).whenComplete((r, e) -> session.busy(false));
    }

    private CompletableFuture<TurnResult> round(final Turn t, final int round) {
        if (this.budget.exceeded()) {
            return CompletableFuture.completedFuture(this.fail(t, TurnResult.Kind.BUDGET));
        }
        final boolean last = round >= t.factory.config().maxToolRounds;
        final MessageCreateParams params = t.factory.build(t.session.context(), List.copyOf(t.session.history()), last);
        final CompletableFuture<BetaMessage> call;
        try {
            call = CompletableFuture.supplyAsync(() -> this.gateway.send(params), this.async);
        } catch (final RejectedExecutionException e) {
            // Too many requests queued (or shutting down): no spammy retries, the ruler is just "thinking".
            return CompletableFuture.completedFuture(this.fail(t, TurnResult.Kind.BUSY));
        }
        t.requests++;
        return call
            .handleAsync((resp, err) -> err != null ? CompletableFuture.completedFuture(this.onError(t, err))
                : this.onResponse(t, round, last, resp), this.main)
            .thenCompose(x -> x);
    }

    private CompletableFuture<TurnResult> onResponse(final Turn t, final int round, final boolean last, final BetaMessage resp) {
        try {
            t.usd += this.budget.record(UsageMapper.map(resp, t.factory.model()));
        } catch (final RuntimeException e) {
            this.logger.log(Level.WARNING, "Nie udało się policzyć kosztu odpowiedzi", e);
        }
        final BetaStopReason.Known stop = resp.stopReason().map(sr -> {
            try {
                return sr.known();
            } catch (final RuntimeException unknown) {
                return null;
            }
        }).orElse(null);
        final List<BetaToolUseBlock> uses = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        for (final BetaContentBlock b : resp.content()) {
            b.toolUse().ifPresent(uses::add);
            b.text().ifPresent(x -> texts.add(x.text()));
        }

        if (stop == BetaStopReason.Known.REFUSAL) {
            // Nothing from a refused response is shown or executed (it can be partial).
            return done(this.fail(t, TurnResult.Kind.REFUSED));
        }
        if (stop == BetaStopReason.Known.MAX_TOKENS) {
            if (!uses.isEmpty() || String.join("", texts).isBlank()) {
                return done(this.fail(t, TurnResult.Kind.BUSY));
            }
            return done(this.reply(t, resp, texts));
        }
        if (stop == BetaStopReason.Known.TOOL_USE && !uses.isEmpty()) {
            if (last) {
                return done(this.fail(t, TurnResult.Kind.BUSY));
            }
            t.texts.addAll(texts);
            HistorySanitizer.assistant(resp.content()).ifPresent(t.session.history()::add);
            final List<BetaContentBlockParam> results = new ArrayList<>();
            for (final BetaToolUseBlock u : uses) {
                final ToolOutcome o = this.execute(t.tools, u);
                results.add(BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
                    .toolUseId(u.id())
                    .content(o.content())
                    .isError(o.error())
                    .build()));
            }
            t.session.history().add(BetaMessageParam.ofUserOfContentBlockParams(results));
            t.safePoint = t.session.history().size();
            return this.round(t, round + 1);
        }
        return done(this.reply(t, resp, texts));
    }

    private TurnResult reply(final Turn t, final BetaMessage resp, final List<String> texts) {
        HistorySanitizer.assistant(resp.content()).ifPresentOrElse(t.session.history()::add,
            () -> t.session.history().add(BetaMessageParam.ofAssistant("…")));
        t.texts.addAll(texts);
        return new TurnResult(TurnResult.Kind.REPLY, String.join(" ", t.texts).strip(), t.requests, t.usd);
    }

    private ToolOutcome execute(final ToolHandler tools, final BetaToolUseBlock u) {
        try {
            return tools.execute(new ToolCall(u.id(), u.name(), input(u)));
        } catch (final RuntimeException e) {
            this.logger.log(Level.WARNING, "Błąd narzędzia " + u.name(), e);
            return ToolOutcome.error("Wewnętrzny błąd gry — nic się nie zmieniło.");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> input(final BetaToolUseBlock u) {
        try {
            final Object raw = u._input().convert(Map.class);
            return raw == null ? Map.of() : new LinkedHashMap<>((Map<String, Object>) raw);
        } catch (final RuntimeException e) {
            return Map.of();
        }
    }

    private TurnResult onError(final Turn t, final Throwable err) {
        final Throwable e = unwrap(err);
        final TurnResult.Kind kind;
        if (e instanceof RateLimitException || e instanceof InternalServerException || e instanceof AnthropicIoException) {
            kind = TurnResult.Kind.BUSY;
            this.logger.info("API chwilowo niedostępne: " + e.getClass().getSimpleName());
        } else if (e instanceof UnauthorizedException || e instanceof PermissionDeniedException) {
            kind = TurnResult.Kind.UNAVAILABLE;
            this.logger.warning("API odrzuciło klucz (" + e.getClass().getSimpleName() + ") — sprawdź api-key / ANTHROPIC_API_KEY");
        } else if (e instanceof AnthropicServiceException se && (se.statusCode() >= 500 || se.statusCode() == 408 || se.statusCode() == 529)) {
            kind = TurnResult.Kind.BUSY;
            this.logger.info("API chwilowo niedostępne: HTTP " + se.statusCode());
        } else if (e instanceof BadRequestException) {
            kind = TurnResult.Kind.ERROR;
            this.logger.warning("API odrzuciło żądanie (400): " + e.getMessage());
        } else {
            kind = TurnResult.Kind.ERROR;
            this.logger.log(Level.WARNING, "Błąd wywołania API", e);
        }
        return this.fail(t, kind);
    }

    /** Rolls the history back to the last consistent point (before the player's message, or after a tool round). */
    private TurnResult fail(final Turn t, final TurnResult.Kind kind) {
        t.session.truncate(t.safePoint);
        if (t.safePoint == t.mark) {
            t.session.restoreEvents(t.events);
        }
        return new TurnResult(kind, "", t.requests, t.usd);
    }

    private static Throwable unwrap(final Throwable e) {
        Throwable x = e;
        while ((x instanceof CompletionException || x instanceof ExecutionException) && x.getCause() != null) {
            x = x.getCause();
        }
        return x;
    }

    private static CompletableFuture<TurnResult> done(final TurnResult r) {
        return CompletableFuture.completedFuture(r);
    }
}
