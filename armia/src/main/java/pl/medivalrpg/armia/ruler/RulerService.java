// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ruler;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.model.RulerRecord;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.armia.soldier.Keys;
import pl.medivalrpg.kraje.api.KrajInfo;

/**
 * NPC rulers: a static {@link Mannequin} per NPC country (immovable, invulnerable, persistent, tagged with PDC).
 * The registry in {@code rulers.json} is the truth: a ruler entity without a matching record is removed, a record
 * whose entity is missing when its chunk loads gets a new one.
 */
public final class RulerService {

    private final Plugin plugin;
    private final Keys keys;
    private final Services services;
    private final Supplier<Messages> messages;
    private final Map<UUID, RulerRecord> rulers;

    public RulerService(final Plugin plugin, final Keys keys, final Services services, final Supplier<Messages> messages, final Map<UUID, RulerRecord> rulers) {
        this.plugin = plugin;
        this.keys = keys;
        this.services = services;
        this.messages = messages;
        this.rulers = rulers;
    }

    public Map<UUID, RulerRecord> records() {
        return this.rulers;
    }

    public Optional<RulerRecord> of(final UUID country) {
        return Optional.ofNullable(this.rulers.get(country));
    }

    /** Places (or moves) the ruler of {@code country} at {@code at}. */
    public void place(final UUID country, final String name, final String skin, final Location at) {
        final RulerRecord old = this.rulers.get(country);
        final UUID skinId = old != null && old.skinId != null ? old.skinId : UUID.randomUUID();
        this.remove(country);
        final RulerRecord r = new RulerRecord(country, name, skin, Pos.of(at));
        r.skinId = skinId;
        this.rulers.put(country, r);
        this.spawn(r);
        this.services.kraje().setNpcRulerName(country, name);
    }

    public void remove(final UUID country) {
        final RulerRecord r = this.rulers.remove(country);
        if (r != null) {
            for (final UUID id : new UUID[] {r.entity, r.hitbox}) {
                final Entity e = id == null ? null : Bukkit.getEntity(id);
                if (e != null) {
                    e.remove();
                }
            }
        }
    }

    private void spawn(final RulerRecord r) {
        final Location at = r.pos.toLocation();
        if (at == null || !at.isChunkLoaded()) {
            return;
        }
        if (r.skinId == null) {
            r.skinId = UUID.randomUUID();
        }
        final Optional<KrajInfo> c = this.services.kraje().getCountry(r.country);
        this.spawnHitbox(r, at);
        final Mannequin m = at.getWorld().spawn(at, Mannequin.class, e -> {
            this.keys.tag(e, null, r.country, Keys.PART_RULER);
            e.setImmovable(true);
            e.setInvulnerable(true);
            e.setPersistent(true);
            e.setGravity(false);
            e.setCollidable(false);
            e.setSilent(true);
            // a Mojang skin, or a profile with only a stable random UUID (one of the client's default skins)
            e.setProfile(r.skin != null && !r.skin.isBlank()
                ? ResolvableProfile.resolvableProfile().name(r.skin).build()
                : ResolvableProfile.resolvableProfile().uuid(r.skinId).build());
            e.customName(this.messages.get().get("ruler-name", Placeholder.unparsed("name", r.name),
                Placeholder.component("country", c.map(k -> Component.text(k.name(), k.color())).orElse(Component.text("?")))));
            e.setCustomNameVisible(true);
            e.setDescription(this.messages.get().get("ruler-description",
                Placeholder.component("country", c.map(k -> Component.text(k.name(), k.color())).orElse(Component.text("?")))));
        });
        r.entity = m.getUniqueId();
        this.animate(m);
    }

    /** Ruler bodies whose pose Interakcje already accepted (stored on the entity by Interakcje, so once per body). */
    private final java.util.Set<UUID> animated = new java.util.HashSet<>();

    /**
     * Dignified guard pose (hand on the hilt) through Interakcje (optional, reflection). Retried by the periodic
     * {@link #ensureAll} until accepted; without Interakcje nothing happens.
     */
    private void animate(final Entity body) {
        if (body == null || this.animated.contains(body.getUniqueId())) {
            return;
        }
        if (this.animated.size() > 1024) {
            this.animated.clear();
        }
        if (pl.medivalrpg.armia.visual.WorkAnim.play(body, "straz", true)) {
            this.animated.add(body.getUniqueId());
        }
    }

    /**
     * Invisible {@code Interaction} (0.8 × 2 blocks, responsive) on the ruler: an immovable Mannequin does not reliably
     * receive right clicks, the Interaction always does. Tagged like the ruler (same country in PDC).
     */
    private void spawnHitbox(final RulerRecord r, final Location at) {
        final Entity old = r.hitbox == null ? null : Bukkit.getEntity(r.hitbox);
        if (old != null && old.isValid()) {
            old.teleport(at);
            return;
        }
        final Interaction box = at.getWorld().spawn(at, Interaction.class, e -> {
            this.keys.tag(e, null, r.country, Keys.PART_RULER_HITBOX);
            e.setInteractionWidth(0.8f);
            e.setInteractionHeight(2.0f);
            e.setResponsive(true);
            e.setPersistent(true);
            e.setInvulnerable(true);
            e.setSilent(true);
        });
        r.hitbox = box.getUniqueId();
    }

    /** Ruler entity (or hitbox) loaded from disk: keep only the registered ones. */
    public void onLoaded(final Entity e) {
        final String part = this.keys.part(e);
        if (!Keys.PART_RULER.equals(part) && !Keys.PART_RULER_HITBOX.equals(part)) {
            return;
        }
        final UUID country = this.keys.countryId(e);
        final RulerRecord r = country == null ? null : this.rulers.get(country);
        final UUID registered = r == null ? null : Keys.PART_RULER.equals(part) ? r.entity : r.hitbox;
        if (registered == null || !registered.equals(e.getUniqueId())) {
            e.remove();
        }
    }

    /** A chunk loaded: respawn rulers standing there whose entity is gone (checked a moment later). */
    public void onChunkLoaded(final Chunk chunk) {
        for (final RulerRecord r : this.rulers.values()) {
            if (r.pos != null && r.pos.world().equals(chunk.getWorld().getName()) && r.pos.chunkX() == chunk.getX() && r.pos.chunkZ() == chunk.getZ()) {
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.ensure(r), 40L);
            }
        }
    }

    /** Startup: make sure every ruler in a loaded chunk exists. */
    public void ensureAll() {
        for (final RulerRecord r : this.rulers.values()) {
            this.ensure(r);
        }
    }

    /** Respawns the ruler of {@code country} at its spot when it is missing (dead, killed, removed) and its chunk is loaded. */
    public void ensure(final UUID country) {
        final RulerRecord r = country == null ? null : this.rulers.get(country);
        if (r != null) {
            this.ensure(r);
        }
    }

    /** Periodic check (every 30 s): every ruler in a loaded chunk exists. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(this.plugin, this::ensureAll, 20L * 30, 20L * 30);
    }

    private void ensure(final RulerRecord r) {
        if (this.rulers.get(r.country) != r || r.pos == null || !r.pos.isChunkLoaded()) {
            return;
        }
        final org.bukkit.World w = r.pos.bukkitWorld();
        if (w == null || !w.getChunkAt(r.pos.chunkX(), r.pos.chunkZ()).isEntitiesLoaded()) {
            return; // entities of the chunk not loaded yet: the old ruler may still appear
        }
        final Entity e = r.entity == null ? null : Bukkit.getEntity(r.entity);
        if (e == null || !e.isValid() || e.isDead()) {
            if (e != null) {
                e.remove();
            }
            this.spawn(r);
            return;
        }
        this.animate(e);
        final Entity box = r.hitbox == null ? null : Bukkit.getEntity(r.hitbox);
        if (box == null || !box.isValid()) {
            final Location at = r.pos.toLocation();
            if (at != null) {
                this.spawnHitbox(r, e.getLocation());
            }
        }
    }

    /** Country of a ruler entity: the Mannequin or its click hitbox. */
    public Optional<UUID> countryOf(final Entity e) {
        final String part = this.keys.part(e);
        if (!Keys.PART_RULER.equals(part) && !Keys.PART_RULER_HITBOX.equals(part)) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.keys.countryId(e));
    }

    /** True only for the ruler's body (the hitbox is no living entity). */
    public boolean isRulerBody(final Entity e) {
        return Keys.PART_RULER.equals(this.keys.part(e));
    }
}
