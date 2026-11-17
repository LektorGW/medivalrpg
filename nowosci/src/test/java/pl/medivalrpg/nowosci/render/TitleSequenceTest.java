// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.nowosci.Fixtures;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.Entry;
import pl.medivalrpg.nowosci.model.ShowMode;
import pl.medivalrpg.nowosci.render.TitleSequence.Slide;

class TitleSequenceTest {

    private final EntryFormat format = Fixtures.format();
    private final TitleSequence titles = new TitleSequence(this.format);
    private final Changelog changelog = Fixtures.bundledChangelog();

    private static String plain(final Component c) {
        return EntryFormat.plain(c);
    }

    private static boolean hasClickOrHover(final Component c) {
        if (c.clickEvent() != null || c.hoverEvent() != null) {
            return true;
        }
        return c.children().stream().anyMatch(TitleSequenceTest::hasClickOrHover);
    }

    private static TextColor firstColor(final Component c) {
        if (c.color() != null) {
            return c.color();
        }
        return c.children().stream().map(TitleSequenceTest::firstColor).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    @Test
    void introThenOneCardPerEntryUpToMaxEntries() {
        final List<Slide> slides = this.titles.forEntries(this.changelog.entries()); // 5 unread, max-entries 3
        assertEquals(4, slides.size());
        assertEquals("✦ Nowości ✦", plain(slides.getFirst().title()));
        assertEquals("na serwerze MEDIVALRPG", plain(slides.getFirst().subtitle()));
        assertEquals("⚙ Porządki na serwerze", plain(slides.get(1).title()));
        assertEquals("✦ Interakcje", plain(slides.get(2).title()));
        assertEquals("✦ Mapa świata", plain(slides.get(3).title()));
        // title in the category colour (chat colours: zmiana orange, nowe green)
        assertEquals(TextColor.color(0xFFAA00), firstColor(slides.get(1).title()));
        assertEquals(TextColor.color(0x55FF55), firstColor(slides.get(2).title()));
    }

    @Test
    void subtitleIsFirstPointShortenedWithoutTagsOrClicks() {
        final List<Slide> slides = this.titles.forEntries(List.of(this.changelog.byId(1).orElseThrow()));
        final String subtitle = plain(slides.get(1).subtitle());
        assertTrue(subtitle.length() <= 40, subtitle);
        assertTrue(subtitle.startsWith("Wpisz /glos i kliknij link"), subtitle);
        assertTrue(subtitle.endsWith("…"), subtitle);
        assertTrue(!subtitle.contains("<"), subtitle);
        for (final Slide s : slides) {
            assertTrue(!hasClickOrHover(s.title()) && !hasClickOrHover(s.subtitle()));
        }
        assertEquals("Krótki punkt.", this.titles.subtitleText("<cmd>Krótki</cmd> punkt."));
    }

    @Test
    void entryWithoutPointsShowsCategoryAndDate() {
        final Entry e = new Entry(9, java.time.LocalDate.of(2026, 10, 26), "Pusty", "poprawka", List.of());
        assertEquals("Poprawka · 26.10.2026", plain(this.titles.forEntries(List.of(e)).get(1).subtitle()));
    }

    @Test
    void longTitlesAreShortened() {
        final Entry e = new Entry(9, null, "Bardzo długi tytuł wpisu, który nie zmieści się na ekranie", "nowe", List.of("x"));
        final String title = plain(this.titles.forEntries(List.of(e)).get(1).title());
        assertTrue(title.length() <= 2 + 28, title);
        assertTrue(title.endsWith("…"), title);
    }

    @Test
    void soundsRiseInPitchAndIntroUsesItsOwnSound() {
        final List<Slide> slides = this.titles.forEntries(this.changelog.entries());
        assertEquals("ui.toast.challenge_complete", slides.getFirst().sound().name().value());
        final List<Float> pitches = new ArrayList<>();
        for (final Slide s : slides.subList(1, slides.size())) {
            assertEquals("block.note_block.chime", s.sound().name().value());
            pitches.add(s.sound().pitch());
        }
        assertTrue(pitches.get(0) < pitches.get(1) && pitches.get(1) < pitches.get(2), pitches.toString());
        assertTrue(slides.getFirst().sound().volume() < 0.5f); // quiet intro
    }

    @Test
    void soundsCanBeDisabled() {
        final TitleSequence silent = new TitleSequence(Fixtures.format(Map.of(
            "title-sequence.intro-sound.key", "", "title-sequence.entry-sound.key", "")));
        for (final Slide s : silent.forEntries(this.changelog.entries())) {
            assertNull(s.sound());
        }
    }

    @Test
    void timesAndSmoothSpacing() {
        assertEquals(Duration.ofMillis(500), this.titles.times().fadeIn());
        assertEquals(Duration.ofMillis(2500), this.titles.times().stay());
        assertEquals(Duration.ofMillis(500), this.titles.times().fadeOut());
        assertEquals(70, this.titles.slideTicks()); // next card starts when the previous one has faded out
    }

    @Test
    void welcomeCard() {
        final List<Slide> welcome = this.titles.welcome();
        assertEquals(1, welcome.size());
        assertNotNull(welcome.getFirst().sound());
        assertEquals("✦ Witaj ✦", plain(welcome.getFirst().title()));
    }

    @Test
    void showModesFromConfig() {
        assertEquals(EnumSet.allOf(ShowMode.class), this.format.config().modes);
        assertEquals(EnumSet.of(ShowMode.CHAT, ShowMode.TITLE),
            Fixtures.format(Map.of("show.mode", List.of("chat", "TITLE"))).config().modes);
        assertEquals(EnumSet.of(ShowMode.BOOK), Fixtures.format(Map.of("show.mode", "book")).config().modes);
        final List<String> unknown = new ArrayList<>();
        assertEquals(EnumSet.of(ShowMode.CHAT), ShowMode.parse(List.of("chat", "ekran"), unknown));
        assertEquals(List.of("ekran"), unknown);
    }
}
