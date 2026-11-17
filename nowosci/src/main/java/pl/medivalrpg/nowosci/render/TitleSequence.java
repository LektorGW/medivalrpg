// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.render;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import pl.medivalrpg.nowosci.config.Messages;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.config.NowosciConfig.SoundSpec;
import pl.medivalrpg.nowosci.model.Entry;
import pl.medivalrpg.nowosci.render.EntryFormat.Medium;

/**
 * Title cards shown in the middle of the screen ({@code show.mode: title}): an intro card
 * ("✦ Nowości ✦ / na serwerze MEDIVALRPG") and one card per unread entry (icon + title in the category colour,
 * the first point shortened as plain text below). Pure function; playing them is done by the service.
 */
public final class TitleSequence {

    /** One card. {@code sound} may be {@code null}. */
    public record Slide(Component title, Component subtitle, Sound sound) {
    }

    private final EntryFormat format;
    private final Messages m;
    private final NowosciConfig config;

    public TitleSequence(final EntryFormat format) {
        this.format = format;
        this.m = format.messages();
        this.config = format.config();
    }

    /** Intro card plus one card per entry (newest first, at most {@code show.max-entries}). */
    public List<Slide> forEntries(final List<Entry> entries) {
        final List<Slide> slides = new ArrayList<>();
        slides.add(new Slide(this.m.get("sequence-intro-title"), this.m.get("sequence-intro-subtitle"),
            sound(this.config.introSound, 0)));
        final int count = Math.min(entries.size(), this.config.maxEntries);
        for (int i = 0; i < count; i++) {
            slides.add(this.entrySlide(entries.get(i), i));
        }
        return slides;
    }

    /** Single welcome card for a player joining for the first time. */
    public List<Slide> welcome() {
        return List.of(new Slide(this.m.get("sequence-welcome-title"), this.m.get("sequence-welcome-subtitle"),
            sound(this.config.introSound, 0)));
    }

    Slide entrySlide(final Entry e, final int index) {
        final Entry shortTitle = new Entry(e.id(), e.date(), EntryFormat.shorten(e.title(), this.config.titleMaxChars),
            e.category(), e.points());
        final Component title = this.m.get("sequence-entry-title", this.format.tags(shortTitle, Medium.CHAT));
        final Component subtitle = e.points().isEmpty()
            ? this.m.get("sequence-entry-subtitle-empty", this.format.tags(e, Medium.CHAT))
            : this.m.get("sequence-entry-subtitle", this.format.tags(e, Medium.CHAT),
                Placeholder.unparsed("point", this.subtitleText(e.points().getFirst())));
        return new Slide(title, subtitle, sound(this.config.entrySound, index));
    }

    /** First point as plain text (no colours, clicks or hovers), shortened to {@code subtitle-max-chars}. */
    public String subtitleText(final String pointMiniMessage) {
        return this.format.shortPoint(pointMiniMessage, this.config.subtitleMaxChars);
    }

    public Title.Times times() {
        return Title.Times.times(ticks(this.config.titleFadeIn), ticks(this.config.titleStay), ticks(this.config.titleFadeOut));
    }

    /** Ticks between the start of two cards (the next one fades in after the previous one has faded out). */
    public int slideTicks() {
        return this.config.titleSlideTicks();
    }

    private static Sound sound(final SoundSpec spec, final int index) {
        return spec == null ? null : Sound.sound(spec.key(), Sound.Source.MASTER, spec.volume(), spec.pitchAt(index));
    }

    private static Duration ticks(final int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }
}
