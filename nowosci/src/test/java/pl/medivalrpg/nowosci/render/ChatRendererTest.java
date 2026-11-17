// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.nowosci.Fixtures;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.NewPlayerMode;
import pl.medivalrpg.nowosci.model.SeenState;
import pl.medivalrpg.nowosci.model.UnreadSelector;
import pl.medivalrpg.nowosci.storage.SeenStore;

class ChatRendererTest {

    private final ChatRenderer chat = new ChatRenderer(Fixtures.format());
    private final Changelog changelog = Fixtures.bundledChangelog();

    private static List<String> commands(final List<Component> lines) {
        final List<String> out = new ArrayList<>();
        lines.forEach(l -> collect(l, out));
        return out;
    }

    private static void collect(final Component c, final List<String> out) {
        final ClickEvent<?> click = c.clickEvent();
        if (click != null && click.payload() instanceof ClickEvent.Payload.Text t) {
            out.add(t.value());
        }
        c.children().forEach(child -> collect(child, out));
    }

    private static String text(final List<Component> lines) {
        return String.join("\n", lines.stream().map(EntryFormat::plain).toList());
    }

    @Test
    void loginBlockShowsThreeNewestAndButtons() {
        final var selection = UnreadSelector.select(this.changelog, null, false, NewPlayerMode.WELCOME, 3, 3);
        final List<Component> lines = this.chat.loginBlock(selection);
        final String text = text(lines);
        assertTrue(text.contains("Nowości na serwerze"), text);
        assertTrue(text.contains("5 nowych wpisów"), text);
        assertTrue(text.contains("Porządki na serwerze") && text.contains("Interakcje") && text.contains("Mapa świata"), text);
        assertTrue(!text.contains("Czat głosowy"), text);
        assertTrue(text.contains("jeszcze 2 wpisy"), text);
        assertTrue(text.contains("[Pokaż wszystko]") && text.contains("[Nie pokazuj tych więcej]"), text);
        final List<String> cmds = commands(lines);
        assertTrue(cmds.contains(ChatRenderer.CMD_BOOK));
        assertTrue(cmds.contains(ChatRenderer.CMD_READ));
    }

    @Test
    void chatPagesAndNavigation() {
        assertEquals(3, this.chat.chatPages(this.changelog)); // 5 entries, 2 per page
        final List<Component> first = this.chat.chatPage(this.changelog, 1);
        assertTrue(text(first).contains("strona 1/3"));
        assertTrue(commands(first).contains("/nowosci czat 2"));
        final List<Component> clamped = this.chat.chatPage(this.changelog, 99);
        assertTrue(text(clamped).contains("Czat głosowy w przeglądarce"));
        assertTrue(commands(clamped).contains("/nowosci czat 2"));
    }

    @Test
    void helpHidesAdminCommandsFromPlayers() {
        assertTrue(!text(this.chat.help(false)).contains("/nowosci dodaj"));
        assertTrue(text(this.chat.help(true)).contains("/nowosci dodaj"));
    }

    @Test
    void polishPlural() {
        final List<String> f = List.of("wpis", "wpisy", "wpisów");
        assertEquals("wpis", NowosciConfig.plural(f, 1));
        assertEquals("wpisy", NowosciConfig.plural(f, 3));
        assertEquals("wpisów", NowosciConfig.plural(f, 5));
        assertEquals("wpisów", NowosciConfig.plural(f, 12));
        assertEquals("wpisy", NowosciConfig.plural(f, 22));
        assertEquals("wpisów", NowosciConfig.plural(f, 0));
    }

    @Test
    void seenStoreJsonRoundTrip() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final var map = java.util.Map.of(a, new SeenState(5, 1), b, new SeenState(0, 0, 4, 1));
        assertEquals(map, SeenStore.decode(SeenStore.encode(map)));
        assertEquals(java.util.Map.of(a, new SeenState(3, 0)), SeenStore.decode("{\"" + a + "\": 3, \"zly\": 1}"));
    }

    @Test
    void reminderHasCountAndBothButtons() {
        final Component line = this.chat.reminder(3);
        assertTrue(EntryFormat.plain(line).contains("3 nowe wpisy"), EntryFormat.plain(line));
        assertEquals(List.of("/nowosci", "/nowosci przeczytane"), commands(List.of(line)));
        assertTrue(EntryFormat.plain(this.chat.reminderBar(1)).contains("1 nowy wpis"));
    }

    @Test
    void defaultsKeepRemindingUntilRead() {
        final NowosciConfig cfg = Fixtures.format().config();
        assertEquals(0, cfg.autoMarkAfterShows);
        assertTrue(!cfg.autoBookMarksRead);
        assertTrue(cfg.reminderBossBar && cfg.announceNew);
        assertEquals(10 * 60 * 20, cfg.reminderChatTicks);
        assertEquals(15 * 20, cfg.watchTicks);
    }
}