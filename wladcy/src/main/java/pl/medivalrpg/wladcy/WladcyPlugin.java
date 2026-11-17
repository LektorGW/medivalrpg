// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.gson.reflect.TypeToken;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import pl.medivalrpg.wladcy.command.WladcaCommand;
import pl.medivalrpg.wladcy.command.ZleceniaCommand;
import pl.medivalrpg.wladcy.config.Messages;
import pl.medivalrpg.wladcy.config.WladcyConfig;
import pl.medivalrpg.wladcy.cost.BudgetTracker;
import pl.medivalrpg.wladcy.cost.CostCalculator;
import pl.medivalrpg.wladcy.cost.RateLimiter;
import pl.medivalrpg.wladcy.engine.ClaudeGateway;
import pl.medivalrpg.wladcy.engine.ConversationEngine;
import pl.medivalrpg.wladcy.engine.RequestFactory;
import pl.medivalrpg.wladcy.game.ConversationManager;
import pl.medivalrpg.wladcy.game.GameServices;
import pl.medivalrpg.wladcy.game.JsonStore;
import pl.medivalrpg.wladcy.game.RulerState;
import pl.medivalrpg.wladcy.game.TributeOffers;
import pl.medivalrpg.wladcy.memory.MemoryStore;
import pl.medivalrpg.wladcy.memory.PairMemory;
import pl.medivalrpg.wladcy.quest.QuestBook;
import pl.medivalrpg.wladcy.quest.QuestData;
import pl.medivalrpg.wladcy.quest.QuestService;
import pl.medivalrpg.wladcy.quest.ReputationBook;

/**
 * Wladcy: conversations of players with NPC rulers, answered by Claude through the official Anthropic Java SDK
 * (loaded by Paper from Maven Central via {@code libraries:} in plugin.yml, not shaded).
 */
public final class WladcyPlugin extends JavaPlugin implements WladcaCommand.Actions {

    private static final String MEMORY_FILE = "memory.json";
    private static final String STATE_FILE = "state.json";
    private static final String USAGE_FILE = "usage.json";
    private static final String QUESTS_FILE = "zlecenia.json";
    private static final String REPUTATION_FILE = "reputacja.json";

    private volatile WladcyConfig config;
    private volatile Messages messages;
    private volatile RequestFactory requests;
    private volatile ConversationEngine engine;
    private AnthropicClient client;
    private ThreadPoolExecutor apiThreads;
    private JsonStore store;
    private MemoryStore memory;
    private RulerState state;
    private BudgetTracker budget;
    private RateLimiter limiter;
    private ConversationManager conversations;
    private QuestBook questBook;
    private ReputationBook reputation;
    private QuestService quests;

    @Override
    public void onEnable() {
        this.getLogger().info("Wladcy by Maciej Kaczmarczyk — lektoro.pl");
        this.saveDefaultConfig();
        this.loadSettings();

        final Optional<GameServices> svc = GameServices.load();
        if (svc.isEmpty()) {
            this.getLogger().severe("Brak KrajeApi / SkarbiecApi / ArmiaApi w ServicesManager — Wladcy się wyłączają");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        final Clock clock = Clock.systemDefaultZone();
        this.store = new JsonStore(this.getDataFolder().toPath(), this.getLogger());
        final Map<String, PairMemory> mem = this.store.load(MEMORY_FILE, new TypeToken<Map<String, PairMemory>>() { }.getType());
        this.memory = new MemoryStore(mem);
        this.state = new RulerState(clock, this.store.load(STATE_FILE, RulerState.Data.class));
        this.budget = new BudgetTracker(clock, new CostCalculator(m -> this.config.price(m)),
            this.store.load(USAGE_FILE, BudgetTracker.State.class), this.config.dailyBudgetUsd, this.config.monthlyBudgetUsd);
        this.limiter = new RateLimiter(clock, this.config.perPlayerPerDay, this.config.cooldownSeconds, this.config.maxInputChars);

        final AtomicInteger n = new AtomicInteger();
        this.apiThreads = new ThreadPoolExecutor(this.config.threads, this.config.threads, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r -> {
            final Thread t = new Thread(r, "Wladcy-API-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.rebuildClient();

        this.questBook = new QuestBook(clock, this.store.load(QUESTS_FILE, QuestData.class));
        this.reputation = new ReputationBook(clock, this.store.load(REPUTATION_FILE,
            new TypeToken<Map<String, ReputationBook.Entry>>() { }.getType()));
        this.quests = new QuestService(this, svc.get(), () -> this.config, () -> this.messages, this.questBook, this.reputation, this.store, clock);
        Bukkit.getPluginManager().registerEvents(this.quests, this);
        this.quests.start();

        this.conversations = new ConversationManager(this, svc.get(), () -> this.config, () -> this.messages, () -> this.engine,
            this.budget, this.limiter, this.memory, this.state, new TributeOffers(), this.store, clock, this.quests);
        Bukkit.getPluginManager().registerEvents(this.conversations, this);
        this.conversations.start();
        this.quests.setEventSink(this.conversations::gameEvent);

        final WladcaCommand cmd = new WladcaCommand(this, () -> this.messages);
        final ZleceniaCommand quest = new ZleceniaCommand(() -> this.quests, () -> this.messages, this::reload);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(cmd.build(), "Rozmowy z władcami krajów NPC", List.of("wladcy"));
            commands.register(quest.buildQuests(), "Zlecenia władców krajów NPC", List.of("zlecenie"));
            commands.register(quest.buildReputation(), "Twoja reputacja u krajów", List.of("rep"));
        });
        Bukkit.getScheduler().runTaskTimer(this, this::autosave, 20L * 60, 20L * 60);
        this.getLogger().info("Model: " + this.config.model + ", effort: " + this.config.effort.name().toLowerCase(java.util.Locale.ROOT)
            + ", fallbacki: " + (this.config.fallbacksFor(this.config.model) ? "tak" : "nie")
            + ", klucz API: " + (this.engine != null ? "jest" : "BRAK — władcy są nieosiągalni")
            + ", budżet dzienny: $" + this.config.dailyBudgetUsd + ", pamięć: " + this.memory.size() + " par"
            + ", zlecenia: " + (this.config.quests.enabled ? "tak (w toku " + this.questBook.activeCount() + ")" : "wyłączone")
            + ", reputacja: " + this.reputation.size() + " wpisów");
    }

    private void loadSettings() {
        this.reloadConfig();
        this.config = new WladcyConfig(this.getConfig(), this.getLogger());
        this.messages = new Messages(this.getConfig(), this.getLogger());
        this.requests = new RequestFactory(this.config);
    }

    /** Key from config.yml, else the ANTHROPIC_API_KEY environment variable. Never logged. */
    private String resolveKey() {
        if (!this.config.configApiKey.isBlank()) {
            return this.config.configApiKey;
        }
        final String env = System.getenv("ANTHROPIC_API_KEY");
        return env == null ? "" : env.trim();
    }

    private void rebuildClient() {
        if (this.client != null) {
            try {
                this.client.close();
            } catch (final RuntimeException e) {
                this.getLogger().log(Level.FINE, "Zamykanie klienta API", e);
            }
            this.client = null;
        }
        this.engine = null;
        final String key = this.resolveKey();
        if (key.isEmpty()) {
            return;
        }
        this.client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .maxRetries(this.config.maxRetries)
            .timeout(Duration.ofSeconds(this.config.timeoutSeconds))
            .build();
        this.engine = new ConversationEngine(ClaudeGateway.of(this.client), () -> this.requests, this.budget, this.apiThreads,
            Bukkit.getScheduler().getMainThreadExecutor(this), this.getLogger());
    }

    @Override
    public ConversationManager conversations() {
        return this.conversations;
    }

    // ------------------------------------------------------------------ Firmy bridge (called by reflection, main thread)

    /** Reputation of a player with an NPC country (−100…100), or null when quests are off. */
    public Integer firmyReputation(final java.util.UUID npc, final java.util.UUID player) {
        return this.quests == null || !this.quests.enabled() ? null : this.quests.value(npc, player);
    }

    /** Budget of one company wholesale order ({perOrder, perDayLeft}), or null when quests are off (Firmy uses own limits). */
    public java.util.Map<String, Long> firmyOrderBudget(final java.util.UUID npc) {
        return this.quests == null || !this.quests.enabled() ? null : this.quests.firmOrderBudget(npc);
    }

    public void firmyOrderBooked(final java.util.UUID npc, final long amount) {
        if (this.quests != null && this.quests.enabled()) {
            this.quests.firmOrderBooked(npc, amount);
        }
    }

    public int firmyOrderReputation(final java.util.UUID npc, final java.util.UUID player, final String name, final int delta) {
        return this.quests == null || !this.quests.enabled() ? 0 : this.quests.firmOrderReputation(npc, player, name, delta);
    }

    @Override
    public BudgetTracker budget() {
        return this.budget;
    }

    @Override
    public void setApiKey(final String key) {
        this.conversations.endAll("end-shutdown");
        this.getConfig().set("api-key", key);
        this.saveConfig();
        this.config = new WladcyConfig(this.getConfig(), this.getLogger());
        this.requests = new RequestFactory(this.config);
        this.rebuildClient();
        this.getLogger().info("Klucz API zmieniony z konsoli (" + (this.engine != null ? "aktywny" : "brak") + ")");
    }

    @Override
    public void reload() {
        this.conversations.endAll("end-shutdown");
        this.loadSettings();
        this.budget.setBudgets(this.config.dailyBudgetUsd, this.config.monthlyBudgetUsd);
        this.limiter.configure(this.config.perPlayerPerDay, this.config.cooldownSeconds, this.config.maxInputChars);
        this.rebuildClient();
    }

    @Override
    public boolean hasApiKey() {
        return this.engine != null;
    }

    @Override
    public String model() {
        return this.config.model;
    }

    private void autosave() {
        if (this.memory.dirty()) {
            this.store.saveAsync(MEMORY_FILE, this.memory.snapshot());
        }
        if (this.state.dirty()) {
            this.store.saveAsync(STATE_FILE, this.state.snapshot());
        }
        if (this.budget.dirty()) {
            this.store.saveAsync(USAGE_FILE, this.budget.snapshot());
        }
        if (this.questBook.dirty()) {
            this.store.saveAsync(QUESTS_FILE, this.questBook.snapshot());
        }
        if (this.reputation.dirty()) {
            this.store.saveAsync(REPUTATION_FILE, this.reputation.snapshot());
        }
    }

    @Override
    public void onDisable() {
        if (this.conversations != null) {
            this.conversations.stop();
        }
        if (this.quests != null) {
            this.quests.stop();
        }
        if (this.apiThreads != null) {
            this.apiThreads.shutdownNow();
        }
        if (this.client != null) {
            try {
                this.client.close();
            } catch (final RuntimeException ignored) {
                // shutting down
            }
        }
        if (this.store != null) {
            this.store.saveNow(MEMORY_FILE, this.memory.snapshot());
            this.store.saveNow(STATE_FILE, this.state.snapshot());
            this.store.saveNow(USAGE_FILE, this.budget.snapshot());
            if (this.questBook != null) {
                this.store.saveNow(QUESTS_FILE, this.questBook.snapshot());
                this.store.saveNow(REPUTATION_FILE, this.reputation.snapshot());
            }
            this.store.close();
        }
    }
}
