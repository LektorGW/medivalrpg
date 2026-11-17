// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class InterakcjeHookTest {

    /** Same keys as the Interakcje plugin's config.yml. */
    private static final String YAML = """
        interactions:
          kiss:
            name: "<red>Całus</red>"
            description: "Pocałuj drugą osobę."
            command: pocaluj
            aliases: [calus, kiss]
            icon: POPPY
            consent: true
            target: required
            range: 3.0
          slap:
            name: "<dark_red>Liść</dark_red>"
            command: liść
            aliases: [lisc, slap]
            icon: leather
            target: required
          wave:
            name: "<aqua>Pomachanie</aqua>"
            command: pomachaj
            target: optional
            range: 10.0
          off:
            enabled: false
            command: nic
          broken:
            command: "dwa slowa"
        """;

    private static List<InterakcjeHook.InteractionEntry> parse() {
        final YamlConfiguration c = new YamlConfiguration();
        try {
            c.loadFromString(YAML);
        } catch (final InvalidConfigurationException e) {
            throw new AssertionError(e);
        }
        return InterakcjeHook.parse(c.getConfigurationSection("interactions"));
    }

    @Test
    void readsInteractions() {
        final List<InterakcjeHook.InteractionEntry> list = parse();
        assertEquals(List.of("kiss", "slap", "wave"), list.stream().map(InterakcjeHook.InteractionEntry::id).toList());

        final var kiss = list.get(0);
        assertEquals("pocaluj", kiss.label());
        assertEquals("POPPY", kiss.icon());
        assertTrue(kiss.consent());
        assertFalse(kiss.optionalTarget());
        assertEquals(3.0, kiss.range());
        assertEquals("interakcje.kiss", kiss.permission());

        final var slap = list.get(1);
        assertEquals("lisc", slap.label(), "ASCII alias preferred over a label with Polish letters");
        assertEquals("LEATHER", slap.icon());
        assertEquals(3.0, slap.range(), "default range");
        assertFalse(slap.consent());

        final var wave = list.get(2);
        assertTrue(wave.optionalTarget());
        assertEquals(10.0, wave.range());
        assertEquals("PAPER", wave.icon(), "default icon");
    }

    @Test
    void nullSectionIsEmpty() {
        assertTrue(InterakcjeHook.parse(null).isEmpty());
    }

    @Test
    void dancesThenEmotesFromConfig() throws InvalidConfigurationException {
        final YamlConfiguration c = new YamlConfiguration();
        c.loadFromString("""
            dances:
              command: taniec
              list:
                "1": { icon: NOTE_BLOCK, name: "<gold>Taniec ludowy</gold>", description: "Przytupy." }
                "2": { icon: jukebox, name: "Dworski", enabled: false }
            emotes:
              command: emotka
              aliases: [emote, em]
              list:
                klaskanie: { icon: GOLD_NUGGET, name: "Klaskanie" }
                poklon: { icon: GOLDEN_HELMET, name: "Pokłon", target: optional }
            """);
        final List<InterakcjeHook.MotionEntry> m = InterakcjeHook.parseMotions(c);
        assertEquals(List.of("taniec 1", "emotka klaskanie", "emotka poklon"),
            m.stream().map(InterakcjeHook.MotionEntry::command).toList());
        assertTrue(m.getFirst().dance());
        assertEquals("interakcje.dance", m.getFirst().permission());
        assertEquals("interakcje.emote", m.get(1).permission());
        assertFalse(m.get(1).optionalTarget());
        assertTrue(m.get(2).optionalTarget());
        assertEquals("GOLDEN_HELMET", m.get(2).icon());

        final YamlConfiguration off = new YamlConfiguration();
        off.loadFromString("dances:\n  enabled: false\n  list:\n    '1': {}\n");
        assertTrue(InterakcjeHook.parseMotions(off).isEmpty());
        assertTrue(InterakcjeHook.parseMotions(null).isEmpty());
    }
}
