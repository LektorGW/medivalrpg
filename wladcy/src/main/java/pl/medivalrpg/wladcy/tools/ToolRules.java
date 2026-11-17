// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.tools;

import java.util.Optional;
import java.util.OptionalInt;
import pl.medivalrpg.kraje.model.Rank;

/**
 * Game rules for the ruler's tools that do not need Bukkit (the glue in {@code game.GameToolHandler} gathers the
 * facts and executes). Every method returns the Polish error for the model, or empty when allowed.
 */
public final class ToolRules {

    private ToolRules() {
    }

    /** Who the ruler is talking to. {@code country}/{@code rank} are null when the player has no country. */
    public record Interlocutor(Object country, Rank rank, boolean sameCountryAsRuler) {
        public boolean hasCountry() {
            return this.country != null;
        }
    }

    /** May the interlocutor conclude treaties / pay tribute for their country? */
    public static Optional<String> negotiator(final Interlocutor who, final Rank minRank) {
        if (!who.hasCountry()) {
            return Optional.of("Rozmówca nie należy do żadnego kraju — nie można z nim zawierać traktatów.");
        }
        if (who.sameCountryAsRuler()) {
            return Optional.of("Rozmówca należy do twojego kraju.");
        }
        if (who.rank() == null || !who.rank().atLeast(minRank)) {
            return Optional.of("Rozmówca nie może negocjować w imieniu kraju (wymagana ranga: " + minRank.displayName()
                + "). Odeślij go do jego władcy.");
        }
        return Optional.empty();
    }

    public static Optional<String> tribute(final Interlocutor who, final Rank minRank, final long amount, final long max, final boolean pendingOffer) {
        final Optional<String> n = negotiator(who, minRank);
        if (n.isPresent()) {
            return n;
        }
        if (amount <= 0) {
            return Optional.of("Kwota musi być dodatnia.");
        }
        if (amount > max) {
            return Optional.of("Za wysoki trybut — najwyżej " + max + " md.");
        }
        if (pendingOffer) {
            return Optional.of("Rozmówca ma już nierozstrzygnięte żądanie trybutu. Poczekaj na jego decyzję.");
        }
        return Optional.empty();
    }

    /**
     * How much the ruler may still give today: {@code percent}% of the current treasury minus today's gifts of this
     * ruler, capped by what is left of the per-player daily limit.
     */
    public static long giftAllowance(final long treasury, final double percent, final long givenTodayByRuler,
                                     final long givenTodayToPlayer, final long perPlayerMax) {
        final long byTreasury = (long) Math.floor(Math.max(0, treasury) * percent / 100.0) - Math.max(0, givenTodayByRuler);
        final long byPlayer = perPlayerMax - Math.max(0, givenTodayToPlayer);
        return Math.max(0, Math.min(byTreasury, byPlayer));
    }

    public static Optional<String> gift(final long amount, final long allowance, final boolean toCountry, final Interlocutor who) {
        if (amount <= 0) {
            return Optional.of("Kwota musi być dodatnia.");
        }
        if (toCountry && !who.hasCountry()) {
            return Optional.of("Rozmówca nie ma kraju — dar może trafić tylko na jego konto (odbiorca „gracz”).");
        }
        if (toCountry && who.sameCountryAsRuler()) {
            return Optional.of("To twój własny kraj.");
        }
        if (allowance <= 0) {
            return Optional.of("Dzisiejszy limit darów jest wyczerpany.");
        }
        if (amount > allowance) {
            return Optional.of("Za dużo — dziś możesz podarować najwyżej " + allowance + " md.");
        }
        return Optional.empty();
    }

    /**
     * Facts for {@link #war}.
     *
     * @param aggression  the NPC country's aggression from Armia (empty = unknown)
     * @param lastWarAt   epoch millis of the last war declared through a conversation by this NPC country (0 = never)
     */
    public record WarCheck(boolean allowWar, Interlocutor who, boolean alreadyAtWar, OptionalInt aggression, int minAggression,
                           long lastWarAt, long now, int cooldownHours, String reason) {
    }

    public static Optional<String> war(final WarCheck w) {
        if (!w.allowWar()) {
            return Optional.of("Wojny nie wypowiada się podczas audiencji.");
        }
        if (!w.who().hasCountry()) {
            return Optional.of("Rozmówca nie należy do żadnego kraju — nie ma komu wypowiedzieć wojny. Wycofaj się.");
        }
        if (w.who().sameCountryAsRuler()) {
            return Optional.of("Rozmówca należy do twojego kraju.");
        }
        if (w.alreadyAtWar()) {
            return Optional.of("Wasze kraje już są w stanie wojny.");
        }
        if (w.reason() == null || w.reason().isBlank()) {
            return Optional.of("Podaj powód wojny.");
        }
        if (w.aggression().isEmpty()) {
            return Optional.of("Rada nie zna twoich zamiarów (brak ustawień AI kraju) — wojna niemożliwa. Wycofaj się.");
        }
        if (w.aggression().getAsInt() < w.minAggression()) {
            return Optional.of("Twój charakter jest zbyt łagodny na wojnę (agresja " + w.aggression().getAsInt() + " < "
                + w.minAggression() + "). Wycofaj się z groźby w roli.");
        }
        final long cooldown = w.cooldownHours() * 3_600_000L;
        if (w.lastWarAt() > 0 && w.now() - w.lastWarAt() < cooldown) {
            final long hours = Math.max(1, (cooldown - (w.now() - w.lastWarAt()) + 3_599_999L) / 3_600_000L);
            return Optional.of("Rada nie zgodzi się na kolejną wojnę tak szybko (możliwe za ok. " + hours + " h). Wycofaj się w roli.");
        }
        return Optional.empty();
    }

    /**
     * The admin's stance towards the interlocutor's country (Armia {@code /nk postawa}): {@code NEVER_WAR} forbids every
     * war of this NPC country with it, also one talked into during an audience.
     */
    public static Optional<String> warStance(final pl.medivalrpg.armia.api.NpcStance stance) {
        if (stance == pl.medivalrpg.armia.api.NpcStance.NEVER_WAR) {
            return Optional.of("Rada królewska zakazała wojny z tym krajem (postawa: nigdy wojny). Wycofaj się w roli.");
        }
        return Optional.empty();
    }
}
