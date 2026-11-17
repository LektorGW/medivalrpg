// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

/**
 * Result of an action that another country may accept (proposal, tribute demand).
 *
 * @param ok       the action was delivered (false = invalid / not allowed, see {@code message})
 * @param message  short Polish summary or error
 * @param accepted true/false when an NPC country decided at once; null while a player country has to answer
 */
public record NpcReply(boolean ok, String message, Boolean accepted) {

    public static NpcReply fail(final String message) {
        return new NpcReply(false, message == null ? "" : message, null);
    }
}
