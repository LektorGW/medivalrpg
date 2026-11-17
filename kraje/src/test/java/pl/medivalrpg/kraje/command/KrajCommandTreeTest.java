// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.listener.ChatService;

/** Structure and parsing of the Brigadier trees; no server needed (nothing is executed). */
class KrajCommandTreeTest {

    private static CommandSourceStack source(final boolean admin) {
        final CommandSender sender = (CommandSender) Proxy.newProxyInstance(KrajCommandTreeTest.class.getClassLoader(),
            new Class<?>[] {CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> admin || KrajCommandTree.PERM_USE.equals(String.valueOf(args[0]));
                case "getName" -> "Tester";
                case "toString" -> "Tester";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return (CommandSourceStack) Proxy.newProxyInstance(KrajCommandTreeTest.class.getClassLoader(),
            new Class<?>[] {CommandSourceStack.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getSender" -> sender;
                case "hashCode" -> 2;
                case "equals" -> proxy == args[0];
                case "toString" -> "source";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static LiteralCommandNode<CommandSourceStack> tree() {
        return new KrajCommandTree(null).build();
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        final CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        d.getRoot().addChild(tree());
        return d;
    }

    private static Set<String> childNames(final CommandNode<CommandSourceStack> node) {
        return node.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet());
    }

    private static ArgumentCommandNode<CommandSourceStack, ?> onlyArgument(final CommandNode<CommandSourceStack> node) {
        final List<CommandNode<CommandSourceStack>> args = node.getChildren().stream()
            .filter(c -> c instanceof ArgumentCommandNode).toList();
        assertEquals(1, args.size(), node.getName());
        return (ArgumentCommandNode<CommandSourceStack, ?>) args.get(0);
    }

    /** Nodes matched by parsing {@code input} (all of it must be consumed). */
    private static List<String> parsedPath(final String input, final boolean admin) {
        final ParseResults<CommandSourceStack> parse = dispatcher().parse(input, source(admin));
        assertFalse(parse.getReader().canRead(), "not fully parsed: " + input);
        assertNotNull(parse.getContext().getCommand(), "no command for: " + input);
        return parse.getContext().getNodes().stream().map(ParsedCommandNode::getNode).map(CommandNode::getName).toList();
    }

    @Test
    void subcommandsAreLiteralsWithPolishArgumentNames() {
        final LiteralCommandNode<CommandSourceStack> root = tree();
        assertTrue(childNames(root).containsAll(Set.of("pomoc", "zaloz", "zajmij", "porzuc", "zapros", "dolacz", "opusc",
            "wyrzuc", "awansuj", "degraduj", "przekaz", "rozwiaz", "usun", "kolor", "opis", "stolica", "spawn", "info",
            "lista", "mapa", "sojusz", "wojna", "pokoj", "zerwij", "czat", "admin",
            "oblezenie", "oblezenia", "skarbiec", "wplac", "wyplac", "podatek")));
        assertEquals("nazwa", onlyArgument(root.getChild("zaloz")).getName());
        for (final String sub : List.of("zapros", "wyrzuc", "awansuj", "degraduj", "przekaz")) {
            assertEquals("gracz", onlyArgument(root.getChild(sub)).getName(), sub);
            assertNotNull(onlyArgument(root.getChild(sub)).getCustomSuggestions(), sub);
        }
        for (final String sub : List.of("dolacz", "info", "sojusz", "wojna", "pokoj", "zerwij")) {
            assertEquals("kraj", onlyArgument(root.getChild(sub)).getName(), sub);
            assertNotNull(onlyArgument(root.getChild(sub)).getCustomSuggestions(), sub);
        }
        assertEquals("kolor", onlyArgument(root.getChild("kolor")).getName());
        assertEquals("tekst", onlyArgument(root.getChild("opis")).getName());
        assertEquals(StringArgumentType.StringType.GREEDY_PHRASE,
            ((StringArgumentType) onlyArgument(root.getChild("opis")).getType()).getType());
        assertEquals("strona", onlyArgument(root.getChild("lista")).getName());
        assertEquals("strona", onlyArgument(root.getChild("pomoc")).getName());
        assertEquals(Set.of(KrajCommand.CONFIRM), childNames(root.getChild("rozwiaz")));
        assertEquals(Set.of(KrajCommand.CONFIRM), childNames(root.getChild("usun")));
        assertEquals(Set.of(KrajCommand.CONFIRM), childNames(onlyArgument(root.getChild("przekaz"))));
        assertEquals(Set.of("usun", "wladca", "zajmij", "tp", "porzuc", "przylacz", "przywroc", "bypass", "spy", "reload", "tick", "oblezenie"),
            childNames(root.getChild("admin")));
        for (final String sub : List.of("wplac", "wyplac", "podatek")) {
            assertEquals("kwota", onlyArgument(root.getChild(sub)).getName(), sub);
            assertNotNull(onlyArgument(root.getChild(sub)).getCustomSuggestions(), sub);
        }
        assertTrue(root.getChild("oblezenie").getChildren().isEmpty());
        assertTrue(root.getChild("desant").getChildren().isEmpty());
        assertEquals(List.of("kraj","desant"),parsedPath("kraj desant",false));
    }

    @Test
    void adminBranchNeedsPermission() {
        final LiteralCommandNode<CommandSourceStack> root = tree();
        assertTrue(root.canUse(source(false)));
        assertFalse(root.getChild("admin").canUse(source(false)));
        assertTrue(root.getChild("admin").canUse(source(true)));
        final ParseResults<CommandSourceStack> parse = dispatcher().parse("kraj admin reload", source(false));
        assertTrue(parse.getReader().canRead(), "non-admin must not reach /kraj admin reload");
        assertEquals(List.of("kraj", "admin", "reload"), parsedPath("kraj admin reload", true));
    }

    @Test
    void parsesPolishCountryNamesAndConfirmations() {
        assertEquals(List.of("kraj", "zaloz", "nazwa"), parsedPath("kraj zaloz Królestwo_Łąk", false));
        assertEquals(List.of("kraj", "info", "kraj"), parsedPath("kraj info Księstwo", false));
        assertEquals(List.of("kraj", "sojusz", "kraj"), parsedPath("kraj sojusz Żmudź", false));
        assertEquals(List.of("kraj", "kolor", "kolor"), parsedPath("kraj kolor #E53935", false));
        assertEquals(List.of("kraj", "opis", "tekst"), parsedPath("kraj opis Kraj <red>nie</red> tagów", false));
        assertEquals(List.of("kraj", "przekaz", "gracz", "potwierdz"), parsedPath("kraj przekaz Gracz potwierdz", false));
        assertEquals(List.of("kraj", "rozwiaz", "potwierdz"), parsedPath("kraj rozwiaz potwierdz", false));
        assertEquals(List.of("kraj", "admin", "wladca", "kraj i gracz"), parsedPath("kraj admin wladca Żmudź Gracz", true));
        assertEquals(List.of("kraj"), parsedPath("kraj", false));
        assertEquals(List.of("kraj", "wplac", "kwota"), parsedPath("kraj wplac 1zł 5sr monety", false));
        assertEquals(List.of("kraj", "podatek", "kwota"), parsedPath("kraj podatek 20", false));
        assertEquals(List.of("kraj", "oblezenie"), parsedPath("kraj oblezenie", false));
        assertEquals(List.of("kraj", "admin", "tick"), parsedPath("kraj admin tick", true));
        assertEquals(List.of("kraj", "admin", "tp", "kraj"), parsedPath("kraj admin tp Żmudź", true));
    }

    @Test
    void adminAreaToolsParse() {
        assertEquals(List.of("kraj", "admin", "zajmij", "kraj i obszar"), parsedPath("kraj admin zajmij Żmudź", true));
        assertEquals(List.of("kraj", "admin", "zajmij", "kraj i obszar"), parsedPath("kraj admin zajmij Żmudź promien 5 potwierdz", true));
        assertEquals(List.of("kraj", "admin", "zajmij", "kraj i obszar"), parsedPath("kraj admin zajmij Żmudź zaznaczenie", true));
        assertEquals(List.of("kraj", "admin", "porzuc"), parsedPath("kraj admin porzuc", true));
        assertEquals(List.of("kraj", "admin", "porzuc", "kraj i obszar"), parsedPath("kraj admin porzuc Żmudź promien 2", true));
        assertEquals(List.of("kraj", "admin", "przylacz", "kraj i pierścienie"), parsedPath("kraj admin przylacz Żmudź 2", true));
        final LiteralCommandNode<CommandSourceStack> admin = (LiteralCommandNode<CommandSourceStack>) tree().getChild("admin");
        for (final String sub : List.of("zajmij", "porzuc", "przylacz")) {
            assertNotNull(onlyArgument(admin.getChild(sub)).getCustomSuggestions(), sub);
            assertNotNull(admin.getChild(sub).getCommand(), sub);
        }
    }

    @Test
    void unknownInputGoesToOldDispatcher() {
        assertEquals(List.of("kraj", "komenda"), parsedPath("kraj nieznana", false));
        assertEquals(List.of("kraj", "komenda"), parsedPath("kraj ZALOZ Aldoria", false));
        assertEquals(List.of("kraj", "zajmij"), parsedPath("kraj zajmij", false));
    }

    @Test
    void helpEntriesMatchTree() {
        final LiteralCommandNode<CommandSourceStack> root = tree();
        for (final HelpBook.Entry e : HelpBook.ENTRIES) {
            if (!e.syntax().startsWith("/kraj ")) {
                continue;
            }
            CommandNode<CommandSourceStack> node = root;
            for (final String word : e.syntax().substring("/kraj ".length()).split(" ")) {
                if (word.startsWith("<") || word.startsWith("[")) {
                    final String name = word.substring(1, word.length() - 1);
                    final ArgumentCommandNode<CommandSourceStack, ?> arg = onlyArgument(node);
                    assertTrue(arg.getName().startsWith(name), e.id() + ": " + arg.getName() + " vs " + name);
                    break; // greedy arguments (e.g. "kraj i gracz") end the path
                }
                node = node.getChild(word);
                assertNotNull(node, e.id() + ": missing literal " + word);
            }
        }
    }

    @Test
    void chatAndMapCommands() {
        for (final String name : List.of("kc", "ks")) {
            final LiteralCommandNode<CommandSourceStack> node = new ChannelCommand(null, ChatService.Channel.COUNTRY).build(name);
            assertEquals(name, node.getName());
            final ArgumentCommandNode<CommandSourceStack, ?> arg = onlyArgument(node);
            assertEquals("wiadomość", arg.getName());
            assertInstanceOf(StringArgumentType.class, arg.getType());
            assertEquals(StringArgumentType.StringType.GREEDY_PHRASE, ((StringArgumentType) arg.getType()).getType());
        }
        final LiteralCommandNode<CommandSourceStack> map = new MapLinkCommand(null).build();
        assertEquals("mapa", map.getName());
        assertTrue(map.getChildren().isEmpty());
        assertNotNull(map.getCommand());
    }
}
