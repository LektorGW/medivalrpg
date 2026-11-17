// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pure split of rule documents into embed pages. No JDA; text is the owner's file, mentions are disabled on send. */
record RulesBook(List<Page> pages) {
    record Page(String title,String text) {}
    static final int LIMIT=3900,MAX_PAGES=20;
    /** Documents in file-name order; a leading "# Title" line becomes the title of the first page. */
    static RulesBook of(SortedMap<String,String> documents) {
        List<Page> pages=new ArrayList<>();
        for(String raw:documents.values()) {
            String text=raw.replace("\r\n","\n").replace('\r','\n').strip();if(text.isEmpty())continue;
            String title=null;
            if(text.startsWith("# ")){int end=text.indexOf('\n');title=(end<0?text.substring(2):text.substring(2,end)).strip();text=end<0?"":text.substring(end+1).strip();}
            if(title!=null&&title.length()>250)title=title.substring(0,250);
            for(String chunk:split(text)){pages.add(new Page(title,chunk));title=null;}
            if(text.isEmpty()&&title!=null)pages.add(new Page(title,"—"));
        }
        if(pages.size()>MAX_PAGES)throw new IllegalStateException("Regulamin ma ponad "+MAX_PAGES+" stron. Skróć pliki.");
        return new RulesBook(List.copyOf(pages));
    }
    /** Splits at blank lines, then lines, then hard cut; never exceeds the embed limit. */
    static List<String> split(String text) {
        List<String> out=new ArrayList<>();StringBuilder page=new StringBuilder();
        for(String block:text.split("\n\\s*\n")) {
            for(String part:fit(block.strip())) {
                if(page.length()>0&&page.length()+2+part.length()>LIMIT){out.add(page.toString());page.setLength(0);}
                if(page.length()>0)page.append("\n\n");page.append(part);
            }
        }
        if(page.length()>0)out.add(page.toString());return out;
    }
    private static List<String> fit(String block) {
        if(block.length()<=LIMIT)return List.of(block);
        List<String> out=new ArrayList<>();StringBuilder part=new StringBuilder();
        for(String line:block.split("\n")) {
            while(line.length()>LIMIT){if(part.length()>0){out.add(part.toString());part.setLength(0);}out.add(line.substring(0,LIMIT));line=line.substring(LIMIT);}
            if(part.length()>0&&part.length()+1+line.length()>LIMIT){out.add(part.toString());part.setLength(0);}
            if(part.length()>0)part.append('\n');part.append(line);
        }
        if(part.length()>0)out.add(part.toString());return out;
    }
    String fingerprint() {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(Page p:pages)digest.update((Objects.toString(p.title,"")+"\u0000"+p.text+"\u0001").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
