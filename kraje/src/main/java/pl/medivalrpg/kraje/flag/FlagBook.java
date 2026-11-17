// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Flag state of every country and the moderation state machine (pure, no Bukkit; main thread only).
 *
 * <pre>
 * upload ──► pending ──approve──► current (in game after the next pack rebuild)
 *               │ └──reject──► lastRejection (image deleted)
 *               └──withdraw / newer upload──► WITHDRAWN
 * current ──reset──► default flag (tinted, immediately)      history ──restore──► current
 * </pre>
 *
 * Nothing reaches the game without {@link #approve}: banners only ever show {@link Country#current} (or the default).
 */
public final class FlagBook {

    public enum Status { APPROVED, REJECTED, WITHDRAWN, REPLACED, RESET, RESTORED }

    public record Pending(String imageId, long uploadedAt, UUID uploadedBy, String uploaderName, String format,
                          int width, int height, int bytes, int colors) {
    }

    public record Rejection(String imageId, long at, String by, String reason) {
    }

    public record Entry(String imageId, Status status, long at, String by, String note) {
    }

    /** Flags of one country. {@code current == null} means the default flag generated from the country colour. */
    public static final class Country {
        String current;
        long approvedAt;
        String approvedBy;
        Pending pending;
        Rejection lastRejection;
        long lastUploadAt;
        final List<Entry> history = new ArrayList<>();

        public String current() {
            return this.current;
        }

        public long approvedAt() {
            return this.approvedAt;
        }

        public String approvedBy() {
            return this.approvedBy;
        }

        public Pending pending() {
            return this.pending;
        }

        public Rejection lastRejection() {
            return this.lastRejection;
        }

        public long lastUploadAt() {
            return this.lastUploadAt;
        }

        public List<Entry> history() {
            return List.copyOf(this.history);
        }
    }

    /** A recent moderation decision (panel "Ostatnie decyzje"). */
    public record Decision(UUID country, String imageId, Status status, long at, String by, String note) {
    }

    final Map<UUID, Country> countries = new HashMap<>();
    /** imageId of every country model that went into the last successful pack build. */
    final Map<UUID, String> packed = new HashMap<>();
    final List<Decision> decisions = new ArrayList<>();
    /** Manual capital banner spots (world, x, y, z, yaw). */
    final Map<UUID, Spot> capitalSpots = new HashMap<>();
    long lastRebuildAt;
    long nextRebuildAt;

    public record Spot(String world, double x, double y, double z, float yaw) {
    }

    public Country get(final UUID country) {
        return this.countries.get(country);
    }

    public Country getOrCreate(final UUID country) {
        return this.countries.computeIfAbsent(country, id -> new Country());
    }

    public Map<UUID, Country> all() {
        return java.util.Collections.unmodifiableMap(this.countries);
    }

    public String current(final UUID country) {
        final Country c = this.countries.get(country);
        return c == null ? null : c.current;
    }

    public String packed(final UUID country) {
        return this.packed.get(country);
    }

    public Map<UUID, String> packedAll() {
        return java.util.Collections.unmodifiableMap(this.packed);
    }

    public List<Decision> decisions() {
        return List.copyOf(this.decisions);
    }

    public Spot capitalSpot(final UUID country) {
        return this.capitalSpots.get(country);
    }

    public void capitalSpot(final UUID country, final Spot spot) {
        if (spot == null) {
            this.capitalSpots.remove(country);
        } else {
            this.capitalSpots.put(country, spot);
        }
    }

    public long lastRebuildAt() {
        return this.lastRebuildAt;
    }

    public long nextRebuildAt() {
        return this.nextRebuildAt;
    }

    // ------------------------------------------------------------------ transitions

    /**
     * New upload waiting for moderation; replaces an older pending image.
     *
     * @return the replaced pending image (its file may be deleted), or null
     */
    public Pending submit(final UUID country, final Pending pending, final long cooldownMillis, final int historySize) {
        final Country c = this.getOrCreate(country);
        if (c.lastUploadAt > 0 && pending.uploadedAt() - c.lastUploadAt < cooldownMillis) {
            final long wait = (cooldownMillis - (pending.uploadedAt() - c.lastUploadAt) + 999) / 1000;
            throw new IllegalArgumentException("Odczekaj jeszcze " + wait + " s przed kolejnym wgraniem flagi.");
        }
        if (pending.imageId().equals(c.current)) {
            throw new IllegalArgumentException("Ta flaga jest już flagą waszego kraju.");
        }
        if (c.pending != null && c.pending.imageId().equals(pending.imageId())) {
            throw new IllegalArgumentException("Ta flaga już czeka na zatwierdzenie.");
        }
        final Pending old = c.pending;
        if (old != null) {
            this.log(c, new Entry(old.imageId(), Status.WITHDRAWN, pending.uploadedAt(), pending.uploaderName(), "zastąpiona nowszym wgraniem"), historySize);
        }
        c.pending = pending;
        c.lastUploadAt = pending.uploadedAt();
        return old;
    }

    /** The uploader takes the pending image back. */
    public Pending withdraw(final UUID country, final String by, final long now, final int historySize) {
        final Country c = this.countries.get(country);
        if (c == null || c.pending == null) {
            throw new IllegalArgumentException("Żadna flaga nie czeka na zatwierdzenie.");
        }
        final Pending old = c.pending;
        c.pending = null;
        this.log(c, new Entry(old.imageId(), Status.WITHDRAWN, now, by, ""), historySize);
        return old;
    }

    /** Moderator accepts the pending image; it becomes the country flag (in game after the next pack rebuild). */
    public void approve(final UUID country, final String imageId, final String by, final long now, final int historySize) {
        final Country c = this.countries.get(country);
        final Pending p = this.requirePending(c, imageId);
        if (c.current != null) {
            this.log(c, new Entry(c.current, Status.REPLACED, now, by, "zastąpiona nową flagą"), historySize);
        }
        c.current = p.imageId();
        c.approvedAt = now;
        c.approvedBy = by;
        c.pending = null;
        c.lastRejection = null;
        this.log(c, new Entry(p.imageId(), Status.APPROVED, now, by, ""), historySize);
        this.decide(new Decision(country, p.imageId(), Status.APPROVED, now, by, ""));
    }

    /** Moderator refuses the pending image (the reason is shown to the country). */
    public Pending reject(final UUID country, final String imageId, final String by, final String reason, final long now,
                          final int historySize) {
        final String note = reason == null ? "" : reason.strip();
        if (note.length() < 3 || note.length() > 200) {
            throw new IllegalArgumentException("Podaj powód odrzucenia (3–200 znaków).");
        }
        final Country c = this.countries.get(country);
        final Pending p = this.requirePending(c, imageId);
        c.pending = null;
        c.lastRejection = new Rejection(p.imageId(), now, by, note);
        this.log(c, new Entry(p.imageId(), Status.REJECTED, now, by, note), historySize);
        this.decide(new Decision(country, p.imageId(), Status.REJECTED, now, by, note));
        return p;
    }

    /** Back to the default flag (immediate: the tinted default model is always in the pack). */
    public void reset(final UUID country, final String by, final long now, final int historySize) {
        final Country c = this.countries.get(country);
        if (c == null || c.current == null) {
            throw new IllegalArgumentException("Kraj ma już domyślną flagę.");
        }
        this.log(c, new Entry(c.current, Status.RESET, now, by, "przywrócono domyślną flagę"), historySize);
        c.current = null;
        c.approvedAt = now;
        c.approvedBy = by;
    }

    /** A previously approved image comes back without new moderation. */
    public void restore(final UUID country, final String imageId, final String by, final long now, final int historySize,
                        final Predicate<String> imageExists) {
        final Country c = this.countries.get(country);
        if (c == null || imageId == null) {
            throw new IllegalArgumentException("Nie ma takiej flagi w historii.");
        }
        if (imageId.equals(c.current)) {
            throw new IllegalArgumentException("Ta flaga jest już flagą waszego kraju.");
        }
        if (!this.wasApproved(c, imageId)) {
            throw new IllegalArgumentException("Przywrócić można tylko flagę, którą wcześniej zatwierdzono.");
        }
        if (!imageExists.test(imageId)) {
            throw new IllegalArgumentException("Obrazek tej flagi nie jest już przechowywany.");
        }
        if (c.current != null) {
            this.log(c, new Entry(c.current, Status.REPLACED, now, by, "zastąpiona przywróconą flagą"), historySize);
        }
        c.current = imageId;
        c.approvedAt = now;
        c.approvedBy = by;
        this.log(c, new Entry(imageId, Status.RESTORED, now, by, ""), historySize);
    }

    public boolean restorable(final UUID country, final String imageId, final Predicate<String> imageExists) {
        final Country c = this.countries.get(country);
        return c != null && imageId != null && !imageId.equals(c.current) && this.wasApproved(c, imageId) && imageExists.test(imageId);
    }

    /** The approved image file is gone (manual deletion): back to the default flag instead of retrying forever. */
    public void dropLost(final UUID country, final long now, final int historySize) {
        final Country c = this.countries.get(country);
        if (c == null || c.current == null) {
            return;
        }
        this.log(c, new Entry(c.current, Status.RESET, now, "serwer", "brak pliku obrazka — przywrócono domyślną flagę"), historySize);
        c.current = null;
        c.approvedAt = now;
        c.approvedBy = "serwer";
    }

    /** Country disbanded: drop its data (images are collected by {@link #referencedImages()}). */
    public void forget(final UUID country) {
        this.countries.remove(country);
        this.capitalSpots.remove(country);
    }

    private boolean wasApproved(final Country c, final String imageId) {
        for (final Entry e : c.history) {
            if (imageId.equals(e.imageId()) && (e.status() == Status.APPROVED || e.status() == Status.RESTORED)) {
                return true;
            }
        }
        return false;
    }

    private Pending requirePending(final Country c, final String imageId) {
        if (c == null || c.pending == null) {
            throw new IllegalArgumentException("Ta flaga nie czeka już na decyzję.");
        }
        if (imageId != null && !imageId.isEmpty() && !c.pending.imageId().equals(imageId)) {
            throw new IllegalArgumentException("W międzyczasie wgrano inną flagę — odśwież kolejkę.");
        }
        return c.pending;
    }

    private void log(final Country c, final Entry e, final int historySize) {
        c.history.add(0, e);
        while (c.history.size() > historySize) {
            c.history.remove(c.history.size() - 1);
        }
    }

    private void decide(final Decision d) {
        this.decisions.add(0, d);
        while (this.decisions.size() > 50) {
            this.decisions.remove(this.decisions.size() - 1);
        }
    }

    // ------------------------------------------------------------------ pack bookkeeping

    /** Countries whose approved image is not yet in the built pack. */
    public Set<UUID> waitingForPack() {
        final Set<UUID> out = new HashSet<>();
        for (final Map.Entry<UUID, Country> e : this.countries.entrySet()) {
            final String cur = e.getValue().current;
            if (cur != null && !cur.equals(this.packed.get(e.getKey()))) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Desired content of the pack: country → approved image. */
    public Map<UUID, String> desiredPack() {
        final Map<UUID, String> out = new HashMap<>();
        for (final Map.Entry<UUID, Country> e : this.countries.entrySet()) {
            if (e.getValue().current != null) {
                out.put(e.getKey(), e.getValue().current);
            }
        }
        return out;
    }

    /** Whether the country's banner can show its own image (the built pack holds exactly the current image). */
    public boolean live(final UUID country) {
        final String cur = this.current(country);
        return cur != null && cur.equals(this.packed.get(country));
    }

    public void packBuilt(final Map<UUID, String> built, final long now) {
        this.packed.clear();
        this.packed.putAll(built);
        this.lastRebuildAt = now;
    }

    /** Every image still needed: current, pending and history (rejected images are not kept). */
    public Set<String> referencedImages() {
        final Set<String> out = new HashSet<>();
        for (final Country c : this.countries.values()) {
            if (c.current != null) {
                out.add(c.current);
            }
            if (c.pending != null) {
                out.add(c.pending.imageId());
            }
            for (final Entry e : c.history) {
                if (e.imageId() != null && e.status() != Status.REJECTED && e.status() != Status.WITHDRAWN) {
                    out.add(e.imageId());
                }
            }
        }
        out.addAll(this.packed.values());
        return out;
    }
}
