// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.model;

/**
 * What one player has seen.
 *
 * @param lastSeenId  highest entry id the player has read (0 = nothing yet)
 * @param shows       how many times the login news were shown since the player last read everything
 * @param bookEntryId newest entry id the book was last opened automatically for (0 = never)
 * @param bookOpens   how many times the book opened automatically for {@code bookEntryId}
 */
public record SeenState(int lastSeenId, int shows, int bookEntryId, int bookOpens) {

    public static final SeenState NONE = new SeenState(0, 0, 0, 0);

    public SeenState(final int lastSeenId, final int shows) {
        this(lastSeenId, shows, 0, 0);
    }

    public static SeenState readUpTo(final int id) {
        return new SeenState(id, 0);
    }

    /** Everything up to {@code id} read; the automatic book counter is kept. */
    public SeenState read(final int id) {
        return new SeenState(Math.max(this.lastSeenId, id), 0, this.bookEntryId, this.bookOpens);
    }

    public SeenState shownOnce() {
        return new SeenState(this.lastSeenId, this.shows + 1, this.bookEntryId, this.bookOpens);
    }

    public SeenState bookOpened(final int entryId, final int opens) {
        return new SeenState(this.lastSeenId, this.shows, entryId, opens);
    }
}
