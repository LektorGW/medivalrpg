// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class LinkStoreTest {
    @TempDir Path directory;
    AtomicLong now=new AtomicLong(1000);
    LinkStore store;
    UUID player=UUID.randomUUID();
    static final String DISCORD="1000000000000000846";
    String url() {return "jdbc:sqlite:"+directory.resolve("links.db");}
    @BeforeEach void open() throws Exception {store=new LinkStore(url(),now::get);}
    @AfterEach void close() throws Exception {store.close();}
    @Test void codeIsRandomAndSingleUse() throws Exception {
        String code=store.issue(player);assertTrue(code.matches("[a-f0-9]{20}"));
        assertEquals(player,store.redeem(code,DISCORD));assertNull(store.redeem(code,DISCORD));
    }
    @Test void exactExpiryFails() throws Exception {String c=store.issue(player);now.addAndGet(300000);assertNull(store.redeem(c,DISCORD));}
    @Test void beforeExpirySucceeds() throws Exception {String c=store.issue(player);now.addAndGet(299999);assertEquals(player,store.redeem(c,DISCORD));}
    @Test void newCodeRevokesPrevious() throws Exception {String old=store.issue(player),fresh=store.issue(player);assertNotEquals(old,fresh);assertNull(store.redeem(old,DISCORD));assertEquals(player,store.redeem(fresh,DISCORD));}
    @Test void invalidationOnQuit() throws Exception {String c=store.issue(player);store.invalidate(player);assertNull(store.redeem(c,DISCORD));}
    @Test void discordCannotStealSecondMinecraft() throws Exception {
        store.redeem(store.issue(player),DISCORD);UUID second=UUID.randomUUID();String c=store.issue(second);
        assertNull(store.redeem(c,DISCORD));assertEquals(Map.of(player,DISCORD),store.links());
        assertEquals(second,store.redeem(c,"1000000000000000847"));
    }
    @Test void minecraftCannotBeReassigned() throws Exception {store.redeem(store.issue(player),DISCORD);assertThrows(IllegalStateException.class,()->store.issue(player));}
    @Test void malformedAndSqlInjectionRejected() throws Exception {
        for(String c:new String[]{"", "' OR 1=1 --", "abcdef", "z".repeat(20),"a".repeat(10000)})assertNull(store.redeem(c,DISCORD));
        assertNull(store.redeem(store.issue(player),"' OR 1=1 --"));assertTrue(store.links().isEmpty());
    }
    @Test void unlinkAllowsFreshPair() throws Exception {store.redeem(store.issue(player),DISCORD);store.unlink(player);assertEquals(player,store.redeem(store.issue(player),DISCORD));}
    @Test void persistenceSurvivesRestartButCodesDoNot() throws Exception {
        store.redeem(store.issue(player),DISCORD);UUID second=UUID.randomUUID();String code=store.issue(second);store.put("country:test","123");
        store.close();store=new LinkStore(url(),now::get);
        assertEquals(DISCORD,store.links().get(player));assertEquals("123",store.settings().get("country:test"));assertNull(store.redeem(code,"1000000000000000847"));
    }
    @Test void uppercaseCodeAccepted() throws Exception {assertEquals(player,store.redeem(store.issue(player).toUpperCase(Locale.ROOT),DISCORD));}
    @Test void unknownCodeDoesNotInvalidateValidOne() throws Exception {String c=store.issue(player);assertNull(store.redeem("0".repeat(20),DISCORD));assertEquals(player,store.redeem(c,DISCORD));}
}
