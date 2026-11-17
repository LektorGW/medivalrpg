// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.account;

import java.util.UUID;

/**
 * One account. {@code nick} keeps the registered letter case; {@code uuid} is the offline UUID of that exact nick.
 * {@code hash} is a PHC Argon2 string, never shown anywhere. {@code scheme}: {@link #SCHEME_NLOGIN} for a hash
 * imported from nLogin (rehashed on the first successful login), {@link #SCHEME_ARGON2ID} for our own hashes.
 */
public record Account(String nick, UUID uuid, String hash, String scheme, String source, String registerIp,
                      String lastIp, long created, long lastLogin, long passwordChanged) {

    public static final String SCHEME_NLOGIN = "nlogin";
    public static final String SCHEME_ARGON2ID = "argon2id";

    public static final String SOURCE_REGISTER = "rejestracja";
    public static final String SOURCE_NLOGIN = "nlogin";
    public static final String SOURCE_ADMIN = "admin";

    public String key() {
        return Names.key(this.nick);
    }

    public Account withHash(final String newHash, final String newScheme, final long when) {
        return new Account(this.nick, this.uuid, newHash, newScheme, this.source, this.registerIp, this.lastIp,
            this.created, this.lastLogin, when);
    }

    public Account withLogin(final String ip, final long when) {
        return new Account(this.nick, this.uuid, this.hash, this.scheme, this.source, this.registerIp,
            ip == null ? this.lastIp : ip, this.created, when, this.passwordChanged);
    }

    /** No hash in the string form (records print every field by default). */
    @Override
    public String toString() {
        return "Account[" + this.nick + ", " + this.uuid + ", " + this.scheme + ", " + this.source + "]";
    }
}
