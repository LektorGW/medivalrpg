// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Title of the newest changelog entry of the optional Nowosci plugin. Nowosci has no service API, so its public
 * accessors ({@code NowosciPlugin#changelog()}, {@code Changelog#newest()}, {@code Entry#title()}) are read
 * reflectively; anything missing simply yields no news. Main thread.
 */
public final class TablistNews {

    private final Logger logger;
    private boolean warned;

    public TablistNews(final Logger logger) {
        this.logger = logger;
    }

    public Optional<String> latestTitle() {
        final Plugin nowosci = Bukkit.getPluginManager().getPlugin("Nowosci");
        if (nowosci == null || !nowosci.isEnabled()) {
            return Optional.empty();
        }
        try {
            final Object changelog = call(nowosci, "changelog");
            if (changelog == null) {
                return Optional.empty();
            }
            if (!(call(changelog, "newest") instanceof Optional<?> newest) || newest.isEmpty()) {
                return Optional.empty();
            }
            final Object title = call(newest.get(), "title");
            return title == null || String.valueOf(title).isBlank() ? Optional.empty() : Optional.of(String.valueOf(title));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!this.warned) {
                this.warned = true;
                this.logger.warning("Tablista: nie udało się odczytać ostatniej nowości z pluginu Nowosci: " + e);
            }
            return Optional.empty();
        }
    }

    private static Object call(final Object target, final String name) throws ReflectiveOperationException {
        final Method m = target.getClass().getMethod(name);
        return m.invoke(target);
    }
}
