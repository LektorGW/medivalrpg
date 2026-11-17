// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.model.QueuedOrder;
import pl.medivalrpg.ludnosc.service.LudnoscData;

/** JSON form of {@link LudnoscData} (Gson bundled with Paper). Pure, unit tested. */
public final class DataCodec {

    public static final int VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private DataCodec() {
    }

    /** File content. */
    public record Snapshot(int version, String lastDaily, List<CitizenRecord> citizens, List<Building> buildings,
                           List<ConstructionSite> sites, Map<UUID, CountryState> countries, Map<UUID, Long> tombstones,
                           List<QueuedOrder> queue, Long seasonAnchor, Long seasonSerial) {

        /** Snapshot written before seasons existed. */
        public Snapshot(final int version, final String lastDaily, final List<CitizenRecord> citizens, final List<Building> buildings,
                        final List<ConstructionSite> sites, final Map<UUID, CountryState> countries, final Map<UUID, Long> tombstones,
                        final List<QueuedOrder> queue) {
            this(version, lastDaily, citizens, buildings, sites, countries, tombstones, queue, null, null);
        }
    }

    public static String encode(final LudnoscData data) {
        final Snapshot s = new Snapshot(VERSION, data.lastDaily() == null ? null : data.lastDaily().toString(),
            new ArrayList<>(data.allCitizens()), new ArrayList<>(data.allBuildings()), new ArrayList<>(data.allSites()),
            new LinkedHashMap<>(data.states()), new LinkedHashMap<>(data.tombstones()), data.allQueued(),
            data.seasonAnchor() == 0 ? null : data.seasonAnchor(),
            data.seasonSerial() == Long.MIN_VALUE ? null : data.seasonSerial());
        return GSON.toJson(s);
    }

    public static Snapshot decode(final String json) {
        final Snapshot s = GSON.fromJson(json, Snapshot.class);
        if (s == null) {
            throw new IllegalArgumentException("Pusty plik danych");
        }
        return s;
    }

    /** Replaces the content of {@code data} with the snapshot (skipping broken entries). */
    public static void apply(final Snapshot s, final LudnoscData data) {
        data.clear();
        if (s.citizens() != null) {
            for (final CitizenRecord r : s.citizens()) {
                if (r != null && r.entityId() != null && r.countryId() != null) {
                    data.putCitizen(r);
                }
            }
        }
        if (s.buildings() != null) {
            for (final Building b : s.buildings()) {
                if (b != null && b.id() != null && b.box() != null) {
                    data.putBuilding(b);
                }
            }
        }
        if (s.sites() != null) {
            for (final ConstructionSite site : s.sites()) {
                if (site != null && site.id != null && site.countryId != null && site.type != null && site.world != null) {
                    data.putSite(site);
                }
            }
        }
        // "queue" is absent in data.json written before the construction queue existed (version 1) → empty queue
        if (s.queue() != null) {
            for (final QueuedOrder o : s.queue()) {
                if (o != null && o.id != null && o.countryId != null && o.type != null && o.world != null) {
                    data.enqueue(o);
                }
            }
        }
        if (s.countries() != null) {
            s.countries().forEach((id, state) -> {
                if (id != null && state != null) {
                    data.states().put(id, state);
                }
            });
        }
        if (s.tombstones() != null) {
            data.tombstones().putAll(s.tombstones());
        }
        if (s.lastDaily() != null && !s.lastDaily().isBlank()) {
            data.setLastDaily(LocalDate.parse(s.lastDaily()));
        }
        if (s.seasonAnchor() != null) {
            data.setSeasonAnchor(s.seasonAnchor());
        }
        if (s.seasonSerial() != null) {
            data.setSeasonSerial(s.seasonSerial());
        }
        data.takeDirty();
    }
}
