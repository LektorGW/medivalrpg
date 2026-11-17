// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.service;

import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.armia.api.NpcSettings;
import pl.medivalrpg.postep.logic.NpcStrategy;

/** {@link Integrations.NpcAi} backed by {@link ArmiaApi}; loaded only when Armia is enabled. */
final class ArmiaNpcAi implements Integrations.NpcAi {

    private final ArmiaApi api;
    private final Logger logger;
    private final Set<String> warned = new HashSet<>();

    ArmiaNpcAi(final ArmiaApi api, final Logger logger) {
        this.api = api;
        this.logger = logger;
    }

    @Override
    public boolean present() {
        return true;
    }

    @Override
    public Optional<NpcStrategy.Personality> personality(final UUID country) {
        try {
            final Optional<NpcSettings> s = this.api.npcSettings(country);
            return s.map(n -> new NpcStrategy.Personality(n.aggression(), n.expansion(), n.diplomacy(), n.wealth()));
        } catch (final RuntimeException | LinkageError e) {
            this.warn("npcSettings", e);
            return Optional.empty();
        }
    }

    @Override
    public OptionalLong income24h(final UUID country) {
        try {
            return this.api.economyBreakdown(country).map(e -> OptionalLong.of(e.incomeTotal())).orElse(OptionalLong.empty());
        } catch (final RuntimeException | LinkageError e) {
            this.warn("economyBreakdown", e);
            return OptionalLong.empty();
        }
    }

    @Override
    public void log(final UUID country, final String category, final String text) {
        try {
            this.api.logNpcDecision(country, category, text);
        } catch (final RuntimeException | LinkageError e) {
            this.warn("logNpcDecision", e);
        }
    }

    private void warn(final String method, final Throwable e) {
        if (this.warned.add(method)) {
            this.logger.warning("ArmiaApi#" + method + " zawiodło: " + e);
        }
    }
}
