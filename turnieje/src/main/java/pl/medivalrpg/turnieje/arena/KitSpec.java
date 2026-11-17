// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.arena;

import java.util.Locale;
import java.util.Optional;

/** One kit entry from config: {@code "IRON_SWORD"} or {@code "ARROW:16"}. Material resolution happens at runtime. */
public record KitSpec(String material, int amount) {

    public static Optional<KitSpec> parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        final String[] parts = raw.trim().split(":");
        if (parts.length > 2 || parts[0].isBlank()) {
            return Optional.empty();
        }
        int amount = 1;
        if (parts.length == 2) {
            try {
                amount = Integer.parseInt(parts[1].trim());
            } catch (final NumberFormatException e) {
                return Optional.empty();
            }
            if (amount < 1 || amount > 64) {
                return Optional.empty();
            }
        }
        final String material = parts[0].trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        if (!material.matches("[A-Z0-9_]+")) {
            return Optional.empty();
        }
        return Optional.of(new KitSpec(material, amount));
    }
}
