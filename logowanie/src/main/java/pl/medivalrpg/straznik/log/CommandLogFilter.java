// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.log;

import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * Hides console lines "&lt;nick&gt; issued server command: /login …" (and the other password commands), so passwords
 * never reach {@code logs/latest.log}. Also hides Paper's ERROR line "Command exception: /login …" (logged with the
 * whole command line when a command handler throws) and {@code /execute … run login …}. Attached to the root logger
 * configuration once; after the plugin is disabled it becomes a no-op (log4j has no public way to remove a logger
 * filter).
 */
public final class CommandLogFilter extends AbstractFilter {

    private static final String MARK = " issued server command: /";
    /** Paper (Commands#performCommand) logs every exception of a command with the full command line. */
    private static final String EXCEPTION_MARK = "Command exception: /";
    private static volatile Set<String> sensitive = Set.of();
    private static volatile boolean installed;

    private CommandLogFilter() {
        super(Filter.Result.NEUTRAL, Filter.Result.NEUTRAL);
    }

    /** Sets the command names to hide (lower case, without "/"); installs the filter on first use. */
    public static void install(final Set<String> commands, final Logger logger) {
        sensitive = Set.copyOf(commands);
        if (installed) {
            return;
        }
        try {
            final org.apache.logging.log4j.core.Logger root = (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
            root.addFilter(new CommandLogFilter());
            installed = true;
        } catch (final LinkageError | RuntimeException e) {
            logger.warning("Nie udało się podpiąć filtra konsoli (hasła z /login mogą trafić do logu): " + e);
        }
    }

    public static void disable() {
        sensitive = Set.of();
    }

    /** {@code true} when the console line must be hidden. Visible for tests. */
    public static boolean hides(final String message, final Set<String> commands) {
        if (message == null) {
            return false;
        }
        final int at = message.indexOf(MARK);
        final String line;
        if (at >= 0) {
            line = message.substring(at + MARK.length());
        } else if (message.startsWith(EXCEPTION_MARK)) {
            line = message.substring(EXCEPTION_MARK.length());
        } else {
            return false;
        }
        final String[] words = line.split("\\s+");
        if (commands.contains(name(words[0]))) {
            return true;
        }
        // /execute … run login <hasło>
        for (int i = 1; i < words.length; i++) {
            if ("run".equalsIgnoreCase(words[i - 1]) && commands.contains(name(words[i]))) {
                return true;
            }
        }
        return false;
    }

    /** Command name without the namespace ({@code straznik:login} → {@code login}), lower case. */
    private static String name(final String word) {
        final String cmd = word.toLowerCase(Locale.ROOT);
        final int colon = cmd.indexOf(':');
        return colon >= 0 ? cmd.substring(colon + 1) : cmd;
    }

    @Override
    public Result filter(final LogEvent event) {
        if (event == null || event.getMessage() == null) {
            return Result.NEUTRAL;
        }
        return hides(event.getMessage().getFormattedMessage(), sensitive) ? Result.DENY : Result.NEUTRAL;
    }
}
