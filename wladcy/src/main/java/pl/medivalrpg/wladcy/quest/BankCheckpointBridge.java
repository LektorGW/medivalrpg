// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Optional Skarbiec plugin bridge. Invoked on main; returned futures contain no Bukkit callbacks. */
public final class BankCheckpointBridge {
    private final Supplier<?> provider;

    public BankCheckpointBridge(final Supplier<?> provider) {
        this.provider = provider;
    }

    private static Method method(final Object target) throws ReflectiveOperationException {
        final Method method = target.getClass().getMethod("bankDurable");
        if (!CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
            throw new NoSuchMethodException("Unsupported bankDurable return type");
        }
        return method;
    }

    public boolean available() {
        try {
            final Object target = this.provider.get();
            return target != null && method(target) != null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    public CompletableFuture<Void> checkpoint() {
        try {
            final Object target = this.provider.get();
            if (target == null) throw new IllegalStateException("Bank durability unavailable");
            final Object raw = method(target).invoke(target);
            if (!(raw instanceof CompletableFuture<?> future)) throw new IllegalStateException("Invalid bank durability acknowledgement");
            return future.thenApply(value -> {
                if (value != null) throw new IllegalStateException("Invalid bank durability acknowledgement");
                return null;
            });
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return CompletableFuture.failedFuture(new IllegalStateException("Bank durability unavailable", e));
        }
    }
}
