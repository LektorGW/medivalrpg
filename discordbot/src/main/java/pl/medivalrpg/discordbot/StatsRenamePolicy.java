// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
public final class StatsRenamePolicy {
    public static boolean due(String current,String desired,long lastAttempt,long now){return desired!=null&&!current.equals(desired)&&now-lastAttempt>=600_000;}
}
