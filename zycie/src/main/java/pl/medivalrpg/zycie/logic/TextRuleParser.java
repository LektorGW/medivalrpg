// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a text pool from the raw YAML list ({@code ConfigurationSection#getMapList}) — pure Java, testable.
 *
 * <pre>
 * - pory: [rano]            # rano, praca, poludnie, spotkanie, wieczor, noc, msza
 *   epoki: [1, 2]
 *   nastroj-od: 60
 *   wojna: true
 *   teksty: ["Dzień dobry!", "Kogut już dawno piał."]
 * - kwestie: ["Słyszałeś?", "Co takiego?", "Król znów podnosi podatki!"]   # dialogue (one variant)
 * </pre>
 */
public final class TextRuleParser {

    private TextRuleParser() {
    }

    public static TextPool parse(final List<Map<?, ?>> raw, final String path, final List<String> problems) {
        final List<TextRule> rules = new ArrayList<>();
        if (raw == null) {
            return TextPool.EMPTY;
        }
        int index = 0;
        for (final Map<?, ?> m : raw) {
            index++;
            final String where = path + "[" + index + "]";
            try {
                final TextRule r = rule(m, where, problems);
                if (r.variants().isEmpty()) {
                    problems.add(where + ": brak tekstów (teksty / kwestie)");
                    continue;
                }
                rules.add(r);
            } catch (final IllegalArgumentException e) {
                problems.add(where + ": " + e.getMessage());
            }
        }
        return new TextPool(rules);
    }

    private static TextRule rule(final Map<?, ?> m, final String where, final List<String> problems) {
        final Set<DayPhase> phases = new LinkedHashSet<>();
        for (final String p : strings(m.get("pory"))) {
            phases.add(DayPhase.byKey(p).orElseThrow(() -> new IllegalArgumentException("nieznana pora: " + p)));
        }
        final Set<Integer> eras = new LinkedHashSet<>();
        for (final String e : strings(m.get("epoki"))) {
            try {
                eras.add(Integer.parseInt(e.trim()));
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException("epoka musi być liczbą 1–4: " + e);
            }
        }
        final List<List<String>> variants = new ArrayList<>();
        for (final String t : strings(m.get("teksty"))) {
            if (!t.isBlank()) {
                variants.add(List.of(t));
            }
        }
        final List<String> lines = strings(m.get("kwestie"));
        if (!lines.isEmpty()) {
            variants.add(List.copyOf(lines));
        }
        final Object weight = m.get("waga");
        return new TextRule(Set.copyOf(phases), Set.copyOf(eras), integer(m.get("nastroj-od")), integer(m.get("nastroj-do")),
            bool(m.get("wojna")), bool(m.get("oblezenie")), bool(m.get("glod")), bool(m.get("podatki")),
            bool(m.get("bezdomnosc")), bool(m.get("swieto")), bool(m.get("protest")), normalized(m.get("zdarzenia")),
            normalized(m.get("zawody")), normalized(m.get("relacje")),
            weight == null ? 1 : Math.max(0, intOf(weight)), List.copyOf(variants));
    }

    static List<String> strings(final Object o) {
        final List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (final Object e : list) {
                if (e != null) {
                    out.add(String.valueOf(e));
                }
            }
        } else if (o != null) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static Set<String> normalized(final Object o) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String s : strings(o)) {
            out.add(Texts.stripNamespace(s));
        }
        return Set.copyOf(out);
    }

    private static Integer integer(final Object o) {
        return o == null ? null : intOf(o);
    }

    private static int intOf(final Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("oczekiwano liczby: " + o);
        }
    }

    private static Boolean bool(final Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        final String s = Texts.normalize(String.valueOf(o));
        return switch (s) {
            case "true", "tak", "yes" -> Boolean.TRUE;
            case "false", "nie", "no" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("oczekiwano true/false: " + o);
        };
    }
}
