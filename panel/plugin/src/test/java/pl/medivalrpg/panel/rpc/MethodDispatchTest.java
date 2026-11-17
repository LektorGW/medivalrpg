// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * MethodSpec ↔ PanelMethods: every registered method (AccessMatrixTest checks its permission) is routed to a handler that
 * knows it, and every routed name is registered — a method missing from MethodSpec is refused before dispatch, a method
 * missing from the dispatcher would reach {@code NpcV2Methods}' "Nieznana metoda". Reads the dispatcher sources.
 */
class MethodDispatchTest {

    private static final Path DIR = Path.of("src/main/java/pl/medivalrpg/panel/rpc");
    private static final Pattern CASE = Pattern.compile("case\\s+((?:\"[^\"]+\"\\s*,\\s*)*\"[^\"]+\")\\s*->\\s*([^;]+);");
    private static final Pattern NAME = Pattern.compile("\"([^\"]+)\"");

    /** {@code name → handler expression} of the {@code case "a", "b" -> handler;} labels of one source file. */
    private static Map<String, String> cases(final String file) throws IOException {
        final String src = Files.readString(DIR.resolve(file), StandardCharsets.UTF_8);
        final Map<String, String> out = new LinkedHashMap<>();
        final Matcher m = CASE.matcher(src);
        while (m.find()) {
            final Matcher n = NAME.matcher(m.group(1));
            while (n.find()) {
                out.put(n.group(1), m.group(2).trim());
            }
        }
        return out;
    }

    private static Set<String> rpcNames(final Map<String, String> cases) {
        final Set<String> out = new TreeSet<>();
        for (final String name : cases.keySet()) {
            if (name.contains(".") || name.equals("me") || name.equals("overview")) {
                out.add(name);
            }
        }
        return out;
    }

    @Test
    void everyRegisteredMethodHasAHandlerAndNothingElseIsRouted() throws IOException {
        final Map<String, String> top = cases("PanelMethods.java");
        final Set<String> v2 = rpcNames(cases("NpcV2Methods.java"));
        final Set<String> plan = rpcNames(cases("PlanMethods.java"));
        final Set<String> extra = rpcNames(cases("NpcExtraMethods.java"));
        final Set<String> postep = rpcNames(cases("PostepMethods.java"));
        final Set<String> routed = new LinkedHashSet<>(rpcNames(top));
        routed.addAll(v2); // PanelMethods' default branch
        assertEquals(new TreeSet<>(MethodSpec.ALL.keySet()), new TreeSet<>(routed), "MethodSpec ↔ dispatcher");
        for (final String name : MethodSpec.ALL.keySet()) {
            final String handler = top.get(name);
            if (handler == null) {
                assertTrue(v2.contains(name), name + " → NpcV2Methods");
            } else if (handler.startsWith("this.plan.")) {
                assertTrue(plan.contains(name), name + " → PlanMethods");
            } else if (handler.startsWith("this.extra.invoke")) {
                assertTrue(extra.contains(name), name + " → NpcExtraMethods");
            } else if (handler.startsWith("this.postep.")) {
                assertTrue(postep.contains(name), name + " → PostepMethods");
            } else if (handler.equals("this.v2.invoke(c)")) {
                assertTrue(v2.contains(name), name + " → NpcV2Methods");
            }
        }
    }

    @Test
    void ownerOnlyBridgesStayOnTheirModules() throws IOException {
        final Map<String, String> top = cases("PanelMethods.java");
        for (final String name : MethodSpec.ALL.keySet()) {
            if (name.startsWith("world.stats.")) {
                assertEquals("WorldMethods.stats(c)", top.get(name), name);
            } else if (name.startsWith("world.")) {
                assertEquals("WorldMethods.invoke(c)", top.get(name), name);
            } else if (name.startsWith("railway.")) {
                assertEquals("RailwayMethods.invoke(c)", top.get(name), name);
            } else if (name.startsWith("firmy.")) {
                assertEquals("FirmyMethods.invoke(c)", top.get(name), name);
            }
        }
    }
}
