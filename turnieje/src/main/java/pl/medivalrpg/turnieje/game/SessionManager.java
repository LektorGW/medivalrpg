// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import pl.medivalrpg.turnieje.arena.Arena;
import pl.medivalrpg.turnieje.arena.Pos;
import pl.medivalrpg.turnieje.bracket.Bracket;
import pl.medivalrpg.turnieje.bracket.FreeForAll;
import pl.medivalrpg.turnieje.bracket.PrizeSplit;
import pl.medivalrpg.turnieje.bracket.TimeoutJudge;
import pl.medivalrpg.turnieje.config.Messages;
import pl.medivalrpg.turnieje.config.TurniejeConfig;
import pl.medivalrpg.turnieje.data.EscrowLedger;
import pl.medivalrpg.turnieje.data.PlayerStats;
import pl.medivalrpg.turnieje.data.Storage;
import pl.medivalrpg.turnieje.integration.Countries;
import pl.medivalrpg.turnieje.integration.Economy;
import pl.medivalrpg.turnieje.integration.Services;

import static pl.medivalrpg.turnieje.config.Messages.p;

/**
 * Runs all sessions: weekly schedule, sign-ups with countdown, brackets and melees, fights with timers, prizes from
 * escrow, cancellation with refunds, duels and spectators. Ticks once per second on the main thread.
 */
public final class SessionManager {

    /** Why a fighter lost. */
    public enum Cause {
        KILL, DEATH, QUIT, TIMEOUT, LEFT
    }

    private record Spectator(String arena, Pos origin) {
    }

    private record DuelRequest(UUID from, String fromName, Instant expires) {
    }

    private final JavaPlugin plugin;
    private final Logger log;
    private final Storage storage;
    private final Services services;
    private final PlayerCare care;
    private final Horses horses;
    private final Celebration celebration;
    private TurniejeConfig cfg;
    private Messages msg;
    private final List<Session> sessions = new ArrayList<>();
    private final Map<UUID, Spectator> spectators = new HashMap<>();
    private final Map<UUID, DuelRequest> duelRequests = new HashMap<>();
    private final Random random = new Random();
    private String warnedWeeklyKey;
    private int recoveryTicks;

    public SessionManager(final JavaPlugin plugin, final Storage storage, final Services services, final PlayerCare care,
                          final Horses horses, final Celebration celebration) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.storage = storage;
        this.services = services;
        this.care = care;
        this.horses = horses;
        this.celebration = celebration;
    }

    public void settings(final TurniejeConfig config, final Messages messages) {
        this.cfg = config;
        this.msg = messages;
    }

    public Messages messages() {
        return this.msg;
    }

    public TurniejeConfig config() {
        return this.cfg;
    }

    // =============================================================================================
    // lookups
    // =============================================================================================

    public List<Session> sessions() {
        return List.copyOf(this.sessions);
    }

    /** Tournament with open sign-ups. */
    public Optional<Session> openTournament() {
        return this.sessions.stream().filter(s -> s.isTournament() && s.phase() == Phase.SIGNUPS).findFirst();
    }

    /** Any tournament (not duel) that is not over. */
    public Optional<Session> currentTournament() {
        return this.sessions.stream().filter(Session::isTournament).findFirst();
    }

    public Session sessionInside(final UUID player) {
        for (final Session s : this.sessions) {
            if (s.isInside(player)) {
                return s;
            }
        }
        return null;
    }

    public Session liveFightSession(final UUID player) {
        for (final Session s : this.sessions) {
            if (s.isLiveFighter(player)) {
                return s;
            }
        }
        return null;
    }

    public Session sessionOn(final String arenaId) {
        for (final Session s : this.sessions) {
            if (s.arena.id.equals(arenaId)) {
                return s;
            }
        }
        return null;
    }

    /** Inside an event or signed up for one. */
    public boolean busy(final UUID player) {
        for (final Session s : this.sessions) {
            if (s.isInside(player) || (s.phase() == Phase.SIGNUPS && s.isEntrant(player))) {
                return true;
            }
        }
        return false;
    }

    public boolean isSpectator(final UUID player) {
        return this.spectators.containsKey(player);
    }

    /** Arena a spectator watches, or null. */
    public Arena spectatedArena(final UUID player) {
        final Spectator s = this.spectators.get(player);
        return s == null ? null : this.storage.arenas().get(s.arena());
    }

    /** Arena blocks are protected (session on it, or always per config). */
    public boolean isProtected(final String world, final int x, final int y, final int z) {
        for (final Arena a : this.storage.arenas().values()) {
            if (a.box.contains(world, x, y, z) && (this.cfg.protectAlways || this.sessionOn(a.id) != null)) {
                return true;
            }
        }
        return false;
    }

    public Location waitingSpot(final Session s) {
        return s.arena.spectatorPos().toLocation();
    }

    // =============================================================================================
    // ticking
    // =============================================================================================

    public void tick() {
        this.expireDuelRequests();
        if (++this.recoveryTicks >= 60) { this.recoveryTicks = 0; this.recoverAfterRestart(); }
        this.tickWeekly(Instant.now());
        for (final Session s : List.copyOf(this.sessions)) {
            try {
                this.tickSession(s);
            } catch (final RuntimeException e) {
                this.log.log(java.util.logging.Level.SEVERE, "Błąd sesji " + s.id + " — anuluję ze zwrotem", e);
                this.cancel(s, "reason-error");
            }
        }
    }

    private void tickSession(final Session s) {
        switch (s.phase()) {
            case SIGNUPS -> this.tickSignups(s);
            case INTERMISSION -> {
                if (--s.timer <= 0) {
                    this.nextMatch(s);
                }
            }
            case COUNTDOWN -> {
                if (s.timer > 0) {
                    for (final UUID f : s.fighters) {
                        final Player p = Bukkit.getPlayer(f);
                        if (p != null) {
                            p.showTitle(Title.title(this.msg.get("countdown-title", p("n", s.timer)), this.msg.get("countdown-subtitle"),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
                        }
                    }
                    s.timer--;
                } else {
                    s.machine.moveTo(Phase.FIGHTING);
                    s.timer = s.timeLimit;
                    for (final UUID f : s.fighters) {
                        final Player p = Bukkit.getPlayer(f);
                        if (p != null) {
                            p.showTitle(Title.title(this.msg.get("fight-title"), Component.empty(),
                                Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(300))));
                        }
                    }
                }
            }
            case FIGHTING -> {
                this.keepFightersInside(s);
                if (--s.timer <= 0) {
                    this.timeout(s);
                }
            }
            case CELEBRATION -> {
                if (--s.timer <= 0) {
                    this.finish(s);
                }
            }
            default -> {
            }
        }
        if (!s.machine.phase().terminal()) {
            this.updateBar(s);
        }
    }

    private void tickSignups(final Session s) {
        final long left = Math.max(0, Duration.between(Instant.now(), s.startAt).toSeconds());
        if (s.isTournament() && s.lastSecondsLeft != Long.MAX_VALUE) {
            for (final int t : Texts.crossed(s.lastSecondsLeft, left, this.cfg.announceSeconds)) {
                Bukkit.getServer().sendMessage(this.msg.get("announce-reminder", p("title", s.title), p("time", Texts.duration(t)),
                    p("count", s.entrants.size()), p("arena", s.arena.id)));
            }
            for (final int t : Texts.crossed(s.lastSecondsLeft, left, this.cfg.titleSeconds)) {
                final Title title = Title.title(this.msg.get("reminder-title", p("title", s.title)),
                    this.msg.get("reminder-subtitle", p("time", Texts.duration(t))));
                for (final Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(title);
                }
            }
        }
        s.lastSecondsLeft = left;
        if (left <= 0) {
            this.begin(s);
        }
    }

    // =============================================================================================
    // weekly / admin / organized tournaments
    // =============================================================================================

    private void tickWeekly(final Instant now) {
        if (!this.cfg.weeklyEnabled || this.currentTournament().isPresent()) {
            return;
        }
        final ZonedDateTime next = this.cfg.weekly.nextStart(now);
        if (!WeeklySchedule.inSignupWindow(now, next, this.cfg.weeklySignupMinutes)) {
            return;
        }
        final String key = WeeklySchedule.occurrenceKey(next);
        if (key.equals(this.storage.state().weeklyKey)) {
            return;
        }
        final Arena arena = this.pickTournamentArena(this.cfg.weeklyArena);
        if (arena == null) {
            if (!key.equals(this.warnedWeeklyKey)) {
                this.log.warning("Cotygodniowy turniej " + key + ": brak gotowej areny (typ turniej/melee, min. 2 spawny, wolna)");
                this.warnedWeeklyKey = key;
            }
            return;
        }
        this.storage.state().weeklyKey = key;
        final Session s = this.open(Session.Kind.WEEKLY, arena, this.cfg.weeklyName, next.toInstant(), this.cfg.weeklyEntryFee, null, null);
        this.fundFromConfig(s);
        s.ledger.reopenWeeklyKey = key;
        this.storage.saveState();
        this.announceOpen(s);
    }

    /** Arena by id, or the first ready, free arena that hosts tournaments. */
    public Arena pickTournamentArena(final String preferred) {
        if (preferred != null && !preferred.isBlank()) {
            final Arena a = this.storage.arenas().get(preferred);
            return a != null && a.type.hostsTournaments() && a.ready() && this.sessionOn(a.id) == null ? a : null;
        }
        for (final Arena a : this.storage.arenas().values()) {
            if (a.type.hostsTournaments() && a.ready() && this.sessionOn(a.id) == null) {
                return a;
            }
        }
        return null;
    }

    private Session open(final Session.Kind kind, final Arena arena, final String title, final Instant startAt, final long fee,
                         final UUID country, final String countryName) {
        final String id = UUID.randomUUID().toString();
        final EscrowLedger ledger = new EscrowLedger(id, title, "turnieje:" + id);
        final Session s = new Session(id, kind, arena, title, arena.gearOr(this.cfg.defaultGear),
            this.services.economy().available() ? fee : 0, country, countryName, ledger);
        s.startAt = startAt;
        s.signupSeconds = Math.max(1, Duration.between(Instant.now(), startAt).toSeconds());
        if (kind != Session.Kind.DUEL) {
            this.storage.state().escrows.put(id, ledger);
            this.storage.saveState();
        }
        this.sessions.add(s);
        return s;
    }

    /** Weekly/admin prize per config: server pool, country treasury, minted at payout, or none. Never more than available. */
    private void fundFromConfig(final Session s) {
        final Economy eco = this.services.economy();
        if (!eco.available() || this.cfg.prizeAmount <= 0) {
            return;
        }
        switch (this.cfg.prizeSource) {
            case SERVER -> this.fundFrom(s, this.cfg.serverAccount);
            case COUNTRY -> this.services.countries().byName(this.cfg.prizeCountry).ifPresentOrElse(
                c -> this.fundFrom(s, c.treasuryAccount()),
                () -> this.log.warning("weekly.prize.country: nie ma kraju '" + this.cfg.prizeCountry + "' — turniej bez puli"));
            case MINT -> s.ledger.minted = this.cfg.allowMint ? this.cfg.prizeAmount : 0;
            case NONE -> {
            }
        }
        this.storage.saveState();
    }

    private void fundFrom(final Session s, final String account) {
        final Economy eco = this.services.economy();
        final long amount = Math.min(this.cfg.prizeAmount, eco.balance(account));
        if (amount > 0 && eco.transfer(account, s.ledger.escrowAccount, amount, "Pula turnieju: " + s.title)) {
            s.ledger.funded = amount;
            s.ledger.sourceAccount = account;
        } else if (amount <= 0) {
            this.log.warning("Konto " + account + " jest puste — turniej '" + s.title + "' bez puli z tego źródła");
        }
    }

    private void announceOpen(final Session s) {
        final Economy eco = this.services.economy();
        Bukkit.getServer().sendMessage(this.msg.get("announce-open",
            p("title", s.title), p("arena", s.arena.id), Messages.c("type", this.typeName(s)),
            p("time", Texts.duration(Duration.between(Instant.now(), s.startAt).toSeconds())),
            p("prize", s.ledger.prizePool() > 0 ? eco.format(s.ledger.prizePool()) : this.msg.plain("money-none")),
            p("fee", s.entryFee > 0 ? eco.format(s.entryFee) : this.msg.plain("money-none")),
            p("organizer", s.organizerName == null ? this.msg.plain("organizer-server") : s.organizerName)));
    }

    public Component typeName(final Session s) {
        final String key = "type." + s.arena.type.id() + (s.mounted ? "-konno" : "");
        return this.msg.get(this.msg.has(key) ? key : "type." + s.arena.type.id());
    }

    /** {@code /turniej start [arena] [minuty]}. */
    public void adminStart(final org.bukkit.command.CommandSender sender, final String arenaId, final Integer minutes) {
        final Optional<Session> open = this.openTournament();
        if (open.isPresent() && (arenaId == null || open.get().arena.id.equals(arenaId))) {
            open.get().startAt = Instant.now();
            this.msg.send(sender, "start-now", p("title", open.get().title));
            return;
        }
        if (this.currentTournament().isPresent()) {
            this.msg.send(sender, "tournament-busy");
            return;
        }
        final Arena arena = this.pickTournamentArena(arenaId);
        if (arena == null) {
            this.msg.send(sender, "start-no-arena");
            return;
        }
        final int m = minutes == null ? 5 : Math.max(1, minutes);
        final Session s = this.open(Session.Kind.ADMIN, arena, this.cfg.weeklyName, Instant.now().plusSeconds(m * 60L), this.cfg.weeklyEntryFee, null, null);
        this.fundFromConfig(s);
        this.announceOpen(s);
        this.msg.send(sender, "start-opened", p("title", s.title), p("time", Texts.duration(m * 60L)));
    }

    /** {@code /turniej stop [arena]}. */
    public void adminStop(final org.bukkit.command.CommandSender sender, final String arenaId) {
        final Session s = arenaId == null ? this.currentTournament().orElse(null) : this.sessionOn(arenaId);
        if (s == null) {
            this.msg.send(sender, "stop-none");
            return;
        }
        this.cancel(s, "reason-admin");
        this.msg.send(sender, "stop-done", p("title", s.title));
    }

    /** {@code /turniej zorganizuj <arena> <pula> [wpisowe] [minuty]} by a country ruler. */
    public void organize(final Player p, final String arenaId, final String poolRaw, final String feeRaw, final Integer minutesArg) {
        final Economy eco = this.services.economy();
        final Countries countries = this.services.countries();
        if (!this.cfg.organizeEnabled) {
            this.msg.send(p, "organize-disabled");
            return;
        }
        if (!eco.available() || !countries.available()) {
            this.msg.send(p, "organize-unavailable");
            return;
        }
        final Optional<Countries.Country> country = countries.countryOf(p.getUniqueId());
        if (country.isEmpty()) {
            this.msg.send(p, "organize-no-country");
            return;
        }
        final Countries.Country k = country.get();
        if (k.rankWeight() < this.cfg.organizeMinRank) {
            this.msg.send(p, "organize-rank", p("rank", this.msg.plain("rank." + this.cfg.organizeMinRank)));
            return;
        }
        final Long until = this.storage.state().countryCooldowns.get(k.id());
        if (until != null && until > System.currentTimeMillis()) {
            this.msg.send(p, "organize-cooldown", p("time", Texts.duration((until - System.currentTimeMillis()) / 1000)));
            return;
        }
        if (this.currentTournament().isPresent()) {
            this.msg.send(p, "tournament-busy");
            return;
        }
        final Arena arena = this.storage.arenas().get(arenaId.toLowerCase(Locale.ROOT));
        if (arena == null) {
            this.msg.send(p, "unknown-arena", p("arena", arenaId));
            return;
        }
        if (!arena.type.hostsTournaments()) {
            this.msg.send(p, "organize-arena-type");
            return;
        }
        if (!(k.id().equals(arena.country) || (arena.country == null && this.cfg.organizePublicArenas))) {
            this.msg.send(p, "organize-arena-owner");
            return;
        }
        if (!arena.ready()) {
            this.msg.send(p, "arena-not-ready", p("arena", arena.id));
            return;
        }
        if (this.sessionOn(arena.id) != null) {
            this.msg.send(p, "arena-busy", p("arena", arena.id));
            return;
        }
        final Optional<Long> pool = eco.parse(poolRaw);
        if (pool.isEmpty()) {
            this.msg.send(p, "invalid-amount", p("value", poolRaw));
            return;
        }
        if (pool.get() < this.cfg.organizeMinPool || pool.get() > this.cfg.organizeMaxPool) {
            this.msg.send(p, "organize-pool-range", p("min", eco.format(this.cfg.organizeMinPool)), p("max", eco.format(this.cfg.organizeMaxPool)));
            return;
        }
        long fee = 0;
        if (feeRaw != null) {
            final Optional<Long> f = eco.parse(feeRaw);
            if (f.isEmpty()) {
                this.msg.send(p, "invalid-amount", p("value", feeRaw));
                return;
            }
            fee = f.get();
            if (fee > this.cfg.organizeMaxFee) {
                this.msg.send(p, "organize-fee-range", p("max", eco.format(this.cfg.organizeMaxFee)));
                return;
            }
        }
        final int minutes = minutesArg == null ? this.cfg.organizeDefaultMinutes : minutesArg;
        if (minutes < this.cfg.organizeMinMinutes || minutes > this.cfg.organizeMaxMinutes) {
            this.msg.send(p, "organize-minutes-range", p("min", this.cfg.organizeMinMinutes), p("max", this.cfg.organizeMaxMinutes));
            return;
        }
        if (eco.balance(k.treasuryAccount()) < pool.get()) {
            this.msg.send(p, "organize-treasury-funds", p("amount", eco.format(pool.get())));
            return;
        }
        final String title = this.msg.plain("organized-title", p("country", k.name()));
        final Session s = this.open(Session.Kind.ORGANIZED, arena, title, Instant.now().plusSeconds(minutes * 60L), fee, k.id(), k.name());
        if (pool.get() > 0) {
            if (!eco.transfer(k.treasuryAccount(), s.ledger.escrowAccount, pool.get(), "Pula turnieju: " + title)) {
                this.sessions.remove(s);
                this.storage.state().escrows.remove(s.id);
                this.storage.saveState();
                this.msg.send(p, "organize-treasury-funds", p("amount", eco.format(pool.get())));
                return;
            }
            s.ledger.funded = pool.get();
            s.ledger.sourceAccount = k.treasuryAccount();
        }
        if (this.cfg.organizeCooldownMinutes > 0) {
            this.storage.state().countryCooldowns.put(k.id(), System.currentTimeMillis() + this.cfg.organizeCooldownMinutes * 60_000L);
        }
        this.storage.saveState();
        this.msg.send(p, "organize-done", p("title", title), p("time", Texts.duration(minutes * 60L)));
        this.announceOpen(s);
    }

    // =============================================================================================
    // sign-ups
    // =============================================================================================

    public void signup(final Player p) {
        final Optional<Session> open = this.openTournament();
        if (open.isEmpty()) {
            this.msg.send(p, "no-open-tournament", p("next", this.nextWeeklyText()));
            return;
        }
        final Session s = open.get();
        final UUID id = p.getUniqueId();
        if (s.isEntrant(id)) {
            this.msg.send(p, "already-signed");
            return;
        }
        if (this.busy(id)) {
            this.msg.send(p, "busy");
            return;
        }
        if (s.entrants.size() >= this.cfg.maxParticipants) {
            this.msg.send(p, "tournament-full", p("max", this.cfg.maxParticipants));
            return;
        }
        final Economy eco = this.services.economy();
        if (s.entryFee > 0) {
            if (!eco.transfer(eco.playerAccount(id), s.ledger.escrowAccount, s.entryFee, "Wpisowe: " + s.title)) {
                this.msg.send(p, "insufficient-funds", p("amount", eco.format(s.entryFee)));
                return;
            }
            s.ledger.addFee(id, s.entryFee);
            this.storage.saveState();
        }
        s.entrants.put(id, p.getName());
        this.msg.send(p, "signed-up", p("title", s.title), p("count", s.entrants.size()),
            p("fee", s.entryFee > 0 ? eco.format(s.entryFee) : this.msg.plain("money-none")),
            p("time", Texts.duration(Duration.between(Instant.now(), s.startAt).toSeconds())));
    }

    /** {@code /turniej wypisz}: before the start with a refund; during the event = withdrawal (lost fight). */
    public void signout(final Player p) {
        final UUID id = p.getUniqueId();
        final Session inside = this.sessionInside(id);
        if (inside != null) {
            if (inside.phase() == Phase.CELEBRATION) {
                this.msg.send(p, "signout-too-late");
                return;
            }
            this.withdraw(inside, p, Cause.LEFT);
            this.msg.send(p, "left-tournament", p("title", inside.title));
            return;
        }
        for (final Session s : this.sessions) {
            if (s.phase() == Phase.SIGNUPS && s.isEntrant(id)) {
                s.entrants.remove(id);
                final long refunded = this.refundFee(s, id);
                this.msg.send(p, "signed-out", p("title", s.title),
                    p("refund", refunded > 0 ? this.services.economy().format(refunded) : this.msg.plain("money-none")));
                if (s.kind == Session.Kind.DUEL) {
                    this.cancel(s, "reason-duel-left");
                }
                return;
            }
        }
        this.msg.send(p, "not-signed");
    }

    private long refundFee(final Session s, final UUID player) {
        final long fee = s.ledger.removeFee(player);
        if (fee > 0) {
            final Economy eco = this.services.economy();
            if (!eco.transfer(s.ledger.escrowAccount, eco.playerAccount(player), fee, "Zwrot wpisowego: " + s.title)) {
                s.ledger.addFee(player, fee);
                this.log.warning("Nie udało się zwrócić wpisowego " + fee + " md graczowi " + player + " (" + s.id + ") — zostaje w depozycie");
                this.storage.saveState();
                return 0;
            }
            this.storage.saveState();
        }
        return fee;
    }

    // =============================================================================================
    // the event
    // =============================================================================================

    private void begin(final Session s) {
        final List<UUID> ready = new ArrayList<>();
        for (final Map.Entry<UUID, String> e : List.copyOf(s.entrants.entrySet())) {
            final Player p = Bukkit.getPlayer(e.getKey());
            // An item held on the cursor makes PlayerCare.capture refuse; skip (and refund) that entrant instead of
            // letting the exception cancel the whole tournament for everybody.
            if (p == null || p.isDead() || !PlayerCare.canCapture(p) || this.sessionInside(e.getKey()) != null
                || this.storage.hasSnapshot(e.getKey())) {
                s.entrants.remove(e.getKey());
                final long refunded = this.refundFee(s, e.getKey());
                if (p != null) {
                    this.msg.send(p, "start-missed", p("title", s.title),
                        p("refund", refunded > 0 ? this.services.economy().format(refunded) : this.msg.plain("money-none")));
                }
                continue;
            }
            ready.add(e.getKey());
        }
        final int min = s.kind == Session.Kind.DUEL ? 2 : this.cfg.minParticipants;
        if (ready.size() < min) {
            this.cancel(s, "reason-too-few");
            return;
        }
        final Location waiting = this.waitingSpot(s);
        if (waiting == null || s.arena.spawns.stream().anyMatch(sp -> sp.toLocation() == null)) {
            this.cancel(s, "reason-world");
            return;
        }
        s.machine.moveTo(Phase.PREPARING);
        s.ledger.reopenWeeklyKey = null;
        if (s.isTournament()) {
            this.storage.saveState();
        }
        for (final UUID id : ready) {
            final Player p = Bukkit.getPlayer(id);
            final Spectator spec = this.spectators.remove(id);
            final Location origin = spec != null && spec.origin().toLocation() != null ? spec.origin().toLocation() : null;
            this.care.capture(p, s.gear, s.id, origin);
            s.inside.add(id);
            if (s.isTournament() || this.cfg.duelStats) {
                final PlayerStats st = this.storage.stats(id, p.getName());
                if (s.isTournament()) {
                    st.tournaments++;
                }
            }
            this.care.heal(p);
            this.care.teleport(p, waiting, null);
        }
        if (s.format == Session.Format.BRACKET) {
            s.bracket = Bracket.create(ready, this.random);
        } else {
            s.ffa = new FreeForAll<>(ready);
        }
        if (s.isTournament()) {
            Bukkit.getServer().sendMessage(this.msg.get("announce-begin", p("title", s.title), p("count", ready.size()), p("arena", s.arena.id)));
            if (s.bracket != null) {
                this.sendBracket(s);
            }
        }
        s.machine.moveTo(Phase.INTERMISSION);
        s.timer = this.cfg.intermissionSeconds + 2;
    }

    private void sendBracket(final Session s) {
        final int round = s.bracket.currentRound();
        final List<Component> lines = new ArrayList<>();
        lines.add(this.msg.get("bracket-header", p("round", round), p("rounds", s.bracket.totalRounds())));
        for (final Bracket.Match<UUID> m : s.bracket.round(round - 1)) {
            if (m.a() != null && m.b() != null) {
                lines.add(this.msg.get("bracket-pair", p("a", s.nameOf(m.a())), p("b", s.nameOf(m.b()))));
            } else if (m.done() && m.winner() != null) {
                lines.add(this.msg.get("bracket-bye", p("a", s.nameOf(m.winner()))));
            }
        }
        for (final Player p : this.audience(s)) {
            lines.forEach(p::sendMessage);
        }
    }

    private void nextMatch(final Session s) {
        if (s.format == Session.Format.BRACKET) {
            if (s.bracket.isFinished()) {
                this.celebrate(s);
                return;
            }
            final Optional<Bracket.Match<UUID>> next = s.bracket.nextPlayable();
            if (next.isEmpty()) {
                this.celebrate(s);
                return;
            }
            final Bracket.Match<UUID> m = next.get();
            for (final UUID f : List.of(m.a(), m.b())) {
                final Player p = Bukkit.getPlayer(f);
                if (p == null || p.isDead() || !s.isInside(f)) {
                    s.bracket.withdraw(f);
                    if (p != null && s.isInside(f) && p.isDead()) {
                        this.leave(s, f, false);
                    }
                    s.timer = 1;
                    return;
                }
            }
            final int roundBefore = s.match == null ? 0 : s.match.round();
            s.match = m;
            if (m.round() > roundBefore && m.round() > 0 && s.isTournament()) {
                this.sendBracket(s);
            }
            this.startMatch(s, List.of(m.a(), m.b()));
        } else {
            if (s.meleeFought || s.ffa.isFinished()) {
                this.celebrate(s);
                return;
            }
            final List<UUID> alive = new ArrayList<>();
            for (final UUID f : s.ffa.alive()) {
                final Player p = Bukkit.getPlayer(f);
                if (p == null || p.isDead() || !s.isInside(f)) {
                    s.ffa.eliminate(f);
                } else {
                    alive.add(f);
                }
            }
            if (alive.size() < 2) {
                this.celebrate(s);
                return;
            }
            s.meleeFought = true;
            this.startMatch(s, alive);
        }
    }

    private void startMatch(final Session s, final List<UUID> fighters) {
        s.fighters.clear();
        s.fighters.addAll(fighters);
        s.damage.clear();
        s.down.clear();
        s.matchNo++;
        s.timeLimit = s.format == Session.Format.MELEE ? this.cfg.meleeTimeLimitSeconds : this.cfg.timeLimitSeconds;
        s.machine.moveTo(Phase.COUNTDOWN);
        for (int i = 0; i < fighters.size(); i++) {
            final UUID id = fighters.get(i);
            final Player p = Bukkit.getPlayer(id);
            final Location spawn = s.arena.spawns.get(i % s.arena.spawns.size()).toLocation();
            if (p == null || spawn == null) {
                continue;
            }
            this.care.heal(p);
            if (s.gear == pl.medivalrpg.turnieje.arena.GearMode.KIT) {
                this.care.giveKit(p, s.mounted);
            }
            this.care.teleport(p, spawn, () -> {
                if (!p.isOnline() || !s.isInside(p.getUniqueId()) || !s.isLiveFighter(p.getUniqueId())) return;
                if (s.mounted && s.isLiveFighter(id) && p.isOnline() && !p.isDead()) {
                    final Horse h = this.horses.spawn(spawn, p);
                    s.horses.add(h.getUniqueId());
                    h.addPassenger(p);
                }
            });
        }
        s.timer = this.cfg.countdownSeconds;
        final Component announce = s.format == Session.Format.MELEE
            ? this.msg.get("melee-announce", p("count", fighters.size()), p("title", s.title))
            : this.msg.get("match-announce", p("round", s.bracket.currentRound()), p("rounds", s.bracket.totalRounds()),
                p("a", s.nameOf(fighters.get(0))), p("b", s.nameOf(fighters.get(1))), p("title", s.title));
        for (final Player p : this.audience(s)) {
            p.sendMessage(announce);
        }
    }

    /** Fighters pushed/flown out of the arena are put back on their spawn point. */
    private void keepFightersInside(final Session s) {
        for (int i = 0; i < s.fighters.size(); i++) {
            final UUID id = s.fighters.get(i);
            final Player p = Bukkit.getPlayer(id);
            if (p == null || s.down.contains(id) || this.care.isOwnTeleport(id)) {
                continue;
            }
            final Location l = p.getLocation();
            if (s.arena.box.contains(l.getWorld().getName(), l.getX(), l.getY(), l.getZ())) {
                continue;
            }
            final Location spawn = s.arena.spawns.get(i % s.arena.spawns.size()).toLocation();
            if (spawn == null) {
                continue;
            }
            this.msg.send(p, "left-arena-warning");
            final Entity vehicle = p.getVehicle();
            if (vehicle != null && this.horses.isOurs(vehicle)) {
                this.horses.dismount(p);
                vehicle.teleport(spawn);
                this.care.teleport(p, spawn, () -> vehicle.addPassenger(p));
            } else {
                this.care.teleport(p, spawn, null);
            }
        }
    }

    /** Lethal hit intercepted (damage cancelled): the victim lost. Called one tick later, main thread. */
    public void onLethal(final UUID victim) {
        final Session s = this.liveFightSession(victim);
        if (s == null || s.phase() != Phase.FIGHTING) {
            return;
        }
        this.knockOut(s, victim, Cause.KILL);
    }

    /** Marks the fighter as beaten and resolves the match (bracket) or the elimination (melee). */
    private void knockOut(final Session s, final UUID loser, final Cause cause) {
        if (!s.fighters.contains(loser) || s.down.contains(loser)) {
            return;
        }
        s.down.add(loser);
        if (s.format == Session.Format.BRACKET) {
            final UUID winner = s.fighters.get(0).equals(loser) ? s.fighters.get(1) : s.fighters.get(0);
            s.bracket.recordWinner(s.match, winner);
            this.recordFight(s, winner, loser);
            this.broadcastResult(s, winner, loser, cause);
            this.leave(s, loser, cause == Cause.QUIT);
            this.endMatch(s, List.of(winner));
        } else {
            s.ffa.eliminate(loser);
            this.recordFight(s, null, loser);
            final Component line = this.msg.get("melee-out", p("player", s.nameOf(loser)), p("left", s.ffa.alive().size()),
                p("cause", this.msg.plain("cause." + cause.name().toLowerCase(Locale.ROOT))));
            for (final Player p : this.audience(s)) {
                p.sendMessage(line);
            }
            this.leave(s, loser, cause == Cause.QUIT);
            if (s.ffa.isFinished()) {
                s.ffa.champion().ifPresent(w -> this.recordFight(s, w, null));
                this.endMatch(s, s.ffa.alive());
            }
        }
    }

    private void timeout(final Session s) {
        final List<TimeoutJudge.Score<UUID>> scores = new ArrayList<>();
        for (final UUID f : s.fighters) {
            if (s.down.contains(f)) {
                continue;
            }
            final Player p = Bukkit.getPlayer(f);
            final double share = p == null ? 0 : p.getHealth() / PlayerCare.maxHealth(p);
            scores.add(new TimeoutJudge.Score<>(f, s.damage.getOrDefault(f, 0.0), share));
        }
        final List<UUID> ranking = TimeoutJudge.rank(scores, this.random);
        for (final Player p : this.audience(s)) {
            this.msg.send(p, "timeout-announce");
        }
        if (s.format == Session.Format.BRACKET) {
            if (ranking.size() >= 2) {
                this.knockOut(s, ranking.get(1), Cause.TIMEOUT);
            } else if (ranking.size() == 1) {
                final UUID other = s.fighters.get(0).equals(ranking.getFirst()) ? s.fighters.get(1) : s.fighters.get(0);
                this.knockOut(s, other, Cause.TIMEOUT);
            }
        } else {
            for (int i = ranking.size() - 1; i >= 1; i--) {
                this.knockOut(s, ranking.get(i), Cause.TIMEOUT);
            }
            if (s.phase().matchLive()) {
                this.endMatch(s, s.ffa.alive());
            }
        }
    }

    private void recordFight(final Session s, final UUID winner, final UUID loser) {
        if (!s.isTournament() && !this.cfg.duelStats) {
            return;
        }
        if (winner != null) {
            this.storage.stats(winner, this.nameFor(s, winner)).wins++;
        }
        if (loser != null) {
            this.storage.stats(loser, this.nameFor(s, loser)).losses++;
        }
    }

    private String nameFor(final Session s, final UUID id) {
        final Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : s.nameOf(id);
    }

    private void broadcastResult(final Session s, final UUID winner, final UUID loser, final Cause cause) {
        final Component line = this.msg.get("result." + cause.name().toLowerCase(Locale.ROOT),
            p("winner", s.nameOf(winner)), p("loser", s.nameOf(loser)), p("title", s.title));
        for (final Player p : this.audience(s)) {
            p.sendMessage(line);
        }
    }

    /** Match over: remove horses, survivors back to the waiting spot, next phase. */
    private void endMatch(final Session s, final List<UUID> survivors) {
        this.horses.remove(s.horses);
        s.horses.clear();
        final Location waiting = this.waitingSpot(s);
        for (final UUID id : survivors) {
            final Player p = Bukkit.getPlayer(id);
            if (p == null || !s.isInside(id)) {
                continue;
            }
            this.horses.dismount(p);
            this.care.heal(p);
            if (s.gear == pl.medivalrpg.turnieje.arena.GearMode.KIT) {
                this.care.clearKit(p);
            }
            this.msg.send(p, "won-match-you");
            if (waiting != null) {
                this.care.teleport(p, waiting, null);
            }
        }
        s.fighters.clear();
        s.down.clear();
        s.machine.moveTo(Phase.INTERMISSION);
        final boolean over = s.format == Session.Format.BRACKET ? s.bracket.isFinished() : s.ffa.isFinished() || s.meleeFought;
        s.timer = over ? 2 : this.cfg.intermissionSeconds;
    }

    /** A participant leaves for good (lost, withdrew, disconnected); restored now, after respawn, or at the next join. */
    private void leave(final Session s, final UUID id, final boolean quitting) {
        if (!s.inside.remove(id)) {
            return;
        }
        final Player p = Bukkit.getPlayer(id);
        if (p == null) {
            return;
        }
        this.hideBar(s, p);
        if (quitting) {
            this.care.onQuitInside(p);
            return;
        }
        this.horses.dismount(p);
        if (this.care.restore(p) && !s.phase().terminal() && s.phase() != Phase.CELEBRATION && s.isTournament()) {
            this.msg.send(p, "eliminated-you", p("title", s.title));
        }
    }

    /**
     * {@link #leave} for everybody still inside. A failed restore (e.g. a refused teleport) is logged and skipped: the
     * snapshot stays on disk and is applied at the next respawn/join, while the session can still be cleaned up —
     * otherwise it would stay in the list forever and block its arena and every further tournament until a restart.
     */
    private void leaveAll(final Session s) {
        for (final UUID id : List.copyOf(s.inside)) {
            try {
                this.leave(s, id, false);
            } catch (final RuntimeException e) {
                this.log.log(java.util.logging.Level.SEVERE, "Nie przywrócono gracza " + id + " po sesji " + s.id
                    + " — snapshot zostaje do ponownego wejścia/odrodzenia", e);
            }
        }
    }

    /** Withdrawal of a participant (wypisz, disconnect) while the event runs. */
    private void withdraw(final Session s, final Player p, final Cause cause) {
        final UUID id = p.getUniqueId();
        if (s.isLiveFighter(id)) {
            this.knockOut(s, id, cause);
            return;
        }
        if (s.bracket != null) {
            s.bracket.withdraw(id);
        }
        if (s.ffa != null) {
            s.ffa.eliminate(id);
        }
        this.leave(s, id, cause == Cause.QUIT);
    }

    // =============================================================================================
    // end: prizes, celebration, cleanup, cancellation
    // =============================================================================================

    private void celebrate(final Session s) {
        if (!s.machine.tryMoveTo(Phase.CELEBRATION)) {
            return;
        }
        this.horses.remove(s.horses);
        s.horses.clear();
        s.placements = s.bracket != null ? s.bracket.placements() : s.ffa.placements();
        s.champion = !s.placements.isEmpty() && !s.placements.getFirst().isEmpty() ? s.placements.getFirst().getFirst() : null;
        if (s.champion != null && s.isTournament()) {
            this.storage.stats(s.champion, this.nameFor(s, s.champion)).titles++;
            if (this.cfg.trophyEnabled) {
                final var trophy = new Storage.TrophyRecord(s.title, LocalDate.now(this.cfg.weekly.zone()).toString(), s.arena.id, s.id);
                this.storage.state().pendingTrophies.computeIfAbsent(s.champion, k -> new ArrayList<>()).add(trophy);
                this.storage.saveState();
            }
        }
        this.payout(s);
        final Economy eco = this.services.economy();
        if (s.champion == null) {
            final Component line = this.msg.get("no-winner", p("title", s.title));
            if (s.isTournament()) {
                Bukkit.getServer().sendMessage(line);
            } else {
                this.audience(s).forEach(p -> p.sendMessage(line));
            }
        } else if (s.isTournament()) {
            final long prize = s.payouts.getOrDefault(s.champion, 0L);
            Bukkit.getServer().sendMessage(this.msg.get("winner-broadcast", p("title", s.title), p("player", s.nameOf(s.champion)),
                p("prize", prize > 0 ? eco.format(prize) : this.msg.plain("money-none"))));
            for (int place = 1; place < s.placements.size(); place++) {
                for (final UUID id : s.placements.get(place)) {
                    final long amount = s.payouts.getOrDefault(id, 0L);
                    Bukkit.getServer().sendMessage(this.msg.get("place-line", p("place", place + 1), p("player", s.nameOf(id)),
                        p("prize", amount > 0 ? eco.format(amount) : this.msg.plain("money-none"))));
                }
            }
            final Title title = Title.title(this.msg.get("winner-title", p("player", s.nameOf(s.champion))),
                this.msg.get("winner-subtitle", p("title", s.title)));
            for (final Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(title);
            }
        } else {
            final UUID loser = s.placements.size() > 1 && !s.placements.get(1).isEmpty() ? s.placements.get(1).getFirst() : null;
            final Component line = this.msg.get("duel-result", p("winner", s.nameOf(s.champion)), p("loser", loser == null ? "?" : s.nameOf(loser)));
            this.audience(s).forEach(p -> p.sendMessage(line));
        }
        final Location waiting = this.waitingSpot(s);
        this.celebration.fireworks(waiting, s.isTournament() ? this.cfg.fireworks : Math.min(2, this.cfg.fireworks));
        final Player champ = s.champion == null ? null : Bukkit.getPlayer(s.champion);
        if (champ != null && s.isTournament()) {
            this.celebration.fireworks(champ.getLocation(), Math.max(1, this.cfg.fireworks / 2));
        }
        s.timer = s.isTournament() ? this.cfg.celebrationSeconds : 3;
    }

    /** Prizes from escrow (minting only when allowed); remainder back to the source (or to the champion when none). */
    private void payout(final Session s) {
        final EscrowLedger l = s.ledger;
        final Economy eco = this.services.economy();
        if (l.prizePool() <= 0) {
            this.dropLedger(s);
            return;
        }
        if (s.champion == null) { this.refundAll(s); return; }
        final long pool = l.prizePool();
        final PrizeSplit.Result<UUID> r = PrizeSplit.split(pool, this.cfg.split, s.placements, this.cfg.unclaimedToChampion);
        l.settling = true;
        for (final var e : r.payouts().entrySet()) {
            l.payments.merge(eco.playerAccount(e.getKey()), e.getValue(), Math::addExact);
        }
        if (r.remainder() > 0) {
            final String recipient = l.sourceAccount != null ? l.sourceAccount
                : s.champion == null ? null : eco.playerAccount(s.champion);
            if (recipient != null) l.payments.merge(recipient, r.remainder(), Math::addExact);
        }
        this.storage.saveState();
        if (eco.available() && this.settle(l, eco)) this.dropLedger(s);
        for (final var e : r.payouts().entrySet()) {
            if (!l.payments.containsKey(eco.playerAccount(e.getKey()))) s.payouts.put(e.getKey(), e.getValue());
        }
    }

    private boolean settle(final EscrowLedger l, final Economy eco) {
        return pl.medivalrpg.turnieje.data.EscrowSettlement.settle(l, eco, this.storage::saveState);
    }

    private void dropLedger(final Session s) {
        s.ledger.clear();
        if (this.storage.state().escrows.remove(s.id) != null) {
            this.storage.saveState();
        }
    }

    /** End of the celebration: everybody restored, trophy handed over, session removed. */
    private void finish(final Session s) {
        this.leaveAll(s);
        if (s.champion != null && s.isTournament() && this.cfg.trophyEnabled) {
            final Player champ = Bukkit.getPlayer(s.champion);
            if (champ != null && !champ.isDead() && !this.storage.hasSnapshot(s.champion)) this.deliverTrophies(champ);
        }
        this.cleanup(s);
        s.machine.moveTo(Phase.FINISHED);
    }

    private void deliverTrophies(final Player p) {
        final List<Storage.TrophyRecord> trophies = this.storage.state().pendingTrophies.get(p.getUniqueId());
        if (trophies == null) return;
        for (final var t : List.copyOf(trophies)) {
            if (!this.celebration.giveOnce(p, t, this.cfg, this.msg)) break;
            trophies.remove(t);
            this.storage.saveState();
        }
        if (trophies.isEmpty()) {
            this.storage.state().pendingTrophies.remove(p.getUniqueId());
            this.storage.saveState();
        }
    }

    /** Cancels a session: refunds (until prizes were paid), restores everybody, removes it. */
    public void cancel(final Session s, final String reasonKey) {
        if (s.phase().terminal() || !this.sessions.contains(s)) {
            return;
        }
        if (s.phase() == Phase.CELEBRATION) {
            this.finish(s);
            return;
        }
        this.refundAll(s);
        s.machine.moveTo(Phase.CANCELLED);
        this.leaveAll(s);
        this.cleanup(s);
        final Component line = this.msg.get("announce-cancel", p("title", s.title), p("reason", this.msg.plain(reasonKey)));
        if (s.isTournament()) {
            Bukkit.getServer().sendMessage(line);
        } else {
            for (final UUID id : s.entrants.keySet()) {
                final Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.sendMessage(line);
                }
            }
        }
    }

    private void refundAll(final Session s) {
        final EscrowLedger l = s.ledger;
        if (l.held() <= 0) {
            this.dropLedger(s);
            return;
        }
        final Economy eco = this.services.economy();
        if (!eco.available()) {
            this.log.warning("Skarbiec niedostępny — zwrot depozytu turnieju " + s.id + " przy następnym starcie");
            return;
        }
        if (this.refund(l, eco)) {
            for (final Map.Entry<UUID, Long> e : l.refundPlan().fees().entrySet()) {
                final Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    this.msg.send(p, "refund-received", p("amount", eco.format(e.getValue())), p("title", s.title));
                }
            }
            this.dropLedger(s);
        } else {
            this.storage.saveState();
        }
    }

    /** Executes the refund plan of a ledger; true when everything went back. Failed parts stay in the ledger. */
    private boolean refund(final EscrowLedger l, final Economy eco) {
        return pl.medivalrpg.turnieje.data.EscrowSettlement.refund(l, eco, this.storage::saveState);
    }

    private void cleanup(final Session s) {
        this.horses.remove(s.horses);
        s.horses.clear();
        if (s.bar != null) {
            for (final UUID id : s.barViewers) {
                final Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.hideBossBar(s.bar);
                }
            }
            s.barViewers.clear();
        }
        this.sessions.remove(s);
        this.storage.saveStatsIfDirty();
    }

    /**
     * After a restart: every ledger left in {@code state.json} belongs to a tournament that did not finish — refund it.
     * A weekly tournament still in sign-ups gets its week reopened.
     */
    public void recoverAfterRestart() {
        final Map<String, EscrowLedger> escrows = this.storage.state().escrows;
        if (escrows.isEmpty()) {
            return;
        }
        final Economy eco = this.services.economy();
        if (!eco.available()) {
            this.log.warning("Niezakończone turnieje: " + escrows.size() + ", ale Skarbiec jest niedostępny — zwrot przy następnym starcie");
            return;
        }
        for (final EscrowLedger l : List.copyOf(escrows.values())) {
            if (this.sessions.stream().anyMatch(s -> s.id.equals(l.sessionId))) continue;
            final long total = l.held();
            if (l.settling ? this.settle(l, eco) : this.refund(l, eco)) {
                escrows.remove(l.sessionId);
                this.log.info("Turniej '" + l.title + "' (" + l.sessionId + ") przerwany restartem — zwrócono " + eco.format(total));
                if (l.reopenWeeklyKey != null && l.reopenWeeklyKey.equals(this.storage.state().weeklyKey)) {
                    this.storage.state().weeklyKey = null;
                }
            } else {
                this.log.warning("Turniej " + l.sessionId + ": część depozytu nie wróciła, spróbuję przy następnym starcie");
            }
        }
        this.storage.saveState();
    }

    /** Plugin disable: everything cancelled with refunds, players restored synchronously, spectators sent back. */
    public void shutdown() {
        this.care.shuttingDown();
        for (final Session s : List.copyOf(this.sessions)) {
            try {
                this.cancel(s, "reason-shutdown");
            } catch (final RuntimeException e) {
                // the ledger and snapshots are on disk: refund/restore happen at the next start
                this.log.log(java.util.logging.Level.SEVERE, "Nie anulowano sesji " + s.id + " przy wyłączaniu", e);
            }
        }
        for (final UUID id : List.copyOf(this.spectators.keySet())) {
            final Player p = Bukkit.getPlayer(id);
            if (p != null) {
                this.stopSpectating(p, true);
            }
        }
        this.spectators.clear();
    }

    // =============================================================================================
    // player lifecycle hooks (listeners)
    // =============================================================================================

    public void onQuit(final Player p) {
        final UUID id = p.getUniqueId();
        this.duelRequests.remove(id);
        this.duelRequests.values().removeIf(r -> r.from().equals(id));
        this.spectators.remove(id);
        final Session s = this.sessionInside(id);
        if (s != null) {
            if (s.phase() == Phase.CELEBRATION) {
                this.leave(s, id, true);
            } else {
                this.withdraw(s, p, Cause.QUIT);
            }
        }
    }

    /** Death inside an event (lethal damage normally never gets here). Inventory is kept, nothing drops. */
    public boolean onDeath(final Player p) {
        final UUID id = p.getUniqueId();
        final Session s = this.sessionInside(id);
        if (s == null) {
            return this.storage.hasSnapshot(id);
        }
        this.withdraw(s, p, Cause.DEATH);
        return true;
    }

    /** Where a player respawns: waiting spot while inside, the stored position with a pending snapshot. */
    public Location respawnLocation(final Player p) {
        final Session s = this.sessionInside(p.getUniqueId());
        if (s != null) {
            return this.waitingSpot(s);
        }
        final var snap = this.storage.snapshot(p.getUniqueId());
        return snap == null || snap.location == null ? null : snap.location.toLocation();
    }

    /** After respawn/join: apply a pending snapshot (not while still inside), hand over trophies. */
    public void afterRespawnOrJoin(final Player p) {
        final UUID id = p.getUniqueId();
        if (!p.isOnline() || p.isDead() || this.sessionInside(id) != null) {
            return;
        }
        if (this.storage.hasSnapshot(id)) {
            this.care.restore(p);
            this.msg.send(p, "restored");
        } else {
            this.care.stripKit(p);
        }
        this.deliverTrophies(p);
    }

    public void addDamage(final UUID attacker, final double amount) {
        final Session s = this.liveFightSession(attacker);
        if (s != null) {
            s.damage.merge(attacker, amount, Double::sum);
        }
    }

    // =============================================================================================
    // duels
    // =============================================================================================

    public void challenge(final Player from, final String targetName) {
        if (!this.cfg.duelEnabled) {
            this.msg.send(from, "duel-disabled");
            return;
        }
        final Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            this.msg.send(from, "player-offline", p("player", targetName));
            return;
        }
        if (target.equals(from)) {
            this.msg.send(from, "duel-self");
            return;
        }
        if (this.busy(from.getUniqueId())) {
            this.msg.send(from, "busy");
            return;
        }
        if (this.busy(target.getUniqueId())) {
            this.msg.send(from, "duel-target-busy", p("player", target.getName()));
            return;
        }
        if (this.freeDuelArena() == null) {
            this.msg.send(from, "duel-no-arena");
            return;
        }
        this.duelRequests.put(target.getUniqueId(), new DuelRequest(from.getUniqueId(), from.getName(),
            Instant.now().plusSeconds(this.cfg.duelRequestSeconds)));
        this.msg.send(from, "duel-sent", p("player", target.getName()), p("seconds", this.cfg.duelRequestSeconds));
        this.msg.send(target, "duel-received", p("player", from.getName()), p("seconds", this.cfg.duelRequestSeconds));
    }

    public void acceptDuel(final Player target) {
        final DuelRequest r = this.duelRequests.remove(target.getUniqueId());
        if (r == null || r.expires().isBefore(Instant.now())) {
            this.msg.send(target, "duel-none");
            return;
        }
        final Player from = Bukkit.getPlayer(r.from());
        if (from == null) {
            this.msg.send(target, "player-offline", p("player", r.fromName()));
            return;
        }
        if (this.busy(from.getUniqueId()) || this.busy(target.getUniqueId())) {
            this.msg.send(target, "busy");
            return;
        }
        final Arena arena = this.freeDuelArena();
        if (arena == null) {
            this.msg.send(target, "duel-no-arena");
            return;
        }
        final String title = this.msg.plain("duel-title", p("a", from.getName()), p("b", target.getName()));
        final Session s = this.open(Session.Kind.DUEL, arena, title, Instant.now(), 0, null, null);
        s.entrants.put(from.getUniqueId(), from.getName());
        s.entrants.put(target.getUniqueId(), target.getName());
        this.msg.send(from, "duel-accepted", p("player", target.getName()), p("arena", arena.id));
        this.msg.send(target, "duel-accepted", p("player", from.getName()), p("arena", arena.id));
    }

    public void declineDuel(final Player target) {
        final DuelRequest r = this.duelRequests.remove(target.getUniqueId());
        if (r == null) {
            this.msg.send(target, "duel-none");
            return;
        }
        this.msg.send(target, "duel-declined-you", p("player", r.fromName()));
        final Player from = Bukkit.getPlayer(r.from());
        if (from != null) {
            this.msg.send(from, "duel-declined", p("player", target.getName()));
        }
    }

    private Arena freeDuelArena() {
        for (final Arena a : this.storage.arenas().values()) {
            if (a.type == pl.medivalrpg.turnieje.arena.ArenaType.POJEDYNEK && a.ready() && this.sessionOn(a.id) == null) {
                return a;
            }
        }
        return null;
    }

    private void expireDuelRequests() {
        final Instant now = Instant.now();
        this.duelRequests.entrySet().removeIf(e -> {
            if (e.getValue().expires().isBefore(now)) {
                final Player from = Bukkit.getPlayer(e.getValue().from());
                if (from != null) {
                    this.msg.send(from, "duel-expired");
                }
                return true;
            }
            return false;
        });
    }

    // =============================================================================================
    // spectators
    // =============================================================================================

    public void spectate(final Player p, final String arenaId) {
        final UUID id = p.getUniqueId();
        if (this.spectators.containsKey(id)) {
            this.stopSpectating(p, true);
            this.msg.send(p, "spectate-stop");
            return;
        }
        if (!this.cfg.spectateEnabled) {
            this.msg.send(p, "spectate-disabled");
            return;
        }
        if (this.sessionInside(id) != null) {
            this.msg.send(p, "spectate-participant");
            return;
        }
        Arena arena = null;
        if (arenaId != null) {
            arena = this.storage.arenas().get(arenaId.toLowerCase(Locale.ROOT));
            if (arena == null) {
                this.msg.send(p, "unknown-arena", p("arena", arenaId));
                return;
            }
        } else {
            final Session s = this.currentTournament().orElseGet(() -> this.sessions.isEmpty() ? null : this.sessions.getFirst());
            arena = s == null ? null : s.arena;
        }
        if (arena == null) {
            this.msg.send(p, "spectate-none");
            return;
        }
        final Location to = arena.spectatorPos().toLocation();
        if (to == null) {
            this.msg.send(p, "spectate-none");
            return;
        }
        this.spectators.put(id, new Spectator(arena.id, Pos.of(p.getLocation())));
        this.care.teleport(p, to, null);
        this.msg.send(p, "spectate-start", p("arena", arena.id));
    }

    private void stopSpectating(final Player p, final boolean teleportBack) {
        final Spectator s = this.spectators.remove(p.getUniqueId());
        if (s == null) {
            return;
        }
        for (final Session session : this.sessions) {
            this.hideBar(session, p);
        }
        final Location back = s.origin().toLocation();
        if (teleportBack && back != null) {
            this.care.teleport(p, back, null);
        }
    }

    /** Spectators walking into a live arena are put back on the stand. */
    public void onSpectatorMove(final Player p, final Location to) {
        final Arena a = this.spectatedArena(p.getUniqueId());
        if (a == null || this.care.isOwnTeleport(p.getUniqueId())) {
            return;
        }
        final Session s = this.sessionOn(a.id);
        if (s == null || !s.phase().matchLive()) {
            return;
        }
        if (a.box.contains(to.getWorld().getName(), to.getX(), to.getY(), to.getZ())) {
            final Location stand = a.spectatorPos().toLocation();
            if (stand != null && !a.box.contains(stand.getWorld().getName(), stand.getX(), stand.getY(), stand.getZ())) {
                this.care.teleport(p, stand, null);
                this.msg.send(p, "spectate-blocked");
            }
        }
    }

    // =============================================================================================
    // boss bar & audience
    // =============================================================================================

    /** Participants, entrants (sign-ups) and spectators of the session's arena. */
    private List<Player> audience(final Session s) {
        final Set<UUID> ids = new HashSet<>(s.inside);
        if (s.phase() == Phase.SIGNUPS || s.phase() == Phase.CELEBRATION) {
            ids.addAll(s.entrants.keySet());
        }
        for (final Map.Entry<UUID, Spectator> e : this.spectators.entrySet()) {
            if (e.getValue().arena().equals(s.arena.id)) {
                ids.add(e.getKey());
            }
        }
        final List<Player> out = new ArrayList<>();
        for (final UUID id : ids) {
            final Player p = Bukkit.getPlayer(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private void updateBar(final Session s) {
        final Component name;
        float progress = 1f;
        BossBar.Color color = BossBar.Color.YELLOW;
        switch (s.phase()) {
            case SIGNUPS -> {
                final long left = Math.max(0, Duration.between(Instant.now(), s.startAt).toSeconds());
                name = this.msg.get(s.isTournament() ? "bossbar-signups" : "bossbar-duel-start", p("title", s.title),
                    p("time", Texts.clock(left)), p("count", s.entrants.size()));
                progress = s.signupSeconds <= 0 ? 0f : (float) left / s.signupSeconds;
            }
            case COUNTDOWN, FIGHTING -> {
                color = BossBar.Color.RED;
                final int limit = Math.max(1, s.timeLimit);
                final int left = s.phase() == Phase.COUNTDOWN ? limit : s.timer;
                if (s.format == Session.Format.MELEE) {
                    name = this.msg.get("bossbar-melee", p("title", s.title), p("count", s.fighters.size() - s.down.size()), p("time", Texts.clock(left)));
                } else {
                    name = this.msg.get("bossbar-fight", p("title", s.title), p("a", s.nameOf(s.fighters.get(0))),
                        p("b", s.nameOf(s.fighters.get(1))), p("time", Texts.clock(left)));
                }
                progress = (float) left / limit;
            }
            case CELEBRATION -> {
                color = BossBar.Color.PURPLE;
                name = this.msg.get("bossbar-celebration", p("title", s.title),
                    p("player", s.champion == null ? "—" : s.nameOf(s.champion)));
            }
            default -> {
                color = BossBar.Color.BLUE;
                name = this.msg.get("bossbar-intermission", p("title", s.title),
                    p("round", s.bracket != null ? s.bracket.currentRound() : 1),
                    p("rounds", s.bracket != null ? s.bracket.totalRounds() : 1), p("time", Texts.clock(Math.max(0, s.timer))));
            }
        }
        progress = Math.max(0f, Math.min(1f, progress));
        if (s.bar == null) {
            s.bar = BossBar.bossBar(name, progress, color, BossBar.Overlay.PROGRESS);
        } else {
            s.bar.name(name);
            s.bar.progress(progress);
            s.bar.color(color);
        }
        final Set<UUID> wanted = new HashSet<>();
        if (s.phase() == Phase.SIGNUPS && s.isTournament() && this.cfg.bossbarForAll) {
            for (final Player p : Bukkit.getOnlinePlayers()) {
                wanted.add(p.getUniqueId());
            }
        } else {
            for (final Player p : this.audience(s)) {
                wanted.add(p.getUniqueId());
            }
        }
        for (final UUID id : List.copyOf(s.barViewers)) {
            if (!wanted.contains(id)) {
                final Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.hideBossBar(s.bar);
                }
                s.barViewers.remove(id);
            }
        }
        for (final UUID id : wanted) {
            if (s.barViewers.add(id)) {
                final Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.showBossBar(s.bar);
                }
            }
        }
    }

    private void hideBar(final Session s, final Player p) {
        if (s.bar != null && s.barViewers.remove(p.getUniqueId())) {
            p.hideBossBar(s.bar);
        }
    }

    // =============================================================================================
    // info texts
    // =============================================================================================

    /** {@code sobota 20:00 (za 2 d 3 h)} or the "weekly off" text. */
    public String nextWeeklyText() {
        if (!this.cfg.weeklyEnabled) {
            return this.msg.plain("weekly-off");
        }
        final ZonedDateTime next = this.cfg.weekly.nextStart(Instant.now());
        return this.msg.plain("weekly-next", p("day", WeeklySchedule.polishDay(next.getDayOfWeek())),
            p("time", String.format(Locale.ROOT, "%02d:%02d", next.getHour(), next.getMinute())),
            p("in", Texts.duration(Duration.between(Instant.now(), next.toInstant()).toSeconds())));
    }

    /** Lines of {@code /turniej info}. */
    public List<Component> info() {
        final List<Component> out = new ArrayList<>();
        final Optional<Session> cur = this.currentTournament();
        final Economy eco = this.services.economy();
        if (cur.isEmpty()) {
            out.add(this.msg.get("info-none", p("next", this.nextWeeklyText())));
            for (final Session d : this.sessions) {
                out.add(this.msg.get("info-duel", p("title", d.title), p("arena", d.arena.id)));
            }
            return out;
        }
        final Session s = cur.get();
        out.add(this.msg.get("info-header", p("title", s.title)));
        out.add(this.msg.get("info-arena", p("arena", s.arena.id), Messages.c("type", this.typeName(s)),
            p("gear", this.msg.plain("gear." + s.gear.id()))));
        out.add(this.msg.get("info-organizer", p("organizer", s.organizerName == null ? this.msg.plain("organizer-server") : s.organizerName)));
        out.add(this.msg.get("info-phase", p("phase", this.msg.plain("phase." + s.phase().name().toLowerCase(Locale.ROOT)))));
        final List<String> split = new ArrayList<>();
        for (final int pct : this.cfg.split) {
            split.add(pct + "%");
        }
        out.add(this.msg.get("info-prize", p("prize", s.ledger.prizePool() > 0 ? eco.format(s.ledger.prizePool()) : this.msg.plain("money-none")),
            p("fee", s.entryFee > 0 ? eco.format(s.entryFee) : this.msg.plain("money-none")), p("split", String.join(" / ", split))));
        if (s.phase() == Phase.SIGNUPS) {
            out.add(this.msg.get("info-start", p("time", Texts.duration(Duration.between(Instant.now(), s.startAt).toSeconds()))));
            out.add(this.msg.get("info-entrants", p("count", s.entrants.size()), p("max", this.cfg.maxParticipants),
                p("names", s.entrants.isEmpty() ? "—" : String.join(", ", s.entrants.values()))));
        } else if (s.bracket != null) {
            out.add(this.msg.get("info-round", p("round", s.bracket.currentRound()), p("rounds", s.bracket.totalRounds())));
            for (final Bracket.Match<UUID> m : s.bracket.playable()) {
                out.add(this.msg.get(m == s.match && s.phase().matchLive() ? "info-match-live" : "info-match",
                    p("a", s.nameOf(m.a())), p("b", s.nameOf(m.b())), p("time", Texts.clock(Math.max(0, s.timer)))));
            }
        } else if (s.ffa != null) {
            final List<String> alive = new ArrayList<>();
            for (final UUID id : s.ffa.alive()) {
                alive.add(s.nameOf(id));
            }
            out.add(this.msg.get("info-alive", p("names", String.join(", ", alive))));
        }
        out.add(this.msg.get("info-next-weekly", p("next", this.nextWeeklyText())));
        return out;
    }

    /** Ranking lines for {@code /turniej ranking [strona]}. */
    public List<Component> ranking(final Player viewer, final int page) {
        final List<PlayerStats> all = PlayerStats.ranking(this.storage.stats().values());
        final List<Component> out = new ArrayList<>();
        final int perPage = 10;
        final int pages = PlayerStats.pages(all.size(), perPage);
        final int pg = Math.max(1, Math.min(page, pages));
        out.add(this.msg.get("ranking-header", p("page", pg), p("pages", pages)));
        if (all.isEmpty()) {
            out.add(this.msg.get("ranking-empty"));
            return out;
        }
        final List<PlayerStats> slice = PlayerStats.page(all, pg, perPage);
        int pos = (pg - 1) * perPage;
        for (final PlayerStats st : slice) {
            pos++;
            out.add(this.msg.get("ranking-line", p("pos", pos), p("player", st.name == null ? "?" : st.name), p("titles", st.titles),
                p("wins", st.wins), p("losses", st.losses), p("percent", st.winPercent())));
        }
        if (viewer != null) {
            for (int i = 0; i < all.size(); i++) {
                if (viewer.getUniqueId().equals(all.get(i).uuid)) {
                    final PlayerStats st = all.get(i);
                    out.add(this.msg.get("ranking-you", p("pos", i + 1), p("titles", st.titles), p("wins", st.wins),
                        p("losses", st.losses), p("tournaments", st.tournaments)));
                }
            }
        }
        if (pg < pages) {
            out.add(this.msg.get("ranking-next", p("next", pg + 1))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/turniej ranking " + (pg + 1))));
        }
        return out;
    }
}
