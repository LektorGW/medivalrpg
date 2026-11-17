// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.config.Messages;

/**
 * Server spawn: {@code spawn.location} from config (set by {@code /ustawspawn}), otherwise the spawn of the main
 * world.
 *
 * <ul>
 *   <li>After login (Straznik, fallback nLogin) every player ({@code spawn.always-on-join}) or only new players
 *       ({@code spawn.teleport-new-players}) land on the spawn. For a few seconds afterwards a plugin teleport
 *       away from the spawn (the login plugin restoring the old position) is answered with one more teleport to the spawn.</li>
 *   <li>The position at quit is remembered ({@code data/last-positions.json}); a returning player gets a clickable
 *       {@code /powrot} offer, valid for {@code spawn.return-window-minutes}, usable once, with the same countdown
 *       and combat / siege checks as {@code /spawn}. The target is checked for a safe place to stand.</li>
 *   <li>Optionally respawns players without a bed or anchor on the spawn.</li>
 * </ul>
 * Main thread only.
 */
public final class SpawnService implements Listener {

    public static final String KIND = "spawn";
    public static final String KIND_RETURN = "return";
    public static final String PERM_BYPASS = "menu.spawn.bypass";
    /** How long after the join teleport a foreign plugin teleport is undone. */
    private static final long GUARD_MS = 4000L;
    /** "Far from the spawn" for the guard. */
    private static final double GUARD_DISTANCE = 3.0;

    private static final Set<Material> DANGER = EnumSet.noneOf(Material.class);

    static {
        for (final String name : new String[] {"LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CAMPFIRE", "SOUL_CAMPFIRE",
            "CACTUS", "SWEET_BERRY_BUSH", "WITHER_ROSE", "POWDER_SNOW", "POINTED_DRIPSTONE", "COBWEB", "END_PORTAL",
            "NETHER_PORTAL", "END_GATEWAY", "LAVA_CAULDRON"}) {
            final Material m = Material.matchMaterial(name);
            if (m != null) {
                DANGER.add(m);
            }
        }
    }

    private record Guard(long until, Location spawn) {
    }

    private final Plugin plugin;
    private final Supplier<MenuSettings> settings;
    private final Supplier<Messages> messages;
    private final TeleportService teleports;
    private final PositionStore positions;
    private final Logger logger;
    private final ReturnWindow returns = new ReturnWindow();
    /**
     * Players who joined for the first time and have not logged in yet. Kept across a quit, so a newcomer who
     * leaves before registering still lands on the spawn after the next login (until restart).
     */
    private final Set<UUID> newPlayers = new HashSet<>();
    /** Logged in this session – only their quit position is real (not the login limbo). */
    private final Set<UUID> ready = new HashSet<>();
    private final Map<UUID, Guard> guards = new HashMap<>();
    private boolean warnedWorld;

    public SpawnService(final Plugin plugin, final Supplier<MenuSettings> settings, final Supplier<Messages> messages,
                        final TeleportService teleports, final PositionStore positions, final Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.teleports = teleports;
        this.positions = positions;
        this.logger = logger;
    }

    /** Where {@code /spawn} leads; empty only when no world is loaded at all. */
    public Optional<Location> location() {
        final Optional<StoredLocation> stored = this.settings.get().spawnLocation;
        if (stored.isPresent()) {
            final Optional<Location> loc = stored.get().toLocation();
            if (loc.isPresent()) {
                return loc;
            }
            if (!this.warnedWorld) {
                this.warnedWorld = true;
                this.logger.warning("spawn.location: świat '" + stored.get().world()
                    + "' nie jest wczytany – używam spawnu głównego świata.");
            }
        }
        if (Bukkit.getWorlds().isEmpty()) {
            return Optional.empty();
        }
        final Location spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
        if (spawn.getX() == Math.floor(spawn.getX()) && spawn.getZ() == Math.floor(spawn.getZ())) {
            spawn.add(0.5, 0, 0.5); // block coordinates → centre of the block
        }
        return Optional.of(spawn);
    }

    /** {@code /spawn}: countdown teleport with all checks. */
    public void teleport(final Player player) {
        final MenuSettings s = this.settings.get();
        this.teleports.request(player, new TeleportService.Kind(KIND, s.spawnWarmupSeconds, s.spawnCooldownSeconds,
                PERM_BYPASS),
            p -> this.location().<TeleportService.Target>map(TeleportService.Target.Ok::new)
                .orElseGet(() -> new TeleportService.Target.Denied(this.messages.get().get("spawn-missing"))),
            this.messages.get().get("spawn-arrived"));
    }

    /** Moves the world spawn so new players and respawns appear there. */
    public static boolean applyWorldSpawn(final Location location) {
        final World world = location.getWorld();
        return world != null && world.setSpawnLocation(location);
    }

    // ------------------------------------------------------------------ join / quit

    /** Called once per session after login (Straznik/nLogin, see {@code LoginWatcher}). */
    public void onLoggedIn(final Player player) {
        final UUID id = player.getUniqueId();
        final boolean isNew = this.newPlayers.remove(id);
        final Location current = player.getLocation();
        this.ready.add(id);
        final MenuSettings s = this.settings.get();
        if (!s.spawnAlwaysOnJoin && !(isNew && s.spawnNewPlayers)) {
            return;
        }
        final Optional<Location> spawnLoc = this.location();
        if (spawnLoc.isEmpty()) {
            return;
        }
        final Location spawn = spawnLoc.get();
        final StoredLocation spawnStored = StoredLocation.of(spawn);
        // where to offer the way back: the position saved at the last quit, else where the login plugin put the player now
        final Optional<StoredLocation> back = isNew ? Optional.empty()
            : this.positions.get(id).or(() -> Optional.of(StoredLocation.of(current)))
                .filter(l -> ReturnWindow.worthRemembering(l, spawnStored, s.returnKeepRadius));
        this.teleports.teleportNow(player, spawn, ok -> {
            if (!ok || !player.isOnline()) {
                return;
            }
            this.guards.put(id, new Guard(System.currentTimeMillis() + GUARD_MS, spawn));
            if (back.isPresent() && s.returnWindowMinutes > 0) {
                this.returns.open(id, back.get(), System.currentTimeMillis(), s.returnWindowMinutes);
                final var minutes = Placeholder.unparsed("minutes", Integer.toString(s.returnWindowMinutes));
                player.sendMessage(this.messages.get().get("return-offer", minutes,
                    Placeholder.unparsed("location", back.get().describe())));
                player.sendActionBar(this.messages.get().get("return-actionbar", minutes));
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        if (!event.getPlayer().hasPlayedBefore()) {
            this.newPlayers.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.remember(event.getPlayer());
        this.returns.consume(event.getPlayer().getUniqueId());
        this.guards.remove(event.getPlayer().getUniqueId());
    }

    /** Saves the position of a logged-in player unless they are still at the spawn. */
    private void remember(final Player player) {
        if (!this.ready.remove(player.getUniqueId())) {
            return;
        }
        final StoredLocation at = StoredLocation.of(player.getLocation());
        final StoredLocation spawn = this.location().map(StoredLocation::of).orElse(null);
        if (ReturnWindow.worthRemembering(at, spawn, this.settings.get().returnKeepRadius)) {
            this.positions.set(player.getUniqueId(), at);
        }
    }

    /** Plugin disable: remember everyone still online (quit events come after plugins are disabled). */
    public void stop() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            this.remember(p);
        }
    }

    /** The login plugin (or another plugin) moving the player back right after the join teleport → spawn again, once. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForeignTeleport(final PlayerTeleportEvent event) {
        final Guard guard = this.guards.get(event.getPlayer().getUniqueId());
        if (guard == null) {
            return;
        }
        if (System.currentTimeMillis() > guard.until()) {
            this.guards.remove(event.getPlayer().getUniqueId());
            return;
        }
        final PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.PLUGIN && cause != PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            return;
        }
        final Location to = event.getTo();
        if (to.getWorld() == guard.spawn().getWorld() && to.distanceSquared(guard.spawn()) <= GUARD_DISTANCE * GUARD_DISTANCE) {
            return;
        }
        this.guards.remove(event.getPlayer().getUniqueId());
        final Player player = event.getPlayer();
        this.logger.fine("Teleport " + player.getName() + " po zalogowaniu nadpisany przez inny plugin – ponownie na spawn.");
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (player.isOnline()) {
                this.teleports.teleportNow(player, guard.spawn());
            }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(final PlayerRespawnEvent event) {
        if (!this.settings.get().spawnOnRespawn
            || event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH
            || event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        this.location().ifPresent(event::setRespawnLocation);
    }

    // ------------------------------------------------------------------ /powrot

    /** Is the one-time way back still open (menu condition {@code can-return})? */
    public boolean canReturn(final Player player) {
        return this.returns.canReturn(player.getUniqueId(), System.currentTimeMillis());
    }

    /** {@code /powrot}: countdown teleport to the last position (once, within the window). */
    public void returnBack(final Player player) {
        final UUID id = player.getUniqueId();
        final Optional<StoredLocation> target = this.returns.get(id, System.currentTimeMillis());
        if (target.isEmpty()) {
            player.sendMessage(this.messages.get().get("return-unavailable"));
            return;
        }
        final Optional<Location> loc = target.get().toLocation();
        if (loc.isEmpty()) {
            player.sendMessage(this.messages.get().get("return-world-missing"));
            return;
        }
        final MenuSettings s = this.settings.get();
        final Location base = loc.get();
        // load the chunks around the target first, so the safety check never blocks the main thread
        final World world = base.getWorld();
        final int r = s.returnSafeRadius;
        final List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = (base.getBlockX() - r) >> 4; cx <= (base.getBlockX() + r) >> 4; cx++) {
            for (int cz = (base.getBlockZ() - r) >> 4; cz <= (base.getBlockZ() + r) >> 4; cz++) {
                loads.add(world.getChunkAtAsync(cx, cz));
            }
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((v, err) ->
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                this.teleports.request(player, new TeleportService.Kind(KIND_RETURN, s.spawnWarmupSeconds, 0, PERM_BYPASS),
                    p -> this.safe(base, s.returnSafeRadius)
                        .<TeleportService.Target>map(TeleportService.Target.Ok::new)
                        .orElseGet(() -> new TeleportService.Target.Denied(this.messages.get().get("return-unsafe",
                            Placeholder.unparsed("radius", Integer.toString(s.returnSafeRadius))))),
                    this.messages.get().get("return-arrived"),
                    () -> this.returns.consume(id));
            }));
    }

    /** The location itself when safe, else the centre of the nearest safe block within {@code radius}. */
    Optional<Location> safe(final Location at, final int radius) {
        final World world = at.getWorld();
        final SafeSpot.Probe probe = (x, y, z) -> classify(world.getBlockAt(x, y, z));
        final int bx = at.getBlockX();
        final int by = at.getBlockY();
        final int bz = at.getBlockZ();
        return SafeSpot.find(probe, bx, by, bz, radius, world.getMinHeight() + 1, world.getMaxHeight() - 2)
            .map(spot -> spot.x() == bx && spot.y() == by && spot.z() == bz ? at.clone()
                : new Location(world, spot.x() + 0.5, spot.y(), spot.z() + 0.5, at.getYaw(), at.getPitch()));
    }

    static SafeSpot.Cell classify(final Block block) {
        final Material m = block.getType();
        if (DANGER.contains(m) || Tag.FIRE.isTagged(m) || Tag.CAMPFIRES.isTagged(m)) {
            return SafeSpot.Cell.DANGER;
        }
        if (block.isLiquid() || m == Material.BUBBLE_COLUMN) {
            return SafeSpot.Cell.LIQUID;
        }
        return m.isSolid() ? SafeSpot.Cell.SOLID : SafeSpot.Cell.PASSABLE;
    }
}
