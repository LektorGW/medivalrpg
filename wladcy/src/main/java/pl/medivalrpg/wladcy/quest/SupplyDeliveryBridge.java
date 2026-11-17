// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import pl.medivalrpg.wladcy.text.TextSafety;

/** Optional plugin-level bridge. Ludnosc alone owns item extraction, warehouse insertion and durable receipts. */
public final class SupplyDeliveryBridge {
    public enum Status { PENDING, DELIVERED, BLOCKED, UNAVAILABLE }

    public record Result(Status status, String receipt, String reason) { }

    private final Supplier<?> provider;

    public SupplyDeliveryBridge(final Supplier<?> provider) {
        this.provider = provider;
    }

    private static Method method(final Object target) throws ReflectiveOperationException {
        final Method method = target.getClass().getMethod("supplyDelivery", UUID.class, UUID.class, String.class, String.class, int.class);
        if (!Map.class.isAssignableFrom(method.getReturnType())) {
            throw new NoSuchMethodException("Unsupported supplyDelivery return type");
        }
        return method;
    }

    /** Presence check only; never calls the mutating provider method. */
    public boolean available() {
        try {
            final Object target = this.provider.get();
            return target != null && method(target) != null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    public Result deliver(final UUID player, final UUID country, final String contractId, final String material, final int amount) {
        if (player == null || country == null || contractId == null || !contractId.matches("wladcy:[a-zA-Z0-9-]{1,80}")
            || material == null || !material.matches("[A-Z][A-Z0-9_]{0,79}") || amount < 1 || amount > 2304) {
            return new Result(Status.UNAVAILABLE, "", "Nieprawidłowe dane kontraktu — wymagany audyt.");
        }
        try {
            final Object target = this.provider.get();
            if (target == null) {
                return unavailable();
            }
            final Object raw = method(target).invoke(target, player, country, contractId, material, amount);
            if (!(raw instanceof Map<?, ?> map) || !(field(map, "status") instanceof String name)) {
                return unavailable();
            }
            final Status status;
            try {
                status = Status.valueOf(name);
            } catch (final IllegalArgumentException unknown) {
                return unavailable();
            }
            final Object receiptValue = field(map, "receipt");
            final String receipt = receiptValue instanceof String s && s.length() <= 256 ? s : "";
            // An unknown/malformed response may follow a real commit. Keep the same contract id and never fall back.
            if (status == Status.DELIVERED && receipt.isBlank()) {
                return unavailable();
            }
            final String reason = field(map, "reason") instanceof String s ? TextSafety.clean(s, 240) : "";
            return new Result(status, receipt, reason);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return unavailable();
        }
    }

    private static Result unavailable() {
        return new Result(Status.UNAVAILABLE, "", "Magazyn nie potwierdził dostawy. Ponów oddanie tego samego zlecenia.");
    }

    private static Object field(final Map<?, ?> map, final String key) {
        return map.get(key);
    }
}
