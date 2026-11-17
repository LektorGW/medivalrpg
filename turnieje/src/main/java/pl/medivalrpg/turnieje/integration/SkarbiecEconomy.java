// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.integration;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/** {@link Economy} backed by SkarbiecApi. Only touched after checking that the Skarbiec plugin is enabled. */
final class SkarbiecEconomy implements Economy {

    private final SkarbiecApi api;
    private final pl.medivalrpg.turnieje.data.Storage storage;

    private SkarbiecEconomy(final SkarbiecApi api, final pl.medivalrpg.turnieje.data.Storage storage) {
        this.api = api;
        this.storage = storage;
    }

    /** Null when the service is not registered. */
    static Economy tryCreate(final pl.medivalrpg.turnieje.data.Storage storage) {
        final SkarbiecApi api = Bukkit.getServicesManager().load(SkarbiecApi.class);
        return api == null ? null : new SkarbiecEconomy(api, storage);
    }

    @Override
    public boolean available() {
        return !this.storage.moneyBlocked();
    }

    @Override
    public long balance(final String account) {
        return this.api.balance(account);
    }

    @Override
    public boolean transfer(final String from, final String to, final long amount, final String reason) {
        if (!this.available() || amount <= 0) return false;
        this.storage.beginMoney("transfer " + from + " -> " + to + " " + amount + " " + reason);
        try { return this.api.transfer(from, to, amount, reason).ok(); }
        catch (RuntimeException | Error e) { this.storage.blockMoney(); throw e; }
    }

    @Override
    public boolean deposit(final String account, final long amount, final String reason) {
        if (!this.available() || amount <= 0) return false;
        this.storage.beginMoney("deposit " + account + " " + amount + " " + reason);
        try { return this.api.deposit(account, amount, reason).ok(); }
        catch (RuntimeException | Error e) { this.storage.blockMoney(); throw e; }
    }

    @Override
    public boolean withdraw(final String account, final long amount, final String reason) {
        if (!this.available() || amount <= 0) return false;
        this.storage.beginMoney("withdraw " + account + " " + amount + " " + reason);
        try { return this.api.withdraw(account, amount, reason).ok(); }
        catch (RuntimeException | Error e) { this.storage.blockMoney(); throw e; }
    }

    @Override
    public String format(final long amount) {
        return this.api.format(amount);
    }

    @Override
    public Optional<Long> parse(final String input) {
        return this.api.parse(input);
    }

    @Override
    public String playerAccount(final UUID player) {
        return SkarbiecApi.playerAccount(player);
    }
}
