// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.shop;

import com.google.gson.JsonObject;
import java.util.Base64;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * A player shop: a container plus a sign. {@link Type#SELL} ("Sprzedam") — the owner sells {@code amount}
 * items from the chest to whoever clicks; {@link Type#BUY} ("Kupię") — the owner buys them, paying from the
 * bank account. {@code item} is the exact template (amount 1); only {@link ItemStack#isSimilar similar}
 * items are traded.
 */
public final class Shop {

    public enum Type { SELL, BUY }

    final UUID id;
    final UUID owner;
    String ownerName;
    final Type type;
    final int amount;
    final long price;
    final ItemStack item;
    final String world;
    final int chestX;
    final int chestY;
    final int chestZ;
    final int signX;
    final int signY;
    final int signZ;
    /**
     * Optional bank account the shop trades for (Firmy: {@code firma:<id>}); null = the owner's own account. Kept only in
     * {@code shops-firmy.json}, so an older Skarbiec never sees such a shop and cannot pay a company's takings to the
     * player who built it.
     */
    String account;
    /** Display name of {@link #account} (company name) for the sign and messages. */
    String accountLabel;

    Shop(final UUID id, final UUID owner, final String ownerName, final Type type, final int amount, final long price,
         final ItemStack item, final Block chest, final Block sign) {
        this(id, owner, ownerName, type, amount, price, item, chest.getWorld().getName(), chest.getX(), chest.getY(),
            chest.getZ(), sign.getX(), sign.getY(), sign.getZ());
    }

    private Shop(final UUID id, final UUID owner, final String ownerName, final Type type, final int amount, final long price,
                 final ItemStack item, final String world, final int chestX, final int chestY, final int chestZ,
                 final int signX, final int signY, final int signZ) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.type = type;
        this.amount = amount;
        this.price = price;
        this.item = item.asOne();
        this.world = world;
        this.chestX = chestX;
        this.chestY = chestY;
        this.chestZ = chestZ;
        this.signX = signX;
        this.signY = signY;
        this.signZ = signZ;
    }

    public UUID owner() {
        return this.owner;
    }

    public String ownerName() {
        return this.ownerName;
    }

    public Type type() {
        return this.type;
    }

    public int amount() {
        return this.amount;
    }

    public long price() {
        return this.price;
    }

    public ItemStack item() {
        return this.item.clone();
    }

    /** Account of a company shop, or null for a personal shop. */
    public String account() {
        return this.account;
    }

    public String accountLabel() {
        return this.accountLabel;
    }

    public String id() {
        return this.id.toString();
    }

    public String world() {
        return this.world;
    }

    public String chestKey() {
        return key(this.world, this.chestX, this.chestY, this.chestZ);
    }

    public String signKey() {
        return key(this.world, this.signX, this.signY, this.signZ);
    }

    public Block chestBlock() {
        final World w = Bukkit.getWorld(this.world);
        return w == null ? null : w.getBlockAt(this.chestX, this.chestY, this.chestZ);
    }

    public Block signBlock() {
        final World w = Bukkit.getWorld(this.world);
        return w == null ? null : w.getBlockAt(this.signX, this.signY, this.signZ);
    }

    public boolean chunkLoaded() {
        final World w = Bukkit.getWorld(this.world);
        return w != null && w.isChunkLoaded(this.signX >> 4, this.signZ >> 4) && w.isChunkLoaded(this.chestX >> 4, this.chestZ >> 4);
    }

    public static String key(final Block b) {
        return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    static String key(final String world, final int x, final int y, final int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("id", this.id.toString());
        o.addProperty("owner", this.owner.toString());
        o.addProperty("ownerName", this.ownerName);
        o.addProperty("type", this.type.name());
        o.addProperty("amount", this.amount);
        o.addProperty("price", this.price);
        o.addProperty("item", Base64.getEncoder().encodeToString(this.item.serializeAsBytes()));
        o.addProperty("world", this.world);
        o.addProperty("chestX", this.chestX);
        o.addProperty("chestY", this.chestY);
        o.addProperty("chestZ", this.chestZ);
        o.addProperty("signX", this.signX);
        o.addProperty("signY", this.signY);
        o.addProperty("signZ", this.signZ);
        if (this.account != null) {
            o.addProperty("account", this.account);
            o.addProperty("accountLabel", this.accountLabel == null ? "" : this.accountLabel);
        }
        return o;
    }

    static Shop fromJson(final JsonObject o) {
        final Shop s = new Shop(UUID.fromString(o.get("id").getAsString()), UUID.fromString(o.get("owner").getAsString()),
            o.get("ownerName").getAsString(), Type.valueOf(o.get("type").getAsString()), o.get("amount").getAsInt(),
            o.get("price").getAsLong(), ItemStack.deserializeBytes(Base64.getDecoder().decode(o.get("item").getAsString())),
            o.get("world").getAsString(), o.get("chestX").getAsInt(), o.get("chestY").getAsInt(), o.get("chestZ").getAsInt(),
            o.get("signX").getAsInt(), o.get("signY").getAsInt(), o.get("signZ").getAsInt());
        if (o.has("account") && !o.get("account").isJsonNull()) {
            s.account = o.get("account").getAsString();
            s.accountLabel = o.has("accountLabel") ? o.get("accountLabel").getAsString() : "";
        }
        return s;
    }
}
