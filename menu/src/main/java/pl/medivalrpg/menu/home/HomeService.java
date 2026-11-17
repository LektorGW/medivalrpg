// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.home;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.config.Messages;
import pl.medivalrpg.menu.integration.KrajeHook;
import pl.medivalrpg.menu.teleport.StoredLocation;
import pl.medivalrpg.menu.teleport.TeleportService;

/** {@code /ustawdom}, {@code /dom}, {@code /usundom}, {@code /domy}. Main thread only. */
public final class HomeService {

    public static final String KIND = "home";
    public static final String PERM_USE = "menu.homes";
    public static final String PERM_BYPASS = "menu.homes.bypass";

    private final Supplier<MenuSettings> settings;
    private final Supplier<Messages> messages;
    private final HomeStore store;
    private final KrajeHook kraje;
    private final TeleportService teleports;

    public HomeService(final Supplier<MenuSettings> settings, final Supplier<Messages> messages, final HomeStore store,
                       final KrajeHook kraje, final TeleportService teleports) {
        this.settings = settings;
        this.messages = messages;
        this.store = store;
        this.kraje = kraje;
        this.teleports = teleports;
    }

    public HomeStore store() {
        return this.store;
    }

    private boolean enabled(final Player player) {
        if (!this.settings.get().homesEnabled) {
            player.sendMessage(this.messages.get().get("home-disabled"));
            return false;
        }
        return true;
    }

    private static TagResolver name(final String name) {
        return Placeholder.unparsed("name", name);
    }

    /** Land of a location relative to the player; WILD when Kraje is missing. */
    private HomeRules.Land land(final Player player, final StoredLocation loc) {
        return this.kraje.territory(player.getUniqueId(), loc.world(), loc.chunkX(), loc.chunkZ())
            .map(t -> HomeRules.land(t.landOwner(), t.playerCountry()))
            .orElse(HomeRules.Land.WILD);
    }

    public void set(final Player player, final String rawName) {
        if (!this.enabled(player)) {
            return;
        }
        final Optional<String> normalized = HomeRules.normalizeName(rawName);
        if (normalized.isEmpty()) {
            player.sendMessage(this.messages.get().get("home-bad-name",
                Placeholder.unparsed("max", Integer.toString(HomeRules.MAX_NAME_LENGTH))));
            return;
        }
        final String name = normalized.get();
        final MenuSettings s = this.settings.get();
        final StoredLocation here = StoredLocation.of(player.getLocation());
        if (!HomeRules.allowed(this.land(player, here), s.homesOnlyOwnOrWild)
            && !player.hasPermission(PERM_BYPASS)) {
            player.sendMessage(this.messages.get().get("home-foreign-land"));
            return;
        }
        final Map<String, StoredLocation> homes = this.store.homes(player.getUniqueId());
        final boolean exists = homes.containsKey(name);
        final int limit = HomeRules.limit(s.homesMax, player::hasPermission);
        if (!HomeRules.canSet(homes.size(), exists, limit)) {
            player.sendMessage(this.messages.get().get("home-limit",
                Placeholder.unparsed("limit", Integer.toString(limit))));
            return;
        }
        this.store.set(player.getUniqueId(), name, here);
        player.sendMessage(this.messages.get().get(exists ? "home-updated" : "home-set", name(name),
            Placeholder.unparsed("location", here.describe())));
    }

    public void teleport(final Player player, final String rawName) {
        if (!this.enabled(player)) {
            return;
        }
        final Map<String, StoredLocation> homes = this.store.homes(player.getUniqueId());
        final String name;
        if (rawName == null || rawName.isBlank()) {
            if (homes.isEmpty()) {
                player.sendMessage(this.messages.get().get("home-none"));
                return;
            }
            if (homes.containsKey(HomeRules.DEFAULT_NAME)) {
                name = HomeRules.DEFAULT_NAME;
            } else if (homes.size() == 1) {
                name = homes.keySet().iterator().next();
            } else {
                this.list(player);
                return;
            }
        } else {
            name = HomeRules.normalizeName(rawName).orElse(rawName);
        }
        if (!homes.containsKey(name)) {
            player.sendMessage(this.messages.get().get("home-unknown", name(name)));
            return;
        }
        final MenuSettings s = this.settings.get();
        this.teleports.request(player, new TeleportService.Kind(KIND, s.homesWarmupSeconds, s.homesCooldownSeconds,
            PERM_BYPASS), p -> this.resolve(p, name), this.messages.get().get("home-arrived", name(name)));
    }

    /** Destination of a home, checked again when the countdown ends (deleted, world gone, land conquered). */
    private TeleportService.Target resolve(final Player player, final String name) {
        final Optional<StoredLocation> home = this.store.get(player.getUniqueId(), name);
        if (home.isEmpty()) {
            return new TeleportService.Target.Denied(this.messages.get().get("home-unknown", name(name)));
        }
        final Optional<Location> loc = home.get().toLocation();
        if (loc.isEmpty()) {
            return new TeleportService.Target.Denied(this.messages.get().get("home-world-missing", name(name)));
        }
        if (!HomeRules.allowed(this.land(player, home.get()), this.settings.get().homesOnlyOwnOrWild)
            && !player.hasPermission(PERM_BYPASS)) {
            return new TeleportService.Target.Denied(this.messages.get().get("home-foreign-blocked", name(name)));
        }
        return new TeleportService.Target.Ok(loc.get());
    }

    public void delete(final Player player, final String rawName) {
        final String name = HomeRules.normalizeName(rawName).orElse(rawName == null ? "" : rawName);
        player.sendMessage(this.messages.get().get(this.store.remove(player.getUniqueId(), name)
            ? "home-deleted" : "home-unknown", name(name)));
    }

    public void list(final Player player) {
        if (!this.enabled(player)) {
            return;
        }
        final Map<String, StoredLocation> homes = this.store.homes(player.getUniqueId());
        if (homes.isEmpty()) {
            player.sendMessage(this.messages.get().get("home-none"));
            return;
        }
        final int limit = HomeRules.limit(this.settings.get().homesMax, player::hasPermission);
        final Messages m = this.messages.get();
        player.sendMessage(m.get("homes-header",
            Placeholder.unparsed("count", Integer.toString(homes.size())),
            Placeholder.unparsed("limit", limit == HomeRules.UNLIMITED ? "∞" : Integer.toString(limit))));
        for (final Map.Entry<String, StoredLocation> home : homes.entrySet()) {
            final TagResolver r = TagResolver.resolver(name(home.getKey()),
                Placeholder.unparsed("location", home.getValue().describe()));
            player.sendMessage(m.get("homes-line", r)
                .clickEvent(ClickEvent.runCommand("/dom " + home.getKey()))
                .hoverEvent(HoverEvent.showText(m.get("homes-hover", r))));
        }
    }

    /** For tab completion (any thread: the store hands out immutable maps). */
    public Iterable<String> names(final Player player) {
        return this.store.homes(player.getUniqueId()).keySet();
    }
}
