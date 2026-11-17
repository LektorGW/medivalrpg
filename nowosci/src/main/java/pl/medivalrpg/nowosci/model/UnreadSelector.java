// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.model;

import java.util.List;

/**
 * Decides what a player sees after logging in. Pure function, no Bukkit calls.
 *
 * <p>A player without a stored {@link SeenState} who has played before (e.g. the plugin was installed after
 * they joined) is treated as having seen nothing, so existing players get the whole history as unread. Only a
 * player who joins the server for the first time is handled by {@link NewPlayerMode}.
 */
public final class UnreadSelector {

    public enum Kind {
        /** Nothing to show. */
        NOTHING,
        /** Welcome message for a brand-new player. */
        WELCOME,
        /** The login block with {@link Selection#shown()}. */
        ENTRIES
    }

    /**
     * @param shown       entries for the block, newest first, at most {@code maxShown}
     * @param totalUnread all unread entries (the block says "and N more" when greater than {@code shown.size()})
     * @param state       state to store after this decision; {@code null} = nothing to store
     */
    public record Selection(Kind kind, List<Entry> shown, int totalUnread, SeenState state) {

        public int hiddenCount() {
            return Math.max(0, this.totalUnread - this.shown.size());
        }
    }

    private UnreadSelector() {
    }

    /**
     * @param state       stored state, {@code null} when the player has no record yet
     * @param firstJoin   the player joins the server for the first time ever
     * @param maxShown    how many entries the login block lists at most (at least 1)
     * @param autoMarkAfter after how many shows the entries count as read without clicking (0 = never)
     */
    public static Selection select(final Changelog changelog, final SeenState state, final boolean firstJoin,
                                   final NewPlayerMode mode, final int maxShown, final int autoMarkAfter) {
        final int max = Math.max(1, maxShown);
        final SeenState allRead = SeenState.readUpTo(changelog.maxId());
        if (state == null && firstJoin && mode != NewPlayerMode.ALL) {
            return switch (mode) {
                case WELCOME -> new Selection(Kind.WELCOME, List.of(), 0, allRead);
                case NEWEST -> changelog.newest()
                    .map(e -> new Selection(Kind.ENTRIES, List.of(e), 1,
                        SeenState.readUpTo(changelog.entries().stream().filter(old -> old.id() < e.id())
                            .mapToInt(Entry::id).max().orElse(0)).shownOnce()))
                    .orElse(new Selection(Kind.NOTHING, List.of(), 0, allRead));
                default -> new Selection(Kind.NOTHING, List.of(), 0, allRead);
            };
        }
        final SeenState current = state == null ? SeenState.NONE : state;
        final List<Entry> unread = changelog.newerThan(current.lastSeenId());
        if (unread.isEmpty()) {
            return new Selection(Kind.NOTHING, List.of(), 0, state == null ? allRead : null);
        }
        final SeenState shown = current.shownOnce();
        final SeenState next = autoMarkAfter > 0 && shown.shows() >= autoMarkAfter ? current.read(changelog.maxId()) : shown;
        return new Selection(Kind.ENTRIES, unread.subList(0, Math.min(max, unread.size())), unread.size(), next);
    }

    /** Whether the book opens by itself, and the state with the updated counter. */
    public record BookDecision(boolean open, SeenState state) {
    }

    /**
     * The book opens by itself at most {@code maxOpens} times for the same newest entry
     * ({@code show.book-max-auto-opens}); a newer entry starts the count again.
     *
     * @param state        state after {@link #select} (or the stored one), {@code null} = no record
     * @param newestId     id of the newest entry in the changelog (0 = empty changelog)
     * @param hasUnread    the player had unread entries at login
     * @param onlyIfUnread {@code show.book-only-if-unread}
     */
    public static BookDecision decideBook(final SeenState state, final int newestId, final boolean hasUnread,
                                          final boolean onlyIfUnread, final int maxOpens) {
        final SeenState current = state == null ? SeenState.NONE : state;
        if (newestId <= 0 || maxOpens <= 0 || (onlyIfUnread && !hasUnread)) {
            return new BookDecision(false, state);
        }
        final int opens = current.bookEntryId() == newestId ? current.bookOpens() : 0;
        if (opens >= maxOpens) {
            return new BookDecision(false, state);
        }
        return new BookDecision(true, current.bookOpened(newestId, opens + 1));
    }
}
