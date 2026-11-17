// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Relation;

/**
 * Decides whether a player may perform a protected action at a location.
 */
public final class ProtectionService {

    /** Kinds of protected actions; each maps to a config flag. */
    public enum Flag {
        BLOCK_BREAK, BLOCK_PLACE, CONTAINERS, DOORS, FENCE_GATES, BUTTONS, PRESSURE_PLATES,
        BUCKETS, FIRE, ANIMALS, DECORATIONS, OTHER
    }

    private static final Set<Material> CONTAINER_LIKE = EnumSet.of(
        Material.CHISELED_BOOKSHELF, Material.DECORATED_POT, Material.JUKEBOX, Material.LECTERN);

    private static final Set<Material> OTHER_INTERACTABLE = EnumSet.of(
        Material.REPEATER, Material.COMPARATOR, Material.NOTE_BLOCK, Material.DAYLIGHT_DETECTOR,
        Material.CAKE, Material.RESPAWN_ANCHOR, Material.COMPOSTER, Material.BEACON, Material.DRAGON_EGG,
        Material.REDSTONE_WIRE, Material.SWEET_BERRY_BUSH, Material.CAVE_VINES, Material.CAVE_VINES_PLANT,
        Material.SPAWNER, Material.TRIAL_SPAWNER);

    private static final Set<Material> TRAMPLE = EnumSet.of(Material.FARMLAND, Material.TURTLE_EGG, Material.SNIFFER_EGG);

    private final KrajePlugin plugin;
    private final Set<UUID> bypass = new HashSet<>();
    private final Map<UUID, Long> lastDenyMessage = new HashMap<>();

    public ProtectionService(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    private KrajeConfig.Protection settings() {
        return this.plugin.cfg().protection;
    }

    public boolean enabled() {
        return this.settings().enabled;
    }

    public boolean isFlagProtected(final Flag flag) {
        final KrajeConfig.Protection p = this.settings();
        return switch (flag) {
            case BLOCK_BREAK -> p.blockBreak;
            case BLOCK_PLACE -> p.blockPlace;
            case CONTAINERS -> p.containers;
            case DOORS -> p.doors;
            case FENCE_GATES -> p.fenceGates;
            case BUTTONS -> p.buttons;
            case PRESSURE_PLATES -> p.pressurePlates;
            case BUCKETS -> p.buckets;
            case FIRE -> p.fire;
            case ANIMALS -> p.animals;
            case DECORATIONS -> p.decorations;
            case OTHER -> p.otherInteractions;
        };
    }

    // ------------------------------------------------------------------ bypass

    public boolean toggleBypass(final UUID player) {
        if (this.bypass.remove(player)) {
            return false;
        }
        this.bypass.add(player);
        return true;
    }

    public boolean isBypassing(final UUID player) {
        return this.bypass.contains(player);
    }

    public void forget(final UUID player) {
        this.lastDenyMessage.remove(player);
    }

    // ------------------------------------------------------------------ checks

    /**
     * Country protecting the location, or {@code null}: the land owner, except on an embassy chunk, which is protected for
     * (and buildable by) the guest country that owns the embassy.
     */
    public UUID owner(final Location location) {
        final UUID owner = this.plugin.manager().ownerAt(location);
        return owner == null || location.getWorld() == null ? owner
            : this.embassyAware(owner, location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public UUID owner(final Block block) {
        final UUID owner = this.plugin.manager().ownerAt(block.getWorld(), block.getX(), block.getZ());
        return owner == null ? null : this.embassyAware(owner, block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4);
    }

    private UUID embassyAware(final UUID owner, final String world, final int cx, final int cz) {
        final pl.medivalrpg.kraje.api.EmbassyInfo embassy =
            this.plugin.manager().embassyAt(world, pl.medivalrpg.kraje.util.ChunkKeys.key(cx, cz));
        return embassy != null && embassy.host().equals(owner) ? embassy.guest() : owner;
    }

    /** Whether the player is a member of the owner country or bypasses protection (ignores flags). */
    public boolean isTrusted(final Player player, final UUID owner) {
        if (owner == null || this.isBypassing(player.getUniqueId())) {
            return true;
        }
        // The shared ruler of a personal union is trusted on the land of both countries.
        return owner.equals(this.plugin.manager().countryIdOf(player.getUniqueId()))
            || this.plugin.manager().isUnionMonarch(player.getUniqueId(), owner);
    }

    public boolean canAct(final Player player, final UUID owner, final Flag flag) {
        if (owner == null || !this.enabled() || !this.isFlagProtected(flag)) {
            return true;
        }
        if (this.isTrusted(player, owner)) {
            return true;
        }
        if ((flag == Flag.DOORS || flag == Flag.FENCE_GATES) && this.settings().alliesUseDoors) {
            final UUID own = this.plugin.manager().countryIdOf(player.getUniqueId());
            return this.plugin.manager().relation(own, owner) == Relation.ALLIANCE;
        }
        return false;
    }

    /**
     * Checks and, if denied, informs the player. Returns {@code true} if the action is allowed.
     */
    public boolean check(final Player player, final Location location, final Flag flag) {
        final UUID owner = this.owner(location);
        if (this.canAct(player, owner, flag) || this.siegeAllows(player, location.getBlock(), flag)) {
            return true;
        }
        this.notifyDenied(player, owner);
        return false;
    }

    public boolean check(final Player player, final Block block, final Flag flag) {
        return this.check(player, block.getLocation(), flag);
    }

    /** Attackers may break / place (config) inside a chunk their side is besieging. */
    public boolean siegeAllows(final Player player, final Block block, final Flag flag) {
        return this.plugin.sieges() != null && this.plugin.sieges().allowsAction(player, block, flag);
    }

    public boolean canPrimeTnt(final Player player,final Block block) {
        return this.plugin.tnt()!=null && this.plugin.tnt().canPrime(player,block);
    }

    public void notifyDenied(final Player player, final UUID owner) {
        final long now = System.currentTimeMillis();
        final Long last = this.lastDenyMessage.get(player.getUniqueId());
        if (last != null && now - last < this.settings().denyMessageCooldownMillis) {
            return;
        }
        this.lastDenyMessage.put(player.getUniqueId(), now);
        final Country c = this.plugin.manager().get(owner);
        if (c == null) {
            return;
        }
        player.sendActionBar(this.plugin.messages().get("protection-denied",
            Placeholder.unparsed("country", pl.medivalrpg.kraje.manager.Notify.displayName(c)), Placeholder.styling("color", c.textColor())));
    }

    // ------------------------------------------------------------------ classification

    /** Flag protecting a right-click on this block, or {@code null} if right-clicking it is harmless. */
    public static Flag classifyInteraction(final Block block) {
        final Material type = block.getType();
        if (Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type)) {
            return Flag.DOORS;
        }
        if (Tag.FENCE_GATES.isTagged(type)) {
            return Flag.FENCE_GATES;
        }
        if (Tag.BUTTONS.isTagged(type) || type == Material.LEVER) {
            return Flag.BUTTONS;
        }
        if (CONTAINER_LIKE.contains(type) || Tag.WOODEN_SHELVES.isTagged(type) || Tag.SHULKER_BOXES.isTagged(type)
            || Tag.COPPER_CHESTS.isTagged(type)) {
            return Flag.CONTAINERS;
        }
        if (OTHER_INTERACTABLE.contains(type) || Tag.CANDLE_CAKES.isTagged(type) || Tag.CANDLES.isTagged(type)
            || Tag.FLOWER_POTS.isTagged(type) || Tag.ALL_SIGNS.isTagged(type) || Tag.ANVIL.isTagged(type)
            || Tag.BEDS.isTagged(type) || Tag.CAULDRONS.isTagged(type) || Tag.CAMPFIRES.isTagged(type)
            || Tag.COPPER_GOLEM_STATUES.isTagged(type)) {
            return Flag.OTHER;
        }
        // Last: block entity check (chests, barrels, furnaces, hoppers, brewing stands, crafters...).
        if (block.getState(false) instanceof Container) {
            return Flag.CONTAINERS;
        }
        return null;
    }

    /** Flag for a physical interaction (stepping on a block), or {@code null}. */
    public static Flag classifyPhysical(final Block block) {
        final Material type = block.getType();
        if (Tag.PRESSURE_PLATES.isTagged(type)) {
            return Flag.PRESSURE_PLATES;
        }
        if (TRAMPLE.contains(type)) {
            return Flag.BLOCK_BREAK;
        }
        return null;
    }

    /** Whether using this item on a block modifies the world (strip logs, till, bone meal, place entities...). */
    public static boolean isWorldModifyingItem(final Material item) {
        if (item == null || item.isAir()) {
            return false;
        }
        final String name = item.name();
        return name.endsWith("_AXE") || name.endsWith("_HOE") || name.endsWith("_SHOVEL")
            || name.endsWith("_SPAWN_EGG") || name.endsWith("_BOAT") || name.endsWith("_RAFT")
            || name.endsWith("MINECART") || name.endsWith("_DYE")
            || item == Material.BONE_MEAL || item == Material.BRUSH || item == Material.HONEYCOMB
            || item == Material.ARMOR_STAND || item == Material.END_CRYSTAL || item == Material.ITEM_FRAME
            || item == Material.GLOW_ITEM_FRAME || item == Material.PAINTING || item == Material.SHEARS
            || item == Material.GLOW_INK_SAC || item == Material.INK_SAC;
    }

    /** Resolves the responsible player of a damager / remover entity (direct or via projectile). */
    public static Player responsiblePlayer(final Entity entity) {
        if (entity instanceof Player p) {
            return p;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }
}
