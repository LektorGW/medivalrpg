// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage2;

import java.util.List;
import java.util.Optional;
import pl.medivalrpg.firmy.core.Ctx;
import pl.medivalrpg.firmy.core.Extension;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.model.Firm;

/** Stage 2 in the company life cycle: contracts (and wholesale orders) and loans. Pure Java, main thread. */
public final class Stage2 implements Extension {

    public final Stage2Data data;
    public final Contracts contracts;
    public final Loans loans;
    private final FirmService service;
    private long lastTick;
    private long lastRefill;
    private volatile Stage2Settings settings;

    public Stage2(final FirmService service, final Stage2Data data, final Courts courts, final Stage2Settings settings, final Runnable save) {
        this.service = service;
        this.data = data;
        this.contracts = new Contracts(service, data, courts, settings, save);
        this.loans = new Loans(service, data, courts, settings, save);
        this.settings = settings;
    }

    public void settings(final Stage2Settings s) {
        this.settings = s;
        this.contracts.settings(s);
        this.loans.settings(s);
    }

    /** Every minute: deadlines and offers; wholesale offers once a day. */
    public void tick() {
        final long now = this.service.ctx.now();
        if (now - this.lastTick < 60_000L) {
            return;
        }
        this.lastTick = now;
        this.contracts.tick();
        final String today = this.service.ctx.today();
        if (!today.equals(this.data.lastWholesale)) {
            this.data.lastWholesale = today;
            this.lastRefill = now;
            this.contracts.generateWholesale(today.hashCode() * 31L + now / Ctx.DAY);
        } else if (this.settings.refillMinutes > 0 && now - this.lastRefill >= this.settings.refillMinutes * 60_000L) {
            // courts whose orders were taken, delivered or expired post new ones during the day (still within their budget)
            this.lastRefill = now;
            this.contracts.generateWholesale(today.hashCode() * 31L + now / 60_000L);
        }
    }

    @Override
    public void onIncome(final Firm firm, final long net, final String source) {
        this.loans.onIncome(firm, net);
    }

    @Override
    public void beforeLiquidation(final Firm firm) {
        this.contracts.liquidating(firm);
    }

    @Override
    public List<Claim> liquidationClaims(final Firm firm) {
        return this.loans.claims(firm);
    }

    @Override
    public void daily(final long now) {
        this.loans.daily();
        this.contracts.tick();
    }

    @Override
    public long reserved(final Firm firm) {
        return this.loans.reserved(firm);
    }

    @Override
    public Optional<String> blocksVoluntaryLiquidation(final Firm firm) {
        final boolean disputed = this.data.contracts.values().stream()
            .anyMatch(c -> c.state == Stage2Data.ContractState.DISPUTED && (firm.id.equals(c.contractor) || firm.id.equals(c.clientId)));
        return disputed ? Optional.of("Firma ma kontrakt w sporze — likwidacja po decyzji administratora.") : Optional.empty();
    }
}
