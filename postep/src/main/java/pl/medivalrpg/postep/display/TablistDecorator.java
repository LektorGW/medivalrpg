// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.display;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.postep.PostepPlugin;
import pl.medivalrpg.postep.model.EraDef;
import pl.medivalrpg.postep.service.ProgressService;
import pl.medivalrpg.postep.service.Snapshot;

/**
 * Appends the era icon to the tab-list name ({@code tablista.dopisz-ikone}). Kraje owns the tab-list name (country tag,
 * rank); this decorator takes whatever name Kraje (or vanilla) set, remembers it as the base and sets
 * {@code tablista.format} around it. When Kraje rewrites the name (join, country change) the decorator sees a name it
 * did not set and decorates the new base.
 *
 * <p>Kraje (since its Postep integration) has its own {@code <era>} / {@code <era_icon>} placeholders in
 * {@code tablist.format}. When Kraje's format uses one of them the decorator steps aside automatically (restores the
 * names it decorated and stops decorating, checked every tick), so the icon is never shown twice. With an old Kraje or
 * an old Kraje config without those placeholders it keeps appending the icon. {@code tablista.dopisz-ikone: false}
 * switches it off completely.</p>
 *
 * <p>Kraje with the extended tab list ({@code tablist.style: extended}, the default of new Kraje jars — also for old
 * server configs, through the jar defaults) takes the name from {@code tablist.extended.name-format} instead of
 * {@code tablist.format}; {@link #krajeNameFormat} picks the format that is really in use. An old Kraje has no
 * {@code tablist.style} at all, which means the classic {@code tablist.format}.</p>
 */
public final class TablistDecorator implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final PostepPlugin plugin;
    private final Map<UUID, Component> base = new HashMap<>();
    private final Map<UUID, Component> applied = new HashMap<>();
    private BukkitTask task;
    private boolean krajeNoticeLogged;

    public TablistDecorator(final PostepPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        this.stop();
        if (!this.plugin.service().cfg().tablistAppend) {
            return;
        }
        final long period = this.plugin.service().cfg().tablistSeconds * 20L;
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 40L, period);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        this.restore();
    }

    /** Puts back the names we decorated (when nobody rewrote them since) and forgets all bases. */
    private void restore() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            final Component mine = this.applied.get(p.getUniqueId());
            if (mine != null && PLAIN.serialize(mine).equals(PLAIN.serialize(p.playerListName()))) {
                final Component original = this.base.get(p.getUniqueId());
                p.playerListName(original == null || original.equals(Component.text(p.getName())) ? null : original);
            }
        }
        this.base.clear();
        this.applied.clear();
    }

    /**
     * Does Kraje show the era itself ({@code <era>} / {@code <era_icon>} in its {@code tablist.format})? Read with the
     * jar defaults as fallback ({@code getString(path)} without a default), exactly like Kraje's own default format.
     */
    static boolean krajeShowsEra(final String krajeTablistFormat) {
        return krajeTablistFormat != null
            && (krajeTablistFormat.contains("<era>") || krajeTablistFormat.contains("<era_icon>"));
    }

    /**
     * Name format Kraje really uses: {@code tablist.extended.name-format} when {@code tablist.style} is the extended
     * style, otherwise (classic, or an old Kraje without {@code tablist.style}) {@code tablist.format}.
     */
    static String krajeNameFormat(final String style, final String classicFormat, final String extendedFormat) {
        if (style == null) {
            return classicFormat;
        }
        final String s = style.trim().toLowerCase(java.util.Locale.ROOT);
        final boolean classic = s.equals("classic") || s.equals("klasyczna") || s.equals("klasyczny");
        return classic || extendedFormat == null ? classicFormat : extendedFormat;
    }

    private boolean krajeShowsEra() {
        final org.bukkit.plugin.Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
        if (kraje == null || !kraje.isEnabled()) {
            return false;
        }
        try {
            // getString(path) without a default falls back to Kraje's jar config.yml, exactly like Kraje itself
            final org.bukkit.configuration.file.FileConfiguration c = kraje.getConfig();
            final String format = krajeNameFormat(c.getString("tablist.style"), c.getString("tablist.format"),
                c.getString("tablist.extended.name-format"));
            return krajeShowsEra(format) && c.getBoolean("tablist.enabled", true);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private void tick() {
        if (this.krajeShowsEra()) {
            if (!this.krajeNoticeLogged) {
                this.krajeNoticeLogged = true;
                this.plugin.getLogger().info("Kraje pokazuje epokę w tabliście (<era>/<era_icon>) – Postep nie dopisuje ikony.");
            }
            if (!this.applied.isEmpty() || !this.base.isEmpty()) {
                this.restore();
            }
            return;
        }
        this.krajeNoticeLogged = false;
        final ProgressService service = this.plugin.service();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            final UUID id = p.getUniqueId();
            final Component current = p.playerListName();
            final Component mine = this.applied.get(id);
            // Our own decoration is still there → keep the remembered base; otherwise Kraje/vanilla set a new base.
            // (plain-text comparison: the server may hand back a structurally different but equivalent component)
            final boolean ours = mine != null && PLAIN.serialize(mine).equals(PLAIN.serialize(current));
            final Component baseName = ours && this.base.containsKey(id) ? this.base.get(id) : current;
            final Optional<KrajInfo> country = service.kraje().getCountryOf(id);
            if (country.isEmpty()) {
                if (ours) { // left the country and nobody rewrote the name: restore it
                    p.playerListName(baseName.equals(Component.text(p.getName())) ? null : baseName);
                }
                this.base.remove(id);
                this.applied.remove(id);
                continue;
            }
            final Snapshot s = service.snapshot(country.get().id()).orElse(null);
            final EraDef era = service.era(s == null ? 1 : s.era());
            final Component decorated = service.messages().parse(service.cfg().tablistFormat,
                Placeholder.component("name", baseName),
                Placeholder.parsed("badge", "<color:" + era.color() + ">" + era.icon() + " " + era.name() + "</color>"),
                Placeholder.parsed("badge_icon", "<color:" + era.color() + ">" + era.icon() + "</color>"),
                Placeholder.unparsed("era", era.name()));
            this.base.put(id, baseName);
            if (!PLAIN.serialize(decorated).equals(PLAIN.serialize(current)) || !ours) {
                p.playerListName(decorated);
            }
            this.applied.put(id, decorated);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        this.base.remove(id);
        this.applied.remove(id);
    }
}
