// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;

/** Snapshot bridge avoids resolving Ludnosc classes while Kraje starts before its provider. Main thread. */
final class SettlementMapData {
    record Place(String name, String function, String world, int x, int z, int percent) { }
    record Centre(String world, int x, int z, boolean capital) { }
    record Road(String world, List<xyz.jpenilla.squaremap.api.Point> points, boolean planned) { }
    record Summary(int level, String name, int population, int housing, List<Place> places,
                   List<Centre> centres, List<Road> roads) { }

    private SettlementMapData() { }

    static Optional<Summary> read(final UUID country) {
        for (final Class<?> type : Bukkit.getServicesManager().getKnownServices()) {
            if (!type.getName().equals("pl.medivalrpg.ludnosc.api.LudnoscApi")) continue;
            try {
                final Object provider = Bukkit.getServicesManager().load(type);
                if (provider == null) return Optional.empty();
                final Object optional = type.getMethod("info", UUID.class).invoke(provider, country);
                if (!(optional instanceof Optional<?> result) || result.isEmpty()) return Optional.empty();
                final Object info = result.get();
                final List<Place> places = new ArrayList<>();
                for (final Object b : (List<?>) call(info, "buildings")) {
                    places.add(new Place((String) call(b, "name"), (String) call(b, "function"),
                        (String) call(b, "world"), (int) call(b, "x"), (int) call(b, "z"), 100));
                }
                for (final Object s : (List<?>) call(info, "sites")) {
                    places.add(new Place((String) call(s, "name"), "Budowa", (String) call(s, "world"),
                        (int) call(s, "x"), (int) call(s, "z"), (int) call(s, "percent")));
                }
                int level = 0;
                String name = "Osada";
                try {
                    final Object tierValue = type.getMethod("settlementTier", UUID.class).invoke(provider, country);
                    if (tierValue instanceof Optional<?> tier && tier.isPresent()) {
                        level = (int) call(tier.get(), "level");
                        name = (String) call(tier.get(), "name");
                    }
                } catch (final ReflectiveOperationException ignored) { }
                final List<Centre> centres = new ArrayList<>();
                final List<Road> roads = new ArrayList<>();
                try {
                    final Object mapOptional = type.getMethod("kingdomMap", UUID.class).invoke(provider, country);
                    if (mapOptional instanceof Optional<?> kingdom && kingdom.isPresent()) {
                        final Object map = kingdom.get();
                        for (final Object centre : (List<?>) call(map, "settlements")) {
                            if (centres.size() >= 64) break;
                            centres.add(new Centre((String) call(centre, "world"), (int) call(centre, "x"),
                                (int) call(centre, "z"), (boolean) call(centre, "capital")));
                        }
                        for (final Object road : (List<?>) call(map, "roads")) {
                            if (roads.size() >= 288) break;
                            final List<xyz.jpenilla.squaremap.api.Point> points = new ArrayList<>();
                            for (final Object point : (List<?>) call(road, "points")) {
                                if (points.size() >= 128) break;
                                points.add(xyz.jpenilla.squaremap.api.Point.of((int) call(point, "x") + 0.5,
                                    (int) call(point, "z") + 0.5));
                            }
                            if (points.size() >= 2) roads.add(new Road((String) call(road, "world"), List.copyOf(points),
                                (boolean) call(road, "planned")));
                        }
                    }
                } catch (final ReflectiveOperationException ignored) { /* older Ludnosc */ }
                return Optional.of(new Summary(level, name, (int) call(info, "population"),
                    (int) call(info, "housing"), List.copyOf(places), List.copyOf(centres), List.copyOf(roads)));
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Object call(final Object value, final String method) throws ReflectiveOperationException {
        return value.getClass().getMethod(method).invoke(value);
    }
}
