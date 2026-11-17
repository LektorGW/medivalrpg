// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.listener;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import pl.medivalrpg.kraje.api.event.CountryDisbandEvent;
import pl.medivalrpg.kraje.api.event.RelationChangeEvent;
import pl.medivalrpg.kraje.api.event.SiegeEndEvent;
import pl.medivalrpg.kraje.api.event.SiegeStartEvent;
import pl.medivalrpg.kraje.api.event.VassalageStartEvent;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.zycie.ZyciePlugin;
import pl.medivalrpg.zycie.service.MoodService;

/** Kraje events (wars, sieges, vassals) → mood modifiers and festivals; clean-up of our display entities. */
public final class ZycieListener implements Listener {

    private final ZyciePlugin plugin;

    public ZycieListener(final ZyciePlugin plugin) {
        this.plugin = plugin;
    }

    private MoodService mood() {
        return this.plugin.mood();
    }

    private void recalcSoon(final UUID... countries) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            for (final UUID c : countries) {
                if (c != null) {
                    this.mood().recalc(c);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSiegeStart(final SiegeStartEvent e) {
        this.recalcSoon(e.defender(), e.attacker());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSiegeEnd(final SiegeEndEvent e) {
        this.plugin.sieges().onSiegeEnd(e.siegeId());
        if (e.conquered()) {
            this.mood().addEvent(e.attacker(), "zwyciestwo");
            this.mood().addEvent(e.defender(), "utrata_ziemi");
            this.mood().startFestival(e.attacker(), this.plugin.messages().raw("reason-victory"), false);
        } else {
            this.mood().addEvent(e.defender(), "obrona");
        }
        this.recalcSoon(e.defender(), e.attacker());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRelation(final RelationChangeEvent e) {
        if (e.oldRelation() == Relation.WAR && e.newRelation() != Relation.WAR) {
            this.mood().addEvent(e.countryA(), "pokoj_zawarty");
            this.mood().addEvent(e.countryB(), "pokoj_zawarty");
        } else if (e.newRelation() == Relation.WAR && e.oldRelation() != Relation.WAR) {
            this.mood().addEvent(e.countryA(), "wojna_wypowiedziana");
            this.mood().addEvent(e.countryB(), "wojna_wypowiedziana");
        }
        this.recalcSoon(e.countryA(), e.countryB());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVassal(final VassalageStartEvent e) {
        this.mood().addEvent(e.overlord(), "zwyciestwo");
        this.mood().addEvent(e.vassal(), "porazka");
        this.mood().startFestival(e.overlord(), this.plugin.messages().raw("reason-victory"), false);
        this.recalcSoon(e.overlord(), e.vassal());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisband(final CountryDisbandEvent e) {
        this.mood().remove(e.countryId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(final EntitiesLoadEvent e) {
        for (final Entity entity : e.getEntities()) {
            if ((entity instanceof TextDisplay && this.plugin.bubbles().isOurs(entity)) || this.plugin.festivals().isOurFirework(entity)) {
                entity.remove();
            }
        }
        this.plugin.sieges().onEntitiesLoad(e.getEntities());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFireworkDamage(final EntityDamageByEntityEvent e) {
        if (this.plugin.festivals().isOurFirework(e.getDamager())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPluginEnable(final PluginEnableEvent e) {
        if ("Postep".equals(e.getPlugin().getName())) {
            Bukkit.getScheduler().runTask(this.plugin, this.plugin::hookPostep);
        }
    }
}
