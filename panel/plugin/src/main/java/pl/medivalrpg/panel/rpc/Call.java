// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.panel.access.Actor;

/** One RPC call being executed on the main thread. */
public final class Call {

    /**
     * In-game notification for the online members of a country ({@code messages.notify.<method>}).
     *
     * @param country country whose members are told
     * @param target  {@code <target>} placeholder (may be empty)
     * @param value   {@code <value>} placeholder (may be empty)
     */
    public record Notice(UUID country, String target, String value) {
    }

    public final String method;
    public final Actor actor;
    public final Params params;
    /** Country from {@code params.id} (null for global methods). */
    public final KrajInfo target;
    public final String ip;
    final List<Notice> notices = new ArrayList<>();

    public Call(final String method, final Actor actor, final Params params, final KrajInfo target, final String ip) {
        this.method = method;
        this.actor = actor;
        this.params = params;
        this.target = target;
        this.ip = ip;
    }

    public void notice(final UUID country, final String target, final String value) {
        if (country != null) {
            this.notices.add(new Notice(country, target == null ? "" : target, value == null ? "" : value));
        }
    }

    public List<Notice> notices() {
        return List.copyOf(this.notices);
    }
}
