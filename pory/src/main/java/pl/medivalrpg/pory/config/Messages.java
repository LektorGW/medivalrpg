// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.config;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

/** MiniMessage texts from {@code messages:} in config.yml. */
public final class Messages {

    private final ConfigurationSection section;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public Messages(final ConfigurationSection section) {
        this.section = section;
    }

    public String raw(final String key) {
        return this.section == null ? key : this.section.getString(key, key);
    }

    public Component parse(final String text, final TagResolver... resolvers) {
        return this.mm.deserialize(text, resolvers);
    }

    public Component line(final String key, final TagResolver... resolvers) {
        return this.parse(this.raw(key), resolvers);
    }

    public List<Component> lines(final String key) {
        final List<Component> out = new ArrayList<>();
        if (this.section != null) {
            for (final String s : this.section.getStringList(key)) {
                out.add(this.parse(s));
            }
        }
        return out;
    }

    /** Prefixed message. */
    public void send(final CommandSender to, final String key, final TagResolver... resolvers) {
        to.sendMessage(this.parse(this.raw("prefix")).append(this.line(key, resolvers)));
    }
}
