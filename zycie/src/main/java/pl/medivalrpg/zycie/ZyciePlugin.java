// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.zycie.api.ZycieApi;
import pl.medivalrpg.zycie.command.ZycieCommand;
import pl.medivalrpg.zycie.config.Messages;
import pl.medivalrpg.zycie.config.ZycieConfig;
import pl.medivalrpg.zycie.listener.ZycieListener;
import pl.medivalrpg.zycie.service.BubbleService;
import pl.medivalrpg.zycie.service.CityCache;
import pl.medivalrpg.zycie.service.ConversationService;
import pl.medivalrpg.zycie.service.FestivalService;
import pl.medivalrpg.zycie.service.Integrations;
import pl.medivalrpg.zycie.service.LifeService;
import pl.medivalrpg.zycie.service.MoodService;
import pl.medivalrpg.zycie.service.ReactionService;
import pl.medivalrpg.zycie.service.SiegeService;
import pl.medivalrpg.zycie.service.ZycieApiImpl;
import pl.medivalrpg.zycie.storage.DataStore;

/**
 * Zycie — daily life and mood of the NPC citizens (villagers) of MEDIVALRPG countries: daily plan, speech bubbles and
 * dialogues, reactions to players, mood (→ {@link ZycieApi#moodMultiplier} for Ludnosc), protests, sieges and festivals.
 * It never stores citizens or collects taxes (that is Ludnosc); it only steers villager behaviour and computes moods.
 */
public final class ZyciePlugin extends JavaPlugin {

    private KrajeApi kraje;
    private LudnoscApi ludnosc;
    private Integrations integrations;
    private CityCache cities;
    private MoodService mood;
    private BubbleService bubbles;
    private LifeService life;
    private ConversationService conversations;
    private ReactionService reactions;
    private SiegeService sieges;
    private FestivalService festivals;
    private pl.medivalrpg.zycie.service.FireService fires;
    private ZycieConfig cfg;
    private Messages messages;
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public void onEnable() {
        this.getLogger().info("Zycie by Maciej Kaczmarczyk — lektoro.pl");
        this.kraje = Bukkit.getServicesManager().load(KrajeApi.class);
        this.ludnosc = Bukkit.getServicesManager().load(LudnoscApi.class);
        if (this.kraje == null || this.ludnosc == null) {
            this.getLogger().severe("Brak API Kraje lub Ludnosc — wyłączam Zycie");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.saveDefaultConfig();
        this.integrations = new Integrations(this.getLogger());
        this.cities = new CityCache(this.ludnosc);
        this.mood = new MoodService(this, this.kraje, this.cities, this.integrations,
            new DataStore(this.getDataFolder().toPath(), this.getLogger()));
        this.bubbles = new BubbleService(this);
        this.life = new LifeService(this, this.kraje, this.ludnosc, this.cities, this.mood, this.bubbles, this.integrations);
        this.conversations = new ConversationService(this, this.ludnosc, this.life);
        this.reactions = new ReactionService(this.kraje, this.ludnosc, this.life, this.integrations);
        this.sieges = new SiegeService(this, this.kraje, this.ludnosc);
        this.festivals = new FestivalService(this, this.kraje, this.mood, this.life);
        this.fires = new pl.medivalrpg.zycie.service.FireService(this, this.cities);
        this.life.wire(this.conversations, this.sieges);
        this.loadConfiguration().forEach(p -> this.getLogger().warning("Konfiguracja: " + p));
        this.mood.load();
        this.fires.load();

        Bukkit.getServicesManager().register(ZycieApi.class, new ZycieApiImpl(this.mood), this, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(new ZycieListener(this), this);
        Bukkit.getPluginManager().registerEvents(this.fires, this);

        final ZycieCommand command = new ZycieCommand(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(command.build("zycie"), "Życie i nastrój obywateli NPC", List.of("miasto")));

        // after every plugin is enabled (Postep/Armia services, worlds)
        Bukkit.getScheduler().runTask(this, () -> {
            final int purged = this.bubbles.purgeLoaded();
            if (purged > 0) {
                this.getLogger().info("Usunięto " + purged + " starych dymków.");
            }
            this.hookPostep();
            this.mood.recalcAll();
            this.startTasks();
        });
    }

    @Override
    public void onDisable() {
        this.tasks.forEach(BukkitTask::cancel);
        this.tasks.clear();
        if (this.integrations != null) {
            this.integrations.unhook();
        }
        if (this.bubbles != null) {
            this.bubbles.clearAll();
        }
        if (this.sieges != null) {
            this.sieges.releaseAll();
        }
        if (this.fires != null) {
            this.fires.close();
        }
        if (this.mood != null) {
            try {
                this.mood.saveNow();
            } catch (final RuntimeException e) {
                this.getLogger().log(Level.SEVERE, "Nie udało się zapisać danych nastroju", e);
            }
        }
        Bukkit.getServicesManager().unregisterAll(this);
    }

    /** Hooks Postep's {@code EraAdvanceEvent} when Postep is (now) enabled. */
    public void hookPostep() {
        this.integrations.hookEraEvent(this, (country, oldEra, newEra) -> this.mood.onEraAdvance(country, oldEra, newEra));
    }

    private List<String> loadConfiguration() {
        this.reloadConfig();
        final List<String> problems = new ArrayList<>();
        this.cfg = new ZycieConfig(this.getConfig(), problems);
        this.messages = new Messages(this.getConfig(), this.getLogger());
        this.cities.maxAgeSeconds(this.cfg.refreshSeconds);
        this.mood.configure(this.cfg, this.messages);
        this.bubbles.configure(this.cfg);
        this.life.configure(this.cfg);
        this.conversations.configure(this.cfg);
        this.reactions.configure(this.cfg);
        this.sieges.configure(this.cfg);
        this.festivals.configure(this.cfg);
        this.fires.configure();
        return problems;
    }

    /** {@code /zycie admin reload}. */
    public List<String> reloadAll() {
        final List<String> problems = this.loadConfiguration();
        problems.forEach(p -> this.getLogger().warning("Konfiguracja: " + p));
        this.tasks.forEach(BukkitTask::cancel);
        this.tasks.clear();
        this.bubbles.clearAll();
        this.cities.clear();
        this.startTasks();
        this.mood.recalcAll();
        return problems;
    }

    private void startTasks() {
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.life::tick, 20L, 1L));
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.fires::tick, 30L, 10L));
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.festivals::tick, 40L, 1L));
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.reactions::tick, 30L, this.cfg.reactPeriod));
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.conversations::tick, 60L, this.cfg.talkPeriod));
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.sieges::tick, 50L, this.cfg.siegePeriod));
        final long recalc = 20L * this.cfg.moodRecalcSeconds;
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.mood::recalcAll, recalc, recalc));
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.mood::saveIfDirty, 20L * 60, 20L * 60));
    }

    // ------------------------------------------------------------------ accessors

    public KrajeApi kraje() {
        return this.kraje;
    }

    public LudnoscApi ludnosc() {
        return this.ludnosc;
    }

    public Integrations integrations() {
        return this.integrations;
    }

    public MoodService mood() {
        return this.mood;
    }

    public BubbleService bubbles() {
        return this.bubbles;
    }

    public LifeService life() {
        return this.life;
    }

    public ConversationService conversations() {
        return this.conversations;
    }

    public SiegeService sieges() {
        return this.sieges;
    }

    public FestivalService festivals() {
        return this.festivals;
    }

    public pl.medivalrpg.zycie.service.FireService fires() {
        return this.fires;
    }

    public ZycieConfig cfg() {
        return this.cfg;
    }

    public Messages messages() {
        return this.messages;
    }

    public boolean skarbiecPresent() {
        return Bukkit.getServicesManager().load(SkarbiecApi.class) != null;
    }
}
