// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.embassy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.EmbassyInfo;
import pl.medivalrpg.kraje.api.KrajeResult.Status;
import pl.medivalrpg.kraje.api.event.EmbassyClosedEvent;
import pl.medivalrpg.kraje.api.event.EmbassyClosedEvent.Reason;
import pl.medivalrpg.kraje.api.event.EmbassyOpenedEvent;
import pl.medivalrpg.kraje.api.event.EmbassyRequestEvent;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.economy.TreasuryService;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.manager.Notify;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Embassy;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.util.Format;

/**
 * Player embassies: requests (host ruler accepts, NPC hosts decide by config), opening with the fee paid to the host,
 * closing by the guest, expulsion with a notice period, automatic closing on war, lost land and disbanding, and the
 * diplomat spawn point. Rule violations throw {@link KrajeException}. Main thread only.
 */
public final class EmbassyService {

    /** Result of {@link #request}. */
    public enum Outcome { REQUESTED, OPENED }

    private record Pair(UUID guest, UUID host) {
    }

    private record Request(ChunkPos chunk, SpawnPoint spot, long expiresAt) {
    }

    private final KrajePlugin plugin;
    private final Map<Pair, Request> requests = new HashMap<>();

    public EmbassyService(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    private CountryManager manager() {
        return this.plugin.manager();
    }

    private KrajeConfig cfg() {
        return this.plugin.cfg();
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    private TreasuryService treasury() {
        return this.plugin.treasury();
    }

    // ------------------------------------------------------------------ rules

    public EmbassyRules.State state() {
        final CountryManager m = this.manager();
        return new EmbassyRules.State() {
            @Override
            public UUID ownerAt(final ChunkPos pos) {
                return m.ownerAt(pos.world(), pos.key());
            }

            @Override
            public Relation relation(final UUID a, final UUID b) {
                return m.relation(a, b);
            }

            @Override
            public boolean hasEmbassy(final UUID guest, final UUID host) {
                final Country g = m.get(guest);
                return g != null && g.embassyIn(host) != null;
            }

            @Override
            public int embassiesOf(final UUID guest) {
                final Country g = m.get(guest);
                return g == null ? 0 : g.embassiesMap().size();
            }

            @Override
            public int embassiesIn(final UUID host) {
                return m.embassyGuestsIn(host).size();
            }

            @Override
            public boolean embassyAt(final ChunkPos pos) {
                return m.embassyAt(pos.world(), pos.key()) != null;
            }

            @Override
            public boolean isCapital(final UUID country, final ChunkPos pos) {
                final Country c = m.get(country);
                return c != null && pos.equals(c.capital());
            }

            @Override
            public boolean sieged(final ChunkPos pos) {
                return EmbassyService.this.plugin.sieges() != null && EmbassyService.this.plugin.sieges().siegeAt(pos) != null;
            }

            @Override
            public boolean protectedArea(final ChunkPos pos) {
                return EmbassyService.this.plugin.protectedAreas().isProtected(pos.world(), pos.x(), pos.z());
            }
        };
    }

    private void requireOpen(final Country guest, final Country host, final ChunkPos pos) {
        final EmbassyRules.Verdict v = EmbassyRules.open(this.cfg().embassyRules, this.state(), guest.id(),
            host == null ? null : host.id(), pos);
        final TagResolver h = host == null ? Notify.unparsed("country", "?") : Notify.country(host);
        switch (v) {
            case ALLOWED -> {
            }
            case DISABLED -> throw new KrajeException(Status.DENIED, "embassy-disabled");
            case NOT_HOST_LAND -> throw new KrajeException(Status.INVALID, "embassy-not-host-land", h);
            case SAME_COUNTRY -> throw new KrajeException(Status.INVALID, "cannot-target-own-country");
            case BAD_RELATION -> throw new KrajeException(Status.DENIED, "embassy-bad-relation", h);
            case ALREADY_OPEN -> throw new KrajeException(Status.INVALID, "embassy-already-open", h);
            case LIMIT_GUEST -> throw new KrajeException(Status.LIMIT_REACHED, "embassy-limit-guest",
                Notify.unparsed("max", this.cfg().embassyRules.maxPerCountry()));
            case LIMIT_HOST -> throw new KrajeException(Status.LIMIT_REACHED, "embassy-limit-host", h,
                Notify.unparsed("max", this.cfg().embassyRules.maxHosted()));
            case CAPITAL -> throw new KrajeException(Status.DENIED, "embassy-capital");
            case CHUNK_TAKEN -> throw new KrajeException(Status.ALREADY_CLAIMED, "embassy-chunk-taken");
            case SIEGED -> throw new KrajeException(Status.DENIED, "embassy-sieged");
            case PROTECTED -> throw new KrajeException(Status.DENIED, "embassy-protected");
        }
    }

    // ------------------------------------------------------------------ requests

    /**
     * {@code /kraj ambasada otworz <kraj>}: {@code guest} asks the owner of the chunk at {@code spot} (which must be
     * {@code expectedHost} when given) for an embassy there. NPC hosts answer at once ({@code embassy.npc-auto-decide}).
     */
    public Outcome request(final Country guest, final Country expectedHost, final Location spot) {
        if (spot == null || spot.getWorld() == null) {
            throw new KrajeException(Status.INVALID, "api-invalid-location");
        }
        final ChunkPos pos = ChunkPos.of(spot);
        final Country host = this.manager().countryAt(pos);
        if (expectedHost != null && host != expectedHost) {
            throw new KrajeException(Status.INVALID, "embassy-not-host-land", Notify.country(expectedHost));
        }
        this.requireOpen(guest, host, pos);
        this.treasury().requireFunds(guest, this.cfg().embassyOpenCost);
        final long expiresAt = System.currentTimeMillis() + this.cfg().embassyRequestExpiryMillis;
        if (host.npc() && this.cfg().embassyNpcAutoDecide) {
            if (!EmbassyRules.npcAccepts(this.cfg().embassyRules, guest.relationTo(host.id()))) {
                throw new KrajeException(Status.DENIED, "embassy-npc-refused", Notify.country(host));
            }
            this.open(guest, host, pos, SpawnPoint.of(spot));
            return Outcome.OPENED;
        }
        this.requests.put(new Pair(guest.id(), host.id()), new Request(pos, SpawnPoint.of(spot), expiresAt));
        Notify.leaders(this.messages(), host, "embassy-request-received", Notify.pair(guest, host),
            Notify.unparsed("x", pos.centerBlockX()), Notify.unparsed("z", pos.centerBlockZ()),
            Notify.unparsed("minutes", Math.max(1, this.cfg().embassyRequestExpiryMillis / 60_000L)),
            Placeholder.styling("accept", ClickEvent.runCommand("/kraj ambasada przyjmij " + guest.name())),
            Placeholder.styling("refuse", ClickEvent.runCommand("/kraj ambasada odrzuc " + guest.name())));
        Bukkit.getPluginManager().callEvent(new EmbassyRequestEvent(guest.id(), host.id(), pos.world(), pos.x(), pos.z(),
            host.npc(), expiresAt));
        return Outcome.REQUESTED;
    }

    /** Valid requests made to {@code host} (suggestions). */
    public List<UUID> requestsTo(final UUID host) {
        final long now = System.currentTimeMillis();
        this.requests.values().removeIf(r -> r.expiresAt() < now);
        return this.requests.keySet().stream().filter(p -> p.host().equals(host)).map(Pair::guest).toList();
    }

    /** {@code /kraj ambasada przyjmij|odrzuc <kraj>} and the API: {@code host} answers {@code guest}. */
    public void respond(final Country host, final Country guest, final boolean accept) {
        final Request req = this.requests.remove(new Pair(guest.id(), host.id()));
        if (req == null || req.expiresAt() < System.currentTimeMillis()) {
            throw new KrajeException(Status.NO_PROPOSAL, "embassy-no-request", Notify.country(guest));
        }
        if (!accept) {
            Notify.leaders(this.messages(), guest, "embassy-refused", Notify.country(host));
            return;
        }
        this.requireOpen(guest, host, req.chunk());
        this.open(guest, host, req.chunk(), req.spot());
    }

    private void open(final Country guest, final Country host, final ChunkPos pos, final SpawnPoint spot) {
        final long cost = this.cfg().embassyOpenCost;
        if (this.treasury().active() && cost > 0) {
            this.treasury().requireFunds(guest, cost);
            final long moved = this.treasury().transferBetween(guest, host, cost, "embassy");
            if (moved < cost) {
                if (moved > 0) {
                    this.treasury().transferBetween(host, guest, moved, "embassy");
                }
                throw new KrajeException(Status.INSUFFICIENT_FUNDS, "treasury-insufficient",
                    Notify.unparsed("cost", this.treasury().format(cost)),
                    Notify.unparsed("balance", this.treasury().format(this.treasury().balance(guest))));
            }
        }
        this.manager().addEmbassy(guest, new Embassy(host.id(), pos, spot, System.currentTimeMillis(), 0L));
        this.requests.remove(new Pair(guest.id(), host.id()));
        final TagResolver r = TagResolver.resolver(Notify.pair(guest, host), Notify.unparsed("x", pos.centerBlockX()),
            Notify.unparsed("z", pos.centerBlockZ()), Notify.unparsed("cost", this.treasury().format(this.treasury().active() ? cost : 0)));
        Notify.members(this.messages(), guest, "embassy-opened-guest", r);
        Notify.members(this.messages(), host, "embassy-opened-host", r);
        this.plugin.getLogger().info("Ambasada " + guest.name() + " w " + host.name() + " (" + pos.world() + " " + pos.x() + "," + pos.z() + ").");
        Bukkit.getPluginManager().callEvent(new EmbassyOpenedEvent(guest.id(), host.id(), pos.world(), pos.x(), pos.z()));
    }

    // ------------------------------------------------------------------ closing

    /** {@code /kraj ambasada zamknij <kraj>}: the guest closes its embassy in {@code host}. */
    public void closeByGuest(final Country guest, final Country host) {
        if (guest.embassyIn(host.id()) == null) {
            throw new KrajeException(Status.NOT_FOUND, "embassy-none", Notify.country(host));
        }
        this.close(guest, host.id(), Reason.CLOSED);
    }

    /**
     * {@code /kraj ambasada wydal <kraj>}: the host expels the embassy of {@code guest}; it closes after
     * {@code embassy.expel-notice-hours} (at once with 0). Returns the closing time.
     */
    public long expel(final Country host, final Country guest) {
        final Embassy e = guest.embassyIn(host.id());
        if (e == null) {
            throw new KrajeException(Status.NOT_FOUND, "embassy-none-hosted", Notify.country(guest));
        }
        if (e.expelled()) {
            throw new KrajeException(Status.INVALID, "embassy-already-expelled", Notify.country(guest),
                Notify.unparsed("until", Format.dateTime(e.closesAt())));
        }
        final long now = System.currentTimeMillis();
        final long deadline = EmbassyRules.expelDeadline(this.cfg().embassyRules, now);
        if (deadline <= now) {
            this.close(guest, host.id(), Reason.EXPELLED);
            return now;
        }
        this.manager().setEmbassyClosesAt(guest, host.id(), deadline);
        final TagResolver r = TagResolver.resolver(Notify.pair(guest, host), Notify.unparsed("until", Format.dateTime(deadline)),
            Notify.unparsed("time", Format.duration(deadline - now)));
        Notify.members(this.messages(), guest, "embassy-expel-notice", r);
        Notify.leaders(this.messages(), host, "embassy-expel-sent", r);
        return deadline;
    }

    /** Closes the embassy of {@code guest} in {@code hostId} (no-op when there is none), announces it and fires the event. */
    public void close(final Country guest, final UUID hostId, final Reason reason) {
        final Embassy e = this.manager().removeEmbassy(guest, hostId);
        if (e == null) {
            return;
        }
        final Country host = this.manager().get(hostId);
        final TagResolver r = host == null
            ? TagResolver.resolver(Notify.unparsed("a", Notify.displayName(guest)), Placeholder.styling("color_a", guest.textColor()),
                Notify.unparsed("b", "?"), Placeholder.styling("color_b", guest.textColor()))
            : Notify.pair(guest, host);
        final String key = "embassy-closed-" + switch (reason) {
            case CLOSED -> "closed";
            case EXPELLED -> "expelled";
            case WAR -> "war";
            case LAND_LOST -> "land";
            case DISBANDED -> "gone";
            case ADMIN -> "admin";
        };
        Notify.members(this.messages(), guest, key, r);
        if (host != null) {
            Notify.members(this.messages(), host, key, r);
        }
        Bukkit.getPluginManager().callEvent(new EmbassyClosedEvent(guest.id(), hostId, reason));
    }

    /** Every minute: expelled embassies whose notice ran out, and embassies whose chunk is no longer host land. */
    public void checkExpiry() {
        final long now = System.currentTimeMillis();
        for (final Country guest : this.manager().countriesSnapshot()) {
            for (final Embassy e : List.copyOf(guest.embassiesMap().values())) {
                if (e.expelled() && now >= e.closesAt()) {
                    this.close(guest, e.host(), Reason.EXPELLED);
                } else if (!e.host().equals(this.manager().ownerAt(e.chunk().world(), e.chunk().key()))) {
                    this.close(guest, e.host(), Reason.LAND_LOST);
                }
            }
        }
        this.requests.values().removeIf(r -> r.expiresAt() < now);
    }

    /** War between {@code a} and {@code b}: their embassies at each other close. */
    public void onWar(final Country a, final Country b) {
        this.close(a, b.id(), Reason.WAR);
        this.close(b, a.id(), Reason.WAR);
        this.requests.keySet().removeIf(p -> (p.guest().equals(a.id()) && p.host().equals(b.id()))
            || (p.guest().equals(b.id()) && p.host().equals(a.id())));
    }

    /** {@code c} is about to disappear: its embassies abroad and the embassies it hosts close. */
    public void onDisband(final Country c) {
        for (final Embassy e : List.copyOf(c.embassiesMap().values())) {
            this.close(c, e.host(), Reason.DISBANDED);
        }
        for (final Country guest : this.manager().embassyGuestsIn(c.id())) {
            this.close(guest, c.id(), Reason.DISBANDED);
        }
        this.requests.keySet().removeIf(p -> p.guest().equals(c.id()) || p.host().equals(c.id()));
    }

    /** A chunk changed owner (conquest, admin, bankruptcy): an embassy on it closes unless the host still owns it. */
    public void onChunkOwnerChange(final String world, final long key, final UUID newOwner) {
        final EmbassyInfo info = this.manager().embassyAt(world, key);
        if (info != null && !info.host().equals(newOwner)) {
            final Country guest = this.manager().get(info.guest());
            if (guest != null) {
                this.close(guest, info.host(), Reason.LAND_LOST);
            }
        }
    }

    // ------------------------------------------------------------------ queries

    /** Diplomat spawn point of {@code guest}'s embassy in {@code host}. */
    public Location spot(final Country guest, final Country host) {
        final Embassy e = guest.embassyIn(host.id());
        if (e == null) {
            throw new KrajeException(Status.NOT_FOUND, "embassy-none", Notify.country(host));
        }
        final Location loc = e.spot().toLocation();
        if (loc == null) {
            throw new KrajeException(Status.INVALID, "spawn-unavailable");
        }
        return loc;
    }

    /** Hosts in which {@code guest} has embassies (main thread). */
    public List<Country> hostsOf(final Country guest) {
        final List<Country> out = new ArrayList<>();
        for (final Embassy e : guest.embassiesMap().values()) {
            final Country h = this.manager().get(e.host());
            if (h != null) {
                out.add(h);
            }
        }
        return out;
    }

    public void clear() {
        this.requests.clear();
    }
}
