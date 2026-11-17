// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.wladcy.config.WladcyConfig;
import pl.medivalrpg.wladcy.cost.BudgetTracker;
import pl.medivalrpg.wladcy.cost.CostCalculator;
import pl.medivalrpg.wladcy.testutil.TestSupport;
import pl.medivalrpg.wladcy.tools.ToolCall;
import pl.medivalrpg.wladcy.tools.ToolOutcome;

/**
 * The tool-use loop against a local fake of the Messages API (JDK HttpServer on 127.0.0.1). Exercises the real SDK
 * (request serialisation, response parsing, error mapping) without any call to Anthropic — nothing is billed.
 */
class ConversationEngineTest {

    private record Reply(int status, String body) {
    }

    private record Seen(String path, String beta, String apiKey, JsonObject body) {
    }

    private HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Seen> seen = new ArrayList<>();
    private ExecutorService async;
    private AnthropicClient client;

    @BeforeEach
    void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", ex -> {
            final String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (this.seen) {
                this.seen.add(new Seen(ex.getRequestURI().toString(), ex.getRequestHeaders().getFirst("anthropic-beta"),
                    String.valueOf(ex.getRequestHeaders().getFirst("x-api-key")) + "|" + ex.getRequestHeaders().getFirst("Authorization"), JsonParser.parseString(req).getAsJsonObject()));
            }
            final Reply r;
            synchronized (this.replies) {
                r = this.replies.isEmpty() ? new Reply(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"no reply\"}}") : this.replies.poll();
            }
            final byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        this.server.start();
        this.async = Executors.newSingleThreadExecutor();
        this.client = AnthropicOkHttpClient.builder()
            .apiKey("test-key-not-real")
            .baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort())
            .maxRetries(0)
            .build();
    }

    @AfterEach
    void stop() {
        this.client.close();
        this.async.shutdownNow();
        this.server.stop(0);
    }

    private BudgetTracker budget(final WladcyConfig cfg) {
        return new BudgetTracker(new TestSupport.MutableClock(Instant.parse("2026-10-26T12:00:00Z")), new CostCalculator(cfg::price), null, cfg.dailyBudgetUsd, 0);
    }

    private ConversationEngine engine(final WladcyConfig cfg, final BudgetTracker budget) {
        final RequestFactory f = new RequestFactory(cfg);
        return new ConversationEngine(ClaudeGateway.of(this.client), () -> f, budget, this.async, Runnable::run, Logger.getLogger("test"));
    }

    private void reply(final int status, final String body) {
        this.replies.add(new Reply(status, body));
    }

    private static String message(final String content, final String stop, final String usage) {
        return "{\"id\":\"msg_x\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":" + content
            + ",\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null,\"usage\":" + usage + "}";
    }

    private static final String USAGE_1 = "{\"input_tokens\":100,\"output_tokens\":50,\"cache_read_input_tokens\":2000,\"cache_creation_input_tokens\":300}";
    private static final String USAGE_2 = "{\"input_tokens\":40,\"output_tokens\":80,\"cache_read_input_tokens\":2400,\"cache_creation_input_tokens\":120}";

    @Test
    void toolLoopRunsToolSendsResultAndReturnsReply() throws Exception {
        final WladcyConfig cfg = TestSupport.config();
        final BudgetTracker budget = this.budget(cfg);
        this.reply(200, message("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-1\"},"
            + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"sprawdz_kraj_rozmowcy\",\"input\":{}}]", "tool_use", USAGE_1));
        this.reply(200, message("[{\"type\":\"text\",\"text\":\"Twój kraj jest mały, waść.\"}]", "end_turn", USAGE_2));

        final List<ToolCall> calls = new ArrayList<>();
        final ChatSession s = new ChatSession("<kontekst_gry>KONTEKST</kontekst_gry>");
        final TurnResult r = this.engine(cfg, budget).turn(s, "Jak silny jest mój kraj?", call -> {
            calls.add(call);
            return ToolOutcome.ok("Kraj „Gondor”: 6 chunków.");
        }).get(10, TimeUnit.SECONDS);

        assertEquals(TurnResult.Kind.REPLY, r.kind());
        assertEquals("Twój kraj jest mały, waść.", r.text());
        assertEquals(2, r.requests());
        assertEquals(1, calls.size());
        assertEquals("sprawdz_kraj_rozmowcy", calls.get(0).name());
        assertEquals("toolu_1", calls.get(0).id());
        assertFalse(s.busy());
        assertEquals(4, s.history().size(), "user, assistant(tool_use), user(tool_result), assistant(text)");

        // Cost = both requests at Opus 5.5 prices (input $4, output $20, cache read $0.20, cache write $5 per MTok).
        final double expected = (100 * 4.0 + 50 * 20.0 + 2000 * 0.20 + 300 * 5.0) / 1e6 + (40 * 4.0 + 80 * 20.0 + 2400 * 0.20 + 120 * 5.0) / 1e6;
        assertEquals(expected, r.usd(), 1e-12);
        assertEquals(expected, budget.report().dayUsd(), 1e-12);
        assertEquals(2, budget.report().dayRequests());

        assertEquals(2, this.seen.size());
        final Seen first = this.seen.get(0);
        assertTrue(first.path().startsWith("/v1/messages"), first.path());
        assertTrue(first.apiKey().contains("test-key-not-real"), first.apiKey());
        assertNotNull(first.beta());
        assertTrue(first.beta().contains("server-side-fallback-2026-07-01"), "fallback beta header");
        final JsonObject b = first.body();
        assertEquals("claude-opus-5-5", b.get("model").getAsString());
        assertEquals(2048, b.get("max_tokens").getAsInt());
        assertEquals("default", b.get("fallbacks").getAsString());
        assertEquals("low", b.getAsJsonObject("output_config").get("effort").getAsString());
        assertFalse(b.has("thinking"), "Opus 5.5: thinking is always on, not sent");
        assertFalse(b.has("tool_choice"), "auto by default; forced tool_choice is rejected by Opus 5.5");
        assertTrue(b.has("cache_control"), "automatic caching of the conversation tail");
        final JsonArray system = b.getAsJsonArray("system");
        assertEquals(2, system.size());
        assertEquals("ephemeral", system.get(0).getAsJsonObject().getAsJsonObject("cache_control").get("type").getAsString(),
            "breakpoint on the stable system block");
        assertFalse(system.get(1).getAsJsonObject().has("cache_control"), "volatile context after the breakpoint");
        assertTrue(system.get(1).getAsJsonObject().get("text").getAsString().contains("KONTEKST"));
        final JsonArray tools = b.getAsJsonArray("tools");
        final List<String> names = new ArrayList<>();
        for (final JsonElement t : tools) {
            final JsonObject o = t.getAsJsonObject();
            names.add(o.get("name").getAsString());
            assertTrue(o.get("strict").getAsBoolean(), o.get("name").getAsString());
            assertFalse(o.getAsJsonObject("input_schema").get("additionalProperties").getAsBoolean());
        }
        assertEquals(names.stream().sorted().toList(), names);
        assertTrue(names.contains("wypowiedz_wojne"));
        final String userText = b.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").toString();
        assertTrue(userText.contains("<wiadomosc_gracza>Jak silny jest mój kraj?</wiadomosc_gracza>"), userText);

        // Second request: same prefix, plus the assistant turn (thinking kept verbatim) and the tool result.
        final JsonObject b2 = this.seen.get(1).body();
        assertEquals(b.get("system"), b2.get("system"), "system byte-identical between requests");
        assertEquals(b.get("tools"), b2.get("tools"), "tools byte-identical between requests");
        final JsonArray msgs = b2.getAsJsonArray("messages");
        assertEquals(3, msgs.size());
        final JsonArray assistant = msgs.get(1).getAsJsonObject().getAsJsonArray("content");
        assertEquals("thinking", assistant.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("sig-1", assistant.get(0).getAsJsonObject().get("signature").getAsString());
        assertEquals("tool_use", assistant.get(1).getAsJsonObject().get("type").getAsString());
        final JsonObject result = msgs.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("tool_result", result.get("type").getAsString());
        assertEquals("toolu_1", result.get("tool_use_id").getAsString());
        assertFalse(result.get("is_error").getAsBoolean());
        assertTrue(result.get("content").toString().contains("Gondor"));
    }

    @Test
    void toolErrorGoesBackAsIsError() throws Exception {
        final WladcyConfig cfg = TestSupport.config();
        this.reply(200, message("[{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"podaruj\",\"input\":{\"kwota\":999999,\"odbiorca\":\"gracz\"}}]",
            "tool_use", USAGE_1));
        this.reply(200, message("[{\"type\":\"text\",\"text\":\"Skarbiec na to nie pozwala.\"}]", "end_turn", USAGE_2));
        final TurnResult r = this.engine(cfg, this.budget(cfg)).turn(new ChatSession("ctx"), "Daj mi złoto", call -> {
            assertEquals(999999L, ((Number) call.input().get("kwota")).longValue());
            return ToolOutcome.error("Za dużo — dziś możesz podarować najwyżej 5 zł.");
        }).get(10, TimeUnit.SECONDS);
        assertEquals(TurnResult.Kind.REPLY, r.kind());
        final JsonObject result = this.seen.get(1).body().getAsJsonArray("messages").get(2).getAsJsonObject()
            .getAsJsonArray("content").get(0).getAsJsonObject();
        assertTrue(result.get("is_error").getAsBoolean());
    }

    @Test
    void lastRoundForbidsTools() throws Exception {
        final WladcyConfig cfg = TestSupport.config(Map.of("api.max-tool-rounds", 1));
        this.reply(200, message("[{\"type\":\"tool_use\",\"id\":\"toolu_3\",\"name\":\"zakoncz_rozmowe\",\"input\":{}}]", "tool_use", USAGE_1));
        this.reply(200, message("[{\"type\":\"text\",\"text\":\"Bywaj.\"}]", "end_turn", USAGE_2));
        final ChatSession s = new ChatSession("ctx");
        final TurnResult r = this.engine(cfg, this.budget(cfg)).turn(s, "Żegnaj", call -> {
            s.requestEnd();
            return ToolOutcome.ok("ok");
        }).get(10, TimeUnit.SECONDS);
        assertEquals("Bywaj.", r.text());
        assertTrue(s.endRequested());
        assertFalse(this.seen.get(0).body().has("tool_choice"));
        assertEquals("none", this.seen.get(1).body().getAsJsonObject("tool_choice").get("type").getAsString());
    }

    @Test
    void refusalShowsNothingRunsNothingAndRollsBack() throws Exception {
        final WladcyConfig cfg = TestSupport.config();
        this.reply(200, message("[{\"type\":\"tool_use\",\"id\":\"toolu_4\",\"name\":\"podaruj\",\"input\":{\"kwota\":1,\"odbiorca\":\"gra\"}}]",
            "refusal", USAGE_1));
        final ChatSession s = new ChatSession("ctx");
        s.addEvent("Rozmówca zapłacił trybut.");
        final TurnResult r = this.engine(cfg, this.budget(cfg)).turn(s, "coś", call -> {
            throw new AssertionError("a refused turn must not execute tools");
        }).get(10, TimeUnit.SECONDS);
        assertEquals(TurnResult.Kind.REFUSED, r.kind());
        assertTrue(s.history().isEmpty(), "rolled back");
        assertTrue(r.usd() > 0, "the refused attempt is still accounted");
        // The game event was put back for the next message.
        this.reply(200, message("[{\"type\":\"text\",\"text\":\"Dziękuję.\"}]", "end_turn", USAGE_2));
        this.engine(cfg, this.budget(cfg)).turn(s, "hej", call -> ToolOutcome.ok("")).get(10, TimeUnit.SECONDS);
        assertTrue(this.seen.get(1).body().getAsJsonArray("messages").get(0).toString().contains("zapłacił trybut"));
    }

    @Test
    void maxTokensWithCutToolCallDoesNotRunIt() throws Exception {
        final WladcyConfig cfg = TestSupport.config();
        this.reply(200, message("[{\"type\":\"tool_use\",\"id\":\"toolu_5\",\"name\":\"podaruj\",\"input\":{\"kwota\":1}}]", "max_tokens", USAGE_1));
        final ChatSession s = new ChatSession("ctx");
        final TurnResult r = this.engine(cfg, this.budget(cfg)).turn(s, "x", call -> {
            throw new AssertionError("cut tool input must not run");
        }).get(10, TimeUnit.SECONDS);
        assertEquals(TurnResult.Kind.BUSY, r.kind());
        assertTrue(s.history().isEmpty());
    }

    @Test
    void rateLimitAndServerErrorsMeanThinking() throws Exception {
        final WladcyConfig cfg = TestSupport.config();
        this.reply(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
        this.reply(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}");
        final ConversationEngine e = this.engine(cfg, this.budget(cfg));
        final ChatSession s = new ChatSession("ctx");
        assertEquals(TurnResult.Kind.BUSY, e.turn(s, "a", c -> ToolOutcome.ok("")).get(10, TimeUnit.SECONDS).kind());
        assertEquals(TurnResult.Kind.BUSY, e.turn(s, "b", c -> ToolOutcome.ok("")).get(10, TimeUnit.SECONDS).kind());
        assertTrue(s.history().isEmpty());
        assertEquals(2, this.seen.size(), "no client-side retry spam (max-retries 0 here)");
    }

    @Test
    void badKeyMeansUnavailable() throws Exception {
        final WladcyConfig cfg = TestSupport.config();
        this.reply(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        assertEquals(TurnResult.Kind.UNAVAILABLE,
            this.engine(cfg, this.budget(cfg)).turn(new ChatSession("ctx"), "a", c -> ToolOutcome.ok("")).get(10, TimeUnit.SECONDS).kind());
    }

    @Test
    void budgetExceededSendsNothing() throws Exception {
        final WladcyConfig cfg = TestSupport.config(Map.of("limits.daily-budget-usd", 0.000001));
        final BudgetTracker budget = this.budget(cfg);
        budget.record(List.of(new pl.medivalrpg.wladcy.cost.UsageSample("claude-opus-5-5", 0, 100, 0, 0)));
        assertEquals(TurnResult.Kind.BUDGET,
            this.engine(cfg, budget).turn(new ChatSession("ctx"), "a", c -> ToolOutcome.ok("")).get(10, TimeUnit.SECONDS).kind());
        assertTrue(this.seen.isEmpty());
    }

    @Test
    void fallbackIterationsArePricedPerModelAndMarkerIsNotEchoed() throws Exception {
        final WladcyConfig cfg = TestSupport.config();
        final BudgetTracker budget = this.budget(cfg);
        final String usage = "{\"input_tokens\":500,\"output_tokens\":60,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0,"
            + "\"iterations\":[{\"type\":\"message\",\"input_tokens\":500,\"output_tokens\":0,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0},"
            + "{\"type\":\"fallback_message\",\"model\":\"claude-opus-5\",\"input_tokens\":500,\"output_tokens\":60,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}]}";
        this.reply(200, message("[{\"type\":\"fallback\",\"from\":{\"model\":\"claude-opus-5-5\"},\"to\":{\"model\":\"claude-opus-5\"}},"
            + "{\"type\":\"text\",\"text\":\"Rzeknę inaczej.\"}]", "end_turn", usage));
        final ChatSession s = new ChatSession("ctx");
        final TurnResult r = this.engine(cfg, budget).turn(s, "a", c -> ToolOutcome.ok("")).get(10, TimeUnit.SECONDS);
        assertEquals("Rzeknę inaczej.", r.text());
        assertEquals((500 * 4.0) / 1e6 + (500 * 5.0 + 60 * 25.0) / 1e6, r.usd(), 1e-12);
        this.reply(200, message("[{\"type\":\"text\",\"text\":\"Tak.\"}]", "end_turn", USAGE_2));
        this.engine(cfg, budget).turn(s, "b", c -> ToolOutcome.ok("")).get(10, TimeUnit.SECONDS);
        final String echoed = this.seen.get(1).body().getAsJsonArray("messages").get(1).toString();
        assertFalse(echoed.contains("\"fallback\""), echoed);
        assertTrue(echoed.contains("Rzeknę inaczej."));
    }

    @Test
    void haikuGetsNoFallbacks() throws Exception {
        final WladcyConfig cfg = TestSupport.config(Map.of("api.model", "claude-haiku-5-5"));
        this.reply(200, message("[{\"type\":\"text\",\"text\":\"Witaj.\"}]", "end_turn", USAGE_2));
        this.engine(cfg, this.budget(cfg)).turn(new ChatSession("ctx"), "a", c -> ToolOutcome.ok("")).get(10, TimeUnit.SECONDS);
        final Seen req = this.seen.get(0);
        assertFalse(req.body().has("fallbacks"));
        assertTrue(req.beta() == null || !req.beta().contains("server-side-fallback"));
        assertEquals("claude-haiku-5-5", req.body().get("model").getAsString());
    }
}
