// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.skarbiec.config.Messages;

/** Registry and transactions of player shops ({@code shops.json}). Main thread only. */
public final class ShopManager {

    private final SkarbiecPlugin plugin;
    private final Path file;
    /** Company shops (with an account) live in their own file: an older Skarbiec never loads them (see Shop#account). */
    private final Path accountFile;
    private final ShopAccounts accounts;
    private final Map<UUID, Shop> shops = new HashMap<>();
    private final Map<String, Shop> byChest = new HashMap<>();
    private final Map<String, Shop> bySign = new HashMap<>();

    public ShopManager(final SkarbiecPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("shops.json");
        this.accountFile = plugin.getDataFolder().toPath().resolve("shops-firmy.json");
        this.accounts = new ShopAccounts(plugin.getLogger());
    }

    /** Policies of company shops (Firmy registers {@code firma} by reflection through SkarbiecPlugin). */
    public ShopAccounts accounts() {
        return this.accounts;
    }

    // ------------------------------------------------------------------ persistence

    public void load() throws IOException {
        this.loadFile(this.file, false);
        this.loadFile(this.accountFile, true);
        this.plugin.getLogger().info("Sklepy graczy: " + this.shops.size() + " (w tym firmowych: "
            + this.shops.values().stream().filter(s -> s.account != null).count() + ")");
    }

    private void loadFile(final Path path, final boolean accountShops) throws IOException {
        final JsonElement root = this.plugin.files().read(path);
        if (root == null) {
            return;
        }
        for (final JsonElement e : root.getAsJsonArray()) {
            try {
                final Shop s = Shop.fromJson(e.getAsJsonObject());
                if (accountShops != (s.account != null)) {
                    this.plugin.getLogger().warning("Sklep " + s.chestKey() + " w niewłaściwym pliku " + path.getFileName() + " — pominięto");
                    continue;
                }
                this.index(s);
            } catch (final RuntimeException ex) {
                this.plugin.getLogger().warning("Pominięto uszkodzony sklep w " + path.getFileName() + ": " + ex.getMessage());
            }
        }
    }

    private void save() {
        final JsonArray personal = new JsonArray();
        final JsonArray company = new JsonArray();
        for (final Shop s : this.shops.values()) {
            (s.account == null ? personal : company).add(s.toJson());
        }
        this.plugin.files().writeAsync(this.file, personal);
        this.plugin.files().writeAsync(this.accountFile, company);
    }

    private void index(final Shop s) {
        this.shops.put(s.id, s);
        this.byChest.put(s.chestKey(), s);
        this.bySign.put(s.signKey(), s);
    }

    // ------------------------------------------------------------------ lookup

    public Shop atSign(final Block b) {
        return this.bySign.get(Shop.key(b));
    }

    /** Shop whose container is this block or the other half of its double chest. */
    public Shop atContainer(final Block b) {
        final Shop direct = this.byChest.get(Shop.key(b));
        if (direct != null) {
            return direct;
        }
        if (b.getType() != org.bukkit.Material.CHEST && b.getType() != org.bukkit.Material.TRAPPED_CHEST) {
            return null;
        }
        final BlockState state = b.getState(false);
        if (state instanceof org.bukkit.block.Chest chest && chest.getInventory() instanceof DoubleChestInventory dci) {
            return this.ofHolder(dci.getHolder(false));
        }
        return null;
    }

    /** Shop of either block, or null. */
    public Shop at(final Block b) {
        final Shop s = this.atSign(b);
        return s != null ? s : this.atContainer(b);
    }

    /** Shop owning the inventory (hoppers), or null. Never creates block state snapshots. */
    public Shop ofInventory(final Inventory inv) {
        if (this.byChest.isEmpty()) {
            return null;
        }
        return this.ofHolder(inv.getHolder(false));
    }

    private Shop ofHolder(final InventoryHolder holder) {
        if (holder instanceof DoubleChest dc) {
            final Shop left = this.ofHolder(dc.getLeftSide(false));
            return left != null ? left : this.ofHolder(dc.getRightSide(false));
        }
        if (holder instanceof BlockState bs) {
            return this.byChest.get(Shop.key(bs.getBlock()));
        }
        return null;
    }

    public List<Shop> ownedBy(final UUID owner) {
        final List<Shop> out = new ArrayList<>();
        for (final Shop s : this.shops.values()) {
            if (s.owner.equals(owner)) {
                out.add(s);
            }
        }
        return out;
    }

    public int count() {
        return this.shops.size();
    }

    /** Shops trading for {@code account} (company shops). */
    public List<Shop> ofAccount(final String account) {
        final List<Shop> out = new ArrayList<>();
        for (final Shop s : this.shops.values()) {
            if (account.equals(s.account)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Name shown to customers: the company of a company shop, else the builder. */
    public static String display(final Shop s) {
        return s.account != null && s.accountLabel != null && !s.accountLabel.isBlank() ? s.accountLabel : s.ownerName;
    }

    /**
     * Makes a shop trade for {@code account} (null = back to the builder's own account). Moves the record between
     * shops.json and shops-firmy.json and rewrites the sign.
     */
    public void assign(final Shop s, final String account, final String label, final String by) {
        s.account = account;
        s.accountLabel = account == null ? null : label;
        this.save();
        this.plugin.store().audit("SHOP_ACCOUNT\t" + s.owner + "\t" + s.ownerName + "\t" + s.chestKey() + " -> "
            + (account == null ? "konto właściciela" : account + " (" + label + ")") + " przez " + by);
        if (s.chunkLoaded()) {
            this.writeSign(s);
        }
    }

    /** Removes every shop of an account (company liquidated); returns how many. */
    public int removeAccount(final String account, final String by) {
        int n = 0;
        for (final Shop s : this.ofAccount(account)) {
            this.remove(s, by);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ create / remove

    public Shop create(final Player owner, final Shop.Type type, final int amount, final long price, final ItemStack item,
                       final Block chest, final Block sign) {
        final Shop s = new Shop(UUID.randomUUID(), owner.getUniqueId(), owner.getName(), type, amount, price, item, chest, sign);
        this.index(s);
        this.save();
        this.plugin.store().audit("SHOP_CREATE\t" + owner.getUniqueId() + "\t" + owner.getName() + "\t" + type + " "
            + amount + "x " + item.getType() + " za " + price + " @ " + s.chestKey());
        return s;
    }

    public void remove(final Shop s, final String by) {
        this.shops.remove(s.id);
        this.byChest.remove(s.chestKey());
        this.bySign.remove(s.signKey());
        this.save();
        this.plugin.store().audit("SHOP_REMOVE\t" + s.owner + "\t" + s.ownerName + "\t" + s.chestKey() + " przez " + by);
    }

    // ------------------------------------------------------------------ sign

    public List<Component> frontLines(final Shop s) {
        final Messages m = this.plugin.messages();
        final TagResolver amount = Placeholder.unparsed("count", Integer.toString(s.amount));
        return List.of(
            m.get("shop.sign.line1"),
            m.get(s.type == Shop.Type.SELL ? "shop.sign.sell" : "shop.sign.buy", amount),
            m.get("shop.sign.item", Placeholder.component("item", s.item.effectiveName())),
            m.get("shop.sign.price", SkarbiecPlugin.amount(s.price)));
    }

    /** Writes the sign text (both sides) and waxes it so nobody can edit it. */
    public void writeSign(final Shop s) {
        final Block b = s.signBlock();
        if (b == null || !(b.getState() instanceof Sign sign)) {
            return;
        }
        final List<Component> front = this.frontLines(s);
        final SignSide f = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            f.line(i, front.get(i));
        }
        final Messages m = this.plugin.messages();
        final SignSide back = sign.getSide(Side.BACK);
        back.line(0, m.get(s.account != null ? "shop.sign.back-firm" : "shop.sign.back1"));
        back.line(1, m.get("shop.sign.back2", Placeholder.unparsed("owner", display(s))));
        back.line(2, Component.empty());
        back.line(3, Component.empty());
        sign.setWaxed(true);
        sign.update(true, false);
    }

    public void refreshSigns() {
        for (final Shop s : this.shops.values()) {
            if (s.chunkLoaded()) {
                this.writeSign(s);
            }
        }
    }

    // ------------------------------------------------------------------ transactions

    private Inventory containerInventory(final Shop s) {
        final Block b = s.chestBlock();
        if (b == null || !(b.getState(false) instanceof Container c)) {
            return null;
        }
        return c.getInventory(); // whole double chest when it is one
    }

    public int stock(final Shop s) {
        final Inventory inv = this.containerInventory(s);
        return inv == null ? 0 : Items.count(inv.getStorageContents(), s.item);
    }

    /** Shows the shop's offer to a player (left click / owner click). */
    public void info(final Player player, final Shop s) {
        final Messages m = this.plugin.messages();
        m.send(player, s.type == Shop.Type.SELL ? "shop.info-sell" : "shop.info-buy",
            Placeholder.unparsed("owner", display(s)), Placeholder.unparsed("count", Integer.toString(s.amount)),
            Placeholder.component("item", s.item.effectiveName().hoverEvent(s.item.asHoverEvent())),
            SkarbiecPlugin.amount(s.price), Placeholder.unparsed("stock", Integer.toString(this.stock(s))));
    }

    /** A non-owner right-clicked the sign: buy from (SELL) or sell to (BUY) the shop. */
    public void use(final Player player, final Shop s) {
        final Inventory chest = this.containerInventory(s);
        final Messages m = this.plugin.messages();
        if (chest == null) {
            m.send(player, "shop.broken");
            return;
        }
        if (s.type == Shop.Type.SELL) {
            this.buyFromShop(player, s, chest, m);
        } else {
            this.sellToShop(player, s, chest, m);
        }
    }

    private TagResolver[] resolvers(final Shop s, final Player other) {
        return new TagResolver[] {
            Placeholder.unparsed("owner", display(s)), Placeholder.unparsed("player", other.getName()),
            Placeholder.unparsed("count", Integer.toString(s.amount)),
            Placeholder.component("item", s.item.effectiveName()), SkarbiecPlugin.amount(s.price)};
    }

    private void buyFromShop(final Player buyer, final Shop s, final Inventory chest, final Messages m) {
        synchronized(this.plugin.ledger()){
            if (s.account != null) {
                this.buyFromAccountShopLocked(buyer, s, chest, m);
            } else {
                buyFromShopLocked(buyer,s,chest,m);
            }
        }
    }

    private Map<String, Object> tradeArgs(final Shop s, final Player p, final String type) {
        final Map<String, Object> a = new HashMap<>();
        a.put("player", p.getUniqueId().toString());
        a.put("playerName", p.getName());
        a.put("type", type);
        a.put("world", s.world);
        a.put("x", s.chestX);
        a.put("y", s.chestY);
        a.put("z", s.chestZ);
        a.put("price", s.price);
        a.put("amount", s.amount);
        a.put("item", s.item.getType().name());
        a.put("shop", s.id.toString());
        return a;
    }

    /** Company shop "Sprzedam": coins go straight to the company account, then the seat country's tax is moved. */
    private void buyFromAccountShopLocked(final Player buyer, final Shop s, final Inventory chest, final Messages m) {
        final Map<String, Object> verdict = this.accounts.ask(s.account, "trade", this.tradeArgs(s, buyer, "SELL"));
        if (!ShopAccounts.allowed(verdict)) {
            m.send(buyer, "shop.firm-closed", Placeholder.unparsed("owner", display(s)), Placeholder.unparsed("reason", ShopAccounts.reason(verdict)));
            return;
        }
        if (Items.count(chest.getStorageContents(), s.item) < s.amount) {
            m.send(buyer, "shop.out-of-stock", this.resolvers(s, buyer));
            this.notifyOwner(s, "shop.owner-out-of-stock", buyer);
            return;
        }
        if (!Items.fits(buyer.getInventory().getStorageContents(), s.item, s.amount)) {
            m.send(buyer, "shop.no-space");
            return;
        }
        final boolean closed = this.plugin.ledger().closedSupply();
        final boolean paid = closed ? this.plugin.coins().takeCoinsTo(buyer, s.price, s.account) : this.plugin.coins().takeCoins(buyer, s.price);
        if (!paid) {
            m.send(buyer, "shop.cannot-afford", SkarbiecPlugin.amount(s.price),
                SkarbiecPlugin.amount("coins", this.plugin.coins().countCoins(buyer)));
            return;
        }
        if (!closed) {
            this.plugin.ledger().deposit(s.account, s.price, "sklep: sprzedaż " + s.amount + "x " + s.item.getType() + " dla " + buyer.getName());
        }
        final int removed = Items.remove(chest, s.item, s.amount);
        if (removed != s.amount) { // impossible on the same tick; undo everything from the company account
            Items.add(chest, s.item, removed);
            final TxResult back = closed ? this.plugin.coins().payFromAccount(buyer, s.account, s.price, "sklep firmy: cofnięcie")
                : this.plugin.ledger().withdraw(s.account, s.price, "sklep firmy: cofnięcie");
            if (!closed && back.ok()) {
                this.plugin.coins().giveCoins(buyer, s.price);
            }
            m.send(buyer, "shop.broken");
            return;
        }
        Items.give(buyer, s.item, s.amount);
        long tax = 0;
        final Object taxAccount = verdict.get("taxAccount");
        final long bp = Math.max(0, Math.min(10_000, ShopAccounts.longOf(verdict, "taxBp")));
        if (taxAccount instanceof String ta && bp > 0 && pl.medivalrpg.skarbiec.ledger.Ledger.validAccount(ta)) {
            tax = Math.multiplyExact(s.price, bp) / 10_000L;
            if (tax > 0) {
                final TxResult r = this.plugin.ledger().transfer(s.account, ta, tax, "Firmy: podatek od sprzedaży w sklepie firmy "
                    + display(s) + " (" + s.amount + "x " + s.item.getType() + ")");
                if (!r.ok()) {
                    this.plugin.getLogger().severe("Sklep firmy " + s.chestKey() + ": podatek " + tax + " md nie przeszedł (" + r + ")");
                    tax = 0;
                }
            }
        }
        this.plugin.store().audit("SHOP_SELL\t" + buyer.getUniqueId() + "\t" + buyer.getName() + "\tkupił " + s.amount + "x "
            + s.item.getType() + " za " + s.price + " (monety) od firmy " + display(s) + " " + s.account + " podatek " + tax + " @ " + s.chestKey());
        buyer.playSound(buyer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        m.send(buyer, "shop.bought", this.resolvers(s, buyer));
        final Map<String, Object> done = this.tradeArgs(s, buyer, "SELL");
        done.put("gross", s.price);
        done.put("tax", tax);
        this.accounts.ask(s.account, "traded", done);
    }

    /** Company shop "Kupię": the company account pays the seller (bank transfer or coins straight from the company). */
    private void sellToAccountShopLocked(final Player seller, final Shop s, final Inventory chest, final Messages m) {
        final Map<String, Object> verdict = this.accounts.ask(s.account, "trade", this.tradeArgs(s, seller, "BUY"));
        if (!ShopAccounts.allowed(verdict)) {
            m.send(seller, "shop.firm-closed", Placeholder.unparsed("owner", display(s)), Placeholder.unparsed("reason", ShopAccounts.reason(verdict)));
            return;
        }
        if (Items.count(seller.getInventory().getStorageContents(), s.item) < s.amount) {
            m.send(seller, "shop.you-lack-items", this.resolvers(s, seller));
            return;
        }
        if (!Items.fits(chest.getStorageContents(), s.item, s.amount)) {
            m.send(seller, "shop.chest-full", this.resolvers(s, seller));
            this.notifyOwner(s, "shop.owner-chest-full", seller);
            return;
        }
        if (this.plugin.ledger().balance(s.account) < s.price) {
            m.send(seller, "shop.owner-broke", this.resolvers(s, seller));
            this.notifyOwner(s, "shop.owner-no-funds", seller);
            return;
        }
        final int removed = Items.remove(seller.getInventory(), s.item, s.amount);
        if (removed != s.amount) {
            Items.give(seller, s.item, removed);
            m.send(seller, "shop.broken");
            return;
        }
        final String reason = "sklep firmy " + display(s) + ": skup " + s.amount + "x " + s.item.getType() + " od " + seller.getName();
        final boolean toAccount = this.plugin.cfg().shopBuyPayoutToAccount;
        final TxResult r;
        if (toAccount) {
            r = this.plugin.ledger().transfer(s.account, SkarbiecPlugin.account(seller), s.price, reason);
        } else if (this.plugin.ledger().closedSupply()) {
            r = this.plugin.coins().payFromAccount(seller, s.account, s.price, reason);
        } else {
            r = this.plugin.ledger().withdraw(s.account, s.price, reason);
            if (r.ok()) {
                this.plugin.coins().giveCoins(seller, s.price);
            }
        }
        if (!r.ok()) {
            Items.give(seller, s.item, s.amount);
            m.send(seller, r == TxResult.INSUFFICIENT_FUNDS ? "shop.owner-broke" : "shop.broken", this.resolvers(s, seller));
            return;
        }
        final Map<Integer, ItemStack> rest = Items.add(chest, s.item, s.amount);
        if (!rest.isEmpty()) {
            Items.giveAll(seller, rest.values());
            this.plugin.getLogger().warning("Sklep firmy " + s.chestKey() + ": skrzynia przepełniona przy skupie");
        }
        this.plugin.store().audit("SHOP_BUY\t" + seller.getUniqueId() + "\t" + seller.getName() + "\tsprzedał " + s.amount
            + "x " + s.item.getType() + " za " + s.price + (toAccount ? " (konto)" : " (monety)") + " do firmy " + display(s)
            + " " + s.account + " @ " + s.chestKey());
        seller.playSound(seller.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        m.send(seller, toAccount ? "shop.sold-account" : "shop.sold", this.resolvers(s, seller));
        final Map<String, Object> done = this.tradeArgs(s, seller, "BUY");
        done.put("gross", s.price);
        done.put("tax", 0L);
        this.accounts.ask(s.account, "traded", done);
    }

    private void buyFromShopLocked(final Player buyer, final Shop s, final Inventory chest, final Messages m) {
        if (Items.count(chest.getStorageContents(), s.item) < s.amount) {
            m.send(buyer, "shop.out-of-stock", this.resolvers(s, buyer));
            this.notifyOwner(s, "shop.owner-out-of-stock", buyer);
            return;
        }
        if (!Items.fits(buyer.getInventory().getStorageContents(), s.item, s.amount)) {
            m.send(buyer, "shop.no-space");
            return;
        }
        if (!this.plugin.coins().takeCoins(buyer, s.price)) {
            m.send(buyer, "shop.cannot-afford", SkarbiecPlugin.amount(s.price),
                SkarbiecPlugin.amount("coins", this.plugin.coins().countCoins(buyer)));
            return;
        }
        final int removed = Items.remove(chest, s.item, s.amount);
        if (removed != s.amount) { // impossible on the same tick; undo everything
            Items.add(chest, s.item, removed);
            this.plugin.coins().giveCoins(buyer, s.price);
            m.send(buyer, "shop.broken");
            return;
        }
        Items.give(buyer, s.item, s.amount);
        final TxResult r = this.plugin.ledger().deposit(SkarbiecApi.playerAccount(s.owner), s.price,
            "sklep: sprzedaż " + s.amount + "x " + s.item.getType() + " dla " + buyer.getName());
        if (!r.ok()) {
            this.plugin.getLogger().severe("Sklep " + s.chestKey() + ": nie udało się zaksięgować " + s.price + " md dla "
                + s.ownerName + " (" + r + ")");
        }
        this.plugin.store().audit("SHOP_SELL\t" + buyer.getUniqueId() + "\t" + buyer.getName() + "\tkupił " + s.amount + "x "
            + s.item.getType() + " za " + s.price + " (monety) od " + s.ownerName + " " + s.owner + " @ " + s.chestKey());
        buyer.playSound(buyer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        m.send(buyer, "shop.bought", this.resolvers(s, buyer));
        this.notifyOwner(s, "shop.owner-sold", buyer);
    }

    private void sellToShop(final Player seller, final Shop s, final Inventory chest, final Messages m) {
        synchronized(this.plugin.ledger()){
            if (s.account != null) {
                this.sellToAccountShopLocked(seller, s, chest, m);
            } else {
                sellToShopLocked(seller,s,chest,m);
            }
        }
    }
    private void sellToShopLocked(final Player seller, final Shop s, final Inventory chest, final Messages m) {
        if (Items.count(seller.getInventory().getStorageContents(), s.item) < s.amount) {
            m.send(seller, "shop.you-lack-items", this.resolvers(s, seller));
            return;
        }
        if (!Items.fits(chest.getStorageContents(), s.item, s.amount)) {
            m.send(seller, "shop.chest-full", this.resolvers(s, seller));
            this.notifyOwner(s, "shop.owner-chest-full", seller);
            return;
        }
        final String ownerAccount = SkarbiecApi.playerAccount(s.owner);
        final String reason = "sklep: skup " + s.amount + "x " + s.item.getType() + " od " + seller.getName();
        final boolean toAccount = this.plugin.cfg().shopBuyPayoutToAccount;
        final TxResult r = toAccount
            ? this.plugin.ledger().transfer(ownerAccount, SkarbiecPlugin.account(seller), s.price, reason)
            : this.plugin.ledger().withdraw(ownerAccount, s.price, reason);
        if (r == TxResult.INSUFFICIENT_FUNDS) {
            m.send(seller, "shop.owner-broke", this.resolvers(s, seller));
            this.notifyOwner(s, "shop.owner-no-funds", seller);
            return;
        }
        if (!r.ok()) {
            m.send(seller, "error.transaction", Placeholder.unparsed("reason", r.name()));
            return;
        }
        final int removed = Items.remove(seller.getInventory(), s.item, s.amount);
        if (removed != s.amount) { // impossible on the same tick; undo
            Items.give(seller, s.item, removed);
            if (toAccount) {
                this.plugin.ledger().transfer(SkarbiecPlugin.account(seller), ownerAccount, s.price, "sklep: cofnięcie");
            } else {
                this.plugin.ledger().deposit(ownerAccount, s.price, "sklep: cofnięcie");
            }
            m.send(seller, "shop.broken");
            return;
        }
        final Map<Integer, ItemStack> rest = Items.add(chest, s.item, s.amount);
        if (!rest.isEmpty()) { // cannot happen (checked above); never destroy items
            Items.giveAll(seller, rest.values());
            this.plugin.getLogger().warning("Sklep " + s.chestKey() + ": skrzynia przepełniona przy skupie");
        }
        if (!toAccount) {
            this.plugin.coins().giveCoins(seller, s.price);
        }
        this.plugin.store().audit("SHOP_BUY\t" + seller.getUniqueId() + "\t" + seller.getName() + "\tsprzedał " + s.amount
            + "x " + s.item.getType() + " za " + s.price + (toAccount ? " (konto)" : " (monety)") + " do " + s.ownerName
            + " " + s.owner + " @ " + s.chestKey());
        seller.playSound(seller.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        m.send(seller, toAccount ? "shop.sold-account" : "shop.sold", this.resolvers(s, seller));
        this.notifyOwner(s, "shop.owner-bought", seller);
    }

    private void notifyOwner(final Shop s, final String key, final Player other) {
        final Player owner = Bukkit.getPlayer(s.owner);
        if (owner != null && owner != other) {
            this.plugin.messages().send(owner, key, this.resolvers(s, other));
        }
    }
}
