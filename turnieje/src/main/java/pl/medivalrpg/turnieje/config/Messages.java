// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.config;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * MiniMessage templates from {@code messages} in config.yml (missing keys come from the jar's config). Every message
 * may use {@code <prefix>}. Player names and other player-typed text are ALWAYS passed as {@link Placeholder#unparsed}
 * — never parsed as MiniMessage. Immutable.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> raw;
    private final TagResolver prefix;
    private final Logger logger;

    public Messages(final FileConfiguration config, final Logger logger) {
        this.logger = logger;
        final Map<String, String> map = new HashMap<>();
        collect(config.getConfigurationSection("messages"), map, false);
        if (config.getDefaults() != null) {
            collect(config.getDefaults().getConfigurationSection("messages"), map, true);
        }
        this.raw = Map.copyOf(map);
        this.prefix = Placeholder.parsed("prefix", this.raw.getOrDefault("prefix", "<gold>[Turnieje]</gold> "));
    }

    private static void collect(final ConfigurationSection section, final Map<String, String> map, final boolean onlyMissing) {
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(true)) {
            if (section.isString(key)) {
                if (onlyMissing) {
                    map.putIfAbsent(key, section.getString(key));
                } else {
                    map.put(key, section.getString(key));
                }
            }
        }
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

    /** Parses a template from config (never player text). */
    public Component parse(final String miniMessage, final TagResolver... resolvers) {
        return MM.deserialize(miniMessage, TagResolver.resolver(TagResolver.resolver(resolvers), this.prefix));
    }

    public String plain(final String key, final TagResolver... resolvers) {
        return PlainTextComponentSerializer.plainText().serialize(this.get(key, resolvers));
    }

    public void send(final Audience audience, final String key, final TagResolver... resolvers) {
        if (audience == null || this.raw(key).isEmpty()) {
            return;
        }
        audience.sendMessage(this.get(key, resolvers));
    }

    public static TagResolver p(final String name, final Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver c(final String name, final Component value) {
        return Placeholder.component(name, value);
    }
}
