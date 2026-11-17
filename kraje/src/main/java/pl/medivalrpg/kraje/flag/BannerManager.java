// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Bell;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Embassy;
import pl.medivalrpg.kraje.model.SpawnPoint;

/**
 * Keeps the banner models in the world in sync with the flags (main thread, once a second): one banner over every
 * capital (manual spot, else next to the village bell, else at the country spawn, else in the capital chunk centre),
 * one guest banner at every embassy and the banners other plugins asked for through the bridge (ships, commanders).
 * Banners only exist while their chunk is loaded and are sent only to players within the view distance.
 */
final class BannerManager {

    /** What should stand somewhere. */
    record Want(String model, int tint, Location origin, float yaw, boolean pole, float scale) {

        boolean sameLook(final Want o) {
            return o != null && this.model.equals(o.model) && this.tint == o.tint && this.pole == o.pole
                && Math.abs(this.scale - o.scale) < 1e-3;
        }
    }

    /** External banner (bridge): country + where; resolved to a model every tick. */
    record External(UUID country, Location at, float yaw, boolean pole, float scale) {
    }

    private record CachedBell(Location bell, long at) {
    }

    private final FlagService service;
    private final Map<String, BannerRenderer.Banner> active = new HashMap<>();
    private final Map<String, Want> shown = new HashMap<>();
    private final Map<String, External> external = new LinkedHashMap<>();
    private final Map<UUID, CachedBell> bells = new HashMap<>();

    BannerManager(final FlagService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ bridge banners

    void showExternal(final String handle, final UUID country, final Location at, final float yaw, final boolean pole, final float scale) {
        this.external.put("x:" + handle, new External(country, at.clone(), yaw, pole, scale));
    }

    void hideExternal(final String handle) {
        this.external.remove("x:" + handle);
        this.close("x:" + handle);
    }

    void hideExternalPrefix(final String prefix) {
        final Iterator<String> it = this.external.keySet().iterator();
        while (it.hasNext()) {
            final String key = it.next();
            if (key.startsWith("x:" + prefix)) {
                it.remove();
                this.close(key);
            }
        }
    }

    int externalCount() {
        return this.external.size();
    }

    // ------------------------------------------------------------------ tick

    void tick() {
        final BannerRenderer renderer = this.service.renderer();
        final Map<String, Want> wants = new HashMap<>();
        final FlagSettings s = this.service.settings();
        for (final Country c : this.service.plugin().manager().countries()) {
            if (s.capitalBanner()) {
                final Location origin = this.capitalOrigin(c);
                if (origin != null) {
                    this.want(wants, "c:" + c.id(), c, origin, origin.getYaw(), true, 1f);
                }
            }
            if (s.embassyBanner()) {
                for (final Embassy e : c.embassies()) {
                    final Location origin = this.embassyOrigin(e);
                    if (origin != null) {
                        this.want(wants, "e:" + c.id() + ":" + e.host(), c, origin, origin.getYaw(), true, 0.75f);
                    }
                }
            }
        }
        for (final Map.Entry<String, External> e : this.external.entrySet()) {
            final External x = e.getValue();
            final Country c = this.service.plugin().manager().get(x.country());
            if (c == null || x.at().getWorld() == null || !x.at().isChunkLoaded()) {
                continue;
            }
            // The bridge gives the top of the hoist; the model origin is the foot of the (maybe hidden) pole.
            final Location origin = x.at().clone().subtract(0, FlagModel.CLOTH_TOP / 16.0 * x.scale(), 0);
            this.want(wants, e.getKey(), c, origin, x.yaw(), x.pole(), x.scale());
        }

        // close what is gone or looks different
        final Iterator<Map.Entry<String, BannerRenderer.Banner>> it = this.active.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, BannerRenderer.Banner> e = it.next();
            final Want want = wants.get(e.getKey());
            if (want == null || !want.sameLook(this.shown.get(e.getKey())) || e.getValue().closed()) {
                e.getValue().close();
                it.remove();
                this.shown.remove(e.getKey());
            }
        }
        final Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        for (final Map.Entry<String, Want> e : wants.entrySet()) {
            final Want want = e.getValue();
            BannerRenderer.Banner banner = this.active.get(e.getKey());
            if (banner == null) {
                if (!s.alwaysSpawn() && !this.anyoneNear(players, want.origin(), s.viewDistance())) {
                    continue; // nobody to show it to: no tracker at all
                }
                banner = renderer.create(want.model(), want.origin(), want.yaw(), want.tint(), want.pole(), want.scale());
                if (banner == null) {
                    continue;
                }
                this.active.put(e.getKey(), banner);
                this.shown.put(e.getKey(), want);
            } else {
                final Want old = this.shown.get(e.getKey());
                if (old == null || !old.origin().equals(want.origin()) || old.yaw() != want.yaw()) {
                    banner.move(want.origin(), want.yaw());
                    this.shown.put(e.getKey(), want);
                }
            }
            banner.sync(players, s.viewDistance());
        }
    }

    private boolean anyoneNear(final Collection<? extends Player> players, final Location at, final double distance) {
        for (final Player p : players) {
            if (p.getWorld().equals(at.getWorld()) && p.getLocation().distanceSquared(at) < distance * distance) {
                return true;
            }
        }
        return false;
    }

    private void want(final Map<String, Want> wants, final String key, final Country c, final Location origin, final float yaw,
                      final boolean pole, final float scale) {
        final FlagService.Look look = this.service.look(c);
        if (look == null) {
            return;
        }
        wants.put(key, new Want(look.model(), look.tint(), origin, yaw, pole, scale));
    }

    /** All banners are recreated (after a pack rebuild: new models, possibly new bone layout). */
    void respawnAll() {
        this.active.values().forEach(BannerRenderer.Banner::close);
        this.active.clear();
        this.shown.clear();
    }

    void close(final String key) {
        final BannerRenderer.Banner b = this.active.remove(key);
        this.shown.remove(key);
        if (b != null) {
            b.close();
        }
    }

    void shutdown() {
        this.respawnAll();
        this.external.clear();
    }

    int activeCount() {
        return this.active.size();
    }

    Map<String, Want> shownSnapshot() {
        return Map.copyOf(this.shown);
    }

    void forgetBell(final UUID country) {
        this.bells.remove(country);
    }

    // ------------------------------------------------------------------ locations

    /** Capital banner origin, or null while the capital chunk is not loaded. */
    Location capitalOrigin(final Country c) {
        final ChunkPos cap = c.capital();
        if (cap == null) {
            return null;
        }
        final World world = Bukkit.getWorld(cap.world());
        if (world == null || !world.isChunkLoaded(cap.x(), cap.z())) {
            return null;
        }
        final FlagBook.Spot spot = this.service.book().capitalSpot(c.id());
        if (spot != null) {
            final World sw = Bukkit.getWorld(spot.world());
            if (sw != null && sw.isChunkLoaded((int) Math.floor(spot.x()) >> 4, (int) Math.floor(spot.z()) >> 4)) {
                return new Location(sw, spot.x(), spot.y(), spot.z(), spot.yaw(), 0);
            }
            return null;
        }
        final Location bell = this.bell(c, world, cap);
        if (bell != null) {
            return this.ground(world, bell.getBlockX() + 2, bell.getBlockZ(), 0f);
        }
        final SpawnPoint sp = c.spawn();
        if (sp != null && sp.world().equals(cap.world())) {
            return this.ground(world, (int) Math.floor(sp.x()) + 2, (int) Math.floor(sp.z()), 0f);
        }
        return this.ground(world, cap.centerBlockX(), cap.centerBlockZ(), 0f);
    }

    private Location embassyOrigin(final Embassy e) {
        final SpawnPoint sp = e.spot();
        if (sp == null) {
            return null;
        }
        final World world = Bukkit.getWorld(sp.world());
        if (world == null || !world.isChunkLoaded((int) Math.floor(sp.x()) >> 4, (int) Math.floor(sp.z()) >> 4)) {
            return null;
        }
        return this.ground(world, (int) Math.floor(sp.x()) + 1, (int) Math.floor(sp.z()) + 1, sp.yaw());
    }

    private Location ground(final World world, final int x, final int z, final float yaw) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        final int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        return new Location(world, x + 0.5, y, z + 0.5, yaw, 0);
    }

    /** Village bell in the capital chunk or its loaded neighbours (cached for 5 minutes). */
    private Location bell(final Country c, final World world, final ChunkPos cap) {
        final long now = System.currentTimeMillis();
        final CachedBell cached = this.bells.get(c.id());
        if (cached != null && now - cached.at() < 300_000L) {
            return cached.bell();
        }
        Location best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!world.isChunkLoaded(cap.x() + dx, cap.z() + dz)) {
                    continue;
                }
                final Chunk chunk = world.getChunkAt(cap.x() + dx, cap.z() + dz);
                for (final BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Bell) {
                        final Location l = state.getLocation();
                        final UUID owner = this.service.plugin().manager().ownerAt(l);
                        if (!c.id().equals(owner)) {
                            continue;
                        }
                        final double d = Math.pow(l.getX() - cap.centerBlockX(), 2) + Math.pow(l.getZ() - cap.centerBlockZ(), 2);
                        if (d < bestDist) {
                            bestDist = d;
                            best = l;
                        }
                    }
                }
            }
        }
        this.bells.put(c.id(), new CachedBell(best, now));
        return best;
    }
}
