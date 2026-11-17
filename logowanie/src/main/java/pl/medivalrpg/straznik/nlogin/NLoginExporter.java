// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.nlogin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pl.medivalrpg.straznik.account.Account;
import pl.medivalrpg.straznik.account.Names;

/**
 * Rollback helper: writes the current Straznik accounts into a COPY of an nLogin database, so nLogin can be put
 * back without losing registrations or password changes made in the meantime. Our hashes are Argon2id PHC strings,
 * which nLogin verifies with the parameters stored in the string. The source file is never modified.
 */
public final class NLoginExporter {

    /** Counts only. */
    public record Result(int updated, int inserted, int onlyInNLogin, Path output) {
    }

    /** {@code settings} of an account without preferences in the nLogin 2.x format. */
    static final String EMPTY_SETTINGS = "{\"settings\":[],\"version\":0}";

    private NLoginExporter() {
    }

    public static Result export(final Path source, final Path output, final List<Account> accounts) throws IOException, SQLException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        final Path tmp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
        final Path wal = source.resolveSibling(source.getFileName() + "-wal");
        if (Files.isRegularFile(wal)) {
            Files.copy(wal, tmp.resolveSibling(tmp.getFileName() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (final ClassNotFoundException e) {
            throw new SQLException("Brak sterownika SQLite", e);
        }
        int updated = 0;
        int inserted = 0;
        int onlyInNLogin;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.toAbsolutePath())) {
            c.setAutoCommit(false);
            // same nick in several rows (different case): the row with the exact nick is updated, the others stay
            final Map<String, Long> rowsByKey = new HashMap<>();
            final Map<String, Long> exact = new HashMap<>();
            int rowCount = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT ai, last_name FROM nlogin");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rowCount++;
                    rowsByKey.putIfAbsent(Names.key(rs.getString(2)), rs.getLong(1));
                    exact.put(rs.getString(2), rs.getLong(1));
                }
            }
            for (final Account a : accounts) {
                final Long own = exact.get(a.nick());
                if (own != null) {
                    rowsByKey.put(a.key(), own);
                }
            }
            final int matched = (int) accounts.stream().filter(a -> rowsByKey.containsKey(a.key())).count();
            try (PreparedStatement up = c.prepareStatement(
                     "UPDATE nlogin SET last_name=?, unique_id=?, password=?, last_ip=?, last_seen=? WHERE ai=?");
                 PreparedStatement ins = c.prepareStatement("INSERT INTO nlogin (last_name, unique_id, password, last_ip, "
                     + "last_seen, creation_date, settings) VALUES (?,?,?,?,?,?,?)")) {
                for (final Account a : accounts) {
                    final Long ai = rowsByKey.remove(a.key());
                    final String ip = a.lastIp() == null ? "127.0.0.1" : a.lastIp();
                    if (ai != null) {
                        up.setString(1, a.nick());
                        up.setString(2, Names.compactUuid(a.uuid()));
                        up.setString(3, a.hash());
                        up.setString(4, ip);
                        up.setLong(5, Math.max(a.lastLogin(), a.created()));
                        up.setLong(6, ai);
                        up.executeUpdate();
                        updated++;
                    } else {
                        ins.setString(1, a.nick());
                        ins.setString(2, Names.compactUuid(a.uuid()));
                        ins.setString(3, a.hash());
                        ins.setString(4, ip);
                        ins.setLong(5, Math.max(a.lastLogin(), a.created()));
                        ins.setLong(6, a.created());
                        ins.setString(7, EMPTY_SETTINGS);
                        ins.executeUpdate();
                        inserted++;
                    }
                }
            }
            onlyInNLogin = rowCount - matched;
            c.commit();
            try (var st = c.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        }
        Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName() + "-wal"));
        Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName() + "-shm"));
        Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Result(updated, inserted, onlyInNLogin, output);
    }
}
