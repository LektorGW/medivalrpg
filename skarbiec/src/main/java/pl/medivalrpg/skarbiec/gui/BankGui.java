// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.gui;

import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.skarbiec.config.Messages;
import pl.medivalrpg.skarbiec.money.Money;

/** Banker NPC window: deposit all coins, withdraw quick amounts or a typed amount. */
public final class BankGui extends Gui {

    private static final int INFO = 4;
    private static final int DEPOSIT = 10;
    private static final int[] QUICK = {12, 13, 14};
    private static final int OTHER = 15;
    private static final int CLOSE = 22;

    private final SkarbiecPlugin plugin;
    private final Player viewer;

    public BankGui(final SkarbiecPlugin plugin, final Player viewer) {
        super(3, plugin.messages().get("gui.bank.title"));
        this.plugin = plugin;
        this.viewer = viewer;
        this.render();
    }

    private void render() {
        final Messages m = this.plugin.messages();
        for (int i = 0; i < this.inventory.getSize(); i++) {
            this.inventory.setItem(i, filler(Material.GRAY_STAINED_GLASS_PANE));
        }
        final long balance = this.plugin.ledger().balance(SkarbiecPlugin.account(this.viewer));
        final long purse = this.plugin.coins().countCoins(this.viewer);
        this.inventory.setItem(INFO, icon(Material.WRITABLE_BOOK, m.item("gui.bank.info-name"),
            m.lines("gui.bank.info-lore", SkarbiecPlugin.amount("balance", balance), SkarbiecPlugin.amount("coins", purse))));
        this.inventory.setItem(DEPOSIT, icon(Material.CHEST, m.item("gui.bank.deposit-name"),
            m.lines("gui.bank.deposit-lore", SkarbiecPlugin.amount("coins", purse))));
        final List<Long> quick = this.plugin.cfg().quickWithdraw;
        for (int i = 0; i < quick.size(); i++) {
            final long a = quick.get(i);
            final Material mat = a < 100 ? Material.IRON_NUGGET : a < 1000 ? Material.GOLD_NUGGET : Material.GOLD_INGOT;
            this.inventory.setItem(QUICK[i], icon(mat, m.item("gui.bank.withdraw-name", SkarbiecPlugin.amount(a)),
                m.lines("gui.bank.withdraw-lore", SkarbiecPlugin.amount(a), SkarbiecPlugin.amount("balance", balance))));
        }
        this.inventory.setItem(OTHER, icon(Material.OAK_SIGN, m.item("gui.bank.other-name"), m.lines("gui.bank.other-lore")));
        this.inventory.setItem(CLOSE, icon(Material.BARRIER, m.item("gui.close"), List.of()));
    }

    @Override
    protected void onClick(final Player player, final int slot, final ClickType click) {
        if (slot == DEPOSIT) {
            depositAll(this.plugin, player);
            this.render();
        } else if (slot == OTHER) {
            player.closeInventory();
            askAmount(this.plugin, player);
        } else if (slot == CLOSE) {
            player.closeInventory();
        } else {
            for (int i = 0; i < QUICK.length && i < this.plugin.cfg().quickWithdraw.size(); i++) {
                if (QUICK[i] == slot) {
                    withdraw(this.plugin, player, this.plugin.cfg().quickWithdraw.get(i));
                    this.render();
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ actions (also used by commands)

    /** Moves every genuine coin from the inventory to the bank account. */
    public static void depositAll(final SkarbiecPlugin plugin, final Player player) {
        final Messages m = plugin.messages();
        final String account = SkarbiecPlugin.account(player);
        final long value = plugin.coins().closedSupply()?plugin.coins().takeAllCoinsTo(player,account):plugin.coins().takeAllCoins(player);
        if (value <= 0) {
            m.send(player, "bank.no-coins");
            return;
        }
        final TxResult r;
        try {
            r = plugin.coins().closedSupply()?TxResult.OK:plugin.ledger().deposit(account, value, "bank: wpłata monet");
        } catch (final RuntimeException e) {
            plugin.coins().giveCoins(player, value); // never lose the coins
            throw e;
        }
        if (!r.ok()) {
            plugin.coins().giveCoins(player, value);
            m.send(player, "error.transaction", Placeholder.unparsed("reason", r.name()));
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.4f);
        m.send(player, "bank.deposited", SkarbiecPlugin.amount(value),
            SkarbiecPlugin.amount("balance", plugin.ledger().balance(account)));
    }

    /** Takes {@code amount} from the bank account and pays it out as coins. */
    public static void withdraw(final SkarbiecPlugin plugin, final Player player, final long amount) {
        final Messages m = plugin.messages();
        if (amount <= 0) {
            m.send(player, "bank.invalid-amount", Placeholder.unparsed("input", Money.format(amount)));
            return;
        }
        final String account = SkarbiecPlugin.account(player);
        if (!plugin.coins().fits(player, amount)) {
            m.send(player, "bank.no-space");
            return;
        }
        final TxResult r = plugin.coins().payFromAccount(player,account,amount,"bank: wypłata monet");
        if (r == TxResult.INSUFFICIENT_FUNDS) {
            m.send(player, "bank.insufficient", SkarbiecPlugin.amount("balance", plugin.ledger().balance(account)));
            return;
        }
        if (!r.ok()) {
            m.send(player, "error.transaction", Placeholder.unparsed("reason", r.name()));
            return;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
        m.send(player, "bank.withdrawn", SkarbiecPlugin.amount(amount),
            SkarbiecPlugin.amount("balance", plugin.ledger().balance(account)));
    }

    /** "Other amount": next chat line is the amount to withdraw. */
    public static void askAmount(final SkarbiecPlugin plugin, final Player player) {
        final int timeout = plugin.cfg().chatInputTimeoutSeconds;
        plugin.messages().send(player, "bank.ask-amount", Placeholder.unparsed("timeout", Integer.toString(timeout)));
        plugin.chatInput().ask(player, timeout, text -> {
            if (text.equalsIgnoreCase("anuluj") || text.equalsIgnoreCase("cancel")) {
                plugin.messages().send(player, "bank.input-cancelled");
                return;
            }
            final Optional<Long> amount = Money.parse(text);
            if (amount.isEmpty() || amount.get() <= 0) {
                plugin.messages().send(player, "bank.invalid-amount", Placeholder.unparsed("input", text));
                return;
            }
            withdraw(plugin, player, amount.get());
        });
    }
}
