// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.config.Messages;

class HelpBookTest {

    private static Messages messages() {
        return new Messages(YamlConfiguration.loadConfiguration(new InputStreamReader(
            HelpBookTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)), Logger.getAnonymousLogger());
    }

    @Test
    void everyEntryHasTexts() {
        final Messages m = messages();
        for (final HelpBook.Entry e : HelpBook.ENTRIES) {
            assertTrue(m.has("help-entries." + e.id() + ".short"), e.id());
            assertTrue(m.has("help-entries." + e.id() + ".description"), e.id());
        }
        for (final HelpBook.Category c : HelpBook.Category.values()) {
            assertTrue(m.has("help-categories." + c.key()), c.key());
        }
    }

    @Test
    void adminPageOnlyForAdmins() {
        assertEquals(3, HelpBook.pages(false).size());
        assertEquals(4, HelpBook.pages(true).size());
        assertEquals(3, HelpBook.resolvePage(HelpBook.pages(false), "99"));
        assertEquals(2, HelpBook.resolvePage(HelpBook.pages(false), "handel"));
        assertEquals(1, HelpBook.resolvePage(HelpBook.pages(false), "xyz"));
    }

    @Test
    void renderProducesLines() {
        final var lines = HelpBook.render(messages(), true, "4");
        assertTrue(lines.size() > 5);
    }
}
