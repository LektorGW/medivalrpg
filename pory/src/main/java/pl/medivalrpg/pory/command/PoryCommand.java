// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.pory.PoryPlugin;
import pl.medivalrpg.pory.config.Messages;
import pl.medivalrpg.pory.logic.BiomeMapping;
import pl.medivalrpg.pory.logic.VisualSeason;
import pl.medivalrpg.pory.nms.Nms;
import pl.medivalrpg.pory.nms.PacketContext;
import pl.medivalrpg.pory.nms.SeasonPacketHandler;
import pl.medivalrpg.pory.service.SeasonService;

/** {@code /pory} for players and {@code /pory admin …} (preview for self, refresh, status, reload). */
public final class PoryCommand {

    public static final String ADMIN = "pory.admin";
    private static final List<String> PREVIEW_ARGS = List.of("wiosna", "lato", "jesien", "zima", "wylacz");

    private final PoryPlugin plugin;

    public PoryCommand(final PoryPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build(final String name) {
        return Commands.literal(name)
            .requires(s -> s.getSender().hasPermission("pory.use"))
            .executes(c -> this.info(c.getSource().getSender()))
            .then(Commands.literal("pomoc").executes(c -> this.help(c.getSource().getSender())))
            .then(Commands.literal("admin")
                .requires(s -> s.getSender().hasPermission(ADMIN))
                .executes(c -> this.help(c.getSource().getSender()))
                .then(Commands.literal("podglad")
                    .then(Commands.argument("pora", StringArgumentType.word())
                        .suggests((c, b) -> {
                            final String typed = b.getRemainingLowerCase();
                            PREVIEW_ARGS.stream().filter(s -> s.startsWith(typed)).forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(c -> this.preview(c.getSource().getSender(),
                            StringArgumentType.getString(c, "pora")))))
                .then(Commands.literal("odswiez").executes(c -> this.refresh(c.getSource().getSender())))
                .then(Commands.literal("status").executes(c -> this.status(c.getSource().getSender())))
                .then(Commands.literal("reload").executes(c -> this.reload(c.getSource().getSender()))))
            .build();
    }

    private Messages m() {
        return this.plugin.messages();
    }

    private static TagResolver season(final VisualSeason s) {
        return Placeholder.unparsed("season", s.displayName());
    }

    static String duration(final long millis) {
        final long minutes = Math.max(0, millis) / 60_000L;
        final long days = minutes / 1440;
        final long hours = minutes % 1440 / 60;
        final long mins = minutes % 60;
        if (days > 0) {
            return days + " d " + hours + " h";
        }
        if (hours > 0) {
            return hours + " h " + mins + " min";
        }
        return Math.max(1, mins) + " min";
    }

    private int info(final CommandSender sender) {
        final SeasonService.State s = this.plugin.seasons().state();
        final Messages m = this.m();
        sender.sendMessage(m.line("info-header", season(s.season())));
        if (s.fromLudnosc()) {
            sender.sendMessage(m.line("info-day",
                Placeholder.unparsed("day", String.valueOf(s.day())),
                Placeholder.unparsed("days", String.valueOf(s.days())),
                Placeholder.unparsed("next", s.season().next().displayName()),
                Placeholder.unparsed("left", duration(s.endsAt() - System.currentTimeMillis()))));
        }
        VisualSeason shown = s.season();
        if (sender instanceof Player p && this.plugin.views() != null && this.plugin.views().previewing(p)) {
            shown = this.plugin.views().season(p);
            sender.sendMessage(m.line("info-preview", season(shown)));
        }
        sender.sendMessage(m.line("info-look." + shown.id()));
        sender.sendMessage(m.line(s.fromLudnosc() ? "info-source-ludnosc" : "info-source-fallback", season(s.season())));
        sender.sendMessage(m.line("info-tip"));
        return 1;
    }

    private int help(final CommandSender sender) {
        this.m().lines("help").forEach(sender::sendMessage);
        if (sender.hasPermission(ADMIN)) {
            this.m().lines("help-admin").forEach(sender::sendMessage);
        }
        return 1;
    }

    private int preview(final CommandSender sender, final String arg) {
        if (!(sender instanceof Player player) || this.plugin.views() == null) {
            this.m().send(sender, "players-only");
            return 0;
        }
        final String a = arg.toLowerCase(Locale.ROOT);
        if (a.equals("wylacz") || a.equals("wyłącz") || a.equals("off")) {
            this.plugin.views().preview(player, null);
            this.m().send(sender, "preview-off", season(this.plugin.seasons().state().season()));
            return 1;
        }
        final VisualSeason s = VisualSeason.parse(a);
        if (s == null) {
            this.m().send(sender, "preview-unknown");
            return 0;
        }
        this.plugin.views().preview(player, s);
        this.m().send(sender, "preview-set", season(s));
        return 1;
    }

    private int refresh(final CommandSender sender) {
        if (!(sender instanceof Player player) || this.plugin.views() == null) {
            this.m().send(sender, "players-only");
            return 0;
        }
        final int n = this.plugin.views().apply(player, true);
        this.m().send(sender, "refresh-own", Placeholder.unparsed("chunks", String.valueOf(n)));
        return 1;
    }

    private int reload(final CommandSender sender) {
        this.plugin.reload();
        this.m().send(sender, "reloaded");
        return 1;
    }

    private int status(final CommandSender sender) {
        final Messages m = this.m();
        sender.sendMessage(m.line("status-header"));
        final SeasonService.State s = this.plugin.seasons().state();
        final PacketContext c = this.plugin.context();
        final BiomeMapping map = c == null ? null : c.mapping();
        this.row(sender, "Pora serwera", s.season().displayName() + " (dzień " + s.day() + "/" + s.days() + ", źródło: "
            + (s.fromLudnosc() ? "Ludnosc" : "zapasowa") + ")");
        if (map != null) {
            this.row(sender, "Biomy", map.baseCount() + " bazowych, warianty: wiosna " + map.variantCount(VisualSeason.WIOSNA)
                + ", jesień " + map.variantCount(VisualSeason.JESIEN) + ", zima " + map.variantCount(VisualSeason.ZIMA)
                + (c.biomesEnabled() ? "" : " — WYŁĄCZONE"));
        }
        if (c != null) {
            final long rewritten = c.chunkPacketsRewritten.get();
            final double avgUs = rewritten == 0 ? 0 : c.rewriteNanos.get() / 1000.0 / rewritten;
            this.row(sender, "Pakiety chunków", c.chunkPackets.get() + " wysłanych, " + rewritten + " przepisanych (śr. "
                + String.format(Locale.ROOT, "%.1f", avgUs) + " µs), biomy: " + c.biomePacketsRewritten.get()
                + ", błędy: " + c.errors.get());
            this.row(sender, "Śnieg — poprawki", String.valueOf(c.snowCorrections.get()));
        }
        this.row(sender, "Odświeżanie biomów", this.plugin.biomes().pending() + " w kolejce, wysłano "
            + this.plugin.biomes().sentChunks + " chunków w " + this.plugin.biomes().sentPackets + " pakietach");
        final var snow = this.plugin.snow();
        this.row(sender, "Śnieg", snow.pending() + " w kolejce, " + snow.chunksProcessed + " chunków, +" + snow.columnsAdded
            + " / -" + snow.columnsRemoved + " kolumn, pominięte " + snow.dropped + ", czas "
            + String.format(Locale.ROOT, "%.1f", snow.nanos / 1_000_000.0) + " ms");
        this.row(sender, "Liście", String.valueOf(this.plugin.leaves().spawned));
        if (sender instanceof Player p) {
            final SeasonPacketHandler h = Nms.handler(p);
            if (h != null) {
                this.row(sender, "Twój widok", h.season().displayName() + (h.override() != null ? " (podgląd)" : "")
                    + ", śnieg: " + h.snow().columns() + " kolumn w " + h.snow().chunks() + " chunkach");
            }
        }
        if (SeasonPacketHandler.reflectionError() != null) {
            this.row(sender, "Uwaga", "przepisywanie chunków niedostępne: " + SeasonPacketHandler.reflectionError());
        }
        return 1;
    }

    private void row(final CommandSender sender, final String label, final String value) {
        sender.sendMessage(Component.text()
            .append(this.m().parse("<gray>" + "<label>: </gray>", Placeholder.unparsed("label", label)))
            .append(Component.text(value))
            .build());
    }
}
