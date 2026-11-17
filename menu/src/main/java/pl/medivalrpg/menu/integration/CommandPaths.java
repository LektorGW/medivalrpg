// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure check whether a literal command path ({@code "firma kredyt"}) exists in a Brigadier command tree.
 * Used by {@code requires-command}: a button, help entry or guide section of a prepared module stays hidden
 * until the module's jar that registers the command is active, so one {@code menus.yml} works before and after.
 *
 * <p>Rules: every word must be a literal child (aliases with a redirect are followed). When no literal matches and
 * the node has only a greedy string argument (a plugin.yml command wrapped as {@code <args>}), the rest of the path
 * cannot be verified and is accepted. A node with literal children but no matching one means "missing".</p>
 */
public final class CommandPaths {

    private static final int MAX_REDIRECTS = 8;

    private CommandPaths() {
    }

    /** Words of a path: leading slash removed, lower case, blank → empty list. */
    public static List<String> words(final String path) {
        if (path == null) {
            return List.of();
        }
        final String p = path.strip().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
        return p.isEmpty() ? List.of() : List.of(p.split("\\s+"));
    }

    /** {@code true} when the path is a plain literal path (no {@code <arg>}, {@code [opt]} or {@code a|b}). */
    public static boolean literal(final String path) {
        final List<String> words = words(path);
        return !words.isEmpty() && words.stream().allMatch(w -> w.matches("[a-z0-9_:.-]+"));
    }

    public static boolean exists(final CommandNode<?> root, final String path) {
        final List<String> words = words(path);
        if (root == null || words.isEmpty()) {
            return false;
        }
        CommandNode<?> node = root;
        for (final String word : words) {
            final CommandNode<?> next = literalChild(node, word);
            if (next != null) {
                node = next;
                continue;
            }
            return node != root && onlyArguments(follow(node));
        }
        return true;
    }

    private static CommandNode<?> literalChild(final CommandNode<?> start, final String word) {
        CommandNode<?> node = start;
        for (int i = 0; i <= MAX_REDIRECTS && node != null; i++) {
            final CommandNode<?> child = node.getChild(word);
            if (child instanceof LiteralCommandNode<?>) {
                return child;
            }
            if (node.getRedirect() == null || node.getRedirect() == node) {
                return null;
            }
            node = node.getRedirect();
        }
        return null;
    }

    private static CommandNode<?> follow(final CommandNode<?> start) {
        CommandNode<?> node = start;
        for (int i = 0; i < MAX_REDIRECTS && node.getChildren().isEmpty() && node.getRedirect() != null; i++) {
            node = node.getRedirect();
        }
        return node;
    }

    private static boolean onlyArguments(final CommandNode<?> node) {
        final List<CommandNode<?>> children = new ArrayList<>(node.getChildren());
        return !children.isEmpty() && children.stream().noneMatch(c -> c instanceof LiteralCommandNode<?>)
            && children.stream().anyMatch(CommandPaths::greedy);
    }

    /** A greedy string argument ({@code <polecenie>}, Paper's wrapper of plugin.yml commands): sub-commands unknown. */
    private static boolean greedy(final CommandNode<?> node) {
        return node instanceof ArgumentCommandNode<?, ?> a && a.getType() instanceof StringArgumentType t
            && t.getType() == StringArgumentType.StringType.GREEDY_PHRASE;
    }
}
