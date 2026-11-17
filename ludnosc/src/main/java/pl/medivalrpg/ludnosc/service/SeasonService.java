// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.SeasonInfo;
import pl.medivalrpg.ludnosc.logic.Season;
import pl.medivalrpg.ludnosc.logic.SeasonClock;
import pl.medivalrpg.ludnosc.logic.SeasonRules;
import pl.medivalrpg.ludnosc.model.CountryState;

/**
 * Server-wide seasons in real time ({@code seasons.length-days} per season) and poor harvests per country. The season
 * multiplies the food production of the daily economy (and the visible harvests); in winter crops grow slower. A
 * change of season is announced to everybody. Poor harvests are rolled at the daily tick. State: the anchor of the
 * cycle and the last announced season in {@code data.json}, poor harvests in the country state. Main thread except the
 * thread-safe reads ({@link #season()}, {@link #foodFactor}).
 */
public final class SeasonService implements Listener {

    /** Ticks between two checks for a season change. */
    public static final long PERIOD = 400L;

    private final LudnoscPlugin plugin;
    private final Random random = new Random();

    public SeasonService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private SeasonRules rules() {
        return this.plugin.settings().work.seasons;
    }

    public boolean enabled() {
        return this.rules().enabled();
    }

    /** The clock (sets the anchor at the first call: the first season starts today at the daily tick time). */
    public SeasonClock clock() {
        long anchor = this.plugin.data().seasonAnchor();
        if (anchor == 0) {
            final ZonedDateTime now = ZonedDateTime.now(this.plugin.settings().dailyZone);
            final LocalDate day = pl.medivalrpg.ludnosc.logic.DailySchedule.lastPassed(now, this.plugin.settings().dailyTime);
            anchor = day.atTime(this.plugin.settings().dailyTime).atZone(this.plugin.settings().dailyZone).toInstant().toEpochMilli();
            this.plugin.data().setSeasonAnchor(anchor);
        }
        return new SeasonClock(anchor, this.rules().lengthMillis(), this.rules().first());
    }

    public SeasonClock.State state() {
        return this.clock().at(System.currentTimeMillis());
    }

    public Season current() {
        return this.state().season();
    }

    /** {@code LudnoscApi#season}. */
    public Optional<SeasonInfo> season() {
        if (!this.enabled()) {
            return Optional.empty();
        }
        final SeasonClock.State s = this.state();
        return Optional.of(new SeasonInfo(s.season().id(), s.season().displayName(), s.season().ordinal(), s.day(), s.days(),
            s.startedAt(), s.endsAt(), this.rules().foodMultiplier(s.season())));
    }

    /** The country has a poor harvest now. */
    public boolean blight(final UUID country) {
        final CountryState st = this.plugin.data().stateIfPresent(country);
        return this.enabled() && st != null && st.blightUntil > System.currentTimeMillis();
    }

    /** Food production factor of a country now (season × poor harvest). */
    public double foodFactor(final UUID country) {
        if (!this.enabled()) {
            return 1.0;
        }
        final boolean npc = this.plugin.kraje().getCountry(country).map(KrajInfo::npc).orElse(false);
        return this.rules().productionFactor(this.current(), this.blight(country), npc);
    }

    // ------------------------------------------------------------------ change of season

    /** Every {@link #PERIOD} ticks: announces a new season once. */
    public void tick() {
        if (!this.enabled()) {
            return;
        }
        final SeasonClock.State s = this.state();
        final long last = this.plugin.data().seasonSerial();
        if (last == s.serial()) {
            return;
        }
        this.plugin.data().setSeasonSerial(s.serial());
        if (last != Long.MIN_VALUE && this.plugin.settings().work.announceSeasons) {
            this.announce(s.season());
        }
    }

    private void announce(final Season season) {
        final var m = this.plugin.messages();
        final TagResolver r = this.resolver(season);
        final Component chat = m.get("season-changed", r);
        final Title title = Title.title(m.get("season-title", r), m.get("season-subtitle", r),
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1)));
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(chat);
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 0.6f, 1.2f);
        }
        this.plugin.getLogger().info("Nowa pora roku: " + season.displayName());
    }

    private TagResolver resolver(final Season season) {
        final var m = this.plugin.messages();
        final String hint = "season-hint." + season.id(); // keys checked by ConfigTest#seasonHints
        return TagResolver.resolver(Placeholder.unparsed("season", season.displayName()),
            Placeholder.component("hint", m.get(hint)),
            Placeholder.unparsed("food", times(this.rules().foodMultiplier(season))));
    }

    /** Admin: {@code season} starts now (day {@code day} of it). Announced at once. */
    public void set(final Season season, final int day) {
        final SeasonClock moved = this.clock().startingAt(season, day, System.currentTimeMillis());
        this.plugin.data().setSeasonAnchor(moved.anchor());
        this.plugin.data().setSeasonSerial(Long.MIN_VALUE + 1); // force an announcement on the next check
        this.tick();
    }

    // ------------------------------------------------------------------ poor harvests

    /** Daily tick of a country: a running poor harvest may end, a new one may start. */
    public void daily(final KrajInfo k) {
        if (!this.enabled()) {
            return;
        }
        final CountryState st = this.plugin.data().state(k.id());
        final long now = System.currentTimeMillis();
        if (st.blightUntil != 0 && st.blightUntil <= now) {
            st.blightCooldown = this.rules().cooldownUntil(st.blightUntil);
            st.blightUntil = 0;
            this.plugin.data().markDirty();
            this.plugin.notifyCountry(k.id(), "blight-end");
            return;
        }
        if (st.blightUntil > now || k.npc() && !this.rules().affectsNpc() || this.plugin.data().population(k.id()) == 0) {
            return;
        }
        if (this.rules().blightStarts(this.current(), this.random.nextDouble(), st.blightCooldown > now)) {
            this.startBlight(k.id(), this.rules().blightDays(this.random.nextDouble()));
        }
    }

    /** Starts a poor harvest of {@code days} days (admin or the daily roll). */
    public void startBlight(final UUID country, final int days) {
        final CountryState st = this.plugin.data().state(country);
        st.blightUntil = System.currentTimeMillis() + Math.max(1, days) * SeasonClock.DAY;
        this.plugin.data().markDirty();
        this.plugin.notifyCountry(country, "blight-start",
            Placeholder.unparsed("percent", Long.toString(Math.round(this.rules().blightFactor() * 100))),
            Placeholder.unparsed("days", Integer.toString(Math.max(1, days))));
    }

    /** Ends a poor harvest now (admin). True when one was running. */
    public boolean endBlight(final UUID country) {
        final CountryState st = this.plugin.data().stateIfPresent(country);
        if (st == null || st.blightUntil == 0) {
            return false;
        }
        st.blightUntil = 0;
        st.blightCooldown = this.rules().cooldownUntil(System.currentTimeMillis());
        this.plugin.data().markDirty();
        this.plugin.notifyCountry(country, "blight-end");
        return true;
    }

    // ------------------------------------------------------------------ texts

    /** One line about the season (for {@code /ludnosc info}, join, {@code /ludnosc pora}). */
    public Component line(final String key) {
        final SeasonClock.State s = this.state();
        return this.plugin.messages().get(key, Placeholder.unparsed("season", s.season().displayName()),
            Placeholder.unparsed("day", Integer.toString(s.day())), Placeholder.unparsed("days", Integer.toString(s.days())),
            Placeholder.unparsed("next", s.season().next().displayName()),
            Placeholder.unparsed("left", left(s.endsAt() - System.currentTimeMillis())),
            Placeholder.unparsed("food", times(this.rules().foodMultiplier(s.season()))),
            Placeholder.unparsed("growth", Long.toString(Math.round(this.rules().growthChance(s.season()) * 100))));
    }

    /** "×1,4". */
    public static String times(final double v) {
        final String s = String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return "×" + s.replace('.', ',');
    }

    /** "3 dni 4 h" / "5 h 10 min". */
    public static String left(final long millis) {
        final long m = Math.max(0, millis) / 60_000L;
        final long d = m / 1440;
        final long h = m % 1440 / 60;
        if (d > 0) {
            return d + (d == 1 ? " dzień " : " dni ") + h + " h";
        }
        return h > 0 ? h + " h " + m % 60 + " min" : m % 60 + " min";
    }

    /** Date and time (dd.MM HH:mm) of epoch millis in the daily time zone. */
    public String when(final long millis) {
        return DateTimeFormatter.ofPattern("dd.MM HH:mm").format(Instant.ofEpochMilli(millis).atZone(this.plugin.settings().dailyZone));
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(final BlockGrowEvent e) {
        if (!this.enabled() || !Tag.CROPS.isTagged(e.getBlock().getType())) {
            return;
        }
        final var owner=this.plugin.kraje().getCountryAt(e.getBlock().getWorld().getName(),e.getBlock().getX() >> 4,e.getBlock().getZ() >> 4);
        final boolean poor=owner.filter(k -> (!k.npc() || this.rules().affectsNpc()) && this.blight(k.id())).isPresent();
        final double chance = this.rules().growthChance(this.current())*(poor ? this.rules().blightFactor() : 1.0);
        if (chance < 1 && ThreadLocalRandom.current().nextDouble() >= chance) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent e) {
        if (!this.enabled() || !this.plugin.settings().work.announceOnJoin) {
            return;
        }
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline()) {
                p.sendMessage(this.line("season-join"));
                this.plugin.kraje().getCountryOf(p.getUniqueId()).filter(k -> this.blight(k.id()))
                    .ifPresent(k -> this.plugin.messages().send(p, "season-join-blight"));
            }
        }, 80L);
    }
}
