// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * Recreated, tagged visual infrastructure. Never changes terrain or loads chunks. Mine wagons use real connected
 * rails; passenger trams require an industrial/modern station. Tower cranes follow active high-rise works in era 4.
 * With the server's max era 2 only mine wagons are eligible. All work runs on the main thread with fixed scan caps.
 */
public final class SettlementTransportService implements Listener {
    private record Cart(UUID building, UUID country, Minecart entity, Location home, boolean tram) { }
    private record Crane(UUID site, UUID country, Location base, List<BlockDisplay> parts) { }

    private final LudnoscPlugin plugin;
    private final NamespacedKey key;
    private final Map<UUID, Cart> carts = new HashMap<>();
    private final Map<UUID, Crane> cranes = new HashMap<>();
    private final Map<UUID, Integer> scanCursors = new HashMap<>();
    private BukkitTask task;
    private int tick;
    private int buildingCursor;

    public SettlementTransportService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "transport_visual");
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        for (final World world : Bukkit.getWorlds()) {
            for (final Entity entity : world.getEntities()) this.removeOrphan(entity);
        }
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::update, 40L, 10L);
    }

    public void stop() {
        if (this.task != null) this.task.cancel();
        this.carts.values().forEach(c -> c.entity().remove());
        this.cranes.values().forEach(c -> c.parts().forEach(Entity::remove));
        this.carts.clear();
        this.cranes.clear();
        this.scanCursors.clear();
    }

    private boolean tagged(final Entity e) {
        return e.getPersistentDataContainer().has(this.key, PersistentDataType.BYTE);
    }

    private void tag(final Entity e) {
        e.setPersistent(false);
        e.setInvulnerable(true);
        e.getPersistentDataContainer().set(this.key, PersistentDataType.BYTE, (byte) 1);
    }

    private void removeOrphan(final Entity e) {
        if (this.tagged(e)) e.remove();
    }

    @EventHandler public void onLoad(final ChunkLoadEvent event) {
        for (final Entity e : event.getChunk().getEntities()) {
            if (this.tagged(e) && this.carts.values().stream().noneMatch(c -> c.entity().getUniqueId().equals(e.getUniqueId()))
                && this.cranes.values().stream().noneMatch(c -> c.parts().contains(e))) e.remove();
        }
    }

    @EventHandler(ignoreCancelled = true) public void onDamage(final EntityDamageEvent event) {
        if (this.tagged(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true) public void onDestroy(final VehicleDestroyEvent event) {
        if (this.tagged(event.getVehicle())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true) public void onEnter(final VehicleEnterEvent event) {
        if (this.tagged(event.getVehicle()) && this.carts.values().stream()
            .anyMatch(c -> c.entity().equals(event.getVehicle()) && !c.tram())) event.setCancelled(true);
    }

    private void update() {
        if (!this.plugin.getConfig().getBoolean("transport.enabled", true)) {
            this.carts.values().forEach(c -> c.entity().remove()); this.carts.clear();
            this.cranes.values().forEach(c -> c.parts().forEach(Entity::remove)); this.cranes.clear();
            return;
        }
        this.tick += 10;
        this.carts.entrySet().removeIf(entry -> {
            final Cart c = entry.getValue();
            final Building b = this.plugin.data().building(c.building());
            if (!c.entity().isValid() || b == null || !c.country().equals(b.countryId())
                || !cartAllowed(c.tram(), this.plugin.postep().era(c.country()))
                || !this.loaded(c.entity().getLocation()) || !this.nearPlayer(c.entity().getLocation(), 80)) {
                c.entity().eject(); c.entity().remove(); return true;
            }
            this.drive(c);
            return false;
        });
        this.cranes.entrySet().removeIf(entry -> {
            final Crane c = entry.getValue();
            final ConstructionSite site = this.plugin.data().site(c.site());
            if (site == null || !c.country().equals(site.countryId) || !craneAllowed(site.type, this.plugin.postep().era(c.country())) || !this.loaded(c.base())
                || !this.nearPlayer(c.base(), 96) || c.parts().stream().anyMatch(e -> !e.isValid())) {
                c.parts().forEach(Entity::remove); return true;
            }
            this.animate(c);
            return false;
        });
        this.scanNextBuilding();
        if (this.tick % 100 == 0) this.addCrane();
    }

    private void scanNextBuilding() {
        final int cap = Math.clamp(this.plugin.getConfig().getInt("transport.max-carts", 24), 0, 64);
        if (this.carts.size() >= cap) return;
        final List<Building> buildings = this.plugin.data().allBuildings().stream()
            .filter(b -> b.countryId() != null && (b.function() == BuildingFunction.MINE || b.function() == BuildingFunction.STATION))
            .toList();
        final Set<UUID> active = new HashSet<>();
        buildings.forEach(b -> active.add(b.id()));
        this.scanCursors.keySet().retainAll(active);
        if (buildings.isEmpty()) return;
        final Building b = buildings.get(Math.floorMod(this.buildingCursor++, buildings.size()));
        final boolean tram = b.function() == BuildingFunction.STATION;
        if (this.carts.containsKey(b.id()) || !cartAllowed(tram, this.plugin.postep().era(b.countryId()))) return;
        final World world = Bukkit.getWorld(b.box().world());
        if (world == null) return;
        final int minY = Math.max(world.getMinHeight() + 1, b.box().minY() - (tram ? 2 : 32));
        final int width = Math.min(32, b.box().sizeX() + 4);
        final int depth = Math.min(32, b.box().sizeZ() + 4);
        final int height = Math.min(40, b.box().maxY() - minY + 3);
        final int volume = width * depth * height;
        int cursor = this.scanCursors.getOrDefault(b.id(), 0);
        // At most 256 block probes every ten ticks, globally, across all buildings.
        for (int n = 0; n < Math.min(256, volume); n++, cursor = (cursor + 1) % volume) {
            final int x = b.box().minX() - 2 + cursor % width;
            final int z = b.box().minZ() - 2 + cursor / width % depth;
            final int y = minY + cursor / (width * depth);
            final Location at = new Location(world, x + 0.5, y + 0.1, z + 0.5);
            if (!this.loaded(at) || !this.nearPlayer(at, 64)) continue;
            if (this.plugin.kraje().getCountryAt(world.getName(), x >> 4, z >> 4)
                .map(c -> !c.id().equals(b.countryId())).orElse(true)) continue;
            final Block block = world.getBlockAt(x, y, z);
            if (!(block.getBlockData() instanceof Rail rail)) continue;
            final Vector direction = straightDirection(rail.getShape());
            if (direction == null || !this.trackAhead(at, direction, 2) || !this.trackAhead(at, direction.clone().multiply(-1), 2)) continue;
            final Minecart entity = world.spawn(at, Minecart.class, e -> {
                this.tag(e); e.setSlowWhenEmpty(false); e.setMaxSpeed(tram ? 0.3 : 0.18);
                e.customName(Component.text(tram ? "Tramwaj miejski" : "Wózek kopalniany"));
                e.setDisplayBlockData((tram ? Material.OAK_PLANKS : Material.COAL_BLOCK).createBlockData());
                e.setVelocity(direction.multiply(tram ? 0.25 : 0.14));
            });
            this.carts.put(b.id(), new Cart(b.id(), b.countryId(), entity, at, tram));
            break;
        }
        this.scanCursors.put(b.id(), cursor % volume);
    }

    private void drive(final Cart c) {
        final Minecart entity = c.entity();
        final Location at = entity.getLocation();
        if (at.getWorld() != c.home().getWorld() || at.distanceSquared(c.home()) > 64 * 64) {
            entity.eject(); entity.remove(); return;
        }
        if (this.plugin.kraje().getCountryAt(at).map(country -> !country.id().equals(c.country())).orElse(true)) {
            entity.eject(); entity.remove(); return;
        }
        Block block = at.getBlock();
        if (!(block.getBlockData() instanceof Rail)) block = block.getRelative(0, -1, 0);
        if (!(block.getBlockData() instanceof Rail rail)) { entity.eject(); entity.remove(); return; }
        final Vector axis = straightDirection(rail.getShape());
        if (axis == null) return; // vanilla cart physics handles bends and slopes
        final Vector velocity = entity.getVelocity();
        if (velocity.dot(axis) < 0) axis.multiply(-1);
        if (!this.ownedTrack(c, block.getLocation(), axis, 2)) axis.multiply(-1);
        if (this.ownedTrack(c, block.getLocation(), axis, 1)) entity.setVelocity(axis.multiply(c.tram() ? 0.25 : 0.14));
        else entity.setVelocity(new Vector());
    }

    private boolean ownedTrack(final Cart cart, final Location at, final Vector direction, final int distance) {
        if (!this.trackAhead(at, direction, distance)) return false;
        final Location end = at.clone().add(direction.clone().multiply(distance));
        return this.plugin.kraje().getCountryAt(end).map(c -> c.id().equals(cart.country())).orElse(false);
    }

    private boolean trackAhead(final Location at, final Vector direction, final int distance) {
        final Location end = at.clone().add(direction.clone().multiply(distance));
        if (!this.loaded(end)) return false;
        final Block block = end.getBlock();
        return block.getBlockData() instanceof Rail && block.getRelative(0, 1, 0).isPassable()
            && block.getRelative(0, 2, 0).isPassable();
    }

    static Vector straightDirection(final Rail.Shape shape) {
        return switch (shape) {
            case NORTH_SOUTH -> new Vector(0, 0, 1);
            case EAST_WEST -> new Vector(1, 0, 0);
            default -> null;
        };
    }

    static boolean cartAllowed(final boolean tram, final int era) {
        return !tram || era >= 3;
    }

    static boolean craneAllowed(final String buildingType, final int era) {
        return era >= 4 && buildingType != null && (buildingType.contains("wiezowiec") || buildingType.contains("biurowiec"));
    }

    private void addCrane() {
        final int cap = Math.clamp(this.plugin.getConfig().getInt("transport.max-cranes", 8), 0, 16);
        if (this.cranes.size() >= cap) return;
        for (final ConstructionSite site : this.plugin.data().allSites()) {
            if (site.countryId == null || this.cranes.containsKey(site.id)
                || !craneAllowed(site.type, this.plugin.postep().era(site.countryId))) continue;
            final World world = Bukkit.getWorld(site.world);
            if (world == null) continue;
            final Location base = new Location(world, site.originX - 5, site.originY, site.originZ - 5);
            if (!this.loaded(base) || !this.nearPlayer(base, 80) || base.getY() + 28 >= world.getMaxHeight()) continue;
            final List<BlockDisplay> parts = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                parts.add(world.spawn(base, BlockDisplay.class, e -> {
                    this.tag(e); e.setBlock(Material.YELLOW_CONCRETE.createBlockData());
                    e.setInterpolationDuration(10); e.setTeleportDuration(10); e.setViewRange(0.75f);
                }));
            }
            final Crane crane = new Crane(site.id, site.countryId, base, List.copyOf(parts));
            this.cranes.put(site.id, crane);
            this.animate(crane);
            break;
        }
    }

    private void animate(final Crane c) {
        final float angle = (this.tick % 2400) * (float) (Math.PI * 2 / 2400);
        final float hook = 12 + (float) Math.sin(angle * 2) * 7;
        c.parts().get(0).setTransformationMatrix(new Matrix4f().scaling(0.6f, 24, 0.6f));
        c.parts().get(1).setTransformationMatrix(new Matrix4f().translate(0, 24, 0).rotateY(angle).scale(12, 0.5f, 0.5f));
        final float hx = (float) Math.cos(angle) * 9;
        final float hz = -(float) Math.sin(angle) * 9;
        c.parts().get(2).setBlock(Material.IRON_CHAIN.createBlockData());
        c.parts().get(2).setTransformationMatrix(new Matrix4f().translate(hx, hook, hz).scale(0.15f, 24 - hook, 0.15f));
        c.parts().get(3).setBlock(Material.OAK_PLANKS.createBlockData());
        c.parts().get(3).setTransformationMatrix(new Matrix4f().translate(hx - 0.5f, hook, hz - 0.5f).scale(1.5f, 0.5f, 1.5f));
        c.parts().forEach(e -> e.setInterpolationDelay(0));
    }

    private boolean loaded(final Location location) {
        return location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private boolean nearPlayer(final Location location, final double radius) {
        return location.getWorld().getPlayers().stream().anyMatch(p -> p.getLocation().distanceSquared(location) <= radius * radius);
    }
}
