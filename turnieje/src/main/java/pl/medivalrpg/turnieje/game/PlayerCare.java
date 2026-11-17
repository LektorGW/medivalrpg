// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;
import pl.medivalrpg.turnieje.arena.GearMode;
import pl.medivalrpg.turnieje.arena.KitSpec;
import pl.medivalrpg.turnieje.arena.Pos;
import pl.medivalrpg.turnieje.config.TurniejeConfig;
import pl.medivalrpg.turnieje.data.Snapshot;
import pl.medivalrpg.turnieje.data.Storage;

/**
 * Everything done to a player's own state: snapshot on entry (persisted before anything is changed), kit, healing,
 * GrimAC-friendly teleports and the restore on exit (also after a crash, applied at join). Main thread only.
 */
public final class PlayerCare {

    private final JavaPlugin plugin;
    private final Storage storage;
    private final Supplier<TurniejeConfig> config;
    private final Keys keys;
    /** Players being teleported by us (our own teleports pass the teleport guard). */
    private final Set<UUID> ownTeleports = new HashSet<>();

    public PlayerCare(final JavaPlugin plugin, final Storage storage, final Supplier<TurniejeConfig> config, final Keys keys) {
        this.plugin = plugin;
        this.storage = storage;
        this.config = config;
        this.keys = keys;
    }

    public void shuttingDown() {
        // Teleports already complete synchronously on the main thread.
    }

    // ---- snapshot / restore ---------------------------------------------------------------------

    /** True when {@link #capture} will not refuse the player for an item held on the inventory cursor. */
    public static boolean canCapture(final Player p) {
        final ItemStack cursor = p.getItemOnCursor();
        return cursor == null || cursor.isEmpty();
    }

    /**
     * Stores the player's state; in kit mode also the inventory, which is then cleared. The snapshot is written to
     * disk before the inventory is touched. {@code returnTo} overrides the stored position (e.g. a spectator's origin).
     */
    public void capture(final Player p, final GearMode gear, final String session, final Location returnTo) {
        if (this.storage.hasSnapshot(p.getUniqueId())) {
            // stale snapshot (should not happen: it is applied at join) — apply it first so nothing is lost
            if (!this.restore(p)) throw new IllegalStateException("Nieodzyskany ekwipunek " + p.getUniqueId());
        }
        if (!p.getItemOnCursor().isEmpty()) throw new IllegalStateException("Opróżnij kursor przed wejściem");
        p.closeInventory();
        final Snapshot s = new Snapshot();
        s.location = Pos.of(returnTo != null ? returnTo : p.getLocation());
        s.health = p.getHealth();
        s.food = p.getFoodLevel();
        s.saturation = p.getSaturation();
        s.gameMode = p.getGameMode().name();
        s.session = session + ":" + UUID.randomUUID();
        if (gear == GearMode.KIT) {
            s.inventoryStored = true;
            s.items = encode(p.getInventory().getContents());
            final ItemStack cursor = p.getItemOnCursor();
            if (!cursor.isEmpty()) {
                throw new IllegalStateException("Przed wejściem opróżnij kursor ekwipunku");
            }
        }
        this.storage.putSnapshot(p.getUniqueId(), s);
        p.setItemOnCursor(null);
        if (gear == GearMode.KIT) {
            p.getInventory().clear();
        }
        p.setGameMode(GameMode.ADVENTURE);
    }

    /**
     * Applies and removes the snapshot. False (snapshot kept) when there is none or the player is dead — then it is
     * applied after respawn or at the next join.
     */
    public boolean restore(final Player p) {
        return this.restore(p, Pos::toLocation);
    }

    /** {@link #restore(Player)} with the world lookup injectable (tests run without a server). */
    boolean restore(final Player p, final java.util.function.Function<Pos, Location> worlds) {
        final Snapshot s = this.storage.snapshot(p.getUniqueId());
        if (s == null) {
            this.stripKit(p);
            return false;
        }
        if (p.isDead()) {
            return false;
        }
        if (s.session.equals(p.getPersistentDataContainer().get(this.keys.restored, PersistentDataType.STRING))) {
            p.saveData();
            this.storage.removeSnapshot(p.getUniqueId());
            return true;
        }
        // Decode before clearing: corrupt item data must leave the player and snapshot intact.
        final List<ItemStack> extra = new ArrayList<>();
        final ItemStack[] recovered = s.inventoryStored ? decode(s.items, p.getInventory().getSize(), extra) : null;
        if (!extra.isEmpty()) throw new IllegalStateException("Snapshot z nadmiarowymi slotami: wymagana ręczna kontrola");
        final Location destination = s.location == null ? null : worlds.apply(s.location);
        if (destination == null) throw new IllegalStateException("Świat powrotu jest niedostępny");
        p.closeInventory();
        if (p.isInsideVehicle()) {
            p.leaveVehicle();
        }
        // Teleport BEFORE touching the inventory: a refused teleport (e.g. the login limbo of Logowanie, which keeps
        // items "added by other plugins" on login) must leave nothing restored while the snapshot stays pending —
        // otherwise the next restore would hand the same items out a second time.
        this.ownTeleports.add(p.getUniqueId());
        try {
            if (!p.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN))
                throw new IllegalStateException("Teleport powrotny anulowany");
        } finally { this.ownTeleports.remove(p.getUniqueId()); }
        final PlayerInventory inv = p.getInventory();
        if (s.inventoryStored) {
            inv.clear();
            p.setItemOnCursor(null);
            inv.setContents(recovered);
        } else {
            this.stripKit(p);
        }
        try {
            p.setGameMode(GameMode.valueOf(s.gameMode));
        } catch (final RuntimeException e) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        final double max = maxHealth(p);
        p.setHealth(Math.max(1.0, Math.min(max, s.health)));
        p.setFoodLevel(Math.max(0, Math.min(20, s.food)));
        p.setSaturation(Math.max(0f, s.saturation));
        p.setFireTicks(0);
        p.setFallDistance(0f);
        // Save restored inventory and receipt together before retiring the snapshot.
        p.getPersistentDataContainer().set(this.keys.restored, PersistentDataType.STRING, s.session);
        p.saveData();
        this.storage.removeSnapshot(p.getUniqueId());
        return true;
    }

    /** Disconnect inside a tournament: kit items vanish now; the snapshot stays and is applied at the next join. */
    public void onQuitInside(final Player p) {
        final Snapshot s = this.storage.snapshot(p.getUniqueId());
        if (p.isInsideVehicle()) {
            p.leaveVehicle();
        }
        if (s != null && s.inventoryStored) {
            p.getInventory().clear();
            p.setItemOnCursor(null);
        } else {
            this.stripKit(p);
        }
    }

    // ---- kit ------------------------------------------------------------------------------------

    /** Clears the inventory and gives the configured kit (all items PDC-marked). Kit mode only. */
    public void giveKit(final Player p, final boolean mounted) {
        final TurniejeConfig c = this.config.get();
        final PlayerInventory inv = p.getInventory();
        inv.clear();
        p.setItemOnCursor(null);
        inv.setHelmet(this.kitItem(c.helmet, 1));
        inv.setChestplate(this.kitItem(c.chestplate, 1));
        inv.setLeggings(this.kitItem(c.leggings, 1));
        inv.setBoots(this.kitItem(c.boots, 1));
        inv.setItemInOffHand(this.kitItem(c.offhand, 1));
        if (mounted) {
            ItemStack lance = this.kitItem(c.lance, 1);
            if (lance == null) {
                lance = this.kitItem(c.fallbackLance, 1);
            }
            if (lance != null) {
                inv.addItem(lance);
            }
        }
        for (final KitSpec spec : c.kitItems) {
            final ItemStack item = this.kitItem(spec.material(), spec.amount());
            if (item != null) {
                inv.addItem(item);
            }
        }
    }

    /** Kit mode, after a won fight: back to the empty inventory of a waiting participant. */
    public void clearKit(final Player p) {
        p.getInventory().clear();
        p.setItemOnCursor(null);
    }

    private ItemStack kitItem(final String materialName, final int amount) {
        if (materialName == null || materialName.isBlank()) {
            return null;
        }
        final Material m = Material.matchMaterial(materialName.trim().toUpperCase(Locale.ROOT));
        if (m == null || !m.isItem() || m.isAir()) {
            return null;
        }
        final ItemStack item = new ItemStack(m, Math.max(1, Math.min(amount, m.getMaxStackSize())));
        final ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(this.keys.kit, PersistentDataType.BYTE, (byte) 1);
            if (m.getMaxDurability() > 0) {
                meta.setUnbreakable(true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isKit(final ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(this.keys.kit, PersistentDataType.BYTE);
    }

    /** Removes every kit item from the inventory and the cursor (they must never exist outside a fight). */
    public void stripKit(final Player p) {
        final PlayerInventory inv = p.getInventory();
        final ItemStack[] contents = inv.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (this.isKit(contents[i])) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) {
            inv.setContents(contents);
        }
        if (this.isKit(p.getItemOnCursor())) {
            p.setItemOnCursor(null);
        }
    }

    // ---- health ---------------------------------------------------------------------------------

    /** Full health and food, no fire, no potion effects (fair start of each fight). */
    public void heal(final Player p) {
        p.setHealth(maxHealth(p));
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (final PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(e.getType());
        }
    }

    public static double maxHealth(final Player p) {
        final AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20.0 : a.getValue();
    }

    // ---- teleports ------------------------------------------------------------------------------

    /**
     * Teleport friendly to GrimAC: leaves vehicles, drops passengers, zero velocity and fall distance, synchronous
     * completion; no callback may teleport an already restored player back into the event.
     */
    public void teleport(final Player p, final Location to, final Runnable after) {
        if (p.isInsideVehicle()) {
            p.leaveVehicle();
        }
        p.eject();
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(0f);
        final UUID id = p.getUniqueId();
        this.ownTeleports.add(id);
        try {
            if (p.isOnline() && p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN) && after != null) after.run();
        } finally {
            this.ownTeleports.remove(id);
        }
    }

    public boolean hasSnapshot(final Player p) { return this.storage.hasSnapshot(p.getUniqueId()); }

    public boolean isOwnTeleport(final UUID player) {
        return this.ownTeleports.contains(player);
    }

    // ---- item (de)serialisation ------------------------------------------------------------------

    static List<String> encode(final ItemStack[] items) {
        final List<String> out = new ArrayList<>(items.length);
        for (final ItemStack i : items) {
            out.add(i == null || i.isEmpty() ? null : encodeOne(i));
        }
        return out;
    }

    static String encodeOne(final ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    /** Items for an inventory of {@code size}; entries beyond it (e.g. the stored cursor) go to {@code extra}. */
    static ItemStack[] decode(final List<String> items, final int size, final List<ItemStack> extra) {
        final ItemStack[] out = new ItemStack[size];
        if (items == null || items.size() < size) {
            throw new IllegalArgumentException("Niepełny snapshot ekwipunku");
        }
        for (int i = 0; i < items.size(); i++) {
            final String b64 = items.get(i);
            if (b64 == null) {
                continue;
            }
            final ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
            if (i < size) {
                out[i] = item;
            } else {
                extra.add(item);
            }
        }
        return out;
    }
}
