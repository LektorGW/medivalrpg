// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * MiniMessage messages from the {@code messages} section of config.yml (missing keys fall back to the jar's
 * defaults). Every message may use {@code <prefix>}. User supplied values must be passed as
 * {@link Placeholder#unparsed}. Immutable after construction, safe from any thread.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> raw;
    private final Map<String, List<String>> lists;
    private final TagResolver prefix;
    private final Logger logger;

    public Messages(final FileConfiguration config, final Logger logger) {
        this.logger = logger;
        final Map<String, String> map = new HashMap<>();
        final Map<String, List<String>> listMap = new HashMap<>();
        collect(config.getConfigurationSection("messages"), map, listMap);
        if (config.getDefaults() != null) {
            final Map<String, String> defaults = new HashMap<>();
            final Map<String, List<String>> defaultLists = new HashMap<>();
            collect(config.getDefaults().getConfigurationSection("messages"), defaults, defaultLists);
            defaults.forEach(map::putIfAbsent);
            defaultLists.forEach(listMap::putIfAbsent);
        }
        this.raw = Map.copyOf(map);
        this.lists = Map.copyOf(listMap);
        this.prefix = Placeholder.parsed("prefix", this.raw.getOrDefault("prefix", "<gold>[Skarbiec]</gold> "));
    }

    private static void collect(final ConfigurationSection section, final Map<String, String> into,
                                final Map<String, List<String>> lists) {
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(true)) {
            if (section.isString(key)) {
                into.put(key, section.getString(key));
            } else if (section.isList(key)) {
                final List<String> list = List.copyOf(section.getStringList(key));
                lists.put(key, list);
                into.put(key, String.join("<newline>", list));
            }
        }
    }

    /** Item lore lines: a list key gives one component per entry, a string key a single line; never italic. */
    public List<Component> lines(final String key, final TagResolver... resolvers) {
        final List<String> list = this.lists.get(key);
        if (list == null) {
            return List.of(this.item(key, resolvers));
        }
        final List<Component> out = new ArrayList<>(list.size());
        for (final String line : list) {
            out.add(this.parse(line, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return out;
    }

    public boolean has(final String key) {
        return this.raw.containsKey(key);
    }

    public String raw(final String key) {
        final String value = this.raw.get(key);
        if (value == null) {
            this.logger.warning("Brak komunikatu w config.yml: messages." + key);
            return key;
        }
        return value;
    }

    public Component get(final String key, final TagResolver... resolvers) {
        return this.parse(this.raw(key), resolvers);
    }

    /** Like {@link #get} but without the default italic (item names and lore). */
    public Component item(final String key, final TagResolver... resolvers) {
        return this.get(key, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public Component parse(final String miniMessage, final TagResolver... resolvers) {
        return MM.deserialize(miniMessage, TagResolver.resolver(TagResolver.resolver(resolvers), this.prefix));
    }

    public void send(final Audience audience, final String key, final TagResolver... resolvers) {
        final String value = this.raw(key);
        if (value.isEmpty()) {
            return;
        }
        audience.sendMessage(this.parse(value, resolvers));
    }

    public static MiniMessage mini() {
        return MM;
    }
}
