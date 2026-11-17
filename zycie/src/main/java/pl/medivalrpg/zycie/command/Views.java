// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.zycie.config.Messages;
import pl.medivalrpg.zycie.config.ZycieConfig;
import pl.medivalrpg.zycie.service.MoodSnapshot;

/** Chat views of {@code /zycie}. */
public final class Views {

    private Views() {
    }

    public static String labelKey(final int mood) {
        if (mood >= 80) {
            return "label-80";
        }
        if (mood >= 60) {
            return "label-60";
        }
        if (mood >= 40) {
            return "label-40";
        }
        return mood >= 20 ? "label-20" : "label-0";
    }

    public static TextColor barColor(final int mood) {
        if (mood >= 80) {
            return NamedTextColor.GOLD;
        }
        if (mood >= 60) {
            return NamedTextColor.GREEN;
        }
        if (mood >= 40) {
            return NamedTextColor.YELLOW;
        }
        return mood >= 20 ? NamedTextColor.RED : NamedTextColor.DARK_RED;
    }

    static Component bar(final Messages m, final int mood) {
        final int filled = Math.max(0, Math.min(20, Math.round(mood / 5.0f)));
        Component out = Component.empty();
        final Component full = m.get("bar-full", Placeholder.styling("color", barColor(mood)));
        final Component empty = m.get("bar-empty");
        for (int i = 0; i < 20; i++) {
            out = out.append(i < filled ? full : empty);
        }
        return out;
    }

    public static List<Component> mood(final Messages m, final ZycieConfig cfg, final KrajInfo k, final MoodSnapshot s) {
        final List<Component> lines = new ArrayList<>();
        lines.add(m.get("view-header", Placeholder.unparsed("country", k.name()), Placeholder.styling("color", k.color())));
        lines.add(m.get("view-mood", Placeholder.component("bar", bar(m, s.mood())),
            Placeholder.unparsed("mood", Integer.toString(s.mood())), Placeholder.component("label", m.get(labelKey(s.mood())))));
        lines.add(m.get("view-multiplier", Placeholder.unparsed("multiplier", String.format(Locale.forLanguageTag("pl"), "%.2f", s.multiplier()))));
        lines.add(m.get("view-population", Placeholder.unparsed("population", Integer.toString(s.population())),
            Placeholder.unparsed("housing", Integer.toString(s.housing()))));
        if (s.festival()) {
            lines.add(m.get("view-festival"));
        }
        if (s.protest()) {
            lines.add(m.get("view-protest"));
        }
        if (s.exodus()) {
            lines.add(m.get("view-exodus"));
        }
        lines.add(m.get("view-factors"));
        final List<Map.Entry<String, Integer>> factors = new ArrayList<>(s.factors().entrySet());
        factors.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> -Math.abs(e.getValue())));
        if (factors.isEmpty()) {
            lines.add(m.get("view-no-factors"));
        }
        for (final Map.Entry<String, Integer> f : factors) {
            final String name = cfg.factorNames.getOrDefault(f.getKey(), f.getKey());
            lines.add(m.get(f.getValue() >= 0 ? "view-factor-plus" : "view-factor-minus",
                Placeholder.unparsed("value", Integer.toString(f.getValue())), Placeholder.unparsed("name", name)));
        }
        lines.add(m.get("view-concerns"));
        if (s.concerns().isEmpty()) {
            lines.add(m.get("view-no-concerns"));
        }
        for (final String c : s.concerns()) {
            lines.add(m.get("view-concern", Placeholder.unparsed("text", c)));
        }
        lines.add(m.get("view-hint"));
        return lines;
    }
}
