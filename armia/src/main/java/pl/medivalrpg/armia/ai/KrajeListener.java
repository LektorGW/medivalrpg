// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ai;

import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.ruler.RulerService;
import pl.medivalrpg.armia.soldier.ArmyService;
import pl.medivalrpg.kraje.api.event.ChunkOwnerChangeEvent;
import pl.medivalrpg.kraje.api.event.CountryCreateEvent;
import pl.medivalrpg.kraje.api.event.CountryDisbandEvent;
import pl.medivalrpg.kraje.api.event.RelationChangeEvent;
import pl.medivalrpg.kraje.api.event.RelationProposalEvent;
import pl.medivalrpg.kraje.api.event.SiegeEndEvent;
import pl.medivalrpg.kraje.api.event.SiegeStartEvent;
import pl.medivalrpg.kraje.api.event.TreasuryTickEvent;
import pl.medivalrpg.kraje.model.Relation;

/** Kraje events: proposals to NPC countries, relation/siege tracking, country lifecycle, daily treasury tick. */
public final class KrajeListener implements Listener {

    private final Plugin plugin;
    private final NpcAiService ai;
    private final ArmyService army;
    private final RulerService rulers;
    private final Supplier<ArmiaConfig> config;
    private final UpkeepService upkeep;

    public KrajeListener(final Plugin plugin, final NpcAiService ai, final ArmyService army, final RulerService rulers,
                         final Supplier<ArmiaConfig> config, final UpkeepService upkeep) {
        this.plugin = plugin;
        this.ai = ai;
        this.army = army;
        this.rulers = rulers;
        this.config = config;
        this.upkeep = upkeep;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProposal(final RelationProposalEvent event) {
        if (event.proposed() != Relation.ALLIANCE && event.proposed() != Relation.NEUTRAL) {
            return;
        }
        if (this.ai.consumeDirectAnswer(event.from(), event.to())) {
            return; // answered synchronously (panel)
        }
        if (!this.ai.isActive(event.to())) {
            if (this.ai.state(event.to()).isPresent()) {
                this.ai.rememberProposal(event.from(), event.to(), event.proposed());
            }
            return;
        }
        // Answer on the next tick: never re-enter Kraje from inside its own event.
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.ai.answerProposal(event.to(), event.from(), event.proposed()), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkChange(final ChunkOwnerChangeEvent event) {
        this.ai.onChunkChange(event.oldOwner(), event.newOwner(), event.world(), event.cx(), event.cz(), event.cause());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRelation(final RelationChangeEvent event) {
        this.ai.onRelationChange(event.countryA(), event.countryB(), event.oldRelation(), event.newRelation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSiegeStart(final SiegeStartEvent event) {
        this.ai.onSiegeStart(event.siegeId(), event.attacker(), event.defender(), event.world(), event.cx(), event.cz());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSiegeEnd(final SiegeEndEvent event) {
        this.ai.onSiegeEnd(event.siegeId(), event.attacker(), event.defender(), event.conquered());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreate(final CountryCreateEvent event) {
        if (event.npc()) {
            this.ai.ensure(event.countryId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisband(final CountryDisbandEvent event) {
        this.army.removeCountry(event.countryId());
        this.rulers.remove(event.countryId());
        this.ai.forget(event.countryId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTreasuryTick(final TreasuryTickEvent event) {
        if (this.config.get().upkeepTrigger == ArmiaConfig.UpkeepTrigger.TREASURY_TICK) {
            this.upkeep.payFromTreasuryTick(event.countryId());
        }
    }
}
