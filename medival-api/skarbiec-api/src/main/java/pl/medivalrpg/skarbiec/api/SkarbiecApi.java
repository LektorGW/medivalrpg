// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.api;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Economy of MEDIVALRPG, provided by the Skarbiec plugin and registered in Bukkit's ServicesManager:
 *
 * <pre>{@code
 * SkarbiecApi eco = Bukkit.getServicesManager().load(SkarbiecApi.class); // null when Skarbiec is absent
 * }</pre>
 *
 * <p><b>Money</b> is a {@code long} amount of the smallest unit, the copper coin (miedziak):
 * 1 silver (srebrnik) = {@value #COPPER_PER_SILVER} copper, 1 gold (złota korona) = {@value #COPPER_PER_GOLD} copper.</p>
 *
 * <p><b>Accounts</b> are identified by strings: {@link #playerAccount(UUID)}, {@link #countryAccount(UUID)}
 * or any other {@code "<namespace>:<id>"} chosen by a plugin. Unknown accounts are created on first deposit and
 * have balance 0. Account methods are thread-safe; physical-coin methods (touching inventories) are main thread only.</p>
 */
public interface SkarbiecApi {

    long COPPER_PER_SILVER = 10;
    long COPPER_PER_GOLD = 100;

    static String playerAccount(final UUID player) {
        return "player:" + player;
    }

    static String countryAccount(final UUID countryId) {
        return "country:" + countryId;
    }

    // ---- accounts (bank balances) -------------------------------------------------------------

    long balance(String account);

    default boolean has(final String account, final long amount) {
        return this.balance(account) >= amount;
    }

    /** Adds money created from nothing (e.g. NPC merchant payout). {@code reason} goes to the transaction log. */
    TxResult deposit(String account, long amount, String reason);

    /** Removes money (destroyed, e.g. a fee). Fails with {@link TxResult#INSUFFICIENT_FUNDS} without changing anything. */
    TxResult withdraw(String account, long amount, String reason);

    /** Atomic move between two accounts. */
    TxResult transfer(String from, String to, long amount, String reason);

    // ---- formatting -----------------------------------------------------------------------------

    /** Human readable Polish amount, e.g. {@code "3 zł 4 sr 5 md"} (zero parts omitted, {@code "0 md"} for zero). */
    String format(long amount);

    /** Parses {@code "150"} (copper), {@code "3zl"}, {@code "1zł 5sr"}, {@code "2 sr 3 md"}... Empty when invalid or negative. */
    Optional<Long> parse(String input);

    // ---- physical coins (main thread only) --------------------------------------------------------

    /** Coin stacks worth exactly {@code amount}, using as few coins as possible. */
    ItemStack[] createCoins(long amount);

    /** True only for genuine coins created by Skarbiec (checked by persistent data, never by name or material). */
    boolean isCoin(ItemStack item);

    /** Value of a coin stack (amount × unit value), 0 for non-coins. */
    long coinValue(ItemStack item);

    /** Total value of genuine coins in the player's inventory. */
    long countCoins(Player player);

    /** Takes coins worth {@code amount} from the inventory, giving change in smaller coins. False (nothing taken) if not enough. */
    boolean takeCoins(Player player, long amount);

    /** Gives coins worth {@code amount}; what does not fit is dropped at the player's feet. */
    void giveCoins(Player player, long amount);
}
