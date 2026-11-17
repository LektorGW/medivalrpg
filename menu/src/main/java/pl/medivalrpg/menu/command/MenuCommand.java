// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.Locale;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.menu.MenuPlugin;
import pl.medivalrpg.menu.guide.GuideService;
import pl.medivalrpg.menu.model.MenuConfig;

/**
 * Brigadier tree of {@code /menu} (aliases {@code /m}, {@code /pomoc}, {@code /przewodnik}):
 *
 * <ul>
 *   <li>{@code /menu} – main menu,</li>
 *   <li>{@code /menu <menu>} – a submenu by id,</li>
 *   <li>{@code /menu poradnik} – the chapter index of the guide, {@code /menu poradnik <rozdział>} – one chapter,</li>
 *   <li>{@code /menu daj} (= {@code /przewodnik daj}) – the guide compass again,</li>
 *   <li>{@code /menu reload} – admin.</li>
 * </ul>
 */
public final class MenuCommand {

    public static final String PERM_USE = "menu.use";
    public static final String PERM_GUIDE = "menu.guide";
    public static final String PERM_ADMIN = "menu.admin";

    private final MenuPlugin plugin;

    public MenuCommand(final MenuPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("menu")
            .requires(s -> s.getSender().hasPermission(PERM_USE))
            .executes(ctx -> this.player(ctx, p -> this.plugin.menus().open(p, MenuConfig.MAIN)))
            .then(Commands.literal("daj")
                .requires(s -> s.getSender().hasPermission(PERM_GUIDE))
                .executes(ctx -> this.player(ctx, this::giveGuide)))
            .then(Commands.literal("poradnik")
                .executes(ctx -> this.player(ctx, p -> this.openBook(p, "poradnik")))
                .then(Commands.argument("rozdzial", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        if (ctx.getSource().getSender() instanceof Player p && Bukkit.isPrimaryThread()) {
                            for (final String chapter : this.plugin.books().chapters(p, "poradnik")) {
                                if (chapter.startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(chapter, MessageComponentSerializer.message().serialize(
                                        Component.text("Rozdział Poradnika: " + chapter, NamedTextColor.GRAY)));
                                }
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> this.player(ctx, p -> this.openBook(p, "poradnik-"
                        + StringArgumentType.getString(ctx, "rozdzial").toLowerCase(Locale.ROOT))))))
            .then(Commands.literal("reload")
                .requires(s -> s.getSender().hasPermission(PERM_ADMIN))
                .executes(ctx -> {
                    final int warnings = this.plugin.reload();
                    ctx.getSource().getSender().sendMessage(this.plugin.messages().get("reloaded",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("warnings",
                            Integer.toString(warnings))));
                    return Command.SINGLE_SUCCESS;
                }))
            .then(Commands.argument("menu", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    if (ctx.getSource().getSender() instanceof Player p && Bukkit.isPrimaryThread()) {
                        for (final String id : this.plugin.menus().openableIds(p)) {
                            if (id.startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(id, MessageComponentSerializer.message().serialize(
                                    Component.text("Otwórz menu " + id, NamedTextColor.GRAY)));
                            }
                        }
                    } else {
                        for (final String id : this.plugin.menuConfig().menus().keySet()) {
                            if (id.startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(id);
                            }
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(ctx -> this.player(ctx, p -> {
                    final String id = StringArgumentType.getString(ctx, "menu").toLowerCase(Locale.ROOT);
                    if (this.plugin.menuConfig().menu(id) == null && this.plugin.menuConfig().book(id) != null) {
                        this.openBook(p, id); // /menu <book>, e.g. a chapter book poradnik-kolej
                    } else {
                        this.plugin.menus().open(p, id);
                    }
                })))
            .build();
    }

    private void openBook(final Player player, final String bookId) {
        switch (this.plugin.books().openBook(player, bookId)) {
            case OPENED -> { }
            case NO_ACCESS -> player.sendMessage(this.plugin.messages().get("no-access"));
            case UNKNOWN -> player.sendMessage(this.plugin.messages().get("unknown-book",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("book", bookId)));
        }
    }

    private int player(final CommandContext<CommandSourceStack> ctx, final Consumer<Player> action) {
        final CommandSender sender = ctx.getSource().getSender();
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            sender.sendMessage(this.plugin.messages().get("players-only"));
            return 0;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }

    private void giveGuide(final Player player) {
        final GuideService.GiveResult result = this.plugin.guide().give(player);
        player.sendMessage(this.plugin.messages().get(switch (result) {
            case GIVEN -> "guide-given";
            case ALREADY_HAS -> "guide-already";
            case NO_SPACE -> "guide-no-space";
            case DISABLED -> "guide-disabled";
        }));
    }
}
