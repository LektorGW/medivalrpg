// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

/**
 * Reads the interactions defined in the Interakcje plugin's {@code config.yml} (section {@code interactions})
 * at runtime, so the menu always shows what the server really has. Keys follow
 * {@code pl.medivalrpg.interakcje.InteractionLoader}: {@code enabled}, {@code name}, {@code description},
 * {@code command}, {@code aliases}, {@code icon}, {@code consent}, {@code target} (required|optional), {@code range}.
 */
public final class InterakcjeHook {

    public static final String PLUGIN = "Interakcje";
    public static final String PERM_USE = "interakcje.use";

    /**
     * @param id             interaction id (permission {@code interakcje.<id>})
     * @param name           MiniMessage name
     * @param description    MiniMessage description
     * @param label          command label to run (ASCII alias preferred when the main command has Polish letters)
     * @param icon           Material name
     * @param consent        target must accept
     * @param optionalTarget can be used without a target (to everyone nearby)
     * @param range          max distance to the target
     */
    public record InteractionEntry(String id, String name, String description, String label, String icon,
                                   boolean consent, boolean optionalTarget, double range) {

        public String permission() {
            return "interakcje." + this.id;
        }
    }

    /**
     * A dance or an emote ({@code dances.list.<id>} / {@code emotes.list.<id>} in Interakcje's config).
     *
     * @param dance          {@code true} for dances, {@code false} for emotes
     * @param label          command of the group ({@code taniec}, {@code emotka}); run as {@code /<label> <id>}
     * @param permission     group permission ({@code interakcje.dance} / {@code interakcje.emote})
     * @param optionalTarget {@code /emotka <id> <gracz>} turns the player to a target
     */
    public record MotionEntry(boolean dance, String id, String name, String description, String icon, String label,
                              String permission, boolean optionalTarget) {

        public String command() {
            return this.label + " " + this.id;
        }
    }

    private InterakcjeHook() {
    }

    /** Dances, then emotes of the running Interakcje plugin; empty when it is missing or disabled. */
    public static List<MotionEntry> motions() {
        final Plugin plugin = PluginHooks.enabled(PLUGIN);
        if (plugin == null) {
            return List.of();
        }
        try {
            return parseMotions(plugin.getConfig());
        } catch (final RuntimeException e) {
            plugin.getLogger().fine("Menu: nie można odczytać tańców i emotek: " + e);
            return List.of();
        }
    }

    /**
     * Parses {@code dances} and {@code emotes} like {@code pl.medivalrpg.interakcje.MotionGroup}: group keys
     * {@code enabled}, {@code command}, {@code aliases}, {@code list.<id>} with {@code enabled}, {@code name},
     * {@code description}, {@code icon}, {@code target} (none|optional). Disabled groups/entries are skipped.
     */
    public static List<MotionEntry> parseMotions(final ConfigurationSection root) {
        final List<MotionEntry> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        group(out, root.getConfigurationSection("dances"), true, "taniec", "interakcje.dance");
        group(out, root.getConfigurationSection("emotes"), false, "emotka", "interakcje.emote");
        return out;
    }

    private static void group(final List<MotionEntry> out, final ConfigurationSection g, final boolean dance,
                              final String defaultCommand, final String permission) {
        if (g == null || !g.getBoolean("enabled", true)) {
            return;
        }
        String label = clean(g.getString("command", defaultCommand));
        if (label == null) {
            label = defaultCommand;
        }
        if (!isAscii(label)) {
            for (final String alias : g.getStringList("aliases")) {
                final String a = clean(alias);
                if (a != null && isAscii(a)) {
                    label = a;
                    break;
                }
            }
        }
        final ConfigurationSection list = g.getConfigurationSection("list");
        if (list == null) {
            return;
        }
        for (final String key : list.getKeys(false)) {
            final ConfigurationSection e = list.getConfigurationSection(key);
            final String id = clean(key);
            if (e == null || id == null || !e.getBoolean("enabled", true)) {
                continue;
            }
            out.add(new MotionEntry(dance, id, e.getString("name", id), e.getString("description", ""),
                e.getString("icon", dance ? "NOTE_BLOCK" : "PAPER").strip().toUpperCase(Locale.ROOT), label, permission,
                "optional".equals(e.getString("target", "none").strip().toLowerCase(Locale.ROOT))));
        }
    }

    /** Interactions of the running Interakcje plugin; empty when it is missing or disabled. */
    public static List<InteractionEntry> current() {
        final Plugin plugin = PluginHooks.enabled(PLUGIN);
        if (plugin == null) {
            return List.of();
        }
        try {
            return parse(plugin.getConfig().getConfigurationSection("interactions"));
        } catch (final RuntimeException e) {
            plugin.getLogger().fine("Menu: nie można odczytać interakcji: " + e);
            return List.of();
        }
    }

    /** Parses the {@code interactions} section; disabled and malformed entries are skipped. */
    public static List<InteractionEntry> parse(final ConfigurationSection section) {
        final List<InteractionEntry> out = new ArrayList<>();
        if (section == null) {
            return out;
        }
        for (final String key : section.getKeys(false)) {
            final ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null || !s.getBoolean("enabled", true)) {
                continue;
            }
            final String id = key.toLowerCase(Locale.ROOT);
            final String command = clean(s.getString("command", id));
            if (command == null) {
                continue;
            }
            String label = command;
            if (!isAscii(command)) {
                for (final String alias : s.getStringList("aliases")) {
                    final String a = clean(alias);
                    if (a != null && isAscii(a)) {
                        label = a;
                        break;
                    }
                }
            }
            double range = s.getDouble("range", 3.0);
            if (!(range > 0)) {
                range = 3.0;
            }
            final String target = s.getString("target", "required").strip().toLowerCase(Locale.ROOT);
            out.add(new InteractionEntry(id,
                s.getString("name", id),
                s.getString("description", ""),
                label,
                s.getString("icon", "PAPER").strip().toUpperCase(Locale.ROOT),
                s.getBoolean("consent", false),
                "optional".equals(target),
                range));
        }
        return out;
    }

    private static String clean(final String label) {
        if (label == null) {
            return null;
        }
        String l = label.strip().toLowerCase(Locale.ROOT);
        while (l.startsWith("/")) {
            l = l.substring(1);
        }
        return l.isEmpty() || l.contains(" ") || l.contains(":") ? null : l;
    }

    static boolean isAscii(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
