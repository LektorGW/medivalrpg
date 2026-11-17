// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import pl.medivalrpg.armia.api.NpcLogEntry;
import pl.medivalrpg.armia.api.NpcSettings;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.wladcy.config.WladcyConfig;
import pl.medivalrpg.wladcy.memory.MemoryStore;
import pl.medivalrpg.wladcy.memory.PairMemory;
import pl.medivalrpg.wladcy.prompt.RulerContext;
import pl.medivalrpg.wladcy.quest.QuestService;
import pl.medivalrpg.wladcy.quest.RepTier;
import pl.medivalrpg.wladcy.tools.ToolRules;

/** Gathers {@link RulerContext} from Kraje, Armia, Skarbiec, Ludnosc and the memory. Main thread. */
public final class ContextCollector {

    private final GameServices services;
    private final Supplier<WladcyConfig> config;
    private final MemoryStore memory;
    private final RulerState state;
    private final QuestService quests;

    public ContextCollector(final GameServices services, final Supplier<WladcyConfig> config, final MemoryStore memory, final RulerState state) {
        this(services, config, memory, state, null);
    }

    /** @param quests null = no reputation or quests in the context */
    public ContextCollector(final GameServices services, final Supplier<WladcyConfig> config, final MemoryStore memory, final RulerState state,
                            final QuestService quests) {
        this.services = services;
        this.config = config;
        this.memory = memory;
        this.state = state;
        this.quests = quests;
    }

    /** Per-player daily gift limit scaled by the player's reputation tier with the ruler's country. */
    long giftMaxPerPlayer(final UUID npc, final UUID player) {
        final long base = this.config.get().giftMaxPerPlayer;
        if (this.quests == null || !this.quests.enabled()) {
            return base;
        }
        return Math.round(base * this.quests.giftMultiplier(npc, player));
    }

    public RulerContext collect(final KrajInfo npc, final UUID player, final String playerName, final long now) {
        final WladcyConfig cfg = this.config.get();
        final Optional<NpcSettings> settings = this.services.armia().npcSettings(npc.id());
        final NpcSettings s = settings.orElse(null);
        final LudnoscApi lud = this.services.ludnosc();
        final Optional<KrajInfo> own = this.services.kraje().getCountryOf(player);
        final Rank rank = own.isPresent() ? this.services.kraje().getRank(player).orElse(Rank.CITIZEN) : null;
        final ToolRules.Interlocutor who = new ToolRules.Interlocutor(own.map(KrajInfo::id).orElse(null), rank,
            own.isPresent() && own.get().id().equals(npc.id()));
        final PairMemory mem = Optional.ofNullable(this.memory.peek(npc.id(), player)).orElseGet(PairMemory::new);

        final List<String> aiLog = new ArrayList<>();
        if (own.isPresent()) {
            final String name = own.get().name();
            for (final NpcLogEntry e : this.services.armia().npcLog(npc.id(), 40)) {
                if (e.text().contains(name)) {
                    aiLog.add(e.text());
                }
                if (aiLog.size() >= 3) {
                    break;
                }
            }
            java.util.Collections.reverse(aiLog);
        }

        final long allowance = ToolRules.giftAllowance(this.services.treasury(npc.id()), cfg.giftTreasuryPercent,
            this.state.giftedToday(npc.id()), this.state.giftedToday(npc.id(), player), this.giftMaxPerPlayer(npc.id(), player));
        final String warStatus = !cfg.allowWar ? "niedozwolona"
            : ToolRules.war(new ToolRules.WarCheck(true, who, own.isPresent() && npc.enemies().contains(own.get().id()),
                s == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(s.aggression()), cfg.warMinAggression,
                this.state.lastWarAt(npc.id()), now, cfg.warCooldownHours, "-"))
                .map(err -> "niemożliwa teraz (" + err.replaceAll("\\s*Wycofaj.*$", "") + ")")
                .orElse("możliwa w ostateczności");

        final boolean rep = this.quests != null && this.quests.enabled();
        final int repValue = rep ? this.quests.value(npc.id(), player) : 0;
        final RepTier tier = RepTier.of(repValue);
        return new RulerContext(
            npc.rulerName() == null || npc.rulerName().isBlank() ? "Władca" : npc.rulerName(),
            npc.name(),
            s != null,
            s == null ? 50 : s.aggression(),
            s == null ? 50 : s.expansion(),
            s == null ? 50 : s.diplomacy(),
            s == null ? 50 : s.wealth(),
            honor(s),
            npc.chunkCount(),
            this.services.armia().soldierCount(npc.id()),
            army(this.services.armia().soldiersByType(npc.id())),
            this.services.approx(this.services.treasury(npc.id())),
            lud == null ? 0 : lud.population(npc.id()),
            lud == null ? 0 : lud.housing(npc.id()),
            this.names(npc.allies()),
            this.names(npc.enemies()),
            playerName,
            own.map(KrajInfo::name).orElse(null),
            rank == null ? null : rank.displayName(),
            rank == Rank.RULER,
            ToolRules.negotiator(who, cfg.diplomacyRank).isEmpty(),
            own.map(o -> this.services.kraje().getRelation(npc.id(), o.id()).displayName()).orElse(null),
            own.map(KrajInfo::chunkCount).orElse(0),
            own.map(o -> this.services.armia().soldierCount(o.id())).orElse(0),
            mem.sympathy,
            mem.summary(this.services::money),
            List.copyOf(mem.notes),
            aiLog,
            List.copyOf(mem.exchanges),
            this.services.money(allowance),
            this.services.money(cfg.tributeMax),
            warStatus,
            repValue,
            rep ? tier.displayName() : null,
            rep ? tier.tone() : null,
            rep ? this.quests.context(player, npc) : null,
            this.realm(npc, own.orElse(null), player));
    }

    /** Wave 13–17 facts for the {@code <swiat>} block; every source is optional (older plugins → defaults). */
    pl.medivalrpg.wladcy.prompt.RealmFacts realm(final KrajInfo npc, final KrajInfo own, final UUID player) {
        final var kraje = this.services.kraje();
        int era = 1;
        String eraName = null;
        int playerEra = 0;
        try {
            final var postep = org.bukkit.Bukkit.getServicesManager().load(pl.medivalrpg.postep.api.PostepApi.class);
            if (postep != null) {
                era = Math.min(2, postep.era(npc.id()));
                eraName = postep.eraInfo(era).name();
                playerEra = own == null ? 0 : Math.min(2, postep.era(own.id()));
            }
        } catch (final RuntimeException | LinkageError absent) {
            // without Postep every country is in era 1
        }
        String quality = null;
        String unionPartner = null;
        String unionMonarch = null;
        boolean unionWithPlayer = false;
        boolean theirEmbassy = false;
        boolean ourEmbassy = false;
        int hosted = 0;
        int abroad = 0;
        try {
            quality = own == null ? null : kraje.getRelationQuality(npc.id(), own.id()).name();
            final var union = kraje.getUnion(npc.id());
            if (union.isPresent()) {
                final UUID other = union.get().other(npc.id());
                unionPartner = kraje.getCountry(other).map(KrajInfo::name).orElse(null);
                unionMonarch = union.get().monarchName();
                unionWithPlayer = own != null && own.id().equals(other);
            }
            theirEmbassy = own != null && kraje.hasEmbassy(own.id(), npc.id());
            ourEmbassy = own != null && kraje.hasEmbassy(npc.id(), own.id());
            for (final var e : kraje.getEmbassies(npc.id())) {
                if (e.host().equals(npc.id())) {
                    hosted++;
                } else {
                    abroad++;
                }
            }
        } catch (final RuntimeException | LinkageError older) {
            // Kraje without unions/embassies (API < 1.5)
        }
        final int ours = npc.capitalWorld() == null ? 0 : pl.medivalrpg.wladcy.prompt.RealmFacts.outposts(kraje.getClaims(npc.id(), npc.capitalWorld()));
        final int theirs = own == null || own.capitalWorld() == null ? 0
            : pl.medivalrpg.wladcy.prompt.RealmFacts.outposts(kraje.getClaims(own.id(), own.capitalWorld()));
        final List<String> naval = new ArrayList<>();
        try {
            for (final NpcLogEntry e : this.services.armia().npcLog(npc.id(), 60)) {
                final String t = e.text();
                if (t.contains("DESANT") || t.contains("Desant:") || t.contains("ALARM: wrogi statek") || t.contains("przyczół")) {
                    naval.add(t);
                }
                if (naval.size() >= 3) {
                    break;
                }
            }
        } catch (final RuntimeException | LinkageError ignored) {
            // older Armia
        }
        final List<String> sieges = new ArrayList<>();
        for (final var siege : kraje.getSieges()) {
            if (siege.attacker().equals(npc.id()) || siege.defender().equals(npc.id())) {
                final boolean attacking = siege.attacker().equals(npc.id());
                final String other = kraje.getCountry(attacking ? siege.defender() : siege.attacker()).map(KrajInfo::name).orElse("?");
                sieges.add((attacking ? "Oblegasz ziemię kraju " : "Kraj ") + other + (attacking ? "" : " oblega twoją ziemię")
                    + " przy " + ((siege.cx() << 4) + 8) + ", " + ((siege.cz() << 4) + 8) + " (postęp " + Math.round(siege.progress() * 100) + "%)");
            }
        }
        return new pl.medivalrpg.wladcy.prompt.RealmFacts(era, eraName, playerEra, quality, unionPartner, unionMonarch, unionWithPlayer,
            theirEmbassy, ourEmbassy, hosted, abroad, ours, theirs, naval, sieges,
            this.quests == null ? null : this.quests.securitySummary(player, npc.id()));
    }

    /**
     * Honor from Armia (API 1.3); for an older Armia build without the field, or without AI settings, derived from the
     * personality with the same formula.
     */
    static int honor(final NpcSettings s) {
        if (s == null) {
            return derive(50, 50, 50);
        }
        try {
            return s.honor();
        } catch (final NoSuchMethodError | NoClassDefFoundError old) {
            return derive(s.aggression(), s.diplomacy(), s.wealth());
        }
    }

    /** Same formula as {@code NpcSettings.deriveHonor}, kept here for Armia builds older than API 1.3. */
    static int derive(final int aggression, final int diplomacy, final int wealth) {
        final double h = 50 + (diplomacy - 50) * 0.5 - (aggression - 50) * 0.4 - Math.max(0, wealth - 60) * 0.3;
        return (int) Math.max(0, Math.min(100, Math.round(h)));
    }

    List<String> names(final java.util.Collection<UUID> ids) {
        final List<String> out = new ArrayList<>();
        for (final UUID id : ids) {
            this.services.kraje().getCountry(id).ifPresent(k -> out.add(k.name()));
        }
        out.sort(String::compareTo);
        return out;
    }

    static String army(final Map<String, Integer> byType) {
        if (byType == null || byType.isEmpty()) {
            return "brak";
        }
        final List<String> parts = new ArrayList<>();
        byType.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(e -> parts.add(e.getKey() + " " + e.getValue()));
        return String.join(", ", parts);
    }

    /** Relation word for tool results. */
    static String relation(final Relation r) {
        return r.displayName();
    }
}
