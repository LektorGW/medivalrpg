// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.manager.KrajeApiImpl;
import pl.medivalrpg.kraje.model.Relation;

/** Authorised prime -> short-lived spawn ticket -> persistent TNT provenance, including chain reactions. */
public final class TntService implements Listener {
    private record Origin(String world,int x,int y,int z) {
        static Origin of(Block b) {return new Origin(b.getWorld().getName(),b.getX(),b.getY(),b.getZ());}
    }
    private record Ticket(UUID country,int tick) { }
    private final KrajePlugin plugin;
    private final KrajeApiImpl countryApi;
    private final NamespacedKey countryKey,verifiedKey;
    private final Map<Origin,Ticket> tickets=new LinkedHashMap<>();

    public TntService(final KrajePlugin plugin) {
        this.plugin=plugin;this.countryApi=new KrajeApiImpl(plugin);
        this.countryKey=new NamespacedKey(plugin,"tnt-country");
        this.verifiedKey=new NamespacedKey(plugin,"tnt-verified");
    }

    private boolean active() {return this.plugin.cfg().protection.playerTnt;}

    private UUID country(final Entity entity) {
        if (!(entity instanceof TNTPrimed)) return null;
        final var pdc=entity.getPersistentDataContainer();
        if (!Byte.valueOf((byte)1).equals(pdc.get(this.verifiedKey,PersistentDataType.BYTE))) return null;
        try {
            UUID id=UUID.fromString(pdc.getOrDefault(this.countryKey,PersistentDataType.STRING,""));
            return this.plugin.manager().get(id)==null ? null : id;
        } catch (IllegalArgumentException ex) {return null;}
    }

    private boolean safe(final Block block) {
        return block.getType().getHardness()>=0 && !(block.getState(false) instanceof Container)
            && ProtectionService.classifyInteraction(block)!=ProtectionService.Flag.CONTAINERS
            && !this.plugin.protectedAreas().isProtected(block.getWorld().getName(),block.getX()>>4,block.getZ()>>4);
    }

    private boolean originAllowed(final UUID source,final Block block) {
        if (source==null || this.plugin.manager().get(source)==null || !this.safe(block)) return false;
        final UUID owner=this.plugin.protection().owner(block);
        return owner==null || owner.equals(source) || this.countryApi.canSiegeBreak(source,block.getWorld().getName(),block.getX(),block.getY(),block.getZ());
    }

    /** Only a TNT block gets the siege exception; FIRE on arbitrary enemy blocks stays protected. */
    public boolean canPrime(final Player player,final Block block) {
        if (!this.active() || block.getType()!=Material.TNT) return false;
        return this.originAllowed(this.plugin.manager().countryIdOf(player.getUniqueId()),block);
    }

    private void ticket(final Block block,final UUID country) {
        final int tick=Bukkit.getCurrentTick();
        this.tickets.entrySet().removeIf(e->tick-e.getValue().tick()>2);
        if (this.tickets.size()>=2048) this.tickets.remove(this.tickets.keySet().iterator().next());
        this.tickets.put(Origin.of(block),new Ticket(country,tick));
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onPrime(final TNTPrimeEvent event) {
        if (!this.active() || event.getBlock().getType()!=Material.TNT) return;
        UUID source=this.country(event.getPrimingEntity());
        final Player player=ProtectionService.responsiblePlayer(event.getPrimingEntity());
        if (source==null && player!=null) source=this.plugin.manager().countryIdOf(player.getUniqueId());
        if (source==null && (event.getCause()==TNTPrimeEvent.PrimeCause.REDSTONE || event.getCause()==TNTPrimeEvent.PrimeCause.DISPENSER)) {
            // These are real accepted TNT-block activations on protected land, never arbitrary entity spawns.
            final UUID owner=this.plugin.protection().owner(event.getBlock());
            final Block priming=event.getPrimingBlock();
            if (owner!=null && (priming==null && event.getCause()==TNTPrimeEvent.PrimeCause.REDSTONE
                || priming!=null && owner.equals(this.plugin.protection().owner(priming)))) source=owner;
        }
        if (this.originAllowed(source,event.getBlock())) this.ticket(event.getBlock(),source);
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onDispense(final BlockDispenseEvent event) {
        if (!this.active() || event.getItem().getType()!=Material.TNT || !(event.getBlock().getBlockData() instanceof Directional facing)) return;
        final UUID owner=this.plugin.protection().owner(event.getBlock());
        final Block target=event.getBlock().getRelative(facing.getFacing());
        // A protected dispenser is controlled by its own country, but may not manufacture enemy provenance.
        if (owner!=null && this.plugin.manager().get(owner)!=null
            && !this.plugin.protectedAreas().isProtected(event.getBlock().getWorld().getName(),event.getBlock().getX()>>4,event.getBlock().getZ()>>4)
            && this.originAllowed(owner,target)) this.ticket(target,owner);
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onSpawn(final EntitySpawnEvent event) {
        if (!this.active() || !(event.getEntity() instanceof TNTPrimed tnt)) return;
        final var loc=tnt.getLocation();
        final Ticket ticket=this.tickets.remove(new Origin(loc.getWorld().getName(),loc.getBlockX(),loc.getBlockY(),loc.getBlockZ()));
        if (ticket==null || Bukkit.getCurrentTick()-ticket.tick()>2) return;
        tnt.getPersistentDataContainer().set(this.countryKey,PersistentDataType.STRING,ticket.country().toString());
        tnt.getPersistentDataContainer().set(this.verifiedKey,PersistentDataType.BYTE,(byte)1);
    }

    public boolean allowsDamage(final Entity explosion,final Block block) {
        final UUID owner=this.plugin.protection().owner(block);
        final UUID source=this.active() ? this.country(explosion) : null;
        final boolean war=source!=null && owner!=null && this.plugin.manager().relation(source,owner)==Relation.WAR;
        final boolean siege=war && this.countryApi.canSiegeBreak(source,block.getWorld().getName(),block.getX(),block.getY(),block.getZ());
        // Protect containers and configured spawn areas even on the source country's land.
        final boolean protectedBlock=explosion instanceof TNTPrimed && !this.safe(block);
        return BlastPolicy.allows(source,owner,source!=null,protectedBlock,war,siege);
    }
}
