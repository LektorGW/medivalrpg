// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import pl.medivalrpg.wladcy.engine.ChatSession;

/** One audience: a player talking to an NPC ruler. Main thread only. */
public final class Conversation {

    final UUID player;
    final String playerName;
    final UUID npc;
    final UUID rulerEntity;
    final Location rulerLocation;
    /** Ruler's name in the country colour, for chat lines. */
    final Component rulerLabel;
    final String rulerName;
    final ChatSession session;
    long lastActivity;

    Conversation(final UUID player, final String playerName, final UUID npc, final UUID rulerEntity, final Location rulerLocation,
                 final Component rulerLabel, final String rulerName, final ChatSession session, final long now) {
        this.player = player;
        this.playerName = playerName;
        this.npc = npc;
        this.rulerEntity = rulerEntity;
        this.rulerLocation = rulerLocation;
        this.rulerLabel = rulerLabel;
        this.rulerName = rulerName;
        this.session = session;
        this.lastActivity = now;
    }

    public UUID npc() {
        return this.npc;
    }

    public UUID player() {
        return this.player;
    }

    public ChatSession session() {
        return this.session;
    }
}
