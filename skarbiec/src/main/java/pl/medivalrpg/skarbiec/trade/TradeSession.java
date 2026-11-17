// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.config.Messages;
import pl.medivalrpg.skarbiec.gui.Gui;
import pl.medivalrpg.skarbiec.shop.Items;

/**
 * One trade between two players: a shared 6-row window. Each side has 8 item slots (2×4) and a coin offer
 * (validated against the coins actually carried when the trade completes). Both must confirm; a countdown
 * follows and ANY change cancels both confirmations. Items placed in the window are held in escrow and go back
 * to their owner whenever the trade ends without completing (close, disconnect, death, distance, shutdown).
 *
 * <p>Anti-dupe rules: {@link #finished} is set before anything is handed out, every click/drag is cancelled
 * afterwards, slots are emptied before their items are given away, and only plain pickup/place/swap clicks on
 * one's own slots are allowed (no shift-click, double-click collect, number keys, drops or drags into the
 * window).</p>
 */
public final class TradeSession extends Gui {

    static final int[] A_ITEMS = {0, 1, 2, 3, 9, 10, 11, 12};
    static final int[] B_ITEMS = {5, 6, 7, 8, 14, 15, 16, 17};
    private static final int[] SEPARATORS = {4, 13, 22, 31};
    private static final int STATUS = 40;
    private static final int CANCEL = 49;
    private static final long[] STEPS = {1, 10, 100, 1000};

    private static final Set<InventoryAction> SLOT_ACTIONS = Set.of(
        InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME,
        InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME, InventoryAction.SWAP_WITH_CURSOR);

    /** Layout of one side: plus buttons, minus buttons, head, coin display, "all coins", "clear", confirm. */
    private record Side(int[] items, int[] plus, int[] minus, int head, int coins, int all, int clear, int[] confirm) {
    }

    private static final Side LEFT = new Side(A_ITEMS, new int[] {18, 19, 20, 21}, new int[] {27, 28, 29, 30},
        36, 37, 38, 39, new int[] {45, 46, 47, 48});
    private static final Side RIGHT = new Side(B_ITEMS, new int[] {26, 25, 24, 23}, new int[] {35, 34, 33, 32},
        44, 43, 42, 41, new int[] {53, 52, 51, 50});

    private final SkarbiecPlugin plugin;
    private final TradeManager manager;
    final Player a;
    final Player b;
    private long offerA;
    private long offerB;
    private boolean confirmA;
    private boolean confirmB;
    private boolean finished;
    private BukkitTask countdown;
    private int secondsLeft;
    private boolean renderQueued;

    TradeSession(final SkarbiecPlugin plugin, final TradeManager manager, final Player a, final Player b) {
        super(6, plugin.messages().get("gui.trade.title", Placeholder.unparsed("first", a.getName()), Placeholder.unparsed("second", b.getName())));
        this.plugin = plugin;
        this.manager = manager;
        this.a = a;
        this.b = b;
        this.render();
    }

    boolean finished() {
        return this.finished;
    }

    Player other(final Player p) {
        return p.getUniqueId().equals(this.a.getUniqueId()) ? this.b : this.a;
    }

    private boolean isA(final Player p) {
        return p.getUniqueId().equals(this.a.getUniqueId());
    }

    private boolean participant(final Player p) {
        return p.getUniqueId().equals(this.a.getUniqueId()) || p.getUniqueId().equals(this.b.getUniqueId());
    }

    void openBoth() {
        this.a.openInventory(this.inventory);
        this.b.openInventory(this.inventory);
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        final Messages m = this.plugin.messages();
        for (final int s : SEPARATORS) {
            this.inventory.setItem(s, filler(Material.BLACK_STAINED_GLASS_PANE));
        }
        this.renderSide(LEFT, this.a, this.offerA, this.confirmA);
        this.renderSide(RIGHT, this.b, this.offerB, this.confirmB);
        if (this.countdown != null) {
            final ItemStack clock = icon(Material.CLOCK, m.item("gui.trade.countdown",
                Placeholder.unparsed("seconds", Integer.toString(this.secondsLeft))), List.of());
            clock.setAmount(Math.max(1, this.secondsLeft));
            this.inventory.setItem(STATUS, clock);
        } else {
            this.inventory.setItem(STATUS, icon(Material.PAPER, m.item("gui.trade.status-name"), m.lines("gui.trade.status-lore",
                Placeholder.unparsed("range", Integer.toString((int) this.plugin.cfg().tradeRange)))));
        }
        this.inventory.setItem(CANCEL, icon(Material.BARRIER, m.item("gui.trade.cancel"), List.of()));
    }

    private void renderSide(final Side side, final Player owner, final long offer, final boolean confirmed) {
        final Messages m = this.plugin.messages();
        final ItemStack head = icon(Material.PLAYER_HEAD, m.item("gui.trade.head", Placeholder.unparsed("player", owner.getName())), List.of());
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        this.inventory.setItem(side.head(), head);
        for (int i = 0; i < 4; i++) {
            final var step = SkarbiecPlugin.amount(STEPS[i]);
            this.inventory.setItem(side.plus()[i], icon(i == 0 ? Material.COPPER_NUGGET : i == 1 ? Material.IRON_NUGGET : Material.GOLD_NUGGET,
                m.item("gui.trade.plus", step), m.lines("gui.trade.button-lore", Placeholder.unparsed("player", owner.getName()))));
            this.inventory.setItem(side.minus()[i], icon(Material.RED_STAINED_GLASS_PANE, m.item("gui.trade.minus", step),
                m.lines("gui.trade.button-lore", Placeholder.unparsed("player", owner.getName()))));
        }
        this.inventory.setItem(side.coins(), icon(Material.GOLD_NUGGET, m.item("gui.trade.offer", SkarbiecPlugin.amount(offer)),
            m.lines("gui.trade.offer-lore", Placeholder.unparsed("player", owner.getName()))));
        this.inventory.setItem(side.all(), icon(Material.CHEST, m.item("gui.trade.all-coins"), List.of()));
        this.inventory.setItem(side.clear(), icon(Material.BUCKET, m.item("gui.trade.clear-coins"), List.of()));
        final ItemStack confirm = icon(confirmed ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
            m.item(confirmed ? "gui.trade.confirmed" : "gui.trade.not-confirmed", Placeholder.unparsed("player", owner.getName())),
            m.lines("gui.trade.confirm-lore"));
        for (final int s : side.confirm()) {
            this.inventory.setItem(s, confirm);
        }
    }

    private void queueRender() {
        if (this.renderQueued) {
            return;
        }
        this.renderQueued = true;
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            this.renderQueued = false;
            if (!this.finished) {
                this.render();
            }
        });
    }

    // ------------------------------------------------------------------ clicks

    private static boolean contains(final int[] arr, final int v) {
        for (final int x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handleClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || this.finished || !this.participant(player)) {
            event.setCancelled(true);
            return;
        }
        final int raw = event.getRawSlot();
        final InventoryAction action = event.getAction();
        if (raw < 0 || raw >= this.inventory.getSize()) {
            // Own inventory (or outside the window): ordinary moves only. Shift-click, double-click collect
            // (could pull items out of the window), number keys and drops are blocked.
            if (raw < 0 || !(SLOT_ACTIONS.contains(action) || action == InventoryAction.NOTHING)) {
                event.setCancelled(true);
            }
            return;
        }
        final Side side = this.isA(player) ? LEFT : RIGHT;
        if (contains(side.items(), raw)) {
            if (!SLOT_ACTIONS.contains(action) || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) {
                event.setCancelled(true);
                return;
            }
            if (this.plugin.coins().isCoin(event.getCursor()) && !action.name().startsWith("PICKUP")) {
                // Coins are offered with the buttons, not as items (keeps the coin offer the only coin channel).
                event.setCancelled(true);
                this.plugin.messages().send(player, "trade.coins-use-buttons");
                return;
            }
            this.changed();
            return; // allowed: Bukkit applies the move after the event
        }
        event.setCancelled(true);
        this.onClick(player, raw, event.getClick());
    }

    @Override
    public void handleDrag(final InventoryDragEvent event) {
        for (final int raw : event.getRawSlots()) {
            if (raw < this.inventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
        // drags only inside the player's own inventory are harmless
    }

    @Override
    protected void onClick(final Player player, final int slot, final ClickType click) {
        if (this.finished) {
            return;
        }
        if (slot == CANCEL) {
            this.manager.cancel(this, "trade.cancelled-by", player);
            return;
        }
        final boolean isA = this.isA(player);
        final Side side = isA ? LEFT : RIGHT;
        for (int i = 0; i < 4; i++) {
            if (side.plus()[i] == slot) {
                this.setOffer(player, isA, (isA ? this.offerA : this.offerB) + STEPS[i]);
                return;
            }
            if (side.minus()[i] == slot) {
                this.setOffer(player, isA, Math.max(0, (isA ? this.offerA : this.offerB) - STEPS[i]));
                return;
            }
        }
        if (slot == side.all()) {
            this.setOffer(player, isA, this.plugin.coins().countCoins(player));
        } else if (slot == side.clear()) {
            this.setOffer(player, isA, 0);
        } else if (contains(side.confirm(), slot)) {
            this.toggleConfirm(player, isA);
        }
    }

    private void setOffer(final Player player, final boolean isA, final long wanted) {
        final long have = this.plugin.coins().countCoins(player);
        long offer = wanted;
        if (offer > have) {
            offer = have;
            this.plugin.messages().send(player, "trade.not-that-many-coins", SkarbiecPlugin.amount("coins", have));
        }
        if (isA) {
            if (offer == this.offerA) {
                return;
            }
            this.offerA = offer;
        } else {
            if (offer == this.offerB) {
                return;
            }
            this.offerB = offer;
        }
        this.changed();
    }

    private void toggleConfirm(final Player player, final boolean isA) {
        final boolean now = !(isA ? this.confirmA : this.confirmB);
        if (now && this.plugin.coins().countCoins(player) < (isA ? this.offerA : this.offerB)) {
            this.plugin.messages().send(player, "trade.not-that-many-coins",
                SkarbiecPlugin.amount("coins", this.plugin.coins().countCoins(player)));
            return;
        }
        if (isA) {
            this.confirmA = now;
        } else {
            this.confirmB = now;
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, now ? 1.4f : 0.8f);
        if (this.confirmA && this.confirmB) {
            this.startCountdown();
        } else {
            this.stopCountdown();
        }
        this.render();
    }

    /** Any change of the offer: both confirmations are withdrawn and a running countdown stops. */
    private void changed() {
        final boolean wasArmed = this.confirmA || this.confirmB;
        this.confirmA = false;
        this.confirmB = false;
        if (this.countdown != null) {
            this.stopCountdown();
            this.plugin.messages().send(this.a, "trade.changed");
            this.plugin.messages().send(this.b, "trade.changed");
        } else if (wasArmed) {
            this.plugin.messages().send(this.a, "trade.changed");
            this.plugin.messages().send(this.b, "trade.changed");
        }
        this.queueRender();
    }

    private void startCountdown() {
        this.stopCountdown();
        this.secondsLeft = this.plugin.cfg().tradeCountdownSeconds;
        this.countdown = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (this.finished) {
                this.stopCountdown();
                return;
            }
            if (this.secondsLeft <= 0) {
                this.stopCountdown();
                this.manager.complete(this);
                return;
            }
            this.render();
            this.a.playSound(this.a.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.2f);
            this.b.playSound(this.b.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.2f);
            this.secondsLeft--;
        }, 0L, 20L);
    }

    private void stopCountdown() {
        if (this.countdown != null) {
            this.countdown.cancel();
            this.countdown = null;
        }
    }

    @Override
    public void onClose(final Player player) {
        if (!this.finished && this.participant(player)) {
            this.manager.cancel(this, "trade.closed-by", player);
        }
    }

    // ------------------------------------------------------------------ settlement (TradeManager)

    long offerA() {
        return this.offerA;
    }

    long offerB() {
        return this.offerB;
    }

    void unconfirm() {
        this.confirmA = false;
        this.confirmB = false;
        this.stopCountdown();
        this.render();
    }

    /** Marks the trade over; after this no click is accepted and no item can enter the window. */
    void finish() {
        this.finished = true;
        this.stopCountdown();
    }

    /** Empties one side's slots and returns their items (escrow release). */
    List<ItemStack> takeItems(final boolean sideA) {
        final List<ItemStack> out = new ArrayList<>();
        for (final int s : sideA ? A_ITEMS : B_ITEMS) {
            final ItemStack item = this.inventory.getItem(s);
            if (item != null && !item.isEmpty()) {
                out.add(item);
            }
            this.inventory.setItem(s, null);
        }
        return out;
    }

    boolean viewing(final Player p) {
        return p.isOnline() && p.getOpenInventory().getTopInventory() == this.inventory;
    }

    /** Closes the window for every viewer (next tick when called from inside an inventory event). */
    void closeAll(final boolean later) {
        final Runnable close = () -> {
            for (final Player p : List.of(this.a, this.b)) {
                if (this.viewing(p)) {
                    p.closeInventory();
                }
            }
        };
        if (later) {
            Bukkit.getScheduler().runTask(this.plugin, close);
        } else {
            close.run();
        }
    }

    static void giveBack(final Player p, final List<ItemStack> items) {
        Items.giveAll(p, items);
    }
}
