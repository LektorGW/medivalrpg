// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.postep.PostepPlugin;
import pl.medivalrpg.postep.command.Views;
import pl.medivalrpg.postep.config.Messages;
import pl.medivalrpg.postep.logic.TechTree;
import pl.medivalrpg.postep.model.Discovery;
import pl.medivalrpg.postep.model.EraDef;
import pl.medivalrpg.postep.service.ProgressService;
import pl.medivalrpg.postep.service.Snapshot;

/**
 * {@code /postep drzewko}: chest GUI with one row per era (column 0 = the era, columns 1–8 = its discoveries) and a
 * bottom row with the summary, research target, ranking and close buttons. Owned discoveries glow, available ones show
 * their own icon, locked ones are glass panes. Clicking an available (or path) discovery runs {@code /postep odkryj}.
 * Every click/drag is cancelled, so no item can be taken or put in.
 */
public final class TreeMenu implements Listener {

    private static final int ROW = 9;

    /** Holder of one open menu: slot → discovery id. */
    static final class Holder implements InventoryHolder {
        private Inventory inventory;
        final Map<Integer, String> slots = new HashMap<>();
        int closeSlot = -1;
        int rankingSlot = -1;
        int statusSlot = -1;

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }
    }

    private final PostepPlugin plugin;
    private final Views views;

    public TreeMenu(final PostepPlugin plugin, final Views views) {
        this.plugin = plugin;
        this.views = views;
    }

    private ProgressService service() {
        return this.plugin.service();
    }

    private Messages m() {
        return this.service().messages();
    }

    public void open(final Player player) {
        final Optional<KrajInfo> info = this.service().kraje().getCountryOf(player.getUniqueId());
        final Snapshot s = info.flatMap(k -> this.service().snapshot(k.id())).orElse(null);
        final List<EraDef> eras = this.service().cfg().eras;
        final int rows = Math.min(6, eras.size() + 1);
        final Holder holder = new Holder();
        final Inventory inv = Bukkit.createInventory(holder, rows * ROW, info.isPresent()
            ? this.m().get("gui-title", this.service().countryResolver(info.get()))
            : this.m().get("gui-title-none"));
        holder.inventory = inv;
        final TechTree tree = this.service().tree();

        for (int r = 0; r < Math.min(eras.size(), rows - 1); r++) {
            final EraDef era = eras.get(r);
            inv.setItem(r * ROW, this.eraItem(era, s));
            final List<Discovery> list = tree.inEra(era.id());
            for (int i = 0; i < Math.min(8, list.size()); i++) {
                final Discovery d = list.get(i);
                final int slot = r * ROW + 1 + i;
                inv.setItem(slot, this.discoveryItem(d, s));
                holder.slots.put(slot, d.id());
            }
        }
        final int bottom = (rows - 1) * ROW;
        holder.statusSlot = bottom;
        inv.setItem(bottom, this.statusItem(s, info.orElse(null)));
        inv.setItem(bottom + 2, this.targetItem(s));
        inv.setItem(bottom + 4, this.item(Material.GOLD_INGOT, this.m().get("gui-invest-name"),
            List.of(this.m().get("gui-invest-lore", Placeholder.unparsed("next_gold",
                ProgressService.fmt(s == null ? 0 : this.service().investPreview(s.id(), 100)))))));
        holder.rankingSlot = bottom + 6;
        inv.setItem(bottom + 6, this.item(Material.GOLDEN_HELMET, this.m().get("gui-ranking-name"), List.of(this.m().get("gui-ranking-lore"))));
        holder.closeSlot = bottom + 8;
        inv.setItem(bottom + 8, this.item(Material.BARRIER, this.m().get("gui-close"), List.of()));
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------ items

    private ItemStack eraItem(final EraDef era, final Snapshot s) {
        final String key = s == null ? "gui-era-lore-future" : s.era() == era.id() ? "gui-era-lore-current"
            : s.era() > era.id() ? "gui-era-lore-past" : "gui-era-lore-future";
        final List<Component> lore = new ArrayList<>();
        if (!era.description().isBlank()) {
            lore.add(this.m().get("gui-era-description", Placeholder.unparsed("text", era.description())));
        }
        lore.add(this.m().get(key, this.views.eraTags(era, "era")));
        final ItemStack item = this.item(material(era.guiIcon(), Material.BOOK),
            this.m().get("gui-era-name", this.views.eraTags(era, "era")), lore);
        if (s != null && s.era() == era.id()) {
            glow(item);
        }
        return item;
    }

    private ItemStack discoveryItem(final Discovery d, final Snapshot s) {
        final TechTree tree = this.service().tree();
        final TechTree.Status status = s == null ? (d.era() == 1 && d.requires().isEmpty() ? TechTree.Status.AVAILABLE
            : TechTree.Status.LOCKED_REQUIREMENTS) : tree.status(d, s.discoverySet(), s.era());
        // Tooltip lines become the lore (the first line = title → item name).
        final List<Component> hoverLines = this.views.discoveryLines(d, s);
        final List<Component> lore = new ArrayList<>(hoverLines.subList(1, hoverLines.size()));
        final boolean target = s != null && d.id().equals(s.target());
        switch (status) {
            case OWNED -> {
                final ItemStack item = this.item(material(d.icon(), Material.PAPER), this.m().get("gui-owned-name",
                    Placeholder.unparsed("name", d.name())), lore);
                glow(item);
                return item;
            }
            case AVAILABLE -> {
                lore.add(Component.empty());
                lore.add(this.m().get(s != null && s.points() + 1e-9 >= d.cost() ? "gui-click-unlock" : "gui-click-target"));
                return this.item(material(d.icon(), Material.PAPER), this.m().get(target ? "gui-target-name" : "gui-available-name",
                    Placeholder.unparsed("name", d.name()), Placeholder.unparsed("cost", Long.toString(d.cost()))), lore);
            }
            case LOCKED_REQUIREMENTS -> {
                if (s != null) {
                    lore.add(Component.empty());
                    lore.add(this.m().get("gui-click-path"));
                }
                return this.item(target ? Material.YELLOW_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    this.m().get(target ? "gui-target-name" : "gui-locked-name", Placeholder.unparsed("name", d.name()),
                        Placeholder.unparsed("cost", Long.toString(d.cost()))), lore);
            }
            default -> {
                return this.item(Material.BLACK_STAINED_GLASS_PANE, this.m().get("gui-era-locked-name",
                    Placeholder.unparsed("name", d.name())), lore);
            }
        }
    }

    private ItemStack statusItem(final Snapshot s, final KrajInfo info) {
        if (s == null || info == null) {
            return this.item(Material.BOOK, this.m().get("gui-status-none"), List.of());
        }
        final List<Component> lore = new ArrayList<>();
        lore.add(this.m().get("gui-status-points", Placeholder.unparsed("points", ProgressService.fmt(Math.floor(s.points()))),
            Placeholder.unparsed("daily", ProgressService.fmt(s.daily().total()))));
        if (s.check().atMaxEra()) {
            lore.add(this.m().get("gui-status-max"));
        } else {
            final EraDef next = this.service().era(s.era() + 1);
            lore.add(this.m().get("gui-status-progress", this.views.eraTags(next, "next"),
                Placeholder.component("bar", this.views.bar(s.check().progress(), ProgressService.color(next.color()))),
                Placeholder.unparsed("percent", Views.percent(s.check().progress()))));
            for (final String miss : s.check().missing()) {
                lore.add(this.m().get("gui-status-missing", Placeholder.unparsed("text", miss)));
            }
            if (s.check().ready()) {
                lore.add(this.m().get("gui-status-ready"));
            }
        }
        return this.item(Material.WRITTEN_BOOK, this.m().get("gui-status-name", this.service().countryResolver(info)), lore);
    }

    private ItemStack targetItem(final Snapshot s) {
        if (s == null || s.target() == null) {
            return this.item(Material.COMPASS, this.m().get("gui-target-none"), List.of(this.m().get("gui-target-none-lore")));
        }
        final Discovery d = this.service().tree().get(s.target()).orElse(null);
        if (d == null) {
            return this.item(Material.COMPASS, this.m().get("gui-target-none"), List.of());
        }
        final long cost = this.service().tree().pathTo(d.id(), s.discoverySet()).stream().mapToLong(Discovery::cost).sum();
        return this.item(Material.RECOVERY_COMPASS, this.m().get("gui-target-current", Placeholder.unparsed("name", d.name())),
            List.of(this.m().get("gui-target-lore", Placeholder.unparsed("cost", Long.toString(cost)),
                Placeholder.unparsed("eta", this.views.eta(cost - s.points(), s.daily().total())))));
    }

    private ItemStack item(final Material material, final Component name, final List<Component> lore) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.lore(lore.stream().map(l -> l.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    private static void glow(final ItemStack item) {
        final ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }

    private static Material material(final String name, final Material fallback) {
        final Material m = name == null ? null : Material.matchMaterial(name);
        return m == null || !m.isItem() || m.isAir() ? fallback : m;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        final int slot = event.getRawSlot();
        if (slot == holder.closeSlot) {
            player.closeInventory();
            return;
        }
        if (slot == holder.rankingSlot) {
            player.closeInventory();
            player.performCommand("postep ranking");
            return;
        }
        if (slot == holder.statusSlot) {
            player.closeInventory();
            player.performCommand("postep");
            return;
        }
        final String id = holder.slots.get(slot);
        if (id == null) {
            return;
        }
        final Optional<KrajInfo> info = this.service().kraje().getCountryOf(player.getUniqueId());
        if (info.isEmpty()) {
            return;
        }
        final Snapshot s = this.service().snapshot(info.get().id()).orElse(null);
        final Discovery d = this.service().tree().get(id).orElse(null);
        if (s == null || d == null || s.discoverySet().contains(id) || d.era() > s.era()) {
            return;
        }
        this.plugin.command().discover(player, id);
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder) {
                this.open(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(final InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
