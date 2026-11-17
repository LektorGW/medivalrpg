// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.nowosci.NowosciPlugin;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.login.AuthGate;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.Entry;
import pl.medivalrpg.nowosci.model.SeenState;
import pl.medivalrpg.nowosci.model.ShowMode;
import pl.medivalrpg.nowosci.model.UnreadSelector;
import pl.medivalrpg.nowosci.model.UnreadSelector.BookDecision;
import pl.medivalrpg.nowosci.model.UnreadSelector.Selection;
import pl.medivalrpg.nowosci.render.TitleSequence;
import pl.medivalrpg.nowosci.render.TitleSequence.Slide;
import pl.medivalrpg.nowosci.storage.SeenStore;

/**
 * Showing news to players after login, according to {@code show.mode}:
 * <ol>
 *   <li>{@code chat} — the compact block is sent right away (a lasting trace in the chat history),</li>
 *   <li>{@code title} — title cards in the middle of the screen, one after another, with sounds,</li>
 *   <li>{@code book} — afterwards (or right away without {@code title}) the book opens by itself.</li>
 * </ol>
 * Everything runs on the main thread except {@link #scheduleLoginShow}, which may be called from a login plugin's (nLogin: async)
 * event and only schedules a main-thread task. A running title sequence stops when the player quits.
 */
public final class NewsService implements Listener {

    private final NowosciPlugin plugin;
    private final SeenStore seen;
    /** Players who already got the login news in this session (login plugins may fire more than once). */
    private final Set<UUID> handled = ConcurrentHashMap.newKeySet();
    /** Players whose current session is their first one on the server (captured at join). */
    private final Set<UUID> firstJoin = ConcurrentHashMap.newKeySet();
    /** Next scheduled step of a running title sequence, per player. */
    private final Map<UUID, BukkitTask> sequences = new ConcurrentHashMap<>();
    /** Reminder boss bar of players who still have unread entries. */
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    /** When the next chat reminder is due (System.currentTimeMillis), per greeted player. */
    private final Map<UUID, Long> nextReminder = new ConcurrentHashMap<>();
    private volatile AuthGate gate = AuthGate.NONE;
    private volatile boolean joinFallback;

    public NewsService(final NowosciPlugin plugin, final SeenStore seen) {
        this.plugin = plugin;
        this.seen = seen;
    }

    public void useGate(final AuthGate gate) {
        this.gate = gate;
    }

    /** Without a login plugin: show the news a few seconds after {@link PlayerJoinEvent}. */
    public void useJoinFallback(final boolean enabled) {
        this.joinFallback = enabled;
    }

    // ------------------------------------------------------------------ login

    /** Thread-safe: schedules {@link #showOnLogin} on the main thread after {@code delayTicks}. */
    public void scheduleLoginShow(final UUID player, final int delayTicks) {
        if (!this.plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.showOnLogin(player), Math.max(1, delayTicks));
        } catch (final IllegalStateException | IllegalArgumentException e) { // plugin disabling meanwhile
            // nothing to do
        }
    }

    /** Called from Straznik's PlayerAuthenticatedEvent (main thread) or nLogin's AuthenticateEvent (async). */
    public void onAuthenticated(final Player player) {
        this.scheduleLoginShow(player.getUniqueId(), this.plugin.cfg().delayAfterLoginTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        if (!event.getPlayer().hasPlayedBefore()) {
            this.firstJoin.add(event.getPlayer().getUniqueId());
        }
        if (this.joinFallback) {
            this.scheduleLoginShow(event.getPlayer().getUniqueId(), this.plugin.cfg().delayAfterJoinTicks);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        this.handled.remove(uuid);
        this.firstJoin.remove(uuid);
        this.nextReminder.remove(uuid);
        this.bars.remove(uuid);
        this.stopSequence(uuid);
    }

    void showOnLogin(final UUID uuid) {
        final Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline() || !this.handled.add(uuid)) {
            return;
        }
        final NowosciConfig cfg = this.plugin.cfg();
        final Changelog changelog = this.plugin.changelog();
        final SeenState stored = this.seen.get(uuid);
        final int lastSeenBefore = stored == null ? 0 : stored.lastSeenId();
        final Selection selection = UnreadSelector.select(changelog, stored, this.firstJoin.contains(uuid),
            cfg.newPlayerMode, cfg.maxEntries, cfg.autoMarkAfterShows);
        final boolean entries = selection.kind() == UnreadSelector.Kind.ENTRIES;

        List<Slide> slides = List.of();
        switch (selection.kind()) {
            case WELCOME -> {
                if (cfg.has(ShowMode.CHAT)) {
                    player.sendMessage(this.plugin.chat().welcome());
                }
                if (cfg.has(ShowMode.TITLE)) {
                    slides = this.plugin.titles().welcome();
                }
            }
            case ENTRIES -> {
                if (cfg.has(ShowMode.CHAT)) {
                    send(player, this.plugin.chat().loginBlock(selection));
                    if (!cfg.has(ShowMode.TITLE)) {
                        this.chatSound(player);
                    }
                }
                if (cfg.has(ShowMode.TITLE)) {
                    slides = this.plugin.titles().forEntries(selection.shown());
                }
            }
            case NOTHING -> {
            }
        }

        SeenState state = selection.state() != null ? selection.state() : stored;
        boolean openBook = false;
        if (cfg.has(ShowMode.BOOK) && selection.kind() != UnreadSelector.Kind.WELCOME) {
            final BookDecision book = UnreadSelector.decideBook(state, changelog.maxId(), entries,
                cfg.bookOnlyIfUnread, cfg.bookMaxAutoOpens);
            openBook = book.open();
            state = book.state();
        }
        if (state != null) {
            this.seen.put(uuid, state);
        }

        // The book that opens by itself may be closed unread, so by default it does not count as reading.
        final Consumer<Player> after = openBook
            ? p -> this.openBook(p, lastSeenBefore, cfg.autoBookMarksRead)
            : null;
        if (!slides.isEmpty()) {
            this.playSequence(player, slides, after);
        } else if (after != null) {
            after.accept(player);
        }
        this.scheduleReminder(uuid);
        this.refreshReminder(player);
    }

    // ------------------------------------------------------------------ reminders until read

    /** Number of entries {@code uuid} has not read yet. */
    private int unread(final UUID uuid) {
        final SeenState state = this.seen.get(uuid);
        return this.plugin.changelog().newerThan(state == null ? 0 : state.lastSeenId()).size();
    }

    private void scheduleReminder(final UUID uuid) {
        final int ticks = this.plugin.cfg().reminderChatTicks;
        if (ticks > 0) {
            this.nextReminder.put(uuid, System.currentTimeMillis() + ticks * 50L);
        }
    }

    /** Shows, updates or hides the boss bar of {@code player} according to their unread entries. */
    public void refreshReminder(final Player player) {
        final UUID uuid = player.getUniqueId();
        final NowosciConfig cfg = this.plugin.cfg();
        final int unread = this.handled.contains(uuid) ? this.unread(uuid) : 0;
        final BossBar current = this.bars.get(uuid);
        if (unread == 0 || !cfg.reminderBossBar) {
            if (current != null) {
                player.hideBossBar(current);
                this.bars.remove(uuid);
            }
            return;
        }
        final Component name = this.plugin.chat().reminderBar(unread);
        if (current == null) {
            final BossBar bar = BossBar.bossBar(name, 1.0f, cfg.bossBarColor, cfg.bossBarOverlay);
            this.bars.put(uuid, bar);
            player.showBossBar(bar);
        } else {
            current.name(name).color(cfg.bossBarColor).overlay(cfg.bossBarOverlay);
        }
    }

    /** After a reload: config or entries may have changed for everyone online. */
    public void refreshAll() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            this.refreshReminder(p);
        }
    }

    /** Repeating task: chat reminder to greeted players who still have unread entries. */
    public void remindDue() {
        final int ticks = this.plugin.cfg().reminderChatTicks;
        if (ticks <= 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            final UUID uuid = p.getUniqueId();
            final Long due = this.nextReminder.get(uuid);
            if (due == null || due > now || !this.handled.contains(uuid)) {
                continue;
            }
            this.nextReminder.put(uuid, now + ticks * 50L);
            final int unread = this.unread(uuid);
            if (unread > 0) {
                p.sendMessage(this.plugin.chat().reminder(unread));
                this.chatSound(p);
            }
        }
    }

    /**
     * The changelog got entries above {@code previousMaxId} (file replaced on disk or {@code /nowosci reload}):
     * players already in the game see them right away — chat, title cards and the boss bar.
     */
    public void onChangelogGrew(final int previousMaxId) {
        final NowosciConfig cfg = this.plugin.cfg();
        final Changelog changelog = this.plugin.changelog();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            final UUID uuid = p.getUniqueId();
            if (!this.handled.contains(uuid) || !this.gate.isAuthenticated(p)) {
                continue;
            }
            final SeenState state = this.seen.get(uuid);
            final List<Entry> fresh = changelog.newerThan(Math.max(previousMaxId, state == null ? 0 : state.lastSeenId()));
            if (fresh.isEmpty()) {
                continue;
            }
            if (cfg.announceNew) {
                for (final Entry e : fresh.reversed()) { // oldest first, so the newest ends up at the bottom of the chat
                    send(p, this.plugin.chat().announcement(e));
                }
                if (cfg.has(ShowMode.TITLE)) {
                    this.playSequence(p, this.plugin.titles().forEntries(fresh.subList(0, Math.min(cfg.maxEntries, fresh.size()))), null);
                } else {
                    this.chatSound(p);
                }
                this.scheduleReminder(uuid);
            }
            this.refreshReminder(p);
        }
    }

    // ------------------------------------------------------------------ title sequence

    /** Plays {@code slides} one after another; {@code after} (may be null) runs when the last one has faded out. */
    public void playSequence(final Player player, final List<Slide> slides, final Consumer<Player> after) {
        final UUID uuid = player.getUniqueId();
        this.stopSequence(uuid);
        this.step(uuid, slides, 0, after);
    }

    private void step(final UUID uuid, final List<Slide> slides, final int index, final Consumer<Player> after) {
        final Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            this.sequences.remove(uuid);
            return;
        }
        final TitleSequence titles = this.plugin.titles();
        if (index >= slides.size()) {
            this.sequences.remove(uuid);
            if (after != null) {
                after.accept(player);
            }
            return;
        }
        final Slide slide = slides.get(index);
        player.showTitle(Title.title(slide.title(), slide.subtitle(), titles.times()));
        if (slide.sound() != null) {
            player.playSound(slide.sound(), Sound.Emitter.self());
        }
        final boolean last = index == slides.size() - 1;
        final int delay = last
            ? titles.slideTicks() - this.plugin.cfg().titleGap + this.plugin.cfg().bookDelayTicks
            : titles.slideTicks();
        if (last && after == null) {
            this.sequences.remove(uuid);
            return;
        }
        try {
            this.sequences.put(uuid, Bukkit.getScheduler().runTaskLater(this.plugin,
                () -> this.step(uuid, slides, index + 1, after), Math.max(1, delay)));
        } catch (final IllegalStateException e) { // plugin disabling
            this.sequences.remove(uuid);
        }
    }

    private void stopSequence(final UUID uuid) {
        final BukkitTask task = this.sequences.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /** Plugin disable: cancel every running sequence. */
    public void stopAll() {
        this.sequences.values().forEach(BukkitTask::cancel);
        this.sequences.clear();
        this.bars.forEach((uuid, bar) -> {
            final Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.hideBossBar(bar);
            }
        });
        this.bars.clear();
    }

    private void chatSound(final Player player) {
        final NowosciConfig cfg = this.plugin.cfg();
        if (cfg.soundEnabled) {
            player.playSound(Sound.sound(cfg.soundKey, Sound.Source.MASTER, cfg.soundVolume, cfg.soundPitch), Sound.Emitter.self());
        }
    }

    // ------------------------------------------------------------------ reading

    /** Everything currently in the changelog counts as read by {@code player}. */
    public void markRead(final Player player) {
        final SeenState current = this.seen.get(player.getUniqueId());
        final int max = this.plugin.changelog().maxId();
        this.seen.put(player.getUniqueId(), current == null ? SeenState.readUpTo(max) : current.read(max));
        this.refreshReminder(player);
    }

    /** {@code /nowosci}: the book with entries the player has not seen yet marked as new. */
    public void openBook(final Player player) {
        final SeenState current = this.seen.get(player.getUniqueId());
        this.openBook(player, current == null ? 0 : current.lastSeenId(), true);
    }

    private void openBook(final Player player, final int lastSeenId, final boolean countsAsRead) {
        player.openBook(this.plugin.book().book(this.plugin.changelog(), lastSeenId));
        if (countsAsRead) {
            this.openedNews(player);
        }
    }

    /** {@code /nowosci} or {@code /nowosci czat} was opened. */
    public void openedNews(final Player player) {
        if (this.plugin.cfg().markReadOnOpen) {
            this.markRead(player);
        }
    }

    /** Sends the newest entry to every logged-in player (chat + title cards); returns how many got it. */
    public int announce(final Entry entry) {
        final List<Component> lines = this.plugin.chat().announcement(entry);
        final boolean titles = this.plugin.cfg().has(ShowMode.TITLE);
        int count = 0;
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (!this.gate.isAuthenticated(p)) {
                continue;
            }
            send(p, lines);
            if (titles) {
                this.playSequence(p, this.plugin.titles().forEntries(List.of(entry)), null);
            } else {
                this.chatSound(p);
            }
            count++;
        }
        return count;
    }

    private static void send(final Player player, final List<Component> lines) {
        for (final Component line : lines) {
            player.sendMessage(line);
        }
    }
}
