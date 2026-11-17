// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.auth;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import pl.medivalrpg.straznik.account.Account;
import pl.medivalrpg.straznik.account.AccountStore;
import pl.medivalrpg.straznik.account.Names;
import pl.medivalrpg.straznik.api.AuthMethod;
import pl.medivalrpg.straznik.api.StraznikApi;
import pl.medivalrpg.straznik.api.VerifyResult;
import pl.medivalrpg.straznik.api.event.PasswordChangedEvent;
import pl.medivalrpg.straznik.api.event.PlayerAuthenticatedEvent;
import pl.medivalrpg.straznik.config.Messages;
import pl.medivalrpg.straznik.config.Settings;
import pl.medivalrpg.straznik.crypto.PasswordHasher;
import pl.medivalrpg.straznik.limbo.LimboStore;
import pl.medivalrpg.straznik.log.EventLog;

/**
 * Heart of the plugin: who is waiting for {@code /login}, who is authenticated, the limbo (hidden position and
 * inventory) and the password operations. Main thread unless a method says otherwise; Argon2 runs on
 * {@code hashPool} and the result comes back to the main thread.
 */
public final class AuthService implements StraznikApi {

    /** A player on the login screen. Main thread only. */
    static final class Waiting {
        final UUID id;
        final String nick;
        final String ip;
        final long deadline;
        final long joined;
        int attemptsLeft;
        boolean busy;
        /** Real place and inventory (also on disk in {@link LimboStore} when {@code secured}). */
        Location realLocation;
        ItemStack[] realItems;
        boolean realAllowFlight;
        boolean realFlying;
        boolean secured;
        boolean inventoryHidden;
        boolean blinded;
        long lastReminder;
        BossBar bar;

        Waiting(final UUID id, final String nick, final String ip, final long joined, final long deadline, final int attempts) {
            this.id = id;
            this.nick = nick;
            this.ip = ip;
            this.joined = joined;
            this.deadline = deadline;
            this.attemptsLeft = attempts;
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final Supplier<Settings> settings;
    private final Supplier<Messages> messages;
    private final AccountStore store;
    private final LimboStore limbo;
    private final EventLog events;
    private final ExecutorService hashPool;
    private volatile PasswordHasher hasher;
    private volatile PasswordPolicy policy;
    private volatile LoginGuard guard;
    private final SessionStore sessions = new SessionStore();

    // thread-safe state shared with the async login events and other plugins
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> connectIps = new ConcurrentHashMap<>();
    private final Map<UUID, Location> spawnRealLocation = new ConcurrentHashMap<>();
    private final Set<UUID> sessionAtSpawn = ConcurrentHashMap.newKeySet();
    private final Map<String, UUID> onlineByNick = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private volatile Location limboLocation;

    // main thread
    private final Map<UUID, Waiting> waiting = new HashMap<>();
    private final Set<UUID> changing = new java.util.HashSet<>();

    public AuthService(final Plugin plugin, final Supplier<Settings> settings, final Supplier<Messages> messages,
                       final AccountStore store, final LimboStore limbo, final EventLog events, final ExecutorService hashPool,
                       final Set<String> weakPasswords) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.settings = settings;
        this.messages = messages;
        this.store = store;
        this.limbo = limbo;
        this.events = events;
        this.hashPool = hashPool;
        this.reload(weakPasswords);
        this.dialogs = new LoginDialogs(plugin, messages, new LoginDialogs.Handler() {
            @Override
            public void submit(final Player player, final boolean register, final String password, final String repeat) {
                if (!AuthService.this.waiting.containsKey(player.getUniqueId())) {
                    player.closeDialog();
                } else if (register) {
                    AuthService.this.register(player, password, repeat);
                } else {
                    AuthService.this.login(player, password);
                }
            }

            @Override
            public void leave(final Player player) {
                player.kick(AuthService.this.messages.get().get("wyszedl-kick"));
            }
        });
    }

    private final LoginDialogs dialogs;

    /** Message in chat and — while the login window is in use — a new window with this message. Main thread. */
    private void tell(final Player player, final Waiting w, final Component message) {
        player.sendMessage(message);
        if (w != null && this.waiting.get(player.getUniqueId()) == w) {
            this.showDialog(player, w, message);
        }
    }

    private void showDialog(final Player player, final Waiting w, final Component error) {
        if (!this.settings.get().dialog || !player.isOnline()) {
            return;
        }
        final long left = Math.max(0, (w.deadline - System.currentTimeMillis() + 999) / 1000);
        try {
            this.dialogs.show(player, this.store.find(player.getName()).isPresent(), w.attemptsLeft, left, this.policy.min(), error);
        } catch (final RuntimeException | LinkageError e) {
            this.logger.warning("Nie udało się pokazać okna logowania (" + e.getClass().getSimpleName() + ") — zostają komendy czatu.");
        }
    }

    /** Applies config values (hash parameters, limits, limbo location). Main thread. */
    public void reload(final Set<String> weakPasswords) {
        final Settings s = this.settings.get();
        this.hasher = new PasswordHasher(s.argonMemoryKiB, s.argonIterations, s.argonLanes, s.hashThreads);
        this.policy = new PasswordPolicy(s.passwordMinLength, s.passwordMaxLength, s.passwordForbidNick, weakPasswords);
        this.guard = new LoginGuard(s.lockoutFailures, s.lockoutWindowMinutes * 60_000L, s.lockoutMinutes * 60_000L);
        this.limboLocation = resolveLimbo(s.limboLocation, this.logger);
    }

    static Location resolveLimbo(final String spec, final Logger logger) {
        if (spec != null && !spec.isEmpty()) {
            final String[] p = spec.split(";");
            final World w = p.length >= 4 ? Bukkit.getWorld(p[0].strip()) : null;
            if (w != null) {
                try {
                    return new Location(w, Double.parseDouble(p[1].strip()), Double.parseDouble(p[2].strip()),
                        Double.parseDouble(p[3].strip()), p.length > 4 ? Float.parseFloat(p[4].strip()) : 0f,
                        p.length > 5 ? Float.parseFloat(p[5].strip()) : 0f);
                } catch (final NumberFormatException e) {
                    // fall through
                }
            }
            logger.warning("limbo.lokalizacja '" + spec + "' jest nieprawidłowa — używam spawnu głównego świata.");
        }
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        final Location spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
        return spawn.add(spawn.getX() == Math.floor(spawn.getX()) ? 0.5 : 0, 0, spawn.getZ() == Math.floor(spawn.getZ()) ? 0.5 : 0);
    }

    public PasswordPolicy policy() {
        return this.policy;
    }

    public LoginGuard guard() {
        return this.guard;
    }

    public SessionStore sessions() {
        return this.sessions;
    }

    public PasswordHasher hasher() {
        return this.hasher;
    }

    public int waitingCount() {
        return this.waiting.size();
    }

    public int authenticatedCount() {
        return this.authenticated.size();
    }

    // ------------------------------------------------------------------ StraznikApi (thread-safe)

    @Override
    public boolean isAuthenticated(final UUID player) {
        return player != null && this.authenticated.contains(player);
    }

    @Override
    public boolean isRegistered(final String nick) {
        return this.store.find(nick).isPresent();
    }

    @Override
    public Optional<String> registeredName(final String nick) {
        return this.store.find(nick).map(Account::nick);
    }

    @Override
    public Optional<UUID> registeredUuid(final String nick) {
        return this.store.find(nick).map(Account::uuid);
    }

    @Override
    public VerifyResult verifyPassword(final String nick, final String password) {
        if (nick == null || password == null || password.isEmpty() || password.length() > 256) {
            return VerifyResult.WRONG;
        }
        if (this.store.failed()) {
            return VerifyResult.UNAVAILABLE;
        }
        final Optional<Account> account = this.store.find(nick);
        if (account.isEmpty()) {
            return VerifyResult.NOT_REGISTERED;
        }
        return this.hasher.verify(account.get().hash(), password) ? VerifyResult.OK : VerifyResult.WRONG;
    }

    @Override
    public int remainingSeconds(final UUID player) {
        final Long deadline = this.deadlines.get(player);
        return deadline == null ? 0 : (int) Math.max(0, (deadline - System.currentTimeMillis() + 999) / 1000);
    }

    // ------------------------------------------------------------------ connection (async parts)

    /**
     * {@code AsyncPlayerPreLoginEvent}: returns the kick message or {@code null} to allow. Async thread.
     */
    public Component checkPreLogin(final String nick, final UUID id, final InetAddress address) {
        final Settings s = this.settings.get();
        final Messages m = this.messages.get();
        if (this.store.failed()) {
            return m.get("blad-bazy-kick");
        }
        if (nick == null || !s.nickPattern.matcher(nick).matches()) {
            return m.get("nick-nieprawidlowy", Messages.text("wzorzec", s.nickPattern.pattern()));
        }
        final Optional<Account> account = this.store.find(nick);
        if (s.nickCaseProtection && account.isPresent() && !account.get().nick().equals(nick)) {
            this.events.log("ODMOWA_WIELKOSC_LITER", nick, ip(address), "zarejestrowany=" + account.get().nick());
            return m.get("nick-wielkosc-liter", Messages.text("nick", account.get().nick()));
        }
        final String ip = ip(address);
        final long wait = this.guard.blockedFor(nick, ip, System.currentTimeMillis());
        if (wait > 0) {
            return m.get("blokada-kick", Messages.number("minuty", Math.max(1, (wait + 59_999) / 60_000)));
        }
        if (s.blockDoubleJoin) {
            final UUID online = this.onlineByNick.get(Names.key(nick));
            if (online != null && this.authenticated.contains(online)) {
                final String onlineIp = this.connectIps.get(online);
                if (onlineIp == null || !onlineIp.equals(ip)) {
                    this.events.log("ODMOWA_PODWOJNE_WEJSCIE", nick, ip, null);
                    return m.get("juz-na-serwerze");
                }
            }
        }
        this.connectIps.put(id, ip);
        return null;
    }

    static String ip(final InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }

    /** {@code AsyncPlayerSpawnLocationEvent}: where the player appears. Async thread. Returns the new location or null. */
    public Location chooseSpawn(final UUID id, final Location original) {
        final Settings s = this.settings.get();
        final String ip = this.connectIps.get(id);
        if (s.sessionEnabled && this.sessions.valid(id, ip, System.currentTimeMillis())) {
            this.sessionAtSpawn.add(id);
        }
        if (!s.limboHidePosition || this.limboLocation == null || original == null) {
            return null;
        }
        this.spawnRealLocation.put(id, original.clone());
        return this.limboLocation.clone();
    }

    // ------------------------------------------------------------------ join / quit (main thread)

    public void onJoin(final Player player) {
        final UUID id = player.getUniqueId();
        final String ip = this.connectIps.computeIfAbsent(id, k -> ip(player.getAddress() == null ? null : player.getAddress().getAddress()));
        this.onlineByNick.put(Names.key(player.getName()), id);
        // players waiting for /login are invisible to everyone who joins later
        if (this.settings.get().limboHidePlayers) {
            for (final Waiting w : this.waiting.values()) {
                final Player other = Bukkit.getPlayer(w.id);
                if (other != null) {
                    player.hidePlayer(this.plugin, other);
                }
            }
        }
        final boolean sessionCandidate = this.sessionAtSpawn.remove(id);
        final long now = System.currentTimeMillis();
        if (sessionCandidate && this.settings.get().sessionEnabled && this.sessions.consume(id, ip, now)
            && this.store.find(player.getName()).isPresent() && !this.limbo.exists(id)) {
            this.startWaiting(player, ip, now);
            this.events.log("SESJA", player.getName(), ip, null);
            this.complete(player, this.waiting.get(id), AuthMethod.SESSION, this.messages.get().get("sesja-zalogowano"));
            return;
        }
        this.sessions.invalidate(id);
        this.startWaiting(player, ip, now);
    }

    private void startWaiting(final Player player, final String ip, final long now) {
        final Settings s = this.settings.get();
        final UUID id = player.getUniqueId();
        final Waiting w = new Waiting(id, player.getName(), ip, now, now + s.authTimeoutSeconds * 1000L, s.attemptsPerConnection);
        this.waiting.put(id, w);
        this.deadlines.put(id, w.deadline);

        // 1+2. real state secured and inventory hidden — normally already done before the client got its inventory
        // (prepareBeforeJoin); otherwise now
        final Location fromSpawnEvent = this.spawnRealLocation.remove(id);
        Secured sec = this.prepared.remove(id);
        if (sec == null) {
            sec = this.secure(player, fromSpawnEvent);
        }
        w.realLocation = sec.realLocation;
        w.realItems = sec.realItems;
        w.realAllowFlight = sec.realAllowFlight;
        w.realFlying = sec.realFlying;
        w.secured = sec.secured;
        w.inventoryHidden = sec.inventoryHidden;
        if (s.limboHidePosition && this.limboLocation != null && fromSpawnEvent == null) {
            // the async spawn event did not move the player (session refused at join, event not called): move now
            final Location here = player.getLocation();
            if (!here.getWorld().equals(this.limboLocation.getWorld()) || here.distanceSquared(this.limboLocation) > 4) {
                this.ownTeleport(player, this.limboLocation);
            }
        }
        player.setAllowFlight(true);
        player.setFlying(true);
        if (s.limboBlindness) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (s.authTimeoutSeconds + 5) * 20, 0, false, false, false));
            w.blinded = true;
        }
        if (s.limboHidePlayers) {
            for (final Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(player)) {
                    other.hidePlayer(this.plugin, player);
                    player.hidePlayer(this.plugin, other);
                }
            }
        }
        player.updateCommands();

        // 3. prompts
        final Messages m = this.messages.get();
        final boolean registered = this.store.find(player.getName()).isPresent();
        if (s.bossbar) {
            w.bar = BossBar.bossBar(m.get("bossbar", Messages.number("sekundy", s.authTimeoutSeconds)), 1f,
                BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            player.showBossBar(w.bar);
        }
        this.remind(player, w, registered);
        w.lastReminder = now;
        this.showDialog(player, w, null);
        this.events.log("WEJSCIE", player.getName(), ip, registered ? "konto=tak" : "konto=nie");
    }

    /** Real state of a player who must log in, secured on disk (limbo snapshot) before anything is hidden. */
    static final class Secured {
        Location realLocation;
        ItemStack[] realItems;
        boolean realAllowFlight;
        boolean realFlying;
        boolean secured;
        boolean inventoryHidden;
    }

    /** Secured in the configuration phase (before the client received its inventory), taken over at join. */
    private final Map<UUID, Secured> prepared = new ConcurrentHashMap<>();
    private volatile boolean loggedPrepareThread;

    /**
     * {@code EntityAddToWorldEvent} of a player who is not logged in yet: the data is loaded, the client has not
     * received the inventory yet. Skipped for players with a valid session, logged-in players and repeated calls.
     */
    public void prepareBeforeJoin(final Player player) {
        final UUID id = player.getUniqueId();
        if (this.authenticated.contains(id) || this.waiting.containsKey(id) || this.sessionAtSpawn.contains(id)
            || this.prepared.containsKey(id) || !this.connectIps.containsKey(id)) {
            return;
        }
        if (!this.loggedPrepareThread) {
            this.loggedPrepareThread = true;
            this.logger.info("Ekwipunek ukrywany przed wysłaniem do klienta (wątek " + (Bukkit.isPrimaryThread() ? "główny" : "asynchroniczny") + ").");
        }
        final Location real = this.spawnRealLocation.get(id);
        this.prepared.put(id, this.secure(player, real != null ? real : player.getLocation()));
    }

    private Secured secure(final Player player, final Location fromSpawnEvent) {
        final Settings s = this.settings.get();
        final UUID id = player.getUniqueId();
        final Secured w = new Secured();
        // a snapshot left from an earlier connection wins (crash / failed restore), else what we see now
        final Optional<LimboStore.Snapshot> previous = this.limbo.load(id);
        final ItemStack[] current = player.getInventory().getContents();
        w.realAllowFlight = player.getAllowFlight();
        w.realFlying = player.isFlying();
        w.realItems = current;
        if (previous.isPresent()) {
            final LimboStore.Snapshot snap = previous.get();
            w.realLocation = toLocation(snap);
            w.realAllowFlight = snap.allowFlight();
            w.realFlying = snap.flying();
            final ItemStack[] saved = decodeItems(snap.items());
            if (saved != null && (isEmpty(current) || sameItems(current, saved))) {
                w.realItems = saved;
                w.secured = true;
            } else {
                // player data holds other items than the old snapshot: keep the old file aside, secure the current state
                this.logger.warning("Kopia limbo gracza " + player.getName() + " różni się od jego ekwipunku — stara kopia "
                    + "zachowana jako .old, zabezpieczam bieżący stan.");
                this.limbo.archive(id);
            }
        }
        if (w.realLocation == null) {
            w.realLocation = fromSpawnEvent != null ? fromSpawnEvent.clone() : player.getLocation();
        }
        if ((s.limboHideInventory || s.limboHidePosition) && !w.secured) {
            try {
                this.limbo.save(id, snapshot(w));
                w.secured = true;
            } catch (final java.io.IOException | RuntimeException e) {
                this.logger.severe("Nie można zabezpieczyć stanu gracza " + player.getName() + " (" + e.getMessage()
                    + ") — ekwipunek NIE zostanie ukryty.");
            }
        }
        if (s.limboHideInventory && w.secured) {
            player.getInventory().clear();
            player.setItemOnCursor(null);
            w.inventoryHidden = true;
        }
        return w;
    }

    private static boolean isEmpty(final ItemStack[] items) {
        for (final ItemStack i : items) {
            if (i != null && !i.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameItems(final ItemStack[] a, final ItemStack[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            final boolean ea = a[i] == null || a[i].isEmpty();
            final boolean eb = b[i] == null || b[i].isEmpty();
            if (ea != eb || !ea && !a[i].equals(b[i])) {
                return false;
            }
        }
        return true;
    }

    private static LimboStore.Snapshot snapshot(final Secured w) {
        final Location l = w.realLocation;
        return new LimboStore.Snapshot(l.getWorld() == null ? null : l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
            l.getYaw(), l.getPitch(), Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(w.realItems)),
            w.realAllowFlight, w.realFlying, System.currentTimeMillis());
    }

    private static Location toLocation(final LimboStore.Snapshot s) {
        final World w = s.world() == null ? null : Bukkit.getWorld(s.world());
        return w == null ? null : new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
    }

    private static ItemStack[] decodeItems(final String b64) {
        if (b64 == null) {
            return null;
        }
        try {
            return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(b64));
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** Every second: countdown, reminders, timeout kick. */
    public void tick() {
        final long now = System.currentTimeMillis();
        final Settings s = this.settings.get();
        final Messages m = this.messages.get();
        for (final Waiting w : List.copyOf(this.waiting.values())) {
            final Player player = Bukkit.getPlayer(w.id);
            if (player == null) {
                continue;
            }
            // Other join hooks may change gamemode/abilities after PlayerJoinEvent. Keep limbo protected
            // without changing the original flags stored in Waiting and the durable snapshot.
            if (!player.getAllowFlight()) player.setAllowFlight(true);
            if (!player.isFlying()) player.setFlying(true);
            final long left = w.deadline - now;
            if (left <= 0 && !w.busy) {
                this.events.log("LIMIT_CZASU", w.nick, w.ip, null);
                player.kick(m.get("limit-czasu-kick"));
                continue;
            }
            if (w.bar != null) {
                final long sec = (left + 999) / 1000;
                w.bar.name(m.get("bossbar", Messages.number("sekundy", sec)));
                w.bar.progress(Math.clamp((float) left / (s.authTimeoutSeconds * 1000f), 0f, 1f));
                w.bar.color(sec <= 15 ? BossBar.Color.RED : BossBar.Color.YELLOW);
            }
            if (now - w.lastReminder >= s.reminderSeconds * 1000L && !w.busy) {
                w.lastReminder = now;
                this.remind(player, w, this.store.find(w.nick).isPresent());
            }
        }
        this.guard.cleanup(now);
        this.sessions.cleanup(now);
    }

    private void remind(final Player player, final Waiting w, final boolean registered) {
        final Messages m = this.messages.get();
        final Settings s = this.settings.get();
        player.sendMessage(m.get(registered ? "przypomnienie-login" : "przypomnienie-rejestracja",
            Messages.number("min", this.policy.min())));
        if (s.titles) {
            player.showTitle(Title.title(m.get(registered ? "tytul-login" : "tytul-rejestracja"),
                m.get(registered ? "podtytul-login" : "podtytul-rejestracja"),
                Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofSeconds(s.reminderSeconds + 1L),
                    java.time.Duration.ofMillis(500))));
        }
    }

    public void onQuit(final Player player) {
        final UUID id = player.getUniqueId();
        this.onlineByNick.remove(Names.key(player.getName()), id);
        final String ip = this.connectIps.remove(id);
        this.spawnRealLocation.remove(id);
        this.sessionAtSpawn.remove(id);
        this.prepared.remove(id);
        this.deadlines.remove(id);
        this.changing.remove(id);
        final Waiting w = this.waiting.remove(id);
        if (w != null) {
            this.restore(player, w, true);
            this.events.log("WYJSCIE_BEZ_LOGOWANIA", player.getName(), ip, null);
        }
        if (this.authenticated.remove(id) && this.settings.get().sessionEnabled) {
            this.sessions.open(id, ip, System.currentTimeMillis(), this.settings.get().sessionMinutes * 60_000L);
        }
    }

    /** Plugin disable: give everyone waiting their state back (the server saves players after plugins stop). */
    public void shutdown() {
        for (final Waiting w : List.copyOf(this.waiting.values())) {
            final Player player = Bukkit.getPlayer(w.id);
            if (player != null) {
                this.restore(player, w, true);
            }
        }
        this.waiting.clear();
    }

    /**
     * Gives back the real inventory, flight flags and position. Items other plugins put into the hidden inventory are
     * kept (added after the restore; what does not fit drops at the player's feet).
     *
     * @param leaving the player is disconnecting: synchronous teleport + save, the snapshot is deleted only if both worked
     */
    private void restore(final Player player, final Waiting w, final boolean leaving) {
        if (w.bar != null) {
            player.hideBossBar(w.bar);
        }
        if (w.blinded) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
        if (w.inventoryHidden) {
            final List<ItemStack> added = new ArrayList<>();
            for (final ItemStack i : player.getInventory().getContents()) {
                if (i != null && !i.isEmpty()) {
                    added.add(i);
                }
            }
            player.getInventory().setContents(w.realItems);
            if (!added.isEmpty()) {
                player.getInventory().addItem(added.toArray(ItemStack[]::new)).values()
                    .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            }
            w.inventoryHidden = false;
        }
        if (leaving) this.restoreFlight(player, w);
        if (this.settings.get().limboHidePlayers) {
            for (final Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(player)) {
                    if (!this.waiting.containsKey(other.getUniqueId())) {
                        other.showPlayer(this.plugin, player);
                    }
                    if (!leaving && !this.waiting.containsKey(other.getUniqueId())) {
                        player.showPlayer(this.plugin, other);
                    }
                }
            }
        }
        final Location target = w.realLocation != null && w.realLocation.getWorld() != null ? w.realLocation : null;
        if (leaving) {
            // a dead player cannot be teleported (Paper returns false) and respawns elsewhere anyway
            boolean placed = target == null || player.isDead() || player.getLocation().equals(target);
            if (!placed && target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) {
                placed = this.ownTeleport(player, target);
            }
            if (w.secured) {
                try {
                    player.saveData();
                } catch (final RuntimeException e) {
                    placed = false;
                }
                if (placed) {
                    this.limbo.delete(w.id);
                } else {
                    // keep the snapshot: next join restores the position from it (inventory already matches)
                    this.logger.warning("Nie udało się przywrócić pozycji gracza " + player.getName()
                        + " przy wyjściu — pozycja zostanie oddana przy następnym zalogowaniu.");
                }
            }
        }
    }

    /** Teleports that the restriction listener must let through (everything else is blocked before login). */
    private final Set<UUID> ownTeleports = new java.util.HashSet<>();

    private boolean ownTeleport(final Player player, final Location to) {
        this.ownTeleports.add(player.getUniqueId());
        try {
            return player.teleport(to);
        } finally {
            this.ownTeleports.remove(player.getUniqueId());
        }
    }

    public boolean isOwnTeleport(final UUID id) {
        return this.ownTeleports.contains(id);
    }

    // ------------------------------------------------------------------ commands (main thread)

    public boolean isWaiting(final UUID id) {
        return this.waiting.containsKey(id);
    }

    public void login(final Player player, final String password) {
        final Messages m = this.messages.get();
        final Waiting w = this.waiting.get(player.getUniqueId());
        if (w == null) {
            player.sendMessage(m.get("juz-zalogowany"));
            return;
        }
        if (password == null || password.isEmpty()) {
            this.tell(player, w, m.get("haslo-puste"));
            return;
        }
        final Optional<Account> account = this.store.find(player.getName());
        if (account.isEmpty()) {
            this.tell(player, w, m.get("brak-konta"));
            return;
        }
        if (w.busy) {
            player.sendMessage(m.get("sprawdzam"));
            return;
        }
        final long wait = this.guard.blockedFor(player.getName(), w.ip, System.currentTimeMillis());
        if (wait > 0) {
            player.kick(m.get("blokada-kick", Messages.number("minuty", Math.max(1, (wait + 59_999) / 60_000))));
            return;
        }
        w.busy = true;
        final Account acc = account.get();
        final PasswordHasher h = this.hasher;
        this.async(player, w, () -> {
            final boolean ok = h.verify(acc.hash(), password);
            final String rehash = ok && (Account.SCHEME_NLOGIN.equals(acc.scheme()) || h.needsRehash(acc.hash()))
                ? h.hash(password) : null;
            return () -> this.finishLogin(player, w, acc, ok, rehash);
        });
    }

    private void finishLogin(final Player player, final Waiting w, final Account checked, final boolean ok, final String rehash) {
        w.busy = false;
        if (!player.isOnline() || this.waiting.get(player.getUniqueId()) != w) {
            return;
        }
        final Messages m = this.messages.get();
        final long now = System.currentTimeMillis();
        if (ok) {
            this.guard.success(player.getName());
            Account current = this.store.find(player.getName()).orElse(checked);
            if (rehash != null && current.hash().equals(checked.hash())) {
                current = new Account(current.nick(), current.uuid(), rehash, Account.SCHEME_ARGON2ID, current.source(),
                    current.registerIp(), current.lastIp(), current.created(), current.lastLogin(), current.passwordChanged());
                this.events.log("PRZEHASZOWANO", player.getName(), w.ip, "z=" + checked.scheme());
            }
            this.store.put(current.withLogin(w.ip, now));
            this.events.log("LOGIN_OK", player.getName(), w.ip, null);
            this.complete(player, w, AuthMethod.LOGIN, m.get("zalogowano"));
            return;
        }
        final boolean locked = this.guard.failure(player.getName(), w.ip, now);
        w.attemptsLeft--;
        this.events.log("LOGIN_ZLE_HASLO", player.getName(), w.ip, "pozostalo=" + w.attemptsLeft + (locked ? " blokada" : ""));
        if (locked) {
            player.kick(m.get("blokada-kick", Messages.number("minuty", this.settings.get().lockoutMinutes)));
        } else if (w.attemptsLeft <= 0) {
            player.kick(m.get("zle-haslo-kick"));
        } else {
            this.tell(player, w, m.get("zle-haslo", Messages.number("proby", w.attemptsLeft)));
        }
    }

    public void register(final Player player, final String password, final String repeat) {
        final Messages m = this.messages.get();
        final Settings s = this.settings.get();
        final Waiting w = this.waiting.get(player.getUniqueId());
        if (w == null) {
            player.sendMessage(m.get("juz-zalogowany"));
            return;
        }
        if (this.store.find(player.getName()).isPresent()) {
            this.tell(player, w, m.get("konto-istnieje"));
            return;
        }
        if (w.busy) {
            player.sendMessage(m.get("sprawdzam"));
            return;
        }
        if (!password.equals(repeat)) {
            this.tell(player, w, m.get("hasla-rozne"));
            return;
        }
        final String problem = this.policy.check(player.getName(), password);
        if (problem != null) {
            this.tell(player, w, m.get(problem, Messages.number("min", this.policy.min()), Messages.number("max", this.policy.max())));
            return;
        }
        if (s.accountsPerIp > 0 && w.ip != null && !s.accountsPerIpExempt.contains(w.ip)
            && this.store.countByIp(w.ip) >= s.accountsPerIp) {
            this.events.log("REJESTRACJA_LIMIT_IP", player.getName(), w.ip, null);
            this.tell(player, w, m.get("limit-kont-ip", Messages.number("limit", s.accountsPerIp)));
            return;
        }
        w.busy = true;
        final PasswordHasher h = this.hasher;
        this.async(player, w, () -> {
            final String hash = h.hash(password);
            return () -> this.finishRegister(player, w, hash);
        });
    }

    private void finishRegister(final Player player, final Waiting w, final String hash) {
        w.busy = false;
        if (!player.isOnline() || this.waiting.get(player.getUniqueId()) != w) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Account a = new Account(player.getName(), player.getUniqueId(), hash, Account.SCHEME_ARGON2ID,
            Account.SOURCE_REGISTER, w.ip, w.ip, now, now, now);
        if (!this.store.putIfAbsent(a)) {
            this.tell(player, w, this.messages.get().get("konto-istnieje"));
            return;
        }
        this.events.log("REJESTRACJA", player.getName(), w.ip, null);
        this.complete(player, w, AuthMethod.REGISTER, this.messages.get().get("zarejestrowano"));
    }

    /** Runs {@code work} on the hash pool; the returned action runs on the main thread. */
    private void async(final Player player, final Waiting w, final java.util.concurrent.Callable<Runnable> work) {
        this.async(player, w, work, null);
    }

    /**
     * Like {@link #async(Player, Waiting, java.util.concurrent.Callable)}; {@code onFailure} (main thread, may be null)
     * also runs when the work fails or cannot be queued, e.g. to release a "check in progress" mark.
     */
    private void async(final Player player, final Waiting w, final java.util.concurrent.Callable<Runnable> work,
                       final Runnable onFailure) {
        try {
            this.hashPool.execute(() -> {
                Runnable then;
                try {
                    then = work.call();
                } catch (final Exception | Error e) {
                    this.logger.warning("Błąd sprawdzania hasła gracza " + player.getName() + ": " + e.getClass().getSimpleName());
                    then = () -> {
                        if (w != null) {
                            w.busy = false;
                        }
                        if (onFailure != null) {
                            onFailure.run();
                        }
                        this.tell(player, w, this.messages.get().get("blad"));
                    };
                }
                final Runnable action = then;
                try {
                    Bukkit.getScheduler().runTask(this.plugin, action);
                } catch (final RuntimeException e) {
                    // plugin disabled meanwhile
                }
            });
        } catch (final RejectedExecutionException e) {
            if (w != null) {
                w.busy = false;
            }
            if (onFailure != null) {
                onFailure.run();
            }
            this.tell(player, w, this.messages.get().get("blad"));
        }
    }

    /** Successful login/register: state back, then the event. */
    private void restoreFlight(final Player player, final Waiting w) {
        boolean creative = player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
        boolean allow = creative || w.realAllowFlight;
        player.setFlying(allow && (w.realFlying || player.getGameMode() == GameMode.SPECTATOR));
        player.setAllowFlight(allow);
        player.setFallDistance(0);
    }

    private void complete(final Player player, final Waiting w, final AuthMethod method, final Component message) {
        if (w == null || w.busy) return;
        final UUID id = player.getUniqueId();
        w.busy = true;
        player.closeDialog();
        new pl.medivalrpg.straznik.limbo.SafeReturn(this.plugin).resolve(w.realLocation).whenComplete((target, error) -> {
            if (!player.isOnline() || this.waiting.get(id) != w) return;
            if (error != null) {
                this.logger.warning("Nie udało się wybrać bezpiecznej pozycji gracza " + player.getName() + ".");
                player.kick(Component.text("Nie można bezpiecznie przywrócić pozycji. Spróbuj ponownie za chwilę."));
                return;
            }
            // Keep the player locked, hidden and able to fly until Paper confirms the teleport.
            // SafeReturn has already loaded the destination asynchronously. The actual teleport and unlock
            // are atomic on this tick; foreign teleports are never whitelisted while chunk IO is pending.
            // Logged out on the death screen: Paper refuses to teleport dead players, which used to kick on every
            // login. Finish the login without a teleport; the normal respawn decides where the player appears.
            final boolean dead = player.isDead();
            if (!dead && !this.ownTeleport(player, target)) {
                player.kick(Component.text("Nie udało się bezpiecznie przywrócić pozycji. Spróbuj ponownie."));
                return;
            }
            this.waiting.remove(id);
            this.deadlines.remove(id);
            this.restore(player, w, false);
            this.restoreFlight(player, w);
            this.authenticated.add(id);
            if (!dead && (w.realLocation == null || !target.equals(w.realLocation))) {
                boolean below = w.realLocation != null && target.getWorld().equals(w.realLocation.getWorld())
                    && target.getX() == w.realLocation.getX() && target.getZ() == w.realLocation.getZ();
                player.sendMessage(Component.text(below
                    ? "Miejsce wylogowania nie miało bezpiecznego gruntu. Przeniesiono Cię na bezpieczne podłoże poniżej."
                    : "W pobliżu miejsca wylogowania nie ma bezpiecznego gruntu. Przeniesiono Cię na spawn serwera."));
            }
            player.resetTitle();
            player.updateCommands();
            player.sendMessage(message);
            if (this.settings.get().titles) {
                player.showTitle(Title.title(this.messages.get().get("tytul-zalogowano"), Component.empty(),
                    Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(500))));
            }
            if (w.secured) {
                player.saveData();
                this.limbo.delete(id);
            }
            Bukkit.getPluginManager().callEvent(new PlayerAuthenticatedEvent(player, method));
        });
    }

    public void logout(final Player player) {
        final UUID id = player.getUniqueId();
        if (!this.authenticated.contains(id)) {
            player.sendMessage(this.messages.get().get("nie-zalogowany"));
            return;
        }
        this.events.log("WYLOGOWANIE", player.getName(), this.connectIps.get(id), null);
        this.authenticated.remove(id);
        this.sessions.invalidate(id);
        player.kick(this.messages.get().get("wylogowano-kick"));
    }

    public void changePassword(final Player player, final String oldPassword, final String newPassword) {
        final Messages m = this.messages.get();
        final UUID id = player.getUniqueId();
        if (!this.authenticated.contains(id)) {
            player.sendMessage(m.get("nie-zalogowany"));
            return;
        }
        final Optional<Account> account = this.store.find(player.getName());
        if (account.isEmpty()) {
            player.sendMessage(m.get("brak-konta"));
            return;
        }
        if (this.changing.contains(id)) {
            player.sendMessage(m.get("sprawdzam"));
            return;
        }
        final String ip = this.connectIps.get(id);
        final long wait = this.guard.blockedFor(player.getName(), ip, System.currentTimeMillis());
        if (wait > 0) {
            player.sendMessage(m.get("blokada", Messages.number("minuty", Math.max(1, (wait + 59_999) / 60_000))));
            return;
        }
        if (oldPassword.equals(newPassword)) {
            player.sendMessage(m.get("haslo-takie-samo"));
            return;
        }
        final String problem = this.policy.check(player.getName(), newPassword);
        if (problem != null) {
            player.sendMessage(m.get(problem, Messages.number("min", this.policy.min()), Messages.number("max", this.policy.max())));
            return;
        }
        this.changing.add(id);
        final Account acc = account.get();
        final PasswordHasher h = this.hasher;
        this.async(player, null, () -> {
            final boolean ok = h.verify(acc.hash(), oldPassword);
            final String hash = ok ? h.hash(newPassword) : null;
            return () -> {
                this.changing.remove(id);
                if (!player.isOnline()) {
                    return;
                }
                if (!ok) {
                    this.guard.failure(player.getName(), ip, System.currentTimeMillis());
                    this.events.log("ZMIANA_HASLA_ZLE_STARE", player.getName(), ip, null);
                    player.sendMessage(m.get("zmiana-zle-stare"));
                    return;
                }
                final Account current = this.store.find(player.getName()).orElse(acc);
                this.store.put(current.withHash(hash, Account.SCHEME_ARGON2ID, System.currentTimeMillis()));
                this.sessions.invalidate(id);
                this.events.log("ZMIANA_HASLA", player.getName(), ip, null);
                player.sendMessage(m.get("zmieniono-haslo"));
                Bukkit.getPluginManager().callEvent(new PasswordChangedEvent(current.uuid(), current.nick(),
                    PasswordChangedEvent.Reason.CHANGED_BY_PLAYER));
            };
        }, () -> this.changing.remove(id));
    }

    // ------------------------------------------------------------------ admin (main thread)

    /** Sets a new password (async hash). {@code done} receives the message for the admin. */
    public void adminSetPassword(final String nick, final String password, final String adminName,
                                 final java.util.function.Consumer<Component> done) {
        final Messages m = this.messages.get();
        final Optional<Account> account = this.store.find(nick);
        if (account.isEmpty()) {
            done.accept(m.get("admin-brak-konta", Messages.text("nick", nick)));
            return;
        }
        final String problem = this.policy.check(account.get().nick(), password);
        if (problem != null) {
            done.accept(m.get(problem, Messages.number("min", this.policy.min()), Messages.number("max", this.policy.max())));
            return;
        }
        final PasswordHasher h = this.hasher;
        try {
            this.hashPool.execute(() -> {
                final String hash = h.hash(password);
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    final Account current = this.store.find(nick).orElse(null);
                    if (current == null) {
                        done.accept(m.get("admin-brak-konta", Messages.text("nick", nick)));
                        return;
                    }
                    this.store.put(current.withHash(hash, Account.SCHEME_ARGON2ID, System.currentTimeMillis()));
                    this.sessions.invalidate(current.uuid());
                    this.events.log("ADMIN_ZMIANA_HASLA", current.nick(), null, "przez=" + adminName);
                    Bukkit.getPluginManager().callEvent(new PasswordChangedEvent(current.uuid(), current.nick(),
                        PasswordChangedEvent.Reason.CHANGED_BY_ADMIN));
                    done.accept(m.get("admin-haslo-zmienione", Messages.text("nick", current.nick())));
                });
            });
        } catch (final RejectedExecutionException e) {
            done.accept(m.get("blad"));
        }
    }

    public boolean adminUnregister(final String nick, final String adminName) {
        final Optional<Account> account = this.store.find(nick);
        if (account.isEmpty() || !this.store.remove(nick)) {
            return false;
        }
        final Account a = account.get();
        this.sessions.invalidate(a.uuid());
        this.events.log("ADMIN_WYREJESTROWANIE", a.nick(), null, "przez=" + adminName);
        Bukkit.getPluginManager().callEvent(new PasswordChangedEvent(a.uuid(), a.nick(), PasswordChangedEvent.Reason.UNREGISTERED));
        final Player online = Bukkit.getPlayerExact(a.nick());
        if (online != null) {
            this.authenticated.remove(online.getUniqueId());
            online.kick(this.messages.get().get("wyrejestrowano-kick"));
        }
        return true;
    }

    /** Players already online when the plugin starts (reload): treated as logged in. */
    public void adoptOnline(final Player player) {
        this.onlineByNick.put(Names.key(player.getName()), player.getUniqueId());
        this.connectIps.put(player.getUniqueId(), ip(player.getAddress() == null ? null : player.getAddress().getAddress()));
        this.authenticated.add(player.getUniqueId());
    }

    public String ipOf(final UUID id) {
        return this.connectIps.get(id);
    }
}
