// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

/**
 * Guard post of an NPC country: soldiers guarding one chunk.
 *
 * @param name     Polish name ("Stolica", "Posterunek 12, -4"...)
 * @param world    world name
 * @param x        block x of the post
 * @param z        block z of the post
 * @param soldiers soldiers assigned to it
 */
public record NpcOutpost(String name, String world, int x, int z, int soldiers) {
}
