// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * MiniMessage texts from the {@code wiadomosci} section of config.yml (missing keys from the jar defaults). Every
 * text may use {@code <prefix>}. Values typed by players are passed as {@link Placeholder#unparsed}. Immutable.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> raw;
    private final TagResolver prefix;
    private final Consumer<String> warn;

    public Messages(final FileConfiguration config, final Consumer<String> warn) {
        this.warn = warn;
        final Map<String, String> map = new HashMap<>();
        collect(config.getConfigurationSection("wiadomosci"), map);
        if (config.getDefaults() != null) {
            collect(config.getDefaults().getConfigurationSection("wiadomosci"), map);
        }
        this.raw = Map.copyOf(map);
        this.prefix = Placeholder.parsed("prefix", this.raw.getOrDefault("prefix", "<dark_gray>[<gold>Strażnik</gold>]</dark_gray> "));
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
            this.warn.accept("Brak komunikatu w config.yml: wiadomosci." + key);
            return key;
        }
        return value;
    }

    public Component get(final String key, final TagResolver... resolvers) {
        final TagResolver[] all = new TagResolver[resolvers.length + 1];
        all[0] = this.prefix;
        System.arraycopy(resolvers, 0, all, 1, resolvers.length);
        return MM.deserialize(this.raw(key), all);
    }

    public static TagResolver text(final String name, final String value) {
        return Placeholder.unparsed(name, value == null ? "" : value);
    }

    public static TagResolver number(final String name, final long value) {
        return Placeholder.unparsed(name, Long.toString(value));
    }
}
