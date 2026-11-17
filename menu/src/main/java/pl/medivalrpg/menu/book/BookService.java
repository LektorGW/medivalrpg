// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.book;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.integration.PluginPermissions;
import pl.medivalrpg.menu.integration.Requirements;
import pl.medivalrpg.menu.model.AccessRules;
import pl.medivalrpg.menu.model.BookDef;
import pl.medivalrpg.menu.model.MenuConfig;
import pl.medivalrpg.menu.text.Placeholders;

/**
 * Opens books from {@code menus.yml} with {@link Player#openBook(Book)}. Books, chapters and sections depend on
 * enabled plugins, permissions and existing commands ({@code requires-plugin}, {@code permission},
 * {@code requires-command}).
 */
public final class BookService {

    /** Result of {@link #open}. */
    public enum OpenResult { OPENED, UNKNOWN, NO_ACCESS }

    private final Supplier<MenuConfig> menus;
    private final Supplier<MenuSettings> settings;
    private final Supplier<Placeholders> placeholders;
    private final Logger logger;

    public BookService(final Supplier<MenuConfig> menus, final Supplier<MenuSettings> settings,
                       final Supplier<Placeholders> placeholders, final Logger logger) {
        this.menus = menus;
        this.settings = settings;
        this.placeholders = placeholders;
        this.logger = logger;
    }

    /** @return {@code false} when there is no such book or the player may not read it */
    public boolean open(final Player player, final String bookId) {
        return this.openBook(player, bookId) == OpenResult.OPENED;
    }

    public OpenResult openBook(final Player player, final String bookId) {
        final BookDef def = this.menus.get().book(bookId == null ? null : bookId.toLowerCase(Locale.ROOT));
        if (def == null) {
            return OpenResult.UNKNOWN;
        }
        if (!this.canRead(player, def)) {
            return OpenResult.NO_ACCESS;
        }
        player.openBook(Book.book(Component.text(def.title()), Component.text(def.author()), this.build(player, def)));
        return OpenResult.OPENED;
    }

    /** Pages the player would see ({@code null} when there is no such book or no access). Used by tests/probes. */
    public List<Component> pages(final Player player, final String bookId) {
        final BookDef def = this.menus.get().book(bookId == null ? null : bookId.toLowerCase(Locale.ROOT));
        return def == null || !this.canRead(player, def) ? null : this.build(player, def);
    }

    public boolean canRead(final Player player, final BookDef def) {
        return AccessRules.allows(def.requiresPlugins(), def.permission(), Requirements::met, permitted(player));
    }

    /** Chapters of a chapter index visible to the player, in order (chapter ids, not book ids). */
    public List<String> chapters(final Player player, final String bookId) {
        final MenuConfig cfg = this.menus.get();
        final BookDef index = cfg.book(bookId);
        if (index == null) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        for (final String chapter : index.chapters()) {
            final BookDef def = cfg.book(index.chapterBookId(chapter));
            if (def != null && this.canRead(player, def)) {
                out.add(chapter);
            }
        }
        return out;
    }

    private List<Component> build(final Player player, final BookDef def) {
        final MenuConfig cfg = this.menus.get();
        final TagResolver resolver = this.placeholders.get().resolver(player);
        final MenuSettings s = this.settings.get();
        final Predicate<String> permitted = permitted(player);
        final List<BookBuilder.Link> links = new ArrayList<>();
        for (final String chapter : this.chapters(player, def.id())) {
            final BookDef c = cfg.book(def.chapterBookId(chapter));
            links.add(new BookBuilder.Link(c.name() == null ? c.title() : c.name(), c.description(),
                def.chapterCommand(chapter)));
        }
        final BookBuilder.Link back = this.parentIndex(cfg, def);
        final BookBuilder.Result result = BookBuilder.build(def,
            section -> AccessRules.allows(section.requiresPlugins(), section.permission(), Requirements::met, permitted),
            links, back, text -> MiniMessage.miniMessage().deserialize(text, resolver),
            new BookPaginator.Layout(s.bookLineWidth, s.bookLinesPerPage));
        if (result.truncated()) {
            this.logger.warning("Książka '" + def.id() + "' ma ponad " + BookBuilder.MAX_PAGES + " stron – resztę ucięto.");
        }
        return result.pages();
    }

    /** "« All chapters" for a chapter book (a book listed in another book's {@code chapters}). */
    static BookBuilder.Link parentIndex(final MenuConfig cfg, final BookDef def) {
        for (final BookDef index : cfg.books().values()) {
            for (final String chapter : index.chapters()) {
                if (index.chapterBookId(chapter).equals(def.id())) {
                    return new BookBuilder.Link("<dark_aqua>« Wszystkie rozdziały</dark_aqua>",
                        "Wróć do spisu rozdziałów", "/menu " + index.id());
                }
            }
        }
        return null;
    }

    private static Predicate<String> permitted(final Player player) {
        return permission -> PluginPermissions.has(player, permission);
    }
}
