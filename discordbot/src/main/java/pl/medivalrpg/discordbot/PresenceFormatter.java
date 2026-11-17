// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import java.util.*;
import java.util.regex.*;
public final class PresenceFormatter {
    public record Line(String type,String text) {}
    private static final Pattern SLOT=Pattern.compile("\\{([^{}]+)}");
    public static List<Line> format(List<Line> lines,Map<String,String> values) {
        List<Line> out=new ArrayList<>();
        for(Line line:lines) {
            if(!Set.of("PLAYING","CUSTOM").contains(line.type()))continue;
            Matcher m=SLOT.matcher(line.text());StringBuilder text=new StringBuilder();boolean valid=true;
            while(m.find()) {
                String value=values.get(m.group(1));
                if(value==null||value.isBlank()||m.group(1).equals("wojny")&&value.equals("0")){valid=false;break;}
                m.appendReplacement(text,Matcher.quoteReplacement(value));
            }
            if(valid) {m.appendTail(text);String result=Policy.clean(text.toString(),128).strip();if(!result.isEmpty())out.add(new Line(line.type(),result));}
        }
        return List.copyOf(out);
    }
}
