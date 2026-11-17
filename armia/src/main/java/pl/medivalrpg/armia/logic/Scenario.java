// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import pl.medivalrpg.armia.api.NpcStance;

/**
 * A start scenario of NPC countries ({@code plugins/Armia/scenarios/<id>.yml}, e.g. "Europa 1350"). Pure value with
 * validation; parsing lives in {@code scenario.ScenarioParser}, placing in {@code scenario.ScenarioService}.
 *
 * @param id               file id ({@code europa1350})
 * @param name             display name
 * @param description      one line
 * @param world            world name; empty = the first world
 * @param villageRadius    look for the nearest village within this many blocks of the target position
 * @param spawnDistance    minimum distance (blocks) from protected areas (Kraje, e.g. spawn) — or the world spawn
 * @param minDistance      minimum distance (blocks) between capitals (also of existing countries)
 * @param countries        countries in file order
 * @param relations        starting relations / opinions / stances
 */
public record Scenario(String id, String name, String description, String world, int villageRadius, int spawnDistance,
                       int minDistance, List<CountrySpec> countries, List<RelationSpec> relations) {

    /** Kraje's rule for country names: letters, digits, {@code _} and {@code -}, no spaces. */
    public static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{N}_-]+$");
    /** "Every other country of the scenario" in a relation. */
    public static final String ALL = "*";

    /**
     * One country.
     *
     * @param army  unit type id → soldiers
     * @param honor optional honor 0–100 of the ruler (null = derived from the personality)
     */
    public record CountrySpec(String name, int rgb, String ruler, String skin, int aggression, int expansion, int diplomacy, int wealth,
                              int maxChunks, int maxSoldiers, int x, int z, long treasury, Map<String, Integer> army, int settlers, long food,
                              Integer honor) {

        public CountrySpec {
            army = army == null ? Map.of() : Map.copyOf(army);
            skin = skin == null ? "" : skin.trim();
        }

        /** Without honor (derived). */
        public CountrySpec(final String name, final int rgb, final String ruler, final String skin, final int aggression, final int expansion,
                           final int diplomacy, final int wealth, final int maxChunks, final int maxSoldiers, final int x, final int z,
                           final long treasury, final Map<String, Integer> army, final int settlers, final long food) {
            this(name, rgb, ruler, skin, aggression, expansion, diplomacy, wealth, maxChunks, maxSoldiers, x, z, treasury, army, settlers, food, null);
        }

        public String hex() {
            return String.format("#%06X", this.rgb & 0xFFFFFF);
        }
    }

    /**
     * A starting relation between {@code a} and {@code b} ({@link #ALL} = every other country).
     *
     * @param relation {@code ALLIANCE} or null (no change; wars are never set at the start)
     * @param opinion  opinion of {@code a} about {@code b} (and back when {@code mutual}), null = unchanged
     * @param stance   stance of {@code a} towards {@code b} (and back when {@code mutual}), null = unchanged
     * @param mutual   opinion and stance apply in both directions
     */
    public record RelationSpec(String a, String b, String relation, Integer opinion, NpcStance stance, boolean mutual) {
    }

    /** One directed opinion/stance after expanding {@link #ALL} and {@code mutual}. */
    public record Attitude(String from, String to, Integer opinion, NpcStance stance) {
    }

    public CountrySpec country(final String name) {
        for (final CountrySpec c : this.countries) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    /** Directed attitudes ({@code *} expanded to every other country, mutual relations in both directions). */
    public List<Attitude> attitudes() {
        final List<Attitude> out = new ArrayList<>();
        for (final RelationSpec r : this.relations) {
            if (r.opinion() == null && r.stance() == null) {
                continue;
            }
            for (final String b : this.targets(r)) {
                out.add(new Attitude(r.a(), b, r.opinion(), r.stance()));
                if (r.mutual()) {
                    out.add(new Attitude(b, r.a(), r.opinion(), r.stance()));
                }
            }
        }
        return out;
    }

    /** Alliances as pairs (never with {@code *}). */
    public List<String[]> alliances() {
        final List<String[]> out = new ArrayList<>();
        for (final RelationSpec r : this.relations) {
            if ("ALLIANCE".equals(r.relation()) && !ALL.equals(r.b())) {
                out.add(new String[] {r.a(), r.b()});
            }
        }
        return out;
    }

    private List<String> targets(final RelationSpec r) {
        if (!ALL.equals(r.b())) {
            return List.of(r.b());
        }
        final List<String> all = new ArrayList<>();
        for (final CountrySpec c : this.countries) {
            if (!c.name().equalsIgnoreCase(r.a())) {
                all.add(c.name());
            }
        }
        return all;
    }

    /**
     * Problems that block loading (Polish), empty when fine.
     *
     * @param unitTypes   unit type ids of the config (army keys must be among them); null = not checked
     * @param nameMaxLen  Kraje's {@code country.name-max-length}
     */
    public List<String> validate(final Set<String> unitTypes, final int nameMinLen, final int nameMaxLen) {
        final List<String> errors = new ArrayList<>();
        if (this.countries.isEmpty()) {
            errors.add("Scenariusz nie ma żadnego państwa.");
        }
        final Set<String> names = new HashSet<>();
        final Map<Integer, String> colors = new HashMap<>();
        for (final CountrySpec c : this.countries) {
            final String n = c.name() == null ? "" : c.name();
            final String who = n.isEmpty() ? "(bez nazwy)" : n;
            if (n.length() < nameMinLen || n.length() > nameMaxLen) {
                errors.add(who + ": nazwa musi mieć " + nameMinLen + "–" + nameMaxLen + " znaków.");
            }
            if (!n.isEmpty() && !NAME.matcher(n).matches()) {
                errors.add(who + ": nazwa może mieć tylko litery, cyfry, _ i - (bez spacji).");
            }
            if (!names.add(n.toLowerCase(Locale.ROOT))) {
                errors.add(who + ": nazwa powtarza się.");
            }
            final String other = colors.putIfAbsent(c.rgb() & 0xFFFFFF, n);
            if (other != null) {
                errors.add(who + ": kolor " + c.hex() + " ma już " + other + ".");
            }
            for (final int trait : new int[] {c.aggression(), c.expansion(), c.diplomacy(), c.wealth()}) {
                if (trait < 0 || trait > 100) {
                    errors.add(who + ": agresja, ekspansja, dyplomacja i bogactwo muszą być w zakresie 0–100.");
                    break;
                }
            }
            if (c.honor() != null && (c.honor() < 0 || c.honor() > 100)) {
                errors.add(who + ": honor musi być w zakresie 0–100 (albo pominięty = liczony z osobowości).");
            }
            if (c.maxChunks() < 1 || c.maxSoldiers() < 0) {
                errors.add(who + ": max-chunki ≥ 1, max-zolnierze ≥ 0.");
            }
            if (Math.abs(c.x()) > 29_000_000 || Math.abs(c.z()) > 29_000_000) {
                errors.add(who + ": pozycja poza światem.");
            }
            if (c.treasury() < 0 || c.settlers() < 0 || c.settlers() > 500 || c.food() < 0) {
                errors.add(who + ": skarbiec, osadnicy (0–500) i jedzenie nie mogą być ujemne.");
            }
            for (final Map.Entry<String, Integer> e : c.army().entrySet()) {
                if (e.getValue() == null || e.getValue() < 0 || e.getValue() > 100) {
                    errors.add(who + ": wojsko " + e.getKey() + " musi mieć 0–100 żołnierzy.");
                } else if (unitTypes != null && !unitTypes.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                    errors.add(who + ": nieznany typ jednostki " + e.getKey() + ".");
                }
            }
            if (c.ruler() == null || c.ruler().isBlank() || c.ruler().length() > 32) {
                errors.add(who + ": władca musi mieć 1–32 znaki.");
            }
            if (!c.skin().isEmpty() && !c.skin().matches("[A-Za-z0-9_]{1,16}")) {
                errors.add(who + ": skin to nick Mojang (1–16 znaków: litery, cyfry, _).");
            }
        }
        for (final RelationSpec r : this.relations) {
            if (this.country(r.a()) == null) {
                errors.add("Relacja: nieznane państwo " + r.a() + ".");
            }
            if (!ALL.equals(r.b()) && this.country(r.b()) == null) {
                errors.add("Relacja: nieznane państwo " + r.b() + ".");
            }
            if (r.a().equalsIgnoreCase(r.b())) {
                errors.add("Relacja: " + r.a() + " z samym sobą.");
            }
            if (r.opinion() != null && (r.opinion() < -100 || r.opinion() > 100)) {
                errors.add("Relacja " + r.a() + " – " + r.b() + ": opinia musi być w zakresie -100..100.");
            }
            if ("ALLIANCE".equals(r.relation()) && ALL.equals(r.b())) {
                errors.add("Relacja " + r.a() + " – *: sojusz ze wszystkimi nie jest dozwolony.");
            }
        }
        if (this.villageRadius < 0 || this.spawnDistance < 0 || this.minDistance < 0) {
            errors.add("Odległości nie mogą być ujemne.");
        }
        return errors;
    }

    /** Pairs of colours that are hard to tell apart on the map (RGB distance below {@code threshold}). */
    public List<String> similarColors(final double threshold) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < this.countries.size(); i++) {
            for (int j = i + 1; j < this.countries.size(); j++) {
                final int a = this.countries.get(i).rgb();
                final int b = this.countries.get(j).rgb();
                final double d = Math.sqrt(Math.pow(((a >> 16) & 255) - ((b >> 16) & 255), 2) + Math.pow(((a >> 8) & 255) - ((b >> 8) & 255), 2)
                    + Math.pow((a & 255) - (b & 255), 2));
                if (d < threshold) {
                    out.add(this.countries.get(i).name() + " / " + this.countries.get(j).name());
                }
            }
        }
        return out;
    }
}
