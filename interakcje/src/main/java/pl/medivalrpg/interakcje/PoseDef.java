// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Solo body pose ({@code sit:} / {@code lay:} in config.yml). Pure data, unit-testable.
 *
 * @param pose         for {@code lay}: the entity pose name ({@code SLEEPING} or {@code SWIMMING}); unused for sit
 * @param animatedPose for {@code lay} while a lying animation plays: {@code NONE} (the model shows the
 *                     pose, the real player keeps standing), {@code SLEEPING} or {@code SWIMMING}
 * @param variant      for {@code sit}: {@code AUTO}, {@code FLOOR} or {@code CHAIR} (which sitting animation)
 */
public record PoseDef(
        String id,
        boolean enabled,
        String name,
        String description,
        String command,
        List<String> aliases,
        long cooldownMs,
        String broadcast,
        boolean rightClickStairs,
        double seatOffset,
        String pose,
        String animatedPose,
        String variant) {

    public static final String SIT = "sit";
    public static final String LAY = "lay";

    public String permission() {
        return "interakcje." + id;
    }

    public String cooldownKey() {
        return "pose:" + id;
    }

    /** All command labels (command + aliases). */
    public List<String> labels() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        out.add(command);
        out.addAll(aliases);
        return out;
    }

    public static PoseDef parse(String id, ConfigurationSection s, Consumer<String> warn) {
        boolean sit = SIT.equals(id);
        String defCommand = sit ? "usiadz" : "poloz";
        List<String> defAliases = sit ? List.of("sit", "siad") : List.of("lay", "lez");
        if (s == null) {
            s = new org.bukkit.configuration.MemoryConfiguration();
        }
        String command = InteractionLoader.normalizeLabel(s.getString("command", defCommand));
        if (command == null) {
            warn.accept(id + ".command: niepoprawna nazwa – używam " + defCommand + ".");
            command = defCommand;
        }
        Set<String> aliases = new LinkedHashSet<>();
        List<String> rawAliases = s.isList("aliases") ? s.getStringList("aliases") : defAliases;
        for (String a : rawAliases) {
            String n = InteractionLoader.normalizeLabel(a);
            if (n == null) {
                warn.accept(id + ".aliases: pomijam niepoprawny alias '" + a + "'.");
            } else if (!n.equals(command)) {
                aliases.add(n);
            }
        }
        String pose = s.getString("pose", "SLEEPING").trim().toUpperCase(Locale.ROOT);
        if (!sit && !pose.equals("SLEEPING") && !pose.equals("SWIMMING")) {
            warn.accept(id + ".pose: dozwolone SLEEPING albo SWIMMING – ustawiam SLEEPING.");
            pose = "SLEEPING";
        }
        String animatedPose = s.getString("animated-pose", "NONE").trim().toUpperCase(Locale.ROOT);
        if (!sit && !animatedPose.equals("NONE") && !animatedPose.equals("SLEEPING") && !animatedPose.equals("SWIMMING")) {
            warn.accept(id + ".animated-pose: dozwolone NONE, SLEEPING albo SWIMMING – ustawiam NONE.");
            animatedPose = "NONE";
        }
        String variant = s.getString("variant", "AUTO").trim().toUpperCase(Locale.ROOT);
        if (sit && !variant.equals("AUTO") && !variant.equals("FLOOR") && !variant.equals("CHAIR")) {
            warn.accept(id + ".variant: dozwolone AUTO, FLOOR albo CHAIR – ustawiam AUTO.");
            variant = "AUTO";
        }
        return new PoseDef(
                id,
                s.getBoolean("enabled", true),
                s.getString("name", sit ? "Siadanie" : "Leżenie"),
                s.getString("description", ""),
                command,
                List.copyOf(aliases),
                Math.round(Math.max(0, s.getDouble("cooldown", 3)) * 1000.0),
                s.getString("broadcast", ""),
                sit && s.getBoolean("right-click-stairs", true),
                s.getDouble("seat-height-offset", 0.0),
                sit ? "" : pose,
                sit ? "" : animatedPose,
                sit ? variant : "");
    }
}
