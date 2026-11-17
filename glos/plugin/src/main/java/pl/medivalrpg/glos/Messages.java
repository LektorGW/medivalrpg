// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;

/**
 * Komunikaty z sekcji {@code messages} configu (MiniMessage).
 * W każdym komunikacie dostępny jest tag {@code <prefix>}.
 * Klucze, których brakuje w configu serwera (np. dodane w nowszej wersji pluginu), są brane
 * z domyślnego {@code config.yml} w jarze.
 */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> raw = new HashMap<>();
    private final TagResolver prefix;

    /**
     * @param cfg         config serwera
     * @param jarDefaults domyślny config.yml z jara (może być {@code null})
     */
    public Messages(Configuration cfg, Configuration jarDefaults) {
        if (jarDefaults != null) {
            readSection(jarDefaults.getConfigurationSection("messages"));
        }
        readSection(cfg.getConfigurationSection("messages")); // config serwera nadpisuje domyślne
        prefix = Placeholder.parsed("prefix", raw.getOrDefault("prefix", "<gold>[Głos]</gold>"));
    }

    private void readSection(ConfigurationSection sec) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(true)) {
            if (!sec.isConfigurationSection(key)) {
                String v = sec.getString(key);
                if (v != null) {
                    raw.put(key, v);
                }
            }
        }
    }

    public String raw(String key) {
        return raw.getOrDefault(key, "<red>[brak komunikatu: " + key + "]</red>");
    }

    public Component get(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), TagResolver.resolver(prefix, TagResolver.resolver(resolvers)));
    }

    public void send(Audience to, String key, TagResolver... resolvers) {
        String text = raw(key);
        if (text.isEmpty()) {
            return; // pusty komunikat = wyłączony
        }
        to.sendMessage(get(key, resolvers));
    }

    /** Polska nazwa trybu (messages.mode-names.*). */
    public String modeName(VoiceMode mode) {
        return raw.getOrDefault("mode-names." + mode.id(), mode.id());
    }

    public static Component parse(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    public static TagResolver text(String key, String value) {
        return Placeholder.unparsed(key, value);
    }
}
