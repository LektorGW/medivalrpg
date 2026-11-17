// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
public final class DeletionPermit {
    private final LongSupplier clock;private String hash,actor,fingerprint;private long expires;
    public DeletionPermit(LongSupplier clock){this.clock=clock;}
    public synchronized String issue(String actor,String fingerprint) {
        byte[] bytes=new byte[10];new SecureRandom().nextBytes(bytes);String code=HexFormat.of().formatHex(bytes);
        this.hash=hash(code);this.actor=actor;this.fingerprint=fingerprint;expires=clock.getAsLong()+600_000;return code;
    }
    public synchronized boolean consume(String code,String actor,String fingerprint) {
        if(hash==null||clock.getAsLong()>=expires||!Objects.equals(this.actor,actor)||!Objects.equals(this.fingerprint,fingerprint)||!hash.equals(hash(code)))return false;
        hash=null;return true;
    }
    private static String hash(String s) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
