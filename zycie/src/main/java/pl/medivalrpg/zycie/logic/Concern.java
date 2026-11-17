// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.Map;

/**
 * One worry of the citizens before formatting: {@code key} selects the Polish template in config.yml
 * ({@code zmartwienia.<key>}), {@code args} fill its placeholders, {@code weight} orders them (bigger = more important).
 */
public record Concern(String key, Map<String, String> args, int weight) {

    public static Concern of(final String key, final int weight) {
        return new Concern(key, Map.of(), weight);
    }

    public static Concern of(final String key, final int weight, final String argName, final String argValue) {
        return new Concern(key, Map.of(argName, argValue), weight);
    }
}
