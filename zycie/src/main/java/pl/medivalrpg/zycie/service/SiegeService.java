// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Bell;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.api.SiegeInfo;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.zycie.ZyciePlugin;
import pl.medivalrpg.zycie.config.ZycieConfig;
import pl.medivalrpg.zycie.logic.DayPhase;
import pl.medivalrpg.zycie.logic.Hashing;

/**
 * Sieges ({@link KrajeApi#getSieges()}): citizens of the defender near the besieged chunk hide at home — through the
 * vanilla bell reaction ({@code HEARD_BELL_TIME} memory → HIDE activity: the brain walks them to their beds/houses),
 * refreshed while the siege lasts; at night the village bell (the citizens' meeting point) rings the alarm. During a
 * siege of the capital chunk part of the adult citizens form a levy ("pospolite ruszenie"): pitchfork (hoe) or axe in
 * hand, standing at the gate — cosmetic only (drop chance 0, PDC {@code zycie:levy}, removed when the siege ends,
 * also for villagers loaded later). Only loaded chunks; one bounded entity query per siege. Main thread only.
 */
public final class SiegeService {

    private final ZyciePlugin plugin;
    private final KrajeApi kraje;
    private final LudnoscApi ludnosc;
    private final NamespacedKey levyTag;
    private final Map<UUID, Long> sheltering = new HashMap<>();
    private final Map<UUID, UUID> militia = new HashMap<>();
    private final Map<UUID, Long> lastBell = new HashMap<>();
    private final Set<UUID> running = new HashSet<>();
    private ZycieConfig cfg;
    private long ticks;

    public SiegeService(final ZyciePlugin plugin, final KrajeApi kraje, final LudnoscApi ludnosc) {
        this.plugin = plugin;
        this.kraje = kraje;
        this.ludnosc = ludnosc;
        this.levyTag = new NamespacedKey(plugin, "levy");
    }

    public void configure(final ZycieConfig config) {
        this.cfg = config;
    }

    public boolean isSheltering(final UUID villager, final long nowMs) {
        final Long until = this.sheltering.get(villager);
        return until != null && until > nowMs;
    }

    public boolean isMilitia(final UUID villager) {
        return this.militia.containsKey(villager);
    }

    /** Runs every {@code oblezenie.co-tickow} ticks. */
    public void tick() {
        this.ticks += this.cfg.siegePeriod;
        final long nowMs = System.currentTimeMillis();
        this.sheltering.values().removeIf(until -> until <= nowMs);
        this.running.clear();
        for (final SiegeInfo s : this.kraje.getSieges()) {
            this.running.add(s.id());
            try {
                this.handle(s, nowMs);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(java.util.logging.Level.FINE, "Błąd obsługi oblężenia", e);
            }
        }
        this.lastBell.keySet().retainAll(this.running);
        // levy of sieges that ended
        final List<UUID> done = new ArrayList<>();
        for (final Map.Entry<UUID, UUID> e : this.militia.entrySet()) {
            if (!this.running.contains(e.getValue())) {
                done.add(e.getKey());
            }
        }
        for (final UUID id : done) {
            this.militia.remove(id);
            if (Bukkit.getEntity(id) instanceof Villager v) {
                this.disarm(v);
            }
        }
    }

    private void handle(final SiegeInfo s, final long nowMs) {
        final World world = Bukkit.getWorld(s.world());
        if (world == null || !this.cfg.worldEnabled(world.getName()) || !world.isChunkLoaded(s.cx(), s.cz())) {
            return;
        }
        final KrajInfo def = this.kraje.getCountry(s.defender()).orElse(null);
        if (def == null) {
            return;
        }
        final int x = (s.cx() << 4) + 8;
        final int z = (s.cz() << 4) + 8;
        final Location center = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1.0, z + 0.5);
        final boolean capital = world.getName().equals(def.capitalWorld()) && s.cx() == def.capitalX() && s.cz() == def.capitalZ();
        int levyCount = 0;
        for (final UUID sid : this.militia.values()) {
            if (sid.equals(s.id())) {
                levyCount++;
            }
        }
        Location bell = null;
        final double r = this.cfg.siegeRadius;
        final long shelterUntil = nowMs + this.cfg.siegePeriod * 50L * 3;
        for (final Villager v : world.getNearbyEntitiesByType(Villager.class, center, r, 32, r)) {
            if (!v.isValid() || !this.ludnosc.countryOf(v).map(def.id()::equals).orElse(false)) {
                continue;
            }
            if (bell == null) {
                final Location meet = v.getMemory(MemoryKey.MEETING_POINT);
                if (meet != null && meet.getWorld() == world) {
                    bell = meet;
                }
            }
            final UUID id = v.getUniqueId();
            if (this.cfg.levy && capital && v.isAdult()) {
                final boolean already = s.id().equals(this.militia.get(id));
                if (already || (levyCount < this.cfg.levyMax && Hashing.unit(id, "levy") < this.cfg.levyShare)) {
                    if (!already) {
                        levyCount++;
                        this.militia.put(id, s.id());
                    }
                    this.arm(v, s.id());
                    continue;
                }
            }
            if (!v.isSleeping()) {
                v.setMemory(MemoryKey.HEARD_BELL_TIME, world.getGameTime());
            }
            this.sheltering.put(id, shelterUntil);
        }
        if (this.cfg.nightBell && this.cfg.schedule.phase(world.getFullTime()) == DayPhase.NIGHT) {
            final long last = this.lastBell.getOrDefault(s.id(), -1_000_000L);
            if (this.ticks - last >= this.cfg.bellPeriod) {
                this.lastBell.put(s.id(), this.ticks);
                this.ring(bell, center);
            }
        }
    }

    private void ring(final Location bell, final Location fallback) {
        if (bell != null && bell.getWorld() != null && bell.getWorld().isChunkLoaded(bell.getBlockX() >> 4, bell.getBlockZ() >> 4)) {
            final BlockState state = bell.getBlock().getState();
            if (state instanceof Bell b && b.ring()) {
                return;
            }
        }
        fallback.getWorld().playSound(fallback, Sound.BLOCK_BELL_USE, SoundCategory.BLOCKS, 3.0f, 0.9f);
    }

    private Material weaponFor(final UUID villager) {
        final List<String> names = this.cfg.levyWeapons;
        final int idx = (int) Math.floor(Hashing.unit(villager, "weapon") * names.size());
        final Material m = Material.matchMaterial(names.get(Math.max(0, Math.min(names.size() - 1, idx))));
        return m == null || !m.isItem() ? Material.IRON_HOE : m;
    }

    private void arm(final Villager v, final UUID siegeId) {
        final EntityEquipment eq = v.getEquipment();
        final ItemStack hand = eq.getItemInMainHand();
        if (hand.getType().isAir()) {
            eq.setItemInMainHand(new ItemStack(this.weaponFor(v.getUniqueId())));
            eq.setItemInMainHandDropChance(0f);
        }
        v.getPersistentDataContainer().set(this.levyTag, PersistentDataType.STRING, siegeId.toString());
    }

    private void disarm(final Villager v) {
        if (!v.getPersistentDataContainer().has(this.levyTag, PersistentDataType.STRING)) {
            return;
        }
        v.getPersistentDataContainer().remove(this.levyTag);
        final ItemStack hand = v.getEquipment().getItemInMainHand();
        for (final String w : this.cfg.levyWeapons) {
            if (hand.getType().name().equals(w)) {
                v.getEquipment().setItemInMainHand(null);
                break;
            }
        }
    }

    /** Siege ended (event): its levy goes home at once. */
    public void onSiegeEnd(final UUID siegeId) {
        this.running.remove(siegeId);
        this.lastBell.remove(siegeId);
        final List<UUID> ids = new ArrayList<>();
        for (final Map.Entry<UUID, UUID> e : this.militia.entrySet()) {
            if (e.getValue().equals(siegeId)) {
                ids.add(e.getKey());
            }
        }
        for (final UUID id : ids) {
            this.militia.remove(id);
            if (Bukkit.getEntity(id) instanceof Villager v) {
                this.disarm(v);
            }
        }
    }

    /** Chunk entities loaded: a levy villager whose siege is over gets its weapon taken away. */
    public void onEntitiesLoad(final List<Entity> entities) {
        for (final Entity e : entities) {
            if (e instanceof Villager v) {
                final String sid = v.getPersistentDataContainer().get(this.levyTag, PersistentDataType.STRING);
                if (sid != null && !this.militia.containsKey(v.getUniqueId())) {
                    this.disarm(v);
                }
            }
        }
    }

    /** Disable / reload: disarm every loaded levy villager. */
    public void releaseAll() {
        for (final UUID id : new ArrayList<>(this.militia.keySet())) {
            if (Bukkit.getEntity(id) instanceof Villager v) {
                this.disarm(v);
            }
        }
        this.militia.clear();
        this.sheltering.clear();
    }
}
