// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage2;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** Rules of contracts, wholesale orders and loans (config sections kontrakty, hurt, kredyty). Amounts in copper. */
public final class Stage2Settings {

    /** One good of the wholesale pool: quantity range and price per piece. */
    public record Good(String material, int min, int max, long unitPrice) {
    }

    // contracts
    public int maxHours = 24 * 30;
    public int minHours = 1;
    public int maxQuantity = 10_000;
    public int maxBondPct = 50;
    public int offerHours = 72;
    public int confirmHours = 72;
    public int openPerFirm = 10;
    public int deliveryReach = 6;

    // wholesale orders of NPC rulers
    public boolean wholesale = true;
    public int perCountry = 2;
    public int wholesaleOfferHours = 48;
    public int wholesaleHours = 72;
    public int capitalRadiusChunks = 2;
    public int reputation = 6;
    public double treasuryPctPerOrder = 3.0;
    public double treasuryPctPerDay = 10.0;
    public long wholesaleMin = 500;
    /**
     * Multiplier of the wholesale rates ({@code hurt.towary.*.cena} × this): decision of the user 2026-10-31 —
     * contract rates at least ×5. The money still comes only from the NPC treasury within its percentage limits.
     */
    public double rateMultiplier = 5.0;
    /** Wholesale offers are topped up (countries below {@link #perCountry} open orders) at most this often. */
    public int refillMinutes = 60;
    public Map<String, Good> goods = new LinkedHashMap<>(Map.of(
        "WHEAT", new Good("WHEAT", 128, 512, 4),
        "BREAD", new Good("BREAD", 64, 256, 12),
        "LEATHER", new Good("LEATHER", 32, 128, 25),
        "WHITE_WOOL", new Good("WHITE_WOOL", 64, 256, 10),
        "IRON_INGOT", new Good("IRON_INGOT", 32, 128, 45),
        "OAK_PLANKS", new Good("OAK_PLANKS", 256, 1024, 2),
        "COBBLESTONE", new Good("COBBLESTONE", 256, 1024, 1),
        "COAL", new Good("COAL", 64, 256, 8),
        "ARROW", new Good("ARROW", 128, 512, 3),
        "COOKED_BEEF", new Good("COOKED_BEEF", 64, 192, 14)));

    // loans
    public boolean loans = true;
    public int maxInterestBp = 1_000;
    public int minDays = 7;
    public int maxDays = 30;
    public int capitalMultiplier = 2;
    public int incomeSharePct = 50;
    public long hardCap = 500_000;
    public int repayFromIncomePct = 50;
    public int loanOfferHours = 72;
    public int overdueLiquidationDays = 7;

    public static Stage2Settings from(final ConfigurationSection root) {
        final Stage2Settings s = new Stage2Settings();
        if (root == null) {
            return s;
        }
        final ConfigurationSection k = root.getConfigurationSection("kontrakty");
        if (k != null) {
            s.minHours = clamp(k.getInt("min-godzin", s.minHours), 1, 24 * 60);
            s.maxHours = clamp(k.getInt("max-godzin", s.maxHours), s.minHours, 24 * 120);
            s.maxQuantity = clamp(k.getInt("max-ilosc", s.maxQuantity), 1, 100_000);
            s.maxBondPct = clamp(k.getInt("max-wadium-procent", s.maxBondPct), 0, 100);
            s.offerHours = clamp(k.getInt("waznosc-oferty-godziny", s.offerHours), 1, 24 * 30);
            s.confirmHours = clamp(k.getInt("potwierdzenie-uslugi-godziny", s.confirmHours), 1, 24 * 30);
            s.openPerFirm = clamp(k.getInt("max-otwartych-na-firme", s.openPerFirm), 1, 200);
            s.deliveryReach = clamp(k.getInt("zasieg-skrzyni", s.deliveryReach), 2, 16);
        }
        final ConfigurationSection h = root.getConfigurationSection("hurt");
        if (h != null) {
            s.wholesale = h.getBoolean("wlaczone", s.wholesale);
            s.perCountry = clamp(h.getInt("na-kraj", s.perCountry), 0, 10);
            s.wholesaleOfferHours = clamp(h.getInt("waznosc-ofert-godziny", s.wholesaleOfferHours), 1, 24 * 14);
            s.wholesaleHours = clamp(h.getInt("termin-godziny", s.wholesaleHours), 1, 24 * 30);
            s.capitalRadiusChunks = clamp(h.getInt("promien-stolicy-chunki", s.capitalRadiusChunks), 0, 16);
            s.reputation = clamp(h.getInt("reputacja", s.reputation), 0, 20);
            s.treasuryPctPerOrder = Math.max(0, Math.min(100, h.getDouble("procent-skarbca-na-zlecenie", s.treasuryPctPerOrder)));
            s.treasuryPctPerDay = Math.max(0, Math.min(100, h.getDouble("procent-skarbca-dziennie", s.treasuryPctPerDay)));
            s.wholesaleMin = Math.max(1, h.getLong("min-nagroda", s.wholesaleMin));
            s.rateMultiplier = Math.max(0.1, Math.min(50.0, h.getDouble("mnoznik-stawek", s.rateMultiplier)));
            s.refillMinutes = clamp(h.getInt("uzupelnianie-minut", s.refillMinutes), 0, 24 * 60);
            final ConfigurationSection g = h.getConfigurationSection("towary");
            if (g != null) {
                final Map<String, Good> goods = new LinkedHashMap<>();
                for (final String key : g.getKeys(false)) {
                    final String mat = key.toUpperCase(Locale.ROOT);
                    final int min = Math.max(1, g.getInt(key + ".min", 64));
                    final int max = Math.max(min, g.getInt(key + ".max", min));
                    final long price = Math.max(1, g.getLong(key + ".cena", 1));
                    goods.put(mat, new Good(mat, min, max, price));
                }
                if (!goods.isEmpty()) {
                    s.goods = goods;
                }
            }
        }
        final ConfigurationSection c = root.getConfigurationSection("kredyty");
        if (c != null) {
            s.loans = c.getBoolean("wlaczone", s.loans);
            s.maxInterestBp = clamp((int) Math.round(c.getDouble("max-odsetki-procent", 10) * 100), 0, 1_000);
            s.minDays = clamp(c.getInt("min-dni", s.minDays), 1, 365);
            s.maxDays = clamp(c.getInt("max-dni", s.maxDays), s.minDays, 365);
            s.capitalMultiplier = clamp(c.getInt("mnoznik-kapitalu", s.capitalMultiplier), 0, 10);
            s.incomeSharePct = clamp(c.getInt("procent-obrotu-30-dni", s.incomeSharePct), 0, 100);
            s.hardCap = Math.max(0, c.getLong("limit-kwotowy", s.hardCap));
            s.repayFromIncomePct = clamp(c.getInt("splata-z-przychodu-procent", s.repayFromIncomePct), 1, 100);
            s.loanOfferHours = clamp(c.getInt("waznosc-oferty-godziny", s.loanOfferHours), 1, 24 * 14);
            s.overdueLiquidationDays = clamp(c.getInt("dni-zwloki-do-likwidacji", s.overdueLiquidationDays), 1, 60);
        }
        return s;
    }

    /** Rate per piece actually offered: the configured price × {@link #rateMultiplier} (at least 1 md). */
    public long rate(final Good g) {
        return Math.max(1L, Math.round(g.unitPrice() * this.rateMultiplier));
    }

    static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }
}
