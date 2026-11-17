// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.straznik.command.AdminCommand;
import pl.medivalrpg.straznik.command.AuthCommands;
import pl.medivalrpg.straznik.limbo.Restrictions;
import pl.medivalrpg.straznik.log.CommandLogFilter;

/** Every registered name and alias of a password command is hidden in the console; only login/register before login. */
class CommandNamesTest {

    @Test
    void consoleFilterCoversEveryPasswordCommandAlias() {
        final AuthCommands cmds = new AuthCommands(null, null);
        final List<String> withPassword = List.of(cmds.login().getName(), cmds.register().getName(),
            cmds.changePassword().getName(), new AdminCommand(null).build().getName());
        assertEquals(List.of("login", "register", "zmienhaslo", "straznik"), withPassword);
        for (final List<String> names : List.of(withPassword, StraznikPlugin.LOGIN_ALIASES, StraznikPlugin.REGISTER_ALIASES,
            StraznikPlugin.CHANGE_ALIASES, StraznikPlugin.ADMIN_ALIASES)) {
            for (final String n : names) {
                assertTrue(StraznikPlugin.HIDDEN_IN_CONSOLE.contains(n), n);
                for (final String typed : Set.of(n, n.toUpperCase(Locale.ROOT), "straznik:" + n, "STRAZNIK:" + n)) {
                    assertTrue(CommandLogFilter.hides("Gracz issued server command: /" + typed + " Tajne1 Tajne1",
                        StraznikPlugin.HIDDEN_IN_CONSOLE), typed);
                    assertTrue(CommandLogFilter.hides("Command exception: /" + typed + " Tajne1", StraznikPlugin.HIDDEN_IN_CONSOLE), typed);
                }
            }
        }
        // no password: stays visible
        assertEquals("wyloguj", cmds.logout().getName());
        for (final String n : List.of("wyloguj", "logout", "kraj", "msg")) {
            assertFalse(StraznikPlugin.HIDDEN_IN_CONSOLE.contains(n), n);
        }
    }

    @Test
    void beforeLoginOnlyLoginAndRegister() {
        assertEquals(Set.of("login", "l", "log", "zaloguj", "register", "reg", "zarejestruj"), StraznikPlugin.LOGIN_COMMANDS);
        assertTrue(StraznikPlugin.LOGIN_COMMANDS.contains(Restrictions.root("/Straznik:LOGIN x")));
        assertFalse(StraznikPlugin.LOGIN_COMMANDS.contains(Restrictions.root("/zmienhaslo a b")));
        assertFalse(StraznikPlugin.LOGIN_COMMANDS.contains(Restrictions.root("/straznik")));
    }
}
