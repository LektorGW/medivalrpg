// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.coin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.skarbiec.config.Messages;
import pl.medivalrpg.skarbiec.config.SkarbiecConfig;
import pl.medivalrpg.skarbiec.money.CoinMath;
import pl.medivalrpg.skarbiec.money.Denomination;
import pl.medivalrpg.skarbiec.ledger.Ledger;
import pl.medivalrpg.skarbiec.ledger.WorldMoney;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Physical coins. A coin is genuine only when its persistent data holds {@code skarbiec:coin = copper|silver|gold}
 * (never judged by name or material). All methods touching inventories are main-thread only.
 */
public final class CoinService {

    /** Safety cap for {@link #createCoins}: more stacks than this is certainly a bug, not a payment. */
    private static final int MAX_STACKS = 10_000;
    private static final int MAX_CONTAINER_DEPTH = 16;
    private static final int MAX_CONTAINER_ITEMS = 1024;

    private final NamespacedKey key;
    private final Ledger ledger;
    private volatile Map<Denomination, ItemStack> templates = Map.of();

    public CoinService(final Plugin plugin) {
        this(plugin,null);
    }
    public CoinService(final Plugin plugin,final Ledger ledger) {
        this.key = new NamespacedKey(plugin, "coin");
        this.ledger=ledger;
    }
    public boolean closedSupply(){return ledger!=null && ledger.closedSupply();}

    public NamespacedKey key() {
        return this.key;
    }

    public static Material material(final Denomination d) {
        return switch (d) {
            case COPPER -> Material.COPPER_NUGGET;
            case SILVER -> Material.IRON_NUGGET;
            case GOLD -> Material.GOLD_NUGGET;
        };
    }

    /** Rebuilds the coin look (name, lore, glint) from the config. Existing coins are refreshed by {@link #normalize}. */
    public void reload(final SkarbiecConfig cfg) {
        final Map<Denomination, ItemStack> map = new EnumMap<>(Denomination.class);
        for (final Denomination d : Denomination.values()) {
            final ItemStack item = ItemStack.of(material(d));
            item.editMeta(meta -> {
                meta.itemName(Messages.mini().deserialize(cfg.coinNames.get(d)));
                final List<Component> lore = new ArrayList<>();
                for (final String line : cfg.coinLore.get(d)) {
                    lore.add(Messages.mini().deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                }
                meta.lore(lore);
                if (cfg.coinGlint) {
                    meta.setEnchantmentGlintOverride(true);
                }
                meta.getPersistentDataContainer().set(this.key, PersistentDataType.STRING, d.id());
            });
            map.put(d, item);
        }
        this.templates = Map.copyOf(map);
    }

    public ItemStack create(final Denomination d, final int amount) {
        return this.templates.get(d).asQuantity(amount);
    }

    /** Denomination of a genuine coin, {@code null} for anything else. */
    public Denomination denomination(final ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        final String id = item.getPersistentDataContainer().get(this.key, PersistentDataType.STRING);
        return Denomination.byId(id);
    }

    public boolean isCoin(final ItemStack item) {
        return this.denomination(item) != null;
    }

    /** Protective scan only: containers are never themselves currency. Unscannable depth/size is protected. */
    public boolean containsCoins(final ItemStack item) {
        return containsCoins(item, 0, new int[] {MAX_CONTAINER_ITEMS});
    }

    private boolean containsCoins(final ItemStack item, final int depth, final int[] remaining) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        if (remaining[0]-- <= 0 || depth >= MAX_CONTAINER_DEPTH || isCoin(item)) {
            return true; // Fail closed rather than permit copying/destruction of an unchecked subtree.
        }
        final var meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta blockMeta
            && blockMeta.hasBlockState()
            && blockMeta.getBlockState() instanceof org.bukkit.inventory.InventoryHolder holder) {
            for (final ItemStack nested : holder.getInventory().getContents()) {
                if (containsCoins(nested, depth + 1, remaining)) {
                    return true;
                }
            }
        }
        if (meta instanceof org.bukkit.inventory.meta.BundleMeta bundle) {
            for (final ItemStack nested : bundle.getItems()) {
                if (containsCoins(nested, depth + 1, remaining)) {
                    return true;
                }
            }
        }
        return false;
    }

    public long coinValue(final ItemStack item) {
        final Denomination d = this.denomination(item);
        return d == null ? 0 : d.value() * item.getAmount();
    }

    public ItemStack[] createCoins(final long amount) {
        return createCoins(amount,"API: emisja monet z budżetu operacyjnego");
    }
    private ItemStack[] createCoins(final long amount,final String reason) {
        checkMain();
        ItemStack[] result=rawCoins(amount);
        if(amount>0 && closedSupply() && !ledger.issueCash(WorldMoney.PAYMENTS,amount,reason).ok())
            throw new IllegalStateException("Brak środków w budżecie operacyjnym Skarbu Świata");
        return result;
    }
    private ItemStack[] rawCoins(final long amount) {
        if (amount <= 0) {
            return new ItemStack[0];
        }
        final long[] split = CoinMath.split(amount);
        long stacks = 0;
        for (final Denomination d : Denomination.values()) {
            stacks += (split[d.ordinal()] + 63) / 64;
        }
        if (stacks > MAX_STACKS) {
            throw new IllegalArgumentException("Kwota " + amount + " to zbyt wiele monet (" + stacks + " stosów)");
        }
        final List<ItemStack> out = new ArrayList<>();
        for (int i = Denomination.values().length - 1; i >= 0; i--) { // gold first
            final Denomination d = Denomination.values()[i];
            final ItemStack template = this.templates.get(d);
            final int max = template.getMaxStackSize();
            long left = split[i];
            while (left > 0) {
                final int n = (int) Math.min(max, left);
                out.add(template.asQuantity(n));
                left -= n;
            }
        }
        return out.toArray(ItemStack[]::new);
    }

    public long countCoins(final Player player) {
        checkMain();
        long total = 0;
        for (final ItemStack item : player.getInventory().getContents()) {
            total += this.coinValue(item);
        }
        return total;
    }

    /** Coin counts in the inventory: index 0 copper, 1 silver, 2 gold. */
    public long[] countByDenomination(final Player player) {
        checkMain();
        final long[] counts = new long[3];
        for (final ItemStack item : player.getInventory().getContents()) {
            final Denomination d = this.denomination(item);
            if (d != null) {
                counts[d.ordinal()] += item.getAmount();
            }
        }
        return counts;
    }

    public boolean takeCoins(final Player player, final long amount) {
        return takeCoinsTo(player,amount,WorldMoney.PAYMENTS);
    }
    public boolean takeCoinsTo(final Player player,final long amount,final String account) {
        checkMain();
        if (amount < 0) {
            return false;
        }
        if (amount == 0) {
            return true;
        }
        final PlayerInventory inv = player.getInventory();
        final ItemStack[] contents = inv.getContents();
        final Denomination[] denoms = new Denomination[contents.length];
        final int[] counts = new int[contents.length];
        for (int i = 0; i < contents.length; i++) {
            denoms[i] = this.denomination(contents[i]);
            counts[i] = denoms[i] == null ? 0 : contents[i].getAmount();
        }
        final long[] have = CoinMath.count(denoms, counts);
        final CoinMath.Payment payment = CoinMath.plan(have[0], have[1], have[2], amount);
        if (payment == null) {
            return false;
        }
        final int[] after = CoinMath.removeFromSlots(denoms, counts, payment);
        // The change is constructed before any mutation. Only the net payment leaves cash circulation.
        final ItemStack[] change=rawCoins(payment.change());
        if(closedSupply() && !ledger.redeemCash(account,amount,"cash "+player.getUniqueId()+": przyjęcie monet").ok())return false;
        for (int i = 0; i < contents.length; i++) {
            if (denoms[i] == null || after[i] == counts[i]) {
                continue;
            }
            inv.setItem(i, after[i] == 0 ? null : contents[i].asQuantity(after[i]));
        }
        if (payment.change() > 0) {
            this.givePrepared(player, change);
        }
        return true;
    }

    /** Removes every genuine coin from the inventory and returns their value (bank "deposit all"). */
    public long takeAllCoins(final Player player) {
        return takeAllCoinsTo(player,WorldMoney.PAYMENTS);
    }
    public long takeAllCoinsTo(final Player player,final String account) {
        checkMain();
        final PlayerInventory inv = player.getInventory();
        final ItemStack[] contents = inv.getContents();
        long total = 0;
        for(ItemStack item:contents)total=Math.addExact(total,this.coinValue(item));
        if(total>0 && closedSupply() && !ledger.redeemCash(account,total,"cash "+player.getUniqueId()+": wpłata fizycznych monet").ok())throw new IllegalStateException("Monety nie mają pokrycia w księdze obiegu");
        for (int i = 0; i < contents.length; i++) {
            final long v = this.coinValue(contents[i]);
            if (v > 0) {
                inv.setItem(i, null);
            }
        }
        return total;
    }

    public void giveCoins(final Player player, final long amount) {
        checkMain();
        if (amount <= 0) {
            return;
        }
        givePrepared(player,createCoins(amount,"cash "+player.getUniqueId()+": wypłata monet z budżetu"));
    }
    /** Atomic bank/budget-to-cash conversion, without passing through a spendable intermediate pool. */
    public TxResult payFromAccount(final Player player,final String account,final long amount,final String reason) {
        checkMain();
        if(amount<=0)return TxResult.INVALID_AMOUNT;
        ItemStack[] stacks=rawCoins(amount);
        TxResult result=closedSupply()?ledger.issueCash(account,amount,"cash "+player.getUniqueId()+": "+reason):
            WorldMoney.PAYMENTS.equals(account)?TxResult.OK:ledger.withdraw(account,amount,reason);
        if(result.ok())givePrepared(player,stacks);
        return result;
    }
    private void givePrepared(final Player player,final ItemStack[] stacks) {
        final Map<Integer, ItemStack> rest = player.getInventory().addItem(stacks);
        if (!rest.isEmpty()) {
            final Location at = player.getLocation();
            for (final ItemStack item : rest.values()) {
                at.getWorld().dropItem(at, item, drop -> {
                    drop.setOwner(player.getUniqueId()); // nobody else can pick up the overflow
                    drop.setPickupDelay(0);
                });
            }
        }
    }

    /** Whether coins worth {@code amount} fit into the player's storage slots without dropping. */
    public boolean fits(final Player player, final long amount) {
        checkMain();
        final ItemStack[] storage = player.getInventory().getStorageContents();
        final Denomination[] denoms = new Denomination[storage.length];
        final int[] counts = new int[storage.length];
        for (int i = 0; i < storage.length; i++) {
            final ItemStack item = storage[i];
            if (item == null || item.isEmpty()) {
                continue;
            }
            final Denomination d = this.denomination(item);
            // Only coins identical to the current template stack with new coins.
            denoms[i] = d != null && item.isSimilar(this.templates.get(d)) ? d : null;
            counts[i] = item.getAmount();
        }
        return CoinMath.fits(denoms, counts, 64, amount);
    }

    /** Replaces coins with an outdated look (config changed) by current ones, so all coins stack again. */
    public void normalize(final Player player) {
        checkMain();
        final PlayerInventory inv = player.getInventory();
        final ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            final Denomination d = this.denomination(contents[i]);
            if (d != null && !contents[i].isSimilar(this.templates.get(d))) {
                inv.setItem(i, this.create(d, contents[i].getAmount()));
            }
        }
    }

    private static void checkMain() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Operacje na monetach (ekwipunek) tylko z głównego wątku serwera");
        }
    }
}
