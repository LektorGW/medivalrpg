// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.auth;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.straznik.config.Messages;

/**
 * Login / register window (Paper Dialog API 26.3), shown right after joining while the player waits in limbo:
 * password field(s), the main button and "Wyjdź". Errors (wrong password, too short, lock…) come back in a new
 * window with the attempt counter. Chat commands stay as a fallback.
 *
 * <p>The vanilla dialog text input has no masking option (checked in paper-api 26.3: {@code TextDialogInput} offers
 * width, label, initial value, max length and multiline only), so the typed password is visible on the player's own
 * screen. The value reaches the server in a custom-click packet, is passed straight to the password check and is
 * never logged. Every window has fresh one-use callbacks bound to the player's UUID.
 */
final class LoginDialogs {

    static final String FIELD_PASSWORD = "haslo";
    static final String FIELD_REPEAT = "powtorz";

    /** What the player sent from the window. */
    interface Handler {
        void submit(Player player, boolean register, String password, String repeat);

        void leave(Player player);
    }

    private final Plugin plugin;
    private final Supplier<Messages> messages;
    private final Handler handler;

    LoginDialogs(final Plugin plugin, final Supplier<Messages> messages, final Handler handler) {
        this.plugin = plugin;
        this.messages = messages;
        this.handler = handler;
    }

    /**
     * Shows (or replaces) the window.
     *
     * @param error message shown above the fields, or {@code null}
     */
    void show(final Player player, final boolean registered, final int attemptsLeft, final long secondsLeft,
              final int minLength, final Component error) {
        final Messages m = this.messages.get();
        final UUID id = player.getUniqueId();
        final ClickCallback.Options once = ClickCallback.Options.builder().uses(1)
            .lifetime(Duration.ofSeconds(Math.max(30, secondsLeft + 10))).build();

        final List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(m.get(registered ? "okno-info-login" : "okno-info-rejestracja",
            Messages.text("nick", player.getName()), Messages.number("min", minLength)), 300));
        if (error != null) {
            body.add(DialogBody.plainMessage(error, 300));
        }
        body.add(DialogBody.plainMessage(m.get(registered ? "okno-stan-login" : "okno-stan-rejestracja",
            Messages.number("sekundy", secondsLeft), Messages.number("proby", attemptsLeft)), 300));

        final List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text(FIELD_PASSWORD, m.get("okno-pole-haslo")).width(240).maxLength(128).build());
        if (!registered) {
            inputs.add(DialogInput.text(FIELD_REPEAT, m.get("okno-pole-powtorz")).width(240).maxLength(128).build());
        }

        final ActionButton confirm = ActionButton.builder(m.get(registered ? "okno-przycisk-zaloguj" : "okno-przycisk-zarejestruj"))
            .width(140)
            .action(DialogAction.customClick((view, audience) -> this.onSubmit(id, !registered, view, audience), once))
            .build();
        final ActionButton leave = ActionButton.builder(m.get("okno-przycisk-wyjdz"))
            .width(100)
            .action(DialogAction.customClick((view, audience) -> this.onLeave(id, audience), once))
            .build();

        final Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(m.get(registered ? "okno-tytul-login" : "okno-tytul-rejestracja"))
                .canCloseWithEscape(false)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(inputs)
                .build())
            .type(io.papermc.paper.registry.data.dialog.type.DialogType.confirmation(confirm, leave)));
        player.showDialog(dialog);
    }

    private void onSubmit(final UUID id, final boolean register, final DialogResponseView view, final Audience audience) {
        final String password = clean(view.getText(FIELD_PASSWORD));
        final String repeat = register ? clean(view.getText(FIELD_REPEAT)) : null;
        this.onMain(id, audience, p -> this.handler.submit(p, register, password, repeat));
    }

    private void onLeave(final UUID id, final Audience audience) {
        this.onMain(id, audience, this.handler::leave);
    }

    private void onMain(final UUID id, final Audience audience, final java.util.function.Consumer<Player> action) {
        final Runnable run = () -> {
            final Player p = Bukkit.getPlayer(id);
            // only the player the window was made for
            if (p != null && p.isOnline() && (!(audience instanceof Player ap) || ap.getUniqueId().equals(id))) {
                action.accept(p);
            }
        };
        // always in a fresh task: a new window (with new callbacks) must not be created inside a callback's dispatch
        Bukkit.getScheduler().runTask(this.plugin, run);
    }

    private static String clean(final String s) {
        return s == null ? "" : s.strip();
    }
}
