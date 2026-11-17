// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.event.CountryDisbandEvent;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.wladcy.config.Messages;
import pl.medivalrpg.wladcy.config.WladcyConfig;
import pl.medivalrpg.wladcy.cost.BudgetTracker;
import pl.medivalrpg.wladcy.cost.RateLimiter;
import pl.medivalrpg.wladcy.engine.ChatSession;
import pl.medivalrpg.wladcy.engine.ConversationEngine;
import pl.medivalrpg.wladcy.engine.TurnResult;
import pl.medivalrpg.wladcy.memory.MemoryStore;
import pl.medivalrpg.wladcy.memory.PairMemory;
import pl.medivalrpg.wladcy.prompt.PromptBuilder;
import pl.medivalrpg.wladcy.quest.QuestService;
import pl.medivalrpg.wladcy.text.TextSafety;

/**
 * Conversations between players and NPC rulers: start/end, chat capture ({@link AsyncChatEvent} is cancelled and
 * handled on the main thread), display (chat + optional {@link TextDisplay}), distance/idle checks, tribute clicks.
 * Main thread except {@link #onChat}.
 */
public final class ConversationManager implements Listener {

    private final Plugin plugin;
    private final GameServices services;
    private final Supplier<WladcyConfig> config;
    private final Supplier<Messages> messages;
    private final Supplier<ConversationEngine> engine;
    private final BudgetTracker budget;
    private final RateLimiter limiter;
    private final MemoryStore memory;
    private final RulerState state;
    private final TributeOffers offers;
    private final JsonStore store;
    private final Clock clock;
    private final ContextCollector collector;
    private final QuestService quests;

    private final Map<UUID, Conversation> conversations = new HashMap<>();
    /** Players in a conversation, readable from the async chat thread. */
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> displays = new HashMap<>();
    private BukkitTask ticker;

    public ConversationManager(final Plugin plugin, final GameServices services, final Supplier<WladcyConfig> config, final Supplier<Messages> messages,
                               final Supplier<ConversationEngine> engine, final BudgetTracker budget, final RateLimiter limiter,
                               final MemoryStore memory, final RulerState state, final TributeOffers offers, final JsonStore store, final Clock clock,
                               final QuestService quests) {
        this.plugin = plugin;
        this.services = services;
        this.config = config;
        this.messages = messages;
        this.engine = engine;
        this.budget = budget;
        this.limiter = limiter;
        this.memory = memory;
        this.state = state;
        this.offers = offers;
        this.store = store;
        this.clock = clock;
        this.quests = quests;
        this.collector = new ContextCollector(services, config, memory, state, quests);
    }

    ContextCollector collector() {
        return this.collector;
    }

    /** Quests and reputation (never null in the plugin; may be disabled by config). */
    QuestService quests() {
        return this.quests;
    }

    public void start() {
        this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (this.ticker != null) {
            this.ticker.cancel();
        }
        this.endAll("end-shutdown");
        for (final UUID id : this.displays.values()) {
            final Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        this.displays.clear();
    }

    /**
     * Ends every conversation (config reload: a changed prompt prefix would invalidate the thinking blocks already in
     * their histories).
     */
    public void endAll(final String messageKey) {
        for (final Conversation c : List.copyOf(this.conversations.values())) {
            this.end(c, messageKey);
        }
    }

    public int count() {
        return this.conversations.size();
    }

    // ------------------------------------------------------------------ start / end

    /** {@code /wladca rozmowa}: talk to the nearest NPC ruler within {@code conversation.start-radius}. */
    public void begin(final Player p) {
        final Messages m = this.messages.get();
        final WladcyConfig cfg = this.config.get();
        if (this.engine.get() == null) {
            m.send(p, "rulers-unavailable");
            return;
        }
        if (this.budget.exceeded()) {
            m.send(p, "budget-exceeded");
            return;
        }
        if (this.limiter.usedToday(p.getUniqueId()) >= cfg.perPlayerPerDay) {
            m.send(p, "daily-limit", Messages.p("limit", cfg.perPlayerPerDay));
            return;
        }
        final Optional<Entity> ruler = this.nearestRuler(p, cfg.startRadius);
        if (ruler.isEmpty()) {
            m.send(p, "no-ruler-near", Messages.p("radius", (int) cfg.startRadius));
            return;
        }
        final UUID npcId = this.services.armia().rulerCountry(ruler.get()).orElseThrow();
        final Optional<KrajInfo> npc = this.services.kraje().getCountry(npcId);
        if (npc.isEmpty() || !npc.get().npc()) {
            m.send(p, "no-ruler-near", Messages.p("radius", (int) cfg.startRadius));
            return;
        }
        final Conversation old = this.conversations.get(p.getUniqueId());
        if (old != null) {
            if (old.npc.equals(npcId)) {
                m.send(p, "already-talking", Messages.c("ruler", old.rulerLabel));
                return;
            }
            this.end(old, "end-switched");
        }
        final long now = this.clock.millis();
        final String context = PromptBuilder.sessionContext(this.collector.collect(npc.get(), p.getUniqueId(), p.getName(), now));
        final String rulerName = npc.get().rulerName() == null || npc.get().rulerName().isBlank() ? "Władca" : npc.get().rulerName();
        final Component label = Component.text(rulerName, npc.get().color());
        final Conversation c = new Conversation(p.getUniqueId(), p.getName(), npcId, ruler.get().getUniqueId(),
            ruler.get().getLocation(), label, rulerName, new ChatSession(context), now);
        this.conversations.put(p.getUniqueId(), c);
        this.active.add(p.getUniqueId());
        final PairMemory mem = this.memory.get(npcId, p.getUniqueId());
        mem.playerName = p.getName();
        mem.conversations++;
        mem.lastTalkAt = now;
        m.send(p, "conversation-start", Messages.c("ruler", label),
            Messages.c("country", Component.text(npc.get().name(), npc.get().color())),
            Messages.p("idle", cfg.idleSeconds), Messages.p("distance", (int) cfg.maxDistance));
    }

    /** {@code /wladca koniec}. */
    public void endByPlayer(final Player p) {
        final Conversation c = this.conversations.get(p.getUniqueId());
        if (c == null) {
            this.messages.get().send(p, "not-talking");
            return;
        }
        this.end(c, "end-by-player");
    }

    /** The conversation is still the player's current one (not ended, not replaced by another audience). */
    boolean current(final Conversation c) {
        return this.conversations.get(c.player) == c;
    }

    private void end(final Conversation c, final String messageKey) {
        if (this.conversations.get(c.player) != c) {
            return;
        }
        this.conversations.remove(c.player);
        this.active.remove(c.player);
        final Player p = Bukkit.getPlayer(c.player);
        if (p != null && messageKey != null) {
            this.messages.get().send(p, messageKey, Messages.c("ruler", c.rulerLabel));
        }
    }

    private Optional<Entity> nearestRuler(final Player p, final double radius) {
        Entity best = null;
        double bestD = radius * radius;
        for (final Entity e : p.getNearbyEntities(radius, radius, radius)) {
            if (this.services.armia().rulerCountry(e).isEmpty()) {
                continue;
            }
            final double d = e.getLocation().distanceSquared(p.getLocation());
            if (d <= bestD) {
                best = e;
                bestD = d;
            }
        }
        return Optional.ofNullable(best);
    }

    private void tick() {
        final WladcyConfig cfg = this.config.get();
        final long now = this.clock.millis();
        for (final Conversation c : List.copyOf(this.conversations.values())) {
            final Player p = Bukkit.getPlayer(c.player);
            if (p == null) {
                this.end(c, null);
                continue;
            }
            final Location at = c.rulerLocation;
            if (at.getWorld() == null || !at.getWorld().equals(p.getWorld()) || at.distanceSquared(p.getLocation()) > cfg.maxDistance * cfg.maxDistance) {
                this.end(c, "end-walked-away");
            } else if (!c.session.busy() && now - c.lastActivity > cfg.idleSeconds * 1000L) {
                this.end(c, "end-idle");
            }
        }
    }

    // ------------------------------------------------------------------ chat

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        if (!this.active.contains(id)) {
            return;
        }
        event.setCancelled(true);
        final String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(this.plugin, () -> this.handle(id, raw));
    }

    private void handle(final UUID id, final String raw) {
        final Player p = Bukkit.getPlayer(id);
        final Conversation c = this.conversations.get(id);
        if (p == null || c == null) {
            return;
        }
        final Messages m = this.messages.get();
        final WladcyConfig cfg = this.config.get();
        final String text = TextSafety.clean(raw, 4000);
        final RateLimiter.Check check = this.limiter.check(id, text);
        switch (check.verdict()) {
            case EMPTY -> {
                return;
            }
            case TOO_LONG -> {
                m.send(p, "too-long", Messages.p("max", cfg.maxInputChars), Messages.p("length", text.length()));
                return;
            }
            case COOLDOWN -> {
                m.send(p, "cooldown", Messages.p("seconds", check.waitSeconds()));
                return;
            }
            case DAILY_LIMIT -> {
                m.send(p, "daily-limit", Messages.p("limit", cfg.perPlayerPerDay));
                this.end(c, null);
                return;
            }
            default -> {
                // OK
            }
        }
        if (c.session.busy()) {
            m.send(p, "still-thinking", Messages.c("ruler", c.rulerLabel));
            return;
        }
        final ConversationEngine eng = this.engine.get();
        if (eng == null) {
            m.send(p, "rulers-unavailable");
            this.end(c, null);
            return;
        }
        if (this.budget.exceeded()) {
            m.send(p, "budget-exceeded");
            this.end(c, null);
            return;
        }
        if (c.session.turns() >= cfg.maxMessages) {
            this.end(c, "end-too-long");
            return;
        }
        c.lastActivity = this.clock.millis();
        p.sendMessage(m.get("chat-player-self", Messages.c("ruler", c.rulerLabel), Messages.p("text", text)));
        final Component rp = m.get("chat-player-rp", Messages.p("player", p.getName()), Messages.c("ruler", c.rulerLabel), Messages.p("text", text));
        for (final Player o : this.listeners(c, p)) {
            o.sendMessage(rp);
        }
        this.limiter.consume(id);
        p.sendActionBar(m.get("thinking", Messages.c("ruler", c.rulerLabel)));
        final GameToolHandler tools = new GameToolHandler(c, this, this.services, this.config, this.memory, this.state, this.offers, this.store, this.clock);
        eng.turn(c.session, text, tools).whenComplete((r, err) -> {
            if (err != null) {
                this.plugin.getLogger().warning("Nieobsłużony błąd rozmowy: " + err);
                return;
            }
            this.onResult(c, text, r);
        });
    }

    private void onResult(final Conversation c, final String playerText, final TurnResult r) {
        final Messages m = this.messages.get();
        final WladcyConfig cfg = this.config.get();
        final Player p = Bukkit.getPlayer(c.player);
        c.lastActivity = this.clock.millis();
        switch (r.kind()) {
            case REPLY -> {
                final String reply = TextSafety.modelReply(r.text(), cfg.replyMaxChars);
                if (reply.isEmpty()) {
                    if (p != null) {
                        m.send(p, "ruler-silent", Messages.c("ruler", c.rulerLabel));
                    }
                } else {
                    this.say(c, p, reply);
                    final PairMemory mem = this.memory.get(c.npc, c.player);
                    mem.addExchange(new PairMemory.Exchange(this.clock.millis(), TextSafety.clean(playerText, cfg.maxInputChars),
                        TextSafety.clean(reply, cfg.memoryReplyChars)), cfg.memoryExchanges);
                }
                if (c.session.endRequested()) {
                    this.end(c, "end-by-ruler");
                }
            }
            case REFUSED -> {
                if (p != null) {
                    m.send(p, "ruler-refuses", Messages.c("ruler", c.rulerLabel));
                }
            }
            case BUSY, ERROR -> {
                if (p != null) {
                    m.send(p, "ruler-pondering", Messages.c("ruler", c.rulerLabel));
                }
            }
            case UNAVAILABLE -> {
                if (p != null) {
                    m.send(p, "rulers-unavailable");
                }
                this.end(c, null);
            }
            case BUDGET -> {
                if (p != null) {
                    m.send(p, "budget-exceeded");
                }
                this.end(c, null);
            }
            default -> {
            }
        }
    }

    private List<Player> listeners(final Conversation c, final Player speaker) {
        final double r = this.config.get().rpRadius;
        final List<Player> out = new ArrayList<>();
        if (r <= 0 || c.rulerLocation.getWorld() == null) {
            return out;
        }
        for (final Player o : c.rulerLocation.getWorld().getPlayers()) {
            if (!o.getUniqueId().equals(c.player) && (speaker == null || !o.equals(speaker))
                && o.getLocation().distanceSquared(c.rulerLocation) <= r * r) {
                out.add(o);
            }
        }
        return out;
    }

    private void say(final Conversation c, final Player p, final String reply) {
        final Messages m = this.messages.get();
        final Component line = m.get("chat-ruler", Messages.c("ruler", c.rulerLabel), Messages.p("text", reply));
        if (p != null) {
            p.sendMessage(line);
        }
        for (final Player o : this.listeners(c, p)) {
            o.sendMessage(line);
        }
        this.showAboveHead(c, reply);
    }

    private void showAboveHead(final Conversation c, final String reply) {
        final WladcyConfig cfg = this.config.get();
        if (!cfg.textDisplay || c.rulerLocation.getWorld() == null || !c.rulerLocation.isChunkLoaded()) {
            return;
        }
        final UUID old = this.displays.remove(c.npc);
        if (old != null) {
            final Entity e = Bukkit.getEntity(old);
            if (e != null) {
                e.remove();
            }
        }
        final Location at = c.rulerLocation.clone().add(0, 2.45, 0);
        final String shortText = TextSafety.clean(reply, cfg.displayMaxChars);
        final TextDisplay td = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(Component.text(shortText, NamedTextColor.WHITE));
            d.setBillboard(Display.Billboard.CENTER);
            d.setLineWidth(160);
            d.setShadowed(true);
        });
        this.displays.put(c.npc, td.getUniqueId());
        final UUID id = td.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            final Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
            this.displays.remove(c.npc, id);
        }, cfg.displaySeconds * 20L);
    }

    // ------------------------------------------------------------------ effects of tools

    void showTributeDemand(final Conversation c, final TributeOffers.Offer o) {
        final Player p = Bukkit.getPlayer(c.player);
        if (p == null) {
            return;
        }
        final Messages m = this.messages.get();
        final String amount = this.services.money(o.amount());
        final Component pay = m.get("tribute-pay-button", Messages.p("amount", amount))
            .clickEvent(ClickEvent.runCommand("/wladca trybut zaplac " + o.id()))
            .hoverEvent(HoverEvent.showText(m.get("tribute-pay-hover", Messages.p("amount", amount))));
        final Component refuse = m.get("tribute-refuse-button")
            .clickEvent(ClickEvent.runCommand("/wladca trybut odmow " + o.id()))
            .hoverEvent(HoverEvent.showText(m.get("tribute-refuse-hover")));
        p.sendMessage(m.get("tribute-demand", Messages.c("ruler", c.rulerLabel), Messages.p("amount", amount),
            Messages.c("pay", pay), Messages.c("refuse", refuse), Messages.p("minutes", this.config.get().tributeExpireMinutes)));
    }

    void showGift(final Conversation c, final long amount, final String countryName) {
        final Player p = Bukkit.getPlayer(c.player);
        if (p != null) {
            this.messages.get().send(p, countryName == null ? "gift-player" : "gift-country", Messages.c("ruler", c.rulerLabel),
                Messages.p("amount", this.services.money(amount)), Messages.p("country", countryName == null ? "" : countryName));
        }
    }

    void announceWar(final Conversation c, final KrajInfo npc, final KrajInfo target, final String reason) {
        final Component msg = this.messages.get().get("war-announcement", Messages.c("ruler", c.rulerLabel),
            Messages.c("country", Component.text(npc.name(), npc.color())), Messages.p("player", c.playerName),
            Messages.c("target", Component.text(target.name(), target.color())), Messages.p("reason", reason));
        Bukkit.getServer().sendMessage(msg);
    }

    /** {@code /wladca trybut zaplac|odmow <id>} — the only way money leaves the player's country. */
    public void tributeClick(final Player p, final String id, final boolean pay) {
        final Messages m = this.messages.get();
        final WladcyConfig cfg = this.config.get();
        final long now = this.clock.millis();
        final Optional<TributeOffers.Offer> oo = this.offers.take(id, p.getUniqueId(), now);
        if (oo.isEmpty()) {
            m.send(p, "tribute-expired");
            return;
        }
        final TributeOffers.Offer o = oo.get();
        final Optional<KrajInfo> npc = this.services.kraje().getCountry(o.npc());
        final Optional<KrajInfo> own = this.services.kraje().getCountryOf(p.getUniqueId());
        final String rulerName = npc.map(k -> k.rulerName() == null ? "Władca" : k.rulerName()).orElse("Władca");
        final Component label = npc.map(k -> Component.text(rulerName, k.color())).orElse(Component.text(rulerName));
        final String amount = this.services.money(o.amount());
        final PairMemory mem = this.memory.get(o.npc(), p.getUniqueId());
        if (!pay) {
            if (this.quests != null && this.quests.enabled()) {
                this.quests.onTribute(p, o.npc(), false);
            }
            mem.tributesRefused++;
            mem.addSympathy(cfg.tributeRefuseSympathy);
            this.store.journal(npc.map(KrajInfo::name).orElse("?") + ": " + p.getName() + " odmówił trybutu " + amount);
            m.send(p, "tribute-refused", Messages.c("ruler", label), Messages.p("amount", amount));
            this.event(p.getUniqueId(), o.npc(), "Rozmówca kliknął „Odmów” — odmówił zapłaty trybutu " + amount + ".");
            return;
        }
        final Rank rank = this.services.kraje().getRank(p.getUniqueId()).orElse(Rank.CITIZEN);
        if (npc.isEmpty() || own.isEmpty() || !own.get().id().equals(o.payerCountry()) || !rank.atLeast(cfg.diplomacyRank)) {
            m.send(p, "tribute-not-allowed", Messages.p("rank", cfg.diplomacyRank.displayName()));
            return;
        }
        final TxResult tx = this.services.skarbiec().transfer(SkarbiecApi.countryAccount(o.payerCountry()), SkarbiecApi.countryAccount(o.npc()),
            o.amount(), "Trybut dla " + npc.get().name() + " (" + p.getName() + ")");
        if (!tx.ok()) {
            m.send(p, "tribute-no-money", Messages.p("amount", amount));
            this.event(p.getUniqueId(), o.npc(), "Rozmówca chciał zapłacić trybut " + amount + ", ale w skarbcu jego kraju brakuje pieniędzy.");
            return;
        }
        mem.tributesPaid++;
        mem.tributePaidTotal += o.amount();
        if (this.quests != null && this.quests.enabled()) {
            this.quests.onTribute(p, o.npc(), true);
        }
        mem.addSympathy((int) Math.min(50, o.amount() / SkarbiecApi.COPPER_PER_GOLD * cfg.tributeSympathyPerGold));
        this.store.journal(npc.get().name() + ": trybut " + amount + " od " + own.get().name() + " (" + p.getName() + ")");
        m.send(p, "tribute-paid", Messages.c("ruler", label), Messages.p("amount", amount));
        this.event(p.getUniqueId(), o.npc(), "Rozmówca kliknął „Zapłać” — trybut " + amount + " wpłynął do twojego skarbca.");
    }

    /** A game event for the player's ongoing conversation with {@code npc} (quests: accepted, refused, done…). */
    public void gameEvent(final UUID player, final UUID npc, final String text) {
        this.event(player, npc, text);
    }

    private void event(final UUID player, final UUID npc, final String text) {
        final Conversation c = this.conversations.get(player);
        if (c != null && c.npc.equals(npc)) {
            c.session.addEvent(text);
        }
    }

    // ------------------------------------------------------------------ lifecycle events

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Conversation c = this.conversations.get(event.getPlayer().getUniqueId());
        if (c != null) {
            this.end(c, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisband(final CountryDisbandEvent event) {
        for (final Conversation c : List.copyOf(this.conversations.values())) {
            if (c.npc.equals(event.countryId())) {
                this.end(c, "end-country-gone");
            }
        }
        this.memory.forgetCountry(event.countryId());
    }
}
