// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ai;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.logic.Payroll;
import pl.medivalrpg.armia.soldier.ArmyService;
import pl.medivalrpg.armia.soldier.Soldier;
import pl.medivalrpg.armia.storage.DataStore;

/**
 * Daily wages. {@code upkeep.trigger: interval} pays every {@code interval-minutes} (catching up missed days after a
 * restart, at most {@code max-catch-up}); {@code treasury-tick} pays when Kraje finishes the daily economy tick of
 * a country.
 */
public final class UpkeepService {

    private final Plugin plugin;
    private final ArmyService army;
    private final NpcAiService ai;
    private final Supplier<ArmiaConfig> config;
    private final DataStore.Meta meta;
    private final Map<UUID, Long> paidAt = new HashMap<>();
    private BukkitTask task;

    public UpkeepService(final Plugin plugin, final ArmyService army, final NpcAiService ai, final Supplier<ArmiaConfig> config, final DataStore.Meta meta) {
        this.plugin = plugin;
        this.army = army;
        this.ai = ai;
        this.config = config;
        this.meta = meta;
    }

    public void start() {
        if (this.meta.lastUpkeepAt <= 0) {
            this.meta.lastUpkeepAt = System.currentTimeMillis();
        }
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::check, 20L * 30, 20L * 60);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
        }
    }

    private void check() {
        this.army.expireContracts(); // mercenary contracts (every minute)
        final ArmiaConfig cfg = this.config.get();
        if (cfg.upkeepTrigger != ArmiaConfig.UpkeepTrigger.INTERVAL) {
            return;
        }
        final long interval = cfg.upkeepIntervalMinutes * 60_000L;
        final long now = System.currentTimeMillis();
        final long due = (now - this.meta.lastUpkeepAt) / interval;
        if (due <= 0) {
            return;
        }
        final long runs = Math.min(due, cfg.upkeepMaxCatchUp);
        for (int i = 0; i < runs; i++) {
            this.payAll();
        }
        this.meta.lastUpkeepAt = due > runs ? now : this.meta.lastUpkeepAt + runs * interval;
    }

    /** Pays every country that has soldiers. */
    public void payAll() {
        final Set<UUID> countries = new HashSet<>();
        for (final Soldier s : this.army.registry().all()) {
            countries.add(s.country());
        }
        for (final UUID c : countries) {
            this.pay(c);
        }
    }

    void payFromTreasuryTick(final UUID country) {
        final long now = System.currentTimeMillis();
        final Long last = this.paidAt.get(country);
        if (last != null && now - last < 20L * 3_600_000L) {
            return; // never twice a day
        }
        this.paidAt.put(country, now);
        this.pay(country);
    }

    private void pay(final UUID country) {
        final Payroll.Result r = this.army.payCountry(country);
        if (this.ai.state(country).isPresent() && (r.paid() > 0 || r.unpaid() > 0)) {
            this.ai.flow(country, "wages", -r.paid(), "", "Żołd " + r.paidIds().size() + " żołnierzy");
            this.ai.log(country, pl.medivalrpg.armia.api.NpcLogCategory.ARMY, "Żołd: wypłacono " + r.paidIds().size() + " (" + r.paid() + " md), niewypłacono "
                + r.unpaid() + ", dezercje " + r.deserters().size());
        }
    }
}
