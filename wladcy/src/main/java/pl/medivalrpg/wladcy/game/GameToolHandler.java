// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;
import pl.medivalrpg.armia.api.NpcSettings;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.wladcy.config.WladcyConfig;
import pl.medivalrpg.wladcy.memory.MemoryStore;
import pl.medivalrpg.wladcy.memory.PairMemory;
import pl.medivalrpg.wladcy.text.TextSafety;
import pl.medivalrpg.wladcy.tools.ToolArgs;
import pl.medivalrpg.wladcy.tools.ToolCall;
import pl.medivalrpg.wladcy.tools.ToolHandler;
import pl.medivalrpg.wladcy.tools.ToolOutcome;
import pl.medivalrpg.wladcy.tools.ToolRules;
import pl.medivalrpg.wladcy.tools.ToolSpec;
import pl.medivalrpg.wladcy.tools.ToolSpecs;

/**
 * Executes the ruler's tools for one conversation on the main thread. Every call is validated again from current
 * game state (the model's view may be stale); nothing ever moves the player's money without the player's click.
 */
final class GameToolHandler implements ToolHandler {

    private final Conversation conv;
    private final ConversationManager manager;
    private final GameServices services;
    private final Supplier<WladcyConfig> config;
    private final MemoryStore memory;
    private final RulerState state;
    private final TributeOffers offers;
    private final JsonStore store;
    private final Clock clock;

    GameToolHandler(final Conversation conv, final ConversationManager manager, final GameServices services, final Supplier<WladcyConfig> config,
                    final MemoryStore memory, final RulerState state, final TributeOffers offers, final JsonStore store, final Clock clock) {
        this.conv = conv;
        this.manager = manager;
        this.services = services;
        this.config = config;
        this.memory = memory;
        this.state = state;
        this.offers = offers;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public ToolOutcome execute(final ToolCall call) {
        // a turn still running when the audience ended (player left, /wladca przeladuj, another ruler) must not act:
        // no gift, tribute or war after the conversation is over
        if (!this.manager.current(this.conv)) {
            return ToolOutcome.error("Rozmówca odszedł, audiencja jest zakończona. Niczego nie wykonuj.");
        }
        final WladcyConfig cfg = this.config.get();
        final List<ToolSpec> specs = ToolSpecs.all(cfg.allowWar, cfg.quests.enabled);
        final Optional<ToolSpec> spec = ToolSpecs.find(specs, call.name());
        if (spec.isEmpty()) {
            return ToolOutcome.error("Nie ma takiego działania.");
        }
        final ToolArgs.Parsed a;
        try {
            a = ToolArgs.parse(spec.get(), call.input());
        } catch (final ToolArgs.InvalidArgs e) {
            return ToolOutcome.error(e.getMessage());
        }
        final Optional<KrajInfo> npcOpt = this.services.kraje().getCountry(this.conv.npc);
        if (npcOpt.isEmpty()) {
            return ToolOutcome.error("Twój kraj już nie istnieje.");
        }
        final KrajInfo npc = npcOpt.get();
        final Optional<KrajInfo> own = this.services.kraje().getCountryOf(this.conv.player);
        final Rank rank = own.isPresent() ? this.services.kraje().getRank(this.conv.player).orElse(Rank.CITIZEN) : null;
        final ToolRules.Interlocutor who = new ToolRules.Interlocutor(own.map(KrajInfo::id).orElse(null), rank,
            own.isPresent() && own.get().id().equals(npc.id()));
        return switch (call.name()) {
            case ToolSpecs.CHECK_COUNTRY -> this.checkCountry(npc, own);
            case ToolSpecs.PROPOSE_ALLIANCE -> this.proposeAlliance(cfg, npc, own, who);
            case ToolSpecs.ACCEPT_ALLIANCE -> this.answer(cfg, npc, own, who, Relation.ALLIANCE, true);
            case ToolSpecs.ACCEPT_PEACE -> this.answer(cfg, npc, own, who, Relation.NEUTRAL, true);
            case ToolSpecs.REFUSE -> this.answer(cfg, npc, own, who, "sojusz".equals(a.string("rodzaj")) ? Relation.ALLIANCE : Relation.NEUTRAL, false);
            case ToolSpecs.PROPOSE_PEACE -> this.proposePeace(cfg, npc, own, who);
            case ToolSpecs.DEMAND_TRIBUTE -> this.demandTribute(cfg, npc, own, who, a.integer("kwota"));
            case ToolSpecs.GIFT -> this.gift(cfg, npc, own, who, a.integer("kwota"), "kraj".equals(a.string("odbiorca")));
            case ToolSpecs.END -> {
                this.conv.session.requestEnd();
                yield ToolOutcome.ok("Audiencja zakończy się po twojej odpowiedzi. Pożegnaj rozmówcę jednym zdaniem.");
            }
            case ToolSpecs.REMEMBER -> this.remember(cfg, a.string("notatka"), (int) a.integer("zmiana_nastawienia"));
            case ToolSpecs.DECLARE_WAR -> this.declareWar(cfg, npc, own, who, a.string("powod"));
            case ToolSpecs.OFFER_QUEST -> this.withPlayer(p -> this.manager.quests().offerTool(p, npc, this.conv.rulerLabel, a.string("rodzaj")));
            case ToolSpecs.CHECK_REPUTATION -> this.manager.quests().reputationTool(this.conv.player, npc);
            case ToolSpecs.TURN_IN_QUEST -> this.withPlayer(p -> this.manager.quests().turnInTool(p, npc));
            default -> ToolOutcome.error("Nie ma takiego działania.");
        };
    }

    private ToolOutcome withPlayer(final java.util.function.Function<org.bukkit.entity.Player, ToolOutcome> action) {
        final org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(this.conv.player);
        if (p == null) {
            return ToolOutcome.error("Rozmówca odszedł.");
        }
        return action.apply(p);
    }

    private PairMemory mem() {
        final PairMemory m = this.memory.get(this.conv.npc, this.conv.player);
        m.playerName = this.conv.playerName;
        return m;
    }

    private ToolOutcome checkCountry(final KrajInfo npc, final Optional<KrajInfo> own) {
        if (own.isEmpty()) {
            return ToolOutcome.ok("Rozmówca nie należy do żadnego kraju.");
        }
        final KrajInfo o = own.get();
        final var lud = this.services.ludnosc();
        final StringBuilder b = new StringBuilder();
        b.append("Kraj „").append(TextSafety.forPrompt(o.name(), 40)).append("”");
        if (o.npc()) {
            b.append(" (kraj NPC)");
        }
        b.append(". Władca: ").append(TextSafety.forPrompt(o.rulerName() == null ? "?" : o.rulerName(), 32)).append(". ");
        b.append("Ziemie: ").append(o.chunkCount()).append(" chunków. Członkowie-gracze: ").append(o.memberCount()).append(". ");
        b.append("Żołnierze NPC: ").append(this.services.armia().soldierCount(o.id())).append(" (")
            .append(ContextCollector.army(this.services.armia().soldiersByType(o.id()))).append("). ");
        if (lud != null) {
            b.append("Ludność: ").append(lud.population(o.id())).append(". ");
        }
        b.append("Skarbiec: ").append(this.services.approx(this.services.treasury(o.id()))).append(". ");
        b.append("Sojusznicy: ").append(join(this.manager.collector().names(o.allies()))).append(". ");
        b.append("Wojny z: ").append(join(this.manager.collector().names(o.enemies()))).append(". ");
        b.append("Relacja z twoim krajem: ").append(this.services.kraje().getRelation(npc.id(), o.id()).displayName()).append('.');
        return ToolOutcome.ok(b.toString());
    }

    private static String join(final List<String> names) {
        if (names.isEmpty()) {
            return "brak";
        }
        return String.join(", ", names.stream().map(n -> TextSafety.forPrompt(n, 40)).toList());
    }

    private ToolOutcome proposeAlliance(final WladcyConfig cfg, final KrajInfo npc, final Optional<KrajInfo> own, final ToolRules.Interlocutor who) {
        final Optional<String> err = ToolRules.negotiator(who, cfg.diplomacyRank);
        if (err.isPresent()) {
            return ToolOutcome.error(err.get());
        }
        final Relation rel = this.services.kraje().getRelation(npc.id(), own.get().id());
        if (rel != Relation.NEUTRAL) {
            return ToolOutcome.error(rel == Relation.ALLIANCE ? "Wasze kraje już są sojusznikami." : "W czasie wojny nie zawiera się sojuszu — najpierw pokój.");
        }
        final KrajeResult r = this.services.kraje().propose(npc.id(), own.get().id(), Relation.ALLIANCE);
        if (!r.ok()) {
            return ToolOutcome.error("Gra odrzuciła propozycję: " + reason(r));
        }
        this.store.journal(npc.name() + ": propozycja sojuszu dla " + own.get().name() + " (rozmowa z " + this.conv.playerName + ")");
        if (this.services.kraje().getRelation(npc.id(), own.get().id()) == Relation.ALLIANCE) {
            this.mem().alliancesFormed++;
            return ToolOutcome.ok("Sojusz zawarty od razu (kraj rozmówcy proponował go wcześniej). Ogłoszono to w królestwie.");
        }
        return ToolOutcome.ok("Propozycja sojuszu wysłana. Przywódcy kraju rozmówcy dostali ją do kliknięcia; decyzja należy do nich.");
    }

    private ToolOutcome proposePeace(final WladcyConfig cfg, final KrajInfo npc, final Optional<KrajInfo> own, final ToolRules.Interlocutor who) {
        final Optional<String> err = ToolRules.negotiator(who, cfg.diplomacyRank);
        if (err.isPresent()) {
            return ToolOutcome.error(err.get());
        }
        if (this.services.kraje().getRelation(npc.id(), own.get().id()) != Relation.WAR) {
            return ToolOutcome.error("Wasze kraje nie są w stanie wojny.");
        }
        final KrajeResult r = this.services.kraje().propose(npc.id(), own.get().id(), Relation.NEUTRAL);
        if (!r.ok()) {
            return ToolOutcome.error("Gra odrzuciła propozycję pokoju: " + reason(r));
        }
        this.store.journal(npc.name() + ": propozycja pokoju dla " + own.get().name() + " (rozmowa z " + this.conv.playerName + ")");
        if (this.services.kraje().getRelation(npc.id(), own.get().id()) == Relation.NEUTRAL) {
            this.mem().peacesMade++;
            return ToolOutcome.ok("Pokój zawarty od razu (kraj rozmówcy proponował go wcześniej). Ogłoszono to w królestwie.");
        }
        return ToolOutcome.ok("Propozycja pokoju wysłana. Przywódcy kraju rozmówcy dostali ją do kliknięcia.");
    }

    private ToolOutcome answer(final WladcyConfig cfg, final KrajInfo npc, final Optional<KrajInfo> own, final ToolRules.Interlocutor who,
                               final Relation proposed, final boolean accept) {
        final Optional<String> err = ToolRules.negotiator(who, cfg.diplomacyRank);
        if (err.isPresent()) {
            return ToolOutcome.error(err.get());
        }
        final String what = proposed == Relation.ALLIANCE ? "sojuszu" : "pokoju";
        final KrajeResult r = this.services.kraje().respondToProposal(npc.id(), own.get().id(), proposed, accept);
        if (r.status() == KrajeResult.Status.NO_PROPOSAL) {
            return ToolOutcome.error("Kraj rozmówcy nie złożył ważnej propozycji " + what + "."
                + (accept ? " Możesz sam ją zaproponować (" + (proposed == Relation.ALLIANCE ? ToolSpecs.PROPOSE_ALLIANCE : ToolSpecs.PROPOSE_PEACE) + ")." : ""));
        }
        if (!r.ok()) {
            return ToolOutcome.error("Gra nie pozwala: " + reason(r));
        }
        this.store.journal(npc.name() + ": " + (accept ? "przyjęto" : "odrzucono") + " propozycję " + what + " od " + own.get().name()
            + " (rozmowa z " + this.conv.playerName + ")");
        if (accept) {
            if (proposed == Relation.ALLIANCE) {
                this.mem().alliancesFormed++;
            } else {
                this.mem().peacesMade++;
            }
            return ToolOutcome.ok((proposed == Relation.ALLIANCE ? "Sojusz zawarty" : "Pokój zawarty") + " i ogłoszony w królestwie.");
        }
        return ToolOutcome.ok("Propozycja " + what + " odrzucona; kraj rozmówcy został powiadomiony.");
    }

    private ToolOutcome demandTribute(final WladcyConfig cfg, final KrajInfo npc, final Optional<KrajInfo> own,
                                      final ToolRules.Interlocutor who, final long amount) {
        final long now = this.clock.millis();
        final Optional<String> err = ToolRules.tribute(who, cfg.diplomacyRank, amount, cfg.tributeMax, this.offers.hasPending(this.conv.player, now));
        if (err.isPresent()) {
            return ToolOutcome.error(err.get());
        }
        final TributeOffers.Offer o = this.offers.create(this.conv.player, npc.id(), own.get().id(), amount, now + cfg.tributeExpireMinutes * 60_000L);
        this.manager.showTributeDemand(this.conv, o);
        this.store.journal(npc.name() + ": żądanie trybutu " + this.services.money(amount) + " od " + own.get().name() + " (" + this.conv.playerName + ")");
        final boolean canPay = this.services.treasury(own.get().id()) >= amount;
        return ToolOutcome.ok("Żądanie trybutu " + this.services.money(amount) + " przedstawione. Rozmówca widzi przyciski [Zapłać] i [Odmów] "
            + "(ważne " + cfg.tributeExpireMinutes + " min); jego decyzję poznasz w zdarzeniach gry."
            + (canPay ? "" : " Uwaga: skarbiec jego kraju ma obecnie mniej, niż żądasz."));
    }

    private ToolOutcome gift(final WladcyConfig cfg, final KrajInfo npc, final Optional<KrajInfo> own, final ToolRules.Interlocutor who,
                             final long amount, final boolean toCountry) {
        final long treasury = this.services.treasury(npc.id());
        final long allowance = ToolRules.giftAllowance(treasury, cfg.giftTreasuryPercent, this.state.giftedToday(npc.id()),
            this.state.giftedToday(npc.id(), this.conv.player), this.manager.collector().giftMaxPerPlayer(npc.id(), this.conv.player));
        final Optional<String> err = ToolRules.gift(amount, allowance, toCountry, who);
        if (err.isPresent()) {
            return ToolOutcome.error(err.get().replace(allowance + " md", this.services.money(allowance)));
        }
        final String target = toCountry ? SkarbiecApi.countryAccount(own.get().id()) : SkarbiecApi.playerAccount(this.conv.player);
        final TxResult tx = this.services.skarbiec().transfer(SkarbiecApi.countryAccount(npc.id()), target, amount,
            "Dar władcy " + npc.name() + " dla " + (toCountry ? own.get().name() : this.conv.playerName));
        if (!tx.ok()) {
            return ToolOutcome.error(tx == TxResult.INSUFFICIENT_FUNDS ? "W skarbcu brakuje pieniędzy." : "Przelew się nie udał (" + tx + ").");
        }
        this.state.gift(npc.id(), this.conv.player, amount);
        final PairMemory m = this.mem();
        m.giftsReceived++;
        m.giftTotal += amount;
        this.manager.showGift(this.conv, amount, toCountry ? own.get().name() : null);
        this.store.journal(npc.name() + ": dar " + this.services.money(amount) + " dla " + (toCountry ? "kraju " + own.get().name() : this.conv.playerName));
        return ToolOutcome.ok("Przekazano " + this.services.money(amount) + (toCountry ? " do skarbca kraju rozmówcy." : " na konto rozmówcy."));
    }

    private ToolOutcome remember(final WladcyConfig cfg, final String note, final int delta) {
        final int d = Math.max(-20, Math.min(20, delta));
        final PairMemory m = this.mem();
        m.addNote(TextSafety.clean(note, 120), cfg.memoryNotes);
        m.addSympathy(d);
        return ToolOutcome.ok("Zapamiętano. Twoje nastawienie: " + m.sympathy + " (" + PairMemory.attitudeWord(m.sympathy) + ").");
    }

    private ToolOutcome declareWar(final WladcyConfig cfg, final KrajInfo npc, final Optional<KrajInfo> own, final ToolRules.Interlocutor who,
                                   final String reason) {
        final Optional<NpcSettings> s = this.services.armia().npcSettings(npc.id());
        final boolean atWar = own.isPresent() && this.services.kraje().getRelation(npc.id(), own.get().id()) == Relation.WAR;
        final Optional<String> err = ToolRules.war(new ToolRules.WarCheck(cfg.allowWar, who, atWar,
            s.map(x -> OptionalInt.of(x.aggression())).orElse(OptionalInt.empty()), cfg.warMinAggression,
            this.state.lastWarAt(npc.id()), this.clock.millis(), cfg.warCooldownHours, reason));
        if (err.isPresent()) {
            return ToolOutcome.error(err.get());
        }
        final KrajInfo target = own.get();
        // the admin's NEVER_WAR stance binds the ruler too (KrajeApi#declareWar alone does not know Armia's stances)
        final Optional<String> stance = ToolRules.warStance(this.services.armia().stance(npc.id(), target.id()));
        if (stance.isPresent()) {
            return ToolOutcome.error(stance.get());
        }
        final KrajeResult r = this.services.kraje().declareWar(npc.id(), target.id());
        if (!r.ok()) {
            return ToolOutcome.error("Gra nie pozwala wypowiedzieć wojny: " + reason(r) + " Wycofaj się w roli.");
        }
        final String clean = TextSafety.clean(reason, 160);
        this.state.warDeclared(npc.id());
        final PairMemory m = this.mem();
        m.warsDeclared++;
        m.addSympathy(-20);
        m.addNote("Wypowiedziałem wojnę jego krajowi: " + clean, cfg.memoryNotes);
        this.services.armia().logNpcDecision(npc.id(), "Wojna z " + target.name() + " wypowiedziana podczas audiencji gracza "
            + this.conv.playerName + ". Powód: " + clean);
        this.store.journal(npc.name() + ": WOJNA z " + target.name() + " (rozmowa z " + this.conv.playerName + "), powód: " + clean);
        this.manager.announceWar(this.conv, npc, target, clean);
        return ToolOutcome.ok("Wojna wypowiedziana i ogłoszona całemu królestwu. Koszt pokrył twój skarbiec.");
    }

    private static String reason(final KrajeResult r) {
        return r.message() == null || r.message().isBlank() ? r.status().name() : TextSafety.clean(r.message(), 200);
    }

    UUID player() {
        return this.conv.player;
    }
}
