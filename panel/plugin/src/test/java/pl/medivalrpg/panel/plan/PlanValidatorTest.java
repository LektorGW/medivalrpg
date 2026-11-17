// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Plan format v1: schema, limits, land + margin, building types, wall modes, warnings with element references. */
class PlanValidatorTest {

    /** Land: chunks 0..3 × 0..3 (blocks 0..63). */
    static final Set<Long> LAND = new HashSet<>();

    static {
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                LAND.add(ChunkGeometry.key(x, z));
            }
        }
    }

    static final Map<String, PlanValidator.TypeInfo> TYPES = Map.of(
        "dom", new PlanValidator.TypeInfo("dom", "Dom", true, List.of(), 3, -1),
        "palac", new PlanValidator.TypeInfo("palac", "Pałac", false, List.of("Poziom osady: Miasto"), 0, 1),
        "studnia", new PlanValidator.TypeInfo("studnia", "Studnia", true, List.of(), 0, -1));

    static PlanValidator.Result run(final String json) {
        return PlanValidator.validate(JsonParser.parseString(json), LAND, 32, TYPES);
    }

    static boolean hasIssue(final List<PlanValidator.Issue> list, final String ref, final String fragment) {
        return list.stream().anyMatch(i -> i.ref().equals(ref) && i.text().contains(fragment));
    }

    @Test
    void validPlanIsNormalisedWithDefaults() {
        final PlanValidator.Result r = run("""
            {"version":1,
             "zones":[{"type":"MIESZKALNA","polygon":[[2,2],[30,2],[30,30],[2,30],[2,2]],"extra":"x"}],
             "roads":[{"kind":"glowna","points":[[0,10],[60,10],[60,10]]}],
             "walls":[{"points":[[1,1],[62,1],[62,62],[1,62]],"closed":true,"gates":[[30,1]]},{"mode":"follow-border"}],
             "buildings":[{"type":"dom","x":20,"z":20},{"type":"studnia","x":45.0,"z":45,"rotation":90,"priority":1}],
             "options":{"allowDemolish":true}}""");
        assertTrue(r.ok(), r.errors().toString());
        final JsonObject p = r.plan();
        assertEquals(1, p.get("version").getAsInt());
        final JsonObject zone = p.getAsJsonArray("zones").get(0).getAsJsonObject();
        assertEquals("mieszkalna", zone.get("type").getAsString());
        assertEquals(4, zone.getAsJsonArray("polygon").size(), "repeated closing point dropped");
        assertFalse(zone.has("extra"), "unknown fields dropped");
        final JsonObject road = p.getAsJsonArray("roads").get(0).getAsJsonObject();
        assertEquals(5, road.get("width").getAsInt(), "main road default width");
        assertEquals(2, road.getAsJsonArray("points").size(), "duplicate point dropped");
        final JsonObject wall = p.getAsJsonArray("walls").get(0).getAsJsonObject();
        assertEquals("points", wall.get("mode").getAsString());
        assertTrue(wall.get("closed").getAsBoolean());
        final JsonObject auto = p.getAsJsonArray("walls").get(1).getAsJsonObject();
        assertEquals(2, auto.get("inset").getAsInt(), "follow-border default inset");
        assertEquals(0, auto.getAsJsonArray("points").size());
        final JsonObject b0 = p.getAsJsonArray("buildings").get(0).getAsJsonObject();
        assertEquals(0, b0.get("rotation").getAsInt());
        assertEquals(PlanValidator.DEFAULT_PRIORITY, b0.get("priority").getAsInt());
        final JsonObject o = p.getAsJsonObject("options");
        assertTrue(o.get("allowDemolish").getAsBoolean());
        assertTrue(o.get("keepExisting").getAsBoolean());
        assertTrue(o.get("autoFill").getAsBoolean());
    }

    @Test
    void emptyPlanIsValid() {
        final PlanValidator.Result r = run("{}");
        assertTrue(r.ok());
        assertEquals(0, r.plan().getAsJsonArray("buildings").size());
    }

    @Test
    void schemaErrorsNameTheElement() {
        final PlanValidator.Result r = run("""
            {"version":2,
             "zones":[{"type":"bagno","polygon":[[0,0],[10,0],[10,10]]},{"type":"las","polygon":[[0,0],[10,0]]},{"type":"park","polygon":[[0,0],[5,5],[10,10]]}],
             "roads":[{"kind":"autostrada","points":[[0,0],[5,5]]},{"kind":"boczna","points":[[0,0],[5,5]],"width":12}],
             "buildings":[{"type":"zamek_xyz","x":1,"z":1},{"type":"dom","x":1.5,"z":1},{"type":"dom","x":5,"z":5,"rotation":45}],
             "options":{"autoFill":"tak"}}""");
        assertFalse(r.ok());
        assertNull(r.plan());
        assertTrue(r.errors().stream().anyMatch(i -> i.kind() == null && i.text().contains("wersja")), "version");
        assertTrue(hasIssue(r.errors(), "zones[0]", "nieznany typ strefy"));
        assertTrue(hasIssue(r.errors(), "zones[1]", "co najmniej 3"));
        assertTrue(hasIssue(r.errors(), "zones[2]", "zerową powierzchnię"));
        assertTrue(hasIssue(r.errors(), "roads[0]", "rodzaj drogi"));
        assertTrue(hasIssue(r.errors(), "roads[1]", "szerokość"));
        assertTrue(hasIssue(r.errors(), "buildings[0]", "nieznany typ budynku"));
        assertTrue(hasIssue(r.errors(), "buildings[1]", "x"));
        assertTrue(hasIssue(r.errors(), "buildings[2]", "obrót"));
        assertTrue(r.errors().stream().anyMatch(i -> i.text().contains("autoFill")), "options");
        assertTrue(r.message().startsWith("Plan ma błędy ("), r.message());
        assertTrue(r.message().contains("zones[0]:"), r.message());
    }

    @Test
    void limitsOfElementCounts() {
        final StringBuilder b = new StringBuilder("{\"buildings\":[");
        for (int i = 0; i <= PlanValidator.MAX_BUILDINGS; i++) {
            b.append(i == 0 ? "" : ",").append("{\"type\":\"dom\",\"x\":").append(i % 60).append(",\"z\":10}");
        }
        b.append("]}");
        final PlanValidator.Result r = run(b.toString());
        assertFalse(r.ok());
        assertTrue(r.errors().get(0).text().contains("za dużo elementów „buildings”"), r.errors().get(0).text());
        final StringBuilder z = new StringBuilder("{\"zones\":[");
        for (int i = 0; i <= PlanValidator.MAX_ZONES; i++) {
            z.append(i == 0 ? "" : ",").append("{\"type\":\"park\",\"polygon\":[[0,0],[9,0],[9,9]]}");
        }
        z.append("]}");
        assertFalse(run(z.toString()).ok());
        final StringBuilder pts = new StringBuilder("{\"roads\":[{\"kind\":\"trakt\",\"points\":[");
        for (int i = 0; i <= PlanValidator.MAX_POINTS_PER_ELEMENT; i++) {
            pts.append(i == 0 ? "" : ",").append("[").append(i % 60).append(",").append(i / 60).append("]");
        }
        pts.append("]}]}");
        assertTrue(hasIssue(run(pts.toString()).errors(), "roads[0]", "za dużo punktów"));
    }

    @Test
    void pointsMustLieOnTheLandPlusMargin() {
        // land ends at x = 63; margin 32 → x = 95 still accepted (with a warning), x = 96 rejected
        PlanValidator.Result r = run("{\"roads\":[{\"kind\":\"trakt\",\"points\":[[10,10],[95,10]]}]}");
        assertTrue(r.ok(), r.errors().toString());
        assertTrue(hasIssue(r.warnings(), "roads[0]", "poza ziemię kraju"));
        r = run("{\"roads\":[{\"kind\":\"trakt\",\"points\":[[10,10],[96,10]]}]}");
        assertTrue(hasIssue(r.errors(), "roads[0]", "poza ziemią kraju i marginesem 32"));
        r = run("{\"buildings\":[{\"type\":\"dom\",\"x\":-40,\"z\":10}]}");
        assertTrue(hasIssue(r.errors(), "buildings[0]", "poza ziemią kraju"));
        r = run("{\"buildings\":[{\"type\":\"dom\",\"x\":62,\"z\":10}]}");
        assertTrue(r.ok());
        assertTrue(hasIssue(r.warnings(), "buildings[0]", "wychodzi poza ziemię kraju"), "footprint crosses the border");
    }

    @Test
    void buildingWarnings() {
        final PlanValidator.Result r = run("""
            {"zones":[{"type":"nie_ruszac","polygon":[[40,40],[60,40],[60,60],[40,60]]}],
             "buildings":[{"type":"palac","x":20,"z":20},{"type":"dom","x":24,"z":24},{"type":"studnia","x":50,"z":50},
                          {"type":"palac","x":20,"z":45}]}""");
        assertTrue(r.ok(), r.errors().toString());
        assertTrue(hasIssue(r.warnings(), "buildings[0]", "niedostępny (brakuje: Poziom osady: Miasto)"));
        assertTrue(hasIssue(r.warnings(), "buildings[1]", "nachodzi na budynek buildings[0]"));
        assertTrue(hasIssue(r.warnings(), "buildings[2]", "strefie „nie ruszac”"));
        assertTrue(hasIssue(r.warnings(), "buildings[3]", "limit 1"));
    }

    @Test
    void wallsGatesAndModes() {
        PlanValidator.Result r = run("{\"walls\":[{\"points\":[[0,0],[40,0]],\"gates\":[[20,1]]}]}");
        assertTrue(r.ok(), r.errors().toString());
        r = run("{\"walls\":[{\"points\":[[0,0],[40,0]],\"gates\":[[20,9]]}]}");
        assertTrue(hasIssue(r.errors(), "walls[0]", "nie leży na murze"));
        r = run("{\"walls\":[{\"points\":[[0,0],[40,0]],\"closed\":true}]}");
        assertTrue(hasIssue(r.errors(), "walls[0]", "co najmniej 3"), "closed wall needs 3 points");
        r = run("{\"walls\":[{\"points\":[[0,0],[40,0],[40,40]],\"closed\":true,\"gates\":[[20,20]]}]}");
        assertTrue(r.ok(), "gate on the closing segment: " + r.errors());
        r = run("{\"walls\":[{\"mode\":\"wokol\"}]}");
        assertTrue(hasIssue(r.errors(), "walls[0]", "nieznany tryb muru"));
        r = run("{\"walls\":[{\"mode\":\"follow-town\",\"inset\":6,\"points\":[[1,1],[2,2]],\"gates\":[[10,10]]},{\"mode\":\"follow-border\",\"inset\":0}]}");
        assertTrue(r.ok(), r.errors().toString());
        assertTrue(hasIssue(r.warnings(), "walls[0]", "nie używa punktów"));
        assertEquals(6, r.plan().getAsJsonArray("walls").get(0).getAsJsonObject().get("inset").getAsInt());
        assertTrue(hasIssue(run("{\"walls\":[{\"mode\":\"follow-town\",\"inset\":8}]}").errors(), "walls[0]", "0–7"), "Ludnosc: inset 0..7");
        assertEquals(1, r.plan().getAsJsonArray("walls").get(0).getAsJsonObject().getAsJsonArray("gates").size());
        r = run("{\"walls\":[{\"mode\":\"follow-border\"},{\"mode\":\"follow-border\"}]}");
        assertTrue(hasIssue(r.errors(), "walls[1]", "tylko jeden mur"));
        r = run("{\"walls\":[{\"mode\":\"follow-border\",\"inset\":99}]}");
        assertTrue(hasIssue(r.errors(), "walls[0]", "inset"));
    }

    @Test
    void anyTypeWhenLudnoscReportsNone() {
        final PlanValidator.Result r = PlanValidator.validate(JsonParser.parseString("{\"buildings\":[{\"type\":\"cokolwiek\",\"x\":5,\"z\":5}]}"), LAND, 32, Map.of());
        assertTrue(r.ok());
        final PlanValidator.Result bad = PlanValidator.validate(JsonParser.parseString("{\"buildings\":[{\"type\":\"Zła nazwa!\",\"x\":5,\"z\":5}]}"), LAND, 32, Map.of());
        assertFalse(bad.ok());
    }

    @Test
    void nonObjectPlanIsRejected() {
        assertFalse(PlanValidator.validate(JsonParser.parseString("[1,2]"), LAND, 32, TYPES).ok());
        assertFalse(PlanValidator.validate(null, LAND, 32, TYPES).ok());
        assertFalse(run("{\"zones\":{}}").ok());
    }

    @Test
    void ludnoscWarningsKeepTheirElementReference() {
        final List<PlanValidator.Issue> w = PlanValidator.parseWarnings(List.of("buildings[3]: kolizja z istniejącym budynkiem", "roads[0] – teren za stromy",
            "ogólne ostrzeżenie", " "));
        assertEquals(3, w.size());
        assertEquals("buildings[3]", w.get(0).ref());
        assertEquals("kolizja z istniejącym budynkiem", w.get(0).text());
        assertEquals("roads", w.get(1).kind());
        assertEquals("teren za stromy", w.get(1).text());
        assertNull(w.get(2).kind());
        assertNull(w.get(2).json().get("ref"));
        // format ostrzeżeń Ludnosc (numery od 1)
        final List<PlanValidator.Issue> pl = PlanValidator.parseWarnings(List.of("budynek #3 (dom): koliduje z istniejącym budynkiem",
            "droga #1: 12 bl. na obcej ziemi — pominięte", "mur #2: nieznany tryb x (follow-border, follow-town)"));
        assertEquals("buildings[2]", pl.get(0).ref());
        assertEquals("koliduje z istniejącym budynkiem", pl.get(0).text());
        assertEquals("roads[0]", pl.get(1).ref());
        assertEquals("walls[1]", pl.get(2).ref());
    }

    @Test
    void footprintsAreCentredAndRotate() {
        final int[] a = BuildingCatalog.footprint("dom", 100, 200, 0); // 11 × 9
        assertEquals(95, a[0]);
        assertEquals(196, a[1]);
        assertEquals(105, a[2]);
        assertEquals(204, a[3]);
        final int[] b = BuildingCatalog.footprint("dom", 100, 200, 90);
        assertEquals(9, b[2] - b[0] + 1);
        assertEquals(11, b[3] - b[1] + 1);
        assertEquals(BuildingCatalog.DEFAULT, BuildingCatalog.of("nieznany"));
        assertFalse(BuildingCatalog.known("nieznany"));
    }
}
