// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ai;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kraje.api.event.EmbassyRequestEvent;
import pl.medivalrpg.kraje.api.event.UnionProposalEvent;

/**
 * Kraje API 1.5 (wave 17): union proposals and embassy requests addressed to NPC countries. Kraje fires them only when
 * its own automatic answer is switched off ({@code union.npc-auto-refuse: false}, {@code embassy.npc-auto-decide: false});
 * the AI answers one tick later (never re-enter Kraje inside its own event). Registered separately so an older Kraje
 * without these events does not break Armia.
 */
public final class BondsListener implements Listener {

    private final Plugin plugin;
    private final NpcAiService ai;

    public BondsListener(final Plugin plugin, final NpcAiService ai) {
        this.plugin = plugin;
        this.ai = ai;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnion(final UnionProposalEvent event) {
        if (event.toNpc()) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.ai.answerUnion(event.to(), event.from()), 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEmbassy(final EmbassyRequestEvent event) {
        if (event.toNpc()) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.ai.answerEmbassy(event.host(), event.guest()), 20L);
        }
    }
}
