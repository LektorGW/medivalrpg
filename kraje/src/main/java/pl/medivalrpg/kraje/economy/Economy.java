// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.KrajePlugin;

/**
 * Money operations used by Kraje. Backed by Skarbiec when it is installed ({@code SkarbiecEconomy}, loaded
 * reflectively so that Kraje never touches Skarbiec classes otherwise); {@link #NONE} when it is absent or
 * {@code economy.enabled: false} — then every cost is 0, there are no taxes and no treasury.
 * Amounts are copper ({@code long}).
 */
public interface Economy {

    /** True when money operations really happen. */
    boolean enabled();

    long balance(String account);

    boolean deposit(String account, long amount, String reason);

    boolean withdraw(String account, long amount, String reason);

    boolean transfer(String from, String to, long amount, String reason);

    String format(long amount);

    Optional<Long> parse(String input);

    /** Main thread. Value of genuine coins in the inventory. */
    long countCoins(Player player);

    /** Main thread. Takes coins worth {@code amount} (with change); false when not enough. */
    boolean takeCoins(Player player, long amount);

    static String playerAccount(final UUID player) {
        return "player:" + player;
    }

    Economy NONE = new Economy() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public long balance(final String account) {
            return 0;
        }

        @Override
        public boolean deposit(final String account, final long amount, final String reason) {
            return false;
        }

        @Override
        public boolean withdraw(final String account, final long amount, final String reason) {
            return false;
        }

        @Override
        public boolean transfer(final String from, final String to, final long amount, final String reason) {
            return false;
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
        public long countCoins(final Player player) {
            return 0;
        }

        @Override
        public boolean takeCoins(final Player player, final long amount) {
            return false;
        }
    };

    /** Skarbiec-backed economy if Skarbiec is enabled and {@code economy.enabled}, else {@link #NONE}. */
    static Economy create(final KrajePlugin plugin) {
        if (!plugin.cfg().economyEnabled || !Bukkit.getPluginManager().isPluginEnabled("Skarbiec")) {
            plugin.getLogger().info("Skarbiec nieobecny albo economy.enabled: false — funkcje ekonomiczne wyłączone.");
            return NONE;
        }
        try {
            final Class<?> type = Class.forName("pl.medivalrpg.kraje.economy.SkarbiecEconomy");
            final Economy economy = (Economy) type.getConstructor().newInstance();
            plugin.getLogger().info("Połączono ze Skarbcem — skarbce krajów, koszty i podatki włączone.");
            return economy;
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Nie udało się połączyć ze Skarbcem — ekonomia wyłączona.", e);
            return NONE;
        }
    }
}
