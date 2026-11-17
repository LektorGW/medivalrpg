// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import pl.medivalrpg.menu.teleport.StoredLocation;

/** Values from {@code config.yml} (everything except {@code messages}, see {@link Messages}). */
public final class MenuSettings {

    /** What happens when a player drops the guide compass. */
    public enum DropMode {
        /** The dropped compass disappears and the player is told how to get it back. */
        DELETE,
        /** Dropping is blocked. */
        BLOCK;

        static DropMode parse(final String raw) {
            if (raw != null && raw.strip().toLowerCase(Locale.ROOT).startsWith("block")) {
                return BLOCK;
            }
            return DELETE;
        }
    }

    public final String mapUrl;
    public final String noCountry;
    public final String noRank;
    public final long clickCooldownMs;
    public final String soundClick;
    public final String soundError;

    public final boolean guideEnabled;
    public final String guideMaterial;
    public final String guideName;
    public final List<String> guideLore;
    public final boolean guideGlint;
    public final boolean guideGiveOnFirstJoin;
    public final int guidePreferredSlot;
    public final boolean guideMovable;
    public final DropMode guideDropMode;
    public final boolean guideKeepOnDeath;
    public final boolean guidePointToSpawn;
    public final String guideSpawnWorld;
    public final int loginFallbackSeconds;
    public final int loginMaxWaitSeconds;

    /** Samouczek: clickable invitation after the first login and a reminder on later logins. */
    public final boolean tutorialEnabled;
    public final boolean tutorialRemind;
    public final int tutorialInviteDelaySeconds;
    /** Copper the player must own (bank + coins) for the step "pierwsze pieniądze". */
    public final long tutorialMoneyGoal;

    public final int bookLineWidth;
    public final int bookLinesPerPage;

    /** Teleport rules shared by /spawn and /dom. */
    public final double teleportMaxMove;
    public final int combatTagSeconds;
    public final boolean blockDuringSiege;
    public final boolean teleportParticles;
    public final String teleportSoundTick;
    public final String teleportSoundDone;

    public final int spawnWarmupSeconds;
    public final int spawnCooldownSeconds;
    /** {@code spawn.location}; empty → spawn of the main world. */
    public final Optional<StoredLocation> spawnLocation;
    public final boolean spawnNewPlayers;
    public final boolean spawnOnRespawn;
    /** Every login (after Straznik/nLogin) ends at the spawn; returning players get a "back" offer. */
    public final boolean spawnAlwaysOnJoin;
    public final int returnWindowMinutes;
    public final double returnKeepRadius;
    public final int returnSafeRadius;

    /** {@code /ustawspawn zbuduj}. */
    public final int buildPreviewSeconds;
    public final int buildConfirmSeconds;
    public final float buildSpawnYaw;
    public final int buildSmoothWidth;
    public final boolean buildNpcEnabled;
    public final String buildNpcMerchant;
    public final String buildNpcBanker;
    public final float buildNpcMerchantYaw;
    public final float buildNpcBankerYaw;
    public final boolean buildRegionEnabled;
    public final String buildRegionId;
    public final int buildRegionMargin;
    public final int buildRegionPriority;
    public final boolean buildRegionInvincible;
    public final String buildRegionGreeting;
    public final String buildRegionFarewell;
    public final List<String> buildRegionDenySpawn;
    public final boolean buildKrajeEnabled;
    public final int buildKrajeBufferChunks;

    public final boolean homesEnabled;
    public final int homesMax;
    public final int homesWarmupSeconds;
    public final int homesCooldownSeconds;
    public final boolean homesOnlyOwnOrWild;

    public MenuSettings(final FileConfiguration c) {
        this.mapUrl = c.getString("map-url", "").strip();
        this.noCountry = c.getString("placeholders.no-country", "brak");
        this.noRank = c.getString("placeholders.no-rank", "-");
        this.clickCooldownMs = Math.max(0, c.getLong("menu.click-cooldown-ms", 250));
        this.soundClick = c.getString("menu.sounds.click", "ui.button.click");
        this.soundError = c.getString("menu.sounds.error", "block.note_block.bass");

        final ConfigurationSection g = c.getConfigurationSection("guide");
        final ConfigurationSection guide = g != null ? g : c.createSection("guide");
        this.guideEnabled = guide.getBoolean("enabled", true);
        this.guideMaterial = guide.getString("material", "COMPASS").strip().toUpperCase(Locale.ROOT);
        this.guideName = guide.getString("name", "<gold><bold>Przewodnik</bold></gold>");
        this.guideLore = guide.getStringList("lore");
        this.guideGlint = guide.getBoolean("glint", true);
        this.guideGiveOnFirstJoin = guide.getBoolean("give-on-first-join", true);
        this.guidePreferredSlot = Math.clamp(guide.getInt("preferred-slot", 8), 0, 35);
        this.guideMovable = guide.getBoolean("movable", true);
        this.guideDropMode = DropMode.parse(guide.getString("on-drop", "delete"));
        this.guideKeepOnDeath = guide.getBoolean("keep-on-death", true);
        this.guidePointToSpawn = guide.getBoolean("point-to-spawn", true);
        this.guideSpawnWorld = guide.getString("spawn-world", "").strip();
        this.loginFallbackSeconds = Math.max(0, guide.getInt("login-fallback-seconds", 3));
        this.loginMaxWaitSeconds = Math.max(10, guide.getInt("login-max-wait-seconds", 600));

        this.tutorialEnabled = c.getBoolean("tutorial.enabled", true);
        this.tutorialRemind = c.getBoolean("tutorial.remind", true);
        this.tutorialInviteDelaySeconds = Math.clamp(c.getInt("tutorial.invite-delay-seconds", 3), 0, 120);
        this.tutorialMoneyGoal = Math.max(1L, c.getLong("tutorial.money-goal", 100L));

        this.bookLineWidth = Math.clamp(c.getInt("book.line-width", 112), 40, 114);
        this.bookLinesPerPage = Math.clamp(c.getInt("book.lines-per-page", 14), 4, 14);

        this.teleportMaxMove = Math.clamp(c.getDouble("teleport.max-move", 0.5), 0.0, 16.0);
        this.combatTagSeconds = Math.clamp(c.getInt("teleport.combat-tag-seconds", 10), 0, 3600);
        this.blockDuringSiege = c.getBoolean("teleport.block-during-siege", true);
        this.teleportParticles = c.getBoolean("teleport.particles", true);
        this.teleportSoundTick = c.getString("teleport.sounds.tick", "block.note_block.hat");
        this.teleportSoundDone = c.getString("teleport.sounds.done", "entity.enderman.teleport");

        this.spawnWarmupSeconds = Math.clamp(c.getInt("spawn.warmup-seconds", 5), 0, 60);
        this.spawnCooldownSeconds = Math.clamp(c.getInt("spawn.cooldown-seconds", 30), 0, 86_400);
        this.spawnLocation = StoredLocation.parse(c.get("spawn.location"));
        this.spawnNewPlayers = c.getBoolean("spawn.teleport-new-players", true);
        this.spawnOnRespawn = c.getBoolean("spawn.respawn-at-spawn", false);
        this.spawnAlwaysOnJoin = c.getBoolean("spawn.always-on-join", false);
        this.returnWindowMinutes = Math.clamp(c.getInt("spawn.return-window-minutes", 10), 0, 1440);
        this.returnKeepRadius = Math.clamp(c.getDouble("spawn.return-keep-radius", 48), 0.0, 1000.0);
        this.returnSafeRadius = Math.clamp(c.getInt("spawn.return-safe-radius", 8), 0, 16);

        this.buildPreviewSeconds = Math.clamp(c.getInt("spawn.build.preview-seconds", 30), 5, 300);
        this.buildConfirmSeconds = Math.clamp(c.getInt("spawn.build.confirm-seconds", 120), 10, 600);
        this.buildSpawnYaw = (float) c.getDouble("spawn.build.spawn-yaw", 0.0);
        this.buildSmoothWidth = Math.clamp(c.getInt("spawn.build.smooth-width", 5), 0, 6);
        this.buildNpcEnabled = c.getBoolean("spawn.build.npc.enabled", true);
        this.buildNpcMerchant = c.getString("spawn.npc.kupiec", "Kupiec Bartłomiej");
        this.buildNpcBanker = c.getString("spawn.npc.bankier", "Bankier Zenon");
        this.buildNpcMerchantYaw = (float) c.getDouble("spawn.build.npc.kupiec-yaw", 0.0);
        this.buildNpcBankerYaw = (float) c.getDouble("spawn.build.npc.bankier-yaw", -90.0);
        this.buildRegionEnabled = c.getBoolean("spawn.build.region.enabled", true);
        this.buildRegionId = c.getString("spawn.build.region.id", "spawn").strip().toLowerCase(Locale.ROOT);
        this.buildRegionMargin = Math.clamp(c.getInt("spawn.build.region.margin", 8), 0, 64);
        this.buildRegionPriority = c.getInt("spawn.build.region.priority", 10);
        this.buildRegionInvincible = c.getBoolean("spawn.build.region.invincible", true);
        this.buildRegionGreeting = c.getString("spawn.build.region.greeting", "<gold>Witaj w stolicy MEDIVALRPG!");
        this.buildRegionFarewell = c.getString("spawn.build.region.farewell", "<gray>Opuszczasz spawn");
        this.buildRegionDenySpawn = c.getStringList("spawn.build.region.deny-spawn");
        this.buildKrajeEnabled = c.getBoolean("spawn.build.kraje.enabled", true);
        this.buildKrajeBufferChunks = Math.clamp(c.getInt("spawn.build.kraje.buffer-chunks", 3), 0, 16);

        this.homesEnabled = c.getBoolean("homes.enabled", true);
        this.homesMax = Math.clamp(c.getInt("homes.max", 1), 0, 100);
        this.homesWarmupSeconds = Math.clamp(c.getInt("homes.warmup-seconds", 5), 0, 60);
        this.homesCooldownSeconds = Math.clamp(c.getInt("homes.cooldown-seconds", 30), 0, 86_400);
        this.homesOnlyOwnOrWild = c.getBoolean("homes.only-own-or-wild", true);
    }
}
