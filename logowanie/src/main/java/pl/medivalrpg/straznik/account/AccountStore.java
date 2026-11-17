// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.account;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Accounts in SQLite ({@code konta.db}), all held in memory (a few hundred rows at most). Reads come from memory and
 * are thread-safe; every change is applied in memory at once and written by a single background thread in order.
 */
public final class AccountStore implements AutoCloseable {

    private static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS konta (
          nick_lower TEXT PRIMARY KEY,
          nick TEXT NOT NULL,
          uuid TEXT NOT NULL,
          hash TEXT NOT NULL,
          schemat TEXT NOT NULL,
          zrodlo TEXT NOT NULL,
          ip_rejestracji TEXT,
          ostatnie_ip TEXT,
          utworzono INTEGER NOT NULL,
          ostatnie_logowanie INTEGER NOT NULL,
          zmiana_hasla INTEGER NOT NULL
        )""";
    private static final String META = "CREATE TABLE IF NOT EXISTS meta (klucz TEXT PRIMARY KEY, wartosc TEXT NOT NULL)";
    private static final String UPSERT = """
        INSERT INTO konta (nick_lower, nick, uuid, hash, schemat, zrodlo, ip_rejestracji, ostatnie_ip, utworzono,
          ostatnie_logowanie, zmiana_hasla) VALUES (?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(nick_lower) DO UPDATE SET nick=excluded.nick, uuid=excluded.uuid, hash=excluded.hash,
          schemat=excluded.schemat, zrodlo=excluded.zrodlo, ip_rejestracji=excluded.ip_rejestracji,
          ostatnie_ip=excluded.ostatnie_ip, utworzono=excluded.utworzono, ostatnie_logowanie=excluded.ostatnie_logowanie,
          zmiana_hasla=excluded.zmiana_hasla""";

    private final Path file;
    private final Logger logger;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, String> meta = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Straznik-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;
    private volatile boolean failed;

    public AccountStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Opens (creates) the database and loads every account. Throws when the database cannot be used. */
    public void open() throws SQLException {
        try {
            Files.createDirectories(this.file.toAbsolutePath().getParent());
            Class.forName("org.sqlite.JDBC");
        } catch (final java.io.IOException | ClassNotFoundException e) {
            throw new SQLException("SQLite niedostępne: " + e, e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.file.toAbsolutePath());
        try (Statement st = this.connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute(SCHEMA);
            st.execute(META);
            try (ResultSet rs = st.executeQuery("SELECT nick, uuid, hash, schemat, zrodlo, ip_rejestracji, ostatnie_ip, "
                + "utworzono, ostatnie_logowanie, zmiana_hasla FROM konta")) {
                while (rs.next()) {
                    final Account a = new Account(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9),
                        rs.getLong(10));
                    this.accounts.put(a.key(), a);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT klucz, wartosc FROM meta")) {
                while (rs.next()) {
                    this.meta.put(rs.getString(1), rs.getString(2));
                }
            }
        }
    }

    /** {@code true} after a failed write: the plugin then refuses new logins instead of losing data silently. */
    public boolean failed() {
        return this.failed;
    }

    public Optional<Account> find(final String nick) {
        return nick == null ? Optional.empty() : Optional.ofNullable(this.accounts.get(Names.key(nick)));
    }

    public Collection<Account> all() {
        return List.copyOf(this.accounts.values());
    }

    public int size() {
        return this.accounts.size();
    }

    /** Accounts registered from or last used from this address. */
    public int countByIp(final String ip) {
        if (ip == null) {
            return 0;
        }
        final String norm = ip.toLowerCase(Locale.ROOT);
        int n = 0;
        for (final Account a : this.accounts.values()) {
            if (norm.equalsIgnoreCase(a.registerIp()) || norm.equalsIgnoreCase(a.lastIp())) {
                n++;
            }
        }
        return n;
    }

    public void put(final Account account) {
        this.accounts.put(account.key(), account);
        this.submit(() -> this.write(List.of(account)));
    }

    /** Inserts only when the key is free (atomic against a parallel register); {@code false} if taken. */
    public boolean putIfAbsent(final Account account) {
        if (this.accounts.putIfAbsent(account.key(), account) != null) {
            return false;
        }
        this.submit(() -> this.write(List.of(account)));
        return true;
    }

    /** Batch write in one transaction, waits for the disk (import). */
    public void putAllNow(final List<Account> batch) throws SQLException {
        for (final Account a : batch) {
            this.accounts.put(a.key(), a);
        }
        try {
            this.writer.submit(() -> {
                this.write(batch);
                return null;
            }).get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Przerwano zapis", e);
        } catch (final java.util.concurrent.ExecutionException e) {
            throw new SQLException("Zapis kont nie powiódł się: " + e.getCause(), e.getCause());
        }
        if (this.failed) {
            throw new SQLException("Zapis kont nie powiódł się (szczegóły w logu)");
        }
    }

    public boolean remove(final String nick) {
        final Account removed = this.accounts.remove(Names.key(nick));
        if (removed == null) {
            return false;
        }
        this.submit(() -> {
            try (PreparedStatement ps = this.connection.prepareStatement("DELETE FROM konta WHERE nick_lower=?")) {
                ps.setString(1, removed.key());
                ps.executeUpdate();
            } catch (final SQLException e) {
                this.fail("usuwanie konta " + removed.nick(), e);
            }
        });
        return true;
    }

    public Optional<String> meta(final String key) {
        return Optional.ofNullable(this.meta.get(key));
    }

    public void setMeta(final String key, final String value) {
        this.meta.put(key, value);
        this.submit(() -> {
            try (PreparedStatement ps = this.connection.prepareStatement(
                "INSERT INTO meta (klucz, wartosc) VALUES (?,?) ON CONFLICT(klucz) DO UPDATE SET wartosc=excluded.wartosc")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            } catch (final SQLException e) {
                this.fail("meta " + key, e);
            }
        });
    }

    private void write(final List<Account> batch) {
        try {
            final boolean auto = this.connection.getAutoCommit();
            this.connection.setAutoCommit(false);
            try (PreparedStatement ps = this.connection.prepareStatement(UPSERT)) {
                for (final Account a : batch) {
                    ps.setString(1, a.key());
                    ps.setString(2, a.nick());
                    ps.setString(3, a.uuid().toString());
                    ps.setString(4, a.hash());
                    ps.setString(5, a.scheme());
                    ps.setString(6, a.source());
                    ps.setString(7, a.registerIp());
                    ps.setString(8, a.lastIp());
                    ps.setLong(9, a.created());
                    ps.setLong(10, a.lastLogin());
                    ps.setLong(11, a.passwordChanged());
                    ps.addBatch();
                }
                ps.executeBatch();
                this.connection.commit();
            } catch (final SQLException e) {
                this.connection.rollback();
                throw e;
            } finally {
                this.connection.setAutoCommit(auto);
            }
        } catch (final SQLException e) {
            this.fail("zapis " + batch.size() + " kont", e);
        }
    }

    private void fail(final String what, final SQLException e) {
        this.failed = true;
        // never log the row content (hashes)
        this.logger.log(Level.SEVERE, "Błąd bazy kont (" + what + "): " + e.getMessage());
    }

    private void submit(final Runnable task) {
        try {
            this.writer.execute(task);
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            this.logger.severe("Baza kont jest już zamknięta — zmiana nie została zapisana.");
            this.failed = true;
        }
    }

    /** Waits until every queued write reached the database (tests, before export). */
    public void flush() {
        try {
            this.writer.submit(() -> null).get(30, TimeUnit.SECONDS);
        } catch (final Exception e) {
            this.logger.warning("Nie udało się poczekać na zapis bazy kont: " + e);
        }
    }

    @Override
    public void close() {
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(30, TimeUnit.SECONDS)) {
                this.logger.severe("Zapis bazy kont nie zakończył się w 30 s.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (final SQLException e) {
                this.logger.warning("Zamykanie bazy kont: " + e.getMessage());
            }
        }
    }

    /** Copy of all accounts (export/report helpers). */
    public List<Account> snapshot() {
        return new ArrayList<>(this.accounts.values());
    }
}
