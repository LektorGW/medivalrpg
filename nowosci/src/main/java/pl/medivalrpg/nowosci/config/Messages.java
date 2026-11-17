// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * MiniMessage texts from the {@code messages} section of config.yml (missing keys come from the jar's
 * config.yml defaults). Every message may use {@code <prefix>}. Values typed by users (titles, ids) are always
 * passed as {@link Placeholder#unparsed}. Immutable, safe from any thread.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> raw;
    private final TagResolver prefix;
    private final Consumer<String> warn;

    public Messages(final FileConfiguration config, final Consumer<String> warn) {
        this.warn = warn;
        final Map<String, String> map = new HashMap<>();
        collect(config.getConfigurationSection("messages"), map);
        if (config.getDefaults() != null) {
            collect(config.getDefaults().getConfigurationSection("messages"), map);
        }
        this.raw = Map.copyOf(map);
        this.prefix = Placeholder.parsed("prefix", this.raw.getOrDefault("prefix", "<gold>[Nowości]</gold> "));
    }

    private static void collect(final ConfigurationSection section, final Map<String, String> into) {
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(true)) {
            if (section.isString(key)) {
                into.putIfAbsent(key, section.getString(key));
            }
        }
    }

    public String raw(final String key) {
        final String value = this.raw.get(key);
        if (value == null) {
            this.warn.accept("Brak komunikatu w config.yml: messages." + key);
            return key;
        }
        return value;
    }

    public boolean isEmpty(final String key) {
        return this.raw(key).isEmpty();
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
