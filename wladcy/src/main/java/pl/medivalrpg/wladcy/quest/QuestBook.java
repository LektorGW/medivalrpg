// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Quest state ({@code zlecenia.json}): daily boards per country, accepted quests per player, daily counters and
 * cooldowns. Day boundaries follow the clock's zone (server time). Main thread only; no Bukkit, no money — the
 * glue ({@code QuestService}) moves items and coins and then records the outcome here.
 */
public final class QuestBook {

    private final Clock clock;
    private final QuestData data;
    private boolean dirty;

    public QuestBook(final Clock clock, final QuestData loaded) {
        this.clock = clock;
        this.data = loaded == null ? new QuestData() : loaded;
        this.data.normalize();
    }

    public String today() {
        return LocalDate.now(this.clock).toString();
    }

    public long now() {
        return this.clock.millis();
    }

    // ------------------------------------------------------------------ boards

    /** Today's offers of the country, or empty when the board must be (re)generated. */
    public Optional<List<QuestData.Offer>> board(final UUID country) {
        final QuestData.Board b = this.data.boards.get(country.toString());
        if (b == null || !this.today().equals(b.day)) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(b.offers));
    }

    public void putBoard(final UUID country, final List<QuestData.Offer> offers) {
        final QuestData.Board b = new QuestData.Board();
        b.day = this.today();
        b.offers = new ArrayList<>(offers);
        this.data.boards.put(country.toString(), b);
        // Yesterday's boards of other countries are useless; drop them so the file does not grow.
        final String today = this.today();
        this.data.boards.values().removeIf(x -> !today.equals(x.day));
        this.dirty = true;
    }

    /** Offer from any of today's boards. */
    public Optional<QuestData.Offer> offer(final String id) {
        final String today = this.today();
        for (final QuestData.Board b : this.data.boards.values()) {
            if (!today.equals(b.day)) {
                continue;
            }
            for (final QuestData.Offer o : b.offers) {
                if (o.id.equalsIgnoreCase(id)) {
                    return Optional.of(o);
                }
            }
        }
        return Optional.empty();
    }

    /** A new offer id (4–5 base-36 characters) not used by any board or active quest. */
    public String newOfferId() {
        String id;
        do {
            id = Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36, 36 * 36 * 36 * 36), 36);
        } while (this.offer(id).isPresent() || this.idInUse(id));
        return id;
    }

    private boolean idInUse(final String id) {
        for (final List<QuestData.Active> l : this.data.active.values()) {
            for (final QuestData.Active a : l) {
                if (a.offerId.equalsIgnoreCase(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ players

    private QuestData.PlayerDay day(final UUID player) {
        final QuestData.PlayerDay d = this.data.players.computeIfAbsent(player.toString(), k -> new QuestData.PlayerDay());
        final String today = this.today();
        if (!today.equals(d.day)) {
            d.day = today;
            d.completed = 0;
            d.perCountry.clear();
            d.used.clear();
            this.dirty = true;
        }
        final long now = this.now();
        if (d.cooldownUntil.values().removeIf(t -> t <= now)) {
            this.dirty = true;
        }
        return d;
    }

    public int completedToday(final UUID player) {
        return this.day(player).completed;
    }

    public int completedToday(final UUID player, final UUID country) {
        return this.day(player).perCountry.getOrDefault(country.toString(), 0);
    }

    public boolean used(final UUID player, final String offerId) {
        return this.day(player).used.contains(offerId.toLowerCase(java.util.Locale.ROOT));
    }

    /** Like {@link #used} but never changes state (safe for tab completion, which may run off the main thread). */
    public boolean usedPeek(final UUID player, final String offerId) {
        final QuestData.PlayerDay d = this.data.players.get(player.toString());
        return d != null && this.today().equals(d.day) && d.used.contains(offerId.toLowerCase(java.util.Locale.ROOT));
    }

    public long cooldownUntil(final UUID player, final UUID country) {
        return this.day(player).cooldownUntil.getOrDefault(country.toString(), 0L);
    }

    public long lastAcceptAt(final UUID player) {
        return this.day(player).lastAcceptAt;
    }

    public int totalCompleted(final UUID player) {
        return this.day(player).totalCompleted;
    }

    public int totalFailed(final UUID player) {
        return this.day(player).totalFailed;
    }

    /** The player refuses an offer: it disappears from their view today. */
    public void refuse(final UUID player, final String offerId) {
        this.day(player).used.add(offerId.toLowerCase(java.util.Locale.ROOT));
        this.dirty = true;
    }

    /** Notices kept for a player who was offline (taken and cleared). */
    public List<String> takeNotices(final UUID player) {
        final QuestData.PlayerDay d = this.data.players.get(player.toString());
        if (d == null || d.notices.isEmpty()) {
            return List.of();
        }
        final List<String> out = List.copyOf(d.notices);
        d.notices.clear();
        this.dirty = true;
        return out;
    }

    public void addNotice(final UUID player, final String text) {
        final QuestData.PlayerDay d = this.day(player);
        d.notices.add(text);
        while (d.notices.size() > 10) {
            d.notices.remove(0);
        }
        this.dirty = true;
    }

    // ------------------------------------------------------------------ active quests

    public List<QuestData.Active> active(final UUID player) {
        final List<QuestData.Active> l = this.data.active.get(player.toString());
        return l == null ? List.of() : List.copyOf(l);
    }

    /** Every active quest of every player (for the expiry check). */
    public List<QuestData.Active> allActive() {
        final List<QuestData.Active> out = new ArrayList<>();
        this.data.active.values().forEach(out::addAll);
        return out;
    }

    /** By offer id or uid, case-insensitive. */
    public Optional<QuestData.Active> active(final UUID player, final String id) {
        for (final QuestData.Active a : this.active(player)) {
            if (a.offerId.equalsIgnoreCase(id) || a.uid.equalsIgnoreCase(id)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    public boolean isActive(final String uid) {
        for (final List<QuestData.Active> l : this.data.active.values()) {
            for (final QuestData.Active a : l) {
                if (a.uid.equals(uid)) {
                    return true;
                }
            }
        }
        return false;
    }

    public long paidToday(final UUID country) {
        this.rollPaid();
        return this.data.paidToday.getOrDefault(country.toString(), 0L);
    }

    /** Firmy: a funded wholesale order of a company counts toward the same daily reward budget of the country. */
    public void addPaidToday(final UUID country, final long amount) {
        this.rollPaid();
        this.data.paidToday.merge(country.toString(), Math.max(0, amount), Long::sum);
        this.dirty = true;
    }

    private void rollPaid() {
        final String today = this.today();
        if (!today.equals(this.data.paidDay)) {
            this.data.paidDay = today;
            this.data.paidToday.clear();
            this.dirty = true;
        }
    }

    /**
     * Records an accepted offer. The caller already checked {@link QuestRules#canAccept} and reserved the reward.
     *
     * @param uid unique id of this acceptance (cargo tag, escrow account)
     */
    public QuestData.Active accept(final UUID player, final String playerName, final QuestData.Offer o, final String countryName,
                                   final long reward, final boolean escrow, final String uid) {
        final long now = this.now();
        final QuestData.Active a = new QuestData.Active();
        a.uid = uid;
        a.offerId = o.id;
        a.player = player.toString();
        a.playerName = playerName == null ? "" : playerName;
        a.country = o.country;
        a.countryName = countryName == null ? "" : countryName;
        a.type = o.type;
        a.material = o.material;
        a.itemName = o.itemName;
        a.amount = o.amount;
        a.reward = reward;
        a.escrow = escrow;
        a.reputation = o.reputation;
        a.acceptedAt = now;
        a.deadline = QuestRules.deadline(now, o.timeLimitMinutes);
        a.targetCountry = o.targetCountry;
        a.targetName = o.targetName;
        a.distance = o.distance;
        this.data.active.computeIfAbsent(player.toString(), k -> new ArrayList<>()).add(a);
        final QuestData.PlayerDay d = this.day(player);
        d.used.add(o.id.toLowerCase(java.util.Locale.ROOT));
        d.lastAcceptAt = now;
        this.dirty = true;
        return a;
    }

    private boolean remove(final QuestData.Active a) {
        final List<QuestData.Active> l = this.data.active.get(a.player);
        if (l == null) {
            return false;
        }
        final boolean removed = l.removeIf(x -> x.uid.equals(a.uid));
        if (l.isEmpty()) {
            this.data.active.remove(a.player);
        }
        this.dirty = true;
        return removed;
    }

    /** Reserve a stable identity/offer slot before any funding; caller must durably checkpoint and fund it. */
    public QuestData.Active prepareFundedAcceptance(final UUID player, final String playerName, final QuestData.Offer offer,
                                                     final String countryName, final long reward, final String uid) {
        for (final QuestData.Active existing : this.active(player)) {
            if (!existing.offerId.equalsIgnoreCase(offer.id)) continue;
            if (!SupplyAcceptance.pending(existing) || !existing.country.equals(offer.country) || !existing.type.equals(offer.type)
                || !existing.material.equals(offer.material) || existing.amount != offer.amount || existing.reward != reward) {
                throw new IllegalArgumentException("Zlecenie ma już inny zapisany zamiar przyjęcia.");
            }
            return existing;
        }
        final QuestData.Active active = this.accept(player, playerName, offer, countryName, reward, true, uid);
        active.supplyDelivery = offer.type() == QuestType.SUROWCE;
        active.acceptancePhase = SupplyAcceptance.Phase.INTENT;
        active.acceptanceMinutes = offer.timeLimitMinutes;
        active.acceptanceNoticePending = true;
        return active;
    }

    /** Quest done and paid: counts toward the daily limits and the country's daily reward budget. */
    public boolean complete(final QuestData.Active a, final long paid) {
        if (SupplyAcceptance.pending(a)) return false;
        if (a.supplyDelivery && (a.supplyPhase != SupplySettlement.Phase.PAID || paid != a.reward)) {
            return false;
        }
        if (!this.remove(a)) {
            return false;
        }
        final UUID player = UUID.fromString(a.player);
        final QuestData.PlayerDay d = this.day(player);
        d.completed++;
        d.perCountry.merge(a.country, 1, Integer::sum);
        d.totalCompleted++;
        this.rollPaid();
        this.data.paidToday.merge(a.country, Math.max(0, paid), Long::sum);
        return true;
    }

    /**
     * Quest abandoned or failed (time out, lost cargo): removed, and the country gives this player nothing for
     * {@code cooldownMinutes}. A failure counts toward the daily limits too (no farming of accept/abandon).
     */
    public boolean fail(final QuestData.Active a, final int cooldownMinutes) {
        if (SupplySettlement.protectedFromCancellation(a)) {
            return false;
        }
        if (!this.remove(a)) {
            return false;
        }
        final UUID player = UUID.fromString(a.player);
        final QuestData.PlayerDay d = this.day(player);
        d.totalFailed++;
        d.perCountry.merge(a.country, 1, Integer::sum);
        if (cooldownMinutes > 0) {
            d.cooldownUntil.put(a.country, this.now() + cooldownMinutes * 60_000L);
        }
        return true;
    }

    /** Quest cancelled without blame (country disbanded, admin): removed, nothing counted. */
    public boolean cancel(final QuestData.Active a) {
        return !SupplySettlement.protectedFromCancellation(a) && this.remove(a);
    }

    public List<QuestData.Active> expired() {
        final long now = this.now();
        return this.allActive().stream().filter(a -> QuestRules.expired(a, now) && !SupplySettlement.protectedFromCancellation(a)).toList();
    }

    /** Drops the country's board; returns its active quests (the caller refunds and cancels them). */
    public List<QuestData.Active> forgetCountry(final UUID country) {
        final String id = country.toString();
        if (this.data.boards.remove(id) != null) {
            this.dirty = true;
        }
        return this.allActive().stream().filter(a -> a.country.equals(id) || id.equals(a.targetCountry)).toList();
    }

    /** Admin: forces a new board for the country (or every country when null) on next view. */
    public void dropBoard(final UUID country) {
        if (country == null) {
            this.data.boards.clear();
        } else {
            this.data.boards.remove(country.toString());
        }
        this.dirty = true;
    }

    public int activeCount() {
        return this.allActive().size();
    }

    public boolean dirty() {
        return this.dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    /** The live data for the JSON writer ({@code JsonStore} serialises it on the calling, main thread). */
    public QuestData snapshot() {
        this.dirty = false;
        return this.data;
    }

    Map<String, QuestData.Board> boards() {
        return this.data.boards;
    }
}
