// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.nms;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import pl.medivalrpg.pory.logic.BiomeMapping;
import pl.medivalrpg.pory.logic.VisualSeason;

/** State shared by every connection's packet handler (written by the main thread, read by network threads). */
public final class PacketContext {

    private volatile BiomeMapping mapping;
    private volatile VisualSeason globalSeason = VisualSeason.LATO;
    private volatile boolean biomesEnabled = true;
    private volatile SnowRules snowRules;
    private final Consumer<Throwable> errorLog;

    public final AtomicLong chunkPackets = new AtomicLong();
    public final AtomicLong chunkPacketsRewritten = new AtomicLong();
    public final AtomicLong biomePacketsRewritten = new AtomicLong();
    public final AtomicLong snowCorrections = new AtomicLong();
    public final AtomicLong errors = new AtomicLong();
    public final AtomicLong rewriteNanos = new AtomicLong();

    public PacketContext(final Consumer<Throwable> errorLog) {
        this.errorLog = errorLog;
    }

    public BiomeMapping mapping() {
        return this.mapping;
    }

    public void mapping(final BiomeMapping mapping) {
        this.mapping = mapping;
    }

    public VisualSeason globalSeason() {
        return this.globalSeason;
    }

    public void globalSeason(final VisualSeason season) {
        this.globalSeason = season == null ? VisualSeason.LATO : season;
    }

    public boolean biomesEnabled() {
        return this.biomesEnabled;
    }

    public void biomesEnabled(final boolean enabled) {
        this.biomesEnabled = enabled;
    }

    public SnowRules snowRules() {
        return this.snowRules;
    }

    public void snowRules(final SnowRules rules) {
        this.snowRules = rules;
    }

    /** Biome id table for a season, null = send unchanged. */
    int[] table(final VisualSeason season) {
        final BiomeMapping m = this.mapping;
        return m == null || !this.biomesEnabled ? null : m.table(season);
    }

    void error(final Throwable t) {
        // first errors are logged in full, later ones only counted (a broken format must not flood the console)
        if (this.errors.incrementAndGet() <= 5) {
            this.errorLog.accept(t);
        }
    }
}
