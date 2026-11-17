// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.wladcy.quest.QuestRules;
import pl.medivalrpg.wladcy.quest.QuestType;
import pl.medivalrpg.wladcy.testutil.TestSupport;

/** Every help entry and every message key built at runtime (quest-type-*, quest-deny-*) exists in config.yml. */
class HelpBooksTest {

    private static final ConfigurationSection MESSAGES = TestSupport.yaml().getConfigurationSection("messages");

    private static void check(final String book, final HelpBook help) {
        for (final String category : List.of("zlecenia", "reputacja", "admin", "rozmowa")) {
            if (help.entries().stream().anyMatch(e -> e.category().equals(category))) {
                assertTrue(MESSAGES.isString(book + ".categories." + category), book + " category " + category);
            }
        }
        assertTrue(MESSAGES.isString(book + ".title"));
        for (final HelpBook.Entry e : help.entries()) {
            for (final String part : List.of("short", "description", "rank")) {
                assertTrue(MESSAGES.isString(book + ".entries." + e.id() + "." + part), book + ": " + e.id() + "." + part);
            }
            assertTrue(e.command().startsWith("/"));
        }
    }

    @Test
    void helpBooksComplete() {
        check("help-wladca", WladcaCommand.HELP);
        check("help-zlecenia", ZleceniaCommand.HELP);
        assertEquals(List.of("zlecenia", "reputacja"), ZleceniaCommand.HELP.pages(false), "players see no admin page");
        assertEquals(List.of("zlecenia", "reputacja", "admin"), ZleceniaCommand.HELP.pages(true));
        assertEquals("reputacja", ZleceniaCommand.HELP.pages(false).get(1), "/reputacja pomoc opens page 2");
    }

    @Test
    void runtimeMessageKeysExist() {
        for (final QuestType t : QuestType.values()) {
            assertTrue(MESSAGES.isString("quest-type-" + t.key()), t.key());
            assertTrue(MESSAGES.isString("quest-task-" + t.key()), t.key());
        }
        for (final QuestRules.Denial d : QuestRules.Denial.values()) {
            assertTrue(MESSAGES.isString("quest-deny-" + d.key()), d.key());
        }
    }
}
