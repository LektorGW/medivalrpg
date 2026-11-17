// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.api;

import org.bukkit.entity.Entity;

import java.util.Set;

/**
 * Work animations of NPC citizens (Mannequins with a player skin), provided by the Interakcje plugin
 * through the Bukkit {@code ServicesManager}:
 *
 * <pre>{@code
 * RegisteredServiceProvider<WorkAnimations> rsp = Bukkit.getServicesManager().getRegistration(WorkAnimations.class);
 * WorkAnimations work = rsp == null ? null : rsp.getProvider();
 * if (work != null) work.play(mannequin, "kilof", true);
 * }</pre>
 *
 * A module that must not compile against this interface can call the same methods by reflection on
 * the provider (see {@code interakcje/INTEGRATION-NPC.md}).
 *
 * <p>The call states <em>what the NPC is doing</em>. Interakcje decides when the animation is actually
 * shown: only while a player (with the resource pack) is within {@code npc-animations.range}, for at
 * most {@code npc-animations.max-active} NPCs at once and within a per-tick time budget; the nearest
 * NPCs win. A looping work animation is stored on the entity (PDC) and survives chunk reloads and
 * restarts until {@link #stop(Entity)}. All methods: main thread only.
 *
 * <p>Animation names: {@code budowa}, {@code noszenie_plecy}, {@code noszenie_rece}, {@code kilof},
 * {@code motyka}, {@code siekiera}, {@code kowadlo}, {@code zaproszenie}, {@code salut}, {@code straz},
 * {@code ziewanie}, {@code odpoczynek} ({@link #animations()}); a clip name of the catalogue works too.
 */
public interface WorkAnimations {

    /**
     * Sets the work animation of an NPC.
     *
     * @param entity    a Mannequin (other entity types only if allowed in {@code npc-animations.entity-types})
     * @param animation work animation name or clip name
     * @param loop      {@code true}: the NPC keeps doing it (stored on the entity); {@code false}: a one-off
     *                  gesture (salute, yawn) that plays now if someone sees the NPC, after which the NPC
     *                  returns to its looping work animation, if any
     * @return {@code false} when the animation or the entity type is unknown / not allowed, or a one-off
     *         gesture could not be shown right now
     */
    boolean play(Entity entity, String animation, boolean loop);

    /** Ends the work animation of the NPC and forgets it (also the stored one). */
    void stop(Entity entity);

    /** The looping work animation set for the NPC (even when it is not shown right now), or {@code null}. */
    String current(Entity entity);

    /** The NPC's animation is being rendered for nearby players right now. */
    boolean isShown(Entity entity);

    /** Known work animation names. */
    Set<String> animations();
}
