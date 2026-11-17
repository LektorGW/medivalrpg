// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.logic.WorkAlerts;
import pl.medivalrpg.ludnosc.model.Building;

/** Observes existing work failures and available food. Never mutates inventories, economy or chunks. */
public final class WorkAlertService {
    private final LudnoscPlugin plugin;
    private final WorkAlerts alerts = new WorkAlerts();
    private final ArrayDeque<UUID> countries = new ArrayDeque<>();
    private final Map<UUID, DeliveryWindow> recipients = new HashMap<>();
    static final class DeliveryWindow {
        private long start;
        private int count;
        DeliveryWindow(long now) { start = now; }
        boolean acquire(long now) {
            if (now - start >= 60_000) { start = now; count = 0; }
            if (count >= 3) return false;
            count++; return true;
        }
    }
    public WorkAlertService(LudnoscPlugin plugin) { this.plugin = plugin; }

    public void blocked(Building b, WorkAlerts.Reason reason) {
        if (enabled()) alerts.observe(key(b), reason, label(b), System.currentTimeMillis());
    }
    public void recovered(Building b) { alerts.recover(key(b), System.currentTimeMillis()); }
    public void forget(Building b) { alerts.forget(key(b)); }
    private static WorkAlerts.Key key(Building b) { return new WorkAlerts.Key(b.countryId(), b.id()); }
    private boolean enabled() { return plugin.getConfig().getBoolean("alerts.enabled", true); }
    private static String label(Building b) {
        String name = switch (b.function()) {
            case MINE -> "Kopalnia"; case QUARRY -> "Kamieniołom"; case FARM -> "Pole";
            case HARBOR -> "Przystań"; case SAWMILL -> "Tartak"; default -> "Miejsce pracy";
        };
        return name + " " + b.id().toString().substring(0, 8) + " (" + b.box().world() + ": "
            + b.box().centerX() + ", " + b.box().minY() + ", " + b.box().centerZ() + ")";
    }

    /** Once a second: one country's food, at most 32 incidents, at most three lines/player/minute. */
    public void tick() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Work alerts require main thread");
        if (!enabled()) { alerts.clear(); countries.clear(); recipients.clear(); return; }
        long now = System.currentTimeMillis();
        recipients.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        if (countries.isEmpty()) plugin.kraje().getCountries().forEach(k -> countries.addLast(k.id()));
        if (!countries.isEmpty()) checkFood(countries.removeFirst(), now);
        long cooldown = Math.max(60, Math.min(3600, plugin.getConfig().getLong("alerts.cooldown-seconds", 600))) * 1000;
        Set<UUID> managers = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("alerts.npc-manager-uuids")) {
            try { managers.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        alerts.dispatch(now, cooldown, 32, notice -> deliver(notice, now, managers));
    }

    private void checkFood(UUID country, long now) {
        WorkAlerts.Key key = new WorkAlerts.Key(country, null);
        if (plugin.kraje().getCountry(country).isEmpty() || plugin.data().population(country) == 0) {
            alerts.forget(key); return;
        }
        if (plugin.royalRebuild() != null && plugin.royalRebuild().blocks(country)) { alerts.forget(key); return; }
        var reserve = plugin.production().foodReserve(country);
        if (reserve == null) { alerts.forget(key); return; } // scan limit: no assertion about unknown supplies
        if (reserve.sufficient()) alerts.recover(key, now);
        else alerts.observe(key, WorkAlerts.Reason.FOOD, reserve.available() + "/" + reserve.required(), now);
    }

    /** Pure recipient rule, also handles a shared ruler who belongs to another country. */
    public static boolean responsible(KrajInfo country, UUID player, Set<UUID> npcManagers) {
        if (country.npc()) return npcManagers.contains(player);
        Rank rank = country.rankOf(player);
        return player.equals(country.ruler()) || rank != null && rank.atLeast(Rank.ADVISOR);
    }

    private boolean deliver(WorkAlerts.Notice notice, long now, Set<UUID> managers) {
        KrajInfo country = plugin.kraje().getCountry(notice.key().country()).orElse(null);
        if (country == null) { alerts.forget(notice.key()); return false; }
        if (plugin.royalRebuild() != null && plugin.royalRebuild().blocks(country.id())) return false;
        if (notice.key().workplace() != null) {
            Building b = plugin.data().building(notice.key().workplace());
            if (b == null || !country.id().equals(b.countryId())) { alerts.forget(notice.key()); return false; }
        }
        String message = notice.recovery() ? notice.reason() == WorkAlerts.Reason.FOOD ? "alert-food-recovered" : "alert-work-recovered"
            : switch (notice.reason()) {
                case FULL -> "alert-storage-full"; case TORCHES -> "alert-torches";
                case MISSING_STORE -> "alert-storage-missing"; case UNAVAILABLE_STORE -> "alert-storage-unavailable";
                case FOOD -> "alert-food";
            };
        if (plugin.messages().raw(message).isEmpty()) return false;
        boolean sent = false;
        for (var player : Bukkit.getOnlinePlayers()) {
            if (!responsible(country, player.getUniqueId(), managers)) continue;
            if (!recipients.computeIfAbsent(player.getUniqueId(), id -> new DeliveryWindow(now)).acquire(now)) continue;
            plugin.messages().send(player, message, Placeholder.unparsed("country", country.name()),
                Placeholder.unparsed("detail", notice.detail()));
            sent = true;
        }
        return sent;
    }
}
