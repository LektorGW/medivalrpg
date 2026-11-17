// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec;

import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.skarbiec.coin.CoinService;
import pl.medivalrpg.skarbiec.ledger.Ledger;
import pl.medivalrpg.skarbiec.money.Money;

/**
 * {@link SkarbiecApi} registered in the ServicesManager. Account methods go straight to the thread-safe
 * {@link Ledger}; coin methods to {@link CoinService} (main thread only, enforced).
 */
final class SkarbiecApiImpl implements SkarbiecApi {

    private final Ledger ledger;
    private final CoinService coins;

    SkarbiecApiImpl(final Ledger ledger, final CoinService coins) {
        this.ledger = ledger;
        this.coins = coins;
    }

    @Override
    public long balance(final String account) {
        return this.ledger.balance(account);
    }

    @Override
    public boolean has(final String account, final long amount) {
        return this.ledger.balance(account) >= amount;
    }

    @Override
    public TxResult deposit(final String account, final long amount, final String reason) {
        return this.ledger.deposit(account, amount, reason);
    }

    @Override
    public TxResult withdraw(final String account, final long amount, final String reason) {
        return this.ledger.withdraw(account, amount, reason);
    }

    @Override
    public TxResult transfer(final String from, final String to, final long amount, final String reason) {
        return this.ledger.transfer(from, to, amount, reason);
    }

    @Override
    public String format(final long amount) {
        return Money.format(amount);
    }

    @Override
    public Optional<Long> parse(final String input) {
        return Money.parse(input);
    }

    @Override
    public ItemStack[] createCoins(final long amount) {
        return this.coins.createCoins(amount);
    }

    @Override
    public boolean isCoin(final ItemStack item) {
        return this.coins.isCoin(item);
    }

    @Override
    public long coinValue(final ItemStack item) {
        return this.coins.coinValue(item);
    }

    @Override
    public long countCoins(final Player player) {
        return this.coins.countCoins(player);
    }

    @Override
    public boolean takeCoins(final Player player, final long amount) {
        return this.coins.takeCoins(player, amount);
    }

    @Override
    public void giveCoins(final Player player, final long amount) {
        this.coins.giveCoins(player, amount);
    }
}
