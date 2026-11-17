// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.config;

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
 * MiniMessage texts from the {@code messages} section of config.yml (missing keys fall back to the jar's defaults).
 * Every message may use {@code <prefix>}. User supplied values are always passed as {@link Placeholder#unparsed}.
 * Immutable, safe from any thread.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> raw;
    private final TagResolver prefix;
    private final Logger logger;

    public Messages(final FileConfiguration config, final Logger logger) {
        this.logger = logger;
        final Map<String, String> map = new HashMap<>();
        copy(config.getConfigurationSection("messages"), map, false);
        copy(config.getDefaults() == null ? null : config.getDefaults().getConfigurationSection("messages"), map, true);
        this.raw = Map.copyOf(map);
        this.prefix = Placeholder.parsed("prefix", this.raw.getOrDefault("prefix", "<#8FBC8F>[Życie]</#8FBC8F> "));
    }

    private static void copy(final ConfigurationSection section, final Map<String, String> into, final boolean onlyMissing) {
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(true)) {
            if (section.isString(key)) {
                if (onlyMissing) {
                    into.putIfAbsent(key, section.getString(key));
                } else {
                    into.put(key, section.getString(key));
                }
            }
        }
    }

    public String raw(final String key) {
        final String value = this.raw.get(key);
        if (value == null) {
            this.logger.warning("Brak komunikatu w config.yml: messages." + key);
            return key;
        }
        return value;
    }

    public boolean has(final String key) {
        return this.raw.containsKey(key);
    }

    public Component get(final String key, final TagResolver... resolvers) {
        return this.parse(this.raw(key), resolvers);
    }

    public Component parse(final String miniMessage, final TagResolver... resolvers) {
        return MM.deserialize(miniMessage, TagResolver.resolver(TagResolver.resolver(resolvers), this.prefix));
    }

    public void send(final Audience audience, final String key, final TagResolver... resolvers) {
        final String value = this.raw(key);
        if (!value.isEmpty()) {
            audience.sendMessage(this.parse(value, resolvers));
        }
    }

    public static MiniMessage mini() {
        return MM;
    }
}
