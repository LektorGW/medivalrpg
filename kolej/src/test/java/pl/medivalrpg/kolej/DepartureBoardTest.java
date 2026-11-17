// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.schedule.Timetable;
class DepartureBoardTest {
    private RailData.Line line(String id,boolean active){var l=new RailData.Line();l.id=id;l.active=active;l.stops=List.of("spawn","baza");l.timetable=Timetable.at(List.of(480,720));l.fare=25;return l;}
    @Test void ordersAndFiltersActualOriginAndActiveLines(){
        var now=LocalDateTime.of(2026,10,28,8,0);
        var rows=DepartureBoard.next(List.of(line("b",true),line("a",true),line("off",false)),"spawn",now,3);
        assertEquals(List.of("a","b","a"),rows.stream().map(DepartureBoard.Departure::line).toList());
        assertEquals(now,rows.getFirst().time());assertEquals(25,rows.getFirst().fare());
        assertTrue(DepartureBoard.next(List.of(line("a",true)),"baza",now,3).isEmpty());
    }
    @Test void excludesMalformedLinesAndNeverPromisesBeyond24Hours(){
        var invalid=line("bad",true);invalid.stops=List.of("spawn");
        var l=line("good",true);l.timetable=Timetable.at(List.of(480));
        var rows=DepartureBoard.next(List.of(l,invalid),null,LocalDateTime.of(2026,10,28,9,0),12);
        assertEquals(1,rows.size());assertEquals(29,rows.getFirst().time().getDayOfMonth());
        assertThrows(IllegalArgumentException.class,()->DepartureBoard.next(List.of(l),null,LocalDateTime.now(),999));
    }
    @Test void timedReturnIsShownAtTheTerminusOnly(){
        var l=line("k",true);l.timetable=Timetable.at(List.of(480,1380));l.returnAfterMinutes=30;
        var now=LocalDateTime.of(2026,10,28,8,10);
        var back=DepartureBoard.next(List.of(l),"baza",now,5);
        assertEquals(List.of(LocalDateTime.of(2026,10,28,8,30),LocalDateTime.of(2026,10,28,23,30)),back.stream().map(DepartureBoard.Departure::time).toList());
        assertEquals("spawn",back.getFirst().destination());
        var midnight=DepartureBoard.next(List.of(l),"baza",LocalDateTime.of(2026,10,29,0,0),1);
        assertEquals(LocalDateTime.of(2026,10,29,8,30),midnight.getFirst().time());
        l.returnAfterMinutes=0;assertTrue(DepartureBoard.next(List.of(l),"baza",now,5).isEmpty(),"legacy lines never invent returns");
    }
}
