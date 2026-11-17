// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import io.papermc.paper.entity.LookAnchor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import pl.medivalrpg.interakcje.anim.SitVariant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sitting (player rides an invisible, non-persistent {@link BlockDisplay} seat) and lying
 * ({@link Player#setPose(Pose, boolean)} with a fixed {@code SLEEPING} / {@code SWIMMING} pose).
 * Neither state survives a relog. Main thread only.
 */
public final class PoseManager {
    /** Vanilla dismount puts the player about here relative to the seat; further = teleported away. */
    private static final double STAND_TELEPORT_MAX_DISTANCE = 2.5;

    private final InterakcjePlugin plugin;
    private final NamespacedKey seatKey;
    private final Map<UUID, Seat> seats = new HashMap<>();
    private final Map<UUID, Location> lying = new HashMap<>();

    /** @param base block that carries the seat (stand up when it becomes passable) */
    private record Seat(BlockDisplay entity, Location standAt, Block base) {
    }

    public PoseManager(InterakcjePlugin plugin) {
        this.plugin = plugin;
        this.seatKey = new NamespacedKey(plugin, "seat");
    }

    public boolean isSitting(UUID player) {
        return seats.containsKey(player);
    }

    public boolean isLying(UUID player) {
        return lying.containsKey(player);
    }

    public boolean isPosing(UUID player) {
        return isSitting(player) || isLying(player);
    }

    public boolean isSeat(Entity e) {
        return e instanceof BlockDisplay && e.getPersistentDataContainer().has(seatKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ commands

    /** {@code /usiadz}: sit down on the ground, or stand up when already sitting. */
    public void toggleSit(Player p) {
        if (isSitting(p.getUniqueId())) {
            standUp(p, true);
            return;
        }
        PoseDef def = plugin.pose(PoseDef.SIT);
        if (!preflight(p, def)) {
            return;
        }
        Location feet = p.getLocation();
        Block ground = feet.clone().subtract(0, 0.05, 0).getBlock();
        Block head = feet.getBlock().getRelative(BlockFace.UP);
        String fail = PoseRules.check(state(p, ground, head));
        if (fail != null) {
            plugin.messages().send(p, fail);
            return;
        }
        Location seatAt = feet.clone().add(0, def.seatOffset(), 0);
        SitVariant variant = SitVariant.FLOOR;
        Float yaw = null;
        if (plugin.animations().active()) {
            int cardinal = SitVariant.cardinal(feet.getYaw());
            int[] d = SitVariant.cardinalOffset(cardinal);
            Block front = feet.getBlock().getRelative(d[0], 0, d[1]);
            Block frontBelow = front.getRelative(BlockFace.DOWN);
            boolean ledge = front.isPassable() && !front.isLiquid() && frontBelow.isPassable() && !frontBelow.isLiquid()
                    && front.getRelative(BlockFace.UP).isPassable();
            variant = SitVariant.choose(SitVariant.Seat.GROUND, ledge, SitVariant.Mode.parse(def.variant()));
            if (variant == SitVariant.CHAIR && ledge) {
                // sit on the edge, facing the drop, so the legs hang over it
                yaw = (float) (cardinal * 90 > 180 ? cardinal * 90 - 360 : cardinal * 90);
                seatAt = ground.getLocation().add(0.5 + d[0] * 0.3, 0, 0.5 + d[1] * 0.3);
                seatAt.setY(feet.getY() + def.seatOffset());
                seatAt.setYaw(yaw);
            }
        }
        sit(p, def, seatAt, feet.clone().add(0, 0.1, 0), ground, yaw, variant);
    }

    /** Right-click on the top of a bottom stairs / bottom slab with an empty hand. */
    public boolean trySitOnBlock(Player p, Block block) {
        PoseDef def = plugin.pose(PoseDef.SIT);
        if (def == null || !def.enabled() || !def.rightClickStairs() || isPosing(p.getUniqueId())) {
            return false;
        }
        BlockData data = block.getBlockData();
        BlockFace facing = null;
        double standHeight;
        if (data instanceof Stairs stairs && stairs.getHalf() == Bisected.Half.BOTTOM) {
            facing = stairs.getFacing();
            standHeight = 1.0;
        } else if (data instanceof Slab slab && slab.getType() == Slab.Type.BOTTOM) {
            standHeight = 0.5;
        } else {
            return false;
        }
        Location center = block.getLocation().add(0.5, 0, 0.5);
        if (!center.getWorld().equals(p.getWorld()) || center.distanceSquared(p.getLocation()) > 9.0) {
            return false;
        }
        if (!preflight(p, def)) {
            return true;
        }
        Block above = block.getRelative(BlockFace.UP);
        String fail = PoseRules.check(state(p, block, above));
        if (fail != null) {
            plugin.messages().send(p, fail);
            return true;
        }
        float yaw = facing != null ? PoseRules.stairsSitYaw(facing) : p.getLocation().getYaw();
        Location seatAt = center.clone().add(0, 0.5 + def.seatOffset(), 0);
        seatAt.setYaw(yaw);
        // stand on top of the block if there is room for a whole player, else back where they came from
        Location standAt = above.getRelative(BlockFace.UP).isPassable() && above.isPassable()
                ? center.clone().add(0, standHeight + 0.05, 0)
                : p.getLocation().add(0, 0.1, 0);
        SitVariant variant = SitVariant.choose(data instanceof Stairs ? SitVariant.Seat.STAIRS : SitVariant.Seat.SLAB,
                false, SitVariant.Mode.parse(def.variant()));
        sit(p, def, seatAt, standAt, block, yaw, variant);
        return true;
    }

    /** {@code /poloz}: lie down, or stand up when already lying. */
    public void toggleLay(Player p) {
        if (isLying(p.getUniqueId())) {
            standUp(p, true);
            return;
        }
        PoseDef def = plugin.pose(PoseDef.LAY);
        if (!preflight(p, def)) {
            return;
        }
        Location feet = p.getLocation();
        Block ground = feet.clone().subtract(0, 0.05, 0).getBlock();
        Block head = feet.getBlock().getRelative(BlockFace.UP);
        String fail = PoseRules.check(state(p, ground, head));
        if (fail != null) {
            plugin.messages().send(p, fail);
            return;
        }
        boolean animated = plugin.animations().active();
        String poseName = animated ? def.animatedPose() : def.pose();
        if (!"NONE".equals(poseName)) {
            p.setPose("SWIMMING".equals(poseName) ? Pose.SWIMMING : Pose.SLEEPING, true);
        }
        lying.put(p.getUniqueId(), feet.clone());
        afterPose(p, def);
        if (animated) {
            plugin.animations().startPose(p, "lay", feet.getYaw());
        }
    }

    // ------------------------------------------------------------------ internals

    private boolean preflight(Player p, PoseDef def) {
        Messages msg = plugin.messages();
        if (def == null || !def.enabled()) {
            msg.send(p, "interaction-disabled");
            return false;
        }
        if (!p.hasPermission(InteractionService.PERM_USE) || !p.hasPermission(def.permission())) {
            msg.send(p, "no-permission");
            return false;
        }
        if (isPosing(p.getUniqueId())) {
            msg.send(p, "pose-busy");
            return false;
        }
        if (!p.hasPermission(InteractionService.PERM_BYPASS_COOLDOWN)) {
            long left = plugin.service().cooldowns().remaining(p.getUniqueId(), def.cooldownKey(),
                    System.currentTimeMillis());
            if (left > 0) {
                msg.send(p, "cooldown", Messages.text("seconds", Long.toString(Cooldowns.secondsLeft(left))));
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("deprecation") // isOnGround is client-reported; combined with a server-side ground block check
    private PoseRules.State state(Player p, Block ground, Block head) {
        return new PoseRules.State(
                p.isInsideVehicle(),
                p.isOnGround(),
                p.isInWater() || p.getLocation().getBlock().isLiquid(),
                p.isFlying() || p.isGliding() || p.isSwimming(),
                p.getPose() == Pose.STANDING || p.getPose() == Pose.SNEAKING,
                !ground.isPassable() && !ground.isLiquid(),
                head.isPassable() && !head.isLiquid());
    }

    private void sit(Player p, PoseDef def, Location seatAt, Location standAt, Block base, Float yaw,
                     SitVariant variant) {
        World world = seatAt.getWorld();
        BlockDisplay seat = world.spawn(seatAt, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setGravity(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.getPersistentDataContainer().set(seatKey, PersistentDataType.BYTE, (byte) 1);
        });
        if (!seat.addPassenger(p)) {
            seat.remove();
            plugin.messages().send(p, "pose-busy");
            return;
        }
        seats.put(p.getUniqueId(), new Seat(seat, standAt, base));
        if (yaw != null) {
            Location eye = p.getEyeLocation();
            Location dir = eye.clone();
            dir.setYaw(yaw);
            dir.setPitch(10f);
            Vector d = dir.getDirection().multiply(2.0);
            p.lookAt(eye.getX() + d.getX(), eye.getY() + d.getY(), eye.getZ() + d.getZ(), LookAnchor.EYES);
        }
        afterPose(p, def);
        // the BlockDisplay stays the physical seat; the look comes from the sitting animation
        plugin.animations().startPose(p, variant.animation(), yaw != null ? yaw : p.getLocation().getYaw());
    }

    private void afterPose(Player p, PoseDef def) {
        plugin.service().cooldowns().mark(p.getUniqueId(), def.cooldownKey(), def.cooldownMs(),
                System.currentTimeMillis());
        if (!def.broadcast().isEmpty()) {
            plugin.service().broadcastNear(p, null,
                    plugin.messages().parse(def.broadcast(), Messages.component("player", plugin.displayName(p))));
        }
        plugin.messages().send(p, isSitting(p.getUniqueId()) ? "sit-hint" : "lay-hint");
    }

    /**
     * Ends sitting / lying.
     *
     * @param teleport for sitting: move the player to a safe standing spot (not on quit / death / teleport)
     */
    public void standUp(Player p, boolean teleport) {
        UUID id = p.getUniqueId();
        plugin.animations().stopPose(p);
        if (lying.remove(id) != null || p.hasFixedPose()) {
            p.setPose(Pose.STANDING, false);
        }
        Seat seat = seats.remove(id);
        if (seat == null) {
            return;
        }
        if (seat.entity.getPassengers().contains(p)) {
            seat.entity.removePassenger(p);
        }
        Location seatLoc = seat.entity.getLocation();
        seat.entity.remove();
        if (teleport) {
            scheduleStandTeleport(p, seatLoc, seat.standAt);
        }
    }

    private void scheduleStandTeleport(Player p, Location seatLoc, Location standAt) {
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player now = Bukkit.getPlayer(id);
            if (now == null || now.isDead() || now.isInsideVehicle() || !now.getWorld().equals(seatLoc.getWorld())
                    || now.getLocation().distanceSquared(seatLoc) > STAND_TELEPORT_MAX_DISTANCE * STAND_TELEPORT_MAX_DISTANCE) {
                return; // teleported away, died, mounted something else...
            }
            Location to = standAt.clone();
            to.setYaw(now.getLocation().getYaw());
            to.setPitch(now.getLocation().getPitch());
            now.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        });
    }

    /** The player left a vehicle (sneak, death, teleport by another plugin...). */
    public void onDismount(Player p, Entity vehicle) {
        if (!plugin.isEnabled()) {
            return; // onDisable: stopAll() cleans up synchronously, the scheduler is unavailable
        }
        Seat seat = seats.get(p.getUniqueId());
        if (seat != null && seat.entity.equals(vehicle)) {
            // the vehicle is still mid-dismount: forget it now, remove the entity next tick
            seats.remove(p.getUniqueId());
            plugin.animations().stopPose(p);
            Location seatLoc = seat.entity.getLocation();
            Bukkit.getScheduler().runTask(plugin, seat.entity::remove);
            scheduleStandTeleport(p, seatLoc, seat.standAt);
        } else if (isSeat(vehicle)) {
            Bukkit.getScheduler().runTask(plugin, vehicle::remove); // stray seat
        }
    }

    /** Lying: stand up when the player moves away from the spot. */
    public void onMove(Player p, Location to) {
        Location origin = lying.get(p.getUniqueId());
        if (origin == null || to == null) {
            return;
        }
        if (!to.getWorld().equals(origin.getWorld())
                || PoseRules.movedTooFar(to.getX() - origin.getX(), to.getY() - origin.getY(), to.getZ() - origin.getZ())) {
            standUp(p, false);
            plugin.messages().send(p, "pose-stood");
        }
    }

    public void onSneak(Player p) {
        if (isLying(p.getUniqueId())) {
            standUp(p, false);
            plugin.messages().send(p, "pose-stood");
        }
        // sitting: vanilla dismounts on sneak -> EntityDismountEvent -> onDismount
    }

    public void onBlockGone(Block block) {
        for (Map.Entry<UUID, Seat> e : new ArrayList<>(seats.entrySet())) {
            if (e.getValue().base.equals(block)) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    standUp(p, true);
                }
            }
        }
    }

    /** Every 10 ticks: seat still valid, player still on it, the block below still there. */
    public void tick() {
        for (Map.Entry<UUID, Seat> e : new ArrayList<>(seats.entrySet())) {
            Seat seat = e.getValue();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                seats.remove(e.getKey());
                seat.entity.remove();
                continue;
            }
            boolean ok = seat.entity.isValid() && seat.entity.getPassengers().contains(p)
                    && !seat.base.isPassable();
            if (!ok) {
                standUp(p, true);
            }
        }
        for (UUID id : new ArrayList<>(lying.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                lying.remove(id);
            } else if (p.isInsideVehicle() || p.isInWater() || p.isGliding()) {
                standUp(p, false);
            }
        }
    }

    /** Join: make sure nothing from a previous session is left (pose fixed, stray seat as vehicle). */
    public void onJoin(Player p) {
        if (p.hasFixedPose()) {
            p.setPose(Pose.STANDING, false);
        }
        Entity vehicle = p.getVehicle();
        if (vehicle != null && isSeat(vehicle)) {
            p.leaveVehicle();
            vehicle.remove();
        }
    }

    public void onQuit(Player p) {
        standUp(p, false);
    }

    /** Removes seat entities that are not in use (startup, chunk load). */
    public int removeStraySeats(Iterable<? extends Entity> entities) {
        int removed = 0;
        for (Entity e : entities) {
            if (isSeat(e) && !isActiveSeat(e)) {
                e.eject();
                e.remove();
                removed++;
            }
        }
        return removed;
    }

    private boolean isActiveSeat(Entity e) {
        for (Seat s : seats.values()) {
            if (s.entity.equals(e)) {
                return true;
            }
        }
        return false;
    }

    public int removeStraySeatsEverywhere() {
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            removed += removeStraySeats(w.getEntitiesByClass(BlockDisplay.class));
        }
        return removed;
    }

    /** onDisable: everyone stands up where they are, seats are removed. */
    public void stopAll() {
        List<UUID> ids = new ArrayList<>(seats.keySet());
        ids.addAll(lying.keySet());
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            Seat seat = seats.get(id);
            standUp(p, false);
            if (seat != null && p.getWorld().equals(seat.standAt.getWorld())) {
                p.teleport(seat.standAt, PlayerTeleportEvent.TeleportCause.PLUGIN); // scheduler is gone in onDisable
            }
        }
        seats.values().forEach(s -> s.entity.remove());
        seats.clear();
        lying.clear();
    }
}
