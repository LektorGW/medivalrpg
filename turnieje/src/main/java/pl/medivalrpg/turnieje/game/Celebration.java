// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.turnieje.config.Messages;
import pl.medivalrpg.turnieje.config.TurniejeConfig;
import pl.medivalrpg.turnieje.data.Storage;

/** Fireworks (harmless, PDC-marked) and the trophy item (named, PDC-marked with the session id). */
public final class Celebration {

    private static final Color[] COLORS = {Color.ORANGE, Color.YELLOW, Color.RED, Color.WHITE, Color.fromRGB(0xD4AF37), Color.PURPLE};

    private final Keys keys;

    public Celebration(final Keys keys) {
        this.keys = keys;
    }

    public void fireworks(final Location at, final int count) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            final Location l = at.clone().add(r.nextDouble(-4, 4), 1 + r.nextDouble(0, 2), r.nextDouble(-4, 4));
            at.getWorld().spawn(l, Firework.class, fw -> {
                fw.getPersistentDataContainer().set(this.keys.firework, PersistentDataType.BYTE, (byte) 1);
                final FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                    .with(r.nextBoolean() ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.STAR)
                    .withColor(COLORS[r.nextInt(COLORS.length)], COLORS[r.nextInt(COLORS.length)])
                    .withFade(COLORS[r.nextInt(COLORS.length)])
                    .trail(true)
                    .flicker(r.nextBoolean())
                    .build());
                meta.setPower(1 + r.nextInt(2));
                fw.setFireworkMeta(meta);
            });
        }
    }

    public ItemStack trophy(final TurniejeConfig c, final Messages m, final Storage.TrophyRecord t, final String winner) {
        Material mat = Material.matchMaterial(c.trophyMaterial.toUpperCase(Locale.ROOT));
        if (mat == null || !mat.isItem() || mat.isAir()) {
            mat = Material.GOLDEN_SWORD;
        }
        final ItemStack item = new ItemStack(mat);
        final ItemMeta meta = item.getItemMeta();
        final var title = Messages.p("title", t.title());
        final var date = Messages.p("date", t.date());
        final var arena = Messages.p("arena", t.arena());
        final var player = Messages.p("player", winner);
        meta.displayName(m.parse(c.trophyName, title, date, arena, player).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        final List<Component> lore = new ArrayList<>();
        for (final String line : c.trophyLore) {
            lore.add(m.parse(line, title, date, arena, player).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        if (mat.getMaxDurability() > 0) {
            meta.setUnbreakable(true);
        }
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(this.keys.trophy, PersistentDataType.STRING, t.session() == null ? "" : t.session());
        item.setItemMeta(meta);
        return item;
    }

    /** Inventory and receipt are saved together; no ground drops that could be replayed after a crash. */
    public boolean giveOnce(final Player p, final Storage.TrophyRecord t, final TurniejeConfig c, final Messages m) {
        final var receipt = new org.bukkit.NamespacedKey(this.keys.trophy.getNamespace(), "received_trophies");
        final var data = p.getPersistentDataContainer();
        String received = data.getOrDefault(receipt, PersistentDataType.STRING, "");
        final String token = "|" + t.session() + "|";
        if (received.contains(token)) { p.saveData(); return true; }
        final int slot = p.getInventory().firstEmpty();
        if (slot < 0) return false;
        p.getInventory().setItem(slot, this.trophy(c, m, t, p.getName()));
        data.set(receipt, PersistentDataType.STRING, received + token);
        p.saveData();
        m.send(p, "trophy-received", Messages.p("title", t.title()));
        return true;
    }
}
