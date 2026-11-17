// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class BankCheckpointBridgeTest {
    public static final class Provider {
        Thread caller;
        CompletableFuture<Void> future = new CompletableFuture<>();
        public CompletableFuture<Void> bankDurable() { this.caller = Thread.currentThread(); return this.future; }
    }
    public static final class WrongType { public String bankDurable() { return "ok"; } }
    public static final class Throwing { public CompletableFuture<Void> bankDurable() { throw new IllegalStateException("bank"); } }

    @Test void missingOldMalformedAndThrowingProviderNeverProduceAnAcknowledgement() {
        for (Object provider : new Object[]{null, new Object(), new WrongType(), new Throwing()}) {
            var bridge = new BankCheckpointBridge(() -> provider);
            assertThrows(CompletionException.class, () -> bridge.checkpoint().join());
        }
        assertFalse(new BankCheckpointBridge(() -> new WrongType()).available());
    }

    @Test void invocationStaysOnCallerThreadAndAsyncAcknowledgementOnlyCompletesAFuture() throws Exception {
        var provider = new Provider(); var bridge = new BankCheckpointBridge(() -> provider);
        assertTrue(bridge.available()); assertNull(provider.caller, "discovery is read-only");
        var ack = bridge.checkpoint(); assertSame(Thread.currentThread(), provider.caller); assertFalse(ack.isDone());
        Thread writer = Thread.ofPlatform().start(() -> provider.future.complete(null)); writer.join();
        ack.join(); assertTrue(ack.isDone());
    }

    @Test void failedBankFuturePropagatesAndCanBeRequestedAgain() {
        var provider = new Provider(); var bridge = new BankCheckpointBridge(() -> provider);
        provider.future.completeExceptionally(new IllegalStateException("disk"));
        assertThrows(CompletionException.class, () -> bridge.checkpoint().join());
        provider.future = CompletableFuture.completedFuture(null);
        bridge.checkpoint().join();
    }
}
