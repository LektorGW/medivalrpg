// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.shop;

import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Inventory helpers working on storage slots only and on items {@link ItemStack#isSimilar similar} to a template. */
public final class Items {

    private Items() {
    }

    public static int count(final ItemStack[] contents, final ItemStack template) {
        int n = 0;
        for (final ItemStack item : contents) {
            if (item != null && item.isSimilar(template)) {
                n += item.getAmount();
            }
        }
        return n;
    }

    /** Whether {@code amount} items like {@code template} fit into the slots. */
    public static boolean fits(final ItemStack[] contents, final ItemStack template, final int amount) {
        final int max = template.getMaxStackSize();
        long room = 0;
        for (final ItemStack item : contents) {
            if (item == null || item.isEmpty()) {
                room += max;
            } else if (item.isSimilar(template)) {
                room += Math.max(0, max - item.getAmount());
            }
            if (room >= amount) {
                return true;
            }
        }
        return room >= amount;
    }

    /** Removes up to {@code amount} similar items from the storage slots (last slots first); returns how many. */
    public static int remove(final Inventory inv, final ItemStack template, final int amount) {
        final int size = inv.getStorageContents().length;
        int left = amount;
        for (int i = size - 1; i >= 0 && left > 0; i--) {
            final ItemStack item = inv.getItem(i);
            if (item == null || !item.isSimilar(template)) {
                continue;
            }
            final int take = Math.min(left, item.getAmount());
            left -= take;
            inv.setItem(i, take == item.getAmount() ? null : item.asQuantity(item.getAmount() - take));
        }
        return amount - left;
    }

    /** Adds {@code amount} items like the template; returns what did not fit (as stacks). */
    public static Map<Integer, ItemStack> add(final Inventory inv, final ItemStack template, final int amount) {
        return inv.addItem(stacks(template, amount));
    }

    /** Gives items to a player; overflow is dropped at their feet, pickable only by them. */
    public static void give(final Player player, final ItemStack template, final int amount) {
        if (amount <= 0) {
            return;
        }
        drop(player, player.getInventory().addItem(stacks(template, amount)));
    }

    public static void giveAll(final Player player, final Iterable<ItemStack> items) {
        for (final ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                drop(player, player.getInventory().addItem(item));
            }
        }
    }

    private static void drop(final Player player, final Map<Integer, ItemStack> rest) {
        if (rest.isEmpty()) {
            return;
        }
        final Location at = player.getLocation();
        for (final ItemStack item : rest.values()) {
            at.getWorld().dropItem(at, item, drop -> {
                drop.setOwner(player.getUniqueId());
                drop.setPickupDelay(0);
            });
        }
    }

    public static ItemStack[] stacks(final ItemStack template, final int amount) {
        final int max = template.getMaxStackSize();
        final int n = (amount + max - 1) / max;
        final ItemStack[] out = new ItemStack[Math.max(0, n)];
        int left = amount;
        for (int i = 0; i < out.length; i++) {
            final int k = Math.min(max, left);
            out[i] = template.asQuantity(k);
            left -= k;
        }
        return out;
    }
}
