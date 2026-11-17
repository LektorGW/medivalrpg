// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.data;

import java.util.*;
import pl.medivalrpg.kolej.network.*;
import pl.medivalrpg.kolej.schedule.Timetable;
import pl.medivalrpg.kolej.steam.*;

/** Durable company ledger. Entity UUIDs are deliberately excluded: visuals are reconstructed. */
public final class RailData {
    public UUID ownerCountry;
    public String companyName = "Królewska Kolej";
    public List<TrackNode> nodes = new ArrayList<>();
    public List<TrackSegment> segments = new ArrayList<>();
    public Map<String, Train> trains = new LinkedHashMap<>();
    public Map<String, Line> lines = new LinkedHashMap<>();
    public Map<UUID, String> drivers = new LinkedHashMap<>();
    public Map<String, Crew> crews = new LinkedHashMap<>();
    public Set<UUID> permits = new HashSet<>();
    public boolean privateCarrierPermits;
    public Map<String,Depot> depots = new LinkedHashMap<>();
    /** Added only by a completed physical network audit; station readiness follows these paid jobs. */
    public Map<String,List<String>> commissionedNodeJobs = new LinkedHashMap<>();
    public Map<Car,Integer> spareCars = new EnumMap<>(Car.class);
    public Map<String,pl.medivalrpg.kolej.signal.SemaphorePlacement> semaphores = new LinkedHashMap<>();
    public String wagesDay = "";
    /** One-command line launches (plan id → durable progress). Absent in older files: Gson keeps the empty map. */
    public Map<String,LaunchState> launches = new LinkedHashMap<>();
    /** Departure boards: packet-free TextDisplay text over a real platform/yard position, rebuilt near players. */
    public Map<String,Board> boards = new LinkedHashMap<>();
    public static final String ACCOUNT = "kolej:przewoznik";

    public enum Car {
        PASAZERSKI("kolej_pasazerski", 8, 22), SALONOWY("kolej_salonowy", 4, 25),
        KRYTY("kolej_kryty", 0, 18), WEGLARKA("kolej_weglarka", 0, 16),
        PLATFORMA("kolej_platforma", 0, 14), CYSTERNA("kolej_cysterna", 0, 20), HAMULCOWY("kolej_hamulcowy", 0, 15);
        public final String model;
        public final int seats;
        public final double tonnes;
        Car(String model, int seats, double tonnes) {this.model=model;this.seats=seats;this.tonnes=tonnes;}
        public static Car parse(String text) {return valueOf(text.toUpperCase(Locale.ROOT));}
    }
    public static final class Train {
        public String id, node, line;
        public List<Car> cars = new ArrayList<>();
        public SteamState steam = SteamState.cold(LocomotiveSpec.defaults());
        public List<String> routeNodes = new ArrayList<>(), routeSegments = new ArrayList<>();
        public double distance;
        public long dwellUntil;
        public int nextStop = 1;
        public boolean running, reverse;
        public UUID manualDriver;
        public String status = "Postój";
        public Map<String,Integer> cargo = new LinkedHashMap<>();
        public String cargoDestination;
        /** Receipt of the last durably credited physical service transaction. */
        public String supplyReceipt;
        public String yardBase, yardPhase;
        public int yardBay = -1;
        public double[] yardPose;
        public transient double yardSpeed;
        public int seats() {return cars.stream().mapToInt(c->c.seats).sum();}
        public double mass() {return 70+cars.stream().mapToDouble(c->c.tonnes).sum()+cargo.values().stream().mapToInt(Integer::intValue).sum()/64.0;}
        public double length() {return 15+cars.size()*8;}
    }
    public static final class Line {
        public String id;
        public List<String> stops = new ArrayList<>();
        public Timetable timetable = Timetable.every(30,360,1320);
        public long lastDepartureMinute = -1;
        public long fare = 10;
        public boolean active;
        /** Timed return from the terminus: minutes after the origin departure (0 = legacy immediate return). */
        public int returnAfterMinutes;
    }
    public static final class Crew {
        public static final String DRIVER = "maszynista", STOKER = "palacz";
        public String id, name, train;
        /** null (older files) or {@link #DRIVER} = engine driver; {@link #STOKER} = fireman. */
        public String role;
        public boolean paid;
        public boolean driver() {return role == null || DRIVER.equals(role);}
        public boolean stoker() {return STOKER.equals(role);}
        public String roleName() {return stoker() ? STOKER : DRIVER;}
    }
    /** Launch progress. PAYING is written durably BEFORE any payment; a restart in PAYING requires an audit. */
    public static final class LaunchState {
        public String phase = "NEW";
        public String intent;
        public String reason = "";
        public List<String> fixturesDone = new ArrayList<>();
        public long paid;
        public String updated;
    }
    public record Board(String id, String station, String world, int x, int y, int z, int rotation) { }
    public record Depot(int x,int groundY,int z,int rotation) { }
    public TrackGraph graph() {
        TrackGraph graph = new TrackGraph();
        nodes.forEach(graph::putNode);
        segments.forEach(graph::putSegment);
        return graph;
    }
    public static boolean identifier(String id) {return id!=null && id.matches("[a-zA-Z0-9_-]{1,24}");}
}
