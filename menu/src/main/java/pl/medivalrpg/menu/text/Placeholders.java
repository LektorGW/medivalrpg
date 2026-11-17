// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.text;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.integration.KrajeHook;
import pl.medivalrpg.menu.integration.PluginHooks;

/**
 * Placeholders for menu names, lore, book pages and action strings.
 *
 * <ul>
 *   <li>{@code <player>}, {@code <online>}, {@code <max>},</li>
 *   <li>{@code <country>}, {@code <rank>}, {@code <country_members>}, {@code <country_chunks>} (Kraje, optional),</li>
 *   <li>{@code <map_url>} (text) and {@code <map_link>} (clickable),</li>
 *   <li>{@code <cfg:Plugin:path:default>} – a value from another plugin's {@code config.yml}
 *       (e.g. {@code <cfg:Kraje:claims.base:16>}), so the guide book never lies about limits.</li>
 * </ul>
 * All player-derived values are inserted as plain text (never parsed as MiniMessage).
 */
public final class Placeholders {

    private final MenuSettings settings;
    private final KrajeHook kraje;

    public Placeholders(final MenuSettings settings, final KrajeHook kraje) {
        this.settings = settings;
        this.kraje = kraje;
    }

    /** Resolver for one viewer (evaluates the country once). Main thread only. */
    public TagResolver resolver(final Player player) {
        final Optional<KrajeHook.CountryView> country = PluginHooks.isEnabled("Kraje")
            ? this.kraje.countryOf(player.getUniqueId()) : Optional.empty();
        return TagResolver.resolver(
            Placeholder.unparsed("player", player.getName()),
            Placeholder.unparsed("online", Integer.toString(Bukkit.getOnlinePlayers().size())),
            Placeholder.unparsed("max", Integer.toString(Bukkit.getMaxPlayers())),
            Placeholder.unparsed("country", country.map(KrajeHook.CountryView::name).orElse(this.settings.noCountry)),
            Placeholder.unparsed("rank", country.map(KrajeHook.CountryView::rank).filter(r -> r != null)
                .orElse(this.settings.noRank)),
            Placeholder.unparsed("country_members", country.map(c -> number(c.members())).orElse("0")),
            Placeholder.unparsed("country_chunks", country.map(c -> number(c.chunks())).orElse("0")),
            Placeholder.unparsed("map_url", this.settings.mapUrl),
            Placeholder.component("map_link", this.mapLink()),
            TagResolver.resolver("cfg", (args, ctx) -> {
                final String pluginName = args.hasNext() ? args.pop().value() : "";
                final String path = args.hasNext() ? args.pop().value() : "";
                final String fallback = args.hasNext() ? args.pop().value() : "?";
                return Tag.inserting(Component.text(configValue(pluginName, path, fallback)));
            }));
    }

    /** Clickable map link (or a grey "brak" when no URL is configured). */
    public Component mapLink() {
        if (this.settings.mapUrl.isEmpty()) {
            return Component.text("brak", NamedTextColor.GRAY);
        }
        return Component.text(this.settings.mapUrl, NamedTextColor.BLUE)
            .decorate(TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.openUrl(this.settings.mapUrl))
            .hoverEvent(HoverEvent.showText(Component.text("Otwórz mapę w przeglądarce", NamedTextColor.GRAY)));
    }

    /** Text substitution for action strings ({@code command:}, {@code url:}, {@code suggest:}). */
    public String apply(final String raw, final Player player) {
        String out = raw.replace("<player>", player.getName()).replace("<map_url>", this.settings.mapUrl);
        if (out.contains("<country>")) {
            out = out.replace("<country>", PluginHooks.isEnabled("Kraje")
                ? this.kraje.countryOf(player.getUniqueId()).map(KrajeHook.CountryView::name).orElse("") : "");
        }
        return out;
    }

    /** A config value of another plugin as plain text (inserted as a text component, never parsed). */
    static String configValue(final String pluginName, final String path, final String fallback) {
        final Plugin plugin = PluginHooks.enabled(pluginName);
        Object value = null;
        if (plugin != null && !path.isEmpty()) {
            try {
                value = plugin.getConfig().get(path);
            } catch (final RuntimeException ignored) {
                // broken config of the other plugin → fallback
            }
        }
        String text = value == null || value instanceof org.bukkit.configuration.ConfigurationSection
            ? fallback : String.valueOf(value);
        if (value instanceof Double d && d == Math.rint(d)) {
            text = Long.toString(d.longValue());
        }
        return text;
    }

    private static String number(final int n) {
        return n < 0 ? "?" : Integer.toString(n);
    }
}
