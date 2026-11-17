// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.*;
import java.io.IOException;
import com.google.gson.JsonParser;
class CommunityLogicTest {
    @TempDir Path dir;
    PresenceFormatter.Line line(String text){return new PresenceFormatter.Line("CUSTOM",text);}
    @Test void slots(){assertEquals("7/40 · 23 · Renesans",PresenceFormatter.format(List.of(line("{online}/{max} · {kraje} · {epoka}")),Map.of("online","7","max","40","kraje","23","epoka","Renesans")).getFirst().text());}
    @Test void unknownSlot(){assertTrue(PresenceFormatter.format(List.of(line("{unknown}")),Map.of()).isEmpty());}
    @Test void emptySlot(){assertTrue(PresenceFormatter.format(List.of(line("{online}")),Map.of("online","")).isEmpty());}
    @Test void zeroWars(){assertEquals(1,PresenceFormatter.format(List.of(line("🔥 {wojny}"),line("Dołącz!")),Map.of("wojny","0")).size());}
    @Test void activeWars(){assertEquals("🔥 2",PresenceFormatter.format(List.of(line("🔥 {wojny}")),Map.of("wojny","2")).getFirst().text());}
    @Test void unknownType(){assertTrue(PresenceFormatter.format(List.of(new PresenceFormatter.Line("BAD","text")),Map.of()).isEmpty());}
    @Test void safePresence(){String s=PresenceFormatter.format(List.of(line("@everyone "+"x".repeat(200))),Map.of()).getFirst().text();assertEquals(128,s.length());assertFalse(s.contains("@"));}
    @Test void permitSingleUse(){DeletionPermit p=new DeletionPermit(()->0L);String c=p.issue("admin","hash");assertTrue(p.consume(c,"admin","hash"));assertFalse(p.consume(c,"admin","hash"));}
    @Test void permitExpiry(){AtomicLong now=new AtomicLong();DeletionPermit p=new DeletionPermit(now::get);String c=p.issue("a","h");now.set(600000);assertFalse(p.consume(c,"a","h"));}
    @Test void permitActorAndPlan(){DeletionPermit p=new DeletionPermit(()->0L);String c=p.issue("a","h");assertFalse(p.consume(c,"other","h"));assertFalse(p.consume(c,"a","changed"));assertTrue(p.consume(c,"a","h"));}
    @Test void newCodeInvalidatesOld(){DeletionPermit p=new DeletionPermit(()->0L);String c=p.issue("a","h"),n=p.issue("a","h");assertFalse(p.consume(c,"a","h"));assertTrue(p.consume(n,"a","h"));}
    @Test void legacyRolesNeverRemoved(){assertTrue(RolePolicy.removable(Set.of("white","join"),Set.of("white","join"),Set.of(),Set.of(),Set.of("white")).isEmpty());}
    @Test void whiteNeverRemoved(){assertTrue(RolePolicy.removable(Set.of("white"),Set.of("white"),Set.of(),Set.of("white"),Set.of("white")).isEmpty());}
    @Test void onlyBotGrantedJoinRemoved(){assertEquals(Set.of("join"),RolePolicy.removable(Set.of("join","foreign"),Set.of("join","foreign"),Set.of("white"),Set.of("join"),Set.of("white")));}
    @Test void grantsPersist()throws Exception {String u="jdbc:sqlite:"+dir.resolve("a.db");try(LinkStore s=new LinkStore(u,()->1L)){s.recordGrant("123","role");}try(LinkStore s=new LinkStore(u,()->2L)){assertEquals(Set.of("role"),s.grantedRoles("123"));s.forgetGrant("123","role");assertTrue(s.grantedRoles("123").isEmpty());}}
    HistoryExporter.Entry entry(){return new HistoryExporter.Entry("12","34","Żółw","2026-10-30T01:00:00Z","Cześć \"świat\"\n\\test\t",List.of("https://example.org/a.png"),List.of("Regulamin"));}
    @Test void exportUtf8JsonAndText()throws Exception {var r=HistoryExporter.write(dir,"Welcome","123",List.of(entry()));assertTrue(r.verify());assertEquals(entry().content(),JsonParser.parseString(Files.readString(r.jsonl())).getAsJsonObject().get("content").getAsString());assertTrue(Files.readString(r.text()).contains("Żółw"));}
    @Test void tampering()throws Exception {var r=HistoryExporter.write(dir,"Welcome","123",List.of(entry()));Files.writeString(r.text(),"changed");assertFalse(r.verify());}
    @Test void writeFailure()throws Exception {Path file=dir.resolve("file");Files.writeString(file,"x");assertThrows(IOException.class,()->HistoryExporter.write(file,"Welcome","123",List.of(entry())));}
    @Test void traversalSafe()throws Exception {var r=HistoryExporter.write(dir,"../../evil","123",List.of(entry()));assertEquals(dir,r.jsonl().getParent());}
    @Test void emptyExport()throws Exception {assertTrue(HistoryExporter.write(dir,"empty","123",List.of()).verify());}
    @Test void renameOnlyAfterTenMinutes(){assertFalse(StatsRenamePolicy.due("a","b",1000,600999));assertTrue(StatsRenamePolicy.due("a","b",1000,601000));}
    @Test void unchangedNameNeverRenamed(){assertFalse(StatsRenamePolicy.due("a","a",0,999999));}
    @Test void reservationSurvivesRestart(){long persisted=700000;assertFalse(StatsRenamePolicy.due("old","new",persisted,800000));}
    @Test void minimumAgeBoundary(){var now=java.time.Instant.parse("2026-10-30T00:00:00Z");assertTrue(VerificationPolicy.oldEnough(now,now,0));assertFalse(VerificationPolicy.oldEnough(now.minusSeconds(7*86400-1),now,7));assertTrue(VerificationPolicy.oldEnough(now.minusSeconds(7*86400),now,7));}
    @Test void missingExportBlocksDeletion(){var called=new java.util.concurrent.atomic.AtomicBoolean();assertThrows(IOException.class,()->DeletionGate.afterExport(true,List.of(),()->called.set(true)));assertFalse(called.get());}
    @Test void tamperedExportBlocksDeletion()throws Exception {var r=HistoryExporter.write(dir,"a","123",List.of(entry()));Files.writeString(r.jsonl(),"changed");var called=new java.util.concurrent.atomic.AtomicBoolean();assertThrows(IOException.class,()->DeletionGate.afterExport(true,List.of(r),()->called.set(true)));assertFalse(called.get());}
    @Test void validExportAllowsFakeDeletion()throws Exception {var r=HistoryExporter.write(dir,"a","123",List.of(entry()));var called=new java.util.concurrent.atomic.AtomicBoolean();DeletionGate.afterExport(true,List.of(r),()->called.set(true));assertTrue(called.get());}
}
