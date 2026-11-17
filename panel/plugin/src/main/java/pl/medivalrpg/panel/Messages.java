// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel;

import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * MiniMessage messages of the {@code messages} section; missing keys fall back to the jar's config.yml defaults.
 * User supplied values are always passed as {@link Placeholder#unparsed}. Immutable, any thread.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> raw;
    private final TagResolver prefix;

    public Messages(final FileConfiguration config) {
        final Map<String, String> map = new HashMap<>();
        collect(map, config.getConfigurationSection("messages"));
        if (config.getDefaults() != null) {
            final Map<String, String> defaults = new HashMap<>();
            collect(defaults, config.getDefaults().getConfigurationSection("messages"));
            defaults.forEach(map::putIfAbsent);
        }
        this.raw = Map.copyOf(map);
        this.prefix = Placeholder.parsed("prefix", this.raw.getOrDefault("prefix", "<gold>[Panel]</gold> "));
    }

    private static void collect(final Map<String, String> into, final ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(true)) {
            if (section.isString(key)) {
                into.put(key, section.getString(key));
            }
        }
    }

    public String raw(final String key) {
        return this.raw.getOrDefault(key, "");
    }

    public boolean has(final String key) {
        return !this.raw(key).isEmpty();
    }

    public Component get(final String key, final TagResolver... resolvers) {
        return MM.deserialize(this.raw(key), TagResolver.resolver(TagResolver.resolver(resolvers), this.prefix));
    }

    public void send(final Audience audience, final String key, final TagResolver... resolvers) {
        if (this.has(key)) {
            audience.sendMessage(this.get(key, resolvers));
        }
    }

    public static TagResolver text(final String key, final Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }
}
