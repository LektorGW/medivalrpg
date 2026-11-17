// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.nlogin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Random;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.straznik.account.Account;
import pl.medivalrpg.straznik.account.AccountStore;
import pl.medivalrpg.straznik.account.Names;
import pl.medivalrpg.straznik.crypto.Argon2;
import pl.medivalrpg.straznik.crypto.PasswordHasher;
import pl.medivalrpg.straznik.crypto.PhcArgon2;

/**
 * Import/export against a SYNTHETIC nLogin database with the exact production schema (nLogin 2.0.25, SQLite) and
 * hashes made by our own implementation of nLogin's algorithm (argon2-jvm: Argon2id v19, m=32768 KiB, t=5, p=1,
 * 16-byte salt, 32-byte tag, UTF-8). No real data is used.
 */
class NLoginMigrationTest {

    static final String NLOGIN_SCHEMA = "CREATE TABLE nlogin (ai INTEGER PRIMARY KEY, `last_name` VARCHAR(255) NOT NULL, "
        + "`unique_id` VARCHAR(32) UNIQUE, `mojang_id` VARCHAR(32) UNIQUE, `bedrock_id` VARCHAR(32) UNIQUE, "
        + "`password` VARCHAR(255), `last_ip` VARCHAR(60) DEFAULT '127.0.0.1', `last_seen` TIMESTAMP, "
        + "`creation_date` TIMESTAMP, `email` VARCHAR(255), `discord` VARCHAR(255), `settings` TEXT)";
    static final Pattern NICK = Pattern.compile("[a-zA-Z0-9_]{3,16}");

    @TempDir
    Path dir;
    private Path nlogin;
    private static String hashAlfa;
    private static String hashBeta;

    /** nLogin 2.0.25 production parameters. */
    static String nloginHash(final String password, final long seed) {
        final byte[] salt = new byte[16];
        new Random(seed).nextBytes(salt);
        return PhcArgon2.encode(Argon2.Type.ARGON2ID, password, salt, 32768, 5, 1, 32);
    }

    @BeforeEach
    void createSyntheticNlogin() throws Exception {
        if (hashAlfa == null) {
            hashAlfa = nloginHash("Alfa-Haslo-1", 1);
            hashBeta = nloginHash("Beta-Hasło-2ą", 2);
        }
        this.nlogin = this.dir.resolve("nlogin.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + this.nlogin);
             Statement st = c.createStatement()) {
            st.execute(NLOGIN_SCHEMA);
            st.execute("CREATE TABLE nlogin_data (ai INTEGER PRIMARY KEY, `key` VARCHAR(255) NOT NULL UNIQUE, `value` BLOB NOT NULL)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO nlogin (last_name, unique_id, mojang_id, password, "
                + "last_ip, last_seen, creation_date, settings) VALUES (?,?,?,?,?,?,?,?)")) {
                insert(ps, "TestAlfa", Names.compactUuid(Names.offlineUuid("TestAlfa")), null, hashAlfa, 1000);
                insert(ps, "Test_Beta", Names.compactUuid(Names.offlineUuid("Test_Beta")), null, hashBeta, 2000);
                insert(ps, "PremiumGosc", Names.compactUuid(Names.offlineUuid("PremiumGosc")), "0123456789abcdef0123456789abcdef", null, 3000);
                insert(ps, "zly nick!", "00000000000000000000000000000001", null, hashAlfa, 4000);
                insert(ps, "Stary", Names.compactUuid(Names.offlineUuid("Stary")), null, "$SHA$abcdef$0123456789", 5000);
                insert(ps, "Duplikat", Names.compactUuid(Names.offlineUuid("Duplikat")), null, hashAlfa, 6000);
                insert(ps, "DUPLIKAT", Names.compactUuid(Names.offlineUuid("DUPLIKAT")), null, hashBeta, 7000);
                insert(ps, "InnyUuid", "ffffffffffffffffffffffffffffffff", null, hashBeta, 8000);
            }
        }
    }

    private static void insert(final PreparedStatement ps, final String name, final String uid, final String mojang,
                               final String pw, final long seen) throws Exception {
        ps.setString(1, name);
        ps.setString(2, uid);
        ps.setString(3, mojang);
        ps.setString(4, pw);
        ps.setString(5, "10.0.0." + (seen / 1000));
        ps.setLong(6, 1_760_000_000_000L + seen);
        ps.setLong(7, 1_750_000_000_000L + seen);
        ps.setString(8, "{\"settings\":[{\"k\":\"lang\"}],\"version\":0}");
        ps.executeUpdate();
    }

    private AccountStore store() throws Exception {
        final AccountStore s = new AccountStore(this.dir.resolve("straznik/konta.db"), Logger.getLogger("test"));
        s.open();
        return s;
    }

    @Test
    void importCountsSkipsAndIsIdempotent() throws Exception {
        try (AccountStore store = this.store()) {
            final ImportReport dry = new NLoginImporter(NICK).run(this.nlogin, store, false);
            assertEquals(8, dry.rows);
            assertEquals(0, store.size(), "dry run writes nothing");

            final ImportReport r = new NLoginImporter(NICK).run(this.nlogin, store, true);
            assertEquals(8, r.rows);
            assertEquals(4, r.imported, "TestAlfa, Test_Beta, DUPLIKAT (newer), InnyUuid");
            assertEquals(1, r.skipped.get(ImportReport.SKIP_NO_PASSWORD));
            assertEquals(1, r.skipped.get(ImportReport.SKIP_INVALID_NICK));
            assertEquals(1, r.skipped.get(ImportReport.SKIP_UNSUPPORTED_HASH));
            assertEquals(1, r.skipped.get(ImportReport.SKIP_DUPLICATE_IN_SOURCE));
            assertEquals(1, r.warnings.get(ImportReport.WARN_UUID_MISMATCH));
            assertEquals(5, r.hashFormats.values().stream().mapToInt(Integer::intValue).sum());
            assertFalse(r.toPrettyJson().contains("TestAlfa"), "report has no nicks");
            assertFalse(r.toPrettyJson().contains("argon2id$v=19$m=32768,t=5,p=1$"), "report has no hashes");

            final Account alfa = store.find("testalfa").orElseThrow();
            assertEquals("TestAlfa", alfa.nick());
            assertEquals(Names.offlineUuid("TestAlfa"), alfa.uuid());
            assertEquals(Account.SCHEME_NLOGIN, alfa.scheme());
            assertEquals("10.0.0.1", alfa.lastIp());
            assertEquals("DUPLIKAT", store.find("duplikat").orElseThrow().nick(), "newer row wins");
            assertEquals(Names.offlineUuid("InnyUuid"), store.find("InnyUuid").orElseThrow().uuid());

            // the imported nLogin hash verifies the same password, byte for byte
            final PasswordHasher h = new PasswordHasher(32768, 5, 1, 1);
            assertTrue(h.verify(alfa.hash(), "Alfa-Haslo-1"));
            assertFalse(h.verify(alfa.hash(), "alfa-haslo-1"));
            assertTrue(h.verify(store.find("Test_Beta").orElseThrow().hash(), "Beta-Hasło-2ą"));

            final ImportReport again = new NLoginImporter(NICK).run(this.nlogin, store, true);
            assertEquals(0, again.imported);
            assertEquals(4, again.alreadyImported);

            // a rehashed (changed) account is never overwritten by a later import
            store.put(alfa.withHash(PhcArgon2.hash(Argon2.Type.ARGON2ID, "Nowe-Haslo-7", 64, 1, 1), Account.SCHEME_ARGON2ID, 1));
            final ImportReport third = new NLoginImporter(NICK).run(this.nlogin, store, true);
            assertEquals(1, third.skipped.get(ImportReport.SKIP_EXISTS_DIFFERENT));
            assertEquals(Account.SCHEME_ARGON2ID, store.find("TestAlfa").orElseThrow().scheme());
        }
    }

    @Test
    void storePersistsAndCountsIp() throws Exception {
        try (AccountStore store = this.store()) {
            final String h = PhcArgon2.hash(Argon2.Type.ARGON2ID, "x-y-z-1", 64, 1, 1);
            assertTrue(store.putIfAbsent(new Account("Nowy", Names.offlineUuid("Nowy"), h, Account.SCHEME_ARGON2ID,
                Account.SOURCE_REGISTER, "1.1.1.1", "1.1.1.1", 1, 1, 1)));
            assertFalse(store.putIfAbsent(new Account("NOWY", Names.offlineUuid("NOWY"), h, Account.SCHEME_ARGON2ID,
                Account.SOURCE_REGISTER, "1.1.1.1", "1.1.1.1", 1, 1, 1)), "case-insensitive key");
            store.put(new Account("Drugi", Names.offlineUuid("Drugi"), h, Account.SCHEME_ARGON2ID, Account.SOURCE_REGISTER,
                "2.2.2.2", "1.1.1.1", 1, 1, 1));
            assertEquals(2, store.countByIp("1.1.1.1"));
            assertEquals(1, store.countByIp("2.2.2.2"));
            store.setMeta("import.nlogin", "x");
            assertTrue(store.remove("drugi"));
            store.flush();
            assertFalse(store.toString().contains(h));
        }
        try (AccountStore again = this.store()) {
            assertEquals(1, again.size());
            assertEquals("Nowy", again.find("nowy").orElseThrow().nick());
            assertEquals("x", again.meta("import.nlogin").orElseThrow());
        }
    }

    @Test
    void exportWritesCopyForRollback() throws Exception {
        final byte[] original = Files.readAllBytes(this.nlogin);
        try (AccountStore store = this.store()) {
            new NLoginImporter(NICK).run(this.nlogin, store, true);
            final String changed = PhcArgon2.hash(Argon2.Type.ARGON2ID, "Zmienione-9", 64, 1, 1);
            store.put(store.find("TestAlfa").orElseThrow().withHash(changed, Account.SCHEME_ARGON2ID, 5));
            store.put(new Account("Nowicjusz", Names.offlineUuid("Nowicjusz"), changed, Account.SCHEME_ARGON2ID,
                Account.SOURCE_REGISTER, "3.3.3.3", "3.3.3.3", 10, 11, 10));
            store.flush();
            final Path out = this.dir.resolve("eksport/nlogin-export.db");
            final NLoginExporter.Result r = NLoginExporter.export(this.nlogin, out, store.snapshot());
            assertEquals(4, r.updated());
            assertEquals(1, r.inserted());
            assertEquals(4, r.onlyInNLogin(), "premium, invalid nick, unsupported hash, older duplicate row stay as they were");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + out);
                 PreparedStatement ps = c.prepareStatement("SELECT password, unique_id, settings FROM nlogin WHERE last_name=?")) {
                ps.setString(1, "TestAlfa");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(changed, rs.getString(1));
                    assertTrue(PhcArgon2.verify(rs.getString(1), "Zmienione-9"));
                }
                ps.setString(1, "Nowicjusz");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(Names.compactUuid(Names.offlineUuid("Nowicjusz")), rs.getString(2));
                    assertEquals(NLoginExporter.EMPTY_SETTINGS, rs.getString(3));
                }
            }
        }
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(this.nlogin)), "source nlogin.db untouched");
    }
}
