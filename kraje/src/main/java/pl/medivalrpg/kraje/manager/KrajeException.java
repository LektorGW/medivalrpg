// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.manager;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import pl.medivalrpg.kraje.api.KrajeResult;

/**
 * A user facing failure; carries a message key from config.yml plus placeholders, and the
 * {@link KrajeResult.Status} reported when the same rule fails through the public API.
 */
public final class KrajeException extends RuntimeException {

    private final String messageKey;
    private final KrajeResult.Status status;
    private final transient TagResolver[] resolvers;

    public KrajeException(final String messageKey, final TagResolver... resolvers) {
        this(KrajeResult.Status.DENIED, messageKey, resolvers);
    }

    public KrajeException(final KrajeResult.Status status, final String messageKey, final TagResolver... resolvers) {
        super(messageKey, null, false, false);
        this.messageKey = messageKey;
        this.status = status;
        this.resolvers = resolvers;
    }

    public String messageKey() {
        return this.messageKey;
    }

    public KrajeResult.Status status() {
        return this.status;
    }

    public TagResolver[] resolvers() {
        return this.resolvers;
    }
}
