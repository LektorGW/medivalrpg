// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class NetworkCommissionTest {
    @TempDir Path root;
    private Path gzip(String text) throws Exception {
        Path path=root.resolve("ops.jsonl.gz");
        try(var out=new GZIPOutputStream(Files.newOutputStream(path))){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}return path;
    }
    private String sha(Path file) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private NetworkCommission.Request request(List<NetworkCommission.Part> parts){return new NetworkCommission.Request(1,true,UUID.randomUUID(),UUID.randomUUID(),"world",1000,parts);}
    @Test void rejectsTraversalDuplicateJobsAndUnboundedBudget() {
        var valid=new NetworkCommission.Part("line01","line01.jsonl.gz","a".repeat(64),500);
        NetworkCommission.validate(request(List.of(valid)));
        assertThrows(IllegalArgumentException.class,()->NetworkCommission.validate(request(List.of(valid,valid))));
        for(String path:List.of("../ops.jsonl.gz","/ops.jsonl.gz","ops.zip","a/b.jsonl.gz"))
            assertThrows(IllegalArgumentException.class,()->NetworkCommission.validate(request(List.of(new NetworkCommission.Part("line01",path,"a".repeat(64),500)))));
        assertThrows(IllegalArgumentException.class,()->NetworkCommission.validate(request(List.of(new NetworkCommission.Part("line01","ops.jsonl.gz","a".repeat(64),1001)))));
    }
    @Test void pinsExactCompressedBytesAndBoundsDecodedOperations() throws Exception {
        String op="{\"x\":1,\"y\":64,\"z\":2,\"part\":\"RAIL\",\"mode\":\"PLACE\"}\n";
        Path file=gzip(op);String expected=sha(file);
        assertEquals(1,NetworkCommission.decode(file,expected,1).size());
        gzip(op+op);
        assertThrows(java.io.IOException.class,()->NetworkCommission.decode(file,expected,10));
        assertThrows(java.io.IOException.class,()->NetworkCommission.decode(file,sha(file),1));
        gzip(op.replace("\"x\":1","\"x\":30000001"));
        assertThrows(java.io.IOException.class,()->NetworkCommission.decode(file,sha(file),10));
    }
    @Test void emptyOrMalformedOperationCannotBecomeAJob() throws Exception {
        for(String value:List.of("","{}\n","{\"x\":1,\"y\":64,\"z\":2,\"mode\":\"PLACE\"}\n")) {
            Path file=gzip(value);assertThrows(java.io.IOException.class,()->NetworkCommission.decode(file,sha(file),10));
        }
    }
    @Test void exactEvidenceCannotAuthorizeOtherCoordinatesOrChangedSourceData() throws Exception {
        String data="minecraft:oak_log[axis=y]";
        String sourceHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String row="{\"xyz\":[1,65,2],\"beforeBlockData\":\""+data+"\",\"beforeSha256\":\""+sourceHash+"\",\"expectedTileEntity\":false}\n";
        Path file=gzip(row);var ops=List.of(pl.medivalrpg.kolej.build.BlockOp.clear(1,65,2));
        assertEquals(Map.of("1:65:2",data),NetworkCommission.decodeEvidence(file,sha(file),false,ops));
        assertThrows(java.io.IOException.class,()->NetworkCommission.decodeEvidence(file,sha(file),false,List.of(pl.medivalrpg.kolej.build.BlockOp.clear(2,65,2))));
        gzip(row.replace("axis=y","axis=x"));
        assertThrows(java.io.IOException.class,()->NetworkCommission.decodeEvidence(file,sha(file),false,ops));
    }
    @Test void existingPaidBatchCannotSilentlyResumeFromAChangedRequest() throws Exception {
        var part=new NetworkCommission.Part("line01","line01.jsonl.gz","a".repeat(64),500);
        var original=request(List.of(part));Path registry=root.resolve("accepted.sha256");
        NetworkCommission.registerSource(registry,original);String signed=Files.readString(registry);
        NetworkCommission.registerSource(registry,original);
        var changed=new NetworkCommission.Request(1,true,original.manager(),original.country(),original.world(),2000,original.parts());
        assertThrows(java.io.IOException.class,()->NetworkCommission.registerSource(registry,changed));
        assertEquals(signed,Files.readString(registry));
    }
    @Test void deploymentHealthGateRequiresTheExactStagedRequest() throws Exception {
        Path marker=root.resolve("start.ready");String approved="a".repeat(64);
        assertFalse(NetworkCommission.startReady(marker,approved));
        Files.writeString(marker,"b".repeat(64));assertFalse(NetworkCommission.startReady(marker,approved));
        Files.writeString(marker,approved+"\n");assertTrue(NetworkCommission.startReady(marker,approved));
        Files.writeString(marker," ".repeat(200)+approved);assertFalse(NetworkCommission.startReady(marker,approved));
    }
    @Test void manualRetryIsBoundedAndCannotRestartAJobOrPayment() {
        for(int retry=0;retry<3;retry++) {
            int used=retry;
            assertDoesNotThrow(()->NetworkCommission.checkPreparationRetry(true,used,false));
            assertThrows(IllegalStateException.class,()->NetworkCommission.checkPreparationRetry(true,used,true));
            assertThrows(IllegalStateException.class,()->NetworkCommission.checkPreparationRetry(false,used,false));
        }
        for(int used:List.of(-1,3,4,Integer.MAX_VALUE))
            assertThrows(IllegalStateException.class,()->NetworkCommission.checkPreparationRetry(true,used,false));
    }
    @Test void retryVerifiesExistingSourceWithoutRecreatingOrRewritingIt() throws Exception {
        var part=new NetworkCommission.Part("preclear01","preclear01.jsonl.gz","a".repeat(64),500,"natural.jsonl.gz","b".repeat(64),null,null);
        var original=request(List.of(part));Path registry=root.resolve("accepted.sha256");
        assertThrows(java.io.IOException.class,()->NetworkCommission.verifySource(registry,original));
        assertFalse(Files.exists(registry));
        NetworkCommission.registerSource(registry,original);byte[] accepted=Files.readAllBytes(registry);
        for(int retry=0;retry<3;retry++)NetworkCommission.verifySource(registry,original);
        assertArrayEquals(accepted,Files.readAllBytes(registry));
        var changedPart=new NetworkCommission.Part(part.id(),part.file(),part.sha256(),part.maxCost(),part.naturalFile(),"c".repeat(64),null,null);
        var changed=new NetworkCommission.Request(1,true,original.manager(),original.country(),original.world(),original.maxTotalMd(),List.of(changedPart));
        assertThrows(java.io.IOException.class,()->NetworkCommission.verifySource(registry,changed));
        assertArrayEquals(accepted,Files.readAllBytes(registry));
        Files.writeString(registry,"d".repeat(64));
        assertThrows(java.io.IOException.class,()->NetworkCommission.verifySource(registry,original));
        assertEquals("d".repeat(64),Files.readString(registry));
    }
}
