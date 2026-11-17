// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.manager;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;

/**
 * Message helpers shared by commands, the API and background services (main thread).
 */
public final class Notify {

    /** Name shown to players (era titles, see {@code KrajePlugin#displayName}); identity until the plugin sets it. */
    private static volatile java.util.function.Function<Country, String> displayNames = Country::name;

    private Notify() {
    }

    /** Set by the plugin on enable (and reset on disable). {@code null} = plain names. */
    public static void setDisplayNames(final java.util.function.Function<Country, String> names) {
        displayNames = names == null ? Country::name : names;
    }

    /** Display name of a country (era title applied); falls back to the real name on any error. */
    public static String displayName(final Country c) {
        try {
            final String name = displayNames.apply(c);
            return name == null || name.isEmpty() ? c.name() : name;
        } catch (final RuntimeException e) {
            return c.name();
        }
    }

    /** {@code <country>} (display name, see {@link #displayName}) and {@code <color>} of a country. */
    public static TagResolver country(final Country c) {
        return TagResolver.resolver(Placeholder.unparsed("country", displayName(c)), Placeholder.styling("color", c.textColor()));
    }

    /** {@code <a>}, {@code <color_a>}, {@code <b>}, {@code <color_b>}. */
    public static TagResolver pair(final Country a, final Country b) {
        return TagResolver.resolver(Placeholder.unparsed("a", displayName(a)), Placeholder.styling("color_a", a.textColor()),
            Placeholder.unparsed("b", displayName(b)), Placeholder.styling("color_b", b.textColor()));
    }

    public static TagResolver unparsed(final String key, final Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    /** All online members. */
    public static void members(final Messages messages, final Country c, final String key, final TagResolver... resolvers) {
        for (final Member m : c.members()) {
            final Player p = Bukkit.getPlayer(m.uuid());
            if (p != null) {
                messages.send(p, key, resolvers);
            }
        }
    }

    /** Online ruler and advisors. */
    public static void leaders(final Messages messages, final Country c, final String key, final TagResolver... resolvers) {
        for (final Member m : c.members()) {
            if (m.rank().atLeast(Rank.ADVISOR)) {
                final Player p = Bukkit.getPlayer(m.uuid());
                if (p != null) {
                    messages.send(p, key, resolvers);
                }
            }
        }
    }

    /** Whole server (skipped when the message is empty). */
    public static void broadcast(final Messages messages, final String key, final TagResolver... resolvers) {
        if (messages.raw(key).isEmpty()) {
            return;
        }
        Bukkit.getServer().sendMessage(messages.get(key, resolvers));
    }
}
