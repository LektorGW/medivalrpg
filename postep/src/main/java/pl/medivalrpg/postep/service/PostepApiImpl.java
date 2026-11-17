// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import pl.medivalrpg.postep.api.PostepApi;
import pl.medivalrpg.postep.model.Discovery;
import pl.medivalrpg.postep.model.EraDef;

/** {@link PostepApi} over the {@link ProgressService} snapshots. Reads are thread-safe; mutations need the main thread. */
public final class PostepApiImpl implements PostepApi {

    private final ProgressService service;

    public PostepApiImpl(final ProgressService service) {
        this.service = service;
    }

    private Optional<Snapshot> snap(final UUID countryId) {
        return this.service.snapshot(countryId);
    }

    @Override
    public int era(final UUID countryId) {
        return this.snap(countryId).map(Snapshot::era).orElse(MEDIEVAL);
    }

    @Override
    public EraInfo eraInfo(final int era) {
        final EraDef e = this.service.era(era);
        return new EraInfo(e.id(), e.name(), e.years(), e.color(), e.description());
    }

    @Override
    public List<EraInfo> eras() {
        return this.service.cfg().eras.stream()
            .map(e -> new EraInfo(e.id(), e.name(), e.years(), e.color(), e.description()))
            .toList();
    }

    @Override
    public double progress(final UUID countryId) {
        return this.snap(countryId).map(s -> s.check().progress()).orElse(0.0);
    }

    @Override
    public long researchPoints(final UUID countryId) {
        return this.snap(countryId).map(s -> (long) Math.floor(s.points())).orElse(0L);
    }

    @Override
    public Set<String> discoveries(final UUID countryId) {
        return this.snap(countryId).map(Snapshot::discoverySet).orElse(Set.of());
    }

    @Override
    public List<String> missingForNextEra(final UUID countryId) {
        return this.snap(countryId).map(s -> s.check().missing()).orElse(List.of());
    }

    @Override
    public Optional<Long> eraSince(final UUID countryId) {
        return this.snap(countryId).map(Snapshot::eraSince);
    }

    @Override
    public String eraIcon(final int era) {
        if (era < 1 || era > this.service.cfg().maxEra()) {
            return "";
        }
        return this.service.era(era).icon();
    }

    @Override
    public String eraBadge(final UUID countryId) {
        final EraDef e = this.service.era(this.era(countryId));
        return "<color:" + e.color() + ">" + e.icon() + " " + e.name() + "</color>";
    }

    @Override
    public double dailyResearch(final UUID countryId) {
        return this.snap(countryId).map(s -> s.daily().total()).orElse(0.0);
    }

    @Override
    public long totalResearchPoints(final UUID countryId) {
        return this.snap(countryId).map(s -> (long) Math.floor(s.totalPoints())).orElse(0L);
    }

    @Override
    public Optional<String> researchTarget(final UUID countryId) {
        return this.snap(countryId).map(Snapshot::target);
    }

    @Override
    public List<DiscoveryInfo> discoveryTree() {
        return this.service.tree().all().stream().map(PostepApiImpl::info).toList();
    }

    @Override
    public Optional<DiscoveryInfo> discovery(final String discoveryId) {
        return this.service.tree().get(discoveryId).map(PostepApiImpl::info);
    }

    static DiscoveryInfo info(final Discovery d) {
        return new DiscoveryInfo(d.id(), d.name(), d.era(), d.cost(), d.requires(), d.requiredForEra(), d.category(),
            d.description(), d.effectText(), d.effects());
    }

    @Override
    public double effect(final UUID countryId, final String effectKey) {
        return this.service.effect(countryId, effectKey);
    }

    @Override
    public double investPreview(final UUID countryId, final long amount) {
        return this.service.investPreview(countryId, amount);
    }

    @Override
    public InvestResult invest(final UUID countryId, final long amount, final UUID byPlayer) {
        requireMain();
        return this.service.invest(countryId, amount, byPlayer);
    }

    @Override
    public int npcInvestShare(final UUID countryId) {
        return this.snap(countryId).map(Snapshot::npcInvestShare).orElse(-1);
    }

    @Override
    public boolean setNpcInvestShare(final UUID countryId, final int percent) {
        requireMain();
        return this.service.setNpcInvestShare(countryId, percent);
    }

    @Override
    public long investedToday(final UUID countryId) {
        return this.snap(countryId).map(s -> (long) s.investedToday()).orElse(0L);
    }

    @Override
    public DiscoverResult discover(final UUID countryId, final String discoveryId, final UUID byPlayer) {
        requireMain();
        final ProgressService.DiscoverResult r = this.service.discover(countryId, discoveryId);
        final String name = r.discovery() == null ? String.valueOf(discoveryId) : r.discovery().name();
        return switch (r.kind()) {
            case UNLOCKED -> new DiscoverResult(true, DiscoverResult.UNLOCKED, r.discovery().id(), 0.0, "Odkryto: " + name + ".");
            case TARGET_SET -> new DiscoverResult(true, DiscoverResult.TARGET_SET, r.discovery().id(), r.missingPoints(),
                "Cel badań: " + name + " (brakuje " + ProgressService.fmt(Math.ceil(r.missingPoints())) + " pkt).");
            case ALREADY_OWNED -> new DiscoverResult(false, DiscoverResult.ALREADY_OWNED, r.discovery().id(), 0.0, "Kraj zna już: " + name + ".");
            case LATER_ERA -> new DiscoverResult(false, DiscoverResult.LATER_ERA, r.discovery().id(), 0.0,
                name + " będzie dostępne dopiero w epoce " + this.service.era(r.discovery().era()).name() + ".");
            case UNKNOWN -> new DiscoverResult(false, DiscoverResult.UNKNOWN, discoveryId, 0.0, "Nie ma odkrycia: " + discoveryId + ".");
            case NO_COUNTRY -> new DiscoverResult(false, DiscoverResult.NO_COUNTRY, discoveryId, 0.0, "Nie ma takiego kraju.");
        };
    }

    @Override
    public boolean setEra(final UUID countryId, final int era) {
        requireMain();
        return this.service.adminSetEra(countryId, era);
    }

    @Override
    public boolean addResearchPoints(final UUID countryId, final double delta) {
        requireMain();
        return Double.isFinite(delta) && this.service.adminPoints(countryId, delta);
    }

    @Override
    public List<String> grantDiscovery(final UUID countryId, final String discoveryId) {
        requireMain();
        return this.service.adminGrant(countryId, discoveryId).stream().map(Discovery::id).toList();
    }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("PostepApi: ta metoda wymaga głównego wątku serwera");
        }
    }
}
