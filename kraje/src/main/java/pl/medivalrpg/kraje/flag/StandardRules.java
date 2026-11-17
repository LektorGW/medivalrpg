// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.kraje.model.Rank;

/**
 * Rules of issuing country standards (pure, unit-tested): settings of {@code flags.standards}, the price (materials from
 * the player's inventory plus a fee from the country treasury — a transfer inside the closed money pool, never new
 * money), the per-country daily limit and its persistent log.
 */
public final class StandardRules {

    private StandardRules() {
    }

    /** A — vanilla banner with the approximated pattern; B — the item with the exact flag (resource pack model). */
    public enum Kind {
        BANNER("sztandar"), ITEM("przedmiot");

        private final String key;

        Kind(final String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }
    }

    /**
     * @param enabled      this kind can be issued
     * @param treasuryCost md taken from the country treasury (Skarbiec withdraw = move to world:payments)
     * @param materials    material key → count; key {@code WOOL} means any wool
     * @param dailyLimit   per country and day (0 = none may be issued)
     */
    public record Cost(boolean enabled, long treasuryCost, Map<String, Integer> materials, int dailyLimit) {
        public Cost {
            materials = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        }
    }

    /**
     * {@code flags.standards}.
     *
     * @param maxPerChunk placed standards (option B) per chunk
     */
    public record Settings(boolean enabled, ZoneId zone, Cost banner, Cost item, int maxPerChunk) {

        public static final Settings DEFAULT = new Settings(true, ZoneId.of("Europe/Warsaw"),
            new Cost(true, 100, materials(List.of("WOOL:6", "STICK:1")), 8),
            new Cost(true, 500, materials(List.of("WOOL:6", "STICK:2")), 4), 4);

        public Cost cost(final Kind kind) {
            return kind == Kind.BANNER ? this.banner : this.item;
        }

        public static Settings from(final ConfigurationSection s) {
            if (s == null) {
                return DEFAULT;
            }
            ZoneId zone = DEFAULT.zone;
            try {
                zone = ZoneId.of(s.getString("time-zone", "Europe/Warsaw").trim());
            } catch (final RuntimeException ignored) {
                // keep the default zone
            }
            return new Settings(s.getBoolean("enabled", true), zone, cost(s.getConfigurationSection("banner"), DEFAULT.banner),
                cost(s.getConfigurationSection("item"), DEFAULT.item), Math.clamp(s.getInt("placed.max-per-chunk", 4), 1, 64));
        }

        private static Cost cost(final ConfigurationSection s, final Cost d) {
            if (s == null) {
                return d;
            }
            return new Cost(s.getBoolean("enabled", d.enabled()), Math.max(0, s.getLong("treasury-cost", d.treasuryCost())),
                s.isList("materials") ? materials(s.getStringList("materials")) : d.materials(),
                Math.max(0, s.getInt("daily-limit", d.dailyLimit())));
        }
    }

    // ------------------------------------------------------------------ materials

    /** {@code ["WOOL:6", "STICK:1"]} → {WOOL=6, STICK=1}; malformed entries are skipped, the same key is summed. */
    public static Map<String, Integer> materials(final List<String> spec) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final String raw : spec) {
            if (raw == null) {
                continue;
            }
            final String[] parts = raw.trim().toUpperCase(Locale.ROOT).split(":");
            if (parts.length != 2 || !parts[0].matches("[A-Z0-9_]{2,64}")) {
                continue;
            }
            try {
                final int n = Integer.parseInt(parts[1].trim());
                if (n > 0 && n <= 64 * 36) {
                    out.merge(parts[0], n, Integer::sum);
                }
            } catch (final NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    /** Whether an inventory material (Bukkit name) satisfies a cost key ({@code WOOL} = any {@code *_WOOL}). */
    public static boolean matches(final String key, final String material) {
        if (key.equals("WOOL")) {
            return material.endsWith("_WOOL");
        }
        return key.equals(material);
    }

    /** What is missing: key → how many more are needed. {@code have} = material name → count in the inventory. */
    public static Map<String, Integer> missing(final Map<String, Integer> need, final Map<String, Integer> have) {
        final Map<String, Integer> left = new LinkedHashMap<>(have);
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Integer> e : need.entrySet()) {
            int want = e.getValue();
            for (final Map.Entry<String, Integer> h : left.entrySet()) {
                if (want <= 0) {
                    break;
                }
                if (matches(e.getKey(), h.getKey()) && h.getValue() > 0) {
                    final int take = Math.min(want, h.getValue());
                    h.setValue(h.getValue() - take);
                    want -= take;
                }
            }
            if (want > 0) {
                out.put(e.getKey(), want);
            }
        }
        return out;
    }

    /** "6× wełna (dowolny kolor), 1× patyk" — names for the common keys, Bukkit names otherwise. */
    public static String describe(final Map<String, Integer> materials) {
        final StringBuilder b = new StringBuilder();
        for (final Map.Entry<String, Integer> e : materials.entrySet()) {
            if (!b.isEmpty()) {
                b.append(", ");
            }
            b.append(e.getValue()).append("× ").append(switch (e.getKey()) {
                case "WOOL" -> "wełna (dowolny kolor)";
                case "STICK" -> "patyk";
                case "WHITE_WOOL" -> "biała wełna";
                case "STRING" -> "nić";
                case "LEATHER" -> "skóra";
                default -> e.getKey().toLowerCase(Locale.ROOT);
            });
        }
        return b.isEmpty() ? "brak" : b.toString();
    }

    // ------------------------------------------------------------------ the decision

    /**
     * Why the player may not take a standard now, or null when he may. Checked again right before paying.
     *
     * @param rank          the player's rank in the country (null = not a member)
     * @param needed        lowest rank that may order standards ({@code flags.upload-rank})
     * @param usedToday     standards of this kind the country took today
     * @param missing       {@link #missing} result
     * @param economyActive Skarbiec present (without it the fee is not charged)
     * @param balance       treasury balance in md
     */
    public static String refusal(final Kind kind, final Cost cost, final boolean settingsEnabled, final Rank rank, final boolean npc,
                                 final Rank needed, final int usedToday, final Map<String, Integer> missing,
                                 final boolean economyActive, final long balance, final java.util.function.LongFunction<String> money) {
        if (!settingsEnabled || !cost.enabled()) {
            return kind == Kind.BANNER ? "Wydawanie sztandarów jest wyłączone." : "Wydawanie przedmiotu „Sztandar kraju” jest wyłączone.";
        }
        if (npc) {
            return "Sztandary państwa NPC wydaje tylko administracja.";
        }
        if (rank == null) {
            return "Nie należysz do tego kraju.";
        }
        if (!rank.atLeast(needed)) {
            return "Sztandary kraju zamawia " + (needed == Rank.RULER ? "Władca." : "Władca albo Doradca.");
        }
        if (cost.dailyLimit() <= 0 || usedToday >= cost.dailyLimit()) {
            return "Limit na dziś wyczerpany (" + cost.dailyLimit() + " dziennie na kraj). Spróbuj jutro.";
        }
        if (!missing.isEmpty()) {
            return "Brakuje materiałów w ekwipunku: " + describe(missing) + ".";
        }
        if (economyActive && cost.treasuryCost() > 0 && balance < cost.treasuryCost()) {
            return "W skarbcu kraju brakuje pieniędzy: potrzeba " + money.apply(cost.treasuryCost()) + ", jest " + money.apply(balance) + ".";
        }
        return null;
    }

    // ------------------------------------------------------------------ daily log

    /** Standards issued per country on the current day (zone of the settings); persisted as {@code flags/standards.json}. */
    public static final class Log {
        private String day = "";
        private final Map<UUID, int[]> counts = new LinkedHashMap<>();

        public static String day(final long now, final ZoneId zone) {
            return LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).toString();
        }

        private void roll(final String today) {
            if (!today.equals(this.day)) {
                this.day = today;
                this.counts.clear();
            }
        }

        public int used(final String today, final UUID country, final Kind kind) {
            this.roll(today);
            final int[] c = this.counts.get(country);
            return c == null ? 0 : c[kind.ordinal()];
        }

        public void add(final String today, final UUID country, final Kind kind) {
            this.roll(today);
            this.counts.computeIfAbsent(country, k -> new int[Kind.values().length])[kind.ordinal()]++;
        }

        public String toJson() {
            final JsonObject root = new JsonObject();
            root.addProperty("day", this.day);
            final JsonObject countries = new JsonObject();
            for (final Map.Entry<UUID, int[]> e : this.counts.entrySet()) {
                final JsonObject o = new JsonObject();
                for (final Kind k : Kind.values()) {
                    o.addProperty(k.key(), e.getValue()[k.ordinal()]);
                }
                countries.add(e.getKey().toString(), o);
            }
            root.add("countries", countries);
            return root.toString();
        }

        public static Log fromJson(final String json) {
            final Log log = new Log();
            if (json == null || json.isBlank()) {
                return log;
            }
            try {
                final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                log.day = root.has("day") ? root.get("day").getAsString() : "";
                if (root.has("countries")) {
                    for (final Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("countries").entrySet()) {
                        final int[] c = new int[Kind.values().length];
                        final JsonObject o = e.getValue().getAsJsonObject();
                        for (final Kind k : Kind.values()) {
                            c[k.ordinal()] = o.has(k.key()) ? Math.max(0, o.get(k.key()).getAsInt()) : 0;
                        }
                        log.counts.put(UUID.fromString(e.getKey()), c);
                    }
                }
            } catch (final RuntimeException e) {
                return new Log();
            }
            return log;
        }
    }
}
