// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.config.Messages;

class HelpBookTest {

    private static YamlConfiguration yaml() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            HelpBookTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    private static Messages messages() {
        return new Messages(yaml(), Logger.getAnonymousLogger());
    }

    private static String plain(final Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static String clickValue(final Component c) {
        final ClickEvent<?> click = c.clickEvent();
        assertNotNull(click, plain(c));
        return ((ClickEvent.Payload.Text) click.payload()).value();
    }

    /** First descendant (pre-order, root excluded) whose text contains {@code text}. */
    private static Component find(final Component root, final String text) {
        for (final Component child : root.children()) {
            if (plain(child).contains(text)) {
                return child;
            }
            final Component deeper = find(child, text);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    @Test
    void everyEntryHasTextsInDefaultConfig() {
        final YamlConfiguration yaml = yaml();
        for (final HelpBook.Entry e : HelpBook.ENTRIES) {
            for (final String field : List.of("short", "description", "rank")) {
                assertTrue(yaml.isString("messages.help-entries." + e.id() + "." + field), e.id() + "." + field);
            }
            assertTrue(e.syntax().startsWith(e.command().trim()), e.id());
        }
        for (final HelpBook.Category c : HelpBook.Category.values()) {
            assertTrue(yaml.isString("messages.help-categories." + c.key()), c.key());
            assertFalse(HelpBook.entries(c).isEmpty(), c.key());
        }
    }

    @Test
    void firstPageLayout() {
        final List<Component> lines = HelpBook.render(messages(), false, null);
        // header + 5 commands (Teren) + nav + tabs
        assertEquals(8, lines.size());
        assertEquals("Kraje — pomoc: Teren (strona 1/7)", plain(lines.get(0)));
        final Component zaloz = lines.get(1);
        assertEquals("/kraj zaloz <nazwa> — załóż kraj", plain(zaloz));
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, zaloz.clickEvent().action());
        assertEquals("/kraj zaloz ", clickValue(zaloz));
        final HoverEvent<?> hover = zaloz.hoverEvent();
        assertNotNull(hover);
        final String hoverText = plain((Component) hover.value());
        assertTrue(hoverText.startsWith("/kraj zaloz <nazwa>\nZakłada nowy kraj."), hoverText);
        assertTrue(hoverText.contains("Wymaga: gracz bez kraju"), hoverText);
        assertTrue(plain(lines.get(2)).startsWith("/kraj zajmij — "));
        assertTrue(plain((Component) lines.get(2).hoverEvent().value()).contains("Wymaga: Doradca+"));

        final Component nav = lines.get(6);
        assertEquals("« poprzednia | następna »", plain(nav));
        final Component prev = find(nav, "poprzednia");
        assertNull(prev.clickEvent(), "no previous page on page 1");
        final Component next = find(nav, "następna");
        assertEquals("/kraj pomoc 2", clickValue(next));

        assertEquals("Teren · Ludzie · Wygląd · Dyplomacja · Skarbiec · Informacje · Czat", plain(lines.get(7)));
    }

    @Test
    void adminPageOnlyWithPermission() {
        assertEquals(7, HelpBook.pages(false).size());
        assertEquals(8, HelpBook.pages(true).size());
        final List<Component> lastForPlayer = HelpBook.render(messages(), false, "99");
        assertTrue(plain(lastForPlayer.get(0)).contains("Czat (strona 7/7)"));
        final List<Component> admin = HelpBook.render(messages(), true, "8");
        assertTrue(plain(admin.get(0)).contains("Admin (strona 8/8)"));
        assertEquals(1 + 19 + 2, admin.size()); // 19: + admin-flaga (chorągwie, 2026-10-29)
        assertTrue(plain(admin.get(admin.size() - 1)).endsWith("Czat · Admin"));
        assertFalse(plain(HelpBook.render(messages(), false, "admin").get(0)).contains("Admin"));
    }

    @Test
    void pageByCategoryName() {
        final Messages m = messages();
        final List<HelpBook.Category> pages = HelpBook.pages(false);
        assertEquals(4, HelpBook.resolvePage(m, pages, "dyplomacja"));
        assertEquals(3, HelpBook.resolvePage(m, pages, "Wygląd"));
        assertEquals(3, HelpBook.resolvePage(m, pages, "wyglad"));
        assertEquals(1, HelpBook.resolvePage(m, pages, "0"));
        assertEquals(1, HelpBook.resolvePage(m, pages, "cokolwiek"));
        assertEquals(2, HelpBook.resolvePage(m, pages, " 2 "));
        assertEquals(5, HelpBook.resolvePage(m, pages, "skarbiec"));
    }

    @Test
    void missingEntriesFallBackToJarDefaults() {
        final YamlConfiguration server = new YamlConfiguration();
        server.set("messages.prefix", "<gold>[Kraje]</gold> ");
        server.set("messages.help-entries.zaloz.short", "własny opis");
        server.setDefaults(yaml());
        final Messages m = new Messages(server, Logger.getAnonymousLogger());
        final List<Component> lines = HelpBook.render(m, false, "1");
        assertEquals("/kraj zaloz <nazwa> — własny opis", plain(lines.get(1)));
        assertEquals("/kraj zajmij — zajmij chunk, na którym stoisz", plain(lines.get(2)));
    }
}
