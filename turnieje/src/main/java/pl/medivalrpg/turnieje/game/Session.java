// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import pl.medivalrpg.turnieje.arena.Arena;
import pl.medivalrpg.turnieje.arena.ArenaType;
import pl.medivalrpg.turnieje.arena.GearMode;
import pl.medivalrpg.turnieje.bracket.Bracket;
import pl.medivalrpg.turnieje.bracket.FreeForAll;
import pl.medivalrpg.turnieje.data.EscrowLedger;

/** One event on one arena: a tournament (weekly, organized by a country, started by an admin) or a duel. */
public final class Session {

    public enum Kind {
        WEEKLY, ORGANIZED, ADMIN, DUEL
    }

    public enum Format {
        /** 1v1 rounds (tournament, joust, duel). */
        BRACKET,
        /** Everybody at once. */
        MELEE
    }

    final String id;
    final Kind kind;
    final Arena arena;
    final String title;
    final GearMode gear;
    final boolean mounted;
    final Format format;
    final PhaseMachine machine = new PhaseMachine(Phase.SIGNUPS);
    /** Signed-up players (uuid → name), in sign-up order. */
    final Map<UUID, String> entrants = new LinkedHashMap<>();
    /** Participants inside the event (snapshot taken), until restored. */
    final Set<UUID> inside = new LinkedHashSet<>();
    final EscrowLedger ledger;
    final long entryFee;
    final UUID organizerCountry;
    final String organizerName;
    Instant startAt;
    long signupSeconds;
    long lastSecondsLeft = Long.MAX_VALUE;

    Bracket<UUID> bracket;
    FreeForAll<UUID> ffa;
    Bracket.Match<UUID> match;
    boolean meleeFought;
    final List<UUID> fighters = new ArrayList<>();
    final Map<UUID, Double> damage = new HashMap<>();
    final Set<UUID> down = new HashSet<>();
    int timer;
    int timeLimit;
    int matchNo;
    final Set<UUID> horses = new HashSet<>();
    BossBar bar;
    final Set<UUID> barViewers = new HashSet<>();
    UUID champion;
    List<List<UUID>> placements = List.of();
    final Map<UUID, Long> payouts = new LinkedHashMap<>();

    Session(final String id, final Kind kind, final Arena arena, final String title, final GearMode gear, final long entryFee,
            final UUID organizerCountry, final String organizerName, final EscrowLedger ledger) {
        this.id = id;
        this.kind = kind;
        this.arena = arena;
        this.title = title;
        this.gear = gear;
        this.mounted = arena.mounted && arena.type == ArenaType.TURNIEJ;
        this.format = arena.type == ArenaType.MELEE ? Format.MELEE : Format.BRACKET;
        this.entryFee = entryFee;
        this.organizerCountry = organizerCountry;
        this.organizerName = organizerName;
        this.ledger = ledger;
    }

    public String id() {
        return this.id;
    }

    public Kind kind() {
        return this.kind;
    }

    public Arena arena() {
        return this.arena;
    }

    public String title() {
        return this.title;
    }

    public Phase phase() {
        return this.machine.phase();
    }

    public boolean isTournament() {
        return this.kind != Kind.DUEL;
    }

    /** Fighter currently on the field and not yet beaten. */
    public boolean isLiveFighter(final UUID player) {
        return this.machine.phase().matchLive() && this.fighters.contains(player) && !this.down.contains(player);
    }

    public boolean isInside(final UUID player) {
        return this.inside.contains(player);
    }

    public boolean isEntrant(final UUID player) {
        return this.entrants.containsKey(player);
    }

    public String nameOf(final UUID player) {
        return this.entrants.getOrDefault(player, "?");
    }
}
