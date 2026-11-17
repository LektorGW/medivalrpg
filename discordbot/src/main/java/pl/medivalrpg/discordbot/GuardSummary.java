// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import java.util.regex.*;
/** Only known numeric fields cross this boundary; no arbitrary log text can enter Discord. */
public final class GuardSummary {
    private GuardSummary() {}
    public static String extract(String report) {
        StringBuilder out=new StringBuilder();
        for(String label:new String[]{"TPS: średnio","Obywatele:","Żołnierze:","Zabici obywatele (zgony w świecie):"}) {
            Matcher m=Pattern.compile(Pattern.quote(label)+"[ *]*([0-9][0-9 .,/]*)").matcher(report);
            if(m.find())out.append(label).append(' ').append(m.group(1).strip()).append('\n');
        }
        return out.toString();
    }
}
