// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.merchant;

import java.util.Set;
import org.bukkit.Material;

/**
 * One line of the merchant's price list (the whole catalog; which lines are on offer now decides
 * {@link MerchantAssortment}).
 *
 * @param material   plain item (no custom name/data) the merchant trades
 * @param amount     items per bundle (one click)
 * @param buy        copper the merchant PAYS for one bundle (0 = does not buy)
 * @param sell       copper the merchant CHARGES for one bundle (0 = does not sell); never below {@code buy}
 * @param dailyLimit items one player may sell per day (0 = only the global daily money limit applies)
 * @param category   catalog category (merchant types carry whole categories); {@code "inne"} when not set
 * @param core       always on offer at every merchant type that carries the category
 * @param seasons    Ludnosc season ids the good is seasonal in (empty = not seasonal)
 */
public record MerchantOffer(Material material, int amount, long buy, long sell, int dailyLimit, String category, boolean core,
                            Set<String> seasons) {

    public static final String DEFAULT_CATEGORY = "inne";

    public MerchantOffer {
        category = category == null || category.isBlank() ? DEFAULT_CATEGORY : category;
        seasons = seasons == null ? Set.of() : Set.copyOf(seasons);
    }

    /** Old price-list line without catalog data (category {@code inne}, not core, not seasonal). */
    public MerchantOffer(final Material material, final int amount, final long buy, final long sell, final int dailyLimit) {
        this(material, amount, buy, sell, dailyLimit, DEFAULT_CATEGORY, false, Set.of());
    }

    public String key() {
        return this.material.name();
    }

    public boolean buys() {
        return this.buy > 0;
    }

    public boolean sells() {
        return this.sell > 0;
    }

    public boolean seasonal() {
        return !this.seasons.isEmpty();
    }
}
