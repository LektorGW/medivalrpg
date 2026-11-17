// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.pory.command.PoryCommand;
import pl.medivalrpg.pory.config.Messages;
import pl.medivalrpg.pory.config.PoryConfig;
import pl.medivalrpg.pory.logic.BiomeMapping;
import pl.medivalrpg.pory.logic.VisualSeason;
import pl.medivalrpg.pory.nms.Nms;
import pl.medivalrpg.pory.nms.PacketContext;
import pl.medivalrpg.pory.nms.SeasonPacketHandler;
import pl.medivalrpg.pory.nms.SnowRules;
import pl.medivalrpg.pory.service.BiomeRefresher;
import pl.medivalrpg.pory.service.LeafService;
import pl.medivalrpg.pory.service.SeasonService;
import pl.medivalrpg.pory.service.SnowService;
import pl.medivalrpg.pory.service.ViewService;

/**
 * Pory — the seasons made visible. Follows the Ludnosc season clock and changes only what clients see: seasonal
 * biome variants in chunk packets (colours, snowfall), client-only snow cover and autumn leaves. The server world is
 * never modified.
 */
public final class PoryPlugin extends JavaPlugin {

    /** Vanilla: precipitation falls as snow below this biome temperature. */
    private static final float SNOW_TEMPERATURE = 0.15f;

    private final List<BukkitTask> tasks = new ArrayList<>();
    private PoryConfig config;
    private Messages messages;
    private PacketContext context;
    private SeasonService seasons;
    private BiomeRefresher biomes;
    private SnowService snow;
    private LeafService leaves;
    private ViewService views;
    private boolean installed;

    @Override
    public void onEnable() {
        this.getLogger().info("Pory by Maciej Kaczmarczyk — lektoro.pl");
        this.saveDefaultConfig();
        this.loadConfigFiles();
        this.seasons = new SeasonService(this.getLogger(), this.config.fallbackSeason());
        this.biomes = new BiomeRefresher();
        this.leaves = new LeafService();

        final PoryCommand command = new PoryCommand(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(command.build("pory"), "Pory roku — jak teraz wygląda świat", List.of()));

        if (!this.config.enabled()) {
            this.getLogger().info("Pory wyłączone w config.yml (enabled: false) — świat bez zmian.");
            return;
        }
        this.context = new PacketContext(t -> this.getLogger().log(Level.WARNING,
            "Błąd przepisywania pakietu — gracz dostał oryginał (kolejne błędy tylko liczone)", t));
        this.snow = new SnowService(p -> this.views == null ? 0 : this.views.coverage(p));
        this.buildMapping();
        this.applyConfig();
        this.seasons.poll(this.config.fallbackSeason());
        this.context.globalSeason(this.seasons.state().season());

        Nms.install(this.context, Bukkit.getOnlinePlayers(), this.getLogger()::warning);
        this.installed = true;
        this.views = new ViewService(this.context, this.seasons, this.biomes, this.snow, this.config,
            this.getLogger()::warning);
        Bukkit.getPluginManager().registerEvents(this.views, this);
        this.startTasks();
        final SeasonService.State s = this.seasons.state();
        this.getLogger().info("Pora: " + s.season().displayName() + " (dzień " + s.day() + "/" + s.days() + ", "
            + (s.fromLudnosc() ? "zegar Ludnosc" : "pora zapasowa") + ").");
        // players already online (plugin reload): show the current season everywhere
        Bukkit.getScheduler().runTask(this, () -> this.views.refreshAll(true));
    }

    @Override
    public void onDisable() {
        this.tasks.forEach(BukkitTask::cancel);
        this.tasks.clear();
        if (this.installed) {
            Nms.uninstall(Bukkit.getOnlinePlayers());
            this.installed = false;
        }
    }

    private void loadConfigFiles() {
        this.reloadConfig();
        this.config = PoryConfig.from(this.getConfig());
        this.messages = new Messages(this.getConfig().getConfigurationSection("messages"));
    }

    private void buildMapping() {
        final BiomeMapping mapping = BiomeMapping.build(Nms.biomeEntries(), SNOW_TEMPERATURE);
        this.context.mapping(mapping);
        final int variants = mapping.variantCount(VisualSeason.WIOSNA) + mapping.variantCount(VisualSeason.JESIEN)
            + mapping.variantCount(VisualSeason.ZIMA);
        this.getLogger().info("Biomy: " + mapping.baseCount() + " bazowych, sezonowe warianty: wiosna "
            + mapping.variantCount(VisualSeason.WIOSNA) + ", jesień " + mapping.variantCount(VisualSeason.JESIEN)
            + ", zima " + mapping.variantCount(VisualSeason.ZIMA) + ".");
        if (variants == 0) {
            this.getLogger().warning("Brak sezonowych biomów — datapack medival-pory-roku nie jest wczytany "
                + "(world/datapacks + restart). Kolory biomów wyłączone, śnieg i liście działają.");
        }
        if (SeasonPacketHandler.reflectionError() != null) {
            this.getLogger().warning("Przepisywanie chunków niedostępne na tym buildzie Papera: "
                + SeasonPacketHandler.reflectionError());
        }
    }

    private void applyConfig() {
        final BiomeMapping mapping = this.context.mapping();
        final int variants = mapping == null ? 0 : mapping.variantCount(VisualSeason.ZIMA)
            + mapping.variantCount(VisualSeason.JESIEN) + mapping.variantCount(VisualSeason.WIOSNA);
        // without the chunk packet rebuild only refreshes would be seasonal: then keep all biomes vanilla
        this.context.biomesEnabled(this.config.biomes() && variants > 0 && SeasonPacketHandler.reflectionError() == null);
        final SnowRules rules = new SnowRules(this.config.denyBlocks());
        this.context.snowRules(rules);
        this.snow.configure(rules, mapping, this.config.snowyGround(), this.config.snowMaxQueue());
    }

    private void startTasks() {
        this.tasks.forEach(BukkitTask::cancel);
        this.tasks.clear();
        final long poll = this.config.pollSeconds() * 20L;
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (this.seasons.poll(this.config.fallbackSeason())) {
                final SeasonService.State s = this.seasons.state();
                this.getLogger().info("Zmiana pory/dnia: " + s.season().displayName() + " dzień " + s.day() + "/"
                    + s.days() + " — odświeżam widok graczy.");
                this.views.refreshAll(false);
            }
        }, poll, poll));
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            this.biomes.tick(this.config.refreshPerPlayer(), this.config.refreshPerTick(), this.config.chunksPerPacket());
            this.snow.tick(this.config.snowBudgetMs());
        }, 1L, 1L));
        if (this.config.leaves()) {
            final long every = this.config.leavesInterval();
            this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> this.leaves.tick(this.views::season,
                this.views::worldAllowed, this.config.leavesSamples(), this.config.leavesRadius()), every, every));
        }
    }

    /** {@code /pory admin reload}. */
    public void reload() {
        this.loadConfigFiles();
        if (this.context == null) {
            return;
        }
        this.applyConfig();
        this.views.config(this.config);
        this.startTasks();
        this.seasons.poll(this.config.fallbackSeason());
        // settings may switch biomes or snow on/off: re-send everyone's view (bounded queues)
        this.views.refreshAll(true);
    }

    public Messages messages() {
        return this.messages;
    }

    public SeasonService seasons() {
        return this.seasons;
    }

    public PacketContext context() {
        return this.context;
    }

    public ViewService views() {
        return this.views;
    }

    public BiomeRefresher biomes() {
        return this.biomes;
    }

    public SnowService snow() {
        return this.snow;
    }

    public LeafService leaves() {
        return this.leaves;
    }
}
