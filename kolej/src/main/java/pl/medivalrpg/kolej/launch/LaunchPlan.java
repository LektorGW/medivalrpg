// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.launch;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.data.RailData.Car;
import pl.medivalrpg.kolej.schedule.Timetable;

/**
 * A manager-approved, one-command line launch (file {@code uruchomienie.yml}): rolling stock bought with real
 * money from the carrier's account, NPC crews with daily wages, real water/coal containers, departure boards and a
 * timetable. Pure data and validation; the Bukkit side ({@code LineLaunch}) performs it only after physical acceptance.
 */
public record LaunchPlan(String id, String name, LineSpec line, List<TrainSpec> trains, List<FixtureSpec> fixtures, List<BoardSpec> boards) {
    public record LineSpec(String id, List<String> stops, long fare, Timetable timetable, int returnAfterMinutes) { }
    public record TrainSpec(String id, String station, List<Car> cars, String driver, String stoker) { }
    public record FixtureSpec(String id, String kind, String station, int x, int y, int z, int rotation) { }
    public record BoardSpec(String id, String station, int x, int y, int z, int rotation) { }
    public record Prices(long locomotive, long car, long hire, long driverWage, long stokerWage) { }
    public record Costs(long locomotives, long cars, long hires, long firstWages) {
        public long total() {return Math.addExact(Math.addExact(locomotives, cars), Math.addExact(hires, firstWages));}
    }

    public static Map<String, LaunchPlan> parseAll(ConfigurationSection root, int maxCars) {
        Map<String, LaunchPlan> plans = new LinkedHashMap<>();
        ConfigurationSection section = root == null ? null : root.getConfigurationSection("plany");
        if (section == null) return plans;
        for (String key : section.getKeys(false)) plans.put(key, parse(key, section.getConfigurationSection(key), maxCars));
        return plans;
    }

    static String id(String value, String what) {
        if (!RailData.identifier(value)) throw new IllegalArgumentException("Nieprawidłowy identyfikator " + what + ": " + value);
        return value;
    }

    static String name(String value, String what) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 32 || !value.matches("[\\p{L}0-9 .'-]+")) throw new IllegalArgumentException("Nieprawidłowe imię " + what + ": " + value);
        return value.trim();
    }

    public static LaunchPlan parse(String id, ConfigurationSection s, int maxCars) {
        if (s == null) throw new IllegalArgumentException("Pusty plan " + id);
        id(id, "planu");
        ConfigurationSection l = s.getConfigurationSection("linia");
        if (l == null) throw new IllegalArgumentException("Plan " + id + " nie ma sekcji linia");
        List<String> stops = new ArrayList<>();
        for (String stop : l.getStringList("przystanki")) stops.add(id(stop, "przystanku"));
        if (stops.size() < 2 || new HashSet<>(stops).size() != stops.size()) throw new IllegalArgumentException("Linia wymaga co najmniej dwóch różnych przystanków");
        long fare = l.getLong("bilet", -1);
        if (fare < 0 || fare > 10000) throw new IllegalArgumentException("Cena biletu 0–10000 md");
        Timetable timetable = timetable(l.getString("rozklad", ""), l.getString("od", "06:00"), l.getString("do", "22:00"));
        int back = l.getInt("powrot-po-minutach", 0);
        if (back < 0 || back > 720) throw new IllegalArgumentException("powrot-po-minutach 0–720");
        LineSpec line = new LineSpec(id(l.getString("id"), "linii"), List.copyOf(stops), fare, timetable, back);

        List<TrainSpec> trains = new ArrayList<>();
        Set<String> ids = new HashSet<>(), crew = new HashSet<>();
        for (Map<?, ?> t : s.getMapList("pociagi")) {
            String trainId = id(String.valueOf(t.get("id")), "pociągu");
            if (!ids.add(trainId)) throw new IllegalArgumentException("Powtórzony pociąg " + trainId);
            String station = id(String.valueOf(t.get("stacja")), "stacji pociągu");
            List<Car> cars = new ArrayList<>();
            Object raw = t.get("wagony");
            if (raw instanceof List<?> list) for (Object car : list) {
                try {cars.add(Car.parse(String.valueOf(car)));} catch (IllegalArgumentException e) {throw new IllegalArgumentException("Nieznany wagon: " + car);}
            }
            if (cars.size() > maxCars) throw new IllegalArgumentException("Pociąg " + trainId + " ma więcej niż " + maxCars + " wagonów");
            String driver = name(t.get("maszynista") == null ? null : String.valueOf(t.get("maszynista")), "maszynisty");
            String stoker = name(t.get("palacz") == null ? null : String.valueOf(t.get("palacz")), "palacza");
            if (driver == null) throw new IllegalArgumentException("Pociąg " + trainId + " wymaga maszynisty NPC");
            if (!crew.add(driver) || stoker != null && !crew.add(stoker)) throw new IllegalArgumentException("Imiona załogi muszą być różne");
            trains.add(new TrainSpec(trainId, station, List.copyOf(cars), driver, stoker));
        }
        if (trains.isEmpty()) throw new IllegalArgumentException("Plan " + id + " nie kupuje żadnego składu");

        List<FixtureSpec> fixtures = new ArrayList<>();
        Set<String> fixtureIds = new HashSet<>();
        for (Map<?, ?> f : s.getMapList("infrastruktura")) {
            String fid = id(String.valueOf(f.get("id")), "stanowiska");
            String kind = String.valueOf(f.get("typ")).toLowerCase(Locale.ROOT);
            if (!Set.of("zuraw", "wieza", "pompa", "zbiornik", "wegiel").contains(kind)) throw new IllegalArgumentException("Typ stanowiska: zuraw|wieza|pompa|zbiornik|wegiel");
            if (!fixtureIds.add(fid)) throw new IllegalArgumentException("Powtórzone stanowisko " + fid);
            fixtures.add(new FixtureSpec(fid, kind, id(String.valueOf(f.get("stacja")), "stacji stanowiska"), integer(f, "x"), integer(f, "y"), integer(f, "z"), rotation(f)));
        }
        for (String stop : List.of(stops.getFirst(), stops.getLast())) {
            boolean water = fixtures.stream().anyMatch(f -> f.station().equals(stop) && !f.kind().equals("wegiel"));
            boolean coal = fixtures.stream().anyMatch(f -> f.station().equals(stop) && f.kind().equals("wegiel"));
            if (!water || !coal) throw new IllegalArgumentException("Stacja końcowa " + stop + " wymaga stanowiska wody i węgla");
        }
        List<BoardSpec> boards = new ArrayList<>();
        Set<String> boardIds = new HashSet<>();
        for (Map<?, ?> b : s.getMapList("tablice")) {
            String bid = id(String.valueOf(b.get("id")), "tablicy");
            if (!boardIds.add(bid)) throw new IllegalArgumentException("Powtórzona tablica " + bid);
            boards.add(new BoardSpec(bid, id(String.valueOf(b.get("stacja")), "stacji tablicy"), integer(b, "x"), integer(b, "y"), integer(b, "z"), rotation(b)));
        }
        String name = s.getString("nazwa", id);
        return new LaunchPlan(id, name, line, List.copyOf(trains), List.copyOf(fixtures), List.copyOf(boards));
    }

    static int integer(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || Math.abs(n.longValue()) > 30_000_000) throw new IllegalArgumentException("Brak liczby całkowitej " + key);
        return n.intValue();
    }

    static int rotation(Map<?, ?> m) {
        int r = m.get("obrot") == null ? 0 : integer(m, "obrot");
        if (!Set.of(0, 90, 180, 270).contains(r)) throw new IllegalArgumentException("Obrót: 0|90|180|270");
        return r;
    }

    /** {@code "60"} = every 60 minutes between {@code od} and {@code do}; {@code "07:00,09:30"} = fixed times. */
    public static Timetable timetable(String value, String from, String to) {
        value = value == null ? "" : value.trim();
        if (value.matches("[0-9]{1,4}")) {
            int every = Integer.parseInt(value), a = Timetable.parseTime(from), b = Timetable.parseTime(to);
            if (every < 10 || every > 1440 || a < 0 || b < a) throw new IllegalArgumentException("Rozkład: odstęp 10–1440 min i godziny od ≤ do");
            return Timetable.every(every, a, b);
        }
        List<Integer> times = new ArrayList<>();
        for (String t : value.split(",")) {
            int m = Timetable.parseTime(t);
            if (m < 0) throw new IllegalArgumentException("Rozkład: liczba minut albo godziny HH:MM,HH:MM");
            times.add(m);
        }
        if (times.isEmpty()) throw new IllegalArgumentException("Pusty rozkład");
        return Timetable.at(times);
    }

    /** Every paid item, as charged: locomotive, each car, hire of each NPC and its first daily wage. */
    public Costs costs(Prices p) {
        long locos = 0, cars = 0, hires = 0, wages = 0;
        for (TrainSpec t : trains) {
            locos = Math.addExact(locos, p.locomotive());
            cars = Math.addExact(cars, Math.multiplyExact(p.car(), t.cars().size()));
            hires = Math.addExact(hires, p.hire());
            wages = Math.addExact(wages, p.driverWage());
            if (t.stoker() != null) {hires = Math.addExact(hires, p.hire());wages = Math.addExact(wages, p.stokerWage());}
        }
        return new Costs(locos, cars, hires, wages);
    }

    /** Deterministic crew ids, so a repeated launch never hires twice. */
    public static String crewId(String train, boolean stoker) {return "L-" + train + (stoker ? "-p" : "-m");}

    /**
     * Minimum interval between origin departures for one shuttle: there and back plus two terminal dwells and the
     * warm-up margin. Used to refuse a timetable that a single train could never keep.
     */
    public static int minimumHeadwayMinutes(int oneWayMinutes, int returnAfterMinutes) {
        return Math.max(returnAfterMinutes, oneWayMinutes + 2) + oneWayMinutes + 2;
    }
}
