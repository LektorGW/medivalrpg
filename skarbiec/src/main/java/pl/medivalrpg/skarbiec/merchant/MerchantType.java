// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.merchant;

import java.util.Set;
import org.bukkit.Material;

/**
 * Kind of merchant NPC (ogólny, kowal, spożywczy, budowlany…): which catalog categories it carries and how many goods
 * of the delivery it has at once. All types share one warehouse, one budget ({@code world:merchant}) and the per-good
 * daily limits, so visiting several merchants never bypasses a limit.
 *
 * @param id         stored id and command argument (lower case)
 * @param name       MiniMessage display name
 * @param icon       item in the NPC's hand
 * @param categories carried categories; {@code "*"} = every category
 * @param coreItems  extra always-on-offer goods (material names), carried even outside the categories
 * @param rotating   goods of the regular delivery per period
 * @param seasonal   extra slots for goods of the current season
 */
public record MerchantType(String id, String name, Material icon, Set<String> categories, Set<String> coreItems, int rotating,
                           int seasonal) {

    public static final String ALL = "*";

    public MerchantType {
        categories = Set.copyOf(categories);
        coreItems = Set.copyOf(coreItems);
        rotating = Math.max(0, rotating);
        seasonal = Math.max(0, seasonal);
    }

    /** Whether this type trades the good at all (in some period). */
    public boolean carries(final MerchantOffer offer) {
        return this.categories.contains(ALL) || this.categories.contains(offer.category()) || this.coreItems.contains(offer.key());
    }

    /** Whether the good is always on offer at this type. */
    public boolean isCore(final MerchantOffer offer) {
        return this.coreItems.contains(offer.key()) || (offer.core() && this.carries(offer));
    }
}
