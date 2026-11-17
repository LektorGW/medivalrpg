// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.gui;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.config.Messages;
import pl.medivalrpg.skarbiec.integration.Seasons;
import pl.medivalrpg.skarbiec.merchant.MerchantAssortment;
import pl.medivalrpg.skarbiec.merchant.MerchantOffer;
import pl.medivalrpg.skarbiec.merchant.MerchantService;
import pl.medivalrpg.skarbiec.merchant.MerchantType;
import pl.medivalrpg.skarbiec.shop.Items;

/**
 * Merchant NPC window: what this merchant type has in the current delivery (45 offers per page; core goods first).
 * LMB sells one bundle, Shift+LMB all of that item, RMB buys one bundle, Shift+RMB a stack. Bottom row: pages,
 * "sell everything", daily limit and delivery clock, preview of the next delivery.
 */
public final class MerchantGui extends Gui {

    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45;
    private static final int SELL_ALL = 47;
    private static final int INFO = 49;
    private static final int PREVIEW = 50;
    private static final int CLOSE = 51;
    private static final int NEXT = 53;
    private static final int PREVIEW_LINES = 18;

    private final SkarbiecPlugin plugin;
    private final Player viewer;
    private final String typeId;
    /** Offers as shown by the last render (clicks map to these). */
    private List<MerchantOffer> offers = List.of();
    private int page;

    public MerchantGui(final SkarbiecPlugin plugin, final Player viewer, final Component title, final String typeId) {
        super(6, title);
        this.plugin = plugin;
        this.viewer = viewer;
        this.typeId = typeId;
        this.render();
    }

    private MerchantType type() {
        return this.plugin.merchant().type(this.typeId);
    }

    private int pages() {
        return Math.max(1, (this.offers.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void render() {
        final Messages m = this.plugin.messages();
        final MerchantService svc = this.plugin.merchant();
        final MerchantType type = this.type();
        final MerchantAssortment.Pick pick = svc.pick(type);
        this.offers = pick.offers();
        this.page = Math.min(this.page, this.pages() - 1);
        this.inventory.clear();
        final long deliveryMinutes = Math.max(1, svc.millisToDelivery() / 60_000);
        final var dHours = Placeholder.unparsed("hours", Long.toString(deliveryMinutes / 60));
        final var dMinutes = Placeholder.unparsed("minutes", Long.toString(deliveryMinutes % 60));
        final ItemStack[] storage = this.viewer.getInventory().getStorageContents();
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int idx = this.page * PAGE_SIZE + i;
            if (idx >= this.offers.size()) {
                break;
            }
            final MerchantOffer o = this.offers.get(idx);
            final List<Component> lore = new ArrayList<>();
            final var count = Placeholder.unparsed("count", Integer.toString(o.amount()));
            final boolean featured = o.buys() && svc.featured(type, o);
            if (featured) {
                lore.add(m.item("gui.merchant.offer-featured"));
            }
            if (svc.assortmentOn()) {
                if (pick.isCore(o)) {
                    lore.add(m.item("gui.merchant.offer-core"));
                } else if (pick.isSeasonal(o)) {
                    lore.add(m.item("gui.merchant.offer-seasonal",
                        Placeholder.unparsed("season", Seasons.name(svc.seasonOf(svc.deliveryPeriod()))), dHours, dMinutes));
                } else {
                    lore.add(m.item("gui.merchant.offer-delivery", dHours, dMinutes));
                }
            }
            if (o.buys() && svc.marketOn()) {
                final long price = svc.currentBuyPrice(type, o);
                if (price <= 0) {
                    lore.add(m.item("gui.merchant.offer-no-budget"));
                } else {
                    lore.add(m.item("gui.merchant.offer-buy", SkarbiecPlugin.amount(price), count));
                    final int percent = svc.pricePercent(type, o);
                    if (percent != 100) {
                        lore.add(m.item(percent > 100 ? "gui.merchant.offer-price-up" : "gui.merchant.offer-price-down",
                            Placeholder.unparsed("percent", Integer.toString(Math.abs(percent - 100)))));
                    }
                    final int[] trend = svc.trend(o);
                    lore.add(m.item(trend[0] > 0 ? "gui.merchant.offer-trend-up" : trend[0] < 0 ? "gui.merchant.offer-trend-down"
                        : "gui.merchant.offer-trend-flat", Placeholder.unparsed("percent", Integer.toString(trend[1]))));
                }
                final int limit = svc.itemLimit(type, o);
                if (limit > 0) {
                    lore.add(m.item("gui.merchant.offer-limit",
                        Placeholder.unparsed("sold", Long.toString(svc.soldToday(this.viewer.getUniqueId(), o))),
                        Placeholder.unparsed("limit", Integer.toString(limit))));
                }
            } else if (o.buys()) {
                final int percent = svc.rotationPercent(type, o);
                lore.add(m.item("gui.merchant.offer-buy", SkarbiecPlugin.amount(svc.currentBuyPrice(type, o)), count));
                if (percent != 100) {
                    lore.add(m.item(percent > 100 ? "gui.merchant.offer-price-up" : "gui.merchant.offer-price-down",
                        Placeholder.unparsed("percent", Integer.toString(Math.abs(percent - 100)))));
                }
                final int drop = svc.supplyDrop(o);
                if (drop > 0) {
                    lore.add(m.item("gui.merchant.offer-supply", Placeholder.unparsed("drop", Integer.toString(drop))));
                }
                final int limit = svc.itemLimit(type, o);
                if (limit > 0) {
                    lore.add(m.item("gui.merchant.offer-limit",
                        Placeholder.unparsed("sold", Long.toString(svc.soldToday(this.viewer.getUniqueId(), o))),
                        Placeholder.unparsed("limit", Integer.toString(limit))));
                }
            } else {
                lore.add(m.item("gui.merchant.offer-no-buy"));
            }
            if (o.sells() && svc.marketOn()) {
                final long sell = svc.sellPriceNow(o);
                lore.add(sell > 0 ? m.item("gui.merchant.offer-sell", SkarbiecPlugin.amount(sell), count)
                    : m.item("gui.merchant.offer-sold-out"));
            } else if (o.sells()) {
                lore.add(m.item("gui.merchant.offer-sell", SkarbiecPlugin.amount(o.sell()), count));
            }
            if (svc.marketOn()) {
                lore.add(m.item("gui.merchant.offer-stock-" + svc.stockLevel(o).toLowerCase(java.util.Locale.ROOT),
                    Placeholder.unparsed("stock", Long.toString(svc.stock(o)))));
            }
            lore.add(m.item("gui.merchant.offer-have",
                Placeholder.unparsed("have", Integer.toString(Items.count(storage, ItemStack.of(o.material()))))));
            lore.add(Component.empty());
            if (o.buys()) {
                lore.addAll(m.lines("gui.merchant.hint-sell", count));
            }
            if (o.sells()) {
                lore.addAll(m.lines("gui.merchant.hint-buy", count));
            }
            final ItemStack icon = icon(o.material(), Component.translatable(o.material()).color(NamedTextColor.YELLOW), lore);
            icon.setAmount(Math.max(1, Math.min(o.amount(), icon.getMaxStackSize())));
            if (featured) {
                icon.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            }
            this.inventory.setItem(i, icon);
        }
        for (int i = PAGE_SIZE; i < 54; i++) {
            this.inventory.setItem(i, filler(Material.BLACK_STAINED_GLASS_PANE));
        }
        if (this.page > 0) {
            this.inventory.setItem(PREV, icon(Material.ARROW, m.item("gui.prev"), List.of()));
        }
        if (this.page < this.pages() - 1) {
            this.inventory.setItem(NEXT, icon(Material.ARROW, m.item("gui.next"), List.of()));
        }
        this.inventory.setItem(SELL_ALL, icon(Material.HOPPER, m.item("gui.merchant.sell-all-name"), m.lines("gui.merchant.sell-all-lore")));
        final long limit = this.plugin.cfg().merchantDailyLimit;
        final long earned = svc.earnedToday(this.viewer.getUniqueId());
        final List<Component> info = new ArrayList<>(m.lines("gui.merchant.info-type",
            Placeholder.parsed("type", type.name()), Placeholder.unparsed("count", Integer.toString(this.offers.size()))));
        info.addAll(limit > 0
            ? m.lines("gui.merchant.info-lore", SkarbiecPlugin.amount("earned", earned), SkarbiecPlugin.amount("limit", limit),
                SkarbiecPlugin.amount("left", Math.max(0, limit - earned)),
                Placeholder.unparsed("target", m.raw(this.plugin.cfg().merchantToAccount ? "merchant.to-account" : "merchant.to-purse")))
            : m.lines("gui.merchant.info-lore-unlimited", SkarbiecPlugin.amount("earned", earned)));
        if (svc.marketOn()) {
            final long budget = svc.budget();
            final long target = this.plugin.cfg().market.budgetTarget();
            final String purse = !svc.buying() ? "gui.merchant.info-purse-empty"
                : target > 0 && budget < target / 2 ? "gui.merchant.info-purse-low"
                : target > 0 && budget > target * 3 / 2 ? "gui.merchant.info-purse-rich" : "gui.merchant.info-purse-ok";
            info.addAll(m.lines(purse));
        }
        if (svc.assortmentOn()) {
            info.addAll(m.lines("gui.merchant.info-delivery", dHours, dMinutes));
        }
        if (this.plugin.cfg().rotation.enabled()) {
            final long minutes = Math.max(1, svc.millisToRotation() / 60_000);
            info.addAll(m.lines("gui.merchant.info-rotation",
                Placeholder.unparsed("hours", Long.toString(minutes / 60)),
                Placeholder.unparsed("minutes", Long.toString(minutes % 60))));
        }
        this.inventory.setItem(INFO, icon(Material.CLOCK, m.item("gui.merchant.info-name"), info));
        if (svc.assortmentOn() && this.plugin.cfg().assortment.preview()) {
            this.inventory.setItem(PREVIEW, icon(Material.MAP, m.item("gui.merchant.preview-name"), this.preview(svc, type, dHours, dMinutes)));
        }
        this.inventory.setItem(CLOSE, icon(Material.BARRIER, m.item("gui.close"), List.of()));
    }

    /** Lore of the "next delivery" item: goods of the next period (★ = the merchant will look for them). */
    private List<Component> preview(final MerchantService svc, final MerchantType type,
                                    final net.kyori.adventure.text.minimessage.tag.resolver.TagResolver hours,
                                    final net.kyori.adventure.text.minimessage.tag.resolver.TagResolver minutes) {
        final Messages m = this.plugin.messages();
        final MerchantAssortment.Pick next = svc.pick(type, svc.deliveryPeriod() + 1);
        final List<Component> lore = new ArrayList<>(m.lines("gui.merchant.preview-head", hours, minutes));
        final List<MerchantOffer> delivery = next.delivery();
        if (delivery.isEmpty()) {
            lore.addAll(m.lines("gui.merchant.preview-empty"));
            return lore;
        }
        final List<MerchantOffer> ordered = new ArrayList<>(delivery.stream().filter(o -> next.featured().contains(o.key())).toList());
        delivery.stream().filter(o -> !next.featured().contains(o.key())).forEach(ordered::add);
        int shown = 0;
        for (final MerchantOffer o : ordered) {
            if (shown == PREVIEW_LINES) {
                lore.add(m.item("gui.merchant.preview-more", Placeholder.unparsed("count", Integer.toString(ordered.size() - shown))));
                break;
            }
            lore.add(m.item(next.featured().contains(o.key()) ? "gui.merchant.preview-featured" : "gui.merchant.preview-line",
                Placeholder.component("item", Component.translatable(o.material()))));
            shown++;
        }
        final long coreFeatured = next.core().stream().filter(o -> next.featured().contains(o.key())).count();
        if (coreFeatured > 0) {
            lore.add(m.item("gui.merchant.preview-core-featured", Placeholder.unparsed("count", Long.toString(coreFeatured))));
        }
        return lore;
    }

    @Override
    protected void onClick(final Player player, final int slot, final ClickType click) {
        if (slot < PAGE_SIZE) {
            final int idx = this.page * PAGE_SIZE + slot;
            if (idx >= this.offers.size()) {
                return;
            }
            final MerchantOffer o = this.offers.get(idx);
            final MerchantType type = this.type();
            switch (click) {
                case LEFT -> this.plugin.merchant().sell(player, type, o, 1, false);
                case SHIFT_LEFT -> this.plugin.merchant().sell(player, type, o, Integer.MAX_VALUE, false);
                case RIGHT -> this.plugin.merchant().buy(player, type, o, 1);
                case SHIFT_RIGHT -> this.plugin.merchant().buy(player, type, o, Math.max(1, 64 / o.amount()));
                default -> {
                    return;
                }
            }
            this.render();
        } else if (slot == PREV && this.page > 0) {
            this.page--;
            this.render();
        } else if (slot == NEXT && this.page < this.pages() - 1) {
            this.page++;
            this.render();
        } else if (slot == SELL_ALL) {
            this.plugin.merchant().sellAll(player, this.type());
            this.render();
        } else if (slot == CLOSE) {
            player.closeInventory();
        }
    }
}
