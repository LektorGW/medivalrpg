// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

/** A failed RPC with a Polish message for the browser ({@code rpc-result.error}). */
public final class RpcException extends RuntimeException {

    public RpcException(final String message) {
        super(message, null, false, false);
    }

    public static RpcException denied() {
        return new RpcException("Brak uprawnień do tej operacji.");
    }
}
