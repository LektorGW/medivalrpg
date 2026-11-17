// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;

/**
 * Runtime check of the requirement tokens of buttons, menus, books, guide sections and help entries.
 *
 * <ul>
 *   <li>{@code requires-plugin: Firmy} → token {@code Firmy}: the plugin is installed and enabled;</li>
 *   <li>{@code requires-command: "firma kredyt"} → token {@code /firma kredyt}: the literal command path exists in the
 *       server's live command tree (Brigadier, incl. plugin.yml commands wrapped by Paper).</li>
 * </ul>
 * Main thread only.
 */
public final class Requirements {

    /** Prefix of a command token in the requirement lists. */
    public static final String COMMAND_PREFIX = "/";

    private static volatile CommandDispatcher<?> captured;
    private static volatile Method serverHandle;
    private static volatile Method commandsHandle;
    private static volatile Method dispatcherHandle;
    private static volatile boolean reflectionFailed;
    private static volatile Logger logger;

    private Requirements() {
    }

    /** Token for {@code requires-command}: {@code "/firma kredyt"} (normalised). */
    public static String commandToken(final String path) {
        return COMMAND_PREFIX + String.join(" ", CommandPaths.words(path));
    }

    public static boolean isCommandToken(final String token) {
        return token != null && token.startsWith(COMMAND_PREFIX);
    }

    /** Plugin names only (command tokens removed), e.g. for messages and tests. */
    public static List<String> plugins(final List<String> tokens) {
        return tokens.stream().filter(t -> !isCommandToken(t)).toList();
    }

    public static boolean met(final String token) {
        if (isCommandToken(token)) {
            return commandExists(token.substring(COMMAND_PREFIX.length()));
        }
        return PluginHooks.isEnabled(token);
    }

    /** Called from the Menu's own {@code LifecycleEvents.COMMANDS} handler (fallback tree). */
    public static void capture(final CommandDispatcher<?> dispatcher, final Logger log) {
        captured = dispatcher;
        logger = log;
    }

    public static boolean commandExists(final String path) {
        final List<String> words = CommandPaths.words(path);
        if (words.isEmpty()) {
            return false;
        }
        final CommandNode<?> root = liveRoot();
        if (root != null && CommandPaths.exists(root, path)) {
            return true;
        }
        if (root != null && root.getChild(words.getFirst()) != null) {
            return false; // the root command is in the tree, the sub-command is not
        }
        // A Bukkit command not mirrored in the tree: only the root can be verified.
        final Command bukkit = Bukkit.getCommandMap().getCommand(words.getFirst());
        return bukkit != null;
    }

    /** The server's live dispatcher root (CraftServer#getServer().getCommands().getDispatcher()), else the captured one. */
    static CommandNode<?> liveRoot() {
        if (!reflectionFailed) {
            try {
                final Object craft = Bukkit.getServer();
                if (serverHandle == null) {
                    serverHandle = craft.getClass().getMethod("getServer");
                }
                final Object minecraft = serverHandle.invoke(craft);
                if (commandsHandle == null) {
                    commandsHandle = minecraft.getClass().getMethod("getCommands");
                }
                final Object commands = commandsHandle.invoke(minecraft);
                if (dispatcherHandle == null) {
                    dispatcherHandle = commands.getClass().getMethod("getDispatcher");
                }
                if (dispatcherHandle.invoke(commands) instanceof CommandDispatcher<?> d) {
                    return d.getRoot();
                }
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                reflectionFailed = true;
                final Logger log = logger;
                if (log != null) {
                    log.warning("requires-command: brak dostępu do drzewa komend serwera (" + e
                        + ") – używam drzewa z LifecycleEvents.");
                }
            }
        }
        final CommandDispatcher<?> d = captured;
        return d == null ? null : d.getRoot();
    }
}
