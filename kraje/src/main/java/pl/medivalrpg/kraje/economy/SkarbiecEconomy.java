// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * {@link Economy} over {@link SkarbiecApi}. Loaded only through reflection when Skarbiec is enabled.
 * The service is looked up on every call, so a late registration (or a reload of Skarbiec) is picked up;
 * when it is missing the economy behaves as disabled.
 */
public final class SkarbiecEconomy implements Economy {

    private static SkarbiecApi api() {
        return Bukkit.getServicesManager().load(SkarbiecApi.class);
    }

    @Override
    public boolean enabled() {
        return api() != null;
    }

    @Override
    public long balance(final String account) {
        final SkarbiecApi api = api();
        return api == null ? 0 : api.balance(account);
    }

    @Override
    public boolean deposit(final String account, final long amount, final String reason) {
        final SkarbiecApi api = api();
        return api != null && amount > 0 && api.deposit(account, amount, reason).ok();
    }

    @Override
    public boolean withdraw(final String account, final long amount, final String reason) {
        final SkarbiecApi api = api();
        return api != null && amount > 0 && api.withdraw(account, amount, reason).ok();
    }

    @Override
    public boolean transfer(final String from, final String to, final long amount, final String reason) {
        final SkarbiecApi api = api();
        return api != null && amount > 0 && api.transfer(from, to, amount, reason).ok();
    }

    @Override
    public String format(final long amount) {
        final SkarbiecApi api = api();
        return api == null ? Money.format(amount) : api.format(amount);
    }

    @Override
    public Optional<Long> parse(final String input) {
        final SkarbiecApi api = api();
        return api == null ? Money.parse(input) : api.parse(input);
    }

    @Override
    public long countCoins(final Player player) {
        final SkarbiecApi api = api();
        return api == null ? 0 : api.countCoins(player);
    }

    @Override
    public boolean takeCoins(final Player player, final long amount) {
        final SkarbiecApi api = api();
        return api != null && amount > 0 && api.takeCoins(player, amount);
    }
}
