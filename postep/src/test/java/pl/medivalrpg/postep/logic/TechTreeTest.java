// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.medivalrpg.postep.logic.TestData.d;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.postep.model.Discovery;

class TechTreeTest {

    @Test
    void statusFollowsRequirementsAndEra() {
        final TechTree t = TestData.small();
        final Discovery c = t.get("c").orElseThrow();
        assertEquals(TechTree.Status.LOCKED_REQUIREMENTS, t.status(c, Set.of("a"), 1));
        assertEquals(TechTree.Status.AVAILABLE, t.status(c, Set.of("a", "b"), 1));
        assertEquals(TechTree.Status.OWNED, t.status(c, Set.of("a", "b", "c"), 1));
        final Discovery d = t.get("d").orElseThrow();
        assertEquals(TechTree.Status.LOCKED_ERA, t.status(d, Set.of("a", "b", "c"), 1));
        assertEquals(TechTree.Status.AVAILABLE, t.status(d, Set.of("a", "b", "c"), 2));
    }

    @Test
    void availableKeepsTreeOrder() {
        final TechTree t = TestData.small();
        assertEquals(List.of("a", "b", "x"), t.available(Set.of(), 1).stream().map(Discovery::id).toList());
        assertEquals(List.of("c", "x"), t.available(Set.of("a", "b"), 1).stream().map(Discovery::id).toList());
    }

    @Test
    void pathPutsPrerequisitesFirst() {
        final TechTree t = TestData.small();
        assertEquals(List.of("a", "b", "c", "d"), t.pathTo("d", Set.of()).stream().map(Discovery::id).toList());
        assertEquals(List.of("b", "c"), t.pathTo("c", Set.of("a")).stream().map(Discovery::id).toList());
        assertTrue(t.pathTo("c", Set.of("a", "b", "c")).isEmpty());
        assertTrue(t.pathTo("nieznane", Set.of()).isEmpty());
    }

    @Test
    void requiredToLeave() {
        assertEquals(List.of("a", "b", "c"), TestData.small().requiredToLeave(1).stream().map(Discovery::id).toList());
    }

    @Test
    void dependentsAreTransitive() {
        assertEquals(Set.of("c", "d"), TestData.small().dependents("a"));
        assertTrue(TestData.small().dependents("x").isEmpty());
    }

    @Test
    void validTreeHasNoProblems() {
        assertTrue(TestData.small().validate(4).isEmpty());
        assertTrue(TestData.small().findCycle().isEmpty());
    }

    @Test
    void detectsCycle() {
        final TechTree t = TechTree.of(List.of(
            d("p", 1, 10, false, "nauka", "r"),
            d("q", 1, 10, false, "nauka", "p"),
            d("r", 1, 10, false, "nauka", "q"),
            d("ok", 1, 10, false, "nauka")));
        final List<String> cycle = t.findCycle().orElseThrow();
        assertEquals(cycle.get(0), cycle.get(cycle.size() - 1));
        assertTrue(cycle.containsAll(List.of("p", "q", "r")));
        assertTrue(t.validate(4).stream().anyMatch(s -> s.startsWith("cykl")));
        final TechTree clean = t.sanitized(4);
        assertEquals(List.of("ok"), clean.all().stream().map(Discovery::id).toList());
    }

    @Test
    void selfRequirementIsACycle() {
        final TechTree t = TechTree.of(List.of(d("s", 1, 10, false, "nauka", "s")));
        assertTrue(t.findCycle().isPresent());
        assertEquals(0, t.sanitized(4).size());
    }

    @Test
    void unknownAndLaterEraRequirementsAreReportedAndDropped() {
        final TechTree t = TechTree.of(List.of(
            d("early", 1, 10, false, "nauka", "late"),
            d("late", 2, 10, false, "nauka"),
            d("ghost", 1, 10, false, "nauka", "brak"),
            d("child", 1, 10, false, "nauka", "ghost"),
            d("fine", 1, 10, false, "nauka"),
            d("badera", 7, 10, false, "nauka"),
            d("free", 1, 0, false, "nauka")));
        final List<String> problems = t.validate(4);
        assertTrue(problems.stream().anyMatch(p -> p.contains("późniejszej epoki")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("nieznane wymaganie brak")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("poza zakresem")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("koszt")));
        final Set<String> kept = Set.copyOf(t.sanitized(4).all().stream().map(Discovery::id).toList());
        assertEquals(Set.of("late", "fine"), kept);
        assertFalse(kept.contains("child"), "dependents of a dropped discovery are dropped too");
    }

    @Test
    void duplicateIdKeepsFirst() {
        final TechTree t = TechTree.of(List.of(d("a", 1, 10, false, "nauka"), d("a", 2, 99, false, "nauka")));
        assertEquals(1, t.size());
        assertEquals(10, t.get("a").orElseThrow().cost());
    }
}
