// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.*;

/** Bounded read-only vanilla persistence proof. No NMS, Bukkit calls or writes. Unknown formats fail closed. */
public final class SupplyNbtReader {
    public static final String KEY="ludnosc:supply_receipt";
    private static final int MAX_COMPRESSED=8*1024*1024, MAX_DECOMPRESSED=32*1024*1024;
    private SupplyNbtReader() { }
    public static String player(Path file) throws IOException {
        if(Files.size(file)>MAX_COMPRESSED) throw new IOException("Zbyt duży plik gracza.");
        try(var raw=Files.newInputStream(file); var gzip=new GZIPInputStream(raw); var reader=new Nbt(gzip)) {
            reader.root(); String marker=null; boolean found=false;
            for(int type;(type=reader.tag())!=0;) {
                String name=reader.in.readUTF();
                if(type==10 && name.equals("BukkitValues")) { if(found) throw new IOException("Powtórzone BukkitValues."); found=true; marker=reader.pdc(1); }
                else reader.skip(type,1);
            }
            reader.finish(); return marker;
        }
    }
    public static String block(Path regionDirectory, int x, int y, int z) throws IOException {
        int cx=x>>4,cz=z>>4;
        Path file=regionDirectory.resolve("r."+(cx>>5)+"."+(cz>>5)+".mca");
        byte[] bytes; int codec;
        try(var channel=FileChannel.open(file,StandardOpenOption.READ)) {
            ByteBuffer header=ByteBuffer.allocate(4); read(channel,header,4L*((cx&31)+(cz&31)*32)); header.flip();
            int location=header.getInt(), sector=location>>>8, count=location&255;
            if(sector<2 || count==0) throw new IOException("Brak zapisanego chunka.");
            long offset=(long)sector*4096;
            if(offset+5>channel.size()) throw new IOException("Niepełny region.");
            ByteBuffer size=ByteBuffer.allocate(5); read(channel,size,offset); size.flip(); int length=size.getInt(); codec=size.get()&255;
            if(length<1 || length>MAX_COMPRESSED+1 || length+4L>count*4096L || offset+length+4>channel.size()) throw new IOException("Nieprawidłowy nagłówek chunka.");
            if((codec&128)!=0) {
                if(length!=1) throw new IOException("Nieprawidłowy zewnętrzny chunk.");
                Path external=regionDirectory.resolve("c."+cx+"."+cz+".mcc");
                if(Files.size(external)>MAX_COMPRESSED) throw new IOException("Zbyt duży zewnętrzny chunk.");
                bytes=Files.readAllBytes(external); codec&=127;
            } else {
                ByteBuffer payload=ByteBuffer.allocate(length-1); read(channel,payload,offset+5); bytes=payload.array();
            }
            // Concurrent replacement/append is unknown, never accepted as proof.
            ByteBuffer again=ByteBuffer.allocate(4); read(channel,again,4L*((cx&31)+(cz&31)*32)); again.flip();
            if(again.getInt()!=location) throw new IOException("Region zmienił się podczas odczytu.");
        }
        InputStream compressed=new ByteArrayInputStream(bytes);
        InputStream decoded=switch(codec) { case 1 -> new GZIPInputStream(compressed); case 2 -> new InflaterInputStream(compressed); case 3 -> compressed; default -> throw new IOException("Nieobsługiwany format regionu."); };
        try(var reader=new Nbt(decoded)) {
            reader.root(); String marker=null; Integer storedX=null,storedZ=null; boolean found=false;
            for(int type;(type=reader.tag())!=0;) {
                String name=reader.in.readUTF();
                if(type==3 && name.equals("xPos")) storedX=reader.in.readInt();
                else if(type==3 && name.equals("zPos")) storedZ=reader.in.readInt();
                else if(type==9 && name.equals("block_entities")) {
                    int child=reader.tag(), n=reader.length(4096);
                    if(child!=10 && n!=0) throw new IOException("Nieprawidłowa lista block_entities.");
                    for(int i=0;i<n;i++) {
                        Integer bx=null,by=null,bz=null; String receipt=null;
                        for(int tag;(tag=reader.tag())!=0;) {
                            String key=reader.in.readUTF();
                            if(tag==3 && key.equals("x")) bx=reader.in.readInt();
                            else if(tag==3 && key.equals("y")) by=reader.in.readInt();
                            else if(tag==3 && key.equals("z")) bz=reader.in.readInt();
                            else if(tag==10 && key.equals("PublicBukkitValues")) receipt=reader.pdc(2);
                            else reader.skip(tag,2);
                        }
                        if(bx!=null && by!=null && bz!=null && bx==x && by==y && bz==z) {
                            if(found) throw new IOException("Powtórzona encja bloku."); found=true; marker=receipt;
                        }
                    }
                } else reader.skip(type,1);
            }
            if(storedX==null || storedZ==null || storedX!=cx || storedZ!=cz) throw new IOException("Region zawiera inny chunk.");
            reader.finish(); return marker;
        }
    }
    private static void read(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while(buffer.hasRemaining()) { int n=channel.read(buffer,position); if(n<=0) throw new EOFException(); position+=n; }
    }
    private static final class Bounded extends FilterInputStream {
        private long read;
        Bounded(InputStream in) { super(in); }
        private void add(long n) throws IOException { if(n>0 && (read+=n)>MAX_DECOMPRESSED) throw new IOException("Przekroczony budżet NBT."); }
        @Override public int read() throws IOException { int n=super.read(); if(n>=0) add(1); return n; }
        @Override public int read(byte[] b,int off,int len) throws IOException { int n=in.read(b,off,len); add(n); return n; }
        @Override public long skip(long n) throws IOException { long skipped=in.skip(Math.min(n,8192)); add(skipped); return skipped; }
    }
    private static final class Nbt implements AutoCloseable {
        final DataInputStream in; int tags;
        Nbt(InputStream stream) { in=new DataInputStream(new Bounded(stream)); }
        int tag() throws IOException { if(++tags>500_000) throw new IOException("Zbyt wiele tagów NBT."); int type=in.readUnsignedByte(); if(type>12) throw new IOException("Nieznany tag NBT."); return type; }
        void root() throws IOException { if(tag()!=10) throw new IOException("Brak compound NBT."); in.readUTF(); }
        void finish() throws IOException { if(in.read()!=-1) throw new IOException("Nadmiarowe dane po NBT."); }
        int length(int max) throws IOException { int n=in.readInt(); if(n<0 || n>max) throw new IOException("Nieprawidłowy rozmiar NBT."); return n; }
        String pdc(int depth) throws IOException {
            String marker=null;
            for(int type;(type=tag())!=0;) {
                String name=in.readUTF();
                if(name.equals(KEY) && type==8) { if(marker!=null) throw new IOException("Powtórzone pokwitowanie."); marker=in.readUTF(); }
                else skip(type,depth+1);
            }
            return marker;
        }
        void bytes(long n) throws IOException { while(n>0) { int k=in.skipBytes((int)Math.min(n,8192)); if(k==0) { in.readByte(); k=1; } n-=k; } }
        void skip(int type,int depth) throws IOException {
            if(depth>64 || ++tags>500_000) throw new IOException("Przekroczony budżet struktury NBT.");
            switch(type) {
                case 0 -> { } case 1 -> bytes(1); case 2 -> bytes(2); case 3,5 -> bytes(4); case 4,6 -> bytes(8);
                case 7 -> bytes(length(MAX_DECOMPRESSED)); case 8 -> in.readUTF();
                case 9 -> { int child=tag(), n=length(500_000); if(child==0 && n>0) throw new IOException("Nieprawidłowa lista NBT."); for(int i=0;i<n;i++) skip(child,depth+1); }
                case 10 -> { for(int child;(child=tag())!=0;) { in.readUTF(); skip(child,depth+1); } }
                case 11 -> bytes((long)length(MAX_DECOMPRESSED/4)*4); case 12 -> bytes((long)length(MAX_DECOMPRESSED/8)*8);
                default -> throw new IOException("Nieznany tag NBT.");
            }
        }
        public void close() throws IOException { in.close(); }
    }
}
