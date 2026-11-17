// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.medivalrpg.nowosci.config.Messages;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.ChangelogCodec;
import pl.medivalrpg.nowosci.render.EntryFormat;

/** Bundled resources (config.yml, changelog.yml) loaded the way the plugin loads them. */
public final class Fixtures {

    private Fixtures() {
    }

    public static String resource(final String name) {
        try {
            return Files.readString(Path.of("src/main/resources", name), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** config.yml with the bundled file as defaults and {@code overrides} applied on top. */
    public static YamlConfiguration config(final Map<String, Object> overrides) {
        try {
            final YamlConfiguration defaults = new YamlConfiguration();
            defaults.loadFromString(resource("config.yml"));
            final YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(resource("config.yml"));
            config.setDefaults(defaults);
            overrides.forEach(config::set);
            return config;
        } catch (final InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static EntryFormat format(final Map<String, Object> overrides) {
        final YamlConfiguration yaml = config(overrides);
        final NowosciConfig cfg = new NowosciConfig(yaml);
        if (!cfg.warnings.isEmpty()) {
            throw new IllegalStateException("config.yml warnings: " + cfg.warnings);
        }
        return new EntryFormat(new Messages(yaml, w -> {
            throw new IllegalStateException(w);
        }), cfg);
    }

    public static EntryFormat format() {
        return format(Map.of());
    }

    public static Changelog bundledChangelog() {
        return ChangelogCodec.parse(resource("changelog.yml")).changelog();
    }
}
