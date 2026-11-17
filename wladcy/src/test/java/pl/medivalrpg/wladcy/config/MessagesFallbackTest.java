// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.wladcy.testutil.TestSupport;

class MessagesFallbackTest {
    @Test void oldProductionConfigGetsEveryNewSupplyMessageFromJarDefaults() {
        var defaults = TestSupport.yaml(); var old = new YamlConfiguration();
        old.set("messages.prefix", "<gray>[Dwór]</gray> ");
        old.set("messages.quest-unknown", "Mój własny tekst");
        old.setDefaults(defaults);
        var messages = new Messages(old, Logger.getAnonymousLogger());
        for (String key : List.of("quest-deny-need-changed", "quest-deny-supply-unavailable", "quest-deny-bank-unavailable", "quest-supply-required",
            "quest-supply-pending", "quest-supply-blocked", "quest-supply-unavailable", "quest-supply-payment", "quest-supply-audit",
            "quest-acceptance-pending", "quest-acceptance-line")) {
            assertTrue(messages.has(key)); assertEquals(defaults.getString("messages." + key), messages.raw(key));
            assertFalse(messages.plain(key, Messages.p("id", "abc"), Messages.p("reason", "test")).contains(key));
        }
        assertEquals("Mój własny tekst", messages.raw("quest-unknown"));
        assertTrue(messages.plain("quest-supply-pending", Messages.p("id", "abc"), Messages.p("reason", "test")).startsWith("[Dwór]"));
    }

    @Test void entirelyMissingSectionFallsBackAndCustomNewMessageStillWins() {
        var old = new YamlConfiguration(); old.setDefaults(TestSupport.yaml());
        assertTrue(new Messages(old, Logger.getAnonymousLogger()).has("quest-deny-need-changed"));
        old.set("messages.quest-supply-pending", "Własna dostawa <id>");
        var messages = new Messages(old, Logger.getAnonymousLogger());
        assertEquals("Własna dostawa xyz", messages.plain("quest-supply-pending", Messages.p("id", "xyz")));
    }
}
