// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * Country granaries: a virtual food store per country. Players put food into granary containers (chests/barrels of a
 * finished granary building, or containers marked with {@code /ludnosc spichlerz oznacz}); when the container is
 * closed the food items are converted into food points (config {@code food.items}) up to the capacity.
 */
public final class GranaryService {

    public enum MarkResult { OK, ALREADY, LIMIT, NOT_MARKED }

    private final LudnoscPlugin plugin;
    /** Container position → country (index of every country's {@code granaryChests}). */
    private final Map<BlockPos, UUID> index = new HashMap<>();

    public GranaryService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    public void rebuildIndex() {
        this.index.clear();
        this.data().states().forEach((country, state) -> {
            for (final BlockPos p : state.chests()) {
                this.index.put(p, country);
            }
        });
    }

    public long capacity(final UUID country) {
        long granaries = 0;
        for (final Building b : this.data().buildingsOf(country)) {
            if (b.function() == BuildingFunction.GRANARY) {
                granaries++;
            }
        }
        return this.plugin.settings().baseCapacity + granaries * this.plugin.settings().capacityPerGranary
            + this.plugin.economy().bonus(country).granary()
            + this.plugin.underground().cellars(country) * this.plugin.settings().cellarCapacity;
    }

    public long food(final UUID country) {
        final CountryState s = this.data().stateIfPresent(country);
        return s == null ? 0 : s.food;
    }

    public void setFood(final UUID country, final long food) {
        this.data().state(country).food = Math.max(0, food);
        this.data().markDirty();
    }

    public static BlockPos pos(final Block b) {
        return new BlockPos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    public UUID countryOf(final BlockPos pos) {
        return this.index.get(pos);
    }

    public int markedCount(final UUID country) {
        final CountryState s = this.data().stateIfPresent(country);
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (final BlockPos p : s.chests()) {
            final Building b = this.data().buildingAt(p.world(), p.x(), p.y(), p.z());
            if (b == null || b.function() != BuildingFunction.GRANARY) {
                n++;
            }
        }
        return n;
    }

    public int containerCount(final UUID country) {
        final CountryState s = this.data().stateIfPresent(country);
        return s == null ? 0 : s.chests().size();
    }

    /** Manual marking (counts towards {@code food.max-marked-chests}). */
    public MarkResult mark(final UUID country, final Block block) {
        final BlockPos p = pos(block);
        if (country.equals(this.index.get(p))) {
            return MarkResult.ALREADY;
        }
        if (this.markedCount(country) >= this.plugin.settings().maxMarkedChests) {
            return MarkResult.LIMIT;
        }
        this.add(country, p);
        return MarkResult.OK;
    }

    public MarkResult unmark(final UUID country, final Block block) {
        final BlockPos p = pos(block);
        if (!country.equals(this.index.get(p))) {
            return MarkResult.NOT_MARKED;
        }
        this.removePos(p);
        return MarkResult.OK;
    }

    /** Registers containers of a finished granary building (no limit). */
    public void addAll(final UUID country, final List<BlockPos> positions) {
        for (final BlockPos p : positions) {
            this.add(country, p);
        }
    }

    private void add(final UUID country, final BlockPos p) {
        final UUID old = this.index.put(p, country);
        if (old != null && !old.equals(country)) {
            final CountryState s = this.data().stateIfPresent(old);
            if (s != null) {
                s.chests().remove(p);
            }
        }
        this.data().state(country).chests().add(p);
        this.data().markDirty();
    }

    public void removePos(final BlockPos p) {
        final UUID country = this.index.remove(p);
        if (country != null) {
            final CountryState s = this.data().stateIfPresent(country);
            if (s != null) {
                s.chests().remove(p);
            }
            this.data().markDirty();
        }
    }

    /** Containers of a removed building stop being granary points. */
    public void buildingRemoved(final Building b) {
        for (final BlockPos p : List.copyOf(this.index.keySet())) {
            if (b.function() == BuildingFunction.GRANARY && b.box().contains(p.world(), p.x(), p.y(), p.z())) {
                this.removePos(p);
            }
        }
    }

    /** Containers in a chunk that changed owner follow the new owner, or are dropped for wilderness. */
    public void chunkChanged(final String world, final int cx, final int cz, final UUID newOwner) {
        for (final BlockPos p : List.copyOf(this.index.keySet())) {
            if (p.world().equals(world) && p.x() >> 4 == cx && p.z() >> 4 == cz) {
                this.removePos(p);
                if (newOwner != null) {
                    this.add(newOwner, p);
                }
            }
        }
    }

    public void countryRemoved(final UUID country) {
        final CountryState s = this.data().stateIfPresent(country);
        if (s != null) {
            for (final BlockPos p : List.copyOf(s.chests())) {
                this.index.remove(p);
            }
        }
        this.data().removeState(country);
    }

    /** Granary country of an inventory's holder (single/double chest, barrel), or null. */
    public UUID countryOf(final Inventory inventory) {
        final InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof DoubleChest dc) {
            for (final InventoryHolder side : new InventoryHolder[] {dc.getLeftSide(false), dc.getRightSide(false)}) {
                if (side instanceof Chest c) {
                    final UUID country = this.index.get(pos(c.getBlock()));
                    if (country != null) {
                        return country;
                    }
                }
            }
            return null;
        }
        if (holder instanceof Chest c) {
            return this.index.get(pos(c.getBlock()));
        }
        if (holder instanceof Barrel b) {
            return this.index.get(pos(b.getBlock()));
        }
        return null;
    }

    /** Converts food items of the inventory into food points of the country (up to the capacity). */
    public void absorb(final Player player, final Inventory inventory, final UUID country) {
        if(this.plugin.supply()!=null && this.plugin.supply().locked(inventory)) return;
        final long capacity = this.capacity(country);
        final CountryState state = this.data().state(country);
        long free = capacity - state.food;
        long added = 0;
        boolean leftover = false;
        final ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            final ItemStack item = contents[i];
            if (item == null || item.isEmpty()) {
                continue;
            }
            final int value = this.plugin.settings().foodValue(item.getType());
            if (value <= 0 || item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                continue; // custom named items (plugin items) are never eaten
            }
            final long canTake = Math.min(item.getAmount(), free / value);
            if (canTake <= 0) {
                leftover = true;
                continue;
            }
            added += canTake * value;
            free -= canTake * value;
            if (canTake >= item.getAmount()) {
                inventory.setItem(i, null);
            } else {
                item.setAmount((int) (item.getAmount() - canTake));
                inventory.setItem(i, item);
                leftover = true;
            }
        }
        if (added > 0) {
            state.food += added;
            this.data().markDirty();
            this.plugin.messages().send(player, "granary-deposit",
                Placeholder.unparsed("amount", Long.toString(added)),
                Placeholder.unparsed("food", Long.toString(state.food)),
                Placeholder.unparsed("capacity", Long.toString(capacity)));
        }
        if (leftover) {
            this.plugin.messages().send(player, "granary-full", Placeholder.unparsed("capacity", Long.toString(capacity)));
        }
    }

    /**
     * The granary takes in the food a hauler (or a farm) put into one of its containers: plain food items become food
     * points up to the capacity, the rest stays in the chest. No player, no message; returns the points added.
     */
    public long absorbStore(final UUID country, final Inventory inventory) {
        if (this.plugin.supply() != null && this.plugin.supply().locked(inventory)) {
            return 0;
        }
        final CountryState state = this.data().state(country);
        long free = this.capacity(country) - state.food;
        long added = 0;
        final ItemStack[] contents = inventory.getStorageContents();
        boolean changed = false;
        for (int i = 0; i < contents.length && free > 0; i++) {
            final ItemStack item = contents[i];
            if (item == null || item.isEmpty() || item.hasItemMeta()) {
                continue;
            }
            final int value = this.plugin.settings().foodValue(item.getType());
            if (value <= 0) {
                continue;
            }
            final long take = Math.min(item.getAmount(), free / value);
            if (take <= 0) {
                continue;
            }
            added += take * value;
            free -= take * value;
            final ItemStack rest = item.clone();
            rest.setAmount((int) (item.getAmount() - take));
            contents[i] = rest.getAmount() <= 0 ? null : rest;
            changed = true;
        }
        if (changed) {
            inventory.setStorageContents(contents);
            state.food += added;
            this.data().markDirty();
        }
        return added;
    }

    /** Inventories of the country's loaded granary containers (marked chests and granary buildings), each once. */
    public List<Inventory> granaryInventories(final UUID country) {
        final java.util.Map<Object, Inventory> out = new java.util.LinkedHashMap<>();
        final CountryState s = this.data().stateIfPresent(country);
        if (s == null) {
            return List.of();
        }
        for (final BlockPos p : s.chests()) {
            final org.bukkit.World w = org.bukkit.Bukkit.getWorld(p.world());
            if (w == null || !w.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
                continue;
            }
            if (w.getBlockAt(p.x(), p.y(), p.z()).getState(false) instanceof org.bukkit.block.Container c
                && (c instanceof Chest || c instanceof Barrel)) {
                final Inventory inv = c.getInventory();
                final Object key = inv.getHolder(false) instanceof DoubleChest dc ? dc.getLocation() : p;
                out.putIfAbsent(key, inv);
            }
        }
        return List.copyOf(out.values());
    }

    /** Positions of all granary containers (for cleanup of missing blocks). */
    public Iterator<BlockPos> positions() {
        return List.copyOf(this.index.keySet()).iterator();
    }

    public static Location center(final BlockPos p, final org.bukkit.World w) {
        return new Location(w, p.x() + 0.5, p.y() + 0.5, p.z() + 0.5);
    }
}
