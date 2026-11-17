// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * Hook for a country flag picture on the stern ensign of a ship model. A plugin that owns uploaded flag images
 * registers an implementation in Bukkit's {@code ServicesManager} (type {@code FlagArt}); Armia asks it every
 * few seconds and shows the returned item (usually an item with {@code item_model} pointing at the flag model in
 * the server resource pack) in place of the dyed cloth. Without a provider, {@code modele.yml: flagi.model-przedmiotu}
 * ({@code {kraj}} = country UUID) can name such an item model; otherwise the ensign is dyed in the country colour.
 *
 * <p>The item is rendered in the space of the {@code bandera} bone: a flat model 11×12 px (Blockbench units),
 * hoisted at the staff, extending towards the stern.
 */
@FunctionalInterface
public interface FlagArt {
    Optional<ItemStack> flag(UUID country);
}
