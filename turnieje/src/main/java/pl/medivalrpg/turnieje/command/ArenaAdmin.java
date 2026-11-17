// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.command;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.turnieje.arena.Arena;
import pl.medivalrpg.turnieje.arena.ArenaType;
import pl.medivalrpg.turnieje.arena.Box;
import pl.medivalrpg.turnieje.arena.GearMode;
import pl.medivalrpg.turnieje.arena.Pos;
import pl.medivalrpg.turnieje.arena.WorldEditSelection;
import pl.medivalrpg.turnieje.config.Messages;
import pl.medivalrpg.turnieje.data.Storage;
import pl.medivalrpg.turnieje.game.SessionManager;
import pl.medivalrpg.turnieje.game.Texts;
import pl.medivalrpg.turnieje.integration.Countries;
import pl.medivalrpg.turnieje.integration.Services;

import static pl.medivalrpg.turnieje.config.Messages.p;

/** {@code /turniej arena …}: defining arenas (WorldEdit selection or two corners), spawns, stand, owner, joust, gear. */
final class ArenaAdmin {

    /** Largest arena (blocks), a safety net against a mistaken selection. */
    static final long MAX_VOLUME = 2_000_000L;

    private final Storage storage;
    private final SessionManager sessions;
    private final Services services;
    private final Map<UUID, Location[]> corners = new HashMap<>();

    ArenaAdmin(final Storage storage, final SessionManager sessions, final Services services) {
        this.storage = storage;
        this.sessions = sessions;
        this.services = services;
    }

    private Messages msg() {
        return this.sessions.messages();
    }

    void corner(final Player p, final int n) {
        final Location[] c = this.corners.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        final Location l = p.getLocation().getBlock().getLocation();
        c[n - 1] = l;
        this.msg().send(p, "arena-corner-set", p("n", n), p("x", l.getBlockX()), p("y", l.getBlockY()), p("z", l.getBlockZ()));
    }

    void create(final Player p, final String rawId, final String rawType) {
        final String id = rawId.toLowerCase(Locale.ROOT);
        if (!Texts.validId(id)) {
            this.msg().send(p, "arena-invalid-id");
            return;
        }
        if (this.storage.arenas().containsKey(id)) {
            this.msg().send(p, "arena-exists", p("arena", id));
            return;
        }
        final Optional<ArenaType> type = ArenaType.parse(rawType);
        if (type.isEmpty()) {
            this.msg().send(p, "arena-invalid-type");
            return;
        }
        Box box = null;
        if (Bukkit.getPluginManager().isPluginEnabled(WorldEditSelection.PLUGIN)) {
            try {
                box = WorldEditSelection.box(p).orElse(null);
            } catch (final LinkageError e) {
                box = null;
            }
        }
        if (box == null) {
            final Location[] c = this.corners.get(p.getUniqueId());
            if (c == null || c[0] == null || c[1] == null) {
                this.msg().send(p, "arena-no-selection");
                return;
            }
            if (!c[0].getWorld().equals(c[1].getWorld())) {
                this.msg().send(p, "arena-wrong-world");
                return;
            }
            box = Box.of(c[0].getWorld().getName(), c[0].getBlockX(), c[0].getBlockY(), c[0].getBlockZ(),
                c[1].getBlockX(), c[1].getBlockY(), c[1].getBlockZ());
        }
        if (box.volume() > MAX_VOLUME) {
            this.msg().send(p, "arena-too-big", p("max", MAX_VOLUME));
            return;
        }
        final Arena arena = new Arena(id, type.get(), box);
        this.storage.arenas().put(id, arena);
        this.storage.saveArenas();
        this.corners.remove(p.getUniqueId());
        this.msg().send(p, "arena-created", p("arena", id), p("type", this.msg().plain("type." + type.get().id())),
            p("size", (box.maxX() - box.minX() + 1) + "×" + (box.maxY() - box.minY() + 1) + "×" + (box.maxZ() - box.minZ() + 1)));
    }

    private Arena arena(final CommandSender s, final String id) {
        final Arena a = this.storage.arenas().get(id.toLowerCase(Locale.ROOT));
        if (a == null) {
            this.msg().send(s, "unknown-arena", p("arena", id));
        }
        return a;
    }

    private boolean free(final CommandSender s, final Arena a) {
        if (this.sessions.sessionOn(a.id) != null) {
            this.msg().send(s, "arena-busy", p("arena", a.id));
            return false;
        }
        return true;
    }

    void remove(final CommandSender s, final String id) {
        final Arena a = this.arena(s, id);
        if (a == null || !this.free(s, a)) {
            return;
        }
        this.storage.arenas().remove(a.id);
        this.storage.saveArenas();
        this.msg().send(s, "arena-removed", p("arena", a.id));
    }

    void list(final CommandSender s) {
        if (this.storage.arenas().isEmpty()) {
            this.msg().send(s, "arena-list-empty");
            return;
        }
        this.msg().send(s, "arena-list-header", p("count", this.storage.arenas().size()));
        final Countries countries = this.services.countries();
        for (final Arena a : this.storage.arenas().values()) {
            final String country = a.country == null ? this.msg().plain("arena-public")
                : countries.byId(a.country).map(Countries.Country::name).orElse("?");
            final String status = this.sessions.sessionOn(a.id) != null ? this.msg().plain("arena-status-busy")
                : a.ready() ? this.msg().plain("arena-status-ready") : this.msg().plain("arena-status-incomplete");
            this.msg().send(s, "arena-list-line", p("arena", a.id), p("type", this.msg().plain("type." + a.type.id())),
                p("world", a.box.world()), p("spawns", a.spawns.size()), p("country", country),
                p("mounted", this.msg().plain(a.mounted ? "word-yes" : "word-no")),
                p("gear", a.gear == null ? this.msg().plain("gear-default") : this.msg().plain("gear." + a.gear.id())),
                p("status", status), p("x", (int) a.box.centerX()), p("z", (int) a.box.centerZ()));
        }
    }

    void addSpawn(final Player p, final String id) {
        final Arena a = this.arena(p, id);
        if (a == null || !this.free(p, a)) {
            return;
        }
        final Location l = p.getLocation();
        if (!a.box.contains(l.getWorld().getName(), l.getX(), l.getY(), l.getZ())) {
            this.msg().send(p, "arena-spawn-outside");
            return;
        }
        a.spawns.add(Pos.of(l));
        this.storage.saveArenas();
        this.msg().send(p, "arena-spawn-added", p("arena", a.id), p("n", a.spawns.size()));
    }

    void clearSpawns(final CommandSender s, final String id) {
        final Arena a = this.arena(s, id);
        if (a == null || !this.free(s, a)) {
            return;
        }
        a.spawns.clear();
        this.storage.saveArenas();
        this.msg().send(s, "arena-spawn-cleared", p("arena", a.id));
    }

    void setSpectators(final Player p, final String id) {
        final Arena a = this.arena(p, id);
        if (a == null) {
            return;
        }
        a.spectators = Pos.of(p.getLocation());
        this.storage.saveArenas();
        this.msg().send(p, "arena-spectators-set", p("arena", a.id));
    }

    void setCountry(final CommandSender s, final String id, final String country) {
        final Arena a = this.arena(s, id);
        if (a == null) {
            return;
        }
        final String norm = Texts.normalize(country);
        if (norm.equals("publiczna") || norm.equals("brak") || norm.equals("-")) {
            a.country = null;
            this.storage.saveArenas();
            this.msg().send(s, "arena-country-public", p("arena", a.id));
            return;
        }
        final Optional<Countries.Country> c = this.services.countries().byName(country);
        if (c.isEmpty()) {
            this.msg().send(s, "arena-country-unknown", p("country", country));
            return;
        }
        a.country = c.get().id();
        this.storage.saveArenas();
        this.msg().send(s, "arena-country-set", p("arena", a.id), p("country", c.get().name()));
    }

    void setMounted(final CommandSender s, final String id, final boolean mounted) {
        final Arena a = this.arena(s, id);
        if (a == null || !this.free(s, a)) {
            return;
        }
        if (mounted && a.type != ArenaType.TURNIEJ) {
            this.msg().send(s, "arena-mounted-type");
            return;
        }
        a.mounted = mounted;
        this.storage.saveArenas();
        this.msg().send(s, "arena-mounted-set", p("arena", a.id), p("value", this.msg().plain(mounted ? "word-yes" : "word-no")));
    }

    void setGear(final CommandSender s, final String id, final String raw) {
        final Arena a = this.arena(s, id);
        if (a == null || !this.free(s, a)) {
            return;
        }
        if (Texts.normalize(raw).equals("domyslny")) {
            a.gear = null;
        } else {
            final Optional<GearMode> g = GearMode.parse(raw);
            if (g.isEmpty()) {
                this.msg().send(s, "arena-gear-invalid");
                return;
            }
            a.gear = g.get();
        }
        this.storage.saveArenas();
        this.msg().send(s, "arena-gear-set", p("arena", a.id),
            p("gear", a.gear == null ? this.msg().plain("gear-default") : this.msg().plain("gear." + a.gear.id())));
    }
}
