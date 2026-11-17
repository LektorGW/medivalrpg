// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.armia.api.NpcStance;
import pl.medivalrpg.armia.logic.Scenario;

/**
 * Reads a scenario YAML ({@code plugins/Armia/scenarios/<id>.yml}). Keys are Polish (see {@code europa1350.yml}).
 * Structural problems throw {@link IllegalArgumentException} with a Polish message; rule checks are in
 * {@link Scenario#validate}.
 */
public final class ScenarioParser {

    private ScenarioParser() {
    }

    public static Scenario parse(final String fileId, final ConfigurationSection c) {
        final String id = c.getString("id", fileId);
        final List<Scenario.CountrySpec> countries = new ArrayList<>();
        int index = 0;
        for (final Map<?, ?> m : c.getMapList("panstwa")) {
            index++;
            countries.add(country(m, index));
        }
        final List<Scenario.RelationSpec> relations = new ArrayList<>();
        for (final Map<?, ?> m : c.getMapList("relacje")) {
            relations.add(relation(m));
        }
        return new Scenario(id, c.getString("nazwa", id), c.getString("opis", ""), c.getString("swiat", ""),
            c.getInt("promien-wioski", 400), c.getInt("odleglosc-od-spawnu", 300), c.getInt("odleglosc-miedzy-panstwami", 500),
            List.copyOf(countries), List.copyOf(relations));
    }

    private static Scenario.CountrySpec country(final Map<?, ?> m, final int index) {
        final String name = str(m, "nazwa", "");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Państwo nr " + index + ": brak nazwy.");
        }
        final Map<String, Integer> army = new LinkedHashMap<>();
        if (raw(m, "wojsko") instanceof Map<?, ?> w) {
            for (final Map.Entry<?, ?> e : w.entrySet()) {
                army.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT), integer(e.getValue(), name + ": wojsko"));
            }
        }
        final long treasury = has(m, "skarbiec-zl") ? Math.round(number(raw(m, "skarbiec-zl"), name + ": skarbiec-zl") * 100)
            : (long) number(get(m, "skarbiec", 0), name + ": skarbiec");
        return new Scenario.CountrySpec(name.trim(), color(str(m, "kolor", ""), name), str(m, "wladca", "Władca").trim(), str(m, "skin", ""),
            integer(get(m, "agresja", 50), name + ": agresja"), integer(get(m, "ekspansja", 50), name + ": ekspansja"),
            integer(get(m, "dyplomacja", 50), name + ": dyplomacja"), integer(get(m, "bogactwo", 50), name + ": bogactwo"),
            integer(get(m, "max-chunki", 40), name + ": max-chunki"), integer(get(m, "max-zolnierze", 20), name + ": max-zolnierze"),
            integer(require(m, "x", name), name + ": x"), integer(require(m, "z", name), name + ": z"), treasury, army,
            integer(get(m, "osadnicy", 0), name + ": osadnicy"), (long) number(get(m, "jedzenie", 0), name + ": jedzenie"),
            has(m, "honor") ? Integer.valueOf(integer(raw(m, "honor"), name + ": honor")) : null);
    }

    private static Scenario.RelationSpec relation(final Map<?, ?> m) {
        final String a = str(m, "a", "").trim();
        final String b = str(m, "b", "").trim();
        if (a.isEmpty() || b.isEmpty()) {
            throw new IllegalArgumentException("Relacja bez państw a / b.");
        }
        final String rel = str(m, "relacja", "").trim().toLowerCase(Locale.ROOT);
        final String relation = switch (rel) {
            case "", "brak", "neutralne", "neutral" -> null;
            case "sojusz", "alliance" -> "ALLIANCE";
            default -> throw new IllegalArgumentException("Relacja " + a + " – " + b + ": dozwolone „sojusz” albo brak (wojen na start nie ma).");
        };
        final Integer opinion = has(m, "opinia") ? integer(raw(m, "opinia"), "Relacja " + a + " – " + b + ": opinia") : null;
        NpcStance stance = null;
        if (has(m, "nastawienie")) {
            final String s = str(m, "nastawienie", "");
            stance = NpcStance.parse(s).orElseThrow(() -> new IllegalArgumentException("Relacja " + a + " – " + b
                + ": nieznane nastawienie " + s + " (auto, przyjazne, wrogie, nigdy_wojny, cel_ataku)."));
        }
        final Object mutual = raw(m, "obustronnie");
        return new Scenario.RelationSpec(a, b, relation, opinion, stance, mutual == null ? !Scenario.ALL.equals(b) : Boolean.parseBoolean(String.valueOf(mutual)));
    }

    private static Object raw(final Map<?, ?> m, final String key) {
        return m.get(key);
    }

    private static boolean has(final Map<?, ?> m, final String key) {
        return m.containsKey(key);
    }

    private static Object get(final Map<?, ?> m, final String key, final Object def) {
        final Object v = raw(m, key);
        return v == null ? def : v;
    }

    private static Object require(final Map<?, ?> m, final String key, final String name) {
        final Object v = raw(m, key);
        if (v == null) {
            throw new IllegalArgumentException(name + ": brak „" + key + "”.");
        }
        return v;
    }

    private static String str(final Map<?, ?> m, final String key, final String def) {
        final Object v = raw(m, key);
        return v == null ? def : String.valueOf(v);
    }

    private static double number(final Object v, final String what) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(what + ": to nie jest liczba (" + v + ").");
        }
    }

    private static int integer(final Object v, final String what) {
        final double d = number(v, what);
        if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(what + ": oczekiwano liczby całkowitej (" + v + ").");
        }
        return (int) d;
    }

    /** Pure check used by {@code extractDefaults} (ScenarioService.extractDefaults; tested). */
    public static boolean outdated(final org.bukkit.configuration.ConfigurationSection bundled, final org.bukkit.configuration.ConfigurationSection disk) {
        if (disk.contains("wersja")) {
            return disk.getInt("wersja", 1) < bundled.getInt("wersja", 1);
        }
        // a file from before versioning: outdated when it lacks the honor field of the bundled one
        return hasHonor(bundled) && !hasHonor(disk);
    }

    private static boolean hasHonor(final org.bukkit.configuration.ConfigurationSection c) {
        for (final Map<?, ?> m : c.getMapList("panstwa")) {
            if (m.containsKey("honor")) {
                return true;
            }
        }
        return false;
    }

    static int color(final String text, final String name) {
        final String s = text.trim().startsWith("#") ? text.trim().substring(1) : text.trim();
        if (!s.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException(name + ": kolor w formacie #RRGGBB.");
        }
        return Integer.parseInt(s, 16);
    }
}
