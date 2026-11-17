// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

/**
 * Lets other plugins (Armia) add fighters to siege calculations. Register any number of implementations in
 * Bukkit's ServicesManager; Kraje sums {@link #presence} of all of them with the players it counts itself.
 * Called on the main thread, about once per second per running siege — keep it cheap.
 */
public interface SiegeContributor {

    /**
     * Strength of {@code countryId}'s non-player fighters (e.g. NPC soldiers) inside chunk ({@code cx}, {@code cz}).
     * One average soldier ≈ 1.0, comparable to one player.
     */
    double presence(java.util.UUID countryId, String world, int cx, int cz);
}
