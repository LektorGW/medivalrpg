// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.straznik.limbo.Restrictions;
import pl.medivalrpg.straznik.log.CommandLogFilter;

class CommandParsingTest {

    private static final Set<String> HIDE = Set.of("login", "l", "register", "zmienhaslo", "straznik");

    @Test
    void consoleFilterHidesPasswordCommandsOnly() {
        assertTrue(CommandLogFilter.hides("WorldSteward issued server command: /login Tajne123", HIDE));
        assertTrue(CommandLogFilter.hides("Gracz issued server command: /LOGIN Tajne123", HIDE));
        assertTrue(CommandLogFilter.hides("Gracz issued server command: /straznik:register a a", HIDE));
        assertTrue(CommandLogFilter.hides("Gracz issued server command: /l x", HIDE));
        assertTrue(CommandLogFilter.hides("Gracz issued server command: /zmienhaslo a b", HIDE));
        assertFalse(CommandLogFilter.hides("Gracz issued server command: /lista", HIDE));
        assertFalse(CommandLogFilter.hides("Gracz issued server command: /kraj info", HIDE));
        assertFalse(CommandLogFilter.hides("Gracz joined the game", HIDE));
        assertFalse(CommandLogFilter.hides(null, HIDE));
    }

    @Test
    void commandRootAndPasswordTokens() {
        assertEquals("login", Restrictions.root("/login abc"));
        assertEquals("register", Restrictions.root("/straznik:register a b"));
        assertEquals("l", Restrictions.root("/L"));
        assertEquals("kraj", Restrictions.root("kraj info"));
        assertEquals(2, AuthCommands.tokens("  Haslo!1   Haslo!1 ").length);
        assertEquals("Hasło!1", AuthCommands.tokens("Hasło!1 cos")[0]);
        assertEquals(0, AuthCommands.tokens("   ").length);
    }

    @Test
    void consoleFilterHidesPaperCommandExceptionsAndExecuteRun() {
        // Paper logs "Command exception: /<whole command>" at ERROR when a command handler throws
        assertTrue(CommandLogFilter.hides("Command exception: /login Tajne123", HIDE));
        assertTrue(CommandLogFilter.hides("Command exception: /straznik haslo Gracz Nowe-Haslo1", HIDE));
        assertTrue(CommandLogFilter.hides("Command exception: /STRAZNIK:REGISTER a a", HIDE));
        assertFalse(CommandLogFilter.hides("Command exception: /kraj info", HIDE));
        assertFalse(CommandLogFilter.hides("Gracz: Command exception: /login x", HIDE), "only Paper's own line format");
        // /execute … run <password command>
        assertTrue(CommandLogFilter.hides("Admin issued server command: /execute as @s run zmienhaslo a b", HIDE));
        assertTrue(CommandLogFilter.hides("Admin issued server command: /execute as Gracz RUN straznik:login x", HIDE));
        assertFalse(CommandLogFilter.hides("Admin issued server command: /execute as @s run kraj info", HIDE));
        assertFalse(CommandLogFilter.hides("Admin issued server command: /kraj run", HIDE));
        assertFalse(CommandLogFilter.hides("Admin issued server command: /msg Gracz run lista", HIDE));
    }

    @Test
    void passwordArgumentsNeedEnoughWords() {
        // "/login" + two spaces reaches the argument as whitespace: usage, not an exception logged with the line
        assertNull(AuthCommands.passwords("  ", 1));
        assertNull(AuthCommands.passwords(null, 1));
        assertEquals("Haslo!1", AuthCommands.passwords(" Haslo!1 ", 1)[0]);
        assertNull(AuthCommands.passwords("Haslo!1", 2));
        assertEquals(2, AuthCommands.passwords("Haslo!1  Haslo!1", 2).length);
        assertEquals(0, AuthCommands.passwords("", 0).length);
    }
}
