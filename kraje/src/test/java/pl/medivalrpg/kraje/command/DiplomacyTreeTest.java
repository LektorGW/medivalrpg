// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/** Brigadier nodes of /kraj unia and /kraj ambasada (no server needed, nothing is executed). */
class DiplomacyTreeTest {

    private static CommandSourceStack source() {
        final CommandSender sender = (CommandSender) Proxy.newProxyInstance(DiplomacyTreeTest.class.getClassLoader(),
            new Class<?>[] {CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> KrajCommandTree.PERM_USE.equals(String.valueOf(args[0]));
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> "Tester";
            });
        return (CommandSourceStack) Proxy.newProxyInstance(DiplomacyTreeTest.class.getClassLoader(),
            new Class<?>[] {CommandSourceStack.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getSender" -> sender;
                case "hashCode" -> 2;
                case "equals" -> proxy == args[0];
                default -> "source";
            });
    }

    private static List<String> parsedPath(final String input) {
        final CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        d.getRoot().addChild(new KrajCommandTree(null).build());
        final ParseResults<CommandSourceStack> parse = d.parse(input, source());
        assertFalse(parse.getReader().canRead(), "not fully parsed: " + input);
        assertNotNull(parse.getContext().getCommand(), "no command for: " + input);
        return parse.getContext().getNodes().stream().map(ParsedCommandNode::getNode).map(CommandNode::getName).toList();
    }

    @Test
    void unionAndEmbassyBranches() {
        final CommandNode<CommandSourceStack> root = new KrajCommandTree(null).build();
        final CommandNode<CommandSourceStack> unia = root.getChild("unia");
        final CommandNode<CommandSourceStack> ambasada = root.getChild("ambasada");
        assertNotNull(unia.getCommand(), "/kraj unia alone shows the union");
        assertNotNull(ambasada.getCommand(), "/kraj ambasada alone lists embassies");
        assertEquals(Set.of("zaproponuj", "przyjmij", "odrzuc", "zerwij", "info", "rzadz"),
            unia.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet()));
        assertEquals(Set.of("otworz", "przyjmij", "odrzuc", "zamknij", "wydal", "lista", "tp"),
            ambasada.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet()));
        for (final String sub : List.of("zaproponuj", "przyjmij", "odrzuc", "info")) {
            final CommandNode<CommandSourceStack> arg = unia.getChild(sub).getChildren().iterator().next();
            assertTrue(arg instanceof ArgumentCommandNode<?, ?> a && a.getCustomSuggestions() != null, sub);
            assertEquals("kraj", arg.getName(), sub);
        }
        for (final CommandNode<CommandSourceStack> sub : ambasada.getChildren()) {
            final CommandNode<CommandSourceStack> arg = sub.getChildren().iterator().next();
            assertEquals("kraj", arg.getName(), sub.getName());
            assertTrue(arg instanceof ArgumentCommandNode<?, ?> a && a.getCustomSuggestions() != null, sub.getName());
        }
    }

    @Test
    void parsesPolishNamesAndConfirmation() {
        assertEquals(List.of("kraj", "unia", "zaproponuj", "kraj"), parsedPath("kraj unia zaproponuj Żmudź"));
        assertEquals(List.of("kraj", "unia", "przyjmij", "kraj"), parsedPath("kraj unia przyjmij Księstwo"));
        assertEquals(List.of("kraj", "unia", "zerwij", "potwierdz"), parsedPath("kraj unia zerwij potwierdz"));
        assertEquals(List.of("kraj", "unia", "rzadz"), parsedPath("kraj unia rzadz"));
        assertEquals(List.of("kraj", "unia"), parsedPath("kraj unia"));
        assertEquals(List.of("kraj", "ambasada", "otworz", "kraj"), parsedPath("kraj ambasada otworz Łęczyca"));
        assertEquals(List.of("kraj", "ambasada", "wydal", "kraj"), parsedPath("kraj ambasada wydal Żmudź"));
        assertEquals(List.of("kraj", "ambasada", "tp", "kraj"), parsedPath("kraj ambasada tp Żmudź"));
        assertEquals(List.of("kraj", "ambasada", "lista"), parsedPath("kraj ambasada lista"));
    }

    @Test
    void menuCatalogContainsOnlyExecutableCommandPaths() throws Exception {
        final org.bukkit.configuration.file.YamlConfiguration menu = new org.bukkit.configuration.file.YamlConfiguration();
        menu.load(new java.io.File("MENU-ADDITIONS.yml"));
        int commands = 0;
        for (final String key : menu.getKeys(true)) {
            if (menu.isString(key)) {
                final String command = menu.getString(key);
                assertTrue(command.startsWith("/kraj "), key);
                parsedPath(command.substring(1).replace("{kraj}", "Żmudź"));
                commands++;
            }
        }
        assertEquals(23, commands); // 14 + 6 chorągwi + 3 sztandary (2026-10-29)
    }

    /**
     * Writes the executable usage paths of the real {@code /kraj} tree in the registry format of the Menu tests
     * ({@code -DkrajeCatalogOut=<file>}; skipped otherwise). Used for verify-flags-banners-20261029/catalog-banners-raw.json.
     */
    @Test
    void writeCommandRegistryWhenAsked() throws Exception {
        final String out = System.getProperty("krajeCatalogOut", System.getenv("KRAJE_CATALOG_OUT"));
        org.junit.jupiter.api.Assumptions.assumeTrue(out != null && !out.isBlank(), "no output requested");
        final CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        d.getRoot().addChild(new KrajCommandTree(null).build());
        final String[] usages = d.getAllUsage(d.getRoot().getChild("kraj"), source(), false);
        final com.google.gson.JsonArray paths = new com.google.gson.JsonArray();
        for (final String u : usages) {
            paths.add("/kraj " + u);
        }
        paths.add("/kraj");
        final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("name", "kraj");
        root.add("executableUsagePaths", paths);
        final com.google.gson.JsonArray roots = new com.google.gson.JsonArray();
        roots.add(root);
        final com.google.gson.JsonObject plugin = new com.google.gson.JsonObject();
        plugin.addProperty("name", "Kraje");
        plugin.addProperty("enabled", true);
        final com.google.gson.JsonArray plugins = new com.google.gson.JsonArray();
        plugins.add(plugin);
        final com.google.gson.JsonObject meta = new com.google.gson.JsonObject();
        meta.add("plugins", plugins);
        meta.addProperty("source", "KrajCommandTree (Brigadier, sztandary 2026-10-29), DiplomacyTreeTest#writeCommandRegistryWhenAsked");
        final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("schemaVersion", 1);
        json.add("roots", roots);
        json.add("bounds", new com.google.gson.JsonObject());
        json.add("metadata", meta);
        java.nio.file.Files.writeString(java.nio.file.Path.of(out), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json));
        assertTrue(usages.length > 50);
    }
}
