// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.tutorial;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.config.Messages;
import pl.medivalrpg.menu.gui.StepProgress;
import pl.medivalrpg.menu.integration.PluginPermissions;
import pl.medivalrpg.menu.integration.ProgressHook;
import pl.medivalrpg.menu.integration.Requirements;
import pl.medivalrpg.menu.model.AccessRules;
import pl.medivalrpg.menu.model.ButtonDef;
import pl.medivalrpg.menu.model.MenuConfig;
import pl.medivalrpg.menu.model.MenuDef;

/**
 * The new-player tutorial ("samouczek"): the steps are the buttons with {@code progress:} of the menu
 * {@code samouczek} in {@code menus.yml}; this service knows which of them the player has done, and sends the
 * chat invitation after login. It never opens a GUI or teleports by itself. Main thread only.
 */
public final class TutorialService implements StepProgress {

    /** Id of the tutorial menu in {@code menus.yml} ({@code /menu samouczek}, {@code /samouczek}). */
    public static final String MENU = "samouczek";

    private final Plugin plugin;
    private final Supplier<MenuConfig> menus;
    private final Supplier<MenuSettings> settings;
    private final Supplier<Messages> messages;
    private final ProgressHook hook;
    private final TutorialStore store;

    public TutorialService(final Plugin plugin, final Supplier<MenuConfig> menus, final Supplier<MenuSettings> settings,
                           final Supplier<Messages> messages, final ProgressHook hook, final TutorialStore store) {
        this.plugin = plugin;
        this.menus = menus;
        this.settings = settings;
        this.messages = messages;
        this.hook = hook;
        this.store = store;
    }

    /** The tutorial menu exists and is switched on. */
    public boolean available() {
        return this.settings.get().tutorialEnabled && this.menus.get().menu(MENU) != null;
    }

    // ------------------------------------------------------------------ progress

    /** Evaluates the player's steps; saves newly proven steps and congratulates once when all are done. */
    public TutorialProgress progress(final Player player) {
        final WorldState state = this.hook.read(player);
        final List<ButtonDef> steps = this.steps(player, Boolean.TRUE.equals(state.hasCountry()));
        final TutorialStore.Entry entry = this.store.entry(player.getUniqueId());
        final TutorialProgress progress = TutorialProgress.evaluate(steps.stream().map(ButtonDef::progress).toList(),
            entry, state, this.settings.get().tutorialMoneyGoal);
        boolean save = progress.changed();
        if (progress.finished() && !entry.finished) {
            entry.finished = true;
            save = true;
            player.sendMessage(this.messages.get().get("tutorial-finished"));
        }
        if (save) {
            this.store.save();
        }
        return progress;
    }

    @Override
    public Map<String, Boolean> states(final Player player) {
        return this.progress(player).steps();
    }

    @Override
    public void clicked(final Player player, final String step) {
        if (step == null || ButtonDef.PROGRESS_SUMMARY.equals(step)) {
            return;
        }
        if (this.store.entry(player.getUniqueId()).seen.add(step)) {
            this.store.save();
        }
        this.progress(player);
    }

    /** Step buttons of the tutorial menu the player can see, in menu order (one per step id). */
    private List<ButtonDef> steps(final Player player, final boolean hasCountry) {
        final MenuDef menu = this.menus.get().menu(MENU);
        final List<ButtonDef> out = new ArrayList<>();
        if (menu == null) {
            return out;
        }
        for (final ButtonDef b : menu.buttons()) {
            if (b.progress() == null || ButtonDef.PROGRESS_SUMMARY.equals(b.progress())
                || out.stream().anyMatch(o -> o.progress().equals(b.progress()))) {
                continue;
            }
            if (AccessRules.visible(b, Requirements::met, permission -> PluginPermissions.has(player, permission),
                hasCountry, false)) {
                out.add(b);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ login

    /**
     * Called once per session after the player has logged in (Straznik/nLogin): a few seconds later the player gets
     * the invitation (first time) or the short reminder (later), unless the reminders are off or nothing is left.
     */
    public void onLoggedIn(final Player player) {
        if (!this.available()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.invite(player),
            Math.max(1L, this.settings.get().tutorialInviteDelaySeconds * 20L));
    }

    /** Sends the invitation or the reminder now; {@code true} when a message was sent. */
    public boolean invite(final Player player) {
        if (!player.isOnline() || !this.available() || !player.hasPermission("menu.use")) {
            return false;
        }
        final TutorialStore.Entry entry = this.store.entry(player.getUniqueId());
        if (entry.dismissed) {
            return false;
        }
        final TutorialProgress progress = this.progress(player);
        final boolean first = !entry.invited;
        if (first) {
            entry.invited = true;
            this.store.save();
        }
        if (!progress.remind() || (!first && !this.settings.get().tutorialRemind)) {
            return false; // an established player: no invitation at all
        }
        final Messages msg = this.messages.get();
        final TagResolver resolver = TagResolver.resolver(
            Placeholder.unparsed("player", player.getName()),
            Placeholder.unparsed("done", Integer.toString(progress.done())),
            Placeholder.unparsed("total", Integer.toString(progress.total())),
            Placeholder.component("step", this.stepName(progress.next())));
        for (final String line : msg.rawList(first ? "tutorial-invite" : "tutorial-reminder")) {
            player.sendMessage(msg.parse(line, resolver));
        }
        return true;
    }

    private Component stepName(final String step) {
        final MenuDef menu = this.menus.get().menu(MENU);
        if (menu != null && step != null) {
            for (final ButtonDef b : menu.buttons()) {
                if (step.equals(b.progress())) {
                    return this.messages.get().parse(b.name());
                }
            }
        }
        return Component.empty();
    }

    // ------------------------------------------------------------------ reminders on / off

    /** {@code /samouczek ukryj} and {@code /samouczek przypominaj}. */
    public void setDismissed(final Player player, final boolean dismissed) {
        final TutorialStore.Entry entry = this.store.entry(player.getUniqueId());
        if (entry.dismissed != dismissed || !entry.invited) {
            entry.dismissed = dismissed;
            entry.invited = true;
            this.store.save();
        }
        player.sendMessage(this.messages.get().get(dismissed ? "tutorial-dismissed" : "tutorial-enabled"));
    }
}
