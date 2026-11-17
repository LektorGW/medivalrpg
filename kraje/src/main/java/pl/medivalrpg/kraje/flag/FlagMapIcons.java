// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kraje.model.Country;

/**
 * Flag icons for the squaremap popup: {@code <web>/kraje-flagi/<country>.png} (the approved image or the default flag in
 * the country colour). Icons are public web files, so only approved flags are ever written here — never pending ones.
 * The popup HTML snippet is cached in a concurrent map because squaremap builds markers off the main thread.
 */
final class FlagMapIcons {

    static final String DIR = "kraje-flagi";

    private final FlagService service;
    private final Path dir;
    private final Map<UUID, String> written = new HashMap<>();
    private final Map<UUID, String> html = new ConcurrentHashMap<>();

    private FlagMapIcons(final FlagService service, final Path dir) {
        this.service = service;
        this.dir = dir;
    }

    /** Null when squaremap is not installed. */
    static FlagMapIcons create(final FlagService service) {
        final Plugin squaremap = Bukkit.getPluginManager().getPlugin("squaremap");
        if (squaremap == null) {
            return null;
        }
        final File dataFolder = squaremap.getDataFolder();
        String web = "web";
        final File config = new File(dataFolder, "config.yml");
        if (config.isFile()) {
            web = YamlConfiguration.loadConfiguration(config).getString("settings.web-directory.path", "web");
        }
        Path dir = Path.of(web);
        if (!dir.isAbsolute()) {
            dir = dataFolder.toPath().resolve(web);
        }
        return new FlagMapIcons(service, dir.resolve(DIR));
    }

    static String fileName(final UUID country) {
        return country.toString().replace("-", "").substring(0, 12) + ".png";
    }

    /** Main thread: rewrites changed icons (async) and returns true when any popup must be redrawn. */
    boolean sync(final Iterable<Country> countries) {
        boolean changed = false;
        final Set<UUID> seen = new HashSet<>();
        for (final Country c : countries) {
            seen.add(c.id());
            final String current = this.service.book().current(c.id());
            final String version = current != null ? current : String.format("d%06x", c.color() & 0xFFFFFF);
            if (version.equals(this.written.get(c.id()))) {
                continue;
            }
            final byte[] png = current != null ? this.service.store().readImage(current) : this.service.defaultPng(c.color());
            if (png == null) {
                continue;
            }
            this.written.put(c.id(), version);
            final Path target = this.dir.resolve(fileName(c.id()));
            this.service.plugin().getServer().getScheduler().runTaskAsynchronously(this.service.plugin(), () -> {
                try {
                    Files.createDirectories(this.dir);
                    final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                    Files.write(tmp, png);
                    FlagStore.move(tmp, target);
                } catch (final IOException e) {
                    this.service.plugin().getLogger().log(Level.WARNING, "Nie udało się zapisać ikony flagi na mapie", e);
                }
            });
            final String v = version.length() > 8 ? version.substring(0, 8) : version;
            this.html.put(c.id(), "<img src=\"" + DIR + "/" + fileName(c.id()) + "?v=" + v + "\" width=\"64\" height=\"40\" alt=\"\""
                + " style=\"float:right;margin:0 0 4px 8px;image-rendering:pixelated;border:1px solid rgba(0,0,0,.45)\">");
            changed = true;
        }
        for (final UUID gone : new HashSet<>(this.written.keySet())) {
            if (!seen.contains(gone)) {
                this.written.remove(gone);
                this.html.remove(gone);
                final Path target = this.dir.resolve(fileName(gone));
                this.service.plugin().getServer().getScheduler().runTaskAsynchronously(this.service.plugin(), () -> {
                    try {
                        Files.deleteIfExists(target);
                    } catch (final IOException ignored) {
                        // stale icon of a disbanded country: harmless
                    }
                });
            }
        }
        return changed;
    }

    /** Thread-safe popup snippet ({@code ""} until the icon is written). */
    String html(final UUID country) {
        return this.html.getOrDefault(country, "");
    }
}
