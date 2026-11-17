// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RailwayPanelAccessTest {
    @Test void personalManagerWorksWithoutCountryAndEmptyWhitelistFailsClosed() {
        UUID owner=UUID.fromString("ff923cf7-f43c-37b8-85a3-77cbac357c9c");
        assertTrue(RailwayPanel.allowed(owner,List.of(owner.toString())));
        assertFalse(RailwayPanel.allowed(UUID.randomUUID(),List.of(owner.toString())));
        assertFalse(RailwayPanel.allowed(null,List.of(owner.toString())));
        assertFalse(RailwayPanel.allowed(owner,List.of()));
    }
    @Test void hostileIdentifiersAndInvalidAmountsNeverReachMutation() {
        for(String id:List.of("../jobs","a b","a;stop","x".repeat(25)))assertThrows(IllegalArgumentException.class,()->RailwayPanel.id(Map.of("id",id),"id"));
        for(String value:List.of("-1","10001","NaN","1.5","9223372036854775808"))assertThrows(IllegalArgumentException.class,()->RailwayPanel.number(Map.of("fare",value),"fare",0,10000));
        assertEquals(10000,RailwayPanel.number(Map.of("fare","10000"),"fare",0,10000));
    }
    @Test void fareOnlyEditPreservesExistingTimetableAndServiceWindow() {
        var original=pl.medivalrpg.kolej.schedule.Timetable.every(17,420,1100);
        original.dwellSeconds=75;original.turnaroundSeconds=180;
        assertSame(original,RailwayPanel.editedTimetable(original,""));
        assertSame(original,RailwayPanel.editedTimetable(original,null));
        assertSame(original,RailwayPanel.editedTimetable(original,"   "));
        var edited=RailwayPanel.editedTimetable(original,"08:00, 12:30,08:00");
        assertEquals(List.of(480,750),edited.minutesOfDay());
        assertEquals(75,edited.dwellSeconds);assertEquals(180,edited.turnaroundSeconds);
        assertEquals(420,original.fromMinute);
        assertEquals(48,RailwayPanel.editedTimetable(original,"30").minutesOfDay().size());
    }
    @Test void invalidScheduleIsRejectedWithoutChangingOriginal() {
        var original=pl.medivalrpg.kolej.schedule.Timetable.every(17,420,1100);
        for(String raw:List.of("0","1441","-1","24:00","12:60","08:00,","NaN","08:00,,09:00","12:30:00"))
            assertThrows(IllegalArgumentException.class,()->RailwayPanel.editedTimetable(original,raw),raw);
        assertEquals(17,original.everyMinutes);assertEquals(420,original.fromMinute);
    }
    @Test void constructionQuoteIsBoundToActorTokenStateAndExpiry() {
        var r=new RailwayProjects.PanelRequest();r.actor=UUID.randomUUID();r.state="QUOTED";r.token="server-token";r.expires=100;
        assertDoesNotThrow(()->RailwayProjects.validQuote(r,r.actor,"server-token",99));
        assertThrows(IllegalArgumentException.class,()->RailwayProjects.validQuote(r,UUID.randomUUID(),"server-token",99));
        assertThrows(IllegalArgumentException.class,()->RailwayProjects.validQuote(r,r.actor,"browser-token",99));
        assertThrows(IllegalArgumentException.class,()->RailwayProjects.validQuote(r,r.actor,null,99));
        assertThrows(IllegalArgumentException.class,()->RailwayProjects.validQuote(r,r.actor,"server-token",101));
        for(String state:List.of("REQUESTED","SURVEYING","SUBMITTED","CANCELLED","EXPIRED","FAILED")) {
            r.state=state;assertThrows(IllegalArgumentException.class,()->RailwayProjects.validQuote(r,r.actor,"server-token",99));
        }
        for(String kind:List.of("station","rail","road"))assertDoesNotThrow(()->RailwayProjects.requestKind(kind));
        for(String kind:List.of("raw","blocks","../tor","depot",""))assertThrows(IllegalArgumentException.class,()->RailwayProjects.requestKind(kind));
    }
    @Test void restartKeepsRequestButInvalidatesSurveyAndQuote() {
        for(String state:List.of("REQUESTED","SURVEYING","QUOTED","SUBMITTED","CANCELLED")) {
            var r=new RailwayProjects.PanelRequest();r.id="wniosek";r.actor=UUID.randomUUID();r.country=UUID.randomUUID();r.world="world";r.kind="rail";r.state=state;r.token="old-token";
            String json=new com.google.gson.Gson().toJson(r);
            assertFalse(json.contains("quoted"));assertFalse(json.contains("ops"));
            var restored=new com.google.gson.Gson().fromJson(json,RailwayProjects.PanelRequest.class);
            RailwayProjects.restoreRequest(restored);
            assertEquals(Set.of("SURVEYING","QUOTED").contains(state)?"EXPIRED":state,restored.state);
            assertEquals("",restored.token);assertEquals(r.actor,restored.actor);assertEquals(r.country,restored.country);
            assertThrows(IllegalArgumentException.class,()->RailwayProjects.validQuote(restored,r.actor,"old-token",0));
        }
    }
    @Test @SuppressWarnings("unchecked") void graphMapExportsActualBendsWorldAndBuildStatus() {
        var graph=new pl.medivalrpg.kolej.network.TrackGraph();
        var type=pl.medivalrpg.kolej.network.NodeType.STATION;
        graph.putNode(new pl.medivalrpg.kolej.network.TrackNode("a","A",type,"world",0,70,0,0,1));
        graph.putNode(new pl.medivalrpg.kolej.network.TrackNode("b","B",type,"world",10,71,10,0,1));
        var segment=new pl.medivalrpg.kolej.network.TrackSegment("ab","a","b");
        var kind=pl.medivalrpg.kolej.network.CellKind.values()[0];
        segment.cells(List.of(new pl.medivalrpg.kolej.network.TrackCell(0,70,0,kind),new pl.medivalrpg.kolej.network.TrackCell(0,70,10,kind),new pl.medivalrpg.kolej.network.TrackCell(10,71,10,kind)));
        graph.putSegment(segment);
        var network=RailwayPanel.network(graph);
        var row=((List<Map<String,Object>>)network.get("segments")).getFirst();
        assertEquals("world",row.get("world"));assertEquals("PLANNED",row.get("status"));
        assertEquals(List.of(List.of(0,70,0),List.of(0,70,10),List.of(10,71,10)),row.get("points"));
        assertEquals(false,row.get("sampled"));
    }
    @Test void stationFootprintCannotHideWrongHeightOrSidewaysRails() {
        var node=new pl.medivalrpg.kolej.network.TrackNode("a","A",pl.medivalrpg.kolej.network.NodeType.STATION,"world",10,70,10,0,1);
        var region=new RailwayProjects.StationRegion(node,null);var kind=pl.medivalrpg.kolej.network.CellKind.values()[0];
        assertTrue(region.accepts(new pl.medivalrpg.kolej.network.TrackCell(10,70,20,kind)));
        assertFalse(region.accepts(new pl.medivalrpg.kolej.network.TrackCell(10,71,20,kind)));
        assertFalse(region.accepts(new pl.medivalrpg.kolej.network.TrackCell(11,70,20,kind)));
        assertTrue(region.contains(pl.medivalrpg.kolej.build.BlockOp.clear(10,74,20)));
        assertFalse(region.contains(pl.medivalrpg.kolej.build.BlockOp.clear(10,100,20)));
        assertFalse(region.contains(pl.medivalrpg.kolej.build.BlockOp.clear(10,10,20)));
    }
}
