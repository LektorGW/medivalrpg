// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.util.*;
import org.bukkit.Bukkit;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.network.TrackSegment;
import pl.medivalrpg.kolej.network.TrackGraph;
import pl.medivalrpg.kolej.schedule.Timetable;

/** Optional Panel bridge. Identity comes from its authenticated server RPC, never a browser role. */
final class RailwayPanel {
    private RailwayPanel() { }
    static Object invoke(KolejPlugin plugin, RailwayProjects projects, UUID actor, String method, Map<String,String> args) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Main thread required");
        if(!allowed(actor,plugin.getConfig().getStringList("firma.manager-uuids")))throw new IllegalArgumentException("Tylko osobisty zarządca kolei ma dostęp do dyspozytorni.");
        RailData data=plugin.data();
        switch(method) {
            case "railway.get" -> {
                List<Map<String,Object>> works=new ArrayList<>();
                for(var j:projects.works().snapshot().jobs())works.add(Map.of("id",j.id(),"status",j.status(),"reason",j.reason(),"done",j.cursor(),"total",j.entries().size(),"cost",j.cost()));
                List<Map<String,Object>> lines=new ArrayList<>();
                data.lines.values().forEach(l->lines.add(Map.of("id",l.id,"stops",List.copyOf(l.stops),"active",l.active,"fare",l.fare,"timetable",l.timetable.describe())));
                List<Map<String,Object>> trains=new ArrayList<>();
                data.trains.values().forEach(t->trains.add(Map.of("id",t.id,"node",Objects.toString(t.node,""),"line",Objects.toString(t.line,""),"status",t.status,"running",t.running,"seats",t.seats())));
                List<Map<String,Object>> crews=new ArrayList<>();
                data.crews.values().forEach(c->crews.add(Map.of("id",c.id,"name",c.name,"train",Objects.toString(c.train,""),"paid",c.paid)));
                Map<String,Object> networkView=new LinkedHashMap<>(network(plugin.graph()));networkView.put("commission",plugin.networkCommissionStatus());
                return Map.of("company",data.companyName,"balance",plugin.economy().balance(RailData.ACCOUNT),"works",works,"lines",lines,"trains",trains,"crews",crews,
                    "network",networkView,"requests",projects.panelRequests(),
                    "stations",plugin.graph().nodes().stream().filter(n->n.isWaitingPoint()).map(n->Map.of("id",n.id(),"name",n.name(),"world",n.world(),"x",n.x(),"y",n.y(),"z",n.z())).toList(),
                    "permits",data.permits.stream().map(id->Map.of("id",id.toString(),"name",plugin.countries().getCountry(id).map(c->c.name()).orElse(id.toString()))).toList());
            }
            case "railway.request.create", "railway.request.quote", "railway.request.confirm", "railway.request.cancel" -> {
                return projects.panelRequest(actor,method,args);
            }
            case "railway.work.resume" -> {
                if(!projects.works().resume(id(args,"id")))throw new IllegalArgumentException("Zlecenie nie jest zablokowane.");
            }
            case "railway.work.cancel" -> {
                if(!projects.works().cancel(id(args,"id")))throw new IllegalArgumentException("Zlecenia nie można anulować. Ukończone bloki pozostają w świecie.");
            }
            case "railway.line.create" -> {
                String key=id(args,"id");if(data.lines.containsKey(key))throw new IllegalArgumentException("Id linii zajęte.");
                String raw=args.getOrDefault("stops","");List<String> stops=Arrays.asList(raw.trim().split("\\s*,\\s*"));
                if(stops.size()<2 || stops.size()>32 || stops.stream().distinct().count()!=stops.size())throw new IllegalArgumentException("Wybierz 2–32 różne stacje.");
                for(String stop:stops){var n=plugin.graph().node(stop);if(n==null || !n.isWaitingPoint())throw new IllegalArgumentException("Nie ma stacji: "+stop);}
                RailData.Line l=new RailData.Line();l.id=key;l.stops=new ArrayList<>(stops);data.lines.put(key,l);
            }
            case "railway.line.configure" -> {
                var line=data.lines.get(id(args,"id"));if(line==null)throw new IllegalArgumentException("Nie ma linii.");
                // Validate all fields before changing any state.
                long fare=number(args,"fare",0,10000);
                Timetable timetable=editedTimetable(line.timetable,args.get("interval"));
                String active=args.get("active");if(!Set.of("true","false").contains(Objects.toString(active,"")))throw new IllegalArgumentException("Nieprawidłowy stan linii.");
                boolean enabled=Boolean.parseBoolean(active);
                if(enabled)plugin.graph().routeThrough(line.stops,TrackSegment::isBuilt,true).orElseThrow(()->new IllegalArgumentException("Linia nie ma ukończonych torów."));
                line.fare=fare;line.timetable=timetable;line.active=enabled;
            }
            case "railway.train.assign" -> {
                var train=plugin.train(id(args,"train"));String line=id(args,"line");
                if(!data.lines.containsKey(line))throw new IllegalArgumentException("Nie ma linii.");
                if(train.running)throw new IllegalArgumentException("Skład jest w ruchu.");train.line=line;
            }
            case "railway.crew.assign" -> {
                var crew=data.crews.get(id(args,"id"));var train=plugin.train(id(args,"train"));
                if(crew==null)throw new IllegalArgumentException("Nie ma maszynisty.");
                if(train.running || data.crews.values().stream().anyMatch(c->c!=crew && train.id.equals(c.train)))throw new IllegalArgumentException("Skład jedzie lub ma już maszynistę.");
                crew.train=train.id;
            }
            default -> throw new IllegalArgumentException("Nieznana czynność dyspozytorni.");
        }
        plugin.save();return Map.of("ok",true);
    }
    /** Exact rail cells, bounded for the private bridge; never infer a built straight connection. */
    static Map<String,Object> network(TrackGraph graph) {
        List<Map<String,Object>> segments=new ArrayList<>();
        int budget=30000;
        for(var s:graph.segments()) {
            var from=graph.node(s.from());var to=graph.node(s.to());
            if(from==null || to==null || !from.world().equals(to.world()))continue;
            int size=s.cells().size(),step=Math.max(1,(size+511)/512);
            List<List<Integer>> points=new ArrayList<>();
            for(int i=0;i<size && budget>0;i+=step,budget--){var c=s.cells().get(i);points.add(List.of(c.x(),c.y(),c.z()));}
            if(size>0 && budget>0){var c=s.cells().getLast();var last=List.of(c.x(),c.y(),c.z());if(points.isEmpty() || !points.getLast().equals(last)){points.add(last);budget--;}}
            segments.add(Map.of("id",s.id(),"from",s.from(),"to",s.to(),"world",from.world(),"status",s.status().name(),"points",points,"sampled",step>1 || budget<=0));
        }
        return Map.of("nodes",graph.nodes().stream().map(n->Map.of("id",n.id(),"name",n.name(),"type",n.type().name(),"world",n.world(),"x",n.x(),"y",n.y(),"z",n.z())).toList(),"segments",segments);
    }
    /** Blank means preserve all existing service hours and dwell settings. */
    static Timetable editedTimetable(Timetable current,String raw) {
        if(raw==null || raw.isBlank())return current;
        String value=raw.trim();Timetable edited;
        if(value.matches("[0-9]{1,4}")) {
            int interval=Integer.parseInt(value);
            if(interval<1 || interval>1440)throw new IllegalArgumentException("Odstęp odjazdów musi wynosić 1–1440 minut.");
            edited=Timetable.every(interval,0,1439);
        } else {
            String[] values=value.split(",",-1);
            if(values.length>1440)throw new IllegalArgumentException("Za dużo godzin odjazdu.");
            List<Integer> times=new ArrayList<>();
            for(String time:values) {
                if(!time.trim().matches("[0-9]{1,2}:[0-9]{2}"))throw new IllegalArgumentException("Podaj odstęp w minutach albo godziny HH:MM oddzielone przecinkami.");
                int minute=Timetable.parseTime(time);
                if(minute<0)throw new IllegalArgumentException("Nieprawidłowa godzina odjazdu.");
                times.add(minute);
            }
            edited=Timetable.at(times);
        }
        edited.dwellSeconds=current.dwellSeconds;
        edited.turnaroundSeconds=current.turnaroundSeconds;
        return edited;
    }
    static boolean allowed(UUID actor,List<String> managers){return actor!=null && managers.contains(actor.toString());}
    static String id(Map<String,String> args,String key){String value=args.get(key);if(!RailData.identifier(value))throw new IllegalArgumentException("Nieprawidłowy identyfikator: "+key);return value;}
    static long number(Map<String,String> args,String key,long min,long max){try{long value=Long.parseLong(args.get(key));if(value>=min && value<=max)return value;}catch(RuntimeException ignored){}throw new IllegalArgumentException("Nieprawidłowa wartość: "+key);}
}
