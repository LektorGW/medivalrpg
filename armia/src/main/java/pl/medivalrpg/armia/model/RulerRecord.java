// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.model;

import java.util.UUID;

/** NPC ruler standing in an NPC country's capital ({@code rulers.json}). */
public final class RulerRecord {

    public UUID country;
    public String name;
    /** Mojang name used for the skin ("" = default mannequin skin). */
    public String skin = "";
    public Pos pos;
    /** Current entity, null when not spawned. */
    public UUID entity;
    /** Invisible {@code Interaction} hitbox in front of the ruler (clicks), null when not spawned. */
    public UUID hitbox;
    /** Stable profile UUID for the default skin when {@link #skin} is empty. */
    public UUID skinId;

    public RulerRecord() {
    }

    public RulerRecord(final UUID country, final String name, final String skin, final Pos pos) {
        this.country = country;
        this.name = name;
        this.skin = skin == null ? "" : skin;
        this.pos = pos;
    }
}
