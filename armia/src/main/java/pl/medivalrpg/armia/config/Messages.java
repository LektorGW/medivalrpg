// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.config;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * MiniMessage texts from {@code messages} in config.yml (missing keys come from the jar's config). Every message may
 * use {@code <prefix>}. Values typed by players must be passed as {@link Placeholder#unparsed}. Immutable.
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
        // Older configs on the server put placeholders inside click arguments (<click:run_command:'<cmd>'>), which
        // MiniMessage never resolves: rewrite them to the <cmd>…</cmd> styling placeholder the code provides.
        map.replaceAll((key, value) -> {
            final String fixed = migrateLegacyClick(value);
            if (!fixed.equals(value)) {
                logger.warning("messages." + key + ": placeholder wewnątrz <click:…> nie działa — używam formatu <cmd>…</cmd> (zaktualizuj config.yml)");
            }
            return fixed;
        });
        this.raw = Map.copyOf(map);
        this.prefix = Placeholder.parsed("prefix", this.raw.getOrDefault("prefix", "<dark_red>[Armia]</dark_red> "));
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

    /** {@code <click:action:'…<tag>…'>text</click>} (a placeholder inside the click argument). */
    static final Pattern LEGACY_CLICK = Pattern.compile("<click:[a-z_]+:(['\"]?)[^'\">]*<[a-z0-9_-]+>[^'\">]*\\1>(.*?)</click>", Pattern.DOTALL);

    /**
     * Rewrites a click tag whose argument contains a placeholder into {@code <cmd>text</cmd>}: the code passes the
     * command as {@code Placeholder.styling("cmd", ClickEvent.runCommand(...))}.
     */
    public static String migrateLegacyClick(final String value) {
        if (value == null || !value.contains("<click:")) {
            return value;
        }
        return LEGACY_CLICK.matcher(value).replaceAll(m -> Matcher.quoteReplacement("<cmd>" + m.group(2) + "</cmd>"));
    }

    /** True when a message still has a placeholder inside a click argument (never resolved by MiniMessage). */
    public static boolean hasPlaceholderInClick(final String value) {
        return value != null && LEGACY_CLICK.matcher(value).find();
    }

    /** Clickable {@code <cmd>…</cmd>} part of a message running {@code command}. */
    public static TagResolver command(final String command) {
        return Placeholder.styling("cmd", ClickEvent.runCommand(command));
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

    public Component parse(final String miniMessage, final TagResolver... resolvers) {
        return MM.deserialize(miniMessage, TagResolver.resolver(TagResolver.resolver(resolvers), this.prefix));
    }

    public String plain(final String key, final TagResolver... resolvers) {
        return PlainTextComponentSerializer.plainText().serialize(this.get(key, resolvers));
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

    public static TagResolver p(final String name, final Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver c(final String name, final Component value) {
        return Placeholder.component(name, value);
    }
}
