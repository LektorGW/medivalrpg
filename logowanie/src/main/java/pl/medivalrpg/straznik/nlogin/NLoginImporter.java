// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.nlogin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import pl.medivalrpg.straznik.account.Account;
import pl.medivalrpg.straznik.account.AccountStore;
import pl.medivalrpg.straznik.account.Names;
import pl.medivalrpg.straznik.crypto.PhcArgon2;

/**
 * One-off, idempotent import of nLogin 2.x accounts ({@code nlogin.db}, SQLite, table {@code nlogin}).
 *
 * <p>The source file is never opened in place: it is copied to a temporary file which is read and deleted. Only
 * accounts with an Argon2 PHC hash (the format nLogin writes with its {@code ARGON2ID} setting, see README) are
 * imported, with nick, offline UUID, last address and dates. Existing Straznik accounts are never overwritten.
 */
public final class NLoginImporter {

    private final Pattern nickPattern;

    public NLoginImporter(final Pattern nickPattern) {
        this.nickPattern = nickPattern;
    }

    /** Row of the nLogin table needed for the import. */
    record Row(String name, String uniqueId, String mojangId, String bedrockId, String password, String lastIp,
               long lastSeen, long created) {
    }

    /**
     * @param apply {@code false} = dry run (report only)
     */
    public ImportReport run(final Path source, final AccountStore store, final boolean apply) throws IOException, SQLException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Brak pliku " + source);
        }
        final ImportReport report = new ImportReport();
        report.sourceSha256 = sha256(source);
        final List<Row> rows = readRows(source);
        report.rows = rows.size();

        // newest row wins when nLogin has the same nick twice (different case)
        final Map<String, Row> byKey = new LinkedHashMap<>();
        for (final Row r : rows) {
            if (r.name() == null || !this.nickPattern.matcher(r.name()).matches()) {
                report.skip(ImportReport.SKIP_INVALID_NICK);
                continue;
            }
            final Row previous = byKey.get(Names.key(r.name()));
            if (previous != null) {
                report.skip(ImportReport.SKIP_DUPLICATE_IN_SOURCE);
                if (r.lastSeen() <= previous.lastSeen()) {
                    continue;
                }
            }
            byKey.put(Names.key(r.name()), r);
        }

        final List<Account> toWrite = new ArrayList<>();
        for (final Row r : byKey.values()) {
            if (r.password() == null || r.password().isBlank()) {
                report.skip(ImportReport.SKIP_NO_PASSWORD);
                continue;
            }
            final Optional<PhcArgon2.Parsed> parsed = PhcArgon2.parse(r.password());
            if (parsed.isEmpty()) {
                report.format("inny/nieznany");
                report.skip(ImportReport.SKIP_UNSUPPORTED_HASH);
                continue;
            }
            final PhcArgon2.Parsed p = parsed.get();
            report.format(p.type().phcName + " v=" + p.version() + " m=" + p.memoryKiB() + " t=" + p.iterations() + " p=" + p.lanes());
            final UUID offline = Names.offlineUuid(r.name());
            final UUID stored = Names.parseCompactUuid(r.uniqueId());
            if (stored != null && !stored.equals(offline)) {
                report.warn(ImportReport.WARN_UUID_MISMATCH);
            }
            final Optional<Account> existing = store.find(r.name());
            if (existing.isPresent()) {
                final Account e = existing.get();
                if (!e.nick().equals(r.name())) {
                    report.warn(ImportReport.WARN_CASE_DIFFERS);
                }
                if (e.hash().equals(r.password())) {
                    report.alreadyImported++;
                } else {
                    report.skip(ImportReport.SKIP_EXISTS_DIFFERENT);
                }
                continue;
            }
            final long created = r.created() > 0 ? r.created() : System.currentTimeMillis();
            toWrite.add(new Account(r.name(), offline, r.password(), Account.SCHEME_NLOGIN, Account.SOURCE_NLOGIN,
                r.lastIp(), r.lastIp(), created, Math.max(0, r.lastSeen()), created));
            report.imported++;
        }
        if (apply && !toWrite.isEmpty()) {
            store.putAllNow(toWrite);
        }
        report.applied = apply;
        report.finishedAt = System.currentTimeMillis();
        return report;
    }

    static List<Row> readRows(final Path source) throws IOException, SQLException {
        final Path tmp = Files.createTempFile("straznik-nlogin-", ".db");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            // a -wal file next to the source means nLogin did not checkpoint: copy it too, otherwise rows are missing
            final Path wal = source.resolveSibling(source.getFileName() + "-wal");
            if (Files.isRegularFile(wal)) {
                Files.copy(wal, tmp.resolveSibling(tmp.getFileName() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (final ClassNotFoundException e) {
                throw new SQLException("Brak sterownika SQLite", e);
            }
            final List<Row> rows = new ArrayList<>();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.toAbsolutePath());
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_name, unique_id, mojang_id, bedrock_id, password, last_ip, "
                     + "last_seen, creation_date FROM nlogin")) {
                while (rs.next()) {
                    rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getLong(7), rs.getLong(8)));
                }
            }
            return rows;
        } finally {
            Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName() + "-wal"));
            Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName() + "-shm"));
            Files.deleteIfExists(tmp);
        }
    }

    static String sha256(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
