// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.embassy;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import pl.medivalrpg.kraje.api.event.ChunkOwnerChangeEvent;
import pl.medivalrpg.kraje.util.ChunkKeys;

/** Closes an embassy as soon as its chunk stops being land of the host (conquest, admin, bankruptcy). */
public final class EmbassyListener implements Listener {

    private final EmbassyService embassies;

    public EmbassyListener(final EmbassyService embassies) {
        this.embassies = embassies;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkOwnerChange(final ChunkOwnerChangeEvent event) {
        this.embassies.onChunkOwnerChange(event.world(), ChunkKeys.key(event.cx(), event.cz()), event.newOwner());
    }
}
