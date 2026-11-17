// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import pl.medivalrpg.turnieje.command.TurniejCommand;
import pl.medivalrpg.turnieje.config.Messages;
import pl.medivalrpg.turnieje.config.TurniejeConfig;
import pl.medivalrpg.turnieje.data.Storage;
import pl.medivalrpg.turnieje.game.Celebration;
import pl.medivalrpg.turnieje.game.Horses;
import pl.medivalrpg.turnieje.game.Keys;
import pl.medivalrpg.turnieje.game.PlayerCare;
import pl.medivalrpg.turnieje.game.SessionManager;
import pl.medivalrpg.turnieje.integration.Services;
import pl.medivalrpg.turnieje.listener.ArenaListener;
import pl.medivalrpg.turnieje.listener.CombatListener;
import pl.medivalrpg.turnieje.listener.PlayerListener;

/** Turnieje: knight tournaments, jousts, melees and duels on arenas, with a weekly event, prizes and a ranking. */
public final class TurniejePlugin extends JavaPlugin {

    private TurniejeConfig config;
    private Messages messages;
    private Storage storage;
    private SessionManager sessions;
    private pl.medivalrpg.turnieje.bootstrap.ArenaBootstrap arenaBootstrap;

    @Override
    public void onEnable() {
        this.getLogger().info("Turnieje by Maciej Kaczmarczyk — lektoro.pl");
        this.saveDefaultConfig();
        this.loadSettings();

        this.storage = new Storage(this.getDataFolder().toPath(), this.getLogger());
        this.storage.load();
        if (this.storage.moneyBlocked()) this.getLogger().severe("Rozliczenia zablokowane: money-journal.json — uzgodnij operacje z logiem Skarbca przed wznowieniem");
        final Services services = new Services(this.getLogger(), this.storage);
        final Keys keys = new Keys(this);
        final PlayerCare care = new PlayerCare(this, this.storage, () -> this.config, keys);
        final Horses horses = new Horses(keys, () -> this.config);
        this.sessions = new SessionManager(this, this.storage, services, care, horses, new Celebration(keys));
        this.sessions.settings(this.config, this.messages);

        final var pm = Bukkit.getPluginManager();
        pm.registerEvents(new CombatListener(this, this.sessions, horses, keys), this);
        pm.registerEvents(new PlayerListener(this, this.sessions, care), this);
        pm.registerEvents(new ArenaListener(this.sessions, horses), this);

        final TurniejCommand command = new TurniejCommand(this.sessions, this.storage, services, this::reload);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(command.build(), "Turnieje rycerskie, pojedynki i areny", List.of("arena")));

        // after all plugins are enabled: refund tournaments interrupted by a restart, remove orphaned horses,
        // restore players already online (plugin reload)
        Bukkit.getScheduler().runTask(this, () -> {
            this.sessions.recoverAfterRestart();
            final int removed = horses.cleanupLoaded();
            if (removed > 0) {
                this.getLogger().info("Usunięto " + removed + " osieroconych koni turniejowych");
            }
            for (final Player p : Bukkit.getOnlinePlayers()) {
                this.sessions.afterRespawnOrJoin(p);
            }
            this.arenaBootstrap = pl.medivalrpg.turnieje.bootstrap.ArenaBootstrap.start(this, this.storage, this.sessions);
        });
        Bukkit.getScheduler().runTaskTimer(this, this.sessions::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this.storage::saveStatsIfDirty, 20L * 60, 20L * 60);
        this.getLogger().info("Areny: " + this.storage.arenas().size() + ", gracze w rankingu: " + this.storage.stats().size()
            + ", cotygodniowy turniej: " + (this.config.weeklyEnabled ? this.sessions.nextWeeklyText() : "wyłączony"));
    }

    @Override
    public void onDisable() {
        // Each step runs even when an earlier one throws: the final synchronous save of snapshots/state/stats must
        // never be skipped because cancelling a session failed.
        try {
            if (this.arenaBootstrap != null) this.arenaBootstrap.close();
        } catch (final RuntimeException e) {
            this.getLogger().log(java.util.logging.Level.SEVERE, "Zamykanie bootstrapu areny nie powiodło się", e);
        }
        try {
            if (this.sessions != null) {
                this.sessions.shutdown();
            }
        } catch (final RuntimeException e) {
            this.getLogger().log(java.util.logging.Level.SEVERE, "Anulowanie sesji przy wyłączaniu nie powiodło się — zwrot przy następnym starcie", e);
        } finally {
            if (this.storage != null) {
                this.storage.shutdown();
            }
        }
    }

    private void loadSettings() {
        this.reloadConfig();
        this.config = new TurniejeConfig(this.getConfig(), this.getLogger());
        this.messages = new Messages(this.getConfig(), this.getLogger());
    }

    /** {@code /turniej reload}: config and messages; running sessions keep going with the new texts. */
    public void reload() {
        this.loadSettings();
        this.sessions.settings(this.config, this.messages);
    }
}
