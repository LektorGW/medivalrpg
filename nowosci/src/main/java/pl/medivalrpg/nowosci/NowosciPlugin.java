// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import pl.medivalrpg.nowosci.command.NowosciCommand;
import pl.medivalrpg.nowosci.config.Messages;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.discord.DiscordPublisher;
import pl.medivalrpg.nowosci.login.AuthGate;
import pl.medivalrpg.nowosci.login.NLoginHook;
import pl.medivalrpg.nowosci.login.StraznikHook;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.ChangelogCodec;
import pl.medivalrpg.nowosci.render.BookRenderer;
import pl.medivalrpg.nowosci.render.ChatRenderer;
import pl.medivalrpg.nowosci.render.EntryFormat;
import pl.medivalrpg.nowosci.render.TitleSequence;
import pl.medivalrpg.nowosci.service.NewsService;
import pl.medivalrpg.nowosci.storage.ChangelogStore;
import pl.medivalrpg.nowosci.storage.IoExecutor;
import pl.medivalrpg.nowosci.storage.SeenStore;

/**
 * Nowosci — changelog for MEDIVALRPG players. Shown after a successful login/registration in Straznik
 * ({@link StraznikHook}; fallback while it is still installed: nLogin
 * {@link NLoginHook}) or, without a login plugin, a few seconds after joining. {@code /nowosci} opens a book.
 */
public final class NowosciPlugin extends JavaPlugin {

    private static final String CHANGELOG_FILE = "changelog.yml";

    /** Everything rebuilt on reload; swapped as a whole so readers on other threads never see a mix. */
    private record View(NowosciConfig config, Messages messages, EntryFormat format, ChatRenderer chat, BookRenderer book,
                        TitleSequence titles) {
    }

    private volatile View view;
    private volatile Changelog changelog = Changelog.empty();
    /** changelog.yml could not be parsed: keep the old entries and refuse to overwrite the file. */
    private volatile boolean changelogBroken;

    private IoExecutor io;
    private ChangelogStore changelogStore;
    private NewsService news;
    private DiscordPublisher discord;
    /** Newest id read from changelog.yml (entries added by commands since then are not auto-posted). */
    private volatile int diskMaxId;
    /** When the heartbeat next looks at changelog.yml (System.currentTimeMillis). */
    private long nextWatch;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.io = new IoExecutor(this.getLogger());
        final Path data = this.getDataFolder().toPath();
        this.changelogStore = new ChangelogStore(data.resolve(CHANGELOG_FILE), this.io);
        final SeenStore seen = new SeenStore(data.resolve("seen.json"), this.io, this.getLogger());
        seen.load();
        this.reloadAll();

        this.discord = new DiscordPublisher(data.resolve("discord.json"), this.io, this.getLogger());
        this.discord.publishNew(this.changelog, this.diskMaxId, this.cfg());

        this.news = new NewsService(this, seen);
        this.getServer().getPluginManager().registerEvents(this.news, this);
        this.hookLogin();

        final NowosciCommand command = new NowosciCommand(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(command.build(), "Nowości na serwerze MEDIVALRPG", List.of("zmiany", "changelog")));
        this.getServer().getScheduler().runTaskTimer(this, this::heartbeat, 20L, 20L);

        this.getLogger().info("Nowosci by Maciej Kaczmarczyk — lektoro.pl");
    }

    /** Every second: due chat reminders and, every {@code auto-reload.check-seconds}, a look at changelog.yml. */
    private void heartbeat() {
        this.news.remindDue();
        this.discord.publishNew(this.changelog, this.diskMaxId, this.cfg());
        final int watch = this.cfg().watchTicks;
        final long now = System.currentTimeMillis();
        if (watch > 0 && now >= this.nextWatch) {
            this.nextWatch = now + watch * 50L;
            if (this.changelogStore.changedOutside()) {
                final int before = this.changelog.maxId();
                final List<String> warnings = this.loadChangelog();
                warnings.forEach(this.getLogger()::warning);
                if (!this.changelogBroken) {
                    this.getLogger().info(CHANGELOG_FILE + " zmieniony na dysku — wczytano " + this.changelog.size() + " wpisów.");
                    this.afterChangelogLoaded(before);
                }
            }
        }
    }

    /** Players online see entries that were not there before and get their reminders updated. */
    private void afterChangelogLoaded(final int previousMaxId) {
        if (this.news == null) {
            return;
        }
        this.news.refreshAll();
        if (this.changelog.maxId() > previousMaxId) {
            this.news.onChangelogGrew(previousMaxId);
        }
    }

    @Override
    public void onDisable() {
        if (this.news != null) {
            this.news.stopAll();
        }
        if (this.io != null) {
            this.io.shutdown();
        }
    }

    private void hookLogin() {
        try {
            final AuthGate straznik = StraznikHook.tryRegister(this, this.news::onAuthenticated);
            if (straznik != null) {
                this.news.useGate(straznik);
                this.getLogger().info("Straznik wykryty — nowości pokażą się po udanym /login albo /register (PlayerAuthenticatedEvent).");
                return;
            }
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            this.getLogger().warning("Nie udało się podpiąć pod Straznika (" + e + ") — próbuję nLogin.");
        }
        if (this.getServer().getPluginManager().isPluginEnabled("nLogin")) {
            try {
                this.news.useGate(NLoginHook.register(this, this.news::onAuthenticated));
                this.getLogger().info("nLogin wykryty — nowości pokażą się po udanym /login albo /register (AuthenticateEvent).");
                return;
            } catch (final LinkageError | RuntimeException e) {
                this.getLogger().warning("Nie udało się podpiąć pod nLogin (" + e + ") — nowości pokażą się po wejściu na serwer.");
            }
        } else {
            this.getLogger().info("Brak nLogina — nowości pokażą się kilka sekund po wejściu na serwer.");
        }
        this.news.useJoinFallback(true);
    }

    /** (Re)loads config.yml and changelog.yml; returns warnings (also logged). */
    public List<String> reloadAll() {
        this.reloadConfig();
        final List<String> warnings = new ArrayList<>();
        final NowosciConfig cfg = new NowosciConfig(this.getConfig());
        warnings.addAll(cfg.warnings.stream().map(w -> "config.yml: " + w).toList());
        final Messages messages = new Messages(this.getConfig(), this.getLogger()::warning);
        final EntryFormat format = new EntryFormat(messages, cfg);
        this.view = new View(cfg, messages, format, new ChatRenderer(format), new BookRenderer(format), new TitleSequence(format));

        final Path file = this.getDataFolder().toPath().resolve(CHANGELOG_FILE);
        if (!Files.exists(file)) {
            this.saveResource(CHANGELOG_FILE, false);
        }
        final int before = this.changelog.maxId();
        warnings.addAll(this.loadChangelog());
        for (final String w : warnings) {
            this.getLogger().warning(w);
        }
        if (this.changelogBroken) {
            this.getLogger().severe(CHANGELOG_FILE + " ma błąd — używam poprzednich wpisów (" + this.changelog.size()
                + "), a komendy edycji są zablokowane do czasu poprawki i /nowosci reload.");
        }
        this.afterChangelogLoaded(before);
        return warnings;
    }

    /** Reads changelog.yml; on a parse error the previous entries stay and {@link #changelogBroken} is set. */
    private List<String> loadChangelog() {
        final List<String> warnings = new ArrayList<>();
        try {
            final ChangelogCodec.Result result = this.changelogStore.load();
            warnings.addAll(result.warnings().stream().map(w -> CHANGELOG_FILE + ": " + w).toList());
            if (result.ok()) {
                this.changelog = result.changelog();
                this.diskMaxId = this.changelog.maxId();
                this.changelogBroken = false;
            } else {
                this.changelogBroken = true;
            }
        } catch (final IOException e) {
            warnings.add(CHANGELOG_FILE + ": nie można odczytać (" + e.getMessage() + ")");
            this.changelogBroken = true;
        }
        return warnings;
    }

    /**
     * Replaces the changelog after an admin command and saves it in the background.
     *
     * @return {@code false} when the file on disk is broken and must not be overwritten
     */
    public boolean updateChangelog(final CommandSender sender, final Changelog updated) {
        if (this.changelogBroken) {
            this.messages().send(sender, "admin-changelog-broken");
            return false;
        }
        this.changelog = updated;
        this.changelogStore.save(updated, () -> {
            if (this.isEnabled()) {
                this.getServer().getScheduler().runTask(this, () -> this.messages().send(sender, "admin-save-failed"));
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ accessors

    public NowosciConfig cfg() {
        return this.view.config();
    }

    public Messages messages() {
        return this.view.messages();
    }

    public EntryFormat format() {
        return this.view.format();
    }

    public ChatRenderer chat() {
        return this.view.chat();
    }

    public BookRenderer book() {
        return this.view.book();
    }

    public TitleSequence titles() {
        return this.view.titles();
    }

    public Changelog changelog() {
        return this.changelog;
    }

    public NewsService news() {
        return this.news;
    }

    public DiscordPublisher discord() {
        return this.discord;
    }
}
