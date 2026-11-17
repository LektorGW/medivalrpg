// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.help;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.config.Messages;
import pl.medivalrpg.menu.gui.ItemFactory;
import pl.medivalrpg.menu.gui.MenuHolder;
import pl.medivalrpg.menu.gui.MenuService;
import pl.medivalrpg.menu.integration.PluginHooks;
import pl.medivalrpg.menu.integration.PluginPermissions;

/**
 * Help center: chat pages ({@code /pomoc …}) and the generated category GUI ({@code help:<kategoria>} buttons in
 * {@code menus.yml}). Visibility follows the same plugin and permission rules as the menus. Main thread only.
 */
public final class HelpService {

    /** Menu with the category buttons (menus.yml), target of "« Wróć" in a category GUI. */
    public static final String HUB_MENU = "pomoc";
    static final int ROWS = 6;
    static final List<Integer> CONTENT = slots(10, 16, 19, 25, 28, 34, 37, 43);
    static final int SLOT_HEADER = 4;
    static final int SLOT_BACK = 45;
    static final int SLOT_PREV = 47;
    static final int SLOT_GUIDE = 48;
    static final int SLOT_CHAT = 49;
    static final int SLOT_SEARCH = 50;
    static final int SLOT_NEXT = 51;
    static final int SLOT_CLOSE = 53;
    static final int LORE_WIDTH = 36;

    private final Supplier<HelpCatalog> catalog;
    private final Supplier<Messages> messages;
    private final Supplier<MenuSettings> settings;
    private final ItemFactory items;
    private final MenuService menus;

    public HelpService(final Supplier<HelpCatalog> catalog, final Supplier<Messages> messages,
                       final Supplier<MenuSettings> settings, final ItemFactory items, final MenuService menus) {
        this.catalog = catalog;
        this.messages = messages;
        this.settings = settings;
        this.items = items;
        this.menus = menus;
    }

    public HelpCatalog catalog() {
        return this.catalog.get();
    }

    // ------------------------------------------------------------------ visibility

    static Predicate<String> plugins() {
        return pl.medivalrpg.menu.integration.Requirements::met;
    }

    static Predicate<String> permissions(final CommandSender sender) {
        return sender instanceof Player p ? perm -> PluginPermissions.has(p, perm) : perm -> sender.hasPermission(perm);
    }

    public HelpChat chat(final CommandSender sender) {
        return new HelpChat(this.messages.get(), plugins(), permissions(sender));
    }

    /** Ids of categories the sender may open (tab completion). */
    public List<String> categoryIds(final CommandSender sender) {
        return this.catalog().visibleCategories(plugins(), permissions(sender)).stream().map(HelpCatalog.Category::id).toList();
    }

    private HelpCatalog.Category visible(final CommandSender sender, final String id) {
        final HelpCatalog.Category c = this.catalog().find(id);
        return c != null && c.visible(plugins(), permissions(sender)) ? c : null;
    }

    // ------------------------------------------------------------------ chat

    public void sendOverview(final CommandSender sender) {
        this.chat(sender).overview(this.catalog()).forEach(sender::sendMessage);
    }

    /** {@code false} when there is no such (visible) category. */
    public boolean sendCategory(final CommandSender sender, final String id) {
        final HelpCatalog.Category c = this.visible(sender, id);
        if (c == null) {
            sender.sendMessage(this.messages.get().get("help-unknown-category", Placeholder.unparsed("category", id)));
            return false;
        }
        this.chat(sender).category(this.catalog(), c).forEach(sender::sendMessage);
        return true;
    }

    public void sendSearch(final CommandSender sender, final String query) {
        this.chat(sender).search(this.catalog(), query).forEach(sender::sendMessage);
    }

    // ------------------------------------------------------------------ GUI

    /** Opens page {@code page} (0-based, clamped) of a category GUI. */
    public boolean open(final Player player, final String id, final int page) {
        final HelpCatalog.Category c = this.visible(player, id);
        if (c == null) {
            player.sendMessage(this.messages.get().get("help-unknown-category", Placeholder.unparsed("category", id)));
            this.menus.sound(player, this.settings.get().soundError);
            return false;
        }
        player.openInventory(this.render(player, c, page).getInventory());
        return true;
    }

    MenuHolder render(final Player player, final HelpCatalog.Category c, final int requestedPage) {
        final Messages m = this.messages.get();
        final HelpChat chat = this.chat(player);
        final List<HelpCatalog.Entry> entries = c.visibleEntries(plugins(), permissions(player));
        final int pages = pageCount(entries.size(), CONTENT.size());
        final int page = Math.max(0, Math.min(requestedPage, pages - 1));
        final TagResolver r = TagResolver.resolver(
            Placeholder.component("category", chat.name(c)),
            Placeholder.unparsed("page", Integer.toString(page + 1)),
            Placeholder.unparsed("pages", Integer.toString(pages)),
            Placeholder.unparsed("count", Integer.toString(entries.size())));
        final MenuHolder holder = new MenuHolder("pomoc:" + c.id(), ROWS * 9,
            m.parse(m.raw(pages > 1 ? "help-gui-title-paged" : "help-gui-title"), r));
        final String border = m.raw("help-gui-border");
        for (int slot = 0; slot < ROWS * 9; slot++) {
            final int row = slot / 9;
            final int col = slot % 9;
            if (row == 0 || row == ROWS - 1 || col == 0 || col == 8) {
                holder.set(slot, this.items.decoration(border), null);
            }
        }

        final List<Component> headerLore = new ArrayList<>();
        for (final String line : wrap(c.description(), LORE_WIDTH)) {
            headerLore.add(Messages.item(m.raw("help-gui-text"), TagResolver.resolver(r, Placeholder.unparsed("text", line))));
        }
        headerLore.addAll(Messages.itemLines(m.rawList("help-gui-header-lore"), r));
        holder.set(SLOT_HEADER, this.items.icon(c.icon(), Messages.item(m.raw("help-gui-header-name"), r), headerLore,
            true, null), (p, click) -> this.toChat(p, c));

        final int from = page * CONTENT.size();
        for (int i = 0; i < CONTENT.size() && from + i < entries.size(); i++) {
            final HelpCatalog.Entry e = entries.get(from + i);
            holder.set(CONTENT.get(i), this.entryIcon(m, e), (p, click) -> this.clickEntry(p, c, e));
        }
        if (entries.isEmpty()) {
            holder.set(CONTENT.get(CONTENT.size() / 2), this.items.icon("BARRIER",
                Messages.item(m.raw("help-gui-empty"), r), List.of(), false, null), null);
        }

        holder.set(SLOT_BACK, this.items.icon("ARROW", Messages.item(m.raw("help-gui-back"), r), List.of(), false, null),
            (p, click) -> {
                this.menus.sound(p, this.settings.get().soundClick);
                this.menus.open(p, HUB_MENU);
            });
        if (page > 0) {
            holder.set(SLOT_PREV, this.items.icon("SPECTRAL_ARROW", Messages.item(m.raw("help-gui-prev"), r), List.of(),
                false, null), (p, click) -> this.turn(p, c, page - 1));
        }
        if (page + 1 < pages) {
            holder.set(SLOT_NEXT, this.items.icon("SPECTRAL_ARROW", Messages.item(m.raw("help-gui-next"), r), List.of(),
                false, null), (p, click) -> this.turn(p, c, page + 1));
        }
        if (!c.guide().isEmpty()) {
            final List<Component> lore = new ArrayList<>();
            int n = 1;
            for (final String step : c.guide()) {
                boolean first = true;
                for (final String line : wrap(plain(step), LORE_WIDTH)) {
                    lore.add(Messages.item(m.raw(first ? "help-gui-guide-step" : "help-gui-text"), TagResolver.resolver(r,
                        Placeholder.unparsed("number", Integer.toString(n)), Placeholder.unparsed("text", line))));
                    first = false;
                }
                n++;
            }
            lore.addAll(Messages.itemLines(m.rawList("help-gui-guide-lore"), r));
            holder.set(SLOT_GUIDE, this.items.icon("WRITABLE_BOOK", Messages.item(m.raw("help-gui-guide-name"), r), lore,
                true, null), (p, click) -> this.toChat(p, c));
        }
        holder.set(SLOT_CHAT, this.items.icon("OAK_SIGN", Messages.item(m.raw("help-gui-chat-name"), r),
            Messages.itemLines(m.rawList("help-gui-chat-lore"), r), false, null), (p, click) -> this.toChat(p, c));
        holder.set(SLOT_SEARCH, this.items.icon("SPYGLASS", Messages.item(m.raw("help-gui-search-name"), r),
            Messages.itemLines(m.rawList("help-gui-search-lore"), r), false, null), (p, click) -> {
                p.closeInventory();
                this.suggest(p, HelpChat.COMMAND + " szukaj ");
            });
        holder.set(SLOT_CLOSE, this.items.icon("BARRIER", Messages.item(m.raw("close-name"), r), List.of(), false, null),
            (p, click) -> p.closeInventory());
        return holder;
    }

    private org.bukkit.inventory.ItemStack entryIcon(final Messages m, final HelpCatalog.Entry e) {
        final TagResolver r = TagResolver.resolver(Placeholder.unparsed("command", e.command()),
            Placeholder.unparsed("rank", e.rank()));
        final List<Component> lore = new ArrayList<>();
        for (final String line : wrap(e.description(), LORE_WIDTH)) {
            lore.add(Messages.item(m.raw("help-gui-text"), TagResolver.resolver(r, Placeholder.unparsed("text", line))));
        }
        if (!e.rank().isBlank()) {
            lore.add(Messages.item(m.raw("help-gui-entry-rank"), r));
        }
        lore.addAll(Messages.itemLines(m.rawList(e.run() ? "help-gui-entry-run" : "help-gui-entry-suggest"), r));
        return this.items.icon(e.run() ? m.raw("help-gui-entry-icon-run") : m.raw("help-gui-entry-icon"),
            Messages.item(m.raw("help-gui-entry-name"), r), lore, false, null);
    }

    private void turn(final Player p, final HelpCatalog.Category c, final int page) {
        this.menus.sound(p, this.settings.get().soundClick);
        this.open(p, c.id(), page);
    }

    private void toChat(final Player p, final HelpCatalog.Category c) {
        this.menus.sound(p, this.settings.get().soundClick);
        p.closeInventory();
        this.sendCategory(p, c.id());
    }

    private void clickEntry(final Player p, final HelpCatalog.Category c, final HelpCatalog.Entry e) {
        // Re-check: the plugin may have been disabled or the permission revoked while the GUI was open.
        if (this.visible(p, c.id()) == null || !c.visibleEntries(plugins(), permissions(p)).contains(e)) {
            p.sendMessage(this.messages.get().get("no-access"));
            p.closeInventory();
            return;
        }
        this.menus.sound(p, this.settings.get().soundClick);
        p.closeInventory();
        if (e.run()) {
            p.performCommand(e.insert().startsWith("/") ? e.insert().substring(1) : e.insert());
        } else {
            p.sendMessage(this.chat(p).entryLine("help-line", e, null));
            this.suggest(p, e.insert());
        }
    }

    private void suggest(final Player p, final String text) {
        final Messages m = this.messages.get();
        final Component button = m.get("suggest-button", Placeholder.unparsed("command", text.strip()))
            .clickEvent(ClickEvent.suggestCommand(text))
            .hoverEvent(HoverEvent.showText(m.get("suggest-hover")));
        p.sendMessage(m.get("suggest-line", Placeholder.component("button", button)));
    }

    // ------------------------------------------------------------------ pure helpers

    static int pageCount(final int entries, final int perPage) {
        return Math.max(1, (entries + perPage - 1) / perPage);
    }

    /** Guide text without the backticks around commands (GUI lore). */
    static String plain(final String line) {
        final StringBuilder out = new StringBuilder();
        for (final HelpSearch.Segment s : HelpSearch.segments(line)) {
            out.append(s.text());
        }
        return out.toString();
    }

    /** Word wrap for item lore; words longer than {@code width} stay whole. */
    static List<String> wrap(final String text, final int width) {
        final List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        final StringBuilder line = new StringBuilder();
        for (final String word : text.strip().split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private static List<Integer> slots(final int... ranges) {
        final List<Integer> out = new ArrayList<>();
        for (int i = 0; i < ranges.length; i += 2) {
            for (int s = ranges[i]; s <= ranges[i + 1]; s++) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }
}
