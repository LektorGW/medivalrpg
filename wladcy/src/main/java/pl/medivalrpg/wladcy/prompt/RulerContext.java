// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.prompt;

import java.util.List;
import pl.medivalrpg.wladcy.memory.PairMemory;

/**
 * Snapshot of everything the ruler knows at the start of a conversation (collected on the main thread by
 * {@code game.ContextCollector}). Strings are plain text; amounts are already formatted ("około 12 zł").
 *
 * @param personalityKnown false when Armia reports no AI settings (then the trait values are defaults)
 * @param honor            0–100 from Armia ({@code NpcSettings.honor}) or derived from the personality
 * @param army             soldiers per unit type, e.g. "piechota 12, łucznik 4" ("brak" when none)
 * @param playerCountry    null when the player has no country
 * @param playerRank       Polish rank name, null without a country
 * @param relation         relation of the ruler's country to the player's ("neutralne", "sojusz", "wojna"), null without a country
 * @param aiLog            recent AI decisions of the ruler's council about the player's country
 * @param previous         earlier exchanges with this player (oldest first)
 * @param reputation       the player's reputation with the ruler's country (−100…100)
 * @param reputationTier   Polish tier name ("Znajomy"), null when quests/reputation are disabled
 * @param reputationTone   how the ruler treats a player of that tier (Polish), null when disabled
 * @param questInfo        quests in progress, today's offers for this player and the country's needs (plain text,
 *                         already escaped), null when disabled
 * @param realm            era, unions, embassies, bridgeheads, landings and quest security ({@code <swiat>}), null = unknown
 */
public record RulerContext(
    String rulerName,
    String countryName,
    boolean personalityKnown,
    int aggression,
    int expansion,
    int diplomacy,
    int wealth,
    int honor,
    int chunks,
    int soldiers,
    String army,
    String treasury,
    int population,
    int housing,
    List<String> allies,
    List<String> enemies,
    String playerName,
    String playerCountry,
    String playerRank,
    boolean playerIsRuler,
    boolean playerCanNegotiate,
    String relation,
    int playerCountryChunks,
    int playerCountrySoldiers,
    int sympathy,
    String relationSummary,
    List<String> notes,
    List<String> aiLog,
    List<PairMemory.Exchange> previous,
    String giftAllowance,
    String tributeMax,
    String warStatus,
    int reputation,
    String reputationTier,
    String reputationTone,
    String questInfo,
    RealmFacts realm
) {

    /** Context without the wave 13–17 world facts (older callers/tests). */
    public RulerContext(final String rulerName, final String countryName, final boolean personalityKnown, final int aggression,
                        final int expansion, final int diplomacy, final int wealth, final int honor, final int chunks, final int soldiers,
                        final String army, final String treasury, final int population, final int housing, final List<String> allies,
                        final List<String> enemies, final String playerName, final String playerCountry, final String playerRank,
                        final boolean playerIsRuler, final boolean playerCanNegotiate, final String relation, final int playerCountryChunks,
                        final int playerCountrySoldiers, final int sympathy, final String relationSummary, final List<String> notes,
                        final List<String> aiLog, final List<PairMemory.Exchange> previous, final String giftAllowance, final String tributeMax,
                        final String warStatus, final int reputation, final String reputationTier, final String reputationTone,
                        final String questInfo) {
        this(rulerName, countryName, personalityKnown, aggression, expansion, diplomacy, wealth, honor, chunks, soldiers, army, treasury,
            population, housing, allies, enemies, playerName, playerCountry, playerRank, playerIsRuler, playerCanNegotiate, relation,
            playerCountryChunks, playerCountrySoldiers, sympathy, relationSummary, notes, aiLog, previous, giftAllowance, tributeMax,
            warStatus, reputation, reputationTier, reputationTone, questInfo, null);
    }

    /** Context without reputation and quests (zlecenia disabled). */
    public RulerContext(final String rulerName, final String countryName, final boolean personalityKnown, final int aggression,
                        final int expansion, final int diplomacy, final int wealth, final int honor, final int chunks, final int soldiers,
                        final String army, final String treasury, final int population, final int housing, final List<String> allies,
                        final List<String> enemies, final String playerName, final String playerCountry, final String playerRank,
                        final boolean playerIsRuler, final boolean playerCanNegotiate, final String relation, final int playerCountryChunks,
                        final int playerCountrySoldiers, final int sympathy, final String relationSummary, final List<String> notes,
                        final List<String> aiLog, final List<PairMemory.Exchange> previous, final String giftAllowance, final String tributeMax,
                        final String warStatus) {
        this(rulerName, countryName, personalityKnown, aggression, expansion, diplomacy, wealth, honor, chunks, soldiers, army, treasury,
            population, housing, allies, enemies, playerName, playerCountry, playerRank, playerIsRuler, playerCanNegotiate, relation,
            playerCountryChunks, playerCountrySoldiers, sympathy, relationSummary, notes, aiLog, previous, giftAllowance, tributeMax,
            warStatus, 0, null, null, null, null);
    }
}
