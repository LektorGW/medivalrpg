// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import java.sql.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** Confined to one worker. SQLite UNIQUE constraints protect the 1:1 link even across restarts. */
public final class LinkStore implements AutoCloseable {
    private record Pending(UUID player, long expires) {}
    private final Connection db;
    private final LongSupplier clock;
    private final Map<String,Pending> pending = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    public LinkStore(String url, LongSupplier clock) throws SQLException {
        this.clock = clock;
        try { Class.forName("org.sqlite.JDBC"); }
        catch(ClassNotFoundException e) { throw new SQLException("Sterownik SQLite niedostępny.",e); }
        db = DriverManager.getConnection(url);
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA synchronous=FULL");
            s.execute("CREATE TABLE IF NOT EXISTS links (minecraft TEXT PRIMARY KEY, discord TEXT NOT NULL UNIQUE)");
            s.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS role_grants (discord TEXT NOT NULL, role TEXT NOT NULL, granted_at INTEGER NOT NULL, PRIMARY KEY(discord,role))");
        }
    }
    public String issue(UUID player) throws SQLException {
        invalidate(player);
        if (links().containsKey(player)) throw new IllegalStateException("Konto jest już powiązane. Najpierw /discord odlacz.");
        pending.values().removeIf(p -> p.expires <= clock.getAsLong());
        if (pending.size() >= 1000) throw new IllegalStateException("Za dużo oczekujących kodów.");
        byte[] bytes = new byte[10]; random.nextBytes(bytes);
        String code = HexFormat.of().formatHex(bytes);
        pending.put(hash(code), new Pending(player, clock.getAsLong() + 300_000)); return code;
    }
    public UUID redeem(String code, String discord) throws SQLException {
        if (!discord.matches("[0-9]{15,22}") || code == null || !code.matches("[a-fA-F0-9]{20}")) return null;
        Pending p = pending.get(hash(code.toLowerCase(Locale.ROOT)));
        if (p == null || p.expires <= clock.getAsLong()) return null;
        try (PreparedStatement s = db.prepareStatement("INSERT OR IGNORE INTO links VALUES (?,?)")) {
            s.setString(1,p.player.toString()); s.setString(2,discord);
            if (s.executeUpdate() != 1) return null;
        }
        invalidate(p.player); return p.player;
    }
    public void invalidate(UUID id) { pending.values().removeIf(p -> p.player.equals(id)); }
    public void unlink(UUID id) throws SQLException {
        invalidate(id);
        try (PreparedStatement s=db.prepareStatement("DELETE FROM links WHERE minecraft=?")) { s.setString(1,id.toString()); s.executeUpdate(); }
    }
    public Map<UUID,String> links() throws SQLException {
        Map<UUID,String> out=new HashMap<>();
        try (Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM links")) {
            while(r.next()) out.put(UUID.fromString(r.getString(1)),r.getString(2));
        } return out;
    }
    public Map<String,String> settings() throws SQLException {
        Map<String,String> out=new HashMap<>();
        try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM settings")) {
            while(r.next()) out.put(r.getString(1),r.getString(2));
        } return out;
    }
    public void put(String key,String value) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("INSERT OR REPLACE INTO settings VALUES (?,?)")) {
            s.setString(1,key); s.setString(2,value); s.executeUpdate();
        }
    }
    public void recordGrant(String discord,String role) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("INSERT OR IGNORE INTO role_grants VALUES (?,?,?)")){s.setString(1,discord);s.setString(2,role);s.setLong(3,clock.getAsLong());s.executeUpdate();}
    }
    public Set<String> grantedRoles(String discord) throws SQLException {
        Set<String> result=new HashSet<>();try(PreparedStatement s=db.prepareStatement("SELECT role FROM role_grants WHERE discord=?")){s.setString(1,discord);try(ResultSet r=s.executeQuery()){while(r.next())result.add(r.getString(1));}}return result;
    }
    public void forgetGrant(String discord,String role) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("DELETE FROM role_grants WHERE discord=? AND role=?")){s.setString(1,discord);s.setString(2,role);s.executeUpdate();}
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Override public void close() throws SQLException { db.close(); }
}
