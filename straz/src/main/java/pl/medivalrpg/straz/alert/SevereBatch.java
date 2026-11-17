// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.alert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.medivalrpg.straz.log.ErrorBook;
import pl.medivalrpg.straz.log.LogParser;
import pl.medivalrpg.straz.util.Fmt;

/**
 * One alert for a burst of new error kinds (pure). Our own plugins come first (they are what the owner can fix),
 * then the watchdog, then the server and third-party plugins; newest first inside a group. A per-plugin count line
 * keeps a startup burst readable.
 */
public final class SevereBatch {

    /** MEDIVALRPG plugins, listed before everything else. */
    public static final Set<String> OURS = Set.of("Skarbiec", "Kraje", "Ludnosc", "Armia", "Kolej", "Zycie", "Wladcy", "Postep",
        "Panel", "Menu", "Nowosci", "Interakcje", "Szlaki", "Turnieje", "Glos", "Straz");
    static final int SHOWN = 6;

    private SevereBatch() {
    }

    static int rank(final String plugin) {
        if (OURS.contains(plugin)) {
            return 0;
        }
        return LogParser.WATCHDOG.equals(plugin) ? 1 : 2;
    }

    public static Alert build(final List<ErrorBook.Line> pending, final long now) {
        final Map<String, ErrorBook.Line> unique = new LinkedHashMap<>();
        for (final ErrorBook.Line l : pending) {
            unique.putIfAbsent(l.plugin() + "|" + LogParser.normalize(l.sample()), l);
        }
        final List<ErrorBook.Line> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparingInt((ErrorBook.Line l) -> rank(l.plugin())).thenComparing(l -> -l.last()));
        final Map<String, Integer> perPlugin = new LinkedHashMap<>();
        for (final ErrorBook.Line l : sorted) {
            perPlugin.merge(l.plugin(), 1, Integer::sum);
        }
        final List<String> lines = new ArrayList<>();
        if (perPlugin.size() > 1) {
            final List<String> parts = new ArrayList<>();
            perPlugin.forEach((p, n) -> parts.add(p + " " + n));
            lines.add("Nowe rodzaje według pluginów: " + String.join(" · ", parts));
        }
        int shown = 0;
        for (final ErrorBook.Line l : sorted) {
            if (shown++ >= SHOWN) {
                break;
            }
            lines.add("**[" + l.plugin() + "]** `" + Fmt.clip(l.sample(), 180).replace('`', '\'') + "`"
                + (l.detail() == null ? "" : "\n  ↳ " + Fmt.clip(l.detail(), 150)));
        }
        final int more = sorted.size() - Math.min(sorted.size(), SHOWN);
        if (more > 0) {
            lines.add("… i " + more + " " + Fmt.plural(more, "kolejny", "kolejne", "kolejnych") + " (pełna lista w raporcie).");
        }
        final Set<String> keys = new LinkedHashSet<>(unique.keySet());
        final String title = keys.size() == 1 ? "nowy błąd SEVERE/ERROR" : "nowe błędy SEVERE/ERROR (" + keys.size() + ")";
        return new Alert("SEVERE", "severe|" + String.join(",", keys), title, lines, now);
    }
}
