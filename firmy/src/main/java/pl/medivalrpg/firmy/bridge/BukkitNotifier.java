// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.medivalrpg.firmy.core.Notifier;
import pl.medivalrpg.firmy.model.FirmsData;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.model.Rank;

/** Chat messages; offline players get them from the mailbox on their next join. Main thread. */
public final class BukkitNotifier implements Notifier {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int MAILBOX = 30;

    private final FirmsData data;
    private final KrajeApi kraje;
    private final Logger log;

    public BukkitNotifier(final FirmsData data, final KrajeApi kraje, final Logger log) {
        this.data = data;
        this.kraje = kraje;
        this.log = log;
    }

    public static Component parse(final String miniMessage) {
        try {
            return MM.deserialize(miniMessage);
        } catch (final RuntimeException e) {
            return Component.text(miniMessage);
        }
    }

    @Override
    public void player(final UUID player, final String miniMessage) {
        final Player p = Bukkit.getPlayer(player);
        if (p != null) {
            p.sendMessage(parse(miniMessage));
            return;
        }
        final List<String> box = this.data.mail.computeIfAbsent(player.toString(), k -> new ArrayList<>());
        box.add(miniMessage);
        if (box.size() > MAILBOX) {
            box.subList(0, box.size() - MAILBOX).clear();
        }
    }

    @Override
    public void countryLeaders(final UUID country, final String miniMessage) {
        this.kraje.getCountry(country).ifPresent(k -> k.members().forEach((uuid, rank) -> {
            if (rank != null && rank.atLeast(Rank.ADVISOR)) {
                this.player(uuid, miniMessage);
            }
        }));
    }

    @Override
    public void admin(final String text, final boolean severe) {
        if (severe) {
            this.log.severe(text);
        } else {
            this.log.info(text);
        }
    }

    /** Shows and clears the mailbox of a player who just joined. */
    public boolean deliver(final Player p) {
        final List<String> box = this.data.mail.remove(p.getUniqueId().toString());
        if (box == null || box.isEmpty()) {
            return false;
        }
        p.sendMessage(parse("<gold>[Firmy]</gold> <gray>Wiadomości z czasu, gdy cię nie było (" + box.size() + "):"));
        for (final String m : box) {
            p.sendMessage(parse(m));
        }
        return true;
    }
}
