// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.medivalrpg.skarbiec.ledger.WorldMoney;

/** Groups ledger accounts into the balance-sheet categories of the owner's statistics (pure Java). */
public final class AccountTypes {

    /** Stable order of the balance sheet. */
    public static final List<String> ORDER = List.of("reserve", "cash", "payments", "merchant", "households", "citizens", "players",
        "countries", "firms", "railBudgets", "escrow", "server", "other");

    public static final Map<String, String> LABELS;

    /** World pools: money leaving them enters the economy, money coming back leaves it. */
    public static final java.util.Set<String> WORLD_POOLS = java.util.Set.of("reserve", "payments", "merchant", "households");

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("reserve", "Rezerwa Skarbu Świata");
        m.put("cash", "Monety fizyczne w obiegu");
        m.put("payments", "Budżet operacyjny świata");
        m.put("merchant", "Budżet kupców NPC");
        m.put("households", "Budżet obywateli NPC");
        m.put("citizens", "Oszczędności obywateli NPC");
        m.put("players", "Konta graczy");
        m.put("countries", "Skarbce krajów");
        m.put("firms", "Firmy (graczy i kolej)");
        m.put("railBudgets", "Budżety budowy kolei");
        m.put("escrow", "Depozyty (zlecenia, karawany, turnieje, firmy)");
        m.put("server", "Konta serwera");
        m.put("other", "Inne konta");
        LABELS = java.util.Collections.unmodifiableMap(m);
    }

    private AccountTypes() {
    }

    public static String type(final String account) {
        if (account == null) {
            return "other";
        }
        return switch (account) {
            case WorldMoney.RESERVE -> "reserve";
            case WorldMoney.CASH -> "cash";
            case WorldMoney.PAYMENTS -> "payments";
            case WorldMoney.MERCHANT -> "merchant";
            case WorldMoney.HOUSEHOLDS -> "households";
            default -> {
                final int colon = account.indexOf(':');
                final String ns = colon < 0 ? account : account.substring(0, colon);
                yield switch (ns) {
                    case "player" -> "players";
                    case "country" -> "countries";
                    case "ludnosc" -> account.startsWith("ludnosc:obywatele_") ? "citizens" : "other";
                    case "kolej" -> account.startsWith("kolej:budzet_") ? "railBudgets" : "firms";
                    case "wladcy", "szlaki", "turnieje" -> "escrow";
                    // Firmy (player companies): the company account, and its dedicated deposits (contracts, loans, applications)
                    case "firma" -> "firms";
                    case "firma-kontrakt", "firma-kredyt", "firma-wniosek" -> "escrow";
                    case "serwer" -> "server";
                    default -> "other";
                };
            }
        };
    }

    public static String label(final String type) {
        return LABELS.getOrDefault(type, LABELS.get("other"));
    }

    /** Descriptive name of a technical account (players and countries are resolved by the caller). */
    public static String technicalName(final String account) {
        if (account == null) {
            return "";
        }
        switch (account) {
            case WorldMoney.RESERVE: return "Rezerwa Skarbu Świata";
            case WorldMoney.CASH: return "Pokrycie monet fizycznych";
            case WorldMoney.PAYMENTS: return "Budżet operacyjny";
            case WorldMoney.MERCHANT: return "Budżet kupców";
            case WorldMoney.HOUSEHOLDS: return "Budżet obywateli NPC";
            case "kolej:przewoznik": return "Kolej — przewoźnik";
            case "kolej:obsluga": return "Kolej — obsługa infrastruktury";
            case "serwer:turnieje": return "Pula nagród turniejów";
            default: break;
        }
        if (account.startsWith("kolej:budzet_")) {
            return "Budżet budowy kolei " + account.substring("kolej:budzet_".length());
        }
        if (account.startsWith("wladcy:")) {
            return "Zlecenie władcy " + account.substring("wladcy:".length());
        }
        if (account.startsWith("szlaki:")) {
            return "Przesyłka szlaku " + account.substring("szlaki:".length());
        }
        if (account.startsWith("firma:")) {
            return "Firma " + account.substring("firma:".length());
        }
        if (account.startsWith("firma-kontrakt:")) {
            return "Depozyt kontraktu firmy " + account.substring("firma-kontrakt:".length());
        }
        if (account.startsWith("firma-kredyt:")) {
            return "Depozyt kredytu firmy " + account.substring("firma-kredyt:".length());
        }
        if (account.startsWith("firma-wniosek:")) {
            return "Depozyt wniosku o firmę " + account.substring("firma-wniosek:".length());
        }
        if (account.startsWith("turnieje:")) {
            return "Turniej " + account.substring("turnieje:".length());
        }
        return account;
    }
}
