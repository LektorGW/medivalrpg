// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Parses the {@code interactions} section. No server access, so it is unit-testable. */
public final class InteractionLoader {
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+");
    /** Brigadier literal: anything without whitespace, slash or colon (Polish letters are fine). */
    private static final Pattern COMMAND = Pattern.compile("[^\\s/:]+");

    private InteractionLoader() {
    }

    /**
     * @param section  the {@code interactions} section (may be {@code null})
     * @param reserved command labels that interactions must not take (e.g. {@code interakcje}, {@code pusc})
     * @param warn     receives human-readable warnings about skipped / corrected entries
     * @return interactions by id, in config order
     */
    public static Map<String, InteractionDef> load(ConfigurationSection section, Set<String> reserved,
                                                   Consumer<String> warn) {
        Map<String, InteractionDef> out = new LinkedHashMap<>();
        if (section == null) {
            warn.accept("Brak sekcji 'interactions' w config.yml – żadna interakcja nie jest dostępna.");
            return out;
        }
        Map<String, String> usedLabels = new HashMap<>();
        for (String reservedName : reserved) {
            usedLabels.put(reservedName.toLowerCase(Locale.ROOT), "komendę pluginu");
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) {
                warn.accept("interactions." + key + ": to nie jest sekcja – pomijam.");
                continue;
            }
            String id = key.toLowerCase(Locale.ROOT);
            if (!ID.matcher(id).matches()) {
                warn.accept("interactions." + key + ": id może zawierać tylko a-z, 0-9, _ i - – pomijam.");
                continue;
            }
            InteractionDef def = parse(id, s, warn);
            if (def == null) {
                continue;
            }
            // command labels must be unique across interactions and plugin commands
            List<String> labels = new ArrayList<>();
            labels.add(def.command());
            labels.addAll(def.aliases());
            String clash = null;
            for (String label : labels) {
                String owner = usedLabels.get(label);
                if (owner != null) {
                    clash = label + " (zajęta przez " + owner + ")";
                    break;
                }
            }
            if (clash != null) {
                warn.accept("interactions." + id + ": komenda/alias " + clash + " – pomijam interakcję.");
                continue;
            }
            labels.forEach(l -> usedLabels.put(l, id));
            out.put(id, def);
        }
        return out;
    }

    static InteractionDef parse(String id, ConfigurationSection s, Consumer<String> warn) {
        String where = "interactions." + id;
        String command = normalizeLabel(s.getString("command", id));
        if (command == null) {
            warn.accept(where + ".command: niepoprawna nazwa komendy – pomijam.");
            return null;
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (String a : s.getStringList("aliases")) {
            String n = normalizeLabel(a);
            if (n == null) {
                warn.accept(where + ".aliases: pomijam niepoprawny alias '" + a + "'.");
            } else if (!n.equals(command)) {
                aliases.add(n);
            }
        }

        double range = s.getDouble("range", 3.0);
        if (!(range > 0)) {
            warn.accept(where + ".range musi być > 0 – ustawiam 3.");
            range = 3.0;
        }
        double cooldown = Math.max(0, s.getDouble("cooldown", 5));

        String targetMode = s.getString("target", "required").trim().toLowerCase(Locale.ROOT);
        boolean optional = switch (targetMode) {
            case "optional" -> true;
            case "required" -> false;
            default -> {
                warn.accept(where + ".target: nieznana wartość '" + targetMode
                        + "' (required|optional) – ustawiam required.");
                yield false;
            }
        };

        Set<String> exempt = new LinkedHashSet<>();
        for (String gm : s.getStringList("exempt-gamemodes")) {
            exempt.add(gm.trim().toUpperCase(Locale.ROOT));
        }

        InteractionDef.Effects effects = parseEffects(where + ".effects", s.getConfigurationSection("effects"), warn);
        InteractionDef.HoldSpec hold = parseHold(where + ".hold", s.getConfigurationSection("hold"), warn);

        int slot = s.getInt("slot", -1);
        if (slot < -1 || slot > 26) {
            warn.accept(where + ".slot poza zakresem 0–26 – ustawiam automatycznie.");
            slot = -1;
        }

        return new InteractionDef(
                id,
                s.getBoolean("enabled", true),
                s.getString("name", id),
                s.getString("description", ""),
                command,
                List.copyOf(aliases),
                s.getString("icon", "PAPER"),
                slot,
                s.getBoolean("consent", false),
                optional,
                range,
                Math.round(cooldown * 1000.0),
                Collections.unmodifiableSet(exempt),
                s.getString("request-text", "chce wykonać z tobą interakcję"),
                s.getString("broadcast", "<gray><i><sender> → <target></i></gray>"),
                s.getString("broadcast-no-target", "<gray><i><sender></i></gray>"),
                effects,
                hold,
                animationId(s.getString("animation", "")),
                animationId(s.getString("animation-no-target", "")),
                requirement(where, s.getString("requires", "none"), warn),
                s.getBoolean("grant-title", false),
                s.getBoolean("audience-bow", false));
    }

    static InteractionDef.Requirement requirement(String where, String raw, java.util.function.Consumer<String> warn) {
        String n = raw == null ? "none" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "", "none", "brak" -> InteractionDef.Requirement.NONE;
            case "ruler", "wladca", "władca" -> InteractionDef.Requirement.RULER;
            default -> {
                warn.accept(where + ".requires: nieznana wartość '" + raw + "' (none|ruler) – ustawiam none.");
                yield InteractionDef.Requirement.NONE;
            }
        };
    }

    /** {@code animation:} value: lower-case id, {@code null} when empty / "none". */
    static String animationId(String raw) {
        if (raw == null) {
            return null;
        }
        String n = raw.trim().toLowerCase(Locale.ROOT);
        return n.isEmpty() || n.equals("none") || n.equals("brak") ? null : n;
    }

    /** Lower-cases, strips a leading slash; {@code null} when the label is not a valid literal. */
    public static String normalizeLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String n = raw.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("/")) {
            n = n.substring(1);
        }
        return !n.isEmpty() && COMMAND.matcher(n).matches() ? n : null;
    }

    static InteractionDef.Effects parseEffects(String where, ConfigurationSection s, Consumer<String> warn) {
        if (s == null) {
            return InteractionDef.Effects.NONE;
        }
        InteractionDef.Swing swing = parseEnum(InteractionDef.Swing.class, s.getString("swing", "none"),
                InteractionDef.Swing.NONE, where + ".swing", warn);
        return new InteractionDef.Effects(
                swing,
                s.getBoolean("face-each-other", false),
                s.getBoolean("nod", false),
                Math.max(0, s.getDouble("knockback", 0)),
                s.getDouble("knockback-up", 0),
                parseParticles(where + ".particles", s.getMapList("particles"), warn),
                parseSounds(where + ".sounds", s.getMapList("sounds"), warn));
    }

    static InteractionDef.HoldSpec parseHold(String where, ConfigurationSection s, Consumer<String> warn) {
        if (s == null) {
            return null;
        }
        int duration = s.getInt("duration", 30);
        if (duration <= 0) {
            warn.accept(where + ".duration musi być > 0 – ustawiam 30.");
            duration = 30;
        }
        int interval = Math.max(2, s.getInt("interval-ticks", 10));
        double maxDistance = s.getDouble("max-distance", 4.0);
        if (!(maxDistance > 0)) {
            warn.accept(where + ".max-distance musi być > 0 – ustawiam 4.");
            maxDistance = 4.0;
        }
        return new InteractionDef.HoldSpec(duration, maxDistance, interval,
                parseParticles(where + ".particles", s.getMapList("particles"), warn));
    }

    static List<InteractionDef.ParticleSpec> parseParticles(String where, List<Map<?, ?>> list, Consumer<String> warn) {
        List<InteractionDef.ParticleSpec> out = new ArrayList<>();
        int i = 0;
        for (Map<?, ?> m : list) {
            String path = where + "[" + i++ + "]";
            Object type = m.get("type");
            if (type == null || type.toString().isBlank()) {
                warn.accept(path + ": brak 'type' – pomijam.");
                continue;
            }
            InteractionDef.Anchor at = parseEnum(InteractionDef.Anchor.class, str(m.get("at"), "between"),
                    InteractionDef.Anchor.BETWEEN, path + ".at", warn);
            out.add(new InteractionDef.ParticleSpec(
                    type.toString().trim(),
                    at,
                    num(m.get("height"), 1.6),
                    Math.max(1, (int) num(m.get("count"), 3)),
                    Math.max(0, num(m.get("spread"), 0.2))));
        }
        return List.copyOf(out);
    }

    static List<InteractionDef.SoundSpec> parseSounds(String where, List<Map<?, ?>> list, Consumer<String> warn) {
        List<InteractionDef.SoundSpec> out = new ArrayList<>();
        int i = 0;
        for (Map<?, ?> m : list) {
            String path = where + "[" + i++ + "]";
            Object sound = m.get("sound");
            if (sound == null || sound.toString().isBlank()) {
                warn.accept(path + ": brak 'sound' – pomijam.");
                continue;
            }
            out.add(new InteractionDef.SoundSpec(
                    sound.toString().trim(),
                    (float) Math.max(0, num(m.get("volume"), 1.0)),
                    (float) Math.clamp(num(m.get("pitch"), 1.0), 0.5, 2.0)));
        }
        return List.copyOf(out);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E def, String where,
                                                   Consumer<String> warn) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn.accept(where + ": nieznana wartość '" + raw + "' – ustawiam "
                    + def.name().toLowerCase(Locale.ROOT) + ".");
            return def;
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static double num(Object o, double def) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
    }
}
