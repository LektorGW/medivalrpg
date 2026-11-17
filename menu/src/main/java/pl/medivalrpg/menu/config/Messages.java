// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.config;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;

/**
 * MiniMessage texts from {@code config.yml → messages}. {@code <prefix>} is available in every message.
 * Missing keys fall back to the defaults shipped in the jar (Bukkit config defaults), then to the key itself.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ConfigurationSection section;
    private final TagResolver prefix;

    public Messages(final ConfigurationSection section) {
        this.section = section;
        this.prefix = Placeholder.parsed("prefix", section == null ? "" : section.getString("prefix", ""));
    }

    public String raw(final String key) {
        final String value = this.section == null ? null : this.section.getString(key);
        return value == null ? key : value;
    }

    public List<String> rawList(final String key) {
        if (this.section == null) {
            return List.of();
        }
        if (this.section.isList(key)) {
            return this.section.getStringList(key);
        }
        final String single = this.section.getString(key);
        return single == null ? List.of() : List.of(single);
    }

    public Component get(final String key, final TagResolver... resolvers) {
        return this.parse(this.raw(key), resolvers);
    }

    public Component parse(final String miniMessage, final TagResolver... resolvers) {
        return MM.deserialize(miniMessage, TagResolver.resolver(this.prefix, TagResolver.resolver(resolvers)));
    }

    /** Item text: parsed MiniMessage with the default italic of item names/lore removed. */
    public static Component item(final String miniMessage, final TagResolver resolver) {
        return MM.deserialize(miniMessage, resolver).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> itemLines(final List<String> lines, final TagResolver resolver) {
        final List<Component> out = new ArrayList<>(lines.size());
        for (final String line : lines) {
            out.add(item(line, resolver));
        }
        return out;
    }
}
