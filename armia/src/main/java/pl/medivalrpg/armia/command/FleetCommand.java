// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import pl.medivalrpg.armia.naval.FleetService;

/** Fleet access is checked in the service on every action, including boarding and unloading. */
final class FleetCommand {
    private FleetCommand() { }
    static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> build(final FleetService fleet) {
        return Commands.literal("flota")
            .executes(ctx->run(ctx.getSource(),fleet::list))
            .then(Commands.literal("lista").executes(ctx->run(ctx.getSource(),fleet::list)))
            .then(Commands.literal("zbuduj").then(Commands.argument("typ",StringArgumentType.word())
                .suggests((ctx,b)->{b.suggest("transportowiec");b.suggest("karawela");return b.buildFuture();})
                .executes(ctx->run(ctx.getSource(),p->fleet.build(p,StringArgumentType.getString(ctx,"typ"))))))
            .then(Commands.literal("wejdz").executes(ctx->run(ctx.getSource(),fleet::board)))
            .then(Commands.literal("steruj").executes(ctx->run(ctx.getSource(),fleet::steer)))
            .then(Commands.literal("zejdz").executes(ctx->run(ctx.getSource(),fleet::leave)))
            .then(Commands.literal("zaladuj").executes(ctx->run(ctx.getSource(),fleet::load)))
            .then(Commands.literal("desant").executes(ctx->run(ctx.getSource(),fleet::land)))
            .then(Commands.literal("salwa").executes(ctx->run(ctx.getSource(),fleet::volley)))
            .then(Commands.literal("usun").then(Commands.literal("potwierdz").executes(ctx->run(ctx.getSource(),fleet::remove))));
    }
    private static int run(final CommandSourceStack source,final Consumer<Player> action) {
        if(source.getSender() instanceof Player player) {action.accept(player);return 1;}
        return 0;
    }
}
