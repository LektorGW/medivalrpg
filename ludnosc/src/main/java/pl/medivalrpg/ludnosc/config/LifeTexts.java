// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.config;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Polish texts of the citizens' life (MiniMessage). Each can be overridden in config.yml as
 * {@code messages.obywatele.<klucz>}; without the key the default below is used, so no config merge is required.
 * An empty override switches that message off.
 */
public final class LifeTexts {

    public static final Map<String, String> DEFAULTS = Map.ofEntries(
        // notifications to the country's players
        Map.entry("unpaid", "<prefix><yellow>Skarbiec nie miał na pełne wypłaty — <n> obywateli nie dostało całej pensji. "
            + "Nastrój spada.</yellow>"),
        Map.entry("hut", "<prefix><green>Obywatele zlecili budowę chaty za własne oszczędności (<cost>) przy <x> <z>.</green>"),
        Map.entry("grown-up", "<prefix><green><name> dorosła/dorósł i przejmuje zawód rodzica.</green>"),
        Map.entry("born", "<prefix><green>W rodzinie <mother> i <father> urodziło się dziecko: <name>.</green>"),
        Map.entry("old-age", "<prefix><gray><name> zmarł(a) ze starości w wieku <age> dni.<heir></gray>"),
        // /ludnosc obywatel
        Map.entry("players-only", "<prefix><red>Tylko dla graczy (albo podaj imię obywatela).</red>"),
        Map.entry("not-found", "<prefix><red>Nie znaleziono obywatela <name>. Spójrz na wieśniaka albo podaj imię.</red>"),
        Map.entry("look-at", "<prefix><yellow>Spójrz na obywatela (do 8 bloków) albo użyj /ludnosc obywatel <imię>.</yellow>"),
        Map.entry("ambiguous", "<prefix><yellow>Jest kilku obywateli o imieniu <name> — pokazuję najbliższego/pierwszego.</yellow>"),
        Map.entry("card-header", "<prefix><gold><name></gold> <gray>— <country>, <age>, <profession></gray>"),
        Map.entry("card-home", " <gray>Dom:</gray> <white><home></white>"),
        Map.entry("card-homeless", " <gray>Dom:</gray> <red>bezdomny</red>"),
        Map.entry("card-work", " <gray>Praca:</gray> <white><work></white> <gray>(<place>) — pensja <wage>/dzień</gray>"),
        Map.entry("card-jobless", " <gray>Praca:</gray> <yellow><work></yellow>"),
        Map.entry("card-routine", " <gray>Teraz (<clock>, <phase>):</gray> <white><where></white><loaded>"),
        Map.entry("card-money", " <gray>Oszczędności:</gray> <gold><savings></gold> <gray>• ostatnia wypłata <last> z <due>"
            + "<unpaid> • zarobione <earned>, wydane <spent></gray>"),
        Map.entry("card-family", " <gray>Rodzina:</gray> <white><family></white>"),
        Map.entry("card-age", " <gray>Wiek:</gray> <white><age> dni</white> <gray>(przewidywane życie ok. <lifespan> dni)</gray>"),
        // /ludnosc obywatele
        Map.entry("stats-header", "<prefix><gold>Obywatele kraju <country></gold> <gray>(stan z <age> s temu)</gray>"),
        Map.entry("stats-people", " <gray>Ludność:</gray> <white><population></white> <gray>(dorośli <adults>, dzieci "
            + "<children>, pary <couples>)</gray>"),
        Map.entry("stats-homes", " <gray>Mieszkania:</gray> <white><housed></white> <gray>z łóżkiem,</gray> "
            + "<homeless_color><homeless> bezdomnych</homeless_color>"),
        Map.entry("stats-work", " <gray>Praca:</gray> <white><employed></white> <gray>pracuje,</gray> <yellow><unemployed> "
            + "bezrobotnych</yellow>"),
        Map.entry("stats-money", " <gray>Oszczędności obywateli:</gray> <gold><savings></gold> <gray>(średnio <average>) • "
            + "dochód z pracy <income>, podatek <tax_paid> z <tax_due>, pensje <paid> z <due>, posiłki <meals>, bez pełnej "
            + "pensji: <unpaid></gray>"),
        Map.entry("stats-huts", " <gray>Samodzielne chaty:</gray> <white><state></white> <gray>• limit naraz <limit>, "
            + "w budowie <progress>, zleconych łącznie <total>, koszt <cost> • <reason> • drewno: <wood></gray>"),
        Map.entry("stats-none", "<prefix><gray>Brak danych — przydział domów i pracy liczy się co kilka sekund.</gray>"),
        Map.entry("no-country", "<prefix><red>Nie należysz do żadnego kraju — podaj nazwę kraju.</red>"),
        Map.entry("huts-set", "<prefix><green>Samodzielne chaty obywateli: <state>, limit naraz <limit>.</green>"),
        Map.entry("huts-usage", "<prefix><yellow>Użycie: /ludnosc obywatele chatki wl|wyl|limit <liczba></yellow>"),
        Map.entry("no-rank", "<prefix><red>Wymagana ranga Doradca lub Władca kraju.</red>"),
        Map.entry("disabled", "<prefix><gray>Życie obywateli jest wyłączone w konfiguracji (life.enabled).</gray>"));

    private LifeTexts() {
    }

    public static String raw(final Messages messages, final String key) {
        final String full = "obywatele." + key;
        return messages.has(full) ? messages.raw(full) : DEFAULTS.getOrDefault(key, key);
    }

    public static Component text(final Messages messages, final String key, final TagResolver... resolvers) {
        return messages.parse(raw(messages, key), resolvers);
    }
}
