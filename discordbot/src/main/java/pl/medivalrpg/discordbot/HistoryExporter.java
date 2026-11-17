// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.io.IOException;
/** Pure export boundary. The caller may delete a channel ONLY after verify succeeds. */
public final class HistoryExporter {
    public record Entry(String id,String authorId,String author,String date,String content,List<String> attachments,List<String> embeds) {}
    public record Result(Path jsonl,Path text,String jsonSha,String textSha,int count) {
        public boolean verify() throws IOException {return sha(jsonl).equals(jsonSha)&&sha(text).equals(textSha);}
    }
    public static Result write(Path directory,String channelName,String channelId,List<Entry> entries) throws IOException {
        if(!channelId.matches("[0-9]+"))throw new IOException("Nieprawidłowe ID kanału.");
        Files.createDirectories(directory);String base=LayoutPlanner.slug(channelName)+"-"+channelId+"-"+UUID.randomUUID();
        Path json=directory.resolve(base+".jsonl"),txt=directory.resolve(base+".txt");
        StringBuilder lines=new StringBuilder(),readable=new StringBuilder("Kanał: "+channelName+" ["+channelId+"]\nWiadomości: "+entries.size()+"\n\n");
        for(Entry e:entries) {
            lines.append("{\"id\":").append(quote(e.id)).append(",\"authorId\":").append(quote(e.authorId)).append(",\"author\":").append(quote(e.author))
                .append(",\"date\":").append(quote(e.date)).append(",\"content\":").append(quote(e.content)).append(",\"attachments\":").append(array(e.attachments))
                .append(",\"embeds\":").append(array(e.embeds)).append("}\n");
            readable.append(e.date).append(" | ").append(e.author).append(" [").append(e.authorId).append("]\n").append(e.content).append('\n');
            e.embeds.forEach(s->readable.append(s).append('\n'));e.attachments.forEach(s->readable.append(s).append('\n'));readable.append('\n');
        }
        durable(json,lines.toString());durable(txt,readable.toString());
        Result result=new Result(json,txt,sha(json),sha(txt),entries.size());if(!result.verify())throw new IOException("Weryfikacja eksportu nie powiodła się.");return result;
    }
    private static String array(List<String> v){return "["+String.join(",",v.stream().map(HistoryExporter::quote).toList())+"]";}
    static String quote(String s) {
        StringBuilder out=new StringBuilder("\"");for(char c:s.toCharArray())switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}return out.append('"').toString();
    }
    private static void durable(Path file,String text) throws IOException {
        Path temp=file.resolveSibling(file.getFileName()+".tmp");byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        try(FileChannel c=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())c.write(buffer);c.force(true);}
        Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);
    }
    private static String sha(Path p) throws IOException {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
