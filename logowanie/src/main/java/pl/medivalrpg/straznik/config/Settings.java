// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.config;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable view of config.yml (values clamped to safe ranges). */
public final class Settings {

    public final int authTimeoutSeconds;
    public final int reminderSeconds;
    public final int attemptsPerConnection;
    public final int lockoutFailures;
    public final int lockoutWindowMinutes;
    public final int lockoutMinutes;

    public final boolean sessionEnabled;
    public final int sessionMinutes;

    public final int accountsPerIp;
    public final Set<String> accountsPerIpExempt;

    public final int passwordMinLength;
    public final int passwordMaxLength;
    public final boolean passwordForbidNick;

    public final int argonMemoryKiB;
    public final int argonIterations;
    public final int argonLanes;
    public final int hashThreads;

    public final Pattern nickPattern;
    public final boolean nickCaseProtection;
    public final boolean blockDoubleJoin;

    public final boolean limboHideInventory;
    public final boolean limboHidePosition;
    /** {@code swiat;x;y;z[;yaw;pitch]}; empty → spawn of the main world. */
    public final String limboLocation;
    public final boolean limboHidePlayers;
    public final boolean limboHideChat;
    public final boolean limboBlindness;

    public final boolean importOnStart;
    public final String importFile;

    public final boolean adminPasswordsConsoleOnly;
    public final boolean hideCommandsInConsole;
    public final boolean bossbar;
    public final boolean titles;
    /** Login/register window (Paper Dialog API); chat commands work either way. */
    public final boolean dialog;

    public Settings(final FileConfiguration c) {
        this.authTimeoutSeconds = Math.clamp(c.getInt("logowanie.limit-czasu-sekundy", 90), 15, 600);
        this.reminderSeconds = Math.clamp(c.getInt("logowanie.przypomnienie-co-sekund", 10), 3, 120);
        this.attemptsPerConnection = Math.clamp(c.getInt("logowanie.proby-na-polaczenie", 3), 1, 10);
        this.lockoutFailures = Math.clamp(c.getInt("logowanie.blokada.po-nieudanych", 5), 1, 100);
        this.lockoutWindowMinutes = Math.clamp(c.getInt("logowanie.blokada.okno-minut", 15), 1, 1440);
        this.lockoutMinutes = Math.clamp(c.getInt("logowanie.blokada.czas-minut", 15), 1, 1440);

        this.sessionEnabled = c.getBoolean("sesja.wlaczona", true);
        this.sessionMinutes = Math.clamp(c.getInt("sesja.minuty", 10), 1, 1440);

        this.accountsPerIp = Math.clamp(c.getInt("konta-na-ip.limit", 3), 0, 100);
        this.accountsPerIpExempt = c.getStringList("konta-na-ip.wyjatki").stream()
            .map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());

        this.passwordMinLength = Math.clamp(c.getInt("haslo.min-dlugosc", 6), 4, 64);
        this.passwordMaxLength = Math.clamp(c.getInt("haslo.max-dlugosc", 64), this.passwordMinLength, 128);
        this.passwordForbidNick = c.getBoolean("haslo.zakaz-nicku", true);

        this.argonMemoryKiB = Math.clamp(c.getInt("haslo.argon2.pamiec-kib", 65536), 8192, 262144);
        this.argonIterations = Math.clamp(c.getInt("haslo.argon2.iteracje", 3), 1, 10);
        this.argonLanes = Math.clamp(c.getInt("haslo.argon2.rownoleglosc", 1), 1, 4);
        this.hashThreads = Math.clamp(c.getInt("haslo.argon2.watki", 2), 1, 4);

        Pattern nick;
        try {
            nick = Pattern.compile(c.getString("nick.wzorzec", "[a-zA-Z0-9_]{3,16}"));
        } catch (final PatternSyntaxException e) {
            nick = Pattern.compile("[a-zA-Z0-9_]{3,16}");
        }
        this.nickPattern = nick;
        this.nickCaseProtection = c.getBoolean("nick.ochrona-wielkosci-liter", true);
        this.blockDoubleJoin = c.getBoolean("nick.blokuj-podwojne-wejscie", true);

        this.limboHideInventory = c.getBoolean("limbo.ukryj-ekwipunek", true);
        this.limboHidePosition = c.getBoolean("limbo.ukryj-pozycje", true);
        this.limboLocation = c.getString("limbo.lokalizacja", "").strip();
        this.limboHidePlayers = c.getBoolean("limbo.ukryj-graczy", true);
        this.limboHideChat = c.getBoolean("limbo.ukryj-czat", true);
        this.limboBlindness = c.getBoolean("limbo.slepota", false);

        this.importOnStart = c.getBoolean("import.nlogin.przy-starcie", true);
        this.importFile = c.getString("import.nlogin.plik", "plugins/nLogin/nlogin.db").strip();

        this.adminPasswordsConsoleOnly = c.getBoolean("admin.hasla-tylko-z-konsoli", true);
        this.hideCommandsInConsole = c.getBoolean("log.ukryj-komendy-w-konsoli", true);
        this.bossbar = c.getBoolean("wyglad.bossbar", true);
        this.titles = c.getBoolean("wyglad.tytuly", true);
        this.dialog = c.getBoolean("wyglad.okno", true);
    }

    /** Defaults only (tests). */
    public static Settings defaults() {
        return new Settings(new YamlConfiguration());
    }
}
