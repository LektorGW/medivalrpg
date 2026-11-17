// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Messages from the {@code messages} section (MiniMessage). Every message can use {@code <prefix>}.
 * Player-provided text must only ever enter through {@link #text} / {@link Placeholder#component}.
 */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> raw = new HashMap<>();
    private final TagResolver prefix;

    public Messages(FileConfiguration cfg) {
        // keys from the server's config plus the bundled defaults, so messages added in a newer
        // version work without editing an older config.yml
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        ConfigurationSection sec = cfg.getConfigurationSection("messages");
        if (sec != null) {
            keys.addAll(sec.getKeys(true));
        }
        if (cfg.getDefaults() != null) {
            ConfigurationSection def = cfg.getDefaults().getConfigurationSection("messages");
            if (def != null) {
                keys.addAll(def.getKeys(true));
            }
        }
        for (String key : keys) {
            String value = cfg.getString("messages." + key);
            if (value != null && !cfg.isConfigurationSection("messages." + key)) {
                raw.put(key, value);
            }
        }
        prefix = Placeholder.parsed("prefix", raw.getOrDefault("prefix", "<light_purple>[Interakcje]</light_purple> "));
    }

    public String raw(String key) {
        return raw.getOrDefault(key, "<red>[brak komunikatu: " + key + "]</red>");
    }

    public Component get(String key, TagResolver... resolvers) {
        return parse(raw(key), resolvers);
    }

    /** Parses admin-provided MiniMessage (config) with the prefix tag and the given resolvers. */
    public Component parse(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(miniMessage, TagResolver.resolver(prefix, TagResolver.resolver(resolvers)));
    }

    /** Same as {@link #parse} but without the default italic of item names / lore. */
    public Component item(String miniMessage, TagResolver... resolvers) {
        return parse(miniMessage, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public void send(Audience to, String key, TagResolver... resolvers) {
        String text = raw(key);
        if (text.isEmpty()) {
            return; // empty message = disabled
        }
        to.sendMessage(get(key, resolvers));
    }

    public static TagResolver text(String key, String value) {
        return Placeholder.unparsed(key, value);
    }

    public static TagResolver component(String key, Component value) {
        return Placeholder.component(key, value);
    }

    /** Plain text of a MiniMessage string (e.g. command descriptions). */
    public static String plain(String miniMessage) {
        return PlainTextComponentSerializer.plainText().serialize(MM.deserialize(miniMessage));
    }
}
