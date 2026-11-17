// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.util.SafeSpot;

/**
 * {@code /kraj admin ...} (permission {@code kraje.admin}).
 */
final class AdminCommands {

    /** Blocks a player must never be teleported into or onto. */
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

    private final KrajePlugin plugin;
    private final KrajCommand root;
    private final AdminTerritory territory;
    private final AdminRestore restore;

    AdminCommands(final KrajePlugin plugin, final KrajCommand root) {
        this.plugin = plugin;
        this.root = root;
        this.territory = new AdminTerritory(plugin, root);
        this.restore = new AdminRestore(plugin, root);
    }

    /** Stops previews, running area operations and the terrain restore (plugin disable). */
    void shutdown() {
        this.territory.shutdown();
        this.restore.shutdown();
    }

    void dispatch(final CommandSender sender, final String[] args) {
        if (args.length == 0) {
            this.plugin.messages().send(sender, "admin-help");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "usun" -> this.delete(sender, args);
            case "wladca" -> this.setRuler(sender, args);
            case "zajmij" -> this.claim(sender, args);
            case "porzuc" -> {
                if (args.length < 2) {
                    this.unclaim(sender);
                } else {
                    this.territory.handle(sender, AdminTerritory.parse(AdminTerritory.Op.UNCLAIM, rest(args)));
                }
            }
            case "przylacz" -> this.territory.handle(sender, AdminTerritory.parse(AdminTerritory.Op.RINGS, rest(args)));
            case "przywroc" -> this.restore.handle(sender, AdminRestore.parse(rest(args)));
            case "tp" -> this.teleport(sender, args);
            case "bypass" -> {
                final Player p = KrajCommand.requirePlayer(sender);
                final boolean on = this.plugin.protection().toggleBypass(p.getUniqueId());
                this.plugin.messages().send(p, on ? "admin-bypass-on" : "admin-bypass-off");
            }
            case "spy" -> {
                final Player p = KrajCommand.requirePlayer(sender);
                final boolean on = this.plugin.chat().toggleSpy(p.getUniqueId());
                this.plugin.messages().send(p, on ? "admin-spy-on" : "admin-spy-off");
            }
            case "reload" -> {
                this.plugin.reload();
                this.plugin.messages().send(sender, "admin-reloaded");
            }
            case "tick" -> {
                this.plugin.treasury().requireActive();
                this.plugin.treasury().runTick();
                this.plugin.messages().send(sender, "admin-tick-done");
            }
            case "oblezenie" -> {
                final Player p = KrajCommand.requirePlayer(sender);
                if (!this.plugin.sieges().stopAt(ChunkPos.of(p.getLocation()))) {
                    throw new KrajeException("admin-no-siege");
                }
                this.plugin.messages().send(p, "admin-siege-stopped");
            }
            default -> this.plugin.messages().send(sender, "admin-help");
        }
    }

    /**
     * {@code /kraj admin usun <kraj>}: deletes the country. With {@code przywroc [potwierdz]} after the name the
     * country is deleted and its terrain regenerated afterwards ({@link AdminRestore}, preview first).
     */
    private void delete(final CommandSender sender, final String[] args) {
        KrajCommand.requireArgs(args, 2, "usage-admin-usun");
        if (AdminRestore.isDeleteRestore(rest(args))) {
            this.restore.handle(sender, AdminRestore.parseDelete(rest(args)));
            return;
        }
        final Country c = this.root.requireCountryArg(args[1]);
        this.root.disbandCountry(c, ClaimCause.ADMIN);
        this.plugin.messages().send(sender, "admin-deleted", KrajCommand.country(c));
        this.root.notifyMembers(c, "admin-deleted-members", KrajCommand.country(c));
    }

    private void setRuler(final CommandSender sender, final String[] args) {
        KrajCommand.requireArgs(args, 3, "usage-admin-wladca");
        final Country c = this.root.requireCountryArg(args[1]);
        final String resolvedName = this.plugin.actions().setRuler(c, args[2]);
        this.plugin.messages().send(sender, "admin-ruler-set", KrajCommand.country(c), KrajCommand.unparsed("player", resolvedName));
    }

    private static String[] rest(final String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    /**
     * {@code /kraj admin zajmij <kraj>}: the current chunk (as always); with {@code promien <n>} or
     * {@code zaznaczenie} the area tools of {@link AdminTerritory}.
     */
    private void claim(final CommandSender sender, final String[] args) {
        KrajCommand.requireArgs(args, 2, "usage-admin-zajmij");
        final AdminTerritory.Request request = AdminTerritory.parse(AdminTerritory.Op.CLAIM, rest(args));
        if (request.mode() != AdminTerritory.Mode.HERE) {
            this.territory.handle(sender, request);
            return;
        }
        final Player player = KrajCommand.requirePlayer(sender);
        final Country whole = this.plugin.manager().byName(request.country());
        final Country c = whole != null ? whole : this.root.requireCountryArg(args[1]);
        final ChunkPos pos = ChunkPos.of(player.getLocation());
        this.plugin.actions().claim(c, pos, ClaimCause.ADMIN, player.getName());
        this.plugin.messages().send(player, "admin-claimed", KrajCommand.country(c), KrajCommand.unparsed("count", c.claimCount()));
    }

    private void unclaim(final CommandSender sender) {
        final Player player = KrajCommand.requirePlayer(sender);
        final ChunkPos pos = ChunkPos.of(player.getLocation());
        final Country owner = this.plugin.manager().countryAt(pos);
        if (owner == null) {
            throw new KrajeException("chunk-not-claimed");
        }
        this.plugin.actions().unclaim(owner, pos, ClaimCause.ADMIN);
        this.plugin.messages().send(player, "admin-unclaimed", KrajCommand.country(owner));
    }

    /**
     * {@code /kraj admin tp <kraj>}: straight to the capital spawn point of any country (player or NPC), no warmup.
     * The chunks around the point are loaded asynchronously first; then the point itself or the nearest safe block
     * within {@link SafeSpot#DEFAULT_RADIUS} is used (fallback: the same search from the highest block of the column).
     */
    private void teleport(final CommandSender sender, final String[] args) {
        KrajCommand.requireArgs(args, 2, "usage-admin-tp");
        final Player player = KrajCommand.requirePlayer(sender);
        // Names have no spaces, but an NPC country created with force may; try the whole argument first.
        final Country whole = this.plugin.manager().byName(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        final Country c = whole != null ? whole : this.root.requireCountryArg(args[1]);
        final Location point = c.spawn() == null ? null : c.spawn().toLocation();
        if (point == null) {
            throw new KrajeException("spawn-unavailable");
        }
        final World world = point.getWorld();
        final int radius = SafeSpot.DEFAULT_RADIUS;
        final List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = (point.getBlockX() - radius) >> 4; cx <= (point.getBlockX() + radius) >> 4; cx++) {
            for (int cz = (point.getBlockZ() - radius) >> 4; cz <= (point.getBlockZ() + radius) >> 4; cz++) {
                loads.add(world.getChunkAtAsync(cx, cz));
            }
        }
        this.plugin.messages().send(player, "admin-tp-searching", KrajCommand.country(c));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
            .whenCompleteAsync((ignored, error) -> {
                if (!player.isOnline()) {
                    return;
                }
                if (error != null) {
                    this.plugin.getLogger().warning("/kraj admin tp: nie udało się wczytać chunków stolicy " + c.name() + ": " + error);
                    this.plugin.messages().send(player, "spawn-unavailable");
                    return;
                }
                final Optional<Location> target = safe(point, radius)
                    .or(() -> safe(surface(point), radius));
                if (target.isEmpty()) {
                    this.plugin.messages().send(player, "admin-tp-unsafe", KrajCommand.country(c),
                        KrajCommand.unparsed("radius", radius));
                    return;
                }
                final Location dest = target.get();
                player.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
                    if (Boolean.TRUE.equals(ok) && player.isOnline()) {
                        this.plugin.messages().send(player, "admin-tp-done", KrajCommand.country(c),
                            KrajCommand.unparsed("x", dest.getBlockX()), KrajCommand.unparsed("y", dest.getBlockY()),
                            KrajCommand.unparsed("z", dest.getBlockZ()));
                    } else if (player.isOnline()) {
                        this.plugin.messages().send(player, "admin-tp-failed", KrajCommand.country(c));
                    }
                });
            }, Bukkit.getScheduler().getMainThreadExecutor(this.plugin));
    }

    /** The location itself when safe, else the centre of the nearest safe block within {@code radius}. Main thread. */
    static Optional<Location> safe(final Location at, final int radius) {
        final World world = at.getWorld();
        final SafeSpot.Probe probe = (x, y, z) -> classify(world.getBlockAt(x, y, z));
        final int bx = at.getBlockX();
        final int by = at.getBlockY();
        final int bz = at.getBlockZ();
        return SafeSpot.find(probe, bx, by, bz, radius, world.getMinHeight() + 1, world.getMaxHeight() - 2)
            .map(spot -> spot.x() == bx && spot.y() == by && spot.z() == bz ? at.clone()
                : new Location(world, spot.x() + 0.5, spot.y(), spot.z() + 0.5, at.getYaw(), at.getPitch()));
    }

    /** Block above the highest non-air block of the column, looking the same way as {@code point}. */
    private static Location surface(final Location point) {
        final Location top = point.getWorld().getHighestBlockAt(point).getLocation().add(0, 1, 0);
        top.setYaw(point.getYaw());
        top.setPitch(point.getPitch());
        return top;
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
