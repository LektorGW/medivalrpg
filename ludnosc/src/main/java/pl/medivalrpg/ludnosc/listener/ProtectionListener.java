// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.hook.ArmiaHook;
import pl.medivalrpg.ludnosc.hook.Hooks;
import pl.medivalrpg.ludnosc.model.CitizenRecord;

/**
 * Citizens on their country's land cannot be hurt by foreigners (players, their projectiles/TNT/pets, Armia soldiers)
 * unless the two countries are at war ({@code KrajeApi#isAtWar}, config). Builders are invulnerable. Trading is untouched.
 */
public final class ProtectionListener implements Listener {

    public static final String PERM_BYPASS = "ludnosc.bypass";

    private final LudnoscPlugin plugin;
    private final Map<UUID, Long> lastWarning = new HashMap<>();

    public ProtectionListener(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    /** Player (or NPC soldier) responsible for the damage. */
    private Entity responsible(final Entity damager) {
        Entity e = damager;
        if (e instanceof Projectile p) {
            final ProjectileSource src = p.getShooter();
            if (src instanceof Entity se) {
                e = se;
            }
        } else if (e instanceof TNTPrimed tnt && tnt.getSource() != null) {
            e = tnt.getSource();
        } else if (e instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Entity se) {
            e = se;
        }
        if (e instanceof Tameable t && t.getOwner() instanceof Player owner) {
            return owner;
        }
        return e;
    }

    private Optional<UUID> countryOf(final Entity attacker) {
        if (attacker instanceof Player p) {
            return this.plugin.kraje().getCountryOf(p.getUniqueId()).map(KrajInfo::id);
        }
        if (Hooks.armia()) {
            return ArmiaHook.soldierCountry(attacker);
        }
        return Optional.empty();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (this.plugin.construction().isHelper(event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        if (!this.plugin.settings().protectionEnabled || !(event.getEntity() instanceof Villager v)) {
            return;
        }
        final CitizenRecord rec = this.plugin.data().citizen(v.getUniqueId());
        if (rec == null) {
            return;
        }
        final Entity attacker = this.responsible(event.getDamager());
        final boolean player = attacker instanceof Player;
        final Optional<UUID> attackerCountry = this.countryOf(attacker);
        if (!player && attackerCountry.isEmpty()) {
            return; // monsters, golems, environment: vanilla
        }
        if (player && attacker.hasPermission(PERM_BYPASS)) {
            return;
        }
        // only on the citizen's own land
        final UUID landOwner = this.plugin.kraje().getCountryAt(v.getLocation()).map(KrajInfo::id).orElse(null);
        if (!rec.countryId().equals(landOwner)) {
            return;
        }
        if (attackerCountry.isPresent()) {
            final UUID ac = attackerCountry.get();
            if (ac.equals(rec.countryId())) {
                if (this.plugin.settings().ownCountryCanDamage || !player) {
                    return;
                }
            } else if (this.plugin.settings().warAllowsDamage && this.plugin.kraje().isAtWar(ac, rec.countryId())) {
                return;
            }
        }
        event.setCancelled(true);
        if (attacker instanceof Player p) {
            this.warn(p, "protect-citizen", rec.countryId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnyDamage(final EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent) && this.plugin.construction().isHelper(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager v)) {
            return;
        }
        if (this.plugin.construction().isHelper(v)) {
            event.setCancelled(true);
            return;
        }
        if (!this.plugin.settings().blockNameTags || this.plugin.data().citizen(v.getUniqueId()) == null) {
            return;
        }
        final ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand());
        if (hand != null && hand.getType() == Material.NAME_TAG && !event.getPlayer().hasPermission(PERM_BYPASS)) {
            event.setCancelled(true);
            this.plugin.messages().send(event.getPlayer(), "protect-nametag");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(final PlayerLeashEntityEvent event) {
        if (!this.plugin.settings().blockLeash || !(event.getEntity() instanceof Villager v)) {
            return;
        }
        final CitizenRecord rec = this.plugin.data().citizen(v.getUniqueId());
        if (rec == null || event.getPlayer().hasPermission(PERM_BYPASS)) {
            return;
        }
        final UUID pc = this.plugin.kraje().getCountryOf(event.getPlayer().getUniqueId()).map(KrajInfo::id).orElse(null);
        if (rec.countryId().equals(pc)) {
            return;
        }
        if (pc != null && this.plugin.settings().warAllowsDamage && this.plugin.kraje().isAtWar(pc, rec.countryId())) {
            return;
        }
        event.setCancelled(true);
        this.warn(event.getPlayer(), "protect-leash", rec.countryId());
    }

    private void warn(final Player p, final String key, final UUID country) {
        final long now = System.currentTimeMillis();
        final Long last = this.lastWarning.get(p.getUniqueId());
        if (last != null && now - last < 3000) {
            return;
        }
        this.lastWarning.put(p.getUniqueId(), now);
        this.plugin.messages().send(p, key, Placeholder.unparsed("country", this.plugin.countryName(country)));
    }
}
