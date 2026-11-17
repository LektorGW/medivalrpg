// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.interakcje.anim.AnimationService;
import pl.medivalrpg.interakcje.api.WorkAnimations;
import pl.medivalrpg.interakcje.ceremony.CeremonyService;
import pl.medivalrpg.interakcje.npc.NpcAnimationService;
import pl.medivalrpg.interakcje.npc.NpcListener;
import pl.medivalrpg.interakcje.pack.ResourcePackService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main class of the Interakcje plugin (RP interactions between players + sitting / lying). */
public final class InterakcjePlugin extends JavaPlugin {
    public static final String DANCES = "dances";
    public static final String EMOTES = "emotes";

    private Settings settings;
    private Messages messages;
    private Map<String, InteractionDef> interactions = Map.of();
    private Map<String, PoseDef> poseDefs = Map.of();
    private Map<String, MotionGroup> motionGroups = Map.of();

    private PlayerDataStore data;
    private InteractionService service;
    private EffectPlayer effects;
    private HoldManager holds;
    private PoseManager poses;
    private PlayerSnapshot snapshot;
    private AnimationService animations;
    private ResourcePackService resourcePack;
    private NpcAnimationService npcAnimations;
    private CeremonyService ceremonies;
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public void onEnable() {
        getLogger().info("Interakcje by Maciej Kaczmarczyk — lektoro.pl");
        saveDefaultConfig();

        effects = new EffectPlayer(this);
        loadConfiguration();

        data = new PlayerDataStore(getDataFolder(), getLogger());
        data.load();
        service = new InteractionService(this);
        holds = new HoldManager(this);
        poses = new PoseManager(this);
        snapshot = new PlayerSnapshot(this);
        animations = new AnimationService(this);
        resourcePack = new ResourcePackService(this);
        ceremonies = new CeremonyService(this);
        ceremonies.reload();
        npcAnimations = new NpcAnimationService(this);
        animations.enable();
        animations.onBackendReload(resourcePack::requestCheck);
        resourcePack.start();

        int stray = poses.removeStraySeatsEverywhere();
        if (stray > 0) {
            getLogger().info("Usunięto osierocone siedziska: " + stray);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            poses.onJoin(p); // e.g. after a plugin reload
        }
        snapshot.refresh();

        getServer().getPluginManager().registerEvents(new InterakcjeListener(this), this);
        getServer().getPluginManager().registerEvents(resourcePack, this);
        getServer().getPluginManager().registerEvents(new NpcListener(npcAnimations), this);
        npcAnimations.enable();
        getServer().getServicesManager().register(WorkAnimations.class, npcAnimations, this, ServicePriority.Normal);
        for (Player p : Bukkit.getOnlinePlayers()) {
            ceremonies.applyDisplayName(p);
        }

        InterakcjeCommands commands = new InterakcjeCommands(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> commands.registerAll(event.registrar()));

        tasks.add(Bukkit.getScheduler().runTaskTimer(this, snapshot::refresh, 5L, 5L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, service::tickSecond, 20L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, poses::tick, 10L, 10L));

        getLogger().info("Wczytano interakcji: " + interactions.size() + " (" + String.join(", ", interactions.keySet()) + ")");
    }

    @Override
    public void onDisable() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (holds != null) {
            holds.stopAll();
        }
        if (poses != null) {
            poses.stopAll();
        }
        getServer().getServicesManager().unregisterAll(this);
        if (npcAnimations != null) {
            npcAnimations.shutdown();
        }
        if (ceremonies != null) {
            ceremonies.shutdown();
        }
        if (animations != null) {
            animations.shutdown();
        }
        if (resourcePack != null) {
            resourcePack.stop();
        }
        if (service != null) {
            service.clear();
        }
        Bukkit.getScheduler().cancelTasks(this);
        if (data != null) {
            data.close();
        }
    }

    /** Settings, messages, interactions and poses from config.yml (+ dynamic permissions). */
    private void loadConfiguration() {
        List<String> added = DefaultEntries.addMissing(getConfig(), getConfig().getBoolean("settings.add-new-defaults", true));
        if (!added.isEmpty()) {
            getLogger().info("Nowe domyślne wpisy z jara (brak w config.yml, użyte bez zapisu): " + String.join(", ", added));
        }
        settings = Settings.from(getConfig());
        messages = new Messages(getConfig());

        Map<String, PoseDef> p = new LinkedHashMap<>();
        p.put(PoseDef.SIT, PoseDef.parse(PoseDef.SIT, getConfig().getConfigurationSection("sit"), this::warn));
        p.put(PoseDef.LAY, PoseDef.parse(PoseDef.LAY, getConfig().getConfigurationSection("lay"), this::warn));
        poseDefs = Collections.unmodifiableMap(p);

        Map<String, MotionGroup> m = new LinkedHashMap<>();
        m.put(DANCES, MotionGroup.parse(DANCES, getConfig().getConfigurationSection(DANCES), "taniec",
                List.of("tancz", "dance"), "interakcje.dance", "numer", this::warn));
        m.put(EMOTES, MotionGroup.parse(EMOTES, getConfig().getConfigurationSection(EMOTES), "emotka",
                List.of("emote", "em"), "interakcje.emote", "nazwa", this::warn));
        motionGroups = Collections.unmodifiableMap(m);

        Set<String> reserved = new HashSet<>();
        reserved.add(InterakcjeCommands.ROOT);
        reserved.addAll(InterakcjeCommands.ROOT_ALIASES);
        reserved.add(settings.releaseCommand());
        reserved.addAll(settings.releaseAliases());
        poseDefs.values().forEach(d -> reserved.addAll(d.labels()));
        motionGroups.values().forEach(g -> reserved.addAll(g.labels()));

        interactions = Collections.unmodifiableMap(
                InteractionLoader.load(getConfig().getConfigurationSection("interactions"), reserved, this::warn));

        PluginManager pm = getServer().getPluginManager();
        List<String> nodes = new ArrayList<>();
        interactions.values().forEach(d -> nodes.add(d.permission()));
        poseDefs.values().forEach(d -> nodes.add(d.permission()));
        for (String node : nodes) {
            if (pm.getPermission(node) == null) {
                pm.addPermission(new Permission(node, "Interakcja " + node.substring("interakcje.".length()),
                        PermissionDefault.TRUE));
            }
        }

        effects.clearCache();
        effects.validate(interactions.values());
        List<InteractionDef.Effects> motionEffects = new ArrayList<>();
        motionGroups.values().forEach(g -> g.entries().values().forEach(e -> motionEffects.add(e.effects())));
        effects.validateEffects(motionEffects);
    }

    private void warn(String message) {
        getLogger().warning("config.yml: " + message);
    }

    /**
     * {@code /interakcje reload}: settings, messages, interaction parameters, menu. Command names /
     * aliases of interactions are registered at startup – new or renamed commands need a restart.
     */
    public void reloadAll() {
        reloadConfig();
        loadConfiguration();
        animations.reloadSettings();
        npcAnimations.reloadSettings();
        ceremonies.reload();
        resourcePack.reload();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.updateCommands();
        }
    }

    // ------------------------------------------------------------------ names

    /** Name component of a player: plain nick, or display name when {@code use-display-names}. */
    public Component displayName(Player p) {
        return settings.useDisplayNames() ? p.displayName() : Component.text(p.getName());
    }

    /** {@code <sender>} and {@code <target>} placeholders (never parsed as MiniMessage). */
    public TagResolver names(Player sender, Player target) {
        return TagResolver.resolver(
                Messages.component("sender", sender == null ? Component.text("?") : displayName(sender)),
                Messages.component("target", target == null ? Component.text("?") : displayName(target)));
    }

    // ------------------------------------------------------------------ accessors

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Map<String, InteractionDef> interactions() {
        return interactions;
    }

    public InteractionDef interaction(String id) {
        return interactions.get(id);
    }

    public Map<String, PoseDef> poseDefs() {
        return poseDefs;
    }

    public PoseDef pose(String id) {
        return poseDefs.get(id);
    }

    public PlayerDataStore data() {
        return data;
    }

    public InteractionService service() {
        return service;
    }

    public EffectPlayer effects() {
        return effects;
    }

    public HoldManager holds() {
        return holds;
    }

    public PoseManager poses() {
        return poses;
    }

    public PlayerSnapshot snapshot() {
        return snapshot;
    }

    public AnimationService animations() {
        return animations;
    }

    public ResourcePackService resourcePack() {
        return resourcePack;
    }

    public NpcAnimationService npcAnimations() {
        return npcAnimations;
    }

    public CeremonyService ceremonies() {
        return ceremonies;
    }

    /** {@link #DANCES} and {@link #EMOTES}. */
    public Map<String, MotionGroup> motionGroups() {
        return motionGroups;
    }
}
