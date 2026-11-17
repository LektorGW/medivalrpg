// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import pl.medivalrpg.kraje.api.KrajeResult;

/**
 * NPC armies and NPC-country AI, provided by the Armia plugin (ServicesManager). The original read methods are
 * thread-safe snapshots.
 *
 * <p>The methods below the "web panel" line (since API 1.1) have {@code default} implementations reporting
 * "unsupported" for older Armia builds. Mutations must be called on the main thread and do not check who may call
 * them (the caller, e.g. the Panel plugin, does). They return a {@link KrajeResult}; on success
 * {@link KrajeResult#message()} may hold a short Polish summary (e.g. how many soldiers were recruited).</p>
 */
public interface ArmiaApi {

    /** Total soldiers of a country. */
    int soldierCount(UUID countryId);

    /** Soldiers per unit type id (e.g. {@code "piechota" → 12}). */
    Map<String, Integer> soldiersByType(UUID countryId);

    /** Country of a soldier entity (any of its parts). */
    Optional<UUID> soldierCountry(Entity entity);

    /** True when the NPC AI is currently active for this NPC country. */
    boolean isAiActive(UUID countryId);

    // ---- web panel extensions ---------------------------------------------------------------------

    /**
     * Recruits {@code count} soldiers of {@code unitType} paid from the country treasury, with the same limits,
     * costs and citizen requirement as {@code /armia rekrutuj}. Soldiers appear at the barracks (Ludnosc building
     * {@code koszary}) or, without barracks, at the capital, guarding there. {@code byPlayer} is recorded as the
     * recruiter (null = admin/API). Fails if barracks are required by the config and the country has none.
     */
    default KrajeResult recruit(final UUID countryId, final String unitType, final int count, final UUID byPlayer) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Disbands up to {@code count} soldiers of {@code unitType}; loaded ones go back to the population. */
    default KrajeResult disband(final UUID countryId, final String unitType, final int count) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Configured unit types in config order. */
    default List<UnitTypeInfo> unitTypes() {
        return List.of();
    }

    /** Average morale 0–100 of the country's soldiers; empty when it has none or unsupported. */
    default OptionalInt morale(final UUID countryId) {
        return OptionalInt.empty();
    }

    /** Daily wages of all soldiers of the country in copper. */
    default long dailyWages(final UUID countryId) {
        return 0L;
    }

    /** AI settings of an NPC country; empty for player countries or when unsupported. */
    default Optional<NpcSettings> npcSettings(final UUID countryId) {
        return Optional.empty();
    }

    /** {@code /nk ustaw}: {@code key} from {@link NpcSettings#PARAMS}, value as typed ({@code 0–100}, {@code tak/nie}). */
    default KrajeResult setNpcSetting(final UUID countryId, final String key, final String value) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /nk pauza|wznow <kraj>}. */
    default KrajeResult setNpcPaused(final UUID countryId, final boolean paused) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /nk tick}: runs one AI decision tick now. */
    default KrajeResult npcTick(final UUID countryId) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Last AI decisions, newest first (at most {@code limit}). */
    default List<NpcLogEntry> npcLog(final UUID countryId, final int limit) {
        return List.of();
    }

    /** Admin: adds soldiers for free at the capital (country limits are ignored, the global safety cap is not). */
    default KrajeResult addSoldiers(final UUID countryId, final String unitType, final int count) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /**
     * {@code /nk wladca}: (re)places the NPC ruler with a name and an optional Mojang skin name — where the ruler
     * stood before, or at the capital.
     */
    default KrajeResult placeRuler(final UUID countryId, final String name, final String skin) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /**
     * {@code /nk stworz}: creates an NPC country through Kraje at {@code capital}, sets up its AI state and places the
     * ruler there. {@code rulerName} null/blank = random name from the config. {@link KrajeResult#countryId()} = new id.
     */
    default KrajeResult createNpcCountry(final String name, final int rgb, final Location capital, final String rulerName) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    // ---- NPC ruler conversations (since API 1.2, used by the Wladcy plugin) ------------------------

    /** Country of an NPC ruler entity (the Mannequin standing in the capital); empty for any other entity. */
    default Optional<UUID> rulerCountry(final Entity entity) {
        return Optional.empty();
    }

    /**
     * Main thread. Appends a Polish line to the NPC country's AI decision log (shown by {@code /nk log} and
     * {@link #npcLog}), e.g. a war declared by the ruler in a conversation. No-op for unknown countries / older Armia.
     */
    default void logNpcDecision(final UUID countryId, final String text) {
    }

    // ---- NPC-country management v2 (since API 1.3, web panel "Państwo NPC" and Wladcy) ---------------
    //
    // {@code npc} is always an NPC country (otherwise reads return the neutral default and mutations fail with
    // Status.INVALID). Reads are snapshots of the AI state (main thread recommended); mutations must be called on
    // the main thread, persist in plugins/Armia/npc.json, are written to the AI decision log and do not check who
    // calls them. Older Armia builds report "unsupported" / neutral values.

    /** Opinion of the NPC country about {@code other}, -100..100 (0 = neutral / unknown). */
    default int opinion(final UUID npc, final UUID other) {
        return 0;
    }

    /** Sets the opinion about {@code other} (clamped to -100..100). */
    default KrajeResult setOpinion(final UUID npc, final UUID other, final int value) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /**
     * Changes the opinion about {@code other} by {@code delta} (result clamped to -100..100), e.g. +10 for a tribute
     * paid in a conversation with the ruler (Wladcy) or -20 for an insult. {@link KrajeResult#message()} on success:
     * the new value as text.
     */
    default KrajeResult adjustOpinion(final UUID npc, final UUID other, final int delta) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Attitude set by an admin towards {@code other} ({@link NpcStance#AUTO} when none). */
    default NpcStance stance(final UUID npc, final UUID other) {
        return NpcStance.AUTO;
    }

    /** {@code stance}: an {@link NpcStance} name or Polish code ({@code auto, przyjazne, wrogie, nigdy_wojny, cel_ataku}). */
    default KrajeResult setStance(final UUID npc, final UUID other, final String stance) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Budget split in percent; empty when unsupported or not an NPC country. */
    default Optional<NpcBudget> budgetShares(final UUID npc) {
        return Optional.empty();
    }

    /** Sets the budget split; each value 0..100 and the sum must be exactly 100 ({@link KrajeResult.Status#INVALID}). */
    default KrajeResult setBudgetShares(final UUID npc, final int army, final int buildings, final int reserve) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /**
     * Research share of the budget in percent (Postep invests it from the daily income); -1 = automatic (Postep decides
     * from the personality), also when unsupported.
     */
    default int researchShare(final UUID npc) {
        return -1;
    }

    /**
     * Budget split with research: each 0..100, sum exactly 100; {@code research} -1 = automatic (then army + buildings +
     * reserve must sum to 100). Without Postep the research share is stored but has no effect.
     */
    default KrajeResult setBudgetShares(final UUID npc, final int army, final int buildings, final int research, final int reserve) {
        return research < 0 ? this.setBudgetShares(npc, army, buildings, reserve)
            : KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Military posture; empty when unsupported or not an NPC country. */
    default Optional<NpcPosture> posture(final UUID npc) {
        return Optional.empty();
    }

    /** {@code posture}: an {@link NpcPosture} name or Polish code ({@code obronna, zrownowazona, ofensywna}). */
    default KrajeResult setPosture(final UUID npc, final String posture) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Siege target chosen by an admin (empty = automatic). */
    default Optional<UUID> siegeTarget(final UUID npc) {
        return Optional.empty();
    }

    /**
     * Siege target: any other country (the AI besieges it while at war with it, before every other enemy);
     * null = automatic.
     */
    default KrajeResult setSiegeTarget(final UUID npc, final UUID target) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** The country the AI attacks / would attack when the siege target is automatic (enemy, or best war candidate). */
    default Optional<UUID> autoSiegeTarget(final UUID npc) {
        return Optional.empty();
    }

    /** Short Polish description of the current AI goal, e.g. "Odkłada na rozbudowę osady"; empty when unknown. */
    default String aiGoal(final UUID npc) {
        return "";
    }

    /** The current AI goal with kind, target and progress (see {@link NpcGoal}). */
    default Optional<NpcGoal> aiGoalDetails(final UUID npc) {
        return Optional.empty();
    }

    /** Income and expenses of the last 24 hours, measured from real flows (see {@link NpcEconomy}). */
    default Optional<NpcEconomy> economyBreakdown(final UUID npc) {
        return Optional.empty();
    }

    /** Largest daily tribute {@link #demandTribute} accepts, copper. */
    default long tributeMax() {
        return 0L;
    }

    /**
     * The NPC country demands a daily tribute of {@code amount} copper from {@code target} for some days (config).
     * An NPC target decides at once from opinion and strength ({@link NpcReply#accepted()} true/false); the ruler of a
     * player country gets a clickable prompt in game ({@code accepted} null). Refusal lowers the opinion (casus belli).
     */
    default NpcReply demandTribute(final UUID npc, final UUID target, final long amount) {
        return NpcReply.fail("nieobsługiwane");
    }

    /**
     * Admin: the NPC country proposes {@code relation} ({@code ALLIANCE} or {@code NEUTRAL} = peace) to {@code target}
     * through Kraje. Another active NPC country answers at once; a player country's ruler gets the usual prompt.
     */
    default NpcReply npcPropose(final UUID npc, final UUID target, final pl.medivalrpg.kraje.model.Relation relation) {
        return NpcReply.fail("nieobsługiwane");
    }

    /**
     * Admin: the NPC country declares war through {@code KrajeApi#declareWar} (cost from its treasury, cooldowns and
     * vassal rules of Kraje). Refused when the stance towards {@code target} is {@link NpcStance#NEVER_WAR}.
     */
    default KrajeResult npcDeclareWar(final UUID npc, final UUID target) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Pending alliance / peace proposal between the NPC country and {@code other}. */
    default Optional<NpcProposal> pendingProposal(final UUID npc, final UUID other) {
        return Optional.empty();
    }

    /** Daily tribute between the NPC country and {@code other} (tribute demand or vassalage). */
    default Optional<NpcTribute> tribute(final UUID npc, final UUID other) {
        return Optional.empty();
    }

    /** Tax multiplier of NPC citizens in percent (100 = normal Ludnosc tax). */
    default int taxMultiplier(final UUID npc) {
        return 100;
    }

    /** Sets the tax multiplier, 0..300 %: Armia adds / takes the difference to Ludnosc's tax at every AI tick. */
    default KrajeResult setTaxMultiplier(final UUID npc, final int percent) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Guard posts of the country (soldiers in guard mode grouped by their post chunk). */
    default List<NpcOutpost> outposts(final UUID npc) {
        return List.of();
    }

    /**
     * AI decision log, newest first, at most {@code limit}. {@code category}: an {@link NpcLogCategory} name or Polish
     * name, null/blank/{@code ALL} = all. {@code query}: substring, case- and Polish-diacritics-insensitive (null = any).
     */
    default List<NpcLogRecord> npcLog(final UUID npc, final String category, final String query, final int limit) {
        return List.of();
    }

    /**
     * Main thread. A safe standing spot in the capital of an NPC country, next to its ruler (or the capital chunk centre
     * on the surface), e.g. for an admin teleport ({@code /nk tp}, Menu). Empty for unknown / non-NPC countries or an
     * unloaded world. May load the capital chunk.
     */
    default Optional<Location> npcCapitalSpot(final UUID npc) {
        return Optional.empty();
    }

    /**
     * Main thread. Takes over the village nearest to (x, z) in {@code world} (Ludnosc's {@code findVillage}, radius 96) as a
     * new NPC country: capital at the bell, every village chunk claimed, villagers and houses adopted, ruler at the bell.
     * {@code name}/{@code ruler} null = random Polish ones, {@code rgb} &lt; 0 = random colour; random personality. The
     * future completes on the main thread.
     */
    default java.util.concurrent.CompletableFuture<NpcVillageResult> createNpcFromVillage(final String world, final int x, final int z,
                                                                                          final String name, final int rgb, final String ruler) {
        return java.util.concurrent.CompletableFuture.completedFuture(NpcVillageResult.fail("nieobsługiwane"));
    }

    /** Mercenaries of a country (any country, not only NPC), grouped by type and contract end, earliest first. */
    default List<NpcMercenary> mercenaries(final UUID countryId) {
        return List.of();
    }

    /** Like {@link #logNpcDecision(UUID, String)} with an explicit category ({@link NpcLogCategory} name or Polish). */
    default void logNpcDecision(final UUID countryId, final String category, final String text) {
        this.logNpcDecision(countryId, text);
    }
}
