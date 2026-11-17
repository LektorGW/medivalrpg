// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import pl.medivalrpg.turnieje.integration.Economy;

/** Settlement obligations are retired only after confirmed transfers, then durably persisted. */
public final class EscrowSettlement {
    private EscrowSettlement() { }
    public static boolean settle(final EscrowLedger l, final Economy eco, final Runnable persist) {
        if (l.minted > 0) {
            if (!eco.deposit(l.escrowAccount, l.minted, "Nagroda turnieju (nowe pieniądze): " + l.title)) {
                persist.run();
                return false;
            }
            l.minted = 0;
            persist.run();
        }
        for (final var e : new java.util.LinkedHashMap<>(l.payments).entrySet()) {
            if (eco.transfer(l.escrowAccount, e.getKey(), e.getValue(), "Rozliczenie nagród: " + l.title)) {
                l.payments.remove(e.getKey());
            }
            persist.run();
        }
        return l.payments.isEmpty();
    }
    public static boolean refund(final EscrowLedger l, final Economy eco, final Runnable persist) {
        boolean ok = true;
        for (final Map.Entry<UUID, Long> e : new HashMap<>(l.fees).entrySet()) {
            if (eco.transfer(l.escrowAccount, eco.playerAccount(e.getKey()), e.getValue(), "Zwrot wpisowego: " + l.title)) {
                l.fees.remove(e.getKey());
                persist.run();
            } else {
                ok = false;
            }
        }
        if (l.sourceAccount != null && l.funded > 0) {
            final long amount = l.funded;
            if (eco.transfer(l.escrowAccount, l.sourceAccount, amount, "Zwrot puli turnieju: " + l.title)) {
                l.funded = 0;
                persist.run();
            } else {
                ok = false;
            }
        }
        if (ok) {
            final long rest = eco.balance(l.escrowAccount);
            if (rest > 0 && l.sourceAccount != null) {
                ok = eco.transfer(l.escrowAccount, l.sourceAccount, rest, "Reszta depozytu turnieju: " + l.title);
                persist.run();
            }
        }
        return ok;
    }
}
