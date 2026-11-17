// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmsData;

/**
 * Warnings about members of one company sharing an address (possible alt accounts on the offline-mode server). Only
 * salted hashes are kept, never the addresses; no automatic penalties — the owner sees the warning in the panel.
 */
public final class AltWatch {

    private static final int KEEP = 8;
    private final FirmsData data;

    public AltWatch(final FirmsData data) {
        this.data = data;
        if (data.addressSalt == null || data.addressSalt.isBlank()) {
            final byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            data.addressSalt = HexFormat.of().formatHex(salt);
        }
    }

    public String hash(final String address) {
        try {
            final MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(this.data.addressSalt.getBytes(StandardCharsets.UTF_8));
            d.update(address.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d.digest()).substring(0, 16);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Remembers an address of a player (hashed), keeping the last few distinct ones. */
    public void seen(final String player, final String address, final long now) {
        if (address == null || address.isBlank()) {
            return;
        }
        final String h = this.hash(address.strip());
        final List<String> list = this.data.addresses.computeIfAbsent(player, k -> new ArrayList<>());
        list.removeIf(e -> e.startsWith(h + "|"));
        list.add(h + "|" + now);
        if (list.size() > KEEP) {
            list.subList(0, list.size() - KEEP).clear();
        }
    }

    private Set<String> hashes(final String player) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String e : this.data.addresses.getOrDefault(player, List.of())) {
            final int bar = e.indexOf('|');
            out.add(bar > 0 ? e.substring(0, bar) : e);
        }
        return out;
    }

    /** Groups of members of the company that share at least one address hash (each group: member names). */
    public List<List<String>> sharedAddresses(final Firm f) {
        final Map<String, Set<String>> byHash = new LinkedHashMap<>();
        for (final Firm.Member m : f.members.values()) {
            for (final String h : this.hashes(m.uuid)) {
                byHash.computeIfAbsent(h, k -> new LinkedHashSet<>()).add(m.name);
            }
        }
        final List<List<String>> out = new ArrayList<>();
        for (final Set<String> names : byHash.values()) {
            if (names.size() > 1) {
                final List<String> g = List.copyOf(names);
                if (!out.contains(g)) {
                    out.add(g);
                }
            }
        }
        return out;
    }

    /** Do two players share an address (e.g. a loan between alts)? */
    public boolean share(final String a, final String b) {
        final Set<String> ha = this.hashes(a);
        for (final String h : this.hashes(b)) {
            if (ha.contains(h)) {
                return true;
            }
        }
        return false;
    }
}
