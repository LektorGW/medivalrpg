// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.config;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * MiniMessage messages from the {@code messages} section of config.yml. Every message may use
 * {@code <prefix>}. User supplied values must be passed as {@link Placeholder#unparsed} resolvers.
 * Immutable after construction, safe from any thread.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> raw;
    private final TagResolver prefix;
    private final Logger logger;

    public Messages(final FileConfiguration config, final Logger logger) {
        this.logger = logger;
        final Map<String, String> map = new HashMap<>();
        final ConfigurationSection section = config.getConfigurationSection("messages");
        if (section != null) {
            for (final String key : section.getKeys(true)) {
                if (section.isString(key)) {
                    map.put(key, section.getString(key));
                }
            }
        }
        // Defaults from the jar's config.yml fill missing keys (Bukkit sets them as configuration defaults).
        final ConfigurationSection defaults = config.getDefaults() == null ? null : config.getDefaults().getConfigurationSection("messages");
        if (defaults != null) {
            for (final String key : defaults.getKeys(true)) {
                if (defaults.isString(key)) {
                    map.putIfAbsent(key, defaults.getString(key));
                }
            }
        }
        this.raw = Map.copyOf(map);
        this.prefix = Placeholder.parsed("prefix", this.raw.getOrDefault("prefix", "<gold>[Kraje]</gold> "));
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
