// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ai;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kraje.api.event.CapitulationOfferEvent;
import pl.medivalrpg.kraje.api.event.VassalageEndEvent;
import pl.medivalrpg.kraje.api.event.VassalageStartEvent;

/**
 * Capitulation and vassalage events of Kraje (API 1.2). A separate listener so an older Kraje without these event
 * classes only disables this part (registration fails with a {@link LinkageError} that ArmiaPlugin catches).
 */
public final class VassalListener implements Listener {

    private final Plugin plugin;
    private final NpcAiService ai;

    public VassalListener(final Plugin plugin, final NpcAiService ai) {
        this.plugin = plugin;
        this.ai = ai;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCapitulationOffer(final CapitulationOfferEvent event) {
        if (!event.toNpc() || this.ai.state(event.to()).isEmpty()) {
            return;
        }
        // Answer on the next second: never re-enter Kraje from inside its own event.
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.ai.answerCapitulation(event.to(), event.from(), event.days(),
            event.estimatedTribute(), event.tributeMin()), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVassalageStart(final VassalageStartEvent event) {
        this.ai.onVassalageStart(event.vassal(), event.overlord(), event.until());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVassalageEnd(final VassalageEndEvent event) {
        this.ai.onVassalageEnd(event.vassal(), event.overlord(), event.reason().name());
    }
}
