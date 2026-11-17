// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.config;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.Index;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import pl.medivalrpg.nowosci.model.Category;
import pl.medivalrpg.nowosci.model.NewPlayerMode;
import pl.medivalrpg.nowosci.model.ShowMode;

/** Settings from config.yml (everything except {@code messages}). Immutable; invalid values fall back to defaults. */
public final class NowosciConfig {

    public final int delayAfterLoginTicks;
    public final int delayAfterJoinTicks;
    public final int maxEntries;
    public final int pointsPerEntry;
    public final int pointMaxChars;
    public final int autoMarkAfterShows;
    public final boolean markReadOnOpen;
    public final NewPlayerMode newPlayerMode;

    public final Set<ShowMode> modes;
    public final boolean bookOnlyIfUnread;
    public final int bookMaxAutoOpens;
    public final int bookDelayTicks;
    /** The book that opened by itself marks the entries as read (otherwise only a click or /nowosci does). */
    public final boolean autoBookMarksRead;

    public final boolean reminderBossBar;
    public final BossBar.Color bossBarColor;
    public final BossBar.Overlay bossBarOverlay;
    /** Ticks between chat reminders while something is unread (0 = off). */
    public final int reminderChatTicks;
    /** Ticks between checks whether changelog.yml was replaced on disk (0 = off). */
    public final int watchTicks;
    /** New entries found on disk (or after /nowosci reload) are shown at once to players online. */
    public final boolean announceNew;

    /** Discord channel webhook; {@code ""} = Discord off. */
    public final String discordWebhook;
    public final String discordUsername;
    public final String discordAvatarUrl;
    public final String discordMention;

    public final int titleFadeIn;
    public final int titleStay;
    public final int titleFadeOut;
    public final int titleGap;
    public final int titleMaxChars;
    public final int subtitleMaxChars;
    /** {@code null} = no sound. */
    public final SoundSpec introSound;
    public final SoundSpec entrySound;

    public final boolean soundEnabled;
    public final Key soundKey;
    public final float soundVolume;
    public final float soundPitch;

    public final int bookLinesPerPage;
    public final int bookCharsPerLine;
    public final int bookMaxPages;
    public final int chatEntriesPerPage;

    public final DateTimeFormatter dateFormat;
    public final Map<String, Category> categories;
    public final TextColor chatCmd;
    public final TextColor chatHl;
    public final TextColor bookCmd;
    public final TextColor bookHl;

    public final List<String> pluralNewEntries;
    public final List<String> pluralEntries;
    public final List<String> pluralPlayers;

    public final List<String> warnings = new ArrayList<>();

    public NowosciConfig(final FileConfiguration c) {
        this.delayAfterLoginTicks = seconds(c.getDouble("show.delay-after-login-seconds", 2));
        this.delayAfterJoinTicks = seconds(c.getDouble("show.delay-after-join-seconds", 3));
        this.maxEntries = clamp(c.getInt("show.max-entries", 3), 1, 10);
        this.pointsPerEntry = clamp(c.getInt("show.points-per-entry", 1), 0, 10);
        this.pointMaxChars = clamp(c.getInt("show.point-max-chars", 55), 10, 500);
        this.autoMarkAfterShows = Math.max(0, c.getInt("show.auto-mark-read-after-shows", 0));
        this.markReadOnOpen = c.getBoolean("show.mark-read-on-open", true);
        final String mode = c.getString("new-player", "welcome");
        this.newPlayerMode = NewPlayerMode.parse(mode, NewPlayerMode.WELCOME);
        if (!this.newPlayerMode.name().equalsIgnoreCase(mode == null ? "" : mode.trim())) {
            this.warnings.add("new-player: nieznana wartość '" + mode + "' — użyto 'welcome'");
        }

        final List<String> unknownModes = new ArrayList<>();
        final List<String> modeNames = c.isString("show.mode")
            ? List.of(c.getString("show.mode", "").split(","))
            : c.getStringList("show.mode");
        this.modes = Collections.unmodifiableSet(ShowMode.parse(modeNames, unknownModes));
        if (!unknownModes.isEmpty()) {
            this.warnings.add("show.mode: nieznane tryby " + unknownModes + " (dostępne: title, book, chat)");
        }
        this.bookOnlyIfUnread = c.getBoolean("show.book-only-if-unread", true);
        this.bookMaxAutoOpens = Math.max(0, c.getInt("show.book-max-auto-opens", 1));
        this.bookDelayTicks = Math.max(0, c.getInt("show.book-delay-ticks", 5));
        this.autoBookMarksRead = c.getBoolean("show.auto-book-marks-read", false);

        this.reminderBossBar = c.getBoolean("reminder.bossbar.enabled", true);
        this.bossBarColor = this.bossBarEnum(BossBar.Color.NAMES, c.getString("reminder.bossbar.color", "yellow"),
            BossBar.Color.YELLOW, "reminder.bossbar.color");
        this.bossBarOverlay = this.bossBarEnum(BossBar.Overlay.NAMES, c.getString("reminder.bossbar.overlay", "progress"),
            BossBar.Overlay.PROGRESS, "reminder.bossbar.overlay");
        this.reminderChatTicks = minutes(c.getDouble("reminder.chat-every-minutes", 10));
        this.watchTicks = seconds(c.getDouble("auto-reload.check-seconds", 15));
        this.announceNew = c.getBoolean("auto-reload.announce-new", true);

        final String webhook = c.getString("discord.webhook-url", "").trim();
        if (!webhook.isEmpty() && !webhook.startsWith("https://")) {
            this.warnings.add("discord.webhook-url: adres musi zaczynać się od https:// — Discord wyłączony");
            this.discordWebhook = "";
        } else {
            this.discordWebhook = webhook;
        }
        this.discordUsername = c.getString("discord.username", "MEDIVALRPG").trim();
        this.discordAvatarUrl = c.getString("discord.avatar-url", "").trim();
        this.discordMention = c.getString("discord.mention", "").trim();

        this.titleFadeIn = clamp(c.getInt("title-sequence.fade-in-ticks", 10), 0, 200);
        this.titleStay = clamp(c.getInt("title-sequence.stay-ticks", 50), 1, 400);
        this.titleFadeOut = clamp(c.getInt("title-sequence.fade-out-ticks", 10), 0, 200);
        this.titleGap = clamp(c.getInt("title-sequence.gap-ticks", 0), 0, 200);
        this.titleMaxChars = clamp(c.getInt("title-sequence.title-max-chars", 28), 8, 80);
        this.subtitleMaxChars = clamp(c.getInt("title-sequence.subtitle-max-chars", 40), 10, 120);
        this.introSound = this.sound(c.getConfigurationSection("title-sequence.intro-sound"), "title-sequence.intro-sound");
        this.entrySound = this.sound(c.getConfigurationSection("title-sequence.entry-sound"), "title-sequence.entry-sound");

        this.soundEnabled = c.getBoolean("notify.sound.enabled", true);
        this.soundKey = this.key(c.getString("notify.sound.key", "minecraft:block.note_block.chime"));
        this.soundVolume = (float) c.getDouble("notify.sound.volume", 0.8);
        this.soundPitch = (float) c.getDouble("notify.sound.pitch", 1.2);

        this.bookLinesPerPage = clamp(c.getInt("book.lines-per-page", 13), 4, 14);
        this.bookCharsPerLine = clamp(c.getInt("book.chars-per-line", 18), 8, 19);
        this.bookMaxPages = clamp(c.getInt("book.max-pages", 100), 1, 100);
        this.chatEntriesPerPage = clamp(c.getInt("chat.entries-per-page", 2), 1, 20);

        this.dateFormat = this.formatter(c.getString("date-format", "dd.MM.yyyy"));
        this.categories = this.categories(c.getConfigurationSection("categories"));
        this.chatCmd = this.color(c.getString("styles.chat.cmd"), TextColor.color(0xFFD25A));
        this.chatHl = this.color(c.getString("styles.chat.hl"), NamedTextColor.WHITE);
        this.bookCmd = this.color(c.getString("styles.book.cmd"), TextColor.color(0x8B1A1A));
        this.bookHl = this.color(c.getString("styles.book.hl"), TextColor.color(0x1F2F7A));

        this.pluralNewEntries = plural(c.getStringList("plural.new-entries"), "nowy wpis", "nowe wpisy", "nowych wpisów");
        this.pluralEntries = plural(c.getStringList("plural.entries"), "wpis", "wpisy", "wpisów");
        this.pluralPlayers = plural(c.getStringList("plural.players"), "graczowi", "graczom", "graczom");
    }

    /** A configured sound; the pitch of the n-th entry card is {@code pitch + n * pitchStep}. */
    public record SoundSpec(Key key, float volume, float pitch, float pitchStep) {

        public float pitchAt(final int index) {
            return Math.max(0.5f, Math.min(2.0f, this.pitch + index * this.pitchStep));
        }
    }

    private SoundSpec sound(final ConfigurationSection s, final String path) {
        if (s == null) {
            return null;
        }
        final String key = s.getString("key", "");
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return new SoundSpec(Key.key(key.trim()), (float) s.getDouble("volume", 0.5),
                (float) s.getDouble("pitch", 1.0), (float) s.getDouble("pitch-step", 0.0));
        } catch (final InvalidKeyException e) {
            this.warnings.add(path + ".key: zły klucz dźwięku '" + key + "'");
            return null;
        }
    }

    /** Ticks between the start of two title cards. */
    public int titleSlideTicks() {
        return this.titleFadeIn + this.titleStay + this.titleFadeOut + this.titleGap;
    }

    public boolean has(final ShowMode mode) {
        return this.modes.contains(mode);
    }

    /** Category for a key; unknown keys get a neutral fallback style. */
    public Category category(final String key) {
        final Category c = this.categories.get(key);
        return c == null ? Category.fallback(key) : c;
    }

    /** Polish plural: 1 → [0], 2-4 (not 12-14) → [1], otherwise → [2]. */
    public static String plural(final List<String> forms, final int n) {
        final int abs = Math.abs(n);
        if (abs == 1) {
            return forms.get(0);
        }
        final int mod10 = abs % 10;
        final int mod100 = abs % 100;
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return forms.get(1);
        }
        return forms.get(2);
    }

    private Map<String, Category> categories(final ConfigurationSection section) {
        final Map<String, Category> map = new LinkedHashMap<>();
        if (section != null) {
            for (final String key : section.getKeys(false)) {
                final ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                final String k = key.toLowerCase(Locale.ROOT);
                map.put(k, new Category(k, s.getString("name", key), s.getString("icon", "•"),
                    this.color(s.getString("color"), NamedTextColor.WHITE),
                    this.color(s.getString("book-color"), NamedTextColor.DARK_GRAY)));
            }
        }
        if (map.isEmpty()) {
            this.warnings.add("categories: brak kategorii — użyto domyślnych");
            map.put("nowe", new Category("nowe", "Nowość", "✦", TextColor.color(0x55FF55), TextColor.color(0x1B7A1B)));
            map.put("zmiana", new Category("zmiana", "Zmiana", "⚙", TextColor.color(0xFFAA00), TextColor.color(0xA35A00)));
            map.put("poprawka", new Category("poprawka", "Poprawka", "✔", TextColor.color(0x55FFFF), TextColor.color(0x1A5F8A)));
        }
        return java.util.Collections.unmodifiableMap(map); // keeps config order (suggestions, help)
    }

    private TextColor color(final String value, final TextColor fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        final String v = value.trim();
        final TextColor hex = v.startsWith("#") ? TextColor.fromHexString(v) : null;
        if (hex != null) {
            return hex;
        }
        final NamedTextColor named = NamedTextColor.NAMES.value(v.toLowerCase(Locale.ROOT));
        if (named != null) {
            return named;
        }
        this.warnings.add("nieznany kolor '" + value + "'");
        return fallback;
    }

    private Key key(final String value) {
        try {
            return Key.key(value == null ? "minecraft:block.note_block.chime" : value.trim());
        } catch (final InvalidKeyException e) {
            this.warnings.add("notify.sound.key: zły klucz dźwięku '" + value + "'");
            return Key.key("minecraft:block.note_block.chime");
        }
    }

    private DateTimeFormatter formatter(final String pattern) {
        try {
            return DateTimeFormatter.ofPattern(pattern == null ? "dd.MM.yyyy" : pattern, Locale.forLanguageTag("pl"));
        } catch (final IllegalArgumentException e) {
            this.warnings.add("date-format: zły wzorzec '" + pattern + "'");
            return DateTimeFormatter.ofPattern("dd.MM.yyyy");
        }
    }

    private static List<String> plural(final List<String> forms, final String one, final String few, final String many) {
        return forms != null && forms.size() == 3 ? List.copyOf(forms) : List.of(one, few, many);
    }

    private <T> T bossBarEnum(final Index<String, T> names, final String value, final T fallback,
                              final String path) {
        final T found = value == null ? null : names.value(value.trim().toLowerCase(Locale.ROOT));
        if (found == null) {
            this.warnings.add(path + ": nieznana wartość '" + value + "' (dostępne: " + names.keys() + ")");
            return fallback;
        }
        return found;
    }

    private static int seconds(final double seconds) {
        return (int) Math.max(0, Math.round(seconds * 20));
    }

    private static int minutes(final double minutes) {
        return (int) Math.max(0, Math.round(minutes * 60 * 20));
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
