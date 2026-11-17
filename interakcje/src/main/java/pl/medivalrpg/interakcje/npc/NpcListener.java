// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.npc;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;

/** Keeps the NPC work animations in step with chunk loading and entity removal. */
public final class NpcListener implements Listener {
    private final NpcAnimationService service;

    public NpcListener(NpcAnimationService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity entity : e.getEntities()) {
            service.adopt(entity);
        }
    }

    /** Death, removal and chunk unload: the tracker goes, the stored state stays on a saved entity. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent e) {
        service.forget(e.getEntity());
    }
}
