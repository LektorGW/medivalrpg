// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.bukkit.command.CommandSender;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.network.TrackSegment;

/** Scheduled departures from line origins and planned timed returns. Never invents intermediate arrival estimates. */
final class DepartureBoard {
    record Departure(String line,String origin,String destination,LocalDateTime time,long fare) {}
    static List<Departure> next(Collection<RailData.Line> lines,String station,LocalDateTime now,int limit) {
        if(limit<1 || limit>50)throw new IllegalArgumentException("Limit 1–50");
        List<Departure> rows=new ArrayList<>();
        for(var line:lines) {
            if(!line.active || line.stops.size()<2)continue;
            // Origin departures, and — only for lines with a timed return — the planned return from the terminus.
            for(int leg=0;leg<2;leg++) {
                String origin=leg==0?line.stops.getFirst():line.stops.getLast(),destination=leg==0?line.stops.getLast():line.stops.getFirst();
                if(leg==1 && line.returnAfterMinutes<=0)continue;
                if(station!=null && !station.equals(origin))continue;
                int added=0;
                var minutes=line.timetable.minutesOfDay();
                for(int day=-1;day<=1 && added<limit;day++)for(int minute:minutes) {
                    LocalDateTime time=now.toLocalDate().plusDays(day).atStartOfDay().plusMinutes(minute+(leg==1?line.returnAfterMinutes:0));
                    if(time.isBefore(now) || time.isAfter(now.plusHours(24)))continue;
                    rows.add(new Departure(line.id,origin,destination,time,line.fare));
                    if(++added>=limit)break;
                }
            }
        }
        return rows.stream().sorted(Comparator.comparing(Departure::time).thenComparing(Departure::line)).limit(limit).toList();
    }
    static void show(KolejPlugin plugin,CommandSender sender,String station) {
        if(station!=null && (plugin.graph().node(station)==null || !plugin.graph().node(station).isWaitingPoint()))throw new IllegalArgumentException("Nie ma takiej stacji.");
        var rows=next(plugin.data().lines.values(),station,LocalDateTime.now(plugin.zone()),12);
        plugin.message(sender,"Planowane odjazdy · "+plugin.zone()+" · najbliższe 24 godziny");
        if(rows.isEmpty())plugin.message(sender,"Brak zaplanowanych odjazdów. Zarządca musi ukończyć tory i uruchomić linię.");
        for(var row:rows) {
            String readiness=readiness(plugin,row);
            plugin.message(sender,row.time.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))+" | "+row.line+" | "+name(plugin,row.origin)+" → "+name(plugin,row.destination)+" | "+plugin.economy().format(row.fare)+" | "+readiness);
        }
        plugin.message(sender,"Godziny są planowane; potwierdzenie odjazdu zależy od torów, maszynisty, paliwa i gotowości składu.");
    }
    static String readiness(KolejPlugin plugin,Departure row){
        var trains=plugin.data().trains.values().stream().filter(t->row.line.equals(t.line)).toList();
        String readiness=trains.isEmpty()?"brak przydzielonego składu":trains.stream().anyMatch(t->!t.running && row.origin.equals(t.node))?"skład na stacji":trains.stream().anyMatch(t->t.running)?"skład w drodze":"skład poza stacją";
        var line=plugin.data().lines.get(row.line);
        if(line==null || plugin.graph().routeThrough(line.stops,TrackSegment::isBuilt,true).isEmpty())readiness="tory niedostępne";
        return readiness;
    }
    static String name(KolejPlugin plugin,String id){var node=plugin.graph().node(id);return node==null?id:node.name();}
}
