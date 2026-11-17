// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import pl.medivalrpg.straz.backup.BackupPlan;
import pl.medivalrpg.straz.discord.WebhookUrl;
import pl.medivalrpg.straz.kolej.RailSummary;
import pl.medivalrpg.straz.report.DiscordPayload;
import pl.medivalrpg.straz.report.ReportFormatter;
import pl.medivalrpg.straz.state.StrazState;
import pl.medivalrpg.straz.util.DailyClock;
import pl.medivalrpg.straz.util.Fmt;

/** {@code /straz} (admin only, permission straz.admin), Brigadier tree with Polish literals. */
public final class StrazCommand {

    public static final String PERM = "straz.admin";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_red>[Straż]</dark_red> ";

    private final StrazPlugin plugin;

    public StrazCommand(final StrazPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("straz")
            .requires(s -> s.getSender().hasPermission(PERM))
            .executes(this.run(this::status))
            .then(Commands.literal("status").executes(this.run(this::status)))
            .then(Commands.literal("raport")
                .executes(this.run(this::showReport))
                .then(Commands.literal("teraz").executes(this.run(this::reportNow))))
            .then(Commands.literal("alerty").executes(this.run(this::alerts)))
            .then(Commands.literal("kopia")
                .executes(this.run(this::backupList))
                .then(Commands.literal("lista").executes(this.run(this::backupList)))
                .then(Commands.literal("teraz").executes(this.run(this::backupNow))))
            .then(Commands.literal("test").executes(this.run(this::test)))
            .then(Commands.literal("przeladuj").executes(this.run(this::reload)))
            .then(Commands.literal("pomoc").executes(this.run(this::help)))
            .build();
    }

    private Command<CommandSourceStack> run(final Consumer<CommandSender> action) {
        return (CommandContext<CommandSourceStack> ctx) -> {
            action.accept(ctx.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        };
    }

    private static void msg(final CommandSender s, final String mini) {
        s.sendMessage(MM.deserialize(PREFIX + mini));
    }

    private static String esc(final String s) {
        return MM.escapeTags(s == null ? "" : s);
    }

    // ------------------------------------------------------------------ subcommands

    private void help(final CommandSender s) {
        s.sendMessage(MM.deserialize("<gold><bold>Straż MEDIVALRPG</bold></gold> <gray>— nadzór, raporty i kopie"));
        for (final String[] l : new String[][] {
            {"/straz status", "stan nadzoru: pula, TPS, kolej, kopie, webhook"},
            {"/straz raport", "ostatni raport dzienny na czacie"},
            {"/straz raport teraz", "przygotuj i wyślij raport teraz"},
            {"/straz alerty", "ostatnie alerty"},
            {"/straz kopia lista", "kopie danych pluginów na dysku"},
            {"/straz kopia teraz", "zrób kopię teraz (w tle)"},
            {"/straz test", "wiadomość testowa na kanał (gdy jest webhook)"},
            {"/straz przeladuj", "wczytaj config.yml (np. nowy webhook) bez restartu"}}) {
            s.sendMessage(MM.deserialize("<yellow><click:suggest_command:'" + l[0] + "'>" + l[0] + "</click></yellow> <dark_gray>—</dark_gray> <gray>" + l[1]));
        }
    }

    private void status(final CommandSender s) {
        final StrazService w = this.plugin.service();
        final StrazConfig c = w.config();
        final StrazState st = w.state();
        final ZonedDateTime now = ZonedDateTime.now(c.zone());
        final List<String> out = new ArrayList<>();
        synchronized (st) {
            if (st.poolLastCheck > 0) {
                final boolean ok = st.poolLastTotal == c.expectedPool();
                out.add((ok ? "<green>✔</green>" : "<red>✘</red>") + " <gray>Pula: <white>" + Fmt.md(st.poolLastTotal)
                    + "</white>" + (ok ? "" : " <red>(różnica " + Fmt.signed(st.poolLastTotal - c.expectedPool()) + " md)</red>")
                    + " <dark_gray>· " + ReportFormatter.formatTime(st.poolLastCheck, c.zone()) + ", zapis nr " + st.poolLastSeq);
            } else {
                out.add("<yellow>?</yellow> <gray>Pula: jeszcze nie sprawdzona");
            }
            final var w24 = st.metrics.window(System.currentTimeMillis(), 86_400_000L);
            final double tpsNow = Bukkit.getTPS()[0];
            out.add("<gray>TPS teraz <white>" + Fmt.dec1(tpsNow) + "</white>" + (w24.samples() > 0
                ? " · 24 h śr. " + Fmt.dec1(w24.tpsAvg()) + ", min " + Fmt.dec1(w24.tpsMin()) + ", lag spikes " + w24.lagSpikes() : "")
                + (w.tpsDetector().streak() > 0 ? " <red>· niski TPS od " + w.tpsDetector().streak() + " min</red>" : ""));
            out.add("<gray>Szczyt graczy 24 h: <white>" + w24.peakPlayers() + "</white> · teraz " + Bukkit.getOnlinePlayers().size());
            final long errors24 = st.errors.summary(System.currentTimeMillis(), 86_400_000L, 0).stream()
                .mapToLong(g -> g.count()).sum();
            out.add((errors24 == 0 ? "<green>✔</green>" : "<yellow>!</yellow>") + " <gray>Błędy ERROR/SEVERE 24 h: <white>" + errors24);
            final String rail = w.kolejStatus();
            final RailSummary.Summary r = w.lastRail();
            out.add("<gray>Kolej: <white>" + esc(rail == null ? "—" : Fmt.clip(rail, 90)) + "</white>"
                + (r != null && r.present() ? " <dark_gray>· części " + r.done() + "/" + r.parts() + ", wydano " + Fmt.md(r.spent()) : ""));
            final StrazState.LastBackup b = st.lastBackup;
            out.add((b.at == 0 ? "<yellow>?</yellow>" : b.ok ? "<green>✔</green>" : "<red>✘</red>") + " <gray>Kopia: "
                + (b.at == 0 ? "jeszcze nie wykonana" : "<white>" + esc(b.file) + "</white> " + Fmt.bytes(b.bytes) + " · "
                + ReportFormatter.formatTime(b.at, c.zone()) + (b.ok ? "" : " <red>" + esc(Fmt.clip(b.error, 80)) + "</red>"))
                + " <dark_gray>· następna " + (c.backupEnabled() ? DailyClock.next(now, c.backupAt(), parseDay(st.lastBackupDay)).toLocalDateTime().toString().replace('T', ' ') : "wyłączona"));
            out.add("<gray>Raport: ostatni " + (st.lastReportAt == 0 ? "—" : ReportFormatter.formatTime(st.lastReportAt, c.zone()))
                + " · następny " + DailyClock.next(now, c.reportAt(), parseDay(st.lastReportDay)).toLocalDateTime().toString().replace('T', ' '));
        }
        out.add("<gray>Discord: " + (!w.discord().configured() ? "<yellow>brak webhooka</yellow> — tylko log i pliki raporty/"
            : w.discord().valid() ? "<green>" + esc(WebhookUrl.mask(c.webhookUrl())) + "</green>" : "<red>niepoprawny adres webhooka</red>"));
        msg(s, "<gold>Stan nadzoru</gold>");
        for (final String l : out) {
            s.sendMessage(MM.deserialize("  " + l));
        }
    }

    private static java.time.LocalDate parseDay(final String d) {
        try {
            return d == null || d.isBlank() ? null : java.time.LocalDate.parse(d);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private void showReport(final CommandSender s) {
        final StrazService w = this.plugin.service();
        w.io(() -> {
            final Path f = w.latestReportFile();
            if (f == null) {
                msg(s, "<gray>Brak raportu. Użyj <yellow>/straz raport teraz</yellow>.");
                return;
            }
            final String text;
            try {
                text = Files.readString(f, StandardCharsets.UTF_8);
            } catch (final IOException e) {
                msg(s, "<red>Nie odczytano " + esc(f.getFileName().toString()));
                return;
            }
            final List<Component> lines = render(text);
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                msg(s, "<gray>Plik <white>raporty/" + esc(f.getFileName().toString()) + "</white>");
                lines.forEach(s::sendMessage);
            });
        });
    }

    /** Markdown of the report → chat lines (headings coloured, bold kept, code quotes removed). */
    static List<Component> render(final String markdown) {
        final List<Component> out = new ArrayList<>();
        for (final String raw : markdown.split("\n")) {
            if (raw.isBlank() || raw.equals("---")) {
                continue;
            }
            String line = esc(raw.replace("`", ""));
            line = line.replaceAll("\\*\\*(.+?)\\*\\*", "<white>$1</white>");
            if (raw.startsWith("# ")) {
                line = "<gold><bold>" + line.substring(2) + "</bold></gold>";
            } else if (raw.startsWith("## ")) {
                line = "<yellow><bold>" + line.substring(3) + "</bold></yellow>";
            } else {
                line = "<gray>" + line;
            }
            out.add(MM.deserialize(line));
        }
        return out;
    }

    private void reportNow(final CommandSender s) {
        final StrazService w = this.plugin.service();
        msg(s, "<gray>Przygotowuję raport…");
        w.startReport(false, () -> msg(s, "<yellow>Raport już się przygotowuje.")).whenComplete((r, e) ->
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (e != null) {
                    msg(s, "<red>Raport nieudany: " + esc(e.getMessage()));
                } else if (r != null) {
                    msg(s, (r.ok() ? "<green>" : "<yellow>") + esc(r.statusLine()));
                    msg(s, "<gray>" + (w.discord().configured() && w.discord().valid() ? "Wysłano na Discord i zapisano w pliku."
                        : "Bez webhooka: zapisano w logu i w pliku.") + " Podgląd: <yellow><click:run_command:'/straz raport'>/straz raport</click>");
                }
            }));
    }

    private void alerts(final CommandSender s) {
        final StrazState st = this.plugin.service().state();
        final List<String> recent;
        synchronized (st) {
            recent = new ArrayList<>(st.recentAlerts);
        }
        if (recent.isEmpty()) {
            msg(s, "<green>Brak alertów.");
            return;
        }
        msg(s, "<gold>Ostatnie alerty</gold> <dark_gray>(najnowsze na dole; pełna treść w plugins/Straz/raporty/alerty-*.md)");
        for (final String l : recent.subList(Math.max(0, recent.size() - 12), recent.size())) {
            s.sendMessage(MM.deserialize("  <gray>" + esc(l)));
        }
    }

    private void backupList(final CommandSender s) {
        final StrazService w = this.plugin.service();
        w.io(() -> {
            final List<String> lines = new ArrayList<>();
            try {
                for (final BackupPlan.Entry e : BackupPlan.sorted(w.backupEntries())) {
                    lines.add("  <white>" + e.name() + "</white> <gray>" + Fmt.bytes(e.bytes()));
                }
            } catch (final IOException e) {
                lines.add("  <red>" + esc(e.getMessage()));
            }
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                msg(s, "<gold>Kopie w " + esc(w.backupDir().toString()) + "</gold> <dark_gray>(" + lines.size() + ", zachowuję "
                    + w.config().backupKeepDays() + " dni)");
                if (lines.isEmpty()) {
                    s.sendMessage(MM.deserialize("  <gray>brak"));
                }
                lines.forEach(l -> s.sendMessage(MM.deserialize(l)));
            });
        });
    }

    private void backupNow(final CommandSender s) {
        final StrazService w = this.plugin.service();
        msg(s, "<gray>Kopia danych pluginów w tle…");
        w.startBackup(true, () -> msg(s, "<yellow>Kopia już trwa.")).whenComplete((r, e) ->
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (e != null) {
                    msg(s, "<red>Kopia nieudana: " + esc(e.getMessage()));
                } else if (r != null) {
                    msg(s, r.ok() ? "<green>Gotowe: <white>" + esc(r.file()) + "</white> " + Fmt.bytes(r.bytes()) + ", "
                        + r.files() + " plików, zweryfikowana." + (r.warnings().isEmpty() ? "" : " <yellow>Ostrzeżenia: " + r.warnings().size())
                        : "<red>Nieudana: " + esc(r.error()));
                }
            }));
    }

    private void test(final CommandSender s) {
        final StrazService w = this.plugin.service();
        if (!w.discord().configured()) {
            msg(s, "<yellow>Brak webhooka</yellow> <gray>(discord.webhook-url pusty). Raporty idą do logu i plików.");
            return;
        }
        if (!w.discord().valid()) {
            msg(s, "<red>discord.webhook-url ma niepoprawny format.</red> <gray>Wymagany: https://discord.com/api/webhooks/<id>/<token>");
            return;
        }
        w.discord().send(DiscordPayload.text("🧪 Straż MEDIVALRPG — wiadomość testowa. Raporty dzienne o " + w.config().reportAt()
            + ", alerty natychmiast.", w.config().username()), "test").whenComplete((o, e) ->
            Bukkit.getScheduler().runTask(this.plugin, () -> msg(s, o != null && o.sent() ? "<green>Wysłano wiadomość testową."
                : "<red>Nie wysłano: " + esc(o == null ? String.valueOf(e) : o.info()))));
    }

    private void reload(final CommandSender s) {
        this.plugin.reloadStraz();
        final StrazService w = this.plugin.service();
        msg(s, "<green>Wczytano config.yml.</green> <gray>Discord: " + (!w.discord().configured() ? "brak webhooka"
            : w.discord().valid() ? esc(WebhookUrl.mask(w.config().webhookUrl())) : "<red>niepoprawny adres</red>")
            + "<gray>, raport " + w.config().reportAt() + ", kopia " + (w.config().backupEnabled() ? w.config().backupAt() : "wyłączona") + ".");
    }
}
