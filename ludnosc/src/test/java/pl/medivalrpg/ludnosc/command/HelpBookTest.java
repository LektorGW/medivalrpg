// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.config.Messages;

class HelpBookTest {

    private static Messages messages() {
        final YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(
            HelpBookTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        return new Messages(y, Logger.getAnonymousLogger());
    }

    @Test
    void everyEntryHasTexts() {
        final Messages m = messages();
        for (final HelpBook.Entry e : HelpBook.ENTRIES) {
            for (final String part : List.of("short", "description", "rank")) {
                assertTrue(m.has("help-entries." + e.id() + "." + part), e.id() + "." + part);
            }
            assertTrue(e.syntax().startsWith("/ludnosc"));
        }
        for (final HelpBook.Category c : HelpBook.Category.values()) {
            assertTrue(m.has("help-categories." + c.key()), c.key());
            assertFalse(HelpBook.entries(c).isEmpty(), c.key());
        }
    }

    @Test
    void adminPageOnlyForAdmins() {
        assertEquals(3, HelpBook.pages(false).size());
        assertEquals(4, HelpBook.pages(true).size());
        final Messages m = messages();
        assertEquals(2, HelpBook.resolvePage(m, HelpBook.pages(false), "budowa"));
        assertEquals(3, HelpBook.resolvePage(m, HelpBook.pages(false), "99"));
        assertEquals(1, HelpBook.resolvePage(m, HelpBook.pages(false), "???"));
        final List<Component> lines = HelpBook.render(m, true, "4");
        final String all = String.join("\n", lines.stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList());
        assertTrue(all.contains("/ludnosc admin dodaj"));
        assertTrue(all.contains("/ludnosc dom zglos"));
    }

    @Test
    void tokensSplitGreedyArguments() {
        assertEquals(List.of("Królestwo", "Północy", "5"), List.of(LudnoscCommandTree.tokens("  Królestwo  Północy 5 ")));
        assertEquals(0, LudnoscCommandTree.tokens("   ").length);
    }
}
