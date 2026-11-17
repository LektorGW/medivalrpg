// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.nowosci.Fixtures;
import pl.medivalrpg.nowosci.model.UnreadSelector.Kind;
import pl.medivalrpg.nowosci.model.UnreadSelector.Selection;

class UnreadSelectorTest {

    private final Changelog changelog = Fixtures.bundledChangelog(); // ids 1..5

    private static List<Integer> ids(final Selection s) {
        return s.shown().stream().map(Entry::id).toList();
    }

    @Test
    void existingPlayerWithoutRecordSeesEverythingAsUnread() {
        final Selection s = UnreadSelector.select(this.changelog, null, false, NewPlayerMode.WELCOME, 3, 3);
        assertEquals(Kind.ENTRIES, s.kind());
        assertEquals(List.of(5, 4, 3), ids(s));
        assertEquals(5, s.totalUnread());
        assertEquals(2, s.hiddenCount());
        assertEquals(new SeenState(0, 1), s.state());
    }

    @Test
    void onlyEntriesNewerThanLastSeen() {
        final Selection s = UnreadSelector.select(this.changelog, new SeenState(3, 0), false, NewPlayerMode.WELCOME, 3, 3);
        assertEquals(List.of(5, 4), ids(s));
        assertEquals(2, s.totalUnread());
        assertEquals(0, s.hiddenCount());
        assertEquals(new SeenState(3, 1), s.state());
    }

    @Test
    void nothingWhenEverythingWasSeen() {
        final Selection s = UnreadSelector.select(this.changelog, new SeenState(5, 2), false, NewPlayerMode.WELCOME, 3, 3);
        assertEquals(Kind.NOTHING, s.kind());
        assertNull(s.state());
    }

    @Test
    void autoMarkAsReadAfterConfiguredShows() {
        final Selection third = UnreadSelector.select(this.changelog, new SeenState(4, 2), false, NewPlayerMode.WELCOME, 3, 3);
        assertEquals(List.of(5), ids(third)); // still shown the third time
        assertEquals(SeenState.readUpTo(5), third.state());
        final Selection never = UnreadSelector.select(this.changelog, new SeenState(4, 50), false, NewPlayerMode.WELCOME, 3, 0);
        assertEquals(new SeenState(4, 51), never.state());
    }

    @Test
    void newPlayerModes() {
        final Selection welcome = UnreadSelector.select(this.changelog, null, true, NewPlayerMode.WELCOME, 3, 3);
        assertEquals(Kind.WELCOME, welcome.kind());
        assertEquals(SeenState.readUpTo(5), welcome.state());

        final Selection newest = UnreadSelector.select(this.changelog, null, true, NewPlayerMode.NEWEST, 3, 3);
        assertEquals(Kind.ENTRIES, newest.kind());
        assertEquals(List.of(5), ids(newest));
        assertEquals(0, newest.hiddenCount());
        assertEquals(new SeenState(4, 1), newest.state());
        assertEquals(List.of(5), ids(UnreadSelector.select(this.changelog, newest.state(), false,
            NewPlayerMode.NEWEST, 3, 0)), "Closing the automatic book must not consume the newest entry");

        final Selection none = UnreadSelector.select(this.changelog, null, true, NewPlayerMode.NONE, 3, 3);
        assertEquals(Kind.NOTHING, none.kind());
        assertEquals(SeenState.readUpTo(5), none.state());

        final Selection all = UnreadSelector.select(this.changelog, null, true, NewPlayerMode.ALL, 3, 3);
        assertEquals(List.of(5, 4, 3), ids(all));
        assertEquals(5, all.totalUnread());
    }

    @Test
    void firstJoinWithStoredRecordIsTreatedNormally() {
        final Selection s = UnreadSelector.select(this.changelog, new SeenState(4, 0), true, NewPlayerMode.WELCOME, 3, 3);
        assertEquals(List.of(5), ids(s));
    }

    @Test
    void emptyChangelog() {
        final Selection s = UnreadSelector.select(Changelog.empty(), null, false, NewPlayerMode.WELCOME, 3, 3);
        assertEquals(Kind.NOTHING, s.kind());
        final Selection newest = UnreadSelector.select(Changelog.empty(), null, true, NewPlayerMode.NEWEST, 3, 3);
        assertEquals(Kind.NOTHING, newest.kind());
    }

    @Test
    void newEntryAfterReadingShowsOnlyThatEntry() {
        final Changelog more = this.changelog.with(new Entry(this.changelog.nextId(), null, "Nowy", "poprawka", List.of("x")));
        final Selection s = UnreadSelector.select(more, SeenState.readUpTo(5), false, NewPlayerMode.WELCOME, 3, 3);
        assertEquals(List.of(6), ids(s));
        assertEquals(1, s.totalUnread());
    }

    @Test
    void newPlayerModeParsing() {
        assertEquals(NewPlayerMode.NEWEST, NewPlayerMode.parse(" Newest ", NewPlayerMode.WELCOME));
        assertEquals(NewPlayerMode.WELCOME, NewPlayerMode.parse("coś", NewPlayerMode.WELCOME));
        assertEquals(NewPlayerMode.WELCOME, NewPlayerMode.parse(null, NewPlayerMode.WELCOME));
    }

    @Test
    void bookOpensByItselfAtMostConfiguredTimesPerNewestEntry() {
        final var first = UnreadSelector.decideBook(new SeenState(0, 1), 5, true, true, 1);
        assertEquals(true, first.open());
        assertEquals(new SeenState(0, 1, 5, 1), first.state());
        final var second = UnreadSelector.decideBook(first.state(), 5, true, true, 1);
        assertEquals(false, second.open());
        assertEquals(first.state(), second.state());
        // a newer entry starts the count again
        final var newer = UnreadSelector.decideBook(first.state(), 6, true, true, 1);
        assertEquals(true, newer.open());
        assertEquals(new SeenState(0, 1, 6, 1), newer.state());
        // two automatic opens allowed
        assertEquals(true, UnreadSelector.decideBook(first.state(), 5, true, true, 2).open());
    }

    @Test
    void bookOnlyIfUnreadAndDisabled() {
        assertEquals(false, UnreadSelector.decideBook(SeenState.readUpTo(5), 5, false, true, 1).open());
        assertEquals(true, UnreadSelector.decideBook(SeenState.readUpTo(5), 5, false, false, 1).open());
        assertEquals(false, UnreadSelector.decideBook(null, 5, true, true, 0).open());
        assertEquals(false, UnreadSelector.decideBook(null, 0, true, false, 1).open()); // empty changelog
        assertEquals(new SeenState(0, 0, 5, 1), UnreadSelector.decideBook(null, 5, true, true, 1).state());
    }

    @Test
    void readingKeepsTheBookCounter() {
        final SeenState s = new SeenState(2, 3, 5, 1);
        assertEquals(new SeenState(5, 0, 5, 1), s.read(5));
        final Selection auto = UnreadSelector.select(this.changelog, new SeenState(4, 2, 5, 1), false, NewPlayerMode.WELCOME, 3, 3);
        assertEquals(new SeenState(5, 0, 5, 1), auto.state());
    }
}
