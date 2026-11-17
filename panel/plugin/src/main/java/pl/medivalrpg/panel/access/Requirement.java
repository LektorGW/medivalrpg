// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.access;

/** Who may call an RPC method (PANEL.md: *, M, A, R, ADM, NPC managers). */
public enum Requirement {
    /** {@code *}: any logged-in user. */
    LOGGED_IN,
    /** {@code M}: member of the target country (any rank). */
    MEMBER,
    /** {@code A}: advisor or ruler of the target country. */
    ADVISOR,
    /**
     * {@code A/NPCM}: advisor or ruler of the target country; for an NPC target also its NPC managers (Postep: choosing
     * research and investing for an NPC country).
     */
    ADVISOR_OR_NPC_MANAGE,
    /** {@code R}: ruler of the target country. */
    RULER,
    /** Ruler of a player country, or the authorized manager of an NPC country. */
    RULER_OR_NPC_MANAGE,
    /** {@code admin.npc.*} with an id: admin, global NPC manager, or the manager assigned to that NPC country. */
    NPC_MANAGE,
    /** {@code admin.npc.create}: admin or global NPC manager. */
    NPC_CREATE,
    /** {@code ADM}: panel admin only. */
    ADMIN
}
