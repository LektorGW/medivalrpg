// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.kraje.model.Rank;

/**
 * {@code flags:} section of config.yml (defaults from the jar fill missing keys).
 *
 * @param enabled                 whole feature
 * @param uploadRank              lowest rank that uploads, withdraws, resets, restores and places the capital banner
 * @param moderators              UUIDs that moderate in game and in the panel (panel admins moderate too)
 * @param limits                  image validation
 * @param uploadCooldownSeconds   per country, between two uploads
 * @param historySize             history entries kept per country
 * @param minIntervalMinutes      resource pack rebuilds happen at most this often (approvals are batched)
 * @param debounceSeconds         wait after the first approval so several approvals land in one rebuild
 * @param forceMinIntervalSeconds lower bound between two forced rebuilds ({@code /kraj flaga admin przebuduj})
 * @param switchDelaySeconds      after a rebuild, banners keep the old model this long (players download the pack)
 * @param modelPrefix             BetterModel model file prefix ({@code <prefix><8 hex of the country id>})
 * @param defaultModel            shared tinted model for countries without an approved image
 * @param capitalBanner           banner over every capital
 * @param embassyBanner           guest banner at every embassy
 * @param viewDistance            players closer than this see the banner
 * @param mapIcons                flag icons in the squaremap popup
 * @param alwaysSpawn             create banner trackers even with nobody nearby (isolated tests only)
 */
public record FlagSettings(boolean enabled, Rank uploadRank, Set<UUID> moderators, FlagImage.Limits limits,
                           int uploadCooldownSeconds, int historySize, int minIntervalMinutes, int debounceSeconds,
                           int forceMinIntervalSeconds, int switchDelaySeconds, String modelPrefix, String defaultModel,
                           boolean capitalBanner, boolean embassyBanner, int viewDistance, boolean mapIcons,
                           boolean alwaysSpawn) {

    /** WorldSteward (offline UUID) — server owner, see MEMORY.md. */
    public static final UUID OWNER = UUID.fromString("ff923cf7-f43c-37b8-85a3-77cbac357c9c");

    public static final FlagSettings DEFAULT = new FlagSettings(true, Rank.ADVISOR, Set.of(OWNER), FlagImage.Limits.DEFAULT,
        60, 20, 15, 60, 60, 20, "kraje_flaga_", "kraje_flaga_domyslna", true, true, 96, true, false);

    public static FlagSettings from(final ConfigurationSection s) {
        if (s == null) {
            return DEFAULT;
        }
        final FlagSettings d = DEFAULT;
        Rank rank = d.uploadRank;
        final String rawRank = s.getString("upload-rank", "ADVISOR");
        try {
            rank = Rank.valueOf(rawRank.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ignored) {
            // keep the default
        }
        final List<UUID> mods = new ArrayList<>();
        for (final String raw : s.getStringList("moderators")) {
            try {
                mods.add(UUID.fromString(raw.trim()));
            } catch (final IllegalArgumentException ignored) {
                // skip garbage
            }
        }
        final FlagImage.Limits limits = new FlagImage.Limits(
            s.getInt("image.max-kb", 160) * 1024,
            s.getInt("image.max-source-pixels", 4096 * 1024),
            s.getInt("image.max-source-side", 4096),
            s.getInt("image.min-width", 16),
            s.getInt("image.min-height", 10),
            s.getInt("image.max-colors", 32));
        String prefix = s.getString("pack.model-prefix", d.modelPrefix);
        if (prefix == null || !prefix.matches("[a-z0-9_]{1,24}")) {
            prefix = d.modelPrefix;
        }
        String defaultModel = s.getString("pack.default-model", d.defaultModel);
        if (defaultModel == null || !defaultModel.matches("[a-z0-9_]{1,40}")) {
            defaultModel = d.defaultModel;
        }
        return new FlagSettings(s.getBoolean("enabled", true), rank, Set.copyOf(mods), limits,
            Math.max(0, s.getInt("upload-cooldown-seconds", 60)),
            Math.clamp(s.getInt("history-size", 20), 1, 200),
            Math.max(1, s.getInt("pack.min-interval-minutes", 15)),
            Math.max(0, s.getInt("pack.debounce-seconds", 60)),
            Math.max(10, s.getInt("pack.force-min-interval-seconds", 60)),
            Math.max(0, s.getInt("pack.switch-delay-seconds", 20)),
            prefix, defaultModel,
            s.getBoolean("banners.capital", true),
            s.getBoolean("banners.embassy", true),
            Math.clamp(s.getInt("banners.view-distance", 96), 16, 256),
            s.getBoolean("map-icons", true),
            s.getBoolean("banners.always-spawn", false));
    }

    /** Model name of a country's own flag. */
    public String modelOf(final UUID country) {
        return this.modelPrefix + country.toString().replace("-", "").substring(0, 12);
    }
}
