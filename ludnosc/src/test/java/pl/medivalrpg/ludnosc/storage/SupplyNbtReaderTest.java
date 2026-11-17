// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupplyNbtReaderTest {
    @TempDir Path temp;
    static final String RECEIPT="d86c8d18-3321-4d73-888c-2423cd1bc168";
    static void name(DataOutputStream d,int tag,String name) throws Exception { d.writeByte(tag); d.writeUTF(name); }
    static void pdc(DataOutputStream d,String name,String receipt) throws Exception {
        name(d,10,name); name(d,8,SupplyNbtReader.KEY); d.writeUTF(receipt); d.writeByte(0);
    }
    static byte[] player(String marker,String location) throws Exception {
        var out=new ByteArrayOutputStream(); var d=new DataOutputStream(out);
        name(d,10,""); pdc(d,location,marker); d.writeByte(0); return out.toByteArray();
    }
    static byte[] chunk(int x,int y,int z,String receipt) throws Exception {
        var out=new ByteArrayOutputStream(); var d=new DataOutputStream(out); name(d,10,"");
        name(d,3,"xPos"); d.writeInt(x>>4); name(d,3,"zPos"); d.writeInt(z>>4);
        name(d,9,"block_entities"); d.writeByte(10); d.writeInt(1);
        name(d,3,"x"); d.writeInt(x); name(d,3,"y"); d.writeInt(y); name(d,3,"z"); d.writeInt(z);
        pdc(d,"PublicBukkitValues",receipt); d.writeByte(0); d.writeByte(0); return out.toByteArray();
    }
    static byte[] zlib(byte[] bytes) throws Exception {
        var out=new ByteArrayOutputStream(); try(var z=new DeflaterOutputStream(out)) { z.write(bytes); } return out.toByteArray();
    }
    void gzip(Path file,byte[] bytes) throws Exception { try(var out=new GZIPOutputStream(Files.newOutputStream(file))) { out.write(bytes); } }
    Path region(int x,int z,byte[] payload,int codec,boolean external) throws Exception {
        int cx=x>>4,cz=z>>4; ByteBuffer file=ByteBuffer.allocate(12288);
        file.putInt(4*((cx&31)+(cz&31)*32),(2<<8)|1); file.position(8192);
        file.putInt(external ? 1 : payload.length+1); file.put((byte)(codec|(external ? 128 : 0)));
        if(!external) file.put(payload); else Files.write(temp.resolve("c."+cx+"."+cz+".mcc"),payload);
        Path target=temp.resolve("r."+(cx>>5)+"."+(cz>>5)+".mca"); Files.write(target,file.array()); return target;
    }
    @Test void actualPlayerGzipMarkerIsReadAndWrongReceiptRejected() throws Exception {
        Path file=temp.resolve("player.dat"); gzip(file,player(RECEIPT,"BukkitValues"));
        assertEquals(RECEIPT,SupplyNbtReader.player(file)); assertTrue(SupplyDiskProof.player(file,RECEIPT)); assertFalse(SupplyDiskProof.player(file,"another"));
    }
    @Test void itemOrForeignPdcCannotForgeThePlayersOwnReceipt() throws Exception {
        Path file=temp.resolve("player.dat"); gzip(file,player(RECEIPT,"Inventory"));
        assertNull(SupplyNbtReader.player(file)); assertFalse(SupplyDiskProof.player(file,RECEIPT));
    }
    @Test void zlibRegionFindsExactBlockEntityIncludingNegativeRegionCoordinates() throws Exception {
        region(-17,-1,zlib(chunk(-17,65,-1,RECEIPT)),2,false);
        assertEquals(RECEIPT,SupplyNbtReader.block(temp,-17,65,-1));
        assertNull(SupplyNbtReader.block(temp,-17,66,-1));
    }
    @Test void externalMccUsesTheReferencedCompressionAndFailsClosedWhenMissing() throws Exception {
        region(32,48,zlib(chunk(32,65,48,RECEIPT)),2,true);
        assertEquals(RECEIPT,SupplyNbtReader.block(temp,32,65,48));
        Files.delete(temp.resolve("c.2.3.mcc")); assertFalse(SupplyDiskProof.block(temp,32,65,48,RECEIPT));
    }
    @Test void unknownCodecAndForeignChunkCoordinatesAreNeverProof() throws Exception {
        region(0,0,zlib(chunk(16,65,0,RECEIPT)),2,false);
        assertFalse(SupplyDiskProof.block(temp,0,65,0,RECEIPT));
        region(0,0,zlib(chunk(0,65,0,RECEIPT)),4,false);
        assertFalse(SupplyDiskProof.block(temp,0,65,0,RECEIPT));
    }
    @Test void missingTruncatedAndOversizedHeadersAreRejected() throws Exception {
        assertFalse(SupplyDiskProof.block(temp,0,65,0,RECEIPT));
        Path file=region(0,0,zlib(chunk(0,65,0,RECEIPT)),2,false);
        byte[] bytes=Files.readAllBytes(file); ByteBuffer.wrap(bytes).putInt(8192,Integer.MAX_VALUE); Files.write(file,bytes);
        assertFalse(SupplyDiskProof.block(temp,0,65,0,RECEIPT));
        Files.write(file,new byte[]{0,1}); assertFalse(SupplyDiskProof.block(temp,0,65,0,RECEIPT));
    }
    @Test void gzipChecksumMustBeValidatedBeforeTrustingAnEarlyMarker() throws Exception {
        Path file=temp.resolve("player.dat"); gzip(file,player(RECEIPT,"BukkitValues"));
        byte[] bytes=Files.readAllBytes(file); bytes[bytes.length-8]^=1; Files.write(file,bytes);
        assertFalse(SupplyDiskProof.player(file,RECEIPT));
    }
    @Test void decompressionAndNbtAllocationBudgetsFailClosed() throws Exception {
        var out=new ByteArrayOutputStream(); var d=new DataOutputStream(out); name(d,10,"");
        name(d,7,"huge"); d.writeInt(Integer.MAX_VALUE); d.writeByte(0);
        Path file=temp.resolve("player.dat"); gzip(file,out.toByteArray()); assertFalse(SupplyDiskProof.player(file,RECEIPT));
        out.reset(); name(d,10,""); for(int i=0;i<70;i++) name(d,10,"deep");
        for(int i=0;i<71;i++) d.writeByte(0); gzip(file,out.toByteArray()); assertFalse(SupplyDiskProof.player(file,RECEIPT));
    }
}
