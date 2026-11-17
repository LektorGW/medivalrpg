// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Solo animations behind one command: {@code dances:} ({@code /taniec [1-3]}) and {@code emotes:}
 * ({@code /emotka <nazwa>}). Pure data parsed from config.yml, unit-testable.
 *
 * @param permission e.g. {@code interakcje.dance}
 */
public record MotionGroup(String key, boolean enabled, String command, List<String> aliases, String permission,
                          String argumentName, Map<String, Motion> entries, ClickTrigger clickTrigger) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+");

    /**
     * One dance / emote.
     *
     * @param icon            menu icon (Material name), for the Menu plugin
     * @param animation       id in the animation catalogue
     * @param target          {@code /emotka <nazwa> <gracz>} is allowed: the player turns to the target
     * @param broadcastTarget RP line when used with a target ({@code <player>}, {@code <target>}); empty = {@code broadcast}
     * @param effects         particles / sounds: at every impact of the animation, or once without animations
     */
    public record Motion(String id, boolean enabled, String name, String description, String icon, String animation,
                         long cooldownMs, boolean target, String broadcast, String broadcastTarget,
                         InteractionDef.Effects effects) {
    }

    /**
     * Starting a motion by right-clicking with an item (e.g. the tankard emote with a bottle or a brew).
     *
     * @param items Material names
     */
    public record ClickTrigger(boolean enabled, String motion, Set<String> items) {
        public static final ClickTrigger NONE = new ClickTrigger(false, "", Set.of());

        public boolean matches(String material) {
            return enabled && items.contains(material.toUpperCase(Locale.ROOT));
        }
    }

    public String cooldownKey(Motion m) {
        return key + ":" + m.id();
    }

    /** All command labels (command + aliases). */
    public List<String> labels() {
        List<String> out = new ArrayList<>();
        out.add(command);
        out.addAll(aliases);
        return out;
    }

    public Motion get(String id) {
        return id == null ? null : entries.get(id.toLowerCase(Locale.ROOT));
    }

    public static MotionGroup parse(String key, ConfigurationSection s, String defCommand, List<String> defAliases,
                                    String permission, String argumentName, Consumer<String> warn) {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        String command = InteractionLoader.normalizeLabel(s.getString("command", defCommand));
        if (command == null) {
            warn.accept(key + ".command: niepoprawna nazwa – używam " + defCommand + ".");
            command = defCommand;
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (String a : s.isList("aliases") ? s.getStringList("aliases") : defAliases) {
            String n = InteractionLoader.normalizeLabel(a);
            if (n == null) {
                warn.accept(key + ".aliases: pomijam niepoprawny alias '" + a + "'.");
            } else if (!n.equals(command)) {
                aliases.add(n);
            }
        }
        double defaultCooldown = Math.max(0, s.getDouble("cooldown", 3));
        Map<String, Motion> entries = new LinkedHashMap<>();
        ConfigurationSection list = s.getConfigurationSection("list");
        if (list != null) {
            for (String raw : list.getKeys(false)) {
                String id = raw.toLowerCase(Locale.ROOT);
                ConfigurationSection e = list.getConfigurationSection(raw);
                if (e == null || !ID.matcher(id).matches()) {
                    warn.accept(key + ".list." + raw + ": niepoprawny wpis – pomijam.");
                    continue;
                }
                String where = key + ".list." + id;
                String targetMode = e.getString("target", "none").trim().toLowerCase(Locale.ROOT);
                if (!targetMode.equals("none") && !targetMode.equals("optional")) {
                    warn.accept(where + ".target: dozwolone none albo optional – ustawiam none.");
                    targetMode = "none";
                }
                entries.put(id, new Motion(
                        id,
                        e.getBoolean("enabled", true),
                        e.getString("name", id),
                        e.getString("description", ""),
                        e.getString("icon", "PAPER"),
                        InteractionLoader.animationId(e.getString("animation", "")),
                        Math.round(Math.max(0, e.getDouble("cooldown", defaultCooldown)) * 1000.0),
                        targetMode.equals("optional"),
                        e.getString("broadcast", ""),
                        e.getString("broadcast-target", ""),
                        InteractionLoader.parseEffects(where + ".effects", e.getConfigurationSection("effects"), warn)));
            }
        }
        ClickTrigger trigger = ClickTrigger.NONE;
        ConfigurationSection click = s.getConfigurationSection("click-trigger");
        if (click != null) {
            String motion = click.getString("emote", "").trim().toLowerCase(Locale.ROOT);
            Set<String> items = new LinkedHashSet<>();
            for (String m : click.getStringList("items")) {
                items.add(m.trim().toUpperCase(Locale.ROOT));
            }
            boolean on = click.getBoolean("enabled", false);
            if (on && !entries.containsKey(motion)) {
                warn.accept(key + ".click-trigger.emote: nieznana pozycja '" + motion + "' – wyłączam.");
                on = false;
            }
            trigger = new ClickTrigger(on, motion, Collections.unmodifiableSet(items));
        }
        return new MotionGroup(key, s.getBoolean("enabled", true), command, List.copyOf(aliases), permission,
                argumentName, Collections.unmodifiableMap(entries), trigger);
    }
}
