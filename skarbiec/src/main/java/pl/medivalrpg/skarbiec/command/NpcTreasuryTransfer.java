// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.skarbiec.ledger.Ledger;
import pl.medivalrpg.skarbiec.ledger.WorldMoney;

/**
 * Transfers between the World Treasury reserve and the treasury of an NPC country (pure Java, unit tested).
 * Owner only (UUID), closed pool only: one atomic ledger move from/to {@code world:reserve}; nothing is created.
 * The amount must be confirmed (the same value in md) and a reason is written into the ledger.
 */
public final class NpcTreasuryTransfer {

    public static final String TO_COUNTRY = "toCountry";
    public static final String TO_RESERVE = "toReserve";

    /** An NPC country as seen through Kraje (null when it does not exist). */
    public record Country(UUID id, String name, boolean npc, String color) {
        public String account() {
            return SkarbiecApi.countryAccount(this.id);
        }
    }

    private NpcTreasuryTransfer() {
    }

    /** Validates and performs the move; throws {@link IllegalArgumentException} with a Polish message on refusal. */
    public static Map<String, Object> execute(final Ledger ledger, final UUID actor, final Country country, final long amount, final long confirm,
                                              final String reason, final String direction) {
        if (actor == null || !WorldMoney.MANAGER.equals(actor)) {
            throw new IllegalArgumentException("Tylko WorldSteward zarządza Skarbem Świata.");
        }
        if (!ledger.closedSupply()) {
            throw new IllegalArgumentException("Zamknięty obieg nie został jeszcze aktywowany.");
        }
        if (country == null || !country.npc()) {
            throw new IllegalArgumentException("Nie ma takiego państwa NPC.");
        }
        if (amount <= 0 || amount > WorldMoney.CAP) {
            throw new IllegalArgumentException("Podaj dodatnią kwotę.");
        }
        if (confirm != amount) {
            throw new IllegalArgumentException("Potwierdzona kwota nie zgadza się z kwotą przelewu.");
        }
        final String why = reason == null ? "" : reason.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').strip();
        if (why.length() < 3 || why.length() > 120) {
            throw new IllegalArgumentException("Podaj powód przelewu (3–120 znaków).");
        }
        final boolean toCountry;
        if (TO_COUNTRY.equals(direction) || direction == null || direction.isBlank()) {
            toCountry = true;
        } else if (TO_RESERVE.equals(direction)) {
            toCountry = false;
        } else {
            throw new IllegalArgumentException("Nieznany kierunek przelewu.");
        }
        final String account = country.account();
        final long before = ledger.totalMoney();
        final TxResult r = toCountry
            ? ledger.allocateWorldMoney(actor, account, amount, "WorldSteward: Skarb Świata → skarbiec kraju NPC " + country.name() + ": " + why)
            : ledger.returnWorldMoney(actor, account, amount, "WorldSteward: zwrot ze skarbca kraju NPC " + country.name() + " do Skarbu Świata: " + why);
        if (r == TxResult.INSUFFICIENT_FUNDS) {
            throw new IllegalArgumentException(toCountry ? "Za mało środków w rezerwie Skarbu Świata." : "Za mało środków w skarbcu kraju.");
        }
        if (!r.ok()) {
            throw new IllegalArgumentException("Przelew nie został wykonany: " + r);
        }
        if (ledger.totalMoney() != before) {
            throw new IllegalStateException("Suma puli zmieniła się podczas przelewu"); // cannot happen: a move keeps the sum
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", (toCountry ? "Przelano " : "Zwrócono ") + pl.medivalrpg.skarbiec.money.Money.format(amount)
            + (toCountry ? " do skarbca " : " ze skarbca ") + country.name() + ".");
        out.put("amount", amount);
        out.put("direction", toCountry ? TO_COUNTRY : TO_RESERVE);
        out.put("country", country.id().toString());
        out.put("reserve", ledger.balance(WorldMoney.RESERVE));
        out.put("balance", ledger.balance(account));
        out.put("seq", ledger.seq());
        return out;
    }
}
