// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.bridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.firmy.core.Bank;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Bank of Firmy on top of {@link SkarbiecApi#transfer} (frozen API) plus two optional Skarbiec methods reached by
 * reflection: {@code transferBatch(List<Map>)} (atomic batch) and {@code bankDurable()} (journal on disk). With an older
 * Skarbiec the batch falls back to single transfers with compensation, and durability to "now".
 */
public final class SkarbiecBank implements Bank {

    private final SkarbiecApi api;
    private final Plugin skarbiec;
    private final Logger log;
    private final Method batch;
    private final Method durable;

    public SkarbiecBank(final SkarbiecApi api, final Plugin skarbiec, final Logger log) {
        this.api = api;
        this.skarbiec = skarbiec;
        this.log = log;
        this.batch = find(skarbiec, "transferBatch", List.class);
        this.durable = find(skarbiec, "bankDurable");
        if (this.batch == null) {
            log.warning("Skarbiec bez transferBatch — przelewy zbiorcze wykonywane pojedynczo z kompensacją (zaktualizuj Skarbiec).");
        }
    }

    private static Method find(final Plugin p, final String name, final Class<?>... args) {
        if (p == null) {
            return null;
        }
        try {
            return p.getClass().getMethod(name, args);
        } catch (final NoSuchMethodException e) {
            return null;
        }
    }

    public boolean atomicBatch() {
        return this.batch != null;
    }

    @Override
    public long balance(final String account) {
        return this.api.balance(account);
    }

    @Override
    public TxResult transfer(final String from, final String to, final long amount, final String reason) {
        return this.api.transfer(from, to, amount, reason);
    }

    @Override
    public TxResult transferAll(final List<Leg> legs) {
        if (legs.isEmpty()) {
            return TxResult.OK;
        }
        if (legs.size() == 1) {
            final Leg l = legs.getFirst();
            return this.api.transfer(l.from(), l.to(), l.amount(), l.reason());
        }
        if (this.batch != null) {
            final List<Map<String, Object>> arg = new ArrayList<>();
            for (final Leg l : legs) {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("from", l.from());
                m.put("to", l.to());
                m.put("amount", l.amount());
                m.put("reason", l.reason());
                arg.add(m);
            }
            final TxResult r = invokeBatch(this.batch, this.skarbiec, arg, this.log);
            if (r != null) {
                return r;
            }
        }
        final List<Leg> done = new ArrayList<>();
        for (final Leg l : legs) {
            final TxResult r = this.api.transfer(l.from(), l.to(), l.amount(), l.reason());
            if (!r.ok()) {
                for (int i = done.size() - 1; i >= 0; i--) {
                    final Leg d = done.get(i);
                    final TxResult back = this.api.transfer(d.to(), d.from(), d.amount(), "Firmy: cofnięcie przelewu (" + d.reason() + ")");
                    if (!back.ok()) {
                        this.log.severe("Firmy: nie udało się cofnąć przelewu " + d + ": " + back + " — wymaga audytu");
                    }
                }
                return r;
            }
            done.add(l);
        }
        return TxResult.OK;
    }

    /**
     * Calls Skarbiec's atomic batch. {@code null} only when the method could not be called at all (then the legs may go
     * one by one). Once Skarbiec ran it — it threw part-way or answered something unknown — the batch is reported as
     * refused and is NEVER repeated leg by leg: legs it already moved would be paid a second time.
     */
    static TxResult invokeBatch(final Method batch, final Object target, final List<Map<String, Object>> arg, final Logger log) {
        final Object raw;
        try {
            raw = batch.invoke(target, arg);
        } catch (final InvocationTargetException e) {
            log.severe("Firmy: przelew zbiorczy Skarbca przerwany wyjątkiem (" + e.getCause() + ") — bez ponawiania pojedynczo, wymaga audytu: " + arg);
            return TxResult.INVALID_ACCOUNT;
        } catch (final IllegalAccessException | IllegalArgumentException e) {
            log.warning("transferBatch niedostępny (" + e + ") — przelewy pojedynczo.");
            return null;
        }
        try {
            return TxResult.valueOf(String.valueOf(raw));
        } catch (final IllegalArgumentException e) {
            log.severe("Firmy: nieznany wynik przelewu zbiorczego Skarbca (" + raw + ") — bez ponawiania, wymaga audytu: " + arg);
            return TxResult.INVALID_ACCOUNT;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> durable() {
        if (this.durable != null) {
            try {
                return (CompletableFuture<Void>) this.durable.invoke(this.skarbiec);
            } catch (final ReflectiveOperationException | ClassCastException e) {
                return CompletableFuture.completedFuture(null);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public String format(final long copper) {
        return this.api.format(copper);
    }
}
