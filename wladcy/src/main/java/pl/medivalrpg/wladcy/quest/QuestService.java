// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.armia.api.NpcSettings;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.event.CountryDisbandEvent;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.wladcy.config.Messages;
import pl.medivalrpg.wladcy.config.WladcyConfig;
import pl.medivalrpg.wladcy.game.GameServices;
import pl.medivalrpg.wladcy.game.JsonStore;
import pl.medivalrpg.wladcy.text.TextSafety;
import pl.medivalrpg.wladcy.tools.ToolOutcome;

/**
 * Quests of NPC rulers and reputation, glued to the game: boards from Kraje/Armia/Ludnosc, items taken from the
 * inventory, rewards from the NPC treasury (held in an escrow account from acceptance to turn-in, so money is never
 * created unless {@code reward.create-money}), sealed cargo for transport quests, expiry. Used by
 * {@code /zlecenia}, {@code /reputacja} and the ruler's tools. Main thread only.
 */
public final class QuestService implements Listener {

    /** Receives game events for an ongoing conversation (player, npc country, Polish text for the model). */
    @FunctionalInterface
    public interface EventSink {
        void event(UUID player, UUID npc, String text);
    }

    private final Plugin plugin;
    private final GameServices services;
    private final Supplier<WladcyConfig> config;
    private final Supplier<Messages> messages;
    private final QuestBook book;
    private final ReputationBook reputation;
    private final JsonStore store;
    private final Clock clock;
    private final NamespacedKey cargoKey;
    private final SupplyDeliveryBridge supplyBridge;
    private final BankCheckpointBridge bankBridge;
    private final SupplySettlement supplySettlement;
    private final SupplyAcceptance supplyAcceptance;
    /** Admin refreshes per country (changes the board seed for today; in memory only). */
    private final Map<UUID, Long> salts = new java.util.HashMap<>();
    private EventSink sink = (p, n, t) -> { };
    private BukkitTask ticker;

    public QuestService(final Plugin plugin, final GameServices services, final Supplier<WladcyConfig> config, final Supplier<Messages> messages,
                        final QuestBook book, final ReputationBook reputation, final JsonStore store, final Clock clock) {
        this.plugin = plugin;
        this.services = services;
        this.config = config;
        this.messages = messages;
        this.book = book;
        this.reputation = reputation;
        this.store = store;
        this.clock = clock;
        this.cargoKey = new NamespacedKey(plugin, "ladunek");
        this.supplyBridge = new SupplyDeliveryBridge(() -> {
            final Plugin ludnosc = Bukkit.getPluginManager().getPlugin("Ludnosc");
            return ludnosc != null && ludnosc.isEnabled() ? ludnosc : null;
        });
        this.bankBridge = new BankCheckpointBridge(() -> {
            final Plugin bank = Bukkit.getPluginManager().getPlugin("Skarbiec");
            return bank != null && bank.isEnabled() ? bank : null;
        });
        this.supplySettlement = new SupplySettlement(this.supplyBridge, services.skarbiec(), this.bankBridge,
            () -> this.store.checkpointAsync("zlecenia.json", this.book.snapshot()));
        this.supplyAcceptance = new SupplyAcceptance(services.skarbiec(), this.bankBridge,
            () -> this.store.checkpointAsync("zlecenia.json", this.book.snapshot()), clock);
    }

    public void setEventSink(final EventSink sink) {
        this.sink = sink == null ? (p, n, t) -> { } : sink;
    }

    public void start() {
        this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 200L, 200L);
        // After every plugin is enabled (Skarbiec's bank must answer): cover or flag rewards without an escrow.
        Bukkit.getScheduler().runTask(this.plugin, this::auditEscrows);
    }

    /** Start-up coverage audit (closed money pool): see {@link EscrowCoverage#reconcile}. */
    public EscrowCoverage.Summary auditEscrows() {
        final EscrowCoverage.Summary s;
        try {
            s = EscrowCoverage.reconcile(this.book.allActive(), this.services.skarbiec());
        } catch (final RuntimeException | LinkageError e) {
            this.plugin.getLogger().warning("Audyt depozytów zleceń nieudany: " + e.getMessage());
            return new EscrowCoverage.Summary(0, 0, 0, 0);
        }
        if (s.funded() > 0 || !s.clean()) {
            this.book.markDirty();
            this.store.checkpointAsync("zlecenia.json", this.book.snapshot());
        }
        this.plugin.getLogger().info("Depozyty zleceń: zabezpieczone " + s.secured() + ", dociągnięte ze skarbców " + s.funded()
            + ", bez pokrycia " + s.uncovered() + ", do audytu " + s.audit());
        if (!s.clean()) {
            this.store.journal("Audyt depozytów: " + s.uncovered() + " zleceń bez pokrycia, " + s.audit() + " do sprawdzenia przez administratora");
        }
        return s;
    }

    /** NPC treasury available for rewards: balance minus the wage/upkeep reserve. */
    private long spendable(final UUID country) {
        final QuestConfig cfg = this.q();
        long wages = 0;
        try {
            wages = this.services.armia().dailyWages(country);
        } catch (final RuntimeException | LinkageError ignored) {
            // Armia absent: only the minimum reserve
        }
        final long upkeep = this.services.kraje().getCountry(country).map(k -> (long) k.chunkCount() * 2L).orElse(0L);
        return QuestRules.spendable(this.services.treasury(country), wages, upkeep, cfg.reserveDaysOfWages, cfg.reserveMin);
    }

    // ------------------------------------------------------------------ Firmy bridge (wholesale orders of companies)

    /**
     * Firmy: how much the NPC treasury may commit to ONE wholesale order now — the same treasury percentages as quests
     * ({@code max-treasury-percent-per-quest} of the spendable treasury per order, {@code max-treasury-percent-per-day}
     * minus what quests and orders already took today). Keys {@code perOrder}, {@code perDayLeft}.
     */
    public java.util.Map<String, Long> firmOrderBudget(final UUID npc) {
        final QuestConfig cfg = this.q();
        final long t = Math.max(0, this.spendable(npc));
        final long perOrder = (long) Math.floor(t * cfg.treasuryPercentPerQuest / 100.0);
        final long perDay = (long) Math.floor(t * cfg.treasuryPercentPerDay / 100.0) - this.book.paidToday(npc);
        return java.util.Map.of("perOrder", perOrder, "perDayLeft", Math.max(0, perDay));
    }

    /** Firmy: a funded wholesale order is booked into today's budget of the country (shared with quests). */
    public void firmOrderBooked(final UUID npc, final long amount) {
        this.book.addPaidToday(npc, amount);
    }

    /** Firmy: reputation for the player who delivered a wholesale order (daily gain cap and AI opinion as for quests). */
    public int firmOrderReputation(final UUID npc, final UUID player, final String name, final int delta) {
        final ReputationBook.Change ch = this.reputation.add(npc, player, name, delta, this.q().repDailyGainCap);
        this.opinion(player, npc, ch.applied());
        return ch.applied();
    }

    public void stop() {
        if (this.ticker != null) {
            this.ticker.cancel();
        }
    }

    private QuestConfig q() {
        return this.config.get().quests;
    }

    public boolean enabled() {
        return this.q().enabled;
    }

    public QuestBook book() {
        return this.book;
    }

    public ReputationBook reputation() {
        return this.reputation;
    }

    // ------------------------------------------------------------------ boards

    /** Today's board of an NPC country (generated on first use of the day). */
    public List<QuestData.Offer> board(final KrajInfo npc) {
        final Optional<List<QuestData.Offer>> b = this.book.board(npc.id());
        if (b.isPresent()) {
            return b.get();
        }
        final QuestConfig cfg = this.q();
        final int wealth = this.services.armia().npcSettings(npc.id()).map(NpcSettings::wealth).orElse(50);
        final List<String> needs = this.needs(npc.id(), cfg);
        final List<QuestGenerator.Target> targets = new ArrayList<>();
        if (npc.capitalWorld() != null) {
            for (final KrajInfo k : this.services.kraje().getCountries()) {
                if (k.id().equals(npc.id()) || !npc.capitalWorld().equals(k.capitalWorld()) || npc.enemies().contains(k.id())) {
                    continue;
                }
                final int d = QuestRules.chunkDistance(npc.capitalX(), npc.capitalZ(), k.capitalX(), k.capitalZ());
                if (d >= cfg.transportMinDistance && d <= cfg.transportMaxDistance) {
                    targets.add(new QuestGenerator.Target(k.id().toString(), k.name(), d, npc.allies().contains(k.id())));
                }
            }
        }
        targets.sort(Comparator.comparing(QuestGenerator.Target::id));
        final Set<String> fresh = new HashSet<>();
        final List<QuestData.Offer> offers = QuestGenerator.generate(
            new QuestGenerator.Input(npc.id().toString(), this.book.today(), wealth, needs, targets, this.salt(npc.id())), cfg, () -> {
                String id;
                do {
                    id = this.book.newOfferId();
                } while (!fresh.add(id));
                return id;
            });
        this.book.putBoard(npc.id(), offers);
        return offers;
    }

    private long salt(final UUID country) {
        return this.salts.getOrDefault(country, 0L);
    }

    /** Admin: new boards (different offers) for one country or all. Returns how many were dropped. */
    public int refreshBoards(final KrajInfo npc) {
        if (npc != null) {
            this.salts.merge(npc.id(), 1L, Long::sum);
            this.book.dropBoard(npc.id());
            return 1;
        }
        int n = 0;
        for (final KrajInfo k : this.services.kraje().getCountries()) {
            if (k.npc()) {
                this.salts.merge(k.id(), 1L, Long::sum);
                n++;
            }
        }
        this.book.dropBoard(null);
        return n;
    }

    /** Resource keys the country lacks (Ludnosc), most urgent first; empty without Ludnosc. */
    public List<String> needs(final UUID country, final QuestConfig cfg) {
        final LudnoscApi lud = this.services.ludnosc();
        if (lud == null) {
            return List.of();
        }
        try {
            final Map<String, Long> stock = lud.resources(country);
            final Optional<LudnoscInfo> info = lud.info(country);
            final QuestGenerator.Food food = info.map(i -> new QuestGenerator.Food(i.food(), i.foodCapacity(), i.foodBalance(), i.hungryDays())).orElse(null);
            return QuestGenerator.needs(stock, food, cfg.resources);
        } catch (final RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /** Country by name (exact, then case-insensitive); {@code npcOnly} limits to NPC countries. */
    public Optional<KrajInfo> findCountry(final String name, final boolean npcOnly) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        final String n = name.trim();
        final Optional<KrajInfo> exact = this.services.kraje().getCountryByName(n).filter(k -> !npcOnly || k.npc());
        if (exact.isPresent()) {
            return exact;
        }
        for (final KrajInfo k : this.services.kraje().getCountries()) {
            if ((!npcOnly || k.npc()) && k.name().equalsIgnoreCase(n)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    /** Names of NPC countries, sorted (command suggestions). */
    public List<String> npcNames() {
        final List<String> out = new ArrayList<>();
        for (final KrajInfo k : this.services.kraje().getCountries()) {
            if (k.npc()) {
                out.add(k.name());
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Ids of the player's active quests and of today's offers they could still answer (command suggestions). Read-only
     * and tolerant of concurrent changes: suggestions may be computed off the main thread.
     */
    public List<String> suggestIds(final UUID player, final boolean offers) {
        final List<String> out = new ArrayList<>();
        try {
            if (offers) {
                final String today = this.book.today();
                for (final QuestData.Board b : List.copyOf(this.book.boards().values())) {
                    if (!today.equals(b.day)) {
                        continue;
                    }
                    for (final QuestData.Offer o : List.copyOf(b.offers)) {
                        if (!this.book.usedPeek(player, o.id)) {
                            out.add(o.id);
                        }
                    }
                }
            } else {
                this.book.active(player).forEach(a -> out.add(a.offerId));
            }
        } catch (final RuntimeException concurrent) {
            // the main thread changed the data meanwhile; no suggestions this time
        }
        return out;
    }

    // ------------------------------------------------------------------ reputation helpers

    public int value(final UUID npc, final UUID player) {
        return this.reputation.value(npc, player);
    }

    public RepTier tier(final UUID npc, final UUID player) {
        return this.reputation.tier(npc, player);
    }

    /** Multiplier of the per-player daily gift limit of the ruler's {@code podaruj} tool. */
    public double giftMultiplier(final UUID npc, final UUID player) {
        return this.q().giftMultiplier(this.tier(npc, player));
    }

    /** Tribute paid/refused from a conversation (ConversationManager). */
    public void onTribute(final Player p, final UUID npc, final boolean paid) {
        final QuestConfig cfg = this.q();
        final int delta = paid ? cfg.repTributePaid : cfg.repTributeRefused;
        if (delta != 0) {
            this.change(p, npc, delta);
        }
    }

    private ReputationBook.Change change(final Player p, final UUID npc, final int delta) {
        final ReputationBook.Change ch = this.reputation.add(npc, p.getUniqueId(), p.getName(), delta, this.q().repDailyGainCap);
        this.opinion(p.getUniqueId(), npc, ch.applied());
        if (ch.tierChanged()) {
            final Messages m = this.messages.get();
            this.services.kraje().getCountry(npc).ifPresent(k -> m.send(p, ch.after().ordinal() > ch.before().ordinal() ? "quest-tier-up" : "quest-tier-down",
                Messages.c("country", country(k)), Messages.c("tier", this.tierLabel(ch.after()))));
        }
        return ch;
    }

    /**
     * The player's deeds also move the NPC AI's opinion of the player's COUNTRY (Armia, war/alliance choices). Uses the
     * applied (daily-capped) reputation change, scaled down ({@link QuestRules#opinionDelta}); players without a country
     * and a missing/older Armia are skipped.
     */
    private void opinion(final UUID player, final UUID npc, final int appliedRep) {
        final int delta = QuestRules.opinionDelta(appliedRep);
        if (delta == 0) {
            return;
        }
        try {
            final pl.medivalrpg.armia.api.ArmiaApi armia = this.services.armia();
            if (armia == null) {
                return;
            }
            this.services.kraje().getCountryOf(player).filter(k -> !k.id().equals(npc))
                .ifPresent(k -> armia.adjustOpinion(npc, k.id(), delta));
        } catch (final RuntimeException | LinkageError ignored) {
            // Armia unavailable: reputation still counts for the ruler's conversations
        }
    }

    public Component tierLabel(final RepTier t) {
        return this.messages.get().parse(t.colorTag() + t.displayName());
    }

    private static Component country(final KrajInfo k) {
        return Component.text(k.name(), k.color());
    }

    private Component countryOf(final String id, final String fallbackName) {
        try {
            final Optional<KrajInfo> k = this.services.kraje().getCountry(UUID.fromString(id));
            if (k.isPresent()) {
                return country(k.get());
            }
        } catch (final IllegalArgumentException ignored) {
            // malformed id from a hand-edited file
        }
        return Component.text(fallbackName == null || fallbackName.isBlank() ? "?" : fallbackName);
    }

    private static String signed(final int v) {
        return v > 0 ? "+" + v : Integer.toString(v);
    }

    // ------------------------------------------------------------------ views

    /** {@code /zlecenia}: active quests, then the board of the NPC country the player stands in (or a list of them). */
    public void list(final Player p) {
        final Messages m = this.messages.get();
        if (!this.enabled()) {
            m.send(p, "quest-disabled");
            return;
        }
        for (final String notice : this.book.takeNotices(p.getUniqueId())) {
            m.send(p, "quest-notice", Messages.p("text", notice));
        }
        this.showActive(p);
        final Optional<KrajInfo> here = this.services.kraje().getCountryAt(p.getLocation()).filter(KrajInfo::npc);
        if (here.isPresent()) {
            this.showBoard(p, here.get());
        } else {
            this.showCountries(p);
        }
    }

    private void showActive(final Player p) {
        final Messages m = this.messages.get();
        final QuestConfig cfg = this.q();
        final List<QuestData.Active> act = this.book.active(p.getUniqueId());
        m.send(p, "quest-list-header", Messages.p("active", act.size()), Messages.p("max", cfg.maxActive),
            Messages.p("done", this.book.completedToday(p.getUniqueId())), Messages.p("max_day", cfg.maxCompletedPerDay));
        if (act.isEmpty()) {
            m.send(p, "quest-active-none");
            return;
        }
        final long now = this.clock.millis();
        for (final QuestData.Active a : act) {
            if (SupplyAcceptance.pending(a)) {
                p.sendMessage(m.get("quest-acceptance-line", Messages.p("id", a.offerId),
                    Messages.c("country", this.countryOf(a.country, a.countryName)))
                    .clickEvent(ClickEvent.runCommand("/zlecenia przyjmij " + a.offerId)));
                continue;
            }
            final Component turnIn = m.get("quest-turnin-button")
                .hoverEvent(HoverEvent.showText(m.get("quest-turnin-hover", Messages.c("where", this.where(a)))))
                .clickEvent(ClickEvent.runCommand("/zlecenia oddaj " + a.offerId));
            final Component abandon = m.get("quest-abandon-button")
                .hoverEvent(HoverEvent.showText(m.get("quest-abandon-hover", Messages.p("rep", signed(cfg.repAbandon)))))
                .clickEvent(ClickEvent.runCommand("/zlecenia porzuc " + a.offerId));
            p.sendMessage(m.get("quest-active-line", Messages.p("id", a.offerId), Messages.c("type", this.typeLabel(a.type())),
                Messages.c("country", this.countryOf(a.country, a.countryName)), Messages.c("task", this.task(a)),
                Messages.p("reward", this.services.money(a.reward)), Messages.p("left", QuestRules.duration(a.deadline - now)),
                Messages.c("turnin", turnIn), Messages.c("abandon", abandon),
                Messages.c("secured", m.get(EscrowCoverage.secured(a, this.services.skarbiec()) ? "quest-reward-secured" : "quest-reward-uncovered"))));
        }
    }

    /** Board of one NPC country, as seen by this player (reward with their reputation, lock reasons). */
    public void showBoard(final Player p, final KrajInfo npc) {
        final Messages m = this.messages.get();
        if (!this.enabled()) {
            m.send(p, "quest-disabled");
            return;
        }
        final QuestConfig cfg = this.q();
        final RepTier tier = this.tier(npc.id(), p.getUniqueId());
        m.send(p, "quest-board-header", Messages.c("country", country(npc)), Messages.c("tier", this.tierLabel(tier)),
            Messages.p("value", this.value(npc.id(), p.getUniqueId())));
        final List<QuestData.Offer> offers = this.board(npc);
        if (offers.isEmpty()) {
            m.send(p, "quest-board-empty");
            return;
        }
        for (final QuestData.Offer o : offers) {
            final QuestRules.RewardQuote quote = this.quote(o, tier);
            final Optional<QuestRules.Denial> deny = this.canAccept(this.facts(p.getUniqueId(), o, tier, quote), o, cfg);
            final Component buttons;
            if (deny.isEmpty()) {
                buttons = this.acceptButtons(o.id);
            } else if (deny.get() == QuestRules.Denial.USED || deny.get() == QuestRules.Denial.ACTIVE_HERE) {
                buttons = m.get("quest-board-used");
            } else {
                buttons = m.get("quest-board-locked").hoverEvent(HoverEvent.showText(this.denial(deny.get(), npc, p.getUniqueId())));
            }
            p.sendMessage(m.get("quest-board-line", Messages.p("id", o.id), Messages.c("type", this.typeLabel(o.type())),
                Messages.c("premium", o.premium ? m.get("quest-board-premium") : Component.empty()),
                Messages.c("task", this.task(o)), Messages.p("reward", this.services.money(quote.reward())),
                Messages.p("time", QuestRules.duration(o.timeLimitMinutes * 60_000L)), Messages.p("rep", o.reputation),
                Messages.c("buttons", buttons), Messages.c("secured", m.get("quest-board-secured"))));
        }
    }

    private Component acceptButtons(final String id) {
        final Messages m = this.messages.get();
        final Component accept = m.get("quest-accept-button")
            .hoverEvent(HoverEvent.showText(m.get("quest-accept-hover", Messages.p("id", id))))
            .clickEvent(ClickEvent.runCommand("/zlecenia przyjmij " + id));
        final Component refuse = m.get("quest-refuse-button")
            .hoverEvent(HoverEvent.showText(m.get("quest-refuse-hover", Messages.p("rep", signed(this.q().repRefuse)))))
            .clickEvent(ClickEvent.runCommand("/zlecenia odrzuc " + id));
        return Component.join(JoinConfiguration.separator(Component.space()), accept, refuse);
    }

    /** NPC countries with their offer counts, nearest first (clickable). */
    private void showCountries(final Player p) {
        final Messages m = this.messages.get();
        final List<KrajInfo> npcs = new ArrayList<>();
        for (final KrajInfo k : this.services.kraje().getCountries()) {
            if (k.npc()) {
                npcs.add(k);
            }
        }
        if (npcs.isEmpty()) {
            m.send(p, "quest-no-npc-countries");
            return;
        }
        final String world = p.getWorld().getName();
        final int px = p.getLocation().getBlockX() >> 4;
        final int pz = p.getLocation().getBlockZ() >> 4;
        npcs.sort(Comparator.comparingLong(k -> world.equals(k.capitalWorld()) ? QuestRules.chunkDistance(px, pz, k.capitalX(), k.capitalZ()) : Long.MAX_VALUE));
        m.send(p, "quest-nearby-header");
        int shown = 0;
        for (final KrajInfo k : npcs) {
            if (shown++ >= 8) {
                break;
            }
            final String dist = world.equals(k.capitalWorld()) ? QuestRules.chunkDistance(px, pz, k.capitalX(), k.capitalZ()) + " bl." : "—";
            p.sendMessage(m.get("quest-nearby-line", Messages.c("country", country(k)), Messages.p("offers", this.board(k).size()),
                    Messages.c("tier", this.tierLabel(this.tier(k.id(), p.getUniqueId()))), Messages.p("distance", dist))
                .hoverEvent(HoverEvent.showText(m.get("quest-nearby-hover")))
                .clickEvent(ClickEvent.runCommand("/zlecenia kraj " + k.name())));
        }
        m.send(p, "quest-not-in-npc");
    }

    // ------------------------------------------------------------------ texts

    private Component typeLabel(final QuestType t) {
        return this.messages.get().get(dynamicKey("quest-type", t.key()));
    }

    private Component task(final QuestType type, final int amount, final String item, final boolean urgent, final String target, final int distance) {
        final Messages m = this.messages.get();
        return switch (type) {
            case DOSTAWA -> m.get("quest-task-dostawa", Messages.p("amount", amount), Messages.p("item", item));
            case SUROWCE -> m.get("quest-task-surowce", Messages.p("amount", amount), Messages.p("item", item),
                Messages.c("urgent", urgent ? m.get("quest-task-urgent") : Component.empty()));
            case TRANSPORT -> m.get("quest-task-transport", Messages.p("item", item), Messages.p("target", target), Messages.p("distance", distance));
        };
    }

    private Component task(final QuestData.Offer o) {
        return this.task(o.type(), o.amount, o.itemName, o.urgent, o.targetName, o.distance);
    }

    private Component task(final QuestData.Active a) {
        return this.task(a.type(), a.amount, a.itemName, false, a.targetName, a.distance);
    }

    private Component where(final QuestData.Active a) {
        final Messages m = this.messages.get();
        if (a.type() == QuestType.TRANSPORT) {
            return m.get("quest-where-target", Messages.c("target", this.countryOf(a.targetCountry, a.targetName)),
                Messages.p("radius", this.q().capitalRadiusChunks));
        }
        return m.get("quest-where-home", Messages.c("country", this.countryOf(a.country, a.countryName)), Messages.p("radius", this.q().capitalRadiusChunks));
    }

    /** Plain Polish description for the ruler's prompt and tool results. */
    static String taskText(final QuestType type, final int amount, final String item, final String target, final int distance) {
        return switch (type) {
            case DOSTAWA -> "dostawa " + amount + " × " + item + " do twojej stolicy";
            case SUROWCE -> "surowce: " + amount + " × " + item + " do twojej stolicy";
            case TRANSPORT -> "przewóz ładunku („" + item + "”) do stolicy kraju " + target + " (ok. " + distance + " bloków)";
        };
    }

    private Component denial(final QuestRules.Denial d, final KrajInfo npc, final UUID player) {
        final Messages m = this.messages.get();
        final QuestConfig cfg = this.q();
        final int limit = switch (d) {
            case MAX_ACTIVE -> cfg.maxActive;
            case DAILY -> cfg.maxCompletedPerDay;
            case COUNTRY_DAILY -> cfg.maxPerCountryPerDay;
            default -> 0;
        };
        final long left = this.book.cooldownUntil(player, npc.id()) - this.clock.millis();
        return m.get(dynamicKey("quest-deny", d.key()), Messages.c("country", country(npc)), Messages.c("tier", this.tierLabel(this.tier(npc.id(), player))),
            Messages.p("limit", limit), Messages.p("time", QuestRules.duration(left)));
    }

    // ------------------------------------------------------------------ rules glue

    private Optional<QuestRules.Denial> canAccept(final QuestRules.AcceptFacts facts, final QuestData.Offer offer, final QuestConfig cfg) {
        final List<String> currentNeeds = offer.type() == QuestType.SUROWCE && offer.urgent
            ? this.needs(UUID.fromString(offer.country), cfg) : List.of();
        final Optional<QuestRules.Denial> denied = QuestRules.canAccept(facts, cfg, offer, currentNeeds);
        if (denied.isEmpty() && offer.type() == QuestType.SUROWCE && !this.supplyBridge.available()) {
            return Optional.of(QuestRules.Denial.SUPPLY_UNAVAILABLE);
        }
        if (denied.isEmpty() && !this.bankBridge.available()) {
            return Optional.of(QuestRules.Denial.BANK_UNAVAILABLE);
        }
        return denied;
    }

    private QuestRules.RewardQuote quote(final QuestData.Offer o, final RepTier tier) {
        final UUID country = UUID.fromString(o.country);
        // Closed pool: every reward must be covered by the NPC treasury above its wage/upkeep reserve.
        return QuestRules.quoteFunded(o.baseReward, tier, this.q(), this.spendable(country), this.book.paidToday(country));
    }

    private QuestRules.AcceptFacts facts(final UUID player, final QuestData.Offer o, final RepTier tier, final QuestRules.RewardQuote quote) {
        final UUID country = UUID.fromString(o.country);
        final List<QuestData.Active> act = this.book.active(player);
        final boolean here = act.stream().anyMatch(a -> a.offerId.equalsIgnoreCase(o.id));
        return new QuestRules.AcceptFacts(this.enabled(), tier, o.type(), o.premium, act.size(), here, this.book.completedToday(player),
            this.book.completedToday(player, country), this.book.used(player, o.id), this.book.cooldownUntil(player, country),
            this.book.lastAcceptAt(player), this.clock.millis(), quote);
    }

    // ------------------------------------------------------------------ places

    /** Standing within {@code capital-radius-chunks} of the country's capital, or next to its NPC ruler. */
    private boolean atCapital(final Player p, final KrajInfo k) {
        if (k.capitalWorld() != null && k.capitalWorld().equals(p.getWorld().getName())
            && QuestRules.nearCapital(p.getLocation().getBlockX() >> 4, p.getLocation().getBlockZ() >> 4, k.capitalX(), k.capitalZ(), this.q().capitalRadiusChunks)) {
            return true;
        }
        return this.nearRuler(p, k.id());
    }

    private boolean nearRuler(final Player p, final UUID country) {
        final double r = this.config.get().startRadius + 2;
        for (final Entity e : p.getNearbyEntities(r, r, r)) {
            if (this.services.armia().rulerCountry(e).filter(country::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean onLandOrAtRuler(final Player p, final KrajInfo npc) {
        if (this.q().acceptAnywhere) {
            return true;
        }
        if (this.services.kraje().getCountryAt(p.getLocation()).map(KrajInfo::id).filter(npc.id()::equals).isPresent()) {
            return true;
        }
        return this.nearRuler(p, npc.id());
    }

    // ------------------------------------------------------------------ accept / refuse

    /** Outcome of an action, with a Polish text for the ruler's tool result. */
    public record Result(boolean ok, String forModel) {
    }

    /** {@code /zlecenia przyjmij <id>} (also the [Przyjmij] button of a ruler's offer). */
    public Result accept(final Player p, final String rawId) {
        final Messages m = this.messages.get();
        final QuestConfig cfg = this.q();
        final String id = rawId.trim().toLowerCase(Locale.ROOT);
        final Optional<QuestData.Active> pending = this.book.active(p.getUniqueId(), id).filter(SupplyAcceptance::pending);
        if (pending.isPresent()) {
            return this.resumeAcceptance(p, pending.get(), true);
        }
        if (!cfg.enabled) {
            m.send(p, "quest-disabled");
            return new Result(false, QuestRules.Denial.DISABLED.forModel());
        }
        final Optional<QuestData.Offer> oo = this.book.offer(id);
        final Optional<KrajInfo> npcOpt = oo.flatMap(o -> this.services.kraje().getCountry(UUID.fromString(o.country))).filter(KrajInfo::npc);
        if (oo.isEmpty() || npcOpt.isEmpty()) {
            m.send(p, "quest-unknown");
            return new Result(false, "Nie ma takiego zlecenia.");
        }
        final QuestData.Offer o = oo.get();
        final KrajInfo npc = npcOpt.get();
        if (!this.onLandOrAtRuler(p, npc)) {
            m.send(p, "quest-accept-where", Messages.c("country", country(npc)));
            return new Result(false, "Rozmówca musi być na ziemi twojego kraju albo przy tobie.");
        }
        final RepTier tier = this.tier(npc.id(), p.getUniqueId());
        final QuestRules.RewardQuote quote = this.quote(o, tier);
        final Optional<QuestRules.Denial> deny = this.canAccept(this.facts(p.getUniqueId(), o, tier, quote), o, cfg);
        if (deny.isPresent()) {
            p.sendMessage(this.denial(deny.get(), npc, p.getUniqueId()));
            return new Result(false, deny.get().forModel());
        }
        // Every quest is funded: the reward moves from the NPC treasury into its own escrow before the quest exists.
        final QuestData.Active intent = this.book.prepareFundedAcceptance(p.getUniqueId(), p.getName(), o, npc.name(),
            quote.reward(), UUID.randomUUID().toString());
        return this.resumeAcceptance(p, intent, true);
    }

    private Result resumeAcceptance(final Player player, final QuestData.Active quest, final boolean reportPending) {
        final SupplyAcceptance.Attempt result;
        if (this.countryById(quest.country).isEmpty()) {
            quest.acceptancePhase = SupplyAcceptance.Phase.AUDIT;
            result = new SupplyAcceptance.Attempt(SupplyAcceptance.Status.AUDIT, "Kraj zleceniodawcy nie istnieje — zamiar finansowania wymaga audytu.");
        } else {
            result = this.supplyAcceptance.advance(quest);
        }
        this.book.markDirty();
        if (result.status() != SupplyAcceptance.Status.ACCEPTED) {
            if (player != null && reportPending) {
                this.messages.get().send(player, "quest-acceptance-pending", Messages.p("id", quest.offerId), Messages.p("reason", result.reason()));
            }
            return new Result(false, "Przyjęcie #" + quest.offerId + " jeszcze nie jest potwierdzone: " + TextSafety.forPrompt(result.reason(), 240));
        }
        if (player != null) return this.announceAcceptance(player, quest);
        // Keep the durable notification pending until the player is online (also for sealed cargo delivery).
        return new Result(true, "Finansowanie przyjęcia #" + quest.offerId + " potwierdzone; gracz otrzyma informację po powrocie.");
    }

    private Result announceAcceptance(final Player p, final QuestData.Active a) {
        final Messages m = this.messages.get();
        m.send(p, "quest-accepted", Messages.p("id", a.offerId), Messages.c("country", this.countryOf(a.country, a.countryName)), Messages.c("task", this.task(a)),
            Messages.p("reward", this.services.money(a.reward)), Messages.p("time", QuestRules.duration(a.deadline - a.acceptedAt)),
            Messages.c("where", this.where(a)), Messages.c("escrow", a.escrow ? m.get("quest-accepted-escrow") : Component.empty()));
        if (a.type() == QuestType.TRANSPORT && this.countCargo(p, a.uid) <= 0) {
            this.giveCargo(p, a);
        }
        if (a.supplyDelivery) {
            m.send(p, "quest-supply-required");
        }
        a.acceptanceNoticePending = false;
        this.book.markDirty();
        this.supplyAcceptance.forget(a.uid);
        this.store.saveAsync("zlecenia.json", this.book.snapshot());
        final String text = taskText(a.type(), a.amount, a.itemName, a.targetName, a.distance);
        this.store.journal(a.countryName + ": " + p.getName() + " przyjął zlecenie #" + a.offerId + " (" + text + "), nagroda " + this.services.money(a.reward));
        this.sink.event(p.getUniqueId(), UUID.fromString(a.country), "Rozmówca przyjął twoje zlecenie #" + a.offerId + ": " + text + ", nagroda "
            + this.services.money(a.reward) + ", czas " + QuestRules.duration(a.deadline - a.acceptedAt) + ".");
        return new Result(true, "Rozmówca przyjął zlecenie #" + a.offerId + ".");
    }

    /** {@code /zlecenia odrzuc <id>}: hides the offer for today, slightly lowers the reputation. */
    public void refuse(final Player p, final String rawId) {
        final Messages m = this.messages.get();
        final String id = rawId.trim().toLowerCase(Locale.ROOT);
        final Optional<QuestData.Offer> oo = this.book.offer(id);
        if (oo.isEmpty() || this.book.used(p.getUniqueId(), id) || this.book.active(p.getUniqueId(), id).isPresent()) {
            m.send(p, "quest-unknown");
            return;
        }
        final QuestData.Offer o = oo.get();
        final UUID npc = UUID.fromString(o.country);
        this.book.refuse(p.getUniqueId(), id);
        final ReputationBook.Change ch = this.change(p, npc, this.q().repRefuse);
        m.send(p, "quest-refused", Messages.p("id", o.id), Messages.c("country", this.countryOf(o.country, "?")), Messages.p("rep_change", signed(ch.applied())));
        this.sink.event(p.getUniqueId(), npc, "Rozmówca odrzucił twoje zlecenie #" + o.id + ".");
    }

    // ------------------------------------------------------------------ turn in

    /** {@code /zlecenia oddaj [id]}: every ready quest (or the one given) is turned in where the player stands. */
    public void turnIn(final Player p, final String rawId) {
        final Messages m = this.messages.get();
        final List<QuestData.Active> list;
        if (rawId == null || rawId.isBlank()) {
            list = this.book.active(p.getUniqueId());
        } else {
            list = this.book.active(p.getUniqueId(), rawId.trim()).map(List::of).orElse(List.of());
            if (list.isEmpty()) {
                m.send(p, "quest-unknown");
                return;
            }
        }
        if (list.isEmpty()) {
            m.send(p, "quest-turnin-none");
            return;
        }
        final List<Component> problems = new ArrayList<>();
        for (final QuestData.Active a : list) {
            final TurnIn t = this.tryTurnIn(p, a);
            if (!t.done() && t.problem() != null) {
                problems.add(t.problem());
            }
        }
        problems.forEach(p::sendMessage);
    }

    private record TurnIn(boolean done, Component problem, String forModel) {
    }

    private TurnIn tryTurnIn(final Player p, final QuestData.Active a) {
        final Messages m = this.messages.get();
        if (!a.player.equals(p.getUniqueId().toString()) || !this.book.isActive(a.uid)) {
            return new TurnIn(false, m.get("quest-unknown"), "Zlecenie zostało już rozliczone albo nie należy do rozmówcy.");
        }
        if (SupplyAcceptance.pending(a)) {
            final Result resumed = this.resumeAcceptance(p, a, true);
            if (!resumed.ok()) return new TurnIn(false, null, resumed.forModel());
        }
        if (QuestRules.expired(a, this.clock.millis()) && !SupplySettlement.protectedFromCancellation(a)) {
            return new TurnIn(false, null, "#" + a.offerId + ": czas minął.");
        }
        final boolean transport = a.type() == QuestType.TRANSPORT;
        final Optional<KrajInfo> place = this.countryById(transport ? a.targetCountry : a.country);
        if (place.isEmpty()) {
            return new TurnIn(false, null, "#" + a.offerId + ": kraj docelowy nie istnieje.");
        }
        if (!this.atCapital(p, place.get())) {
            return new TurnIn(false, m.get("quest-turnin-where", Messages.p("id", a.offerId), Messages.c("where", this.where(a))),
                "#" + a.offerId + ": rozmówca nie jest we właściwym miejscu (" + (transport ? "stolica kraju " + a.targetName : "twoja stolica") + ").");
        }
        if (a.supplyDelivery) {
            final SupplySettlement.Attempt result = this.supplySettlement.advance(a);
            this.book.markDirty();
            if (result.status() != SupplySettlement.Status.PAID) {
                final String key = switch (result.status()) {
                    case PENDING -> "quest-supply-pending";
                    case BLOCKED -> "quest-supply-blocked";
                    case UNAVAILABLE -> "quest-supply-unavailable";
                    case PAYMENT -> "quest-supply-payment";
                    default -> "quest-supply-audit";
                };
                return new TurnIn(false, m.get(key, Messages.p("id", a.offerId), Messages.p("reason", result.reason())),
                    "#" + a.offerId + ": " + result.status() + ". " + TextSafety.forPrompt(result.reason(), 240));
            }
        } else if (transport) {
            if (this.countCargo(p, a.uid) <= 0) {
                return new TurnIn(false, m.get("quest-turnin-no-cargo", Messages.p("id", a.offerId), Messages.p("cargo", a.itemName)),
                    "#" + a.offerId + ": rozmówca nie ma przy sobie ładunku.");
            }
            this.removeCargo(p, a.uid);
        } else {
            final Material mat = Material.matchMaterial(a.material);
            final int have = mat == null ? 0 : this.countItems(p, mat);
            if (mat == null || have < a.amount) {
                return new TurnIn(false, m.get("quest-turnin-missing", Messages.p("id", a.offerId), Messages.p("missing", a.amount - have),
                        Messages.p("item", a.itemName), Messages.p("have", have), Messages.p("amount", a.amount)),
                    "#" + a.offerId + ": rozmówcy brakuje " + (a.amount - have) + " × " + a.itemName + " (ma " + have + "/" + a.amount + ").");
            }
            this.takeItems(p, mat, a.amount);
        }
        final long paid = a.supplyDelivery ? a.reward : this.payout(a);
        if (!this.book.complete(a, paid)) {
            return new TurnIn(false, m.get("quest-unknown"), "Zlecenie zostało już rozliczone.");
        }
        this.supplySettlement.forget(a.uid);
        final UUID npc = UUID.fromString(a.country);
        final ReputationBook.Change ch = this.change(p, npc, a.reputation);
        this.reputation.countCompleted(npc, p.getUniqueId(), p.getName());
        if (a.supplyDelivery) {
            this.store.saveAsync("zlecenia.json", this.book.snapshot());
            this.store.saveAsync("reputacja.json", this.reputation.snapshot());
        }
        final Component countryLabel = this.countryOf(a.country, a.countryName);
        if (paid > 0) {
            m.send(p, "quest-completed", Messages.p("id", a.offerId), Messages.p("reward", this.services.money(paid)), Messages.c("country", countryLabel),
                Messages.p("rep_change", signed(ch.applied())), Messages.c("tier", this.tierLabel(ch.after())), Messages.p("value", ch.value()));
        } else {
            m.send(p, "quest-completed-unpaid", Messages.p("id", a.offerId), Messages.c("country", countryLabel), Messages.p("rep_change", signed(ch.applied())));
        }
        final String text = taskText(a.type(), a.amount, a.itemName, a.targetName, a.distance);
        this.store.journal(a.countryName + ": " + p.getName() + " wykonał zlecenie #" + a.offerId + " (" + text + "), wypłacono " + this.services.money(paid));
        final String forModel = "Zlecenie #" + a.offerId + " wykonane (" + text + "). Wypłacono " + this.services.money(paid)
            + ". Reputacja rozmówcy: " + ch.value() + " (" + ch.after().displayName() + ").";
        this.sink.event(p.getUniqueId(), npc, forModel);
        if (transport) {
            this.services.kraje().getCountry(UUID.fromString(a.targetCountry))
                .ifPresent(t -> this.sink.event(p.getUniqueId(), t.id(), "Rozmówca dostarczył do twojej stolicy ładunek od kraju " + a.countryName + "."));
        }
        return new TurnIn(true, null, forModel);
    }

    private Optional<KrajInfo> countryById(final String id) {
        try {
            return this.services.kraje().getCountry(UUID.fromString(id));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code quest-type-<key>} / {@code quest-deny-<key>} (built here so the message-key test checks them separately). */
    static String dynamicKey(final String prefix, final String key) {
        return prefix + "-" + key;
    }

    static String escrowAccount(final String uid) {
        return "wladcy:zlecenie-" + uid;
    }

    /** Pays the reward to the player's bank account. Returns what was actually paid. */
    private long payout(final QuestData.Active a) {
        final SkarbiecApi eco = this.services.skarbiec();
        final String to = SkarbiecApi.playerAccount(UUID.fromString(a.player));
        final String reason = "Nagroda za zlecenie #" + a.offerId + " (" + a.countryName + ")";
        // Only the quest's escrow (legacy quests: the country's treasury) — never minted, never paid twice.
        return EscrowCoverage.payout(a, eco, to, reason);
    }

    /** Returns the escrowed reward to the country's treasury. */
    private void refund(final QuestData.Active a) {
        if (!a.escrow || SupplySettlement.protectedFromCancellation(a)) {
            return;
        }
        EscrowCoverage.refund(a, this.services.skarbiec());
    }

    // ------------------------------------------------------------------ abandon / expiry

    /** {@code /zlecenia porzuc [id]}. */
    public void abandon(final Player p, final String rawId) {
        final Messages m = this.messages.get();
        final List<QuestData.Active> act = this.book.active(p.getUniqueId());
        final Optional<QuestData.Active> target;
        if (rawId == null || rawId.isBlank()) {
            if (act.size() != 1) {
                m.send(p, act.isEmpty() ? "quest-turnin-none" : "quest-abandon-which");
                return;
            }
            target = Optional.of(act.get(0));
        } else {
            target = this.book.active(p.getUniqueId(), rawId.trim());
        }
        if (target.isEmpty()) {
            m.send(p, "quest-unknown");
            return;
        }
        final QuestData.Active a = target.get();
        if (SupplySettlement.protectedFromCancellation(a)) {
            m.send(p, "quest-supply-pending", Messages.p("id", a.offerId),
                Messages.p("reason", "Najpierw rozstrzygnij odbiór i wypłatę przez ponowne oddanie tego zlecenia."));
            return;
        }
        final QuestConfig cfg = this.q();
        this.removeCargo(p, a.uid);
        this.refund(a);
        this.book.fail(a, cfg.afterFailMinutes);
        this.supplySettlement.forget(a.uid);
        final UUID npc = UUID.fromString(a.country);
        final ReputationBook.Change ch = this.change(p, npc, cfg.repAbandon);
        this.reputation.countAbandoned(npc, p.getUniqueId(), p.getName());
        m.send(p, "quest-abandoned", Messages.p("id", a.offerId), Messages.c("country", this.countryOf(a.country, a.countryName)),
            Messages.p("rep_change", signed(ch.applied())), Messages.p("minutes", cfg.afterFailMinutes));
        this.store.journal(a.countryName + ": " + p.getName() + " porzucił zlecenie #" + a.offerId);
        this.sink.event(p.getUniqueId(), npc, "Rozmówca porzucił twoje zlecenie #" + a.offerId + ".");
    }

    private void tick() {
        final QuestConfig cfg = this.q();
        for (final QuestData.Active intent : this.book.allActive()) {
            if (!SupplyAcceptance.pending(intent) || intent.acceptancePhase == SupplyAcceptance.Phase.AUDIT) continue;
            final Player player = Bukkit.getPlayer(UUID.fromString(intent.player));
            if (player == null && intent.acceptancePhase == SupplyAcceptance.Phase.ACCEPTED) continue;
            this.resumeAcceptance(player, intent, false);
        }
        for (final QuestData.Active a : this.book.expired()) {
            final UUID playerId = UUID.fromString(a.player);
            final UUID npc = UUID.fromString(a.country);
            this.refund(a);
            this.book.fail(a, cfg.afterFailMinutes);
            this.supplySettlement.forget(a.uid);
            final ReputationBook.Change ch = this.reputation.add(npc, playerId, a.playerName, cfg.repFail, cfg.repDailyGainCap);
            this.opinion(playerId, npc, ch.applied());
            this.reputation.countFailed(npc, playerId, a.playerName);
            this.store.journal(a.countryName + ": " + a.playerName + " nie zdążył ze zleceniem #" + a.offerId);
            final Player p = Bukkit.getPlayer(playerId);
            final String task = taskText(a.type(), a.amount, a.itemName, a.targetName, a.distance);
            if (p != null) {
                this.removeCargo(p, a.uid);
                this.messages.get().send(p, "quest-expired", Messages.p("id", a.offerId), Messages.p("task", a.itemName),
                    Messages.c("country", this.countryOf(a.country, a.countryName)), Messages.p("rep_change", signed(ch.applied())));
                this.sink.event(playerId, npc, "Rozmówca nie zdążył ze zleceniem #" + a.offerId + " (" + task + ").");
            } else {
                this.book.addNotice(playerId, "Czas na zlecenie #" + a.offerId + " (" + task.replace(" do twojej stolicy", "") + ") od "
                    + a.countryName + " minął. Reputacja: " + signed(ch.applied()) + ".");
            }
        }
        this.purgeStaleCargo();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            final Player p = Bukkit.getPlayer(id);
            if (p == null) {
                return;
            }
            for (final String notice : this.book.takeNotices(id)) {
                this.messages.get().send(p, "quest-notice", Messages.p("text", notice));
            }
            for (final QuestData.Active intent : this.book.active(id)) {
                if (SupplyAcceptance.pending(intent)) this.resumeAcceptance(p, intent, true);
            }
        }, 60L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisband(final CountryDisbandEvent event) {
        for (final QuestData.Active a : this.book.forgetCountry(event.countryId())) {
            if (SupplySettlement.protectedFromCancellation(a)) {
                this.plugin.getLogger().warning("Dostawa " + SupplySettlement.contractId(a) + " wymaga rozstrzygnięcia po upadku kraju; depozyt pozostaje zablokowany.");
                continue;
            }
            this.refund(a);
            this.book.cancel(a);
            this.supplySettlement.forget(a.uid);
            final Player p = Bukkit.getPlayer(UUID.fromString(a.player));
            if (p != null) {
                this.removeCargo(p, a.uid);
                this.messages.get().send(p, "quest-cancelled", Messages.p("id", a.offerId));
            }
        }
        this.reputation.forgetCountry(event.countryId());
    }

    // ------------------------------------------------------------------ items

    private boolean plainItem(final ItemStack it, final Material mat) {
        return it != null && it.getType() == mat && !this.services.skarbiec().isCoin(it) && this.cargoUid(it) == null;
    }

    private int countItems(final Player p, final Material mat) {
        int n = 0;
        for (final ItemStack it : p.getInventory().getStorageContents()) {
            if (this.plainItem(it, mat)) {
                n += it.getAmount();
            }
        }
        return n;
    }

    private void takeItems(final Player p, final Material mat, final int amount) {
        final PlayerInventory inv = p.getInventory();
        final ItemStack[] contents = inv.getStorageContents();
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            final ItemStack it = contents[i];
            if (!this.plainItem(it, mat)) {
                continue;
            }
            final int take = Math.min(left, it.getAmount());
            left -= take;
            if (take >= it.getAmount()) {
                contents[i] = null;
            } else {
                it.setAmount(it.getAmount() - take);
            }
        }
        inv.setStorageContents(contents);
    }

    private String cargoUid(final ItemStack it) {
        if (it == null || it.getType().isAir()) {
            return null;
        }
        return it.getPersistentDataContainer().get(this.cargoKey, PersistentDataType.STRING);
    }

    private void giveCargo(final Player p, final QuestData.Active a) {
        Material mat = Material.matchMaterial(a.material);
        if (mat == null || !mat.isItem() || mat.isAir()) {
            mat = Material.BARREL;
        }
        final Messages m = this.messages.get();
        final ItemStack it = new ItemStack(mat);
        it.editMeta(meta -> {
            meta.displayName(m.get("quest-cargo-name", Messages.p("goods", a.itemName)).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                m.get("quest-cargo-lore-target", Messages.p("target", a.targetName)).decoration(TextDecoration.ITALIC, false),
                m.get("quest-cargo-lore-id", Messages.p("id", a.offerId), Messages.p("country", a.countryName)).decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(this.cargoKey, PersistentDataType.STRING, a.uid);
        });
        for (final ItemStack rest : p.getInventory().addItem(it).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        m.send(p, "quest-cargo-given", Messages.p("cargo", a.itemName));
    }

    private int countCargo(final Player p, final String uid) {
        int n = 0;
        for (final ItemStack it : p.getInventory().getContents()) {
            if (uid.equals(this.cargoUid(it))) {
                n += it.getAmount();
            }
        }
        return n;
    }

    private void removeCargo(final Player p, final String uid) {
        final PlayerInventory inv = p.getInventory();
        final ItemStack[] contents = inv.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (uid.equals(this.cargoUid(contents[i]))) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) {
            inv.setContents(contents);
        }
    }

    /** Cargo of quests that ended (expired while offline, abandoned elsewhere) disappears from inventories. */
    private void purgeStaleCargo() {
        final Set<String> live = new HashSet<>();
        for (final QuestData.Active a : this.book.allActive()) {
            live.add(a.uid);
        }
        for (final Player p : Bukkit.getOnlinePlayers()) {
            final PlayerInventory inv = p.getInventory();
            final ItemStack[] contents = inv.getContents();
            boolean changed = false;
            for (int i = 0; i < contents.length; i++) {
                final String uid = this.cargoUid(contents[i]);
                if (uid != null && !live.contains(uid)) {
                    contents[i] = null;
                    changed = true;
                }
            }
            if (changed) {
                inv.setContents(contents);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (this.cargoUid(event.getItemInHand()) != null) {
            event.setCancelled(true);
            this.messages.get().send(event.getPlayer(), "quest-cargo-no-place");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBurn(final FurnaceBurnEvent event) {
        if (this.cargoUid(event.getFuel()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCraft(final PrepareItemCraftEvent event) {
        for (final ItemStack it : event.getInventory().getMatrix()) {
            if (this.cargoUid(it) != null) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ reputation view

    /** {@code /reputacja [kraj]} for {@code player} shown to {@code viewer}. */
    public void showReputation(final CommandSender viewer, final UUID player, final String playerName, final KrajInfo npc) {
        final Messages m = this.messages.get();
        final QuestConfig cfg = this.q();
        if (npc != null) {
            final ReputationBook.Entry e = this.reputation.peek(npc.id(), player);
            final RepTier t = RepTier.of(e.value);
            m.send(viewer, "rep-detail", Messages.c("country", country(npc)), Messages.p("player", playerName), Messages.c("tier", this.tierLabel(t)),
                Messages.p("value", e.value), Messages.p("next", t == RepTier.BOHATER ? "—" : RepTier.toNext(e.value) + " pkt"),
                Messages.p("completed", e.completed), Messages.p("failed", e.failed), Messages.p("abandoned", e.abandoned),
                Messages.p("reward_mult", fmt(cfg.rewardMultiplier(t))), Messages.p("gift_mult", fmt(cfg.giftMultiplier(t))),
                Messages.p("gained", e.gainDay.equals(this.book.today()) ? e.gainedToday : 0), Messages.p("cap", cfg.repDailyGainCap));
            m.send(viewer, "rep-tiers");
            return;
        }
        m.send(viewer, "rep-header", Messages.p("player", playerName));
        final Map<UUID, Integer> all = this.reputation.ofPlayer(player);
        final List<Map.Entry<UUID, Integer>> rows = new ArrayList<>(all.entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (final Map.Entry<UUID, Integer> e : rows) {
            final Optional<KrajInfo> k = this.services.kraje().getCountry(e.getKey());
            if (k.isEmpty()) {
                continue;
            }
            shown++;
            viewer.sendMessage(m.get("rep-line", Messages.c("country", country(k.get())), Messages.c("tier", this.tierLabel(RepTier.of(e.getValue()))),
                    Messages.p("value", e.getValue()))
                .hoverEvent(HoverEvent.showText(m.get("rep-line-hover")))
                .clickEvent(ClickEvent.runCommand("/reputacja " + k.get().name())));
        }
        if (shown == 0) {
            m.send(viewer, "rep-none");
        }
        m.send(viewer, "rep-tiers");
    }

    private static String fmt(final double v) {
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "").replace('.', ',');
    }

    // ------------------------------------------------------------------ the ruler (LLM)

    /** One line block for the ruler's frozen context; null when quests are disabled. */
    /** For the ruler's {@code <swiat>}: how this player's quests with the country are covered (null when none/disabled). */
    public String securitySummary(final UUID player, final UUID npc) {
        if (!this.enabled()) {
            return null;
        }
        int secured = 0;
        int other = 0;
        long held = 0;
        for (final QuestData.Active a : this.book.active(player)) {
            if (!a.country.equals(npc.toString())) {
                continue;
            }
            if (EscrowCoverage.secured(a, this.services.skarbiec())) {
                secured++;
                held += a.reward;
            } else {
                other++;
            }
        }
        if (secured == 0 && other == 0) {
            return "Nagrody za twoje zlecenia odkładasz w depozycie skarbca w chwili przyjęcia — bez pokrycia zlecenie nie powstaje.";
        }
        return "Zlecenia rozmówcy u ciebie: " + secured + " z nagrodą zabezpieczoną w depozycie (" + this.services.money(held) + ")"
            + (other > 0 ? ", " + other + " w trakcie przyjęcia lub bez pełnego pokrycia (nie obiecuj za nie pieniędzy)" : "") + ".";
    }

    public String context(final UUID player, final KrajInfo npc) {
        if (!this.enabled()) {
            return null;
        }
        final QuestConfig cfg = this.q();
        final RepTier tier = this.tier(npc.id(), player);
        final StringBuilder b = new StringBuilder();
        final List<QuestData.Active> mine = this.book.active(player).stream().filter(a -> a.country.equals(npc.id().toString())).toList();
        final long now = this.clock.millis();
        b.append("Jego zlecenia od ciebie w toku: ");
        if (mine.isEmpty()) {
            b.append("brak");
        } else {
            final List<String> parts = new ArrayList<>();
            for (final QuestData.Active a : mine) {
                if (SupplyAcceptance.pending(a)) {
                    parts.add("#" + a.offerId + " przyjęcie oczekuje na trwałe finansowanie; nie potwierdzaj jeszcze przyjęcia ani nagrody");
                    continue;
                }
                parts.add("#" + a.offerId + " " + TextSafety.forPrompt(taskText(a.type(), a.amount, a.itemName, a.targetName, a.distance), 160)
                    + " (zostało " + QuestRules.duration(a.deadline - now) + ")");
            }
            b.append(String.join("; ", parts));
        }
        b.append(".\n");
        final List<String> avail = new ArrayList<>();
        QuestRules.Denial firstDeny = null;
        for (final QuestData.Offer o : this.board(npc)) {
            final QuestRules.RewardQuote quote = this.quote(o, tier);
            final Optional<QuestRules.Denial> d = this.canAccept(this.facts(player, o, tier, quote).withoutTiming(), o, cfg);
            if (d.isEmpty()) {
                avail.add(o.type().key() + (o.premium ? " (zaszczytne)" : "") + ": " + TextSafety.forPrompt(taskText(o.type(), o.amount, o.itemName, o.targetName, o.distance), 160)
                    + ", nagroda " + this.services.money(quote.reward()));
            } else if (firstDeny == null && d.get() != QuestRules.Denial.USED && d.get() != QuestRules.Denial.ACTIVE_HERE) {
                firstDeny = d.get();
            }
        }
        b.append("Zlecenia z twojej tablicy, które możesz mu dziś dać: ");
        if (avail.isEmpty()) {
            b.append("żadne").append(firstDeny == null ? "" : " (" + firstDeny.forModel() + ")");
        } else {
            b.append(String.join("; ", avail));
        }
        b.append(".\n");
        final List<String> needs = this.needs(npc.id(), cfg).stream().map(k -> cfg.resources.get(k)).filter(r -> r != null).map(QuestConfig.Resource::name).toList();
        if (!needs.isEmpty()) {
            b.append("Czego twojemu krajowi brakuje: ").append(String.join(", ", needs)).append(".\n");
        }
        b.append("Nowe zlecenia surowcowe wymagają rzeczywistego odbioru przez magazyn Ludnosc w stolicy. Gracz przewozi zwykłe przedmioty; nie wystarczą jego słowa. Nagroda czeka na potwierdzenie dostawy i trwały zapis banku.\n");
        return b.toString();
    }

    /** Tool {@code zaproponuj_zlecenie}: picks the best offer of the kind and shows the player [Przyjmij] / [Odrzuć]. */
    public ToolOutcome offerTool(final Player p, final KrajInfo npc, final Component rulerLabel, final String kind) {
        if (!this.enabled()) {
            return ToolOutcome.error(QuestRules.Denial.DISABLED.forModel());
        }
        final QuestConfig cfg = this.q();
        final RepTier tier = this.tier(npc.id(), p.getUniqueId());
        final Optional<QuestType> wanted = QuestType.byKey(kind);
        QuestData.Offer best = null;
        QuestRules.RewardQuote bestQuote = null;
        QuestRules.Denial firstDeny = null;
        boolean anyOfKind = false;
        for (final QuestData.Offer o : this.board(npc)) {
            if (wanted.isPresent() && o.type() != wanted.get()) {
                continue;
            }
            anyOfKind = true;
            final QuestRules.RewardQuote quote = this.quote(o, tier);
            final Optional<QuestRules.Denial> d = this.canAccept(this.facts(p.getUniqueId(), o, tier, quote).withoutTiming(), o, cfg);
            if (d.isPresent()) {
                if (firstDeny == null || firstDeny == QuestRules.Denial.USED || firstDeny == QuestRules.Denial.ACTIVE_HERE) {
                    firstDeny = d.get();
                }
                continue;
            }
            if (best == null || (o.urgent && !best.urgent) || (o.urgent == best.urgent && quote.reward() > bestQuote.reward())) {
                best = o;
                bestQuote = quote;
            }
        }
        if (best == null) {
            if (!anyOfKind) {
                return ToolOutcome.error("Na dziś nie masz zleceń tego rodzaju." + (wanted.isPresent() ? " Spróbuj rodzaju „dowolne”." : ""));
            }
            return ToolOutcome.error(firstDeny == null ? "Nie masz dziś dla niego zlecenia." : firstDeny.forModel());
        }
        final Messages m = this.messages.get();
        p.sendMessage(m.get("quest-offer-card", Messages.c("ruler", rulerLabel), Messages.p("id", best.id), Messages.c("task", this.task(best)),
            Messages.p("reward", this.services.money(bestQuote.reward())), Messages.p("time", QuestRules.duration(best.timeLimitMinutes * 60_000L)),
            Messages.p("rep", best.reputation), Messages.c("buttons", this.acceptButtons(best.id))));
        return ToolOutcome.ok("Przedstawiono zlecenie #" + best.id + ": " + taskText(best.type(), best.amount, best.itemName, best.targetName, best.distance)
            + "; nagroda " + this.services.money(bestQuote.reward())
            + " (twój skarbiec odkłada ją w depozycie przy przyjęciu; wypłata przy oddaniu, zwrot przy porażce), czas "
            + QuestRules.duration(best.timeLimitMinutes * 60_000L) + ", reputacja +" + best.reputation
            + (best.type() == QuestType.SUROWCE ? ". Towar odbiera fizyczny magazyn Ludnosc w stolicy; pełny lub niedostępny magazyn wstrzymuje dostawę i wypłatę" : "")
            + ". Rozmówca widzi przyciski [Przyjmij] i [Odrzuć]; jego decyzję poznasz w zdarzeniach gry. Opisz zlecenie w roli tymi samymi liczbami.");
    }

    /** Tool {@code sprawdz_reputacje}. */
    public ToolOutcome reputationTool(final UUID player, final KrajInfo npc) {
        final ReputationBook.Entry e = this.reputation.peek(npc.id(), player);
        final RepTier t = RepTier.of(e.value);
        return ToolOutcome.ok("Reputacja rozmówcy u twojego kraju: " + e.value + "/100 (" + t.displayName() + "). Jak go traktujesz: " + t.tone()
            + ". Wykonane zlecenia: " + e.completed + ", nieudane: " + e.failed + ", porzucone: " + e.abandoned + ". W toku od ciebie: "
            + this.book.active(player).stream().filter(a -> a.country.equals(npc.id().toString())).count() + ".");
    }

    /** Tool {@code odbierz_zlecenie}: turns in this ruler's ready quests (and cargo addressed to his capital). */
    public ToolOutcome turnInTool(final Player p, final KrajInfo npc) {
        final String id = npc.id().toString();
        final List<QuestData.Active> mine = this.book.active(p.getUniqueId()).stream()
            .filter(a -> a.type() == QuestType.TRANSPORT ? id.equals(a.targetCountry) : id.equals(a.country)).toList();
        if (mine.isEmpty()) {
            return ToolOutcome.error("Rozmówca nie ma żadnego zlecenia do oddania u ciebie.");
        }
        final List<String> done = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        for (final QuestData.Active a : mine) {
            final TurnIn t = this.tryTurnIn(p, a);
            (t.done() ? done : problems).add(t.forModel());
            if (!t.done() && t.problem() != null) {
                p.sendMessage(t.problem());
            }
        }
        if (done.isEmpty()) {
            return ToolOutcome.error("Nic nie oddano: " + String.join(" ", problems));
        }
        return ToolOutcome.ok(String.join(" ", done) + (problems.isEmpty() ? "" : " Nie oddano: " + String.join(" ", problems)));
    }
}
