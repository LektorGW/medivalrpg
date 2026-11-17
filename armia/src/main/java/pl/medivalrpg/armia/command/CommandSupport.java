// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;

/** Brigadier helpers shared by {@code /armia} and {@code /npckraj}. */
final class CommandSupport {

    private CommandSupport() {
    }

    /** Suggestions filled on the main thread (Bukkit and live data are main-thread only). */
    static SuggestionProvider<CommandSourceStack> provider(final Plugin plugin,
                                                           final BiConsumer<CommandContext<CommandSourceStack>, SuggestionsBuilder> fill) {
        return (ctx, builder) -> {
            if (Bukkit.isPrimaryThread()) {
                fill.accept(ctx, builder);
                return builder.buildFuture();
            }
            try {
                return CompletableFuture.supplyAsync(() -> {
                    fill.accept(ctx, builder);
                    return builder.build();
                }, Bukkit.getScheduler().getMainThreadExecutor(plugin));
            } catch (final RuntimeException e) {
                return Suggestions.empty();
            }
        };
    }

    /** Adds a suggestion with a tooltip when it matches what was typed (quotes and case ignored). */
    static void add(final SuggestionsBuilder builder, final String text, final Component tooltip) {
        final String typed = builder.getRemainingLowerCase().replace("\"", "");
        if (text.replace("\"", "").toLowerCase(Locale.ROOT).startsWith(typed)) {
            builder.suggest(text, tooltip == null ? null : MessageComponentSerializer.message().serialize(tooltip));
        }
    }

    /** A country name as a Brigadier {@code string()} token: quoted when it has non-ASCII letters. */
    static String quoted(final String name) {
        return StringArgumentType.escapeIfRequired(name);
    }

    static String str(final CommandContext<CommandSourceStack> ctx, final String name) {
        return StringArgumentType.getString(ctx, name);
    }

    static Optional<String> optStr(final CommandContext<CommandSourceStack> ctx, final String name) {
        try {
            return Optional.of(StringArgumentType.getString(ctx, name));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Country by name, case-insensitive. */
    static Optional<KrajInfo> country(final KrajeApi kraje, final String name) {
        final Optional<KrajInfo> exact = kraje.getCountryByName(name);
        if (exact.isPresent()) {
            return exact;
        }
        for (final KrajInfo c : kraje.getCountries()) {
            if (c.name().equalsIgnoreCase(name)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
