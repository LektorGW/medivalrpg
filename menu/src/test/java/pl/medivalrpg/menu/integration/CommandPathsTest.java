// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandPathsTest {

    private static CommandDispatcher<Object> tree() {
        final CommandDispatcher<Object> d = new CommandDispatcher<>();
        // Brigadier command with literals (stage 1 of Firmy) and an alias with a redirect.
        final var firma = d.register(LiteralArgumentBuilder.literal("firma")
            .then(LiteralArgumentBuilder.literal("ranking"))
            .then(LiteralArgumentBuilder.literal("info").then(RequiredArgumentBuilder.argument("firma", StringArgumentType.word()))));
        d.register(LiteralArgumentBuilder.literal("kompania").redirect(firma));
        // Bukkit/plugin.yml command wrapped as a single greedy argument (Kolej's style).
        d.register(LiteralArgumentBuilder.literal("kolej")
            .then(RequiredArgumentBuilder.argument("polecenie", StringArgumentType.greedyString())));
        // A root that requires a predicate (e.g. only one UUID): existence ignores requirements.
        d.register(LiteralArgumentBuilder.literal("skarbswiata").requires(s -> false)
            .then(LiteralArgumentBuilder.literal("wszystkimnpc")));
        return d;
    }

    @Test void literalPathsExistOnlyWhenRegistered() {
        final var root = tree().getRoot();
        assertTrue(CommandPaths.exists(root, "firma"));
        assertTrue(CommandPaths.exists(root, "/firma ranking"));
        assertTrue(CommandPaths.exists(root, "FIRMA  info"));
        assertFalse(CommandPaths.exists(root, "firma kredyt"), "stage 2 sub-command is missing");
        assertFalse(CommandPaths.exists(root, "kraj flaga"), "missing root");
        assertTrue(CommandPaths.exists(root, "kompania ranking"), "alias redirect is followed");
        assertFalse(CommandPaths.exists(root, "kompania kredyt"));
        assertTrue(CommandPaths.exists(root, "kolej siec postep"), "a greedy plugin command cannot be verified deeper");
        assertTrue(CommandPaths.exists(root, "skarbswiata wszystkimnpc"), "requirements are not evaluated");
        assertFalse(CommandPaths.exists(root, "firma info gracz"), "argument values are not literal paths");
        assertFalse(CommandPaths.exists(root, ""));
        assertFalse(CommandPaths.exists(null, "firma"));
    }

    @Test void onlyPlainWordsAreAcceptedAsPaths() {
        assertEquals(List.of("firma", "kredyt"), CommandPaths.words(" /Firma   kredyt "));
        assertTrue(CommandPaths.literal("firma kredyt"));
        assertTrue(CommandPaths.literal("nk wojny"));
        assertFalse(CommandPaths.literal("firma <kwota>"));
        assertFalse(CommandPaths.literal("glos szept|krzyk"));
        assertFalse(CommandPaths.literal(" "));
        assertEquals("/firma kredyt", Requirements.commandToken("/firma  Kredyt"));
        assertEquals(List.of("Firmy"), Requirements.plugins(List.of("Firmy", "/firma kredyt")));
    }
}
