// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.postep.api.PostepApi;

/** Tree flags and research paths of {@code postep.get}. */
class PostepMethodsTest {

    private static PostepApi.DiscoveryInfo d(final String id, final int era, final long cost, final String... req) {
        return new PostepApi.DiscoveryInfo(id, id.toUpperCase(), era, cost, List.of(req), false, "nauka", "", "", Map.of());
    }

    private static final List<PostepApi.DiscoveryInfo> TREE = List.of(
        d("zegar", 1, 140), d("gotyk", 1, 160), d("uniwersytety", 1, 220, "zegar", "gotyk"), d("druk", 2, 300, "uniwersytety"));

    @Test
    void status() {
        assertEquals(PostepMethods.AVAILABLE, PostepMethods.status(TREE.get(0), Set.of(), 1));
        assertEquals(PostepMethods.LOCKED, PostepMethods.status(TREE.get(2), Set.of("zegar"), 1));
        assertEquals(PostepMethods.AVAILABLE, PostepMethods.status(TREE.get(2), Set.of("zegar", "gotyk"), 1));
        assertEquals(PostepMethods.OWNED, PostepMethods.status(TREE.get(0), Set.of("zegar"), 1));
        assertEquals(PostepMethods.ERA_LOCKED, PostepMethods.status(TREE.get(3), Set.of("zegar", "gotyk", "uniwersytety"), 1));
        assertEquals(PostepMethods.AVAILABLE, PostepMethods.status(TREE.get(3), Set.of("zegar", "gotyk", "uniwersytety"), 2));
    }

    @Test
    void pathPutsPrerequisitesFirst() {
        assertEquals(List.of("zegar", "gotyk", "uniwersytety", "druk"),
            PostepMethods.path(TREE, "druk", Set.of()).stream().map(PostepApi.DiscoveryInfo::id).toList());
        assertEquals(List.of("gotyk", "uniwersytety"),
            PostepMethods.path(TREE, "uniwersytety", Set.of("zegar")).stream().map(PostepApi.DiscoveryInfo::id).toList());
        assertTrue(PostepMethods.path(TREE, "nieznane", Set.of()).isEmpty());
    }

    @Test
    void pathSurvivesCycles() {
        final List<PostepApi.DiscoveryInfo> cyclic = List.of(d("a", 1, 1, "b"), d("b", 1, 1, "a"));
        assertEquals(2, PostepMethods.path(cyclic, "a", Set.of()).size());
    }
}
