// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.model;

import java.util.List;

/**
 * A book from the {@code books} section of {@code menus.yml}, opened with {@code book:<id>}.
 * The first page(s) hold the intro and a clickable table of contents; each visible section starts on a new page.
 *
 * <p>A book with {@code chapters} is a chapter index (Poradnik): every chapter is its own book with the id
 * {@code <id>-<chapter>}, listed in the table of contents and opened with {@code /menu <id> <chapter>}.</p>
 *
 * @param id              book id
 * @param title           book title (plain text)
 * @param author          author line
 * @param intro           MiniMessage paragraphs shown above the table of contents
 * @param tocTitle        MiniMessage header of the table of contents
 * @param sections        sections in order
 * @param name            MiniMessage entry in the chapter index of the parent book ({@code null} = title)
 * @param description     plain hover text of that entry ({@code null} = none)
 * @param requiresPlugins requirement tokens of the whole book (plugins and {@code /command} paths)
 * @param permission      permission required to open the whole book ({@code null} = everyone)
 * @param chapters        chapter ids (books {@code <id>-<chapter>}) listed after the sections
 */
public record BookDef(String id, String title, String author, List<String> intro, String tocTitle,
                      List<SectionDef> sections, String name, String description, List<String> requiresPlugins,
                      String permission, List<String> chapters) {

    public BookDef {
        intro = List.copyOf(intro);
        sections = List.copyOf(sections);
        requiresPlugins = requiresPlugins == null ? List.of() : List.copyOf(requiresPlugins);
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
    }

    public BookDef(final String id, final String title, final String author, final List<String> intro,
                   final String tocTitle, final List<SectionDef> sections) {
        this(id, title, author, intro, tocTitle, sections, null, null, List.of(), null, List.of());
    }

    /** Book id of a chapter: {@code poradnik} + {@code kolej} → {@code poradnik-kolej}. */
    public String chapterBookId(final String chapter) {
        return this.id + "-" + chapter;
    }

    /** Command that opens a chapter: {@code /menu poradnik kolej}. */
    public String chapterCommand(final String chapter) {
        return "/menu " + this.id + " " + chapter;
    }

    /**
     * @param id              section key
     * @param title           MiniMessage title (TOC entry and header of the section)
     * @param requiresPlugins requirement tokens: plugins that must be enabled and {@code /command} paths that must
     *                        exist ({@code requires-plugin}, {@code requires-command})
     * @param permission      section shown only with this permission ({@code null} = everyone)
     * @param paragraphs      MiniMessage paragraphs; {@code ""} = empty line
     */
    public record SectionDef(String id, String title, List<String> requiresPlugins, String permission,
                             List<String> paragraphs) {
    }
}
